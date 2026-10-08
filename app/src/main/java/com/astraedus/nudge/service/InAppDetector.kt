package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.domain.surfaces.HostSurface
import com.astraedus.nudge.util.NudgeLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects in-app features (Reels, Shorts, TikTok feed) by inspecting the accessibility tree.
 *
 * Instagram's detection rests on two measured signals: the full-screen reel player's own
 * containers, and the home feed named by `domain/surfaces/`'s surface classifier (the feed is
 * Reels-equivalent). It deliberately does NOT read bottom-nav tab selection: every tab reports
 * `selected=false` on current Instagram, which made that path structurally dead (issue #18).
 *
 * Detection is best-effort -- apps change their UI frequently. When detection fails
 * we return null (no feature detected) rather than crashing, so the service falls back
 * to whole-app rule evaluation.
 */
@Singleton
class InAppDetector @Inject constructor(
    private val logger: NudgeLogger
) : InAppDetectorApi {

    /**
     * Signatures of surfaces already reported by [dumpViewIdsForDiagnosis], so each unrecognised
     * screen is logged once per process rather than once per accessibility event. Debug builds
     * only; bounded in practice by the handful of distinct screens these apps have.
     */
    private val loggedUnknownSurfaces = mutableSetOf<String>()

    /** Last time the debug harvest actually walked the tree; see [dumpViewIdsForDiagnosis]. */
    @Volatile
    private var lastDiagnosticMs = 0L

    enum class Feature(val displayName: String, val key: String) {
        REELS("Instagram Reels", "REELS"),
        SHORTS("YouTube Shorts", "SHORTS"),

        /**
         * The Explore feature.
         *
         * No DETECTOR can produce it on current Instagram — tab selection is dead and no positive
         * marker for the screen has been captured — but the feature is enforced where it can be: an
         * EXPLORE rule that resolves to a HARD_BLOCK vanishes the Search/Explore tab (see
         * `InstagramSurfaces.vanishableTabs`). The rule editors offer it with copy that says "tab",
         * not "screen".
         */
        EXPLORE("Instagram Explore", "EXPLORE"),
        TIKTOK_FEED("TikTok Feed", "TIKTOK_FEED")
    }

    companion object {
        /** Packages that support in-app feature detection. */
        val SUPPORTED_PACKAGES = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        )

        /** Cap for the debug-only view-id harvest; keeps the walk off the hot path's budget. */
        private const val DIAGNOSTIC_NODE_LIMIT = 800

        /** Minimum gap between debug harvest walks. Bounds the cost to ~1 tree walk per interval. */
        private const val DIAGNOSTIC_THROTTLE_MS = 5_000L

        /**
         * Containers unique to Instagram's full-screen reel player, harvested from a real device
         * (Galaxy S24 / Android 16) while watching a reel opened from a DM.
         *
         * Checked instead of the bottom-nav tabs because the player is hosted in a modal activity
         * with no nav bar. Verified absent from the home feed (which shows inline video under
         * `media_group` / `carousel_video_media_group`) and from a DM thread, so these do not
         * over-match ordinary browsing.
         *
         * More than one is listed because the player's tree varies between entry points — the
         * DM-opened variant additionally carries a reply bar. Any single match is sufficient.
         */
        private val INSTAGRAM_CLIPS_VIEWER_IDS = listOf(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_video_container",
            "com.instagram.android:id/clips_media_component"
        )
    }

    /** True if any of [viewIds] resolves in [root]. Nodes are recycled before returning. */
    private fun findsAnyViewId(root: AccessibilityNodeInfo, viewIds: List<String>): Boolean {
        for (id in viewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                recycleNodes(nodes)
                return true
            }
            recycleNodes(nodes)
        }
        return false
    }

    /**
     * Attempt to detect which in-app feature is active for the given package.
     *
     * @param hostSurface the classification of the same tree read, from `domain/surfaces/`. It is
     *   the home-feed signal for Instagram: the measured markers behind `InstagramSurfaces` are the
     *   only reliable way to name that screen, because bottom-nav tab selection is dead (issue #18).
     * @return The detected [Feature], or null if no specific feature is detected
     *   (user is in a non-feature part of the app, or detection failed).
     */
    override fun detectFeature(
        packageName: String,
        rootNode: AccessibilityNodeInfo?,
        hostSurface: HostSurface?
    ): Feature? {
        if (rootNode == null) {
            logger.d("feature detection skipped package=$packageName reason=null_root")
            return null
        }
        return try {
            val feature = when (packageName) {
                "com.instagram.android" -> detectInstagram(rootNode, hostSurface)
                "com.google.android.youtube" -> detectYouTube(rootNode)
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> Feature.TIKTOK_FEED
                else -> null
            }
            if (feature == null) dumpViewIdsForDiagnosis(packageName, rootNode)
            logger.d("feature detection result package=$packageName feature=$feature")
            feature
        } catch (e: Exception) {
            logger.w("feature detection failed package=$packageName", e)
            null
        }
    }

    /**
     * DIAGNOSTIC (debug builds only): log the distinct view IDs present when detection found
     * nothing, so an undetected surface can be identified from logcat.
     *
     * Exists because the usual external tools cannot see these surfaces: `uiautomator dump` waits
     * for an idle window and a continuously playing reel/short never idles, so it hangs and gets
     * Killed; `dumpsys activity top` times out on the same screens. The accessibility tree this
     * service already walks has no such constraint.
     *
     * Reads ONLY `viewIdResourceName` — never text or contentDescription, which on these screens
     * would be the user's private messages and captions. Bounded to [DIAGNOSTIC_NODE_LIMIT] nodes,
     * matching the bounded-harvest convention used by the Strict Mode escape guard.
     */
    private fun dumpViewIdsForDiagnosis(packageName: String, root: AccessibilityNodeInfo) {
        if (!BuildConfig.DEBUG) return
        // Throttle the WALK, not just the logging. Detection fails on a firehose of content-change
        // events — ~800 times in three minutes of measured Instagram use — and each call would
        // otherwise BFS up to DIAGNOSTIC_NODE_LIMIT nodes on the accessibility hot path. A new
        // surface stays on screen for far longer than this interval, so nothing is missed.
        val now = System.currentTimeMillis()
        if (now - lastDiagnosticMs < DIAGNOSTIC_THROTTLE_MS) return
        lastDiagnosticMs = now

        val ids = LinkedHashSet<String>()
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        while (queue.isNotEmpty() && visited < DIAGNOSTIC_NODE_LIMIT) {
            val node = queue.removeFirst()
            visited++
            node.viewIdResourceName?.let { ids += it }
            for (i in 0 until node.childCount) {
                queue += node.getChild(i) ?: continue
            }
        }
        // Log each DISTINCT surface once. Detection runs on a firehose of content-change events —
        // a measured ~800 failed detections in three minutes of Instagram use — so logging every
        // miss buries the signal. What matters is "which surfaces do we not recognise", and that
        // set is tiny.
        val signature = ids.joinToString(",")
        if (!loggedUnknownSurfaces.add(signature)) return
        logger.d("undetected surface package=$packageName nodes=$visited viewIds=$signature")
    }

    private fun detectInstagram(root: AccessibilityNodeInfo, hostSurface: HostSurface?): Feature? {
        // The reel PLAYER first, before any surface reasoning. A reel opened from a DM (or a share
        // link, or a profile) runs in com.instagram.modal.ModalActivity, which has NO bottom nav
        // at all — so tab-based detection cannot see it even in principle, and the user scrolled
        // reels indefinitely with a HARD_BLOCK rule active. Keying on the player's own container
        // covers every entry route, including the Reels tab, where these IDs are also present.
        if (findsAnyViewId(root, INSTAGRAM_CLIPS_VIEWER_IDS)) {
            logger.d("instagram clips viewer detected")
            return Feature.REELS
        }

        // The home feed is Reels-equivalent (advertised behaviour), so a REELS rule covers it. It is
        // named by the measured markers behind `InstagramSurfaces.classify` — `title_logo` while the
        // action bar is visible, and `tab_bar` + `sticky_header_list` once the feed is scrolled and
        // the action bar has gone. Bottom-nav tab selection is NOT usable and must not come back:
        // every tab reports selected=false on current Instagram (pinned by
        // `InstagramSurfacesFixtureTest`), which is exactly what made the old selection-based path
        // silently dead (issue #18).
        if (hostSurface == HostSurface.HOME_FEED) {
            logger.d("instagram home feed detected")
            return Feature.REELS
        }

        // Explore is deliberately NOT detected. Current Instagram exposes no positive marker for it:
        // tab selection is dead and no Explore container has been captured, so the old
        // search_tab/text fallback could never fire. It has been deleted rather than left as a
        // plausible-looking heuristic, and the feature is no longer offered in the rule editor until
        // a device capture earns it a marker (see docs/BACKLOG.md).
        return null
    }

    private fun detectYouTube(root: AccessibilityNodeInfo): Feature? {
        // Method 1: Check if Shorts tab is selected (user navigated via bottom tab)
        val shortsNodes = root.findAccessibilityNodeInfosByText("Shorts")
        if (shortsNodes.isNotEmpty()) {
            for (node in shortsNodes) {
                if (node.isSelected || isInSelectedTab(node) || hasSelectedChild(node)) {
                    recycleNodes(shortsNodes)
                    return Feature.SHORTS
                }
            }
        }
        recycleNodes(shortsNodes)

        // Method 2: Check for Shorts player container (user tapped a Short from home feed)
        val reelRecycler = root.findAccessibilityNodeInfosByViewId(
            "com.google.android.youtube:id/reel_recycler"
        )
        if (reelRecycler.isNotEmpty()) {
            recycleNodes(reelRecycler)
            return Feature.SHORTS
        }

        // Method 3: Check for reel player page (another common Shorts container ID)
        val reelPlayer = root.findAccessibilityNodeInfosByViewId(
            "com.google.android.youtube:id/reel_player_page_container"
        )
        if (reelPlayer.isNotEmpty()) {
            recycleNodes(reelPlayer)
            return Feature.SHORTS
        }

        return null
    }

    /**
     * Walk up the parent chain to check if any ancestor is marked as selected.
     * This handles cases where the tab text itself is not selected but its container is.
     */
    private fun isInSelectedTab(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isSelected) return true
            val next = current.parent
            current = next
            depth++
        }
        return false
    }

    /**
     * Check if any immediate child of the node is selected.
     *
     * YouTube has been observed to mark the active tab's child icon rather than the parent
     * FrameLayout that carries the content-description. Instagram was once described the same way,
     * but the measured dumps disprove it on current versions and Instagram no longer uses this path.
     */
    private fun hasSelectedChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isSelected) return true
        }
        return false
    }

    private fun recycleNodes(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Exception) {
                // Already recycled -- ignore
            }
        }
    }
}
