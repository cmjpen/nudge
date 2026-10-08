package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.surfaces.InstagramSurfaces
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
     * The two facts a phone report needs to be actionable: `present=false` is a renamed id,
     * `present=true visible=false` is an attached off-screen page. The distinction is what the
     * 2026-10-07 readout could not make, and guessing between the two cost a device cycle.
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
                "title_logo present=true visible=false",
                "tab_bar present=true visible=true",
                "sticky_header_list present=false visible=false"
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