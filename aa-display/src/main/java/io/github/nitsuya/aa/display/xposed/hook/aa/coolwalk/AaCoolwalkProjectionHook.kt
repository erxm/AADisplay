package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.loadClass
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.xposed.CoreManager
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import kotlin.math.abs

object AaCoolwalkProjectionHook {

    /**
     * content_bounds / content_insets rewriting is **keyed only**: a Rect is rewritten solely
     * when gearhead hands it to us under one of [CoolwalkHookEnv.PROJECTION_CONFIG_KEYS]
     * (config Bundle, `Bundle.putParcelable` / `getParcelable`, `Intent.putExtra`,
     * `ArrayMap.put`).
     *
     * There used to be an unkeyed fallback: process-wide hooks on `Rect(int,int,int,int)` and
     * `Rect.set`, gated by a ThreadLocal "projection config publication" window, that accepted
     * any rect whose shape matched rail-inset content_bounds and expanded it in place. It is
     * gone, and must not come back — DHU field evidence (`gearhead:projection` pids
     * 10039 / 32214 / 1844, three crash-restart cycles in 26s):
     *
     * - gearhead's own `nqm#T` allocates `Rect(80, 0, 1280, 720)` — a genuine 1200px content
     *   slot inside a 1280px window — while the DexKit-hit config method is on the stack, so
     *   the window was open at exactly the wrong moment;
     * - that rect is *shape-identical* to real rail-inset content_bounds (`top == 0`,
     *   `bottom == fullH`, `right == fullW`, `left` a plausible single rail), so no amount of
     *   tightening in `looksLikeHuContentBounds` could separate the two;
     * - the accept expanded it in place to `(0, 0, 1280, 720)`, i.e. told gearhead pane 1 wants
     *   1280px while only 1200px was actually available, so the two-pane measure produced
     *   `available − pane1 = −80` and Compose threw `IllegalArgumentException: maxWidth must be
     *   >= than minWidth` on the main thread inside `dispatchDraw` (`dls.a` ← … ←
     *   `czw.dispatchDraw`), killing `:projection` and dropping the GAL transport;
     * - the window it was scoped to bought nothing: `fixProjectionConfigBundle` never rewrote
     *   anything in that flow (zero "projection config rewrite" lines in 359k logcat lines),
     *   so 100% of the rewrites — and the crash — came from the unkeyed path alone.
     *
     * Rail reclaim does not depend on it: [CoolwalkProjectionBoundsHook] (compositor `blX`/`trX`,
     * a typed ctor on the DexKit-resolved bounds class), [AaCoolwalkLayoutHook] (LayoutInfo +
     * rail width dimens → 0), [CoolwalkDrawingSpecWiden], [AaCoolwalkCompositorHook] (FacetBar VD
     * starve) and [CoolwalkFacetChrome] (gutter reclaim) all act on identified targets.
     *
     * Removing the unkeyed path was necessary but not sufficient: with it gone (e3 / 3014, zero
     * `unkeyed Rect` accepts, one rewrite instead of twelve) `:projection` pid 10400 still died the
     * same way. e2 / 3016 then capped the keyed rewrite to the incoming width as a second attempt
     * at the crash — and `full-e4.log` shows e4 shipped that cap and *still* crashed three times
     * with the identical stack, so the cap never bought crash safety. What did was
     * [CoolwalkComposeConstraintsGuard], clamping the negative-span `Constraints` tuples at the two
     * validator entry points. The cap only pinned the session to a 1200px canvas on a 1280px HU,
     * i.e. it *was* the right-edge black bar, so it is gone again: content_bounds is moved to the
     * origin **and** grown to full HU, as in the crash-free e1 canvas
     * (`content_bounds=Rect(0, 0, 1280, 720)`, `DrawingSpec{width=1280}`,
     * `displayProfile locked: 1280*720`). [CoolwalkDrawingSpecWiden] still widens `DrawingSpec`
     * directly for the paths content_bounds does not reach.
     */
    fun install(env: CoolwalkHookEnv) {
        CoolwalkRailCoordinator.syncExternalTruth()
        hookContentBounds(env)
    }

    /**
     * C1: primary path is [fixProjectionConfigBundle] on DexKit contentBoundsMethods.
     * Bundle.putParcelable / getParcelable / Intent.putExtra / ArrayMap.put are keyed backups for
     * reconnect republication. Every path here has a key proving the Rect is content_bounds /
     * content_insets — see the [AaCoolwalkProjectionHook] header for why there is no unkeyed one.
     */
    private fun hookContentBounds(env: CoolwalkHookEnv) {
        for (method in env.contentBoundsMethods) {
            try {
                method.hookBefore { param ->
                    markProjectionReconnectIfNeeded(env, method.declaringClass.simpleName)
                    param.args.forEach { arg ->
                        if (arg is Bundle) fixProjectionConfigBundle(env, arg)
                    }
                }
                method.hookAfter { param ->
                    param.args.forEach { arg ->
                        if (arg is Bundle) fixProjectionConfigBundle(env, arg)
                    }
                }
                logDebug(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: hooked ${method.declaringClass.name}#${method.name} for config Bundle",
                )
            } catch (e: Throwable) {
                log(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: hook ${method.declaringClass.name}#${method.name} failed",
                    e,
                )
            }
        }

        try {
            val baseBundle = loadClass("android.os.BaseBundle")
            for (method in baseBundle.declaredMethods) {
                if (method.name == "putParcelable" && method.parameterCount == 2 &&
                    method.parameterTypes[0] == String::class.java
                ) {
                    method.isAccessible = true
                    method.hookBefore { param ->
                        rewriteProjectionConfigParcelable(env, param.args[0] as? String, param.args[1])?.let {
                            param.args[1] = it
                            AaCoolwalkAutoOpenHook.scheduleAutoOpenIfNeeded(env, "content_bounds")
                        }
                    }
                    logDebug(CoolwalkHookEnv.TAG, "AaUiHook: hooked BaseBundle.putParcelable(content_bounds)")
                }
                if (method.name == "putInt" && method.parameterCount == 2 &&
                    method.parameterTypes[0] == String::class.java &&
                    (method.parameterTypes[1] == Int::class.javaPrimitiveType ||
                        method.parameterTypes[1] == Integer::class.java)
                ) {
                    method.isAccessible = true
                    method.hookBefore { param ->
                        zeroPillarWidthIfNeeded(env, param)
                    }
                    logDebug(CoolwalkHookEnv.TAG, "AaUiHook: hooked BaseBundle.putInt(pillar_width)")
                }
            }
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: hook BaseBundle put failed", e)
        }

        try {
            findMethod(Bundle::class.java) {
                name == "putParcelable" && parameterCount == 2 &&
                    parameterTypes[0] == String::class.java
            }.hookBefore { param ->
                rewriteProjectionConfigParcelable(env, param.args[0] as? String, param.args[1])?.let {
                    param.args[1] = it
                    AaCoolwalkAutoOpenHook.scheduleAutoOpenIfNeeded(env, "content_bounds")
                }
            }
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: hooked Bundle.putParcelable(content_bounds)")
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: hook Bundle.putParcelable failed", e)
        }

        for (method in Bundle::class.java.declaredMethods) {
            if (method.name != "getParcelable") continue
            if (method.parameterCount !in 1..2) continue
            if (method.parameterTypes[0] != String::class.java) continue
            try {
                method.isAccessible = true
                method.hookAfter { param ->
                    val key = param.args[0] as? String ?: return@hookAfter
                    if (key !in CoolwalkHookEnv.PROJECTION_CONFIG_KEYS) return@hookAfter
                    val rect = param.result as? Rect ?: return@hookAfter
                    rewriteProjectionConfigParcelable(env, key, rect)?.let { param.result = it }
                }
            } catch (e: Throwable) {
                log(CoolwalkHookEnv.TAG, "AaUiHook: hook Bundle.getParcelable failed", e)
            }
        }

        try {
            findMethod(Intent::class.java) {
                name == "putExtra" && parameterCount == 2 &&
                    parameterTypes[0] == String::class.java &&
                    parameterTypes[1] == Parcelable::class.java
            }.hookBefore { param ->
                val key = param.args[0] as? String ?: return@hookBefore
                if (key !in CoolwalkHookEnv.PROJECTION_CONFIG_KEYS) return@hookBefore
                rewriteProjectionConfigParcelable(env, key, param.args[1])?.let {
                    param.args[1] = it
                }
            }
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: hooked Intent.putExtra(content_bounds)")
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: hook Intent.putExtra(Parcelable) failed", e)
        }

        try {
            val arrayMapClass = loadClass("android.util.ArrayMap")
            findMethod(arrayMapClass) {
                name == "put" && parameterCount == 2
            }.hookBefore { param ->
                val key = param.args[0] as? String ?: return@hookBefore
                if (key !in CoolwalkHookEnv.PROJECTION_CONFIG_KEYS) return@hookBefore
                rewriteProjectionConfigParcelable(env, key, param.args[1])?.let {
                    param.args[1] = it
                    AaCoolwalkAutoOpenHook.scheduleAutoOpenIfNeeded(env, "content_bounds")
                }
            }
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: hooked ArrayMap.put for content_bounds")
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: hook ArrayMap.put failed", e)
        }
    }

    private fun markProjectionReconnectIfNeeded(env: CoolwalkHookEnv, source: String) {
        val session = CoolwalkRailCoordinator.onProjectionConfigSignal("projection:$source")
        if (!session.shouldReclaim) return
        val reason = "reconnect:$source"
        CoolwalkFacetChrome.reclaimAllWindowGutters(reason)
        if (env.canHookFacetBar) {
            CoolwalkFacetChrome.scheduleEnsureFacetBar(env, reason, rearm = true)
        }
        AaCoolwalkCompositorHook.starveSurvivingFacetBarVds(env, reason)
        for (delayMs in longArrayOf(250L, 1_000L)) {
            env.mFacetEnsureHandler.postDelayed(
                { AaCoolwalkCompositorHook.starveSurvivingFacetBarVds(env, "$reason+${delayMs}ms") },
                delayMs,
            )
        }
        AaCoolwalkAutoOpenHook.scheduleAutoOpenIfNeeded(env, "projection-reconnect")
    }

    private fun zeroPillarWidthIfNeeded(env: CoolwalkHookEnv, param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
        val key = param.args[0] as? String ?: return
        if (key != "pillar_width") return
        val value = (param.args[1] as? Number)?.toInt() ?: return
        if (value == 0) return
        val range = CoolwalkRailMath.railPxRange(
            env.layoutWidthPx().takeIf { it > 0 } ?: (value * 10),
        )
        if (value in range) {
            val actions = CoolwalkRailCoordinator.onEvent(
                RailEvent.RailWidthObserved(value, "pillar_width"),
            ).second
            CoolwalkRailCoordinator.dispatchActions(actions)
            param.args[1] = 0
        }
    }

    private fun fixProjectionConfigBundle(env: CoolwalkHookEnv, bundle: Bundle) {
        try {
            CoolwalkRailCoordinator.syncExternalTruth(InitFields.appContext.contentResolver)
            val bounds = bundleParcelableRect(bundle, "content_bounds")
            val boundsBefore = bounds?.let { Rect(it) }
            val boundsAfter = rewriteProjectionConfigParcelable(env, "content_bounds", bounds)
            val insets = bundleParcelableRect(bundle, "content_insets")
            val insetsBefore = insets?.let { Rect(it) }
            val insetsAfter = rewriteProjectionConfigParcelable(env, "content_insets", insets)
            var pillarRewrite: String? = null
            if (bundle.containsKey("pillar_width")) {
                val value = bundle.getInt("pillar_width", 0)
                if (value != 0) {
                    val range = CoolwalkRailMath.railPxRange(
                        env.layoutWidthPx().takeIf { it > 0 } ?: (value * 10),
                    )
                    if (value in range) {
                        val actions = CoolwalkRailCoordinator.onEvent(
                            RailEvent.RailWidthObserved(value, "pillar_width-bundle"),
                        ).second
                        CoolwalkRailCoordinator.dispatchActions(actions)
                        bundle.putInt("pillar_width", 0)
                        pillarRewrite = "$value→0"
                    }
                }
            }
            if (boundsAfter != null || insetsAfter != null || pillarRewrite != null) {
                env.logProjectionConfigRewriteOnce(
                    "AaUiHook: projection config rewrite " +
                        "layout=${env.layoutWidthPx()}x${env.layoutHeightPx()} " +
                        "bounds=${boundsBefore ?: "null"}→${bounds ?: "null"} " +
                        "insets=${insetsBefore ?: "null"}→${insets ?: "null"} " +
                        "pillar=${pillarRewrite ?: "unchanged"}",
                )
            }
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: fixProjectionConfigBundle failed", e)
        }
    }

    private fun rewriteProjectionConfigParcelable(env: CoolwalkHookEnv, key: String?, value: Any?): Rect? {
        if (key.isNullOrEmpty() || key !in CoolwalkHookEnv.PROJECTION_CONFIG_KEYS) return null
        val rect = value as? Rect ?: return null
        // The key proves this Rect *is* content_bounds / content_insets — no heuristic involved.
        return when (key) {
            "content_bounds", "contentBounds" -> applyExpandedContentBounds(env, rect)
            "content_insets", "contentInsets" -> applyZeroedContentInsets(env, rect)
            else -> null
        }
    }

    private fun applyExpandedContentBounds(env: CoolwalkHookEnv, rect: Rect): Rect? {
        CoolwalkRailCoordinator.syncExternalTruth(InitFields.appContext.contentResolver)
        val before = Rect(rect.left, rect.top, rect.right, rect.bottom)
        val snapBefore = CoolwalkRailCoordinator.current()
        val expanded = CoolwalkRailMath.applyExpandedContentBounds(
            rect,
            env.layoutWidthPx(),
            env.layoutHeightPx(),
            CoolwalkRailCoordinator.bestObservedFullHuWidthPx(),
            CoolwalkRailCoordinator.observedRailWidthPx(),
        ) ?: return null
        CoolwalkRailCoordinator.rememberFullHuSize(expanded.targetWidthPx, expanded.targetHeightPx)
        val incomingAlreadyFullBleed = before.left <= 0 &&
            abs(before.width() - expanded.targetWidthPx) <= 2
        val needsPresentationWiden = CoolwalkRailMath.needsPresentationWidenAfterExpand(
            before,
            expanded.targetWidthPx,
        )
        if (needsPresentationWiden) {
            logDebug(
                CoolwalkHookEnv.TAG,
                "AaUiHook: needsPresentationWiden slot=${before.right - before.left} target=${expanded.targetWidthPx} " +
                    "before=$before",
            )
        }
        val coordinatorReclaimed = snapBefore.fullHuWidthPx == expanded.targetWidthPx &&
            snapBefore.effectiveRailWidthPx == 0 &&
            snapBefore.phase != RailPhase.ReconnectSettling &&
            snapBefore.phase != RailPhase.Bootstrapping &&
            !CoolwalkRailCoordinator.recentReconnectReclaim()
        val skipReclaimDispatch = coordinatorReclaimed &&
            (incomingAlreadyFullBleed || needsPresentationWiden)
        if (!skipReclaimDispatch) {
            val actions = CoolwalkRailCoordinator.onEvent(
                RailEvent.ContentBoundsExpanded(
                    expanded.targetWidthPx,
                    expanded.targetHeightPx,
                    expanded.railWidthPx,
                    "content_bounds",
                ),
            ).second
            CoolwalkRailCoordinator.dispatchActions(actions)
        }
        env.mLayoutWidthDp = kotlin.math.max(env.mLayoutWidthDp, expanded.targetWidthPx)
        env.mLayoutHeightDp = kotlin.math.max(env.mLayoutHeightDp, expanded.targetHeightPx)
        AaCoolwalkLayoutHook.recheckPresentationCanvas(env, "content_bounds")
        env.logProjectionConfigRewriteOnce(
            "AaUiHook: content_bounds expanded $before→$rect layout=${expanded.targetWidthPx}x${expanded.targetHeightPx}",
        )
        if (!skipReclaimDispatch) {
            notifyAaUiFullBleed()
            if (needsPresentationWiden) {
                log(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: force relaunch slot=${before.width()}→${expanded.targetWidthPx}",
                )
                AaCoolwalkAutoOpenHook.scheduleFullBleedRelaunch(env, "content_bounds-widen", force = true)
            } else if (env.mAaDisplayShownThisSession && expanded.railWidthPx > 0) {
                AaCoolwalkAutoOpenHook.scheduleFullBleedRelaunch(env, "content_bounds")
            } else {
                AaCoolwalkAutoOpenHook.scheduleAutoOpenIfNeeded(
                    env,
                    "content_bounds",
                    bypassRearmGap = expanded.railWidthPx > 0,
                )
            }
            env.mFacetEnsureHandler.post {
                CoolwalkFacetChrome.reclaimAllWindowGutters("content_bounds-post")
            }
        } else if (needsPresentationWiden) {
            notifyAaUiFullBleed()
            AaCoolwalkAutoOpenHook.scheduleFullBleedRelaunch(
                env,
                "content_bounds-slot",
                force = true,
            )
            env.mFacetEnsureHandler.post {
                CoolwalkFacetChrome.reclaimAllWindowGutters("content_bounds-slot")
            }
        }
        return rect
    }

    private fun notifyAaUiFullBleed() {
        if (CoreManager.tryNotifyCoolwalkFullBleed()) return
        try {
            val action = AABroadcastConst.ACTION_COOLWALK_FULL_BLEED
            InitFields.appContext.sendBroadcast(
                Intent(action).setPackage("io.github.nitsuya.aa.display"),
            )
            InitFields.appContext.sendBroadcast(
                Intent(action).setPackage("com.google.android.projection.gearhead"),
            )
        } catch (e: Throwable) {
            log(CoolwalkHookEnv.TAG, "notifyAaUiFullBleed", e)
        }
    }

    private fun applyZeroedContentInsets(env: CoolwalkHookEnv, rect: Rect): Rect? {
        val before = Rect(rect)
        val range = CoolwalkRailMath.railPxRange(
            env.layoutWidthPx().takeIf { it > 0 } ?: rect.left.coerceAtLeast(rect.right) * 10,
        )
        var changed = false
        if (rect.left in range) {
            val actions = CoolwalkRailCoordinator.onEvent(
                RailEvent.RailWidthObserved(rect.left, "content_insets-left"),
            ).second
            CoolwalkRailCoordinator.dispatchActions(actions)
            rect.left = 0
            changed = true
        }
        if (rect.right in range) {
            val actions = CoolwalkRailCoordinator.onEvent(
                RailEvent.RailWidthObserved(rect.right, "content_insets-right"),
            ).second
            CoolwalkRailCoordinator.dispatchActions(actions)
            rect.right = 0
            changed = true
        }
        if (!changed) return null
        env.logProjectionConfigRewriteOnce(
            "AaUiHook: content_insets zeroed $before→$rect layout=${env.layoutWidthPx()}x${env.layoutHeightPx()}",
        )
        return rect
    }

    private fun bundleParcelableRect(bundle: Bundle, key: String): Rect? {
        // 两参 getParcelable 是 API 33 才有的；minSdk 31/32 上会 NoSuchMethodError。
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelable(key, Rect::class.java)
        } else {
            @Suppress("DEPRECATION")
            bundle.getParcelable(key) as? Rect
        }
    }
}
