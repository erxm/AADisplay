package io.github.nitsuya.aa.display.aap

import android.content.Context
import android.util.Base64
import android.util.Log
import io.github.nitsuya.aa.display.aap.cert.AaApkKeyExtractor
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.cert.SelfSignedCertificate
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * 手机身份（证书 + 私钥）。
 *
 * 优先级：
 * 1. **CarService 证书**——从已安装的 Android Auto（gearhead）APK 里抽 Google 签发的证书与私钥
 *    （[AaApkKeyExtractor]，AARE extract_key.py 的 Kotlin 移植）。原厂 Google 库车机与 DHU
 *    都只认这条链，自签证书会在 AAP 认证阶段被拒（status -3）。
 * 2. **自签证书**——没装 gearhead 或抽取失败时的兜底；只有不校验证书链的车机能用。
 *
 * 两者都落盘到 `filesDir/aap/`，并用 `source` 文件记录来源与 gearhead 版本：
 * gearhead 升级后证书可能失效，届时自动重抽。
 */
object AapIdentity {

    private const val TAG = AapConsts.TAG

    private const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
    private const val SOURCE_FILE = "source"
    private const val SOURCE_CARSERVICE = "carservice"
    // 手动从 APK 文件抽取的 CarService 证书：不随本机 gearhead 版本变化失效，
    // 允许从主力机导出的 gearhead APK / PEM 在没有装 AA 的手机上复用。
    private const val SOURCE_CARSERVICE_MANUAL = "carservice-manual"
    // 用户导入的 PEM 密钥对（可能来自另一台手机），永远视为有效。
    private const val SOURCE_IMPORTED = "imported"
    // 从在线证书仓库下载的 CarService 身份（iscle/android-auto-certificates，
    // 持续从最新 AA APK 抽取）：Google 签发、不依赖本机 gearhead，永远视为有效。
    private const val SOURCE_DOWNLOADED = "downloaded"
    private const val SOURCE_SELF_SIGNED = "selfsigned"

    // 密钥库：多组身份（vault/<slot>/{phone.crt,phone.key,source,meta}）+ 手动切换。
    // 切换后写 pinned 标记，loadOrCreate 直接用当前身份、跳过 gearhead 自动重抽
    // （gearhead 升级/卸载不会覆盖用户手动选择的身份）；「取消固定」恢复自动行为。
    private const val PROFILE_DIR = "vault"
    private const val META_FILE = "meta"
    private const val PIN_FILE = "pinned"
    private const val ACTIVE_FILE = "active_profile"

    // 在线证书仓库（与 AARE「Download certificate」同源）：jsDelivr 镜像国内可达，raw 兜底。
    private const val REPO_LATEST = "android-auto/latest"
    private val REPO_MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/iscle/android-auto-certificates@main/$REPO_LATEST",
        "https://raw.githubusercontent.com/iscle/android-auto-certificates/main/$REPO_LATEST",
    )

    /** 当前落盘身份的可展示信息（供设置页密钥管理用）。 */
    data class Status(
        val available: Boolean,
        val source: String,
        val subject: String,
        val modulusFingerprint: String,
        val notBefore: String,
        val notAfter: String,
        val keyBits: Int,
        val hasKey: Boolean,
    )

    fun dir(context: Context): File = File(context.filesDir, AapConsts.IDENTITY_DIR).apply { mkdirs() }

    /** 读取当前身份的展示信息；没有有效证书时 available=false。 */
    fun status(context: Context): Status {
        val dir = dir(context)
        val source = runCatching { File(dir, SOURCE_FILE).readText().trim() }.getOrDefault("")
        val cert = runCatching { readCert(File(dir, AapConsts.CERT_FILE)) }.getOrNull()
        val keyBits = runCatching {
            (readKey(File(dir, AapConsts.KEY_FILE)) as? java.security.interfaces.RSAPrivateCrtKey)
                ?.modulus?.bitLength() ?: 0
        }.getOrDefault(0)
        return if (cert == null) {
            Status(false, source.ifBlank { "none" }, "", "", "", "", 0, false)
        } else {
            Status(
                available = true,
                source = source,
                subject = cert.subjectX500Principal.name,
                modulusFingerprint = modulusFingerprint(cert),
                notBefore = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(cert.notBefore),
                notAfter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    .format(cert.notAfter),
                keyBits = keyBits,
                hasKey = keyBits > 0,
            )
        }
    }

    /** 证书公钥模数的指纹（与 [AaApkKeyExtractor.modulusFingerprint] 同算法，可互相对照）。 */
    fun modulusFingerprint(cert: X509Certificate): String {
        val key = cert.publicKey as java.security.interfaces.RSAPublicKey
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.modulus.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    /** 删除落盘身份（下次 [loadOrCreate] 会按正常优先级重新生成）。 */
    fun delete(context: Context) {
        val dir = dir(context)
        for (name in arrayOf(AapConsts.CERT_FILE, AapConsts.KEY_FILE, SOURCE_FILE, PIN_FILE, ACTIVE_FILE)) {
            runCatching { File(dir, name).delete() }
        }
        Log.i(TAG, "已删除本地 AAP 身份文件")
    }

    /**
     * 从本机已安装的 Android Auto 重新抽取证书，并按 gearhead 的 versionCode 打标签。
     * 与 [loadOrCreate] 的自动路径一致：之后 AA 升级会再次自动重抽。
     * 供设置页「从已安装的 Android Auto 重新提取」使用。
     */
    fun extractFromInstalledGearhead(context: Context): PhoneIdentity {
        val paths = gearheadApkPaths(context)
            ?: throw IllegalStateException("未安装 Android Auto")
        val version = gearheadVersionCode(context)
        val result = AaApkKeyExtractor.extractFromApkPaths(paths)
        val key = parsePrivateKeyPem(result.privateKeyPem)
            ?: throw IllegalStateException("抽取成功但私钥无法解析")
        val dir = dir(context)
        persist(
            File(dir, AapConsts.CERT_FILE), File(dir, AapConsts.KEY_FILE),
            File(dir, SOURCE_FILE), result.certificate, key, carserviceTag(version),
        )
        Log.i(
            TAG,
            "已从 gearhead 重新抽取 CarService 证书（${result.sourceLabel}，modulus=${result.modulusFingerprint}）",
        )
        return PhoneIdentity(result.certificate, key)
    }

    /**
     * 从给定 APK/APKM/DEX 文件抽取 CarService 证书并落盘（source=carservice-manual:<label>）。
     * 供设置页“从 APK 文件提取”与调试入口（adb 传 apk_path）使用。
     */
    fun extractFromApkFiles(context: Context, apkPaths: List<String>): PhoneIdentity {
        val result = AaApkKeyExtractor.extractFromApkPaths(apkPaths)
        val key = parsePrivateKeyPem(result.privateKeyPem)
            ?: throw AaApkKeyExtractor.ExtractError.ModulusMismatch()
        val source = "$SOURCE_CARSERVICE_MANUAL:${result.sourceLabel}"
        val dir = dir(context)
        persist(
            File(dir, AapConsts.CERT_FILE), File(dir, AapConsts.KEY_FILE),
            File(dir, SOURCE_FILE), result.certificate, key, source,
        )
        Log.i(TAG, "已从 APK 抽取 CarService 证书（${result.sourceLabel}，modulus=${result.modulusFingerprint}）")
        return PhoneIdentity(result.certificate, key)
    }

    /** 导入 PEM/DER 私钥与证书，校验模数一致后落盘（source=imported）。 */
    fun importPem(context: Context, keyContent: ByteArray, certContent: ByteArray): PhoneIdentity {
        val (cert, key) = parsePair(keyContent, certContent)
        val dir = dir(context)
        persist(
            File(dir, AapConsts.CERT_FILE), File(dir, AapConsts.KEY_FILE),
            File(dir, SOURCE_FILE), cert, key, SOURCE_IMPORTED,
        )
        Log.i(TAG, "已导入 AAP 密钥对（modulus=${modulusFingerprint(cert)}）")
        return PhoneIdentity(cert, key)
    }

    /**
     * 从 iscle/android-auto-certificates 在线下载 CarService 身份（key+cert）并落盘
     * （source=downloaded:<AA 版本>）。AARE 的「Download certificate」同源：仓库持续从
     * 最新 Android Auto APK 抽取，车机拒签（status -3）时一键更新，无需本机装 gearhead。
     * 校验：X.509 解析 + RSA CRT 私钥 + 模数匹配 + 签发者必须是 Google Automotive Link。
     */
    fun downloadCarServiceIdentity(context: Context): PhoneIdentity {
        val keyPem = fetchRepoText("private-key.pem")
            ?.takeIf { it.contains("-----BEGIN") }
            ?: throw java.io.IOException("私钥下载失败或内容不是 PEM")
        val certPem = fetchRepoText("certificate.pem")
            ?.takeIf { it.contains("-----BEGIN") }
            ?: throw java.io.IOException("证书下载失败或内容不是 PEM")
        val version = fetchRepoText("metadata.json")
            ?.let { runCatching { org.json.JSONObject(it).optString("version") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
        val (cert, key) = parsePair(keyPem.toByteArray(), certPem.toByteArray())
        val issuer = cert.issuerX500Principal.name
        require(issuer.contains("Google Automotive Link")) { "证书签发者不是 Google Automotive Link：$issuer" }
        val dir = dir(context)
        persist(
            File(dir, AapConsts.CERT_FILE), File(dir, AapConsts.KEY_FILE),
            File(dir, SOURCE_FILE), cert, key, "$SOURCE_DOWNLOADED:$version",
        )
        Log.i(TAG, "已从在线仓库下载 CarService 证书（AA $version，modulus=${modulusFingerprint(cert)}）")
        return PhoneIdentity(cert, key)
    }

    /** 当前证书是否在 N 天内到期（车机会直接拒签过期证书）；没有落盘证书时 false。 */
    fun expiringSoon(context: Context, days: Int = 14): Boolean {
        val cert = runCatching { readCert(File(dir(context), AapConsts.CERT_FILE)) }.getOrNull() ?: return false
        val left = cert.notAfter.time - System.currentTimeMillis()
        return left in 0..days * 86_400_000L
    }

    /** 解析 key+cert（PEM 或 DER），要求 RSA CRT 且模数一致；任何一步不合法都抛 IllegalArgumentException。 */
    private fun parsePair(keyContent: ByteArray, certContent: ByteArray): Pair<X509Certificate, PrivateKey> {
        val key = runCatching {
            val der = if (keyContent.decodeToString().contains("-----BEGIN")) {
                Base64.decode(stripPem(keyContent.decodeToString()), Base64.DEFAULT)
            } else keyContent
            java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
        }.getOrElse { throw IllegalArgumentException("私钥格式无法解析（需要 PKCS#8 PEM 或 DER）：$it") }
        val cert = runCatching {
            val factory = CertificateFactory.getInstance("X.509")
            if (certContent.decodeToString().contains("-----BEGIN")) {
                // 逐段解析：多段 PEM 拼一起的 base64 无法直接 decode（两段 DER 相连不是合法流）。
                val certs = pemBlocks(certContent.decodeToString()).mapNotNull { block ->
                    runCatching {
                        factory.generateCertificate(java.io.ByteArrayInputStream(block.toByteArray())) as X509Certificate
                    }.getOrNull()
                }
                certs.firstOrNull { it.subjectX500Principal.name.contains("CarService") } ?: certs.firstOrNull()
            } else {
                factory.generateCertificate(java.io.ByteArrayInputStream(certContent)) as X509Certificate
            } ?: throw IllegalArgumentException("证书流里没有可解析的 X.509 证书")
        }.getOrElse { throw IllegalArgumentException("证书格式无法解析（需要 X.509 PEM 或 DER）：$it") }
        val keyMod = (key as? java.security.interfaces.RSAPrivateCrtKey)?.modulus
            ?: throw IllegalArgumentException("不是 RSA CRT 私钥")
        val certMod = (cert.publicKey as? java.security.interfaces.RSAPublicKey)?.modulus
            ?: throw IllegalArgumentException("证书不是 RSA")
        if (keyMod != certMod) throw IllegalArgumentException("私钥与证书模数不匹配")
        return cert to key
    }

    /** 依次尝试镜像与源站抓取仓库文本文件；全部失败返回 null（失败原因逐个进日志）。 */
    private fun fetchRepoText(name: String): String? {
        for (base in REPO_MIRRORS) {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URL("$base/$name").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                if (conn.responseCode != 200) {
                    Log.w(TAG, "在线证书仓库 $base/$name -> HTTP ${conn.responseCode}")
                    continue
                }
                return conn.inputStream.use { it.readBytes().decodeToString() }
            } catch (t: Throwable) {
                Log.w(TAG, "在线证书仓库抓取失败（$base/$name）：${t.javaClass.simpleName}: ${t.message}")
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    /** 导出 PEM：pair(first=私钥 PEM, second=证书 PEM)；没有落盘身份时 null。 */
    fun exportPem(context: Context): Pair<String, String>? {
        val dir = dir(context)
        val certBytes = runCatching { File(dir, AapConsts.CERT_FILE).readBytes() }.getOrNull() ?: return null
        val keyBytes = runCatching { File(dir, AapConsts.KEY_FILE).readBytes() }.getOrNull() ?: return null
        val keyPem = "-----BEGIN PRIVATE KEY-----\n" +
            android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
                .chunked(64).joinToString("\n") + "\n-----END PRIVATE KEY-----\n"
        val certPem = "-----BEGIN CERTIFICATE-----\n" +
            android.util.Base64.encodeToString(certBytes, android.util.Base64.NO_WRAP)
                .chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
        return keyPem to certPem
    }

    // ---------- 密钥库（多组身份 + 手动切换） ----------

    /** 密钥库里一组可切换身份的描述。 */
    data class KeyProfile(
        val id: String,
        val label: String,
        val source: String,
        val modulusFingerprint: String,
        val notAfter: String,
        val savedAt: Long,
    )

    private fun vaultDir(context: Context): File = File(dir(context), PROFILE_DIR).apply { mkdirs() }

    /** 当前身份是否被固定（密钥库手动切换后不随 gearhead 升级自动重抽）。 */
    fun isPinned(context: Context): Boolean = File(dir(context), PIN_FILE).isFile

    /** 取消固定，恢复自动刷新（gearhead 升级重抽 / 无 gearhead 回自签）。 */
    fun unpin(context: Context) {
        runCatching { File(dir(context), PIN_FILE).delete() }
        Log.i(TAG, "已取消固定，恢复自动刷新")
    }

    /** 把当前身份保存进密钥库；当前没有落盘身份时 null。 */
    fun saveProfile(context: Context, label: String): KeyProfile? {
        val runtime = dir(context)
        val certFile = File(runtime, AapConsts.CERT_FILE)
        val keyFile = File(runtime, AapConsts.KEY_FILE)
        val cert = runCatching { readCert(certFile) }.getOrNull() ?: return null
        val source = runCatching { File(runtime, SOURCE_FILE).readText().trim() }.getOrDefault("")
        if (!keyFile.isFile || source.isBlank()) return null
        val name = label.trim().ifBlank { "未命名身份" }
        val slot = File(vaultDir(context), "p${System.currentTimeMillis()}")
        runCatching {
            slot.mkdirs()
            File(slot, AapConsts.CERT_FILE).writeBytes(certFile.readBytes())
            File(slot, AapConsts.KEY_FILE).writeBytes(keyFile.readBytes())
            File(slot, SOURCE_FILE).writeText(source)
            File(slot, META_FILE).writeText(
                listOf(
                    name,
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .format(java.util.Date()),
                ).joinToString("\n"),
            )
        }.onFailure {
            Log.w(TAG, "保存密钥到密钥库失败", it)
            return null
        }
        val profile = KeyProfile(
            id = slot.name,
            label = name,
            source = source,
            modulusFingerprint = modulusFingerprint(cert),
            notAfter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(cert.notAfter),
            savedAt = slot.lastModified(),
        )
        Log.i(TAG, "已保存密钥到密钥库（${profile.id}，modulus=${profile.modulusFingerprint}）")
        return profile
    }

    /** 列出密钥库里的全部身份（按保存时间倒序）。 */
    fun listProfiles(context: Context): List<KeyProfile> = vaultDir(context)
        .listFiles { f -> f.isDirectory }
        ?.mapNotNull { slot ->
            val meta = runCatching { File(slot, META_FILE).readText() }.getOrNull() ?: return@mapNotNull null
            val cert = runCatching { readCert(File(slot, AapConsts.CERT_FILE)) }.getOrNull() ?: return@mapNotNull null
            val source = runCatching { File(slot, SOURCE_FILE).readText().trim() }.getOrDefault("")
            KeyProfile(
                id = slot.name,
                label = meta.split('\n').getOrNull(0)?.trim().orEmpty().ifBlank { "未命名身份" },
                source = source,
                modulusFingerprint = modulusFingerprint(cert),
                notAfter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(cert.notAfter),
                savedAt = slot.lastModified(),
            )
        }
        ?.sortedByDescending { it.savedAt }
        ?: emptyList()

    /**
     * 应用密钥库身份：覆盖当前落盘身份并写 pinned 标记。
     * 当前身份若未保存进密钥库会被覆盖，调用方（UI）应先提示。
     */
    fun applyProfile(context: Context, id: String): Boolean {
        val slot = File(vaultDir(context), id)
        val runtime = dir(context)
        if (!slot.isDirectory) return false
        val copied = runCatching {
            copyTo(File(slot, AapConsts.CERT_FILE), File(runtime, AapConsts.CERT_FILE))
            copyTo(File(slot, AapConsts.KEY_FILE), File(runtime, AapConsts.KEY_FILE))
            copyTo(File(slot, SOURCE_FILE), File(runtime, SOURCE_FILE))
            File(runtime, PIN_FILE).writeText(id)
            File(runtime, ACTIVE_FILE).writeText(id)
        }.isSuccess
        if (copied) {
            val source = runCatching { File(runtime, SOURCE_FILE).readText().trim() }.getOrDefault("")
            Log.i(TAG, "已切换到密钥库身份 $id（$source，固定=true）")
        }
        return copied
    }

    /** 删除密钥组；若是当前应用的组，取消固定但保留运行时身份。 */
    fun deleteProfile(context: Context, id: String) {
        runCatching { File(vaultDir(context), id).deleteRecursively() }
        val active = runCatching { File(dir(context), ACTIVE_FILE).readText().trim() }.getOrNull()
        if (active == id) {
            runCatching { File(dir(context), ACTIVE_FILE).delete() }
            runCatching { File(dir(context), PIN_FILE).delete() }
            Log.i(TAG, "已删除当前应用的密钥组 $id，取消固定（运行时身份保留）")
        }
        Log.i(TAG, "已删除密钥组 $id")
    }

    private fun copyTo(from: File, to: File) {
        from.inputStream().use { input -> to.outputStream().use { input.copyTo(it) } }
    }

    private fun stripPem(pem: String): String = pem.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("-----") }
        .joinToString("")

    /** 拆出 PEM 里的每一段证书（iscle 仓库的 certificate.pem 是「叶子 + GAL 根」两段相连）。 */
    private fun pemBlocks(pem: String): List<String> =
        Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
            .findAll(pem).map { it.value }.toList()

    private fun readCert(file: File): X509Certificate? {
        if (!file.isFile) return null
        return java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(file.inputStream()) as? X509Certificate
    }

    private fun readKey(file: File): PrivateKey? {
        if (!file.isFile) return null
        return java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(file.readBytes()))
    }

    fun loadOrCreate(context: Context): PhoneIdentity {
        val dir = File(context.filesDir, AapConsts.IDENTITY_DIR).apply { mkdirs() }
        val certFile = File(dir, AapConsts.CERT_FILE)
        val keyFile = File(dir, AapConsts.KEY_FILE)
        val sourceFile = File(dir, SOURCE_FILE)

        val gearheadApks = gearheadApkPaths(context)
        val gearheadVersion = gearheadApks?.let { gearheadVersionCode(context) }
        val cachedSource = runCatching { sourceFile.readText().trim() }.getOrNull()
        val cached = runCatching { read(certFile, keyFile) }
            .onFailure { Log.w(TAG, "读取已存证书失败，重新生成", it) }
            .getOrNull()

        // 密钥库手动切换后固定：当前身份直接用，不参与 gearhead 自动刷新。
        if (File(dir, PIN_FILE).isFile && cached != null) {
            Log.i(TAG, "身份已固定（密钥库切换），跳过自动重抽（source=${cachedSource ?: "unknown"}）")
            return cached
        }

        if (cached != null && cachedSource != null) {
            val stillValid = when {
                // 手动抽取 / 在线下载 / 导入的身份永远有效（除非用户在设置里删除）
                cachedSource.startsWith("$SOURCE_CARSERVICE_MANUAL:") ||
                    cachedSource.startsWith(SOURCE_DOWNLOADED) ||
                    cachedSource.startsWith(SOURCE_IMPORTED) -> true
                // 自动抽取的 CarService 证书跟 gearhead 版本绑定，升级后重抽
                cachedSource.startsWith("$SOURCE_CARSERVICE:") ->
                    gearheadApks != null && cachedSource == carserviceTag(gearheadVersion)
                // 自签证书只在没有 gearhead 可抽的情况下继续用
                else -> gearheadApks == null
            }
            if (stillValid) return cached
        }

        if (gearheadApks != null) {
            runCatching { AaApkKeyExtractor.extractFromApkPaths(gearheadApks) }
                .onSuccess { result ->
                    val key = parsePrivateKeyPem(result.privateKeyPem)
                    if (key != null) {
                        persist(certFile, keyFile, sourceFile, result.certificate, key, carserviceTag(gearheadVersion))
                        Log.i(TAG, "已从 gearhead 抽取 CarService 证书（${result.sourceLabel}，modulus=${result.modulusFingerprint}）")
                        return PhoneIdentity(result.certificate, key)
                    }
                    Log.w(TAG, "CarService 证书已抽取但私钥解析失败")
                }
                .onFailure { Log.w(TAG, "从 gearhead 抽取 CarService 证书失败", it) }
            // AA 升级后抽取失败（例如新版把 key blob 挪了位置）时，绝不能立刻用自签证书
            // 覆盖仍然可用的旧证书——那会让原本能连的车机直接拒签。保留旧身份并保持旧
            // source tag，这样下一次会话仍会继续尝试从新版 AA 重抽。
            if (cached != null) {
                Log.w(TAG, "抽取失败，沿用已存身份（source=${cachedSource ?: "unknown"}）")
                return cached
            }
        }

        val generated = SelfSignedCertificate.generate("AADisplay local")
        persist(certFile, keyFile, sourceFile, generated.certificate, generated.keyPair.private, SOURCE_SELF_SIGNED)
        Log.i(TAG, "已生成本地自签证书")
        return PhoneIdentity(generated.certificate, generated.keyPair.private)
    }

    private fun carserviceTag(versionCode: Long?): String = "$SOURCE_CARSERVICE:${versionCode ?: -1}"

    private fun persist(
        certFile: File,
        keyFile: File,
        sourceFile: File,
        cert: X509Certificate,
        key: PrivateKey,
        source: String,
    ) {
        runCatching {
            certFile.writeBytes(cert.encoded)
            keyFile.writeBytes(key.encoded)
            sourceFile.writeText(source)
        }.onFailure { Log.w(TAG, "写入证书失败（下次会重新生成）", it) }
    }

    private fun read(certFile: File, keyFile: File): PhoneIdentity? {
        if (!certFile.isFile || !keyFile.isFile) return null
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certFile.inputStream()) as? X509Certificate ?: return null
        val key = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        return PhoneIdentity(cert, key)
    }

    private fun parsePrivateKeyPem(pem: String): PrivateKey? {
        val base64 = pem.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") }
            .joinToString("")
        if (base64.isEmpty()) return null
        val der = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /** gearhead 的 APK 路径（base + split），没装则 null。 */
    fun gearheadApkPaths(context: Context): List<String>? = runCatching {
        val info = context.packageManager.getApplicationInfo(GEARHEAD_PACKAGE, 0)
        buildList {
            add(info.sourceDir)
            info.splitSourceDirs?.forEach { add(it) }
        }.filter { it.isNotBlank() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun gearheadVersionCode(context: Context): Long? = runCatching {
        context.packageManager.getPackageInfo(GEARHEAD_PACKAGE, 0).longVersionCode
    }.getOrNull()
}
