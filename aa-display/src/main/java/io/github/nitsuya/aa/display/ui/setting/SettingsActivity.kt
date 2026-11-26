package io.github.nitsuya.aa.display.ui.setting

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.duzhaokun123.template.bases.BaseActivity
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.ActivitySettingsBinding
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.GoogleMapsOnAaManager
import io.github.nitsuya.aa.display.util.HeadUnitServerController
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.aa.display.util.WazeOnAaManager

/**
 * 高级设置：把 AADisplayConfig 里的键位直接暴露成偏好项。
 *
 * 关键点是配置文件必须仍然能被 Xposed 侧读到 —— PreferenceManager 自己去
 * getSharedPreferences，所以这里提前探测出可用的 mode 交给它，并在每次改动后
 * 补一次 makeReadableForHooks（MODE_WORLD_READABLE 不可用时会退化成放宽文件权限）。
 */
class SettingsActivity : BaseActivity<ActivitySettingsBinding>(ActivitySettingsBinding::class.java) {

    override fun initViews() {
        setTitle(R.string.more_settings_title)
        if (isFirstCreate) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fl_root, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // preferenceTheme 由 Theme.AADisplay 提供（PreferenceFragmentCompat 在
            // onCreate 阶段就解析完了，这里再 applyStyle 已经来不及）
            preferenceManager.apply {
                sharedPreferencesName = AADisplayConfig.ConfigName
                sharedPreferencesMode =
                    SharedPreferencesAccess.resolveModeForHooks(requireContext(), AADisplayConfig.ConfigName)
            }
            setPreferencesFromResource(R.xml.pref_aadisplay_config, rootKey)
            bindAppProjectionSwitches()
            bindHeadUnitServerActions()
            // ListPreference 自带的单选对话框在本 ROM 上列表项点不动。
            // 选项移到独立的 ChoiceActivity 页面。
            bindChoicePages()
            findPreference<Preference>("AapCertManage")?.setOnPreferenceClickListener {
                startActivity(
                    android.content.Intent(requireContext(), io.github.nitsuya.aa.display.aap.AapCertActivity::class.java)
                )
                true
            }
        }

        /**
         * 车机服务器是纯动作项（不落盘）：点击后由 [HeadUnitServerController]
         * 通过 gearhead 进程内的 hook 启动/停止 AA 的 5277 服务。
         */
        private fun bindHeadUnitServerActions() {
            bindHeadUnitAction("HeadUnitServerStart", stop = false)
            bindHeadUnitAction("HeadUnitServerStop", stop = true)
        }

        private fun bindHeadUnitAction(key: String, stop: Boolean) {
            val preference = findPreference<Preference>(key) ?: return
            preference.setOnPreferenceClickListener {
                val ctx = context ?: return@setOnPreferenceClickListener false
                HeadUnitServerController.request(ctx, stop) { message ->
                    if (isAdded) {
                        runCatching {
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }
        }

        /**
         * 「禁止 Waze / Google 地图投屏」不是纯配置项：真正生效靠 root 去
         * pm disable 对应的投屏组件。这里在写入前先执行，失败就把开关退回原位、
         * 返回 false 让 preference 不落盘，免得配置和实际组件状态脱节。
         */
        private fun bindAppProjectionSwitches() {
            bindShellBackedSwitch(AADisplayConfig.DisableWazeOnAa.key, R.string.disable_waze_on_aa_apply_failed) {
                WazeOnAaManager.apply(it)
            }
            bindShellBackedSwitch(
                AADisplayConfig.DisableGoogleMapsOnAa.key,
                R.string.disable_google_maps_on_aa_apply_failed
            ) {
                GoogleMapsOnAaManager.apply(it)
            }
        }

        private fun bindShellBackedSwitch(
            key: String,
            @StringRes failedMessage: Int,
            apply: (Boolean) -> Boolean
        ) {
            val preference = findPreference<Preference>(key) ?: return
            preference.setOnPreferenceChangeListener { pref, newValue ->
                val enabled = newValue as? Boolean ?: return@setOnPreferenceChangeListener false
                val ctx = context ?: return@setOnPreferenceChangeListener false
                // root shell 是阻塞操作（最多 8 条命令），绝不能跑在 UI 线程上。
                // 先让开关落盘，失败后再回滚 UI 与配置。
                io.github.nitsuya.template.bases.runIO {
                    val ok = runCatching { apply(enabled) }.getOrDefault(false)
                    if (!ok) {
                        io.github.nitsuya.template.bases.runMain {
                            (pref as? androidx.preference.TwoStatePreference)?.let {
                                it.isChecked = !enabled
                            }
                            runCatching {
                                Toast.makeText(ctx, ctx.getString(failedMessage), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                true
            }
        }

        override fun onCreateRecyclerView(
            inflater: LayoutInflater,
            parent: ViewGroup,
            savedInstanceState: Bundle?
        ): RecyclerView {
            return super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
                // 卡片由 MiuixCardDecoration 画，靠 RecyclerView 的左右 padding 内缩
                val side = (16 * resources.displayMetrics.density).toInt()
                val bottom = (28 * resources.displayMetrics.density).toInt()
                setPadding(side, 0, side, bottom)
                clipToPadding = false
                isVerticalScrollBarEnabled = false
                addItemDecoration(MiuixCardDecoration(requireContext()))
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // 关掉 preference 库自带的分割线，改由 MiuixCardDecoration 绘制
            setDivider(null)
            setDividerHeight(0)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            refreshChoiceSummaries()
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            SharedPreferencesAccess.makeReadableForHooks(requireContext(), AADisplayConfig.ConfigName)
        }

        private data class ChoiceSpec(
            val key: String,
            val entriesRes: Int,
            val valuesRes: Int,
            val default: String,
        )

        private val choiceSpecs = listOf(
            ChoiceSpec(
                "DisplayImePolicy", R.array.ime_policy_keys, R.array.ime_policy_values, "1",
            ),
            ChoiceSpec(
                "AapVideoCodec", R.array.aap_video_codec_keys, R.array.aap_video_codec_values, "h264",
            ),
            ChoiceSpec(
                "AapMaxResolution",
                R.array.aap_max_resolution_keys,
                R.array.aap_max_resolution_values,
                "auto",
            ),
        )

        private fun bindChoicePages() {
            // 本 ROM 上 ListPreference/AlertDialog 的单选列表点不动（对话框能弹出、
            // 外部点击能取消，但条目没有点击响应）。选项改由 [ChoiceActivity] 承载。
            choiceSpecs.forEach { spec ->
                val pref = findPreference<Preference>(spec.key) ?: return@forEach
                updateChoiceSummary(pref, spec)
                pref.setOnPreferenceClickListener {
                    val entries = resources.getStringArray(spec.entriesRes)
                    val values = resources.getStringArray(spec.valuesRes)
                    startActivity(
                        android.content.Intent(requireContext(), ChoiceActivity::class.java)
                            .putExtra(ChoiceActivity.EXTRA_KEY, spec.key)
                            .putExtra(ChoiceActivity.EXTRA_TITLE, pref.title)
                            .putStringArrayListExtra(ChoiceActivity.EXTRA_ENTRIES, ArrayList(entries.toList()))
                            .putStringArrayListExtra(ChoiceActivity.EXTRA_VALUES, ArrayList(values.toList()))
                            .putExtra(ChoiceActivity.EXTRA_DEFAULT, spec.default)
                    )
                    true
                }
            }
        }

        private fun refreshChoiceSummaries() {
            choiceSpecs.forEach { spec ->
                val pref = findPreference<Preference>(spec.key) ?: return@forEach
                updateChoiceSummary(pref, spec)
            }
        }

        private fun updateChoiceSummary(pref: Preference, spec: ChoiceSpec) {
            val entries = resources.getStringArray(spec.entriesRes)
            val values = resources.getStringArray(spec.valuesRes)
            val current = preferenceManager.sharedPreferences
                ?.getString(spec.key, spec.default) ?: spec.default
            val index = values.indexOf(current).takeIf { it >= 0 } ?: 0
            pref.summary = entries.getOrNull(index)
        }
    }
}
