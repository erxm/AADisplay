package io.github.nitsuya.aa.display.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.topjohnwu.superuser.Shell
import io.github.duzhaokun123.template.bases.BaseActivity
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.aap.AapConsts
import io.github.nitsuya.aa.display.aap.AapEgress
import io.github.nitsuya.aa.display.aap.AapEgressService
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding
import io.github.nitsuya.aa.display.ui.setting.SettingsActivity
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.template.bases.getAttr
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain

class MainActivity :
    BaseActivity<ActivityMainBinding>(
        ActivityMainBinding::class.java,
        Config.NO_BACK,
        Config.LAYOUT_MATCH_HORI
    ),
    MenuProvider {
    companion object {
        const val TAG = "AADisplay_MainActivity"
        private const val AA_PACKAGE = "com.google.android.projection.gearhead"
        private const val REQ_WIRELESS = 42
        // 只请求无线连接真正必需的权限：蓝牙连接 + 附近 Wi-Fi。
        // BLUETOOTH_SCAN / 定位只是“发现/读 SSID”的优化项，缺少也能连接，避免一次弹四个框。
        private val WIRELESS_PERMISSIONS = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
    }

    private data class LauncherOption(
        val packageName: String,
        val label: String
    ) {
        val displayName: String
            get() = if (label.equals(packageName, ignoreCase = true)) {
                packageName
            } else {
                "$label ($packageName)"
            }
    }

    private data class DelayOption(
        val seconds: Int,
        val label: String
    )

    private val appConfig by lazy {
        SharedPreferencesAccess.openForHooks(this, AADisplayConfig.ConfigName)
    }

    private var launcherOptions: List<LauncherOption> = emptyList()
    private var launcherDisplayToPackage: Map<String, String> = emptyMap()
    private var delayOptions: List<DelayOption> = emptyList()
    private var delayDisplayToSeconds: Map<String, Int> = emptyMap()
    private var defaultHomePackage: String? = null
    private var savedLauncherPackage: String? = null
    private var savedDelayDestroyTime: Int = 180
    private var savedAutoOpen: Boolean = false

    /** 程序化设置单选/开关时不要回写配置或触发连接。 */
    private var suppressRouteCallbacks = false

    /** AAP 连接状态每秒刷新一次（服务在同进程，可直接读 AapEgress）。 */
    private val routeHandler = Handler(Looper.getMainLooper())
    private val routeStatusTick = object : Runnable {
        override fun run() {
            refreshAapStatus()
            routeHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ActivityMainBinding.inflate(LayoutInflater.from(this))
        super.onCreate(savedInstanceState)
        SharedPreferencesAccess.makeReadableForHooks(this, AADisplayConfig.ConfigName)
        addMenuProvider(this, this)
    }

    override fun initViews() {
        super.initViews()
        setupSettingControls()
        setupRouteControls()
    }

    @SuppressLint("SetTextI18n")
    override fun initData() {
        val buildTime = CoreApi.buildTime
        Log.d(TAG, "buildtime: $buildTime ${BuildConfig.BUILD_TIME}")
        when (buildTime) {
            0L -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_error_outline_24)
                baseBinding.tvActive.setText(R.string.not_activated)
                baseBinding.tvVersion.text = aaVersionText()
                val colorError = android.R.attr.colorError
                val colorOnError = theme.getAttr(com.google.android.material.R.attr.colorOnError).data
                baseBinding.mcvStatus.setCardBackgroundColor(colorError)
                baseBinding.mcvStatus.outlineAmbientShadowColor = colorError
                baseBinding.mcvStatus.outlineSpotShadowColor = colorError
                baseBinding.tvActive.setTextColor(colorOnError)
                baseBinding.tvVersion.setTextColor(colorOnError)
            }

            BuildConfig.BUILD_TIME -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_round_check_circle_24)
                baseBinding.tvActive.setText(R.string.activated)
                baseBinding.tvVersion.text =
                    "${CoreApi.versionName}\n${aaVersionText()}"
            }

            else -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_warning_amber_24)
                baseBinding.tvActive.setText(R.string.need_reboot)
                baseBinding.tvVersion.text =
                    "${BuildConfig.VERSION_NAME}\n${aaVersionText()}"
                baseBinding.mcvStatus.setCardBackgroundColor(
                    MaterialColors.harmonizeWithPrimary(this, getColor(R.color.color_warning))
                )
                baseBinding.mcvStatus.setOnClickListener {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.need_reboot)
                        .setPositiveButton(R.string.reboot) { _, _ ->
                            Shell.getShell().newJob().add("reboot").exec()
                        }
                        .show()
                }
            }
        }

        if (Build.VERSION.PREVIEW_SDK_INT != 0) {
            baseBinding.systemVersion.text =
                "${Build.VERSION.CODENAME} Preview (API ${Build.VERSION.SDK_INT})"
        } else {
            baseBinding.systemVersion.text = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }
        // Shell.getShell() 会阻塞到 su 授权完成，放到 IO 线程，避免启动/返回时卡主线程。
        baseBinding.rootPrivilege.setText(R.string.info_no)
        runIO {
            val isRoot = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
            runMain {
                if (!isFinishing && !isDestroyed) {
                    baseBinding.rootPrivilege.setText(
                        if (isRoot) R.string.info_yes else R.string.info_no
                    )
                }
            }
        }
        warmShellLaunchers()
        refreshSettingControls()
        refreshRouteCard()
    }

    override fun onResume() {
        super.onResume()
        refreshSettingControls()
        refreshRouteCard()
        routeHandler.removeCallbacks(routeStatusTick)
        routeHandler.post(routeStatusTick)
    }

    override fun onPause() {
        routeHandler.removeCallbacks(routeStatusTick)
        super.onPause()
    }

    /**
     * 状态卡第二行显示手机上装的 Android Auto 版本。
     *
     * gearhead 的 versionName 形如 17.3.662854-release，尾巴上的 channel 后缀对用户没
     * 意义，截掉只留版本号。包名已经写在 manifest 的 <queries> 里，所以 targetSdk 30+
     * 的包可见性限制不会挡住这次查询。
     */
    private fun aaVersionText(): String {
        val versionName = try {
            packageManager.getPackageInfo(AA_PACKAGE, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return if (versionName.isNullOrBlank()) {
            getString(R.string.status_aa_not_installed)
        } else {
            getString(R.string.status_aa_version, versionName.substringBefore('-'))
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_main, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.settings -> {
                openAdvancedSettings()
                true
            }

            R.id.github -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = "https://github.com/erxm/AADisplay".toUri()
                })
                true
            }

            else -> false
        }
    }

    private fun openAdvancedSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun setupSettingControls() {
        baseBinding.switchAutoOpen.setOnCheckedChangeListener { _, _ ->
            updateSaveButtonState()
        }

        baseBinding.actvLauncherPackage.apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            threshold = 0
            isCursorVisible = false
            isLongClickable = false
            showSoftInputOnFocus = false
            setTextIsSelectable(false)
            setOnClickListener {
                showAllLauncherOptions()
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showAllLauncherOptions()
                }
                if (!hasFocus) {
                    validateLauncherSelection()
                }
            }
            setOnItemClickListener { _, _, _, _ ->
                validateLauncherSelection()
                updateSaveButtonState()
            }
        }

        baseBinding.actvDelayDestroyTime.apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            threshold = 0
            isCursorVisible = false
            isLongClickable = false
            showSoftInputOnFocus = false
            setTextIsSelectable(false)
            setOnClickListener {
                showAllDelayOptions()
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showAllDelayOptions()
                }
                if (!hasFocus) {
                    validateDelaySelection(showError = true)
                }
            }
            setOnItemClickListener { _, _, _, _ ->
                validateDelaySelection(showError = false)
                updateSaveButtonState()
            }
        }

        baseBinding.btnSave.setOnClickListener {
            persistSettings()
        }
    }

    // ------------------------------------------------------------ 投屏路线 / AAP

    private fun setupRouteControls() {
        baseBinding.toggleRoute.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressRouteCallbacks) return@addOnButtonCheckedListener
            val route = if (checkedId == R.id.btn_route_aap) {
                AapConsts.ROUTE_AAP
            } else {
                AapConsts.ROUTE_LEGACY
            }
            applyRouteSelection(route, fromUser = true)
        }

        baseBinding.switchAapAuto.setOnCheckedChangeListener { _, checked ->
            if (suppressRouteCallbacks) return@setOnCheckedChangeListener
            appConfig.edit().putBoolean(AADisplayConfig.AapEnabled.key, checked).commit()
            SharedPreferencesAccess.makeReadableForHooks(this, AADisplayConfig.ConfigName)
            if (checked && AapEgressService.route(this) == AapConsts.ROUTE_AAP) {
                ensureWirelessPermissions()
                AapEgressService.start(this)
            } else if (!checked) {
                AapEgressService.stop(this)
            }
            refreshAapStatus()
        }

        baseBinding.btnAapConnect.setOnClickListener {
            // 无线 AAP 需要蓝牙/附近设备权限；缺什么就请求什么，USB 不受影响。
            ensureWirelessPermissions()
            AapEgressService.start(this)
            Toast.makeText(this, R.string.aap_connect_requested, Toast.LENGTH_SHORT).show()
            refreshAapStatus()
        }

        baseBinding.btnAapStop.setOnClickListener {
            AapEgressService.stop(this)
            Toast.makeText(this, R.string.aap_stop_requested, Toast.LENGTH_SHORT).show()
            refreshAapStatus()
        }
    }

    private fun refreshRouteCard() {
        val aap = AADisplayConfig.AapRoute.get(appConfig) == AapConsts.ROUTE_AAP
        suppressRouteCallbacks = true
        baseBinding.toggleRoute.check(if (aap) R.id.btn_route_aap else R.id.btn_route_legacy)
        baseBinding.switchAapAuto.isChecked = AADisplayConfig.AapEnabled.get(appConfig)
        suppressRouteCallbacks = false
        baseBinding.llAapControls.visibility = if (aap) View.VISIBLE else View.GONE
        baseBinding.tvRouteHint.setText(if (aap) R.string.route_aap_hint else R.string.route_legacy_hint)
        refreshAapStatus()
    }

    /** 路线切换立即落盘；切到 AAP 时按“自动连接”决定是否马上发起一次连接。 */
    private fun applyRouteSelection(route: String, fromUser: Boolean) {
        val aap = route == AapConsts.ROUTE_AAP
        if (fromUser) {
            appConfig.edit()
                .putString(AADisplayConfig.AapRoute.key, route)
                .commit()
            SharedPreferencesAccess.makeReadableForHooks(this, AADisplayConfig.ConfigName)
            if (!aap) {
                // 两条路线不要同时占用车机：切回原版时结束 AAP 会话。
                AapEgressService.stop(this)
            }
            Toast.makeText(
                this,
                if (aap) R.string.route_switched_to_aap else R.string.route_switched_to_legacy,
                Toast.LENGTH_SHORT,
            ).show()
        }
        suppressRouteCallbacks = true
        baseBinding.toggleRoute.check(if (aap) R.id.btn_route_aap else R.id.btn_route_legacy)
        suppressRouteCallbacks = false
        baseBinding.llAapControls.visibility = if (aap) View.VISIBLE else View.GONE
        baseBinding.tvRouteHint.setText(if (aap) R.string.route_aap_hint else R.string.route_legacy_hint)
        refreshAapStatus()
        if (fromUser && aap && AADisplayConfig.AapEnabled.get(appConfig)) {
            ensureWirelessPermissions()
            AapEgressService.start(this)
        }
    }

    private fun refreshAapStatus() {
        val running = AapEgress.isRunning
        baseBinding.tvAapStatus.text = if (running) {
            getString(R.string.aap_status_connected, AapEgress.transportName ?: "AAP")
        } else {
            getString(R.string.aap_status_disconnected)
        }
        baseBinding.btnAapConnect.isEnabled = !running
        baseBinding.btnAapStop.isEnabled = running
    }

    private fun ensureWirelessPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val missing = WIRELESS_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_WIRELESS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WIRELESS) {
            // 授权完成后，如果仍在 AAP 路线且开着自动连接，补发一次连接请求。
            if (AapEgressService.route(this) == AapConsts.ROUTE_AAP &&
                AADisplayConfig.AapEnabled.get(appConfig)
            ) {
                AapEgressService.start(this)
            }
            refreshAapStatus()
        }
    }

    private fun refreshSettingControls() {
        savedAutoOpen = AADisplayConfig.AutoOpen.get(appConfig)
        savedLauncherPackage = AADisplayConfig.LauncherPackage.get(appConfig)?.trim().orEmpty()
        savedDelayDestroyTime = AADisplayConfig.DelayDestroyTime.get(appConfig)
        baseBinding.switchAutoOpen.isChecked = savedAutoOpen

        detectLauncherEnvironment()
        bindLauncherDropdown()
        bindDelayDropdown()

        val configuredLauncher = savedLauncherPackage.orEmpty()
        val selected = launcherOptions.firstOrNull { it.packageName == configuredLauncher }
            ?: launcherOptions.firstOrNull { it.packageName != defaultHomePackage }
            ?: launcherOptions.firstOrNull()
        if (selected != null) {
            baseBinding.actvLauncherPackage.setText(selected.displayName, false)
        } else {
            baseBinding.actvLauncherPackage.setText("", false)
        }

        val selectedDelay = delayOptions.firstOrNull { it.seconds == savedDelayDestroyTime }
            ?: delayOptions.firstOrNull()
        if (selectedDelay != null) {
            baseBinding.actvDelayDestroyTime.setText(selectedDelay.label, false)
        } else {
            baseBinding.actvDelayDestroyTime.setText("", false)
        }

        validateLauncherSelection()
        validateDelaySelection(showError = false)
        updateSaveButtonState()
    }

    private fun detectLauncherEnvironment() {
        val pm = packageManager
        launcherOptions = queryLauncherOptions(pm)

        defaultHomePackage = resolveDefaultHomePackageFallback(pm)

        val homeDisplay = defaultHomePackage?.let { pkg ->
            launcherOptions.firstOrNull { it.packageName == pkg }?.displayName
                ?: runCatching {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    "${pm.getApplicationLabel(appInfo)} ($pkg)"
                }.getOrDefault(pkg)
        }

        baseBinding.tvDefaultHome.text = if (!homeDisplay.isNullOrBlank()) {
            getString(R.string.default_home_detected, homeDisplay)
        } else {
            getString(R.string.default_home_unknown)
        }

    }

    private fun bindLauncherDropdown() {
        launcherDisplayToPackage = launcherOptions.associate { it.displayName to it.packageName }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            launcherOptions.map { it.displayName }
        )
        baseBinding.actvLauncherPackage.setAdapter(adapter)
    }

    private fun bindDelayDropdown() {
        delayOptions = listOf(
            DelayOption(60, getString(R.string.delay_60_seconds)),
            DelayOption(120, getString(R.string.delay_120_seconds)),
            DelayOption(180, getString(R.string.delay_180_seconds))
        )
        delayDisplayToSeconds = delayOptions.associate { it.label to it.seconds }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            delayOptions.map { it.label }
        )
        baseBinding.actvDelayDestroyTime.setAdapter(adapter)
    }

    private fun showAllLauncherOptions() {
        @Suppress("UNCHECKED_CAST")
        val adapter = baseBinding.actvLauncherPackage.adapter as? ArrayAdapter<String> ?: return
        // Force-reset filtering so the popup always shows all launcher choices.
        adapter.filter.filter(null)
        baseBinding.actvLauncherPackage.showDropDown()
    }

    private fun showAllDelayOptions() {
        @Suppress("UNCHECKED_CAST")
        val adapter = baseBinding.actvDelayDestroyTime.adapter as? ArrayAdapter<String> ?: return
        adapter.filter.filter(null)
        baseBinding.actvDelayDestroyTime.showDropDown()
    }

    private fun queryLauncherOptions(pm: PackageManager): List<LauncherOption> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pmCandidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
        }

        val pmOptions = pmCandidates
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val pkg = activityInfo.packageName?.trim().orEmpty()
                val cls = activityInfo.name?.trim().orEmpty()
                if (!isLauncherCandidateAllowed(pkg, cls)) return@mapNotNull null
                val label = resolveInfo.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                LauncherOption(pkg, label)
            }
            .distinctBy { it.packageName }

        val mergedByPackage = linkedMapOf<String, LauncherOption>()
        pmOptions.forEach { mergedByPackage[it.packageName] = it }

        queryLauncherPackagesViaShell().forEach { pkg ->
            if (mergedByPackage.containsKey(pkg)) return@forEach
            val label = runCatching {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString().trim().ifBlank { pkg }
            }.getOrDefault(pkg)
            mergedByPackage[pkg] = LauncherOption(pkg, label)
        }

        return mergedByPackage.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun isLauncherCandidateAllowed(pkg: String, cls: String): Boolean {
        if (pkg.isBlank()) return false
        if (
            pkg == "android" ||
            pkg == "com.android.internal.app" ||
            pkg == "com.android.settings" ||
            cls.contains("FallbackHome", ignoreCase = true) ||
            cls.contains("ResolverActivity", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    /** root shell 查询结果缓存：查询本身会阻塞等 su，不能放在主线程。 */
    @Volatile
    private var cachedShellLaunchers: List<String>? = null
    @Volatile
    private var shellLauncherWarmInFlight = false

    private fun queryLauncherPackagesViaShell(): List<String> = cachedShellLaunchers ?: emptyList()

    private fun warmShellLaunchers() {
        if (cachedShellLaunchers != null || shellLauncherWarmInFlight) return
        shellLauncherWarmInFlight = true
        runIO {
            val list = queryLauncherPackagesViaShellBlocking()
            cachedShellLaunchers = list
            shellLauncherWarmInFlight = false
            runMain {
                // 结果回来后重建一次下拉框；期间用户可能已经离开页面。
                if (!isFinishing && !isDestroyed) refreshSettingControls()
            }
        }
    }

    private fun queryLauncherPackagesViaShellBlocking(): List<String> {
        if (!Shell.getShell().isRoot) return emptyList()

        val output = runCatching {
            Shell.getShell().newJob()
                .add("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
                .exec()
                .out
        }.getOrDefault(emptyList())

        val packageNames = linkedSetOf<String>()
        for (line in output) {
            val trimmed = line.trim()
            if (!trimmed.contains("/")) continue
            val parts = trimmed.split("/", limit = 2)
            if (parts.size != 2) continue
            val pkg = parts[0].trim()
            val cls = parts[1].trim()
            if (!isLauncherCandidateAllowed(pkg, cls)) continue
            packageNames.add(pkg)
        }
        return packageNames.toList()
    }

    private fun resolveDefaultHomePackageFallback(pm: PackageManager): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        } ?: return null

        val pkg = resolveInfo.activityInfo?.packageName ?: return null
        val cls = resolveInfo.activityInfo?.name.orEmpty()
        return if (
            pkg == "android" ||
            pkg == "com.android.internal.app" ||
            cls.contains("ResolverActivity", ignoreCase = true)
        ) {
            null
        } else {
            pkg
        }
    }

    private fun resolveSelectedLauncherPackage(): String? {
        val raw = baseBinding.actvLauncherPackage.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null
        launcherDisplayToPackage[raw]?.let { return it }
        return launcherOptions.firstOrNull { it.packageName == raw }?.packageName
    }

    private fun resolveSelectedDelaySeconds(): Int? {
        val raw = baseBinding.actvDelayDestroyTime.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null
        delayDisplayToSeconds[raw]?.let { return it }
        return delayOptions.firstOrNull { it.label == raw }?.seconds
    }

    private fun validateLauncherSelection(): Boolean {
        val selectedPackage = resolveSelectedLauncherPackage()

        if (launcherOptions.isEmpty()) {
            baseBinding.tilLauncherPackage.error = getString(R.string.launcher_list_missing)
            hideValidationMessage()
            clearLaunchPackageValidState()
            return false
        }

        if (launcherOptions.size <= 1) {
            baseBinding.tilLauncherPackage.error = getString(R.string.single_launcher_warning)
            hideValidationMessage()
            clearLaunchPackageValidState()
            return false
        }

        if (selectedPackage.isNullOrBlank()) {
            baseBinding.tilLauncherPackage.error = getString(R.string.default_launch_package_empty)
            hideValidationMessage()
            clearLaunchPackageValidState()
            return false
        }

        if (selectedPackage == defaultHomePackage) {
            val errorText = getString(R.string.package_same_error)
            baseBinding.tilLauncherPackage.error = errorText
            hideValidationMessage()
            clearLaunchPackageValidState()
            return false
        }

        baseBinding.tilLauncherPackage.error = null
        applyLaunchPackageValidState()
        showValidationMessage(getString(R.string.package_validation_ok), true)
        return true
    }

    private fun validateDelaySelection(showError: Boolean): Boolean {
        val value = resolveSelectedDelaySeconds()
        val valid = value != null && delayOptions.any { it.seconds == value }
        if (!valid && showError) {
            baseBinding.tilDelayDestroyTime.error = getString(R.string.delay_destroy_time_invalid)
        } else if (valid) {
            baseBinding.tilDelayDestroyTime.error = null
        }
        return valid
    }

    private fun persistSettings() {
        val launcherValid = validateLauncherSelection()
        val delayValid = validateDelaySelection(showError = true)
        if (!launcherValid || !delayValid) {
            updateSaveButtonState()
            return
        }

        val launcherPackage = resolveSelectedLauncherPackage() ?: return
        val delay = resolveSelectedDelaySeconds() ?: return

        val settingsSaved = appConfig.edit()
            .putBoolean(AADisplayConfig.AutoOpen.key, baseBinding.switchAutoOpen.isChecked)
            .putString(AADisplayConfig.LauncherPackage.key, launcherPackage)
            .putString(AADisplayConfig.HomePackage.key, launcherPackage)
            .putString(AADisplayConfig.DelayDestroyTime.key, delay.toString())
            .commit()

        val readableForHooks = SharedPreferencesAccess.makeReadableForHooks(this, AADisplayConfig.ConfigName)
        if (!settingsSaved || !readableForHooks) {
            Toast.makeText(this, getString(R.string.settings_saved_pref_access_failed), Toast.LENGTH_LONG).show()
            updateSaveButtonState()
            return
        }

        savedAutoOpen = baseBinding.switchAutoOpen.isChecked
        savedLauncherPackage = launcherPackage
        savedDelayDestroyTime = delay
        Toast.makeText(this, getString(R.string.settings_saved_successfully), Toast.LENGTH_SHORT).show()
        updateSaveButtonState()
    }

    private fun updateSaveButtonState() {
        val hasChanges = hasPendingChanges()
        val enabled = hasChanges &&
            validateLauncherSelection() &&
            validateDelaySelection(showError = false)
        baseBinding.btnSave.isEnabled = enabled
        baseBinding.btnSave.alpha = if (enabled) 1f else 0.6f
    }

    private fun hasPendingChanges(): Boolean {
        val currentAutoOpen = baseBinding.switchAutoOpen.isChecked
        val currentLauncher = resolveSelectedLauncherPackage()
        val currentDelay = resolveSelectedDelaySeconds()
        return currentAutoOpen != savedAutoOpen ||
            currentLauncher != savedLauncherPackage ||
            currentDelay != savedDelayDestroyTime
    }

    private fun showValidationMessage(message: String, success: Boolean) {
        val color = if (success) {
            getColor(android.R.color.holo_green_dark)
        } else {
            MaterialColors.getColor(baseBinding.tvPackageValidation, android.R.attr.colorError)
        }
        baseBinding.tvPackageValidation.visibility = View.VISIBLE
        baseBinding.tvPackageValidation.text = message
        baseBinding.tvPackageValidation.setTextColor(color)
    }

    private fun hideValidationMessage() {
        baseBinding.tvPackageValidation.visibility = View.GONE
    }

    private fun applyLaunchPackageValidState() {
        val successColor = getColor(android.R.color.holo_green_dark)
        baseBinding.tilLauncherPackage.endIconMode = TextInputLayout.END_ICON_CUSTOM
        baseBinding.tilLauncherPackage.endIconDrawable = AppCompatResources.getDrawable(this, R.drawable.ic_round_check_circle_24)
        baseBinding.tilLauncherPackage.setEndIconTintList(ColorStateList.valueOf(successColor))
        baseBinding.tilLauncherPackage.boxStrokeColor = successColor
    }

    private fun clearLaunchPackageValidState() {
        baseBinding.tilLauncherPackage.endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        if (baseBinding.tilLauncherPackage.error.isNullOrBlank()) {
            baseBinding.tilLauncherPackage.boxStrokeColor =
                MaterialColors.getColor(baseBinding.tilLauncherPackage, com.google.android.material.R.attr.colorOutline)
        }
    }
}
