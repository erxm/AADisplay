package io.github.nitsuya.aa.display.ui.setting

import android.view.ViewGroup
import com.google.android.material.radiobutton.MaterialRadioButton
import io.github.duzhaokun123.template.bases.BaseActivity
import io.github.nitsuya.aa.display.databinding.ActivityChoiceBinding
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess

/**
 * 通用单选页。
 *
 * 本 ROM 上 Preference/AlertDialog 的单选列表收不到点击（对话框能弹出、外部点击能取消，
 * 但条目没有响应），所以选项改由 Activity 承载：对话框换成独立页面，点击可靠。
 * 设置页把 key/标题/entries/values 通过 Intent 传进来。
 */
class ChoiceActivity : BaseActivity<ActivityChoiceBinding>(ActivityChoiceBinding::class.java) {

    companion object {
        const val EXTRA_KEY = "choice_key"
        const val EXTRA_TITLE = "choice_title"
        const val EXTRA_ENTRIES = "choice_entries"
        const val EXTRA_VALUES = "choice_values"
        const val EXTRA_DEFAULT = "choice_default"
    }

    private var key: String = ""
    private var values: List<String> = emptyList()
    private var default: String = ""
    private val radios = mutableListOf<MaterialRadioButton>()

    override fun initViews() {
        key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        values = intent.getStringArrayListExtra(EXTRA_VALUES).orEmpty()
        default = intent.getStringExtra(EXTRA_DEFAULT).orEmpty()
        intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let(::setTitle)
        buildOptions()
    }

    override fun initData() = Unit

    override fun onResume() {
        super.onResume()
        refreshChecked()
    }

    private fun buildOptions() {
        val entries = intent.getStringArrayListExtra(EXTRA_ENTRIES).orEmpty()
        val prefs = SharedPreferencesAccess.openForHooks(this, AADisplayConfig.ConfigName)
        val current = prefs.getString(key, default) ?: default
        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (12 * density).toInt()
        baseBinding.choiceContainer.removeAllViews()
        radios.clear()
        entries.forEachIndexed { index, entry ->
            val value = values.getOrNull(index) ?: return@forEachIndexed
            val radio = MaterialRadioButton(this).apply {
                text = entry
                textSize = 15f
                isChecked = value == current
                setPadding(padH, padV, padH, padV)
                setOnClickListener {
                    prefs.edit().putString(key, value).apply()
                    SharedPreferencesAccess.makeReadableForHooks(this@ChoiceActivity, AADisplayConfig.ConfigName)
                    refreshChecked()
                }
            }
            radios += radio
            baseBinding.choiceContainer.addView(
                radio,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            if (index != entries.lastIndex) {
                baseBinding.choiceContainer.addView(android.view.View(this).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(0x1F000000)
                })
            }
        }
    }

    private fun refreshChecked() {
        val prefs = SharedPreferencesAccess.openForHooks(this, AADisplayConfig.ConfigName)
        val current = prefs.getString(key, default) ?: default
        radios.forEachIndexed { index, radio ->
            radio.isChecked = values.getOrNull(index) == current
        }
    }
}
