package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.graphics.Rect
import io.github.nitsuya.aa.display.util.DisplayProfileSettle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure Coolwalk rail geometry — no Android framework deps beyond Rect.
 *
 * Resolution adaptation model (no hardcoded 800/1280/1920):
 * - [RailSnapshot.fullHuWidthPx] — head-unit canvas from LayoutInfo / VirtualDevice / expanded bounds
 * - [RailSnapshot.contentSlotWidthPx] — live content slot (full − rail) from LayoutInfo / DrawingSpec
 * - [RailSnapshot.touchRailWidthPx] — FacetBar VD width for hit-test only (not profile)
 *
 * Single-rail gap = fullHu − contentSlot. Must fall in [absoluteFacetRailBand] and match
 * observed rail within [RAIL_MEASUREMENT_JITTER_PX] (VD create vs compositor blX can differ).
 */
object CoolwalkRailMath {

    const val FULL_BLEED_STABLE_THRESHOLD = 3

    /** Physical Coolwalk facet bar width band (px) — same UI chrome on all HUs. */
    private const val FACET_RAIL_ABS_MIN = 32
    private const val FACET_RAIL_ABS_MAX = 160

    /** Max |gap − touchRail| when both look like facet rails (e.g. blX 92 vs VD 107). */
    private const val RAIL_MEASUREMENT_JITTER_PX = 20

    fun absoluteFacetRailBand(): IntRange = FACET_RAIL_ABS_MIN..FACET_RAIL_ABS_MAX

    fun railPxRange(fullW: Int): IntRange {
        if (fullW <= 0) return absoluteFacetRailBand()
        // 上界不能再低于下界，否则会得到空区间，coerceIn 直接抛 IllegalArgumentException。
        val upper = (fullW / 2).coerceAtLeast(FACET_RAIL_ABS_MIN)
        val max = (fullW * 0.25f).roundToInt().coerceAtLeast(120).coerceAtMost(upper)
        return FACET_RAIL_ABS_MIN..max
    }

    /**
     * True when [reportedW] is [fullW] minus one facet rail (content slot vs full HU).
     */
    fun isContentSlotVsFull(reportedW: Int, fullW: Int, observedRailWidthPx: Int = 0): Boolean {
        if (reportedW <= 0 || fullW <= 0) return false
        val gap = fullW - reportedW
        return isPlausibleRailGap(gap, fullW, observedRailWidthPx)
    }

    /** Remember the narrower content slot; survives [layoutWidthPx] promotion to full HU. */
    fun rememberContentSlotWidth(measuredW: Int, fullHu: Int, previous: Int): Int {
        if (measuredW <= 0) return previous
        if (fullHu > measuredW + 2) return measuredW
        if (previous > 0 && fullHu > previous + 2) return previous
        return if (fullHu > 0 && measuredW < fullHu - 2) measuredW else previous
    }

    fun isAbsoluteNarrowRailSize(width: Int, height: Int): Boolean =
        width in 8..120 && height >= width * 3

    fun isThinRailSize(width: Int, height: Int, layoutWidthPx: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (layoutWidthPx <= 0) return isAbsoluteNarrowRailSize(width, height)
        val maxRail = railPxRange(layoutWidthPx).last
        return width <= maxRail && height >= width * 2
    }

    /**
     * True when [gap] is a single Coolwalk rail, not 2×rail (the 720→880 inflation).
     * When [observedRailWidthPx] is known, require a match; otherwise use the rail
     * pixel range capped at ~15% of HU so a double-rail gap cannot pass.
     */
    fun isPlausibleRailGap(gap: Int, fullW: Int, observedRailWidthPx: Int = 0): Boolean {
        if (gap <= 1 || fullW <= 0) return false
        if (gap !in absoluteFacetRailBand()) return false
        if (observedRailWidthPx > 1) {
            val jitter = maxOf(RAIL_MEASUREMENT_JITTER_PX, observedRailWidthPx / 5)
            // 已知单轨宽度时只接受与单轨接近的 gap。不能落回下面的兜底：
            // 否则 2×rail 也会通过，正是注释里要拒绝的 720→880 inflation。
            return abs(gap - observedRailWidthPx) <= jitter
        }
        // Single rail only — reject 2×rail (720+80+80) via ~15% HU cap, absolute max 160px.
        val maxSingle = minOf(
            (fullW * 0.15f).roundToInt().coerceAtLeast(FACET_RAIL_ABS_MIN),
            FACET_RAIL_ABS_MAX,
        )
        return gap <= maxSingle
    }

    /**
     * HU x offset: full head-unit origin → content-slot / presentation origin (compositor blX).
     * Derived from observed full vs content slot, not a per-resolution table.
     */
    fun compositorLeftInsetPx(snapshot: RailSnapshot): Int {
        if (snapshot.phase == RailPhase.FullBleed &&
            snapshot.fullBleedStableCount >= FULL_BLEED_STABLE_THRESHOLD
        ) {
            return 0
        }
        val full = snapshot.fullHuWidthPx
        val slot = snapshot.contentSlotWidthPx.takeIf { it > 0 }
            ?: snapshot.layoutWidthPx.takeIf { full > 0 && it in 1 until full }
            ?: 0
        if (full <= 0 || slot <= 0 || full <= slot + 2) return 0
        val gap = full - slot
        return if (isPlausibleRailGap(gap, full, snapshot.touchRailWidthPx)) gap else 0
    }

    /**
     * True when [gap] between anchor full HU and a narrower LayoutInfo width looks like a
     * transient compositor slot (~≤30% of anchor), not a genuinely smaller head unit.
     */
    internal fun isStaleLayoutInfoWidthGap(gap: Int, anchorFull: Int): Boolean {
        if (gap <= 2 || anchorFull <= 0) return false
        return gap <= (anchorFull * 0.30f).roundToInt()
    }

    /**
     * True when [measuredW]×[measuredH] is the same head-unit as [sessionW]×[sessionH]
     * (identical, or one is the other minus 1–3 Coolwalk rails). Different cars fail this.
     */
    fun isSameHuGeometry(
        sessionW: Int,
        sessionH: Int,
        measuredW: Int,
        measuredH: Int,
        railWidthPx: Int,
    ): Boolean {
        if (sessionW <= 0 || measuredW <= 0) return false
        if (sessionH > 0 && measuredH > 0 && abs(sessionH - measuredH) > 8) return false
        val extra = abs(sessionW - measuredW)
        if (extra <= 2) return true
        if (railWidthPx > 1 && extra <= railWidthPx * 3 + 2 && extra % railWidthPx <= 2) return true
        return isPlausibleRailGap(extra, max(sessionW, measuredW), railWidthPx)
    }

    /**
     * This-connection HU width. [measuredW]/[measuredH] is live LayoutInfo / Display /
     * reported size — never a hardcoded resolution. A new head-unit (different height,
     * or width not a rail sibling) replaces the previous session; same HU keeps the
     * full width rather than the content slot (HU − rail).
     */
    fun pickConnectionFullHuWidth(
        sessionFull: Int,
        sessionHeight: Int,
        measuredW: Int,
        measuredH: Int,
        railWidthPx: Int,
    ): Int {
        val measured = measuredW.coerceAtLeast(0)
        val session = sessionFull.coerceAtLeast(0)
        if (measured <= 0) return session
        if (session <= 0) return measured
        if (!isSameHuGeometry(session, sessionHeight, measured, measuredH, railWidthPx)) {
            // Cross-geometry: width shrank but height grew — leaked slot, not a rail trim or new HU.
            if (session > measured &&
                sessionHeight > 0 && measuredH > 0 &&
                abs(sessionHeight - measuredH) > 8 &&
                measured < session &&
                measuredH > sessionHeight + 8
            ) {
                return session
            }
            // Same HU height but width shrank without a single-rail gap — stale LayoutInfo slot.
            if (session > measured &&
                sessionHeight > 0 && measuredH > 0 &&
                abs(sessionHeight - measuredH) <= 8 &&
                !isPlausibleRailGap(session - measured, session, railWidthPx) &&
                isStaleLayoutInfoWidthGap(session - measured, session)
            ) {
                return session
            }
            return measured
        }
        if (session > measured && isPlausibleRailGap(session - measured, session, railWidthPx)) {
            return session
        }
        if (measured > session && isPlausibleRailGap(measured - session, measured, railWidthPx)) {
            return measured
        }
        return max(session, measured)
    }

    fun isRailVirtualDisplayName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return name.contains("FacetBar", ignoreCase = true) ||
            name.contains("GhFacet", ignoreCase = true) ||
            name.contains("VerticalRail", ignoreCase = true) ||
            name.contains("EdgeColumn", ignoreCase = true)
    }

    fun layoutWidthPx(snapshot: RailSnapshot): Int =
        max(snapshot.layoutWidthPx, snapshot.fullHuWidthPx).takeIf { it > 0 } ?: 0

    // `looksLikeHuContentBounds` used to live here: a shape heuristic whose only caller was the
    // unkeyed process-wide `Rect` ctor / `Rect.set` hook. Both are gone — DHU field evidence
    // showed gearhead's own `nqm#T` allocates `Rect(80, 0, 1280, 720)` (a genuine 1200px content
    // slot), which is shape-identical to real rail-inset content_bounds, so the heuristic could
    // not discriminate and the in-place expand killed `:projection`. See the
    // [AaCoolwalkProjectionHook] header. content_bounds is now only rewritten when a key proves
    // what it is; do not reintroduce a shape-only test.

    data class ExpandedBounds(
        /** Full head-unit size this content_bounds is expanded to, and the size written to the Rect. */
        val targetWidthPx: Int,
        val targetHeightPx: Int,
        val railWidthPx: Int,
    )

    /**
     * Expand rail-inset content_bounds to full HU. Returns null when unchanged.
     */
    fun computeExpandedContentBounds(
        rect: Rect,
        layoutWidthPx: Int,
        layoutHeightPx: Int,
        observedFullHuWidthPx: Int,
        touchRailWidthPx: Int = 0,
    ): ExpandedBounds? = computeExpandedContentBounds(
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
        layoutWidthPx = layoutWidthPx,
        layoutHeightPx = layoutHeightPx,
        observedFullHuWidthPx = observedFullHuWidthPx,
        touchRailWidthPx = touchRailWidthPx,
    )

    fun computeExpandedContentBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        layoutWidthPx: Int,
        layoutHeightPx: Int,
        observedFullHuWidthPx: Int,
        touchRailWidthPx: Int = 0,
    ): ExpandedBounds? {
        if (top > 0 || right <= left || bottom <= 0) return null
        val contentW = right - left
        val fullH = max(layoutHeightPx.takeIf { it > 0 } ?: 0, bottom)
        val layoutW = layoutWidthPx.takeIf { it > 0 } ?: 0
        val observedFull = observedFullHuWidthPx.takeIf { it > 0 } ?: 0
        val rangeHint = maxOf(layoutW, observedFull, right, right + left.coerceAtLeast(0))
        val range = railPxRange(rangeHint)

        // Form A: Rect(rail, 0, fullW, H) — Coolwalk right edge is the HU, never right+left.
        // Form B: Rect(rail, 0, contentRight, H) when LayoutInfo/observed is independently larger
        //         and equals right+left (e.g. 107/1173 vs 1280).
        if (left in range) {
            val formA = right
            val formB = right + left
            val formAMatches =
                (layoutW > 0 && abs(formA - layoutW) <= 2) ||
                    (observedFull > 0 && abs(formA - observedFull) <= 2)
            val formBMatches =
                (layoutW > right + 8 && abs(formB - layoutW) <= 2) ||
                    (observedFull > right + 8 && abs(formB - observedFull) <= 2)
            val target = when {
                formAMatches -> formA
                formBMatches -> maxOf(formB, layoutW, observedFull)
                else -> formA
            }
            val snappedTarget = snapExpandedTargetToObservedFull(
                target,
                left,
                right,
                observedFull,
            )
            if (left <= 0 && abs(right - snappedTarget) <= 2) return null
            if (snappedTarget > contentW + 2) {
                return expandedBoundsOrNull(snappedTarget, fullH, left, top, right, bottom, left)
            }
            return null
        }

        // Form C: Rect(0, 0, contentW, H) — expand only to a known full HU, never by
        // blindly adding touchRail onto a width that is already the HU.
        if (left <= 0) {
            val knownFull = max(observedFull, layoutW)
            if (knownFull > right + 8 && isPlausibleRailGap(knownFull - right, knownFull, touchRailWidthPx)) {
                return expandedBoundsOrNull(knownFull, fullH, left, top, right, bottom, knownFull - right)
            }
            if (layoutW <= 0 && observedFull <= 0 && touchRailWidthPx > 1) {
                val inferred = right + touchRailWidthPx
                if (isPlausibleRailGap(touchRailWidthPx, inferred, touchRailWidthPx) && inferred > right) {
                    return expandedBoundsOrNull(inferred, fullH, left, top, right, bottom, touchRailWidthPx)
                }
            }
        }
        return null
    }

    /**
     * Build the expanded rect, or null when it would change nothing.
     *
     * The written size **is** the full-HU target: the rail gutter is reclaimed *and* the canvas
     * grows, because a content_bounds narrower than the head unit is exactly the right-edge black
     * bar — the projected VirtualDisplay only ever paints `writtenWidth` px of a `targetWidth` px
     * head unit, and everything downstream (`DrawingSpec`, the CarActivity presentation VD, the
     * settled display profile) is sized from this rect.
     *
     * e2 / 3016 shipped a cap here (`written = min(target, incomingWidth)`), which turned
     * `Rect(80, 0, 1280, 720)` into `Rect(0, 0, 1200, 720)` and pinned the whole session to a
     * 1200px canvas on a 1280px HU — 高德 and every other projected app lost the right 80px to
     * black, while gearhead's own Coolwalk launcher (drawn into the real 1280px window) and the
     * phone-side preview (mirroring the 1200px VD) both looked correct. The cap was added believing
     * a wider rect is what threw `IllegalArgumentException: maxWidth must be >= than minWidth` in
     * `:projection`; `full-e4.log` disproves that — e4 carried the cap and still died three times
     * with the identical `dls.a` ← … ← `czw.dispatchDraw` stack. What actually cures that crash is
     * [CoolwalkComposeConstraintsGuard], which clamps the negative-span `Constraints` tuples at
     * the two validator entry points. The uncapped e1 build reached `1280x720` with no black bar.
     *
     * Note the incoming right edge is already in full-HU coordinates (1280), so only the *size*
     * changes here; [CoolwalkCompositorPolicy] still refuses to resize AADisplay's own
     * presentation VD, where a Surface mismatch would black the screen.
     */
    private fun expandedBoundsOrNull(
        targetWidthPx: Int,
        targetHeightPx: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        railWidthPx: Int,
    ): ExpandedBounds? {
        if (targetWidthPx <= 0 || targetHeightPx <= 0) return null
        if (right - left <= 0 || bottom - top <= 0) return null
        // Already the rect we would write — no gutter left and no size change.
        if (left == 0 && top == 0 && right == targetWidthPx && bottom == targetHeightPx) return null
        return ExpandedBounds(
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            railWidthPx = railWidthPx,
        )
    }

    /**
     * LayoutInfo / presentation canvas target after facet reclaim.
     * Widens stale LayoutInfo shrink during reconnect settle, and after this session's
     * content_bounds reclaim even when no second LayoutInfo ctor runs.
     */
    fun resolveLayoutCanvasTargetPx(snapshot: RailSnapshot, contentWidth: Int, heightPx: Int): Int {
        val w = contentWidth.coerceAtLeast(0)
        val sessionH = snapshot.layoutHeightPx.takeIf { it > 0 } ?: heightPx
        val full = pickConnectionFullHuWidth(
            snapshot.fullHuWidthPx,
            sessionH,
            w,
            heightPx,
            snapshot.touchRailWidthPx,
        ).coerceAtLeast(w)
        if (w <= 0 || full <= w + 2) return w.coerceAtLeast(1)
        val gap = full - w
        // Stale LayoutInfo shrink on the same HU height — not a rail-trimmed content slot.
        // Defer during ReconnectSettling: widening LayoutInfo ctor before content_bounds
        // crashes :projection (ViewTreeLifecycleOwner) and leaves a compositor black bar.
        if (gap > 2 &&
            snapshot.phase != RailPhase.ReconnectSettling &&
            abs(sessionH - heightPx) <= 8 &&
            !isPlausibleRailGap(gap, full, snapshot.touchRailWidthPx) &&
            isStaleLayoutInfoWidthGap(gap, full)
        ) {
            return full
        }
        if (snapshot.effectiveRailWidthPx > 1) return w
        when (snapshot.phase) {
            RailPhase.Reclaiming,
            RailPhase.FullBleed,
            -> return full
            // True reconnect clears fullHu; known full + rail-shaped gap → widen (aligned
            // with DrawingSpec / blX gates).
            RailPhase.ReconnectSettling -> {
                if (isPlausibleRailGap(full - w, full, snapshot.touchRailWidthPx) ||
                    snapshot.fullBleedStableCount > 0
                ) {
                    return full
                }
            }
            else -> Unit
        }
        if (snapshot.fullBleedStableCount > 0 &&
            isPlausibleRailGap(full - w, full, snapshot.touchRailWidthPx)
        ) {
            return full
        }
        return w
    }

    internal fun snapExpandedTargetToObservedFull(
        target: Int,
        left: Int,
        right: Int,
        observedFull: Int,
        tolerancePx: Int = 32,
    ): Int {
        if (observedFull <= 0 || target >= observedFull) return target
        val formB = right + left
        if (abs(formB - observedFull) <= tolerancePx) return observedFull
        if (left > 0 && abs(target + left - observedFull) <= tolerancePx) return observedFull
        return target
    }

    /**
     * True when expanded content_bounds widened a rail-inset or content-slot rect to full HU
     * (e.g. Rect(107,0-1280,720) slotW=1173 → target 1280).
     */
    fun needsPresentationWidenAfterExpand(before: Rect, targetWidthPx: Int): Boolean {
        return needsPresentationWidenForSlotWidth(before.right - before.left, targetWidthPx)
    }

    internal fun needsPresentationWidenForSlotWidth(slotWidthPx: Int, targetWidthPx: Int): Boolean {
        val slotW = slotWidthPx.coerceAtLeast(0)
        return targetWidthPx > slotW + 2 &&
            DisplayProfileSettle.isContentSlotVsFull(slotW, targetWidthPx)
    }

    /**
     * Move rail-inset content_bounds to the origin and grow it to the full head unit. Returns null
     * when nothing changes. See [expandedBoundsOrNull] for why the size is not capped to the
     * incoming rect.
     */
    fun applyExpandedContentBounds(
        rect: Rect,
        layoutWidthPx: Int,
        layoutHeightPx: Int,
        observedFullHuWidthPx: Int,
        touchRailWidthPx: Int = 0,
    ): ExpandedBounds? {
        val expanded = computeExpandedContentBounds(
            rect,
            layoutWidthPx,
            layoutHeightPx,
            observedFullHuWidthPx,
            touchRailWidthPx,
        ) ?: return null
        rect.set(0, 0, expanded.targetWidthPx, expanded.targetHeightPx)
        return expanded
    }

    fun railHitWidthPx(snapshot: RailSnapshot): Int {
        val fullW = layoutWidthPx(snapshot)
        val observed = snapshot.touchRailWidthPx
        if (fullW > 0) {
            val range = railPxRange(fullW)
            if (observed in range) return observed
            if (observed in 8..(fullW / 2)) return observed
            return (fullW * 0.08f).roundToInt().coerceIn(range)
        }
        if (observed in 8..240) return observed
        return 48
    }
}
