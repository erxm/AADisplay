package io.github.nitsuya.aa.display.ui.aa

import androidx.fragment.app.FragmentManager
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.ui.aa.fragment.AaMainFragment
import io.github.nitsuya.aa.display.ui.aa.fragment.AaRecentTaskFragment

object AaDisplayActivityKt {

    fun pressKey(action: Int){
        CoreApi.pressKey(action)
    }

    fun toggleDisplayPower(){
        CoreApi.toggleDisplayPower()
    }

    fun toast(msg: String){
        CoreApi.toast(msg)
    }

    fun showMain(fragmentManager: FragmentManager){
        // Activity 因进程死亡重建时，FragmentManager 会先恢复旧 fragment；
        // 这里再 add 一次会出现两个 TextureView / 双份触摸转发。
        if (fragmentManager.findFragmentById(R.id.fragment_container_view) != null) return
        fragmentManager.commit {
            setReorderingAllowed(true)
            add<AaMainFragment>(R.id.fragment_container_view)
        }
    }

    fun showRecentTask(fragmentManager: FragmentManager){
        val fragment = fragmentManager.findFragmentByTag("RecentTask")
        if(fragment == null){
            // 同一帧可能收到两次 APP_SWITCH：commit 异步执行会让两次都看到 null，
            // 各 add 一个面板，再按一次只 remove 一个。commitNow 保证同步完成。
            fragmentManager.commitNow {
                setReorderingAllowed(true)
                add<AaRecentTaskFragment>(R.id.fragment_container_view, "RecentTask")
            }
        } else {
            hideRecentTask(fragmentManager)
        }
    }

    fun hideRecentTask(fragmentManager: FragmentManager){
        val fragment = fragmentManager.findFragmentByTag("RecentTask") ?: return
        fragmentManager.commit {
            setReorderingAllowed(true)
            remove(fragment)
        }
    }

}