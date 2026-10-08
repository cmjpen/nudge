package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.surfaces.HostSurface
import com.astraedus.nudge.domain.surfaces.InstagramSurfaces
import com.astraedus.nudge.domain.surfaces.SurfaceFixture
import com.astraedus.nudge.domain.surfaces.SurfaceObservation
import com.astraedus.nudge.util.NudgeLogger
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #18: Instagram's home-feed-as-Reels detection, driven by production's own surface
 * classifier against the recorded hierarchies in `surface-fixtures/` (L2 — a fixture can contradict
 * the code, and `home-scrolled` does: it proves the action bar and `title_logo` are gone).
 *
 * The deleted code resolved features from bottom-nav tab selection. Every tab reports
 * `selected=false` on current Instagram — pinned by `InstagramSurfacesFixtureTest` — so the path
 * could never fire. Detection now consumes the `HostSurface` classification the service already
 * computes from the same tree read, which names the home feed by measured markers: `title_logo`
 * while the action bar is visible, `tab_bar` + `sticky_header_list` once it is scrolled away.
 *
 * The counterfactual lives in the same fixture: `home-scrolled` has no `title_logo`, so a detector
 * keyed on the logo alone fails [scrolled home feed] even though [home feed] passes.
 */
class InAppDetectorInstagramDetectionTest {

    private val detector = InAppDetector(mockk<NudgeLogger>(relaxed = true))

    private val ig = "com.instagram.android"

    /**
     * A root whose [AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId] resolves exactly the
     * ids in [presentIds] and nothing else. childCount is 0 so the debug harvest terminates.
     */
    private fun rootWith(presentIds: Set<String>): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.childCount } returns 0
        every { root.viewIdResourceName } returns null
        every { root.findAccessibilityNodeInfosByViewId(any()) } answers {
            val id = firstArg<String>()
            if (id in presentIds) listOf(mockk<AccessibilityNodeInfo>(relaxed = true)) else emptyList()
        }
        return root
    }

    /** The same observation the service builds from a live tree, reduced from a recorded fixture. */
    private fun observe(fixture: String): SurfaceObservation = SurfaceFixture.observe(fixture)

    /** The production classifier's verdict for a fixture. */
    private fun surfaceOf(fixture: String): HostSurface = InstagramSurfaces.classify(observe(fixture))

    private fun detect(fixture: String, rootIds: Set<String> = emptySet()) =
        detector.detectFeature(ig, rootWith(rootIds), surfaceOf(fixture))

    // -----------------------------------------------------------------------------------------
    // The home feed is Reels-equivalent, on the feed's own measured markers
    // -----------------------------------------------------------------------------------------

    @Test
    fun `home feed is detected as REELS`() {
        assertEquals(HostSurface.HOME_FEED, surfaceOf("home"))
        assertEquals(InAppDetector.Feature.REELS, detect("home"))
    }

    /**
     * The load-bearing case. Instagram's action bar scrolls away with the content, so the scrolled
     * feed has NO `title_logo` — a detector keyed on the logo alone would return null here, which is
     * exactly the "silently non-functional" half of issue #18. The classifier names it through
     * `tab_bar` + `sticky_header_list` instead.
     */
    @Test
    fun `scrolled home feed is detected as REELS even though the title logo is gone`() {
        val ids = observe("home-scrolled").presentViewIds
        assertFalse(
            "the premise of this test is that the action bar has scrolled away",
            InstagramSurfaces.ID_TITLE_LOGO in ids
        )
        assertTrue(InstagramSurfaces.ID_TAB_BAR in ids)
        assertTrue(InstagramSurfaces.ID_STICKY_HEADER_LIST in ids)

        assertEquals(InAppDetector.Feature.REELS, detect("home-scrolled"))
    }

    /** Cold start and both dead-deep-link landings are still the home feed. */
    @Test
    fun `every recorded landing on the home feed detects REELS`() {
        listOf("coldstart", "variant-link", "scheme-link").forEach { fixture ->
            assertEquals("$fixture is a home-feed fixture", HostSurface.HOME_FEED, surfaceOf(fixture))
            assertEquals("$fixture must detect REELS", InAppDetector.Feature.REELS, detect(fixture))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Surfaces that must NOT become a blocked feature
    // -----------------------------------------------------------------------------------------

    /**
     * The Following feed is a separate activity with no nav bar. It is NOT Reels, and a REELS
     * hard block firing there would cover a chronological feed the user navigated to on purpose.
     */
    @Test
    fun `the following feed is not detected as a feature`() {
        assertEquals(HostSurface.UNKNOWN, surfaceOf("following-feed"))
        assertNull(detect("following-feed"))
        assertNull(detect("relaunch"))
    }

    /** The open title dropdown is a popup, not a surface; detection must change nothing. */
    @Test
    fun `the open title dropdown is not detected as a feature`() {
        assertEquals(HostSurface.UNKNOWN, surfaceOf("logo-menu"))
        assertNull(detect("logo-menu"))
    }

    /**
     * A bottom-nav screen that is not the home feed (Messages, Profile, Search/Explore) classifies
     * as OTHER_TAB and must not become REELS. Explore is deliberately not detectable any more:
     * there is no positive marker for it and the rule editor no longer offers the option.
     */
    @Test
    fun `another bottom-nav tab is not detected as a feature`() {
        val otherTabIds = setOf(
            InstagramSurfaces.ID_TAB_BAR,
            "com.instagram.android:id/search_tab",
            "com.instagram.android:id/direct_tab",
            "com.instagram.android:id/profile_tab"
        )
        val observation = SurfaceObservation(presentViewIds = otherTabIds)
        assertEquals(HostSurface.OTHER_TAB, InstagramSurfaces.classify(observation))

        assertNull(detector.detectFeature(ig, rootWith(otherTabIds), HostSurface.OTHER_TAB))
    }

    /**
     * The 2026-10-07 device regression: "on the dm page, I am getting reel hard blocked even though
     * no reels are showing." Instagram keeps the other tabs' pages attached, so the Messages tab's
     * tree carries the Home feed's own nodes (`media_group`, `row_feed_button_*`), and detection
     * keyed on surface classification then named HOME_FEED and hard-blocked Reels on Messages.
     *
     * The Home dump's exact id set with the active tab switched to DIRECT is the tree shape the
     * device produced; the selected tab is the page's identity and clear it is not Reels.
     */
    @Test
    fun `the messages tab carrying the home feed's nodes is not detected as REELS`() {
        val attachedHomePage = observe("home").copy(activeTab = "DIRECT")

        assertEquals(HostSurface.OTHER_TAB, InstagramSurfaces.classify(attachedHomePage))
        assertNull(
            detector.detectFeature(ig, rootWith(emptySet()), InstagramSurfaces.classify(attachedHomePage))
        )
    }

    // -----------------------------------------------------------------------------------------
    // The player stays the one signal that needs no classification
    // -----------------------------------------------------------------------------------------

    /**
     * The full-screen player is still detected from its own containers, and it wins even when the
     * classifier's verdict (here, another tab in the back stack) would say something else. A reel
     * opened over Messages or Explore must not slip through.
     */
    @Test
    fun `the clips player is detected regardless of the host surface verdict`() {
        val clips = setOf("com.instagram.android:id/clips_viewer_view_pager")
        assertEquals(
            InAppDetector.Feature.REELS,
            detector.detectFeature(ig, rootWith(clips), HostSurface.OTHER_TAB)
        )
        assertEquals(
            InAppDetector.Feature.REELS,
            detector.detectFeature(ig, rootWith(clips), null)
        )
    }
}
