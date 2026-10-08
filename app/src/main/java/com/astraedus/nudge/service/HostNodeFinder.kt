package com.astraedus.nudge.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.surfaces.ContentCoverPlacement
import com.astraedus.nudge.domain.surfaces.ContentCoverSpec
import com.astraedus.nudge.domain.surfaces.NodeLocator
import com.astraedus.nudge.domain.surfaces.PlatformSurfaces
import com.astraedus.nudge.domain.surfaces.TabCoverPlacement

/**
 * The ONE place a pure [NodeLocator] becomes a real `AccessibilityNodeInfo` lookup.
 *
 * `domain/surfaces/` describes WHICH node to find, as data, with no `android.*` import. This object
 * is the other half of that seam: it is the only code in the tab-vanish / content-cover features
 * allowed to walk a host app's tree. Nothing else may do node lookups for them — not the overlay
 * manager, not the surface observer, not the service. One implementation means one place where the
 * ids-first ordering, the bounded walk, the recycling discipline and the never-throw rule are
 * stated, instead of four subtly different copies of each.
 *
 * ## Strategy order, and why
 *
 * [NodeLocator] documents its own field order and this object obeys it: [NodeLocator.viewIds] first,
 * then [NodeLocator.texts], then [NodeLocator.contentDescriptions]. A resource id is compiled into
 * the host APK and survives locale changes and copy edits; text and content-descriptions are
 * localized, A/B-tested and rewritten without notice. The first strategy that finds anything wins,
 * so the fragile handles are only ever consulted when the reliable one found nothing at all.
 *
 * The framework supplies a finder for ids ([AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId])
 * and one for text ([AccessibilityNodeInfo.findAccessibilityNodeInfosByText]). It supplies **none**
 * for content-descriptions, so that strategy is a hand-rolled breadth-first walk capped at
 * [CONTENT_DESC_NODE_LIMIT] nodes — the same cap and the same reason as
 * `InAppDetector.dumpViewIdsForDiagnosis`: this runs on the accessibility hot path, which sees a
 * firehose of content-change events (a measured ~800 in three minutes of Instagram use), and an
 * uncapped walk of a feed's tree on every one of them is a budget nobody granted.
 *
 * ## Never throws
 *
 * Every public function wraps its tree reads and fails to `null` / `false` / an empty set, exactly
 * as `InAppDetector.detectFeature` does. A host app's tree can go away mid-read, and a
 * `StaleObjectException` propagating out of here would take the whole accessibility service down —
 * which means blocking stops completely (see CLAUDE.md, "Key conventions"). A feature that silently
 * does nothing is the correct failure for tab vanish and the content covers.
 *
 * ## Recycling
 *
 * Every node LIST obtained from the framework is recycled before returning, per
 * `InAppDetector.recycleNodes`.
 *
 * The breadth-first walk deliberately does NOT recycle the nodes it visits, matching
 * `InAppDetector.dumpViewIdsForDiagnosis`: the queue holds descendants of nodes already dequeued,
 * `recycle()` has been a deprecated no-op since API 33, and "recycle something another live
 * reference still points at" is a worse bug than the allocation it would save.
 */
object HostNodeFinder {

    /**
     * Cap for the content-description walk. Mirrors `InAppDetector.DIAGNOSTIC_NODE_LIMIT` — same hot
     * path, same reason, same number, so the two bounded walks in `service/` cost the same.
     */
    const val CONTENT_DESC_NODE_LIMIT = 800

    /**
     * How far up the parent chain [tabKeyFor] will look for the tab that owns a selected
     * `tab_icon`. Matches `InAppDetector.isInSelectedTab`, which walks the same trees with the same
     * bound; five is slack for a host app that wraps its tabs, not an invitation to climb forever.
     */
    const val CLICKABLE_ANCESTOR_DEPTH = 5

    /**
     * Where a cover over [locator]'s node goes, or null if the node is absent or its bounds cannot
     * be trusted.
     *
     * Returns the PURE [TabCoverPlacement], never an `android.graphics.Rect`. That is deliberate and
     * it is the reason this function exists in this shape: `android.graphics.Rect` is a throwing stub
     * in this repo's JVM unit tests (`testOptions { unitTests.isReturnDefaultValues = true }` is
     * deliberately NOT set — turning every unmocked android call into a silent 0/null would change
     * behaviour under ~1550 existing tests), so a `Rect` in a signature is a `Rect` in every caller
     * and in every caller's test. Handing back validated pure data keeps the untestable surface at
     * three lines instead of spreading it.
     *
     * Those three lines — allocate, `getBoundsInScreen`, read four fields — are the genuinely
     * untestable RESIDUE, isolated here exactly as `docs/TESTING.md` prescribes: one field-copying
     * function, no logic. Do not write a test for it and do not reach for the default-values flag to
     * make one possible. Everything with a decision in it — which strategy matched, the bounded
     * walk, the validation — is either tested here or tested in [TabCoverPlacement.of].
     */
    fun findPlacement(root: AccessibilityNodeInfo, locator: NodeLocator): TabCoverPlacement? = try {
        val nodes = matchesFor(root, locator)
        if (nodes.isEmpty()) {
            null
        } else {
            // The residue. Four field reads off one node; TabCoverPlacement.of owns the validation.
            val bounds = Rect()
            nodes[0].getBoundsInScreen(bounds)
            val placement = TabCoverPlacement.of(bounds.left, bounds.top, bounds.right, bounds.bottom)
            recycleNodes(nodes)
            placement
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Which bottom-nav tab the adapter reports as ACTIVE, or null when the app exposes no active
     * state (or none is selected).
     *
     * Exists because some surfaces have no positive container marker of their own. Instagram's
     * Search/Explore screen is the worked example: no unique view id has been measured for it, but
     * the active tab's `tab_icon` child reports `selected=true` while every tab FrameLayout reports
     * `selected=false` (IG 447, `home.xml`). The indicator is found anywhere in the tree and the
     * parent chain names the tab, so the signal does not depend on nesting depth.
     *
     * Never throws and fails to null, like every other read here: a surface we cannot identify is a
     * cover we do not draw.
     */
    fun activeTab(root: AccessibilityNodeInfo, surfaces: PlatformSurfaces): String? = try {
        val indicatorId = surfaces.activeTabIndicatorId
        if (indicatorId == null || surfaces.activeTabs.isEmpty()) {
            null
        } else {
            // The framework's indexed lookup, NOT a bounded walk: it is depth-unbounded and cheap,
            // and a Search page's grid can hold far more nodes than any walk budget should be asked
            // to cross to reach the nav bar.
            val icons = root.findAccessibilityNodeInfosByViewId(indicatorId)
            val key = icons.firstOrNull { it.isSelected }?.let { tabKeyFor(it, surfaces) }
            recycleNodes(icons)
            key
        }
    } catch (_: Exception) {
        null
    }

    /** Walk up from a selected indicator to the tab node that owns it. */
    private fun tabKeyFor(node: AccessibilityNodeInfo, surfaces: PlatformSurfaces): String? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < CLICKABLE_ANCESTOR_DEPTH) {
            val parent = current
            val key = surfaces.activeTabs.entries.firstOrNull { (_, locator) ->
                locator.matches(
                    parent.viewIdResourceName,
                    parent.text?.toString(),
                    parent.contentDescription?.toString()
                )
            }?.key
            if (key != null) return key
            current = parent.parent
            depth++
        }
        return null
    }

    /**
     * Debug/diagnostic view of every bottom-nav icon and whether it reports itself selected, keyed by
     * the tab it sits inside (`?` when no tab ancestor matched).
     *
     * Exists so a device whose active-tab signal does not fire can be diagnosed from the phone
     * itself (`SurfaceInspector`), instead of another round of guessing: the committed fixture says
     * the active icon is selected, and if a real device says none of them ever is, the selector needs
     * a different signal and we should see that rather than infer it.
     */
    fun tabIconStates(root: AccessibilityNodeInfo, surfaces: PlatformSurfaces): List<String> = try {
        val indicatorId = surfaces.activeTabIndicatorId
        if (indicatorId == null || surfaces.activeTabs.isEmpty()) {
            emptyList()
        } else {
            val icons = root.findAccessibilityNodeInfosByViewId(indicatorId)
            val states = icons.map { icon ->
                val tab = tabKeyFor(icon, surfaces) ?: "?"
                "$tab=${icon.isSelected}"
            }
            recycleNodes(icons)
            states
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The rectangle a [ContentCoverSpec] describes: from the bottom of its top node (or top
     * fallback) to the top of its bottom node, spanning the bottom node's width. Null when either
     * node is absent or the arithmetic yields no real rectangle — the cover is hidden, never guessed.
     */
    fun contentCoverPlacement(
        root: AccessibilityNodeInfo,
        spec: ContentCoverSpec
    ): TabCoverPlacement? = try {
        val bottom = findPlacement(root, spec.bottom)
        val top = findPlacement(root, spec.top)
            ?: spec.topFallback?.let { findPlacement(root, it) }
        ContentCoverPlacement.of(top, bottom)
    } catch (_: Exception) {
        null
    }

    /**
     * Which of [ids] are present in [root] **and visible to the user** — the id half of a
     * [com.astraedus.nudge.domain.surfaces.SurfaceObservation].
     *
     * Asks for a known set rather than harvesting every id in the tree: the adapter declares which
     * ids its classifier cares about, so this is a handful of indexed framework lookups instead of a
     * full walk on the hot path. It also means no id the caller did not ask for can end up in a
     * committed fixture.
     *
     * **Presence is not on-screen, and that distinction is load-bearing.** The framework's id lookup
     * searches the whole view hierarchy, including nodes that are attached but not visible — and
     * Instagram's main activity keeps the other tabs' pages attached, so the Messages or Profile
     * tab's tree carries the home feed's `title_logo` / `sticky_header_list`. Counting those as
     * "present" made `classify` call a non-home screen HOME_FEED: a Reels hard block fired on
     * Messages and on the Profile page, and the home-feed content cover stayed up over them
     * (device-reported 2026-10-07, both). A marker counts only when a matching node is actually on
     * screen; every match is checked because the framework's first hit is not necessarily the
     * visible one.
     */
    fun presentViewIds(root: AccessibilityNodeInfo, ids: Collection<String>): Set<String> = try {
        val present = LinkedHashSet<String>()
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            if (nodes.any { it.isVisibleToUser }) present += id
            recycleNodes(nodes)
        }
        present
    } catch (_: Exception) {
        emptySet()
    }

    /**
     * Every resource id in [root]'s tree that starts with [prefix], from a breadth-first walk capped
     * at [limit] dequeued nodes.
     *
     * This is the id half of a [com.astraedus.nudge.domain.surfaces.SurfaceObservation] as that class
     * defines it — "every fully-qualified resource id seen in this tree read" — and it is what makes
     * a surface classifier work without the adapter having to publish its classifier's id list
     * through the interface. A targeted [presentViewIds] can only confirm ids somebody already named;
     * a classifier that keys on the ABSENCE of an id (Instagram's Home feed is identified partly by
     * `tab_bar` being present and partly by other tabs' markers not being) needs the whole set.
     *
     * [visibleOnly] defaults to true, for the same reason [presentViewIds] filters: an attached but
     * off-screen page's ids must not enter a classifier's input. The debug inspector passes false —
     * a screen that misclassifies is explained by the ids that are present but not visible, and those
     * are exactly what this hides.
     *
     * Reads ONLY `viewIdResourceName` — never `text`, never `contentDescription`. On these screens
     * those are the user's captions and private messages, this repo commits its fixtures publicly,
     * and Nudge ships with no internet permission precisely so that stays true. The same constraint,
     * for the same reason, as `InAppDetector.dumpViewIdsForDiagnosis`, whose cap this shares.
     *
     * [prefix] keeps other packages' ids out of the set: an accessibility tree can contain system
     * decor, and a classifier reasoning about a host app should never see ids that are not its.
     */
    fun harvestViewIds(
        root: AccessibilityNodeInfo,
        prefix: String,
        limit: Int = CONTENT_DESC_NODE_LIMIT,
        visibleOnly: Boolean = true
    ): Set<String> = try {
        val ids = LinkedHashSet<String>()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && visited < limit) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let {
                if (it.startsWith(prefix) && (!visibleOnly || node.isVisibleToUser)) ids += it
            }
            for (i in 0 until node.childCount) {
                queue += node.getChild(i) ?: continue
            }
        }
        ids
    } catch (_: Exception) {
        emptySet()
    }

    /**
     * DEBUG ONLY: one line per classifier marker — whether it resolves, and whether the resolved
     * node is on screen. Consumed by `SurfaceInspector`'s readout; never on a decision path.
     *
     * Exists for the 2026-10-07 report family. A host app that keeps other pages attached puts their
     * markers in the tree while a different page is on screen, and from a phone there is otherwise no
     * way to tell "marker absent" from "marker present but off screen" — the two demand different
     * fixes, and guessing between them cost a device cycle. `present=true visible=false` is the
     * attached-page shape; `present=false` is a renamed id.
     */
    fun markerDiagnostics(root: AccessibilityNodeInfo, ids: Collection<String>): List<String> = try {
        ids.map { id ->
            val nodes = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
            val present = nodes.isNotEmpty()
            val visible = nodes.any { it.isVisibleToUser }
            recycleNodes(nodes)
            "${id.substringAfterLast(":id/")} present=$present visible=$visible"
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The first strategy that finds anything, in [NodeLocator]'s own reliability order. The returned
     * list is framework-owned: every caller either recycles it or documents why it did not.
     */
    private fun matchesFor(
        root: AccessibilityNodeInfo,
        locator: NodeLocator
    ): List<AccessibilityNodeInfo> {
        if (locator.isEmpty) return emptyList()

        for (id in locator.viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            if (nodes.isNotEmpty()) return nodes
        }
        for (text in locator.texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text) ?: continue
            // findAccessibilityNodeInfosByText matches case-insensitively on a SUBSTRING, and it
            // searches contentDescription as well as text, so it over-matches in two directions.
            // The locator's own exact rule decides which of the framework's hits actually counts —
            // on either field, because which of the two carries a host app's label is arbitrary.
            val exact = nodes.firstOrNull {
                locator.matches(null, it.text?.toString(), it.contentDescription?.toString())
            }
            if (exact != null) return listOf(exact) + nodes.filter { it !== exact }
            recycleNodes(nodes)
        }
        if (locator.contentDescriptions.isNotEmpty()) {
            val found = firstByContentDescription(root, locator)
            if (found != null) return listOf(found)
        }
        return emptyList()
    }

    /**
     * Bounded breadth-first search for a node whose `contentDescription` satisfies [locator].
     *
     * The framework has no content-description finder, so this is the one hand-rolled walk. Capped
     * at [CONTENT_DESC_NODE_LIMIT] dequeued nodes; a deeper match is reported as absent, which is
     * the correct failure for a fallback strategy. Matching goes through [NodeLocator.matches] so
     * the RULE lives in the pure layer and a fixture test exercises the same rule production does.
     */
    private fun firstByContentDescription(
        root: AccessibilityNodeInfo,
        locator: NodeLocator
    ): AccessibilityNodeInfo? {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && visited < CONTENT_DESC_NODE_LIMIT) {
            val node = queue.removeFirst()
            visited++
            if (locator.matches(null, null, node.contentDescription?.toString())) return node
            for (i in 0 until node.childCount) {
                queue += node.getChild(i) ?: continue
            }
        }
        return null
    }

    /** Mirrors `InAppDetector.recycleNodes`: best effort, and an already-recycled node is fine. */
    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Exception) {
                // Already recycled -- ignore.
            }
        }
    }
}
