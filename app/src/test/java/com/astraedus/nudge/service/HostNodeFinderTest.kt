package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.surfaces.InstagramSurfaces
import com.astraedus.nudge.domain.surfaces.NodeLocator
import com.astraedus.nudge.domain.surfaces.TabCoverPlacement
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [HostNodeFinder] is the ONE bridge from a pure [NodeLocator] to a real host-app node, so every
 * mistake available in host-app node lookup is available exactly here and nowhere else.
 *
 * Layer **L1** (`docs/testing-strategy.md`): pure decision logic behind a mocked framework surface.
 * `AccessibilityNodeInfo` needs mockk in a JVM test (`tasks/lessons.md`, 2026-06-16) and that is all
 * it needs — none of these cases wants a device, and the two that could not be written this way
 * (`findPlacement`'s three-line `Rect` read) are documented as residue on the function itself and
 * deliberately have no test.
 *
 * Every Instagram id below is READ FROM [InstagramSurfaces] rather than typed out. Two reasons:
 * fixture honesty (`docs/testing-strategy.md` rule (b) — never hand-type a value production
 * derives), and the adapter is the one file in the repo allowed to hold `com.instagram.android:id/…`
 * strings at all.
 */
@Suppress("DEPRECATION") // AccessibilityNodeInfo.recycle: the ownership rule under test is about it.
class HostNodeFinderTest {

    // ------------------------------------------------------------------------------------------
    // Fakes. A node answers only what it was told; nothing here relies on a relaxed default for a
    // value a decision reads, because a silent "" or 0 would make a broken locator look like a
    // working one.
    // ------------------------------------------------------------------------------------------

    private fun node(
        viewId: String? = null,
        text: String? = null,
        desc: String? = null,
        visible: Boolean = true,
        children: List<AccessibilityNodeInfo> = emptyList(),
        byId: Map<String, List<AccessibilityNodeInfo>> = emptyMap(),
        byText: Map<String, List<AccessibilityNodeInfo>> = emptyMap()
    ): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.viewIdResourceName } returns viewId
        every { n.text } returns text
        every { n.contentDescription } returns desc
        every { n.isVisibleToUser } returns visible
        every { n.parent } returns null
        every { n.childCount } returns children.size
        every { n.getChild(any()) } answers { children.getOrNull(firstArg()) }
        every { n.findAccessibilityNodeInfosByViewId(any()) } answers {
            byId[firstArg<String>()] ?: emptyList()
        }
        every { n.findAccessibilityNodeInfosByText(any()) } answers {
            byText[firstArg<String>()] ?: emptyList()
        }
        return n
    }

    /** Link [child] to [parent] after the fact, since each needs the other to exist first. */
    private fun parentOf(child: AccessibilityNodeInfo, parent: AccessibilityNodeInfo?) {
        every { child.parent } returns parent
    }

    /**
     * Give [node] on-screen bounds, read through production's own `getBoundsInScreen` residue.
     *
     * `Rect` field writes need no android implementation (the same fact `TabCoverOverlayManager`'s
     * tests stand on), which is what makes the WHICH-NODE-did-we-read question testable after all:
     * the two findPlacement tests below distinguish their nodes by their rectangles.
     */
    private fun reportsBounds(
        node: AccessibilityNodeInfo,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        every { node.getBoundsInScreen(any()) } answers {
            val r = firstArg<android.graphics.Rect>()
            r.left = left
            r.top = top
            r.right = right
            r.bottom = bottom
        }
    }

    // ------------------------------------------------------------------------------------------
    // Never throws. A host app's tree can go away mid-read, and this service dying means blocking
    // stops completely.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a throwing root yields nothing rather than propagating`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.findAccessibilityNodeInfosByViewId(any()) } throws IllegalStateException("stale")
        every { root.findAccessibilityNodeInfosByText(any()) } throws IllegalStateException("stale")
        every { root.childCount } throws IllegalStateException("stale")

        assertNull(HostNodeFinder.findPlacement(root, InstagramSurfaces.vanishableTabs.getValue("REELS")))
        assertNull(HostNodeFinder.activeTab(root, InstagramSurfaces))
        assertEquals(emptySet<String>(), HostNodeFinder.presentViewIds(root, listOf(InstagramSurfaces.ID_CLIPS_TAB)))
        assertEquals(emptySet<String>(), HostNodeFinder.harvestViewIds(root, "com.example:id/"))
    }

    /**
     * THE 2026-10-08 report family, both captures. A settled Search page ticked `activeTab=SEARCH`
     * (the gate passing) with `cover EXPLORE placement=null` for a minute and a half, in a tree
     * whose readout said `tab_bar present=true visible=true` — and the Profile page attached
     * (`action_bar_username_container`, `avatar_image_view`) from the switch that got there.
     *
     * The reconciliation: `markerDiagnostics` checks `any { it.isVisibleToUser }`, but
     * `findPlacement` read `nodes[0]` — the FIRST hit. An attached off-screen page can duplicate
     * an id, its node reports junk bounds, `TabCoverPlacement.of` rejects them, and the cover never
     * arms while the markers line says the id is visible. The cover's own lookup must obey the same
     * law the observation already obeys: presence is not on-screen.
     *
     * Counterfactual by construction: the first hit reports `[0,0][0,0]` — under first-hit
     * semantics `TabCoverPlacement.of` rejects that and the placement is null, which is exactly the
     * readout the device produced.
     */
    @Test
    fun `findPlacement reads the bounds of the VISIBLE match, not the first hit`() {
        val offscreen = node(viewId = InstagramSurfaces.ID_CLIPS_TAB, visible = false)
        reportsBounds(offscreen, 0, 0, 0, 0)
        val onscreen = node(viewId = InstagramSurfaces.ID_CLIPS_TAB, visible = true)
        reportsBounds(onscreen, 216, 1896, 432, 2028)
        val root = node(byId = mapOf(InstagramSurfaces.ID_CLIPS_TAB to listOf(offscreen, onscreen)))

        assertEquals(
            TabCoverPlacement(x = 216, y = 1896, width = 216, height = 132),
            HostNodeFinder.findPlacement(root, InstagramSurfaces.vanishableTabs.getValue("REELS"))
        )
    }

    /**
     * The other half of the same law. An id whose every match is off screen has no on-screen
     * rectangle, and a cover placed from an attached page's STALE-looking bounds would be an
     * opaque rectangle drawn somewhere the user is not looking — the failure direction every
     * placement rule in this feature already documents ("a cover drawn in the wrong place is worse
     * than no cover"). Fail toward hiding.
     */
    @Test
    fun `findPlacement refuses to place from matches that are all off screen`() {
        val offscreen = node(viewId = InstagramSurfaces.ID_TAB_BAR, visible = false)
        // Deliberately REAL-looking bounds: accepting these would draw a cover at a plausible
        // rectangle the user cannot see behind, which is worse than the null the junk-bounds case
        // already produced.
        reportsBounds(offscreen, 0, 1896, 1080, 2028)
        val root = node(byId = mapOf(InstagramSurfaces.ID_TAB_BAR to listOf(offscreen)))

        assertNull(HostNodeFinder.findPlacement(root, NodeLocator(viewIds = listOf(InstagramSurfaces.ID_TAB_BAR))))
    }

    // ------------------------------------------------------------------------------------------
    // presentViewIds and the two id readers.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `presentViewIds reports only the ids that resolve`() {
        val root = node(byId = mapOf(InstagramSurfaces.ID_TAB_BAR to listOf(node())))

        assertEquals(
            setOf(InstagramSurfaces.ID_TAB_BAR),
            HostNodeFinder.presentViewIds(
                root,
                listOf(InstagramSurfaces.ID_TAB_BAR, InstagramSurfaces.ID_CLIPS_TAB)
            )
        )
    }

    /**
     * Presence is not on-screen — the 2026-10-07 report family. Instagram keeps the other tabs'
     * pages attached, so a marker id resolves in a tree whose visible page is a different tab (the
     * Profile page's tree carries the home feed's `title_logo`). An id whose only matches are off
     * screen must not enter the observation, or `classify` calls the Profile page the home feed and a
     * Reels hard block fires there.
     */
    @Test
    fun `presentViewIds ignores an id whose only matches are off screen`() {
        val offscreen = node(viewId = InstagramSurfaces.ID_TITLE_LOGO, visible = false)
        val root = node(byId = mapOf(InstagramSurfaces.ID_TITLE_LOGO to listOf(offscreen)))

        assertEquals(
            emptySet<String>(),
            HostNodeFinder.presentViewIds(root, listOf(InstagramSurfaces.ID_TITLE_LOGO))
        )
    }

    /**
     * The framework's first hit is not necessarily the visible one — a recycled cell and its live
     * replacement share an id — so EVERY match is checked, not just the first.
     */
    @Test
    fun `presentViewIds accepts an id when any match is on screen`() {
        val offscreen = node(viewId = InstagramSurfaces.ID_TITLE_LOGO, visible = false)
        val onscreen = node(viewId = InstagramSurfaces.ID_TITLE_LOGO, visible = true)
        val root = node(byId = mapOf(InstagramSurfaces.ID_TITLE_LOGO to listOf(offscreen, onscreen)))

        assertEquals(
            setOf(InstagramSurfaces.ID_TITLE_LOGO),
            HostNodeFinder.presentViewIds(root, listOf(InstagramSurfaces.ID_TITLE_LOGO))
        )
    }

    /**
     * The harvest is what makes an observation COMPLETE for a classifier keying on markers the
     * adapter never had to publish. It must stay inside the host app's own id namespace: an
     * accessibility tree carries system decor too, and a classifier reasoning about Instagram should
     * never see an id that is not Instagram's.
     */
    @Test
    fun `harvestViewIds keeps to the host package and honours its limit`() {
        val prefix = InstagramSurfaces.packageName + ":id/"
        val tree = node(
            viewId = InstagramSurfaces.ID_TAB_BAR,
            children = listOf(
                node(viewId = InstagramSurfaces.ID_CLIPS_TAB),
                node(viewId = "android:id/statusBarBackground"),
                node(viewId = InstagramSurfaces.ID_STICKY_HEADER_LIST)
            )
        )

        assertEquals(
            setOf(
                InstagramSurfaces.ID_TAB_BAR,
                InstagramSurfaces.ID_CLIPS_TAB,
                InstagramSurfaces.ID_STICKY_HEADER_LIST
            ),
            HostNodeFinder.harvestViewIds(tree, prefix)
        )

        assertEquals(
            "a limit of 1 reaches the root and nothing under it",
            setOf(InstagramSurfaces.ID_TAB_BAR),
            HostNodeFinder.harvestViewIds(tree, prefix, limit = 1)
        )
    }

    /**
     * The walk reports visible ids only by default — an attached page's ids must not enter a
     * classifier's input — while the debug inspector can ask for everything, because the ids it
     * HIDES are exactly what explains a misclassified screen.
     */
    @Test
    fun `harvestViewIds skips off-screen nodes unless the caller asks for them`() {
        val prefix = InstagramSurfaces.packageName + ":id/"
        val tree = node(
            viewId = InstagramSurfaces.ID_TAB_BAR,
            children = listOf(
                node(viewId = InstagramSurfaces.ID_STICKY_HEADER_LIST, visible = false)
            )
        )

        assertEquals(
            setOf(InstagramSurfaces.ID_TAB_BAR),
            HostNodeFinder.harvestViewIds(tree, prefix)
        )
        assertEquals(
            setOf(InstagramSurfaces.ID_TAB_BAR, InstagramSurfaces.ID_STICKY_HEADER_LIST),
            HostNodeFinder.harvestViewIds(tree, prefix, visibleOnly = false)
        )
    }

    /**
     * The three facts a phone report needs to be actionable: `present=false` is a renamed id,
     * `present=true visible=false` is an attached off-screen page, and `matches=2 visible=true`
     * is the 2026-10-08 shape — an attached duplicate alongside the live node, which is exactly
     * what made `findPlacement`'s first hit the wrong one while this line still said visible=true.
     * Without the count, "visible=true" cannot tell one live node from a live node plus a ghost.
     */
    @Test
    fun `markerDiagnostics separates a renamed id from an off-screen one`() {
        val offscreen = node(viewId = InstagramSurfaces.ID_TITLE_LOGO, visible = false)
        val root = node(
            byId = mapOf(
                InstagramSurfaces.ID_TITLE_LOGO to listOf(offscreen),
                InstagramSurfaces.ID_TAB_BAR to listOf(node(viewId = InstagramSurfaces.ID_TAB_BAR))
            )
        )

        assertEquals(
            listOf(
                "title_logo present=true visible=false matches=1",
                "tab_bar present=true visible=true matches=1",
                "sticky_header_list present=false visible=false matches=0"
            ),
            HostNodeFinder.markerDiagnostics(
                root,
                listOf(
                    InstagramSurfaces.ID_TITLE_LOGO,
                    InstagramSurfaces.ID_TAB_BAR,
                    InstagramSurfaces.ID_STICKY_HEADER_LIST
                )
            )
        )
    }

    /** The duplicate detector: an attached ghost plus the live node reads `matches=2`. */
    @Test
    fun `markerDiagnostics counts matches so an attached duplicate is visible in the readout`() {
        val ghost = node(viewId = InstagramSurfaces.ID_TAB_BAR, visible = false)
        val live = node(viewId = InstagramSurfaces.ID_TAB_BAR, visible = true)
        val root = node(byId = mapOf(InstagramSurfaces.ID_TAB_BAR to listOf(ghost, live)))

        assertEquals(
            listOf("tab_bar present=true visible=true matches=2"),
            HostNodeFinder.markerDiagnostics(root, listOf(InstagramSurfaces.ID_TAB_BAR))
        )
    }

    // ------------------------------------------------------------------------------------------
    // Active tab: the tab FRAMES never report selection; the ACTIVE tab's tab_icon child does.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the selected tab icon names its owning tab`() {
        val icon = node(viewId = InstagramSurfaces.ID_TAB_ICON)
        every { icon.isSelected } returns true
        val searchTab = node(viewId = InstagramSurfaces.ID_SEARCH_TAB)
        parentOf(icon, searchTab)

        val root = node(byId = mapOf(InstagramSurfaces.ID_TAB_ICON to listOf(icon)))
        assertEquals("SEARCH", HostNodeFinder.activeTab(root, InstagramSurfaces))
    }

    @Test
    fun `an unselected icon or no indicator means no active tab`() {
        val icon = node(viewId = InstagramSurfaces.ID_TAB_ICON)
        every { icon.isSelected } returns false
        val searchTab = node(viewId = InstagramSurfaces.ID_SEARCH_TAB)
        parentOf(icon, searchTab)
        val root = node(byId = mapOf(InstagramSurfaces.ID_TAB_ICON to listOf(icon)))
        assertNull(HostNodeFinder.activeTab(root, InstagramSurfaces))

        // A selected icon that belongs to no tab (the top action bar reuses this id) must not be
        // mistaken for a tab.
        val stray = node(viewId = InstagramSurfaces.ID_TAB_ICON)
        every { stray.isSelected } returns true
        parentOf(stray, null)
        val strayRoot = node(byId = mapOf(InstagramSurfaces.ID_TAB_ICON to listOf(stray)))
        assertNull(HostNodeFinder.activeTab(strayRoot, InstagramSurfaces))
    }
}