package io.github.nitsuya.aa.display.aap

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import io.github.duzhaokun123.template.bases.BaseActivity
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.ActivityAapCertBinding
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain
import java.io.File

/**
 * AAP 身份证书管理。
 *
 * 支持：查看当前身份、从已安装的 AA 重新抽取、从 APK 文件抽取、导入 PEM、
 * 从 iscle 在线仓库下载、保存/切换/删除密钥库身份、取消固定、导出。
 * 所有私钥只落在本应用私有目录，不会上传。
 *
 * 注意：本 ROM 上 AlertDialog 的列表/按钮不可靠，所以这里全部用页面内联控件，
 * 不做确认/输入对话框；删除采用“点两次确认”。
 */
class AapCertActivity : BaseActivity<ActivityAapCertBinding>(ActivityAapCertBinding::class.java) {

    private var busy = false
    private var deleteArmed = false

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        extractFromUris(uris)
    }

    private val pickPem = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        importPemFromUris(uris)
    }

    override fun initViews() {
        setTitle(R.string.aap_cert_title)
        bindActions()
        refreshStatus()
    }

    override fun initData() {
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun bindActions() {
        baseBinding.btnExtractInstalled.setOnClickListener {
            if (AapIdentity.gearheadApkPaths(this).isNullOrEmpty()) {
                toast(getString(R.string.aap_cert_failed, getString(R.string.status_aa_not_installed)))
                return@setOnClickListener
            }
            withBusy {
                AapIdentity.extractFromInstalledGearhead(this)
                "carservice（AA 升级后自动重抽）"
            }
        }
        baseBinding.btnExtractApk.setOnClickListener {
            pickApk.launch(arrayOf("*/*"))
        }
        baseBinding.btnImportPem.setOnClickListener {
            pickPem.launch(arrayOf("*/*"))
        }
        baseBinding.btnDownload.setOnClickListener {
            withBusy {
                AapIdentity.downloadCarServiceIdentity(this)
                "downloaded"
            }
        }
        baseBinding.btnSaveProfile.setOnClickListener {
            val label = baseBinding.etProfileName.text?.toString().orEmpty()
            val profile = AapIdentity.saveProfile(this, label)
            if (profile == null) {
                toast(getString(R.string.aap_cert_failed, getString(R.string.aap_cert_no_identity)))
            } else {
                baseBinding.etProfileName.setText("")
                toast(getString(R.string.aap_cert_done, profile.label))
                refreshStatus()
            }
        }
        baseBinding.btnUnpin.setOnClickListener {
            AapIdentity.unpin(this)
            toast(getString(R.string.aap_cert_done, getString(R.string.aap_cert_unpin)))
            refreshStatus()
        }
        baseBinding.btnExport.setOnClickListener {
            exportPem()
        }
        baseBinding.btnDelete.setOnClickListener {
            if (!deleteArmed) {
                deleteArmed = true
                baseBinding.btnDelete.setText(R.string.aap_cert_delete_again)
                baseBinding.btnDelete.postDelayed({
                    deleteArmed = false
                    baseBinding.btnDelete.setText(R.string.aap_cert_delete)
                }, 3_000L)
                return@setOnClickListener
            }
            deleteArmed = false
            baseBinding.btnDelete.setText(R.string.aap_cert_delete)
            AapIdentity.delete(this)
            toast(getString(R.string.aap_cert_deleted))
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        val status = AapIdentity.status(this)
        baseBinding.tvCertStatus.text = if (!status.available) {
            getString(R.string.aap_cert_status_none)
        } else {
            getString(
                R.string.aap_cert_status_fmt,
                status.source,
                status.subject,
                status.notAfter,
                status.keyBits,
                if (status.hasKey) "有私钥" else "无私钥",
                status.modulusFingerprint,
                if (AapIdentity.isPinned(this)) getString(R.string.aap_cert_pinned) else "",
            )
        }
        baseBinding.btnExport.isEnabled = status.available && status.hasKey
        baseBinding.btnUnpin.isEnabled = AapIdentity.isPinned(this)
        renderProfiles()
    }

    /** 密钥库列表直接内联在页面上：每行提供“应用/删除”，不用任何对话框。 */
    private fun renderProfiles() {
        val container = baseBinding.llProfiles
        container.removeAllViews()
        val profiles = AapIdentity.listProfiles(this)
        baseBinding.tvProfilesEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        val density = resources.displayMetrics.density
        val padV = (8 * density).toInt()
        profiles.forEach { profile ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, padV, 0, padV)
            }
            val info = TextView(this).apply {
                text = "${profile.label}\n${profile.source} · ${profile.modulusFingerprint}"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val apply = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = getString(R.string.aap_cert_apply)
                isAllCaps = false
                minWidth = 0
                setOnClickListener {
                    if (AapIdentity.applyProfile(this@AapCertActivity, profile.id)) {
                        toast(getString(R.string.aap_cert_done, profile.label))
                    } else {
                        toast(getString(R.string.aap_cert_failed, "切换失败"))
                    }
                    refreshStatus()
                }
            }
            var armed = false
            val delete = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = getString(R.string.aap_cert_profile_delete)
                isAllCaps = false
                minWidth = 0
                setOnClickListener {
                    if (!armed) {
                        armed = true
                        setText(R.string.aap_cert_delete_again)
                        postDelayed({
                            armed = false
                            setText(R.string.aap_cert_profile_delete)
                        }, 3_000L)
                        return@setOnClickListener
                    }
                    AapIdentity.deleteProfile(this@AapCertActivity, profile.id)
                    toast(getString(R.string.aap_cert_deleted))
                    refreshStatus()
                }
            }
            row.addView(info)
            row.addView(apply)
            row.addView(delete)
            container.addView(row)
            container.addView(android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(0x1F000000)
            })
        }
    }

    private fun withBusy(work: () -> String) {
        if (busy) return
        busy = true
        setButtonsEnabled(false)
        toast(getString(R.string.aap_cert_working))
        runIO {
            val outcome = runCatching { work() }
            runMain {
                if (isFinishing || isDestroyed) return@runMain
                busy = false
                setButtonsEnabled(true)
                outcome.onSuccess { label ->
                    toast(getString(R.string.aap_cert_done, label))
                }.onFailure {
                    toast(getString(R.string.aap_cert_failed, it.message ?: it.javaClass.simpleName))
                }
                refreshStatus()
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        listOf(
            baseBinding.btnExtractInstalled, baseBinding.btnExtractApk, baseBinding.btnImportPem,
            baseBinding.btnDownload, baseBinding.btnSaveProfile, baseBinding.btnUnpin,
            baseBinding.btnExport, baseBinding.btnDelete,
        ).forEach { it.isEnabled = enabled }
    }

    private fun extractFromUris(uris: List<Uri>) {
        withBusy {
            val files = uris.mapIndexed { index, uri -> copyToCache(uri, "aap-import-$index") }
            try {
                AapIdentity.extractFromApkFiles(this, files.map { it.absolutePath })
            } finally {
                files.forEach { runCatching { it.delete() } }
            }
            "carservice-manual"
        }
    }

    private fun importPemFromUris(uris: List<Uri>) {
        withBusy {
            val texts = uris.map { uri ->
                contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    ?: throw IllegalArgumentException("无法读取 ${uri.lastPathSegment}")
            }
            val keyText = texts.firstOrNull { it.contains("PRIVATE KEY") }
                ?: texts.firstOrNull()?.let(::extractPrivateKeyBlock)
                ?: throw IllegalArgumentException("没有找到 PEM 私钥")
            val certText = texts.firstOrNull { it.contains("BEGIN CERTIFICATE") && it !== keyText }
                ?: extractCertificateBlock(keyText)
                ?: throw IllegalArgumentException("没有找到 X.509 证书")
            AapIdentity.importPem(this, keyText.toByteArray(), certText.toByteArray())
            "imported"
        }
    }

    private fun extractPrivateKeyBlock(text: String): String? =
        Regex("-----BEGIN (?:RSA )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA )?PRIVATE KEY-----")
            .find(text)?.value

    private fun extractCertificateBlock(text: String): String? =
        Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
            .find(text)?.value

    private fun copyToCache(uri: Uri, baseName: String): File {
        val dir = File(cacheDir, "aap-cert").apply { mkdirs() }
        val file = File(dir, "$baseName-${System.nanoTime()}")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalArgumentException("无法读取 ${uri.lastPathSegment}")
        return file
    }

    private fun exportPem() {
        val pair = AapIdentity.exportPem(this) ?: run {
            toast(getString(R.string.aap_cert_status_none))
            return
        }
        val text = pair.first + "\n" + pair.second
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "AADisplay AAP identity")
        }
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.aap_cert_export))) }
            .onFailure { toast(getString(R.string.aap_cert_failed, it.message.orEmpty())) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
