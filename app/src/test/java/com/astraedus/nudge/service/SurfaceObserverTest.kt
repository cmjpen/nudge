package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.surfaces.HostSurface
import com.astraedus.nudge.domain.surfaces.InstagramSurfaces
import com.astraedus.nudge.util.NudgeLogger
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The surface observer: one tree read into a pure observation.
 *
 * Layer **L1** (`docs/testing-strategy.md`). The observation is the classifier's ONLY input, so the
 * cases that matter are the ones where the observation quietly stops carrying an id the classifier
 * still keys on, or starts carrying one that is attached but not on screen — both silent in
 * production, both caught here.
 *
 * Every id and locator is read from [InstagramSurfaces], never typed out: fixture honesty
 * (`docs/testing-strategy.md` rule (b)) and the adapter is the one file allowed to hold host-app ids.
 */
class SurfaceObserverTest {

    private val observer = SurfaceObserver(mockk<NudgeLogger>(relaxed = true))

    private fun node(
        viewId: String? = null,
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        visible: Boolean = true,
        children: List<AccessibilityNodeInfo> = emptyList(),
        byId: Map<String, List<AccessibilityNodeInfo>> = emptyMap(),
        byText: Map<String, List<AccessibilityNodeInfo>> = emptyMap()
    ): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.viewIdResourceName } returns viewId
        every { n.text } returns text
        every { n.contentDescription } returns desc
        every { n.isClickable } returns clickable
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

    /**
     * The measured Home feed, replayed through the real adapter: the ids the observation reports must
     * be enough for [InstagramSurfaces.classify] to reach HOME_FEED.
     */
    @Test
    fun `observe reports the host ids that are present`() {
        val tree = node(
            viewId = InstagramSurfaces.ID_TAB_BAR,
            children = listOf(
                node(viewId = InstagramSurfaces.ID_CLIPS_TAB),
                node(viewId = InstagramSurfaces.ID_STICKY_HEADER_LIST),
                node(viewId = "android:id/navigationBarBackground")
            ),
            byId = mapOf(InstagramSurfaces.ID_CLIPS_TAB to listOf(node()))
        )

        val observation = observer.observe(tree, InstagramSurfaces)

        assertTrue(InstagramSurfaces.ID_TAB_BAR in observation.presentViewIds)
        assertTrue(InstagramSurfaces.ID_CLIPS_TAB in observation.presentViewIds)
        assertTrue(InstagramSurfaces.ID_STICKY_HEADER_LIST in observation.presentViewIds)
        assertFalse(
            "ids outside the host package must never reach a host-app classifier",
            observation.presentViewIds.any { !it.startsWith(InstagramSurfaces.packageName) }
        )
    }

    /**
     * The observation is the classifier's ONLY input, so the two have to agree. Driving the real
     * adapter's `classify` off a real `observe` is what catches an observation that quietly stops
     * collecting an id the classifier still keys on.
     */
    @Test
    fun `an observation of a home-feed tree classifies as the home feed`() {
        val logo = node(viewId = InstagramSurfaces.ID_TITLE_LOGO, desc = InstagramSurfaces.DESC_HOME_FEED)
        val tree = node(
            viewId = InstagramSurfaces.ID_TAB_BAR,
            children = listOf(logo, node(viewId = InstagramSurfaces.ID_STICKY_HEADER_LIST))
        )

        assertEquals(
            HostSurface.HOME_FEED,
            InstagramSurfaces.classify(observer.observe(tree, InstagramSurfaces))
        )
    }

    /**
     * The 2026-10-07 Profile-page regression, in miniature: the attached home page's markers are in
     * the tree, but they are off screen while the nav bar is on screen. The observation must drop
     * them and the classifier must answer OTHER_TAB — the presence-only observation said HOME_FEED,
     * so a Reels hard block fired on the Profile page, which shows no reels at all.
     *
     * BOTH halves run on the SAME tree, so the visibility filter is provably load-bearing rather
     * than decorative: with the markers visible the very same shape must still be the home feed.
     */
    @Test
    fun `off-screen home markers on another page do not make it the home feed`() {
        fun tree(markerVisible: Boolean) = node(
            viewId = InstagramSurfaces.ID_TAB_BAR,
            children = listOf(
                node(
                    viewId = InstagramSurfaces.ID_TITLE_LOGO,
                    desc = InstagramSurfaces.DESC_HOME_FEED,
                    visible = markerVisible
                ),
                node(viewId = InstagramSurfaces.ID_STICKY_HEADER_LIST, visible = markerVisible)
            )
        )

        assertEquals(
            "off-screen markers must not classify the visible page as Home",
            HostSurface.OTHER_TAB,
            InstagramSurfaces.classify(observer.observe(tree(markerVisible = false), InstagramSurfaces))
        )
        assertEquals(
            "on-screen markers are still the home feed; if this fails the filter swallows real ones",
            HostSurface.HOME_FEED,
            InstagramSurfaces.classify(observer.observe(tree(markerVisible = true), InstagramSurfaces))
        )
    }

    /**
     * Device-found 2026-10-07: a feed large enough to exhaust the bounded harvest's node budget made
     * `tab_bar` and `sticky_header_list` invisible, `classify` answered UNKNOWN on the Home feed, and
     * the home-feed content cover never armed. The classifier's markers must arrive by the adapter's
     * TARGETED lookups, not from the walk.
     *
     * The tree below has no children at all, so the walk can see only the root and cannot possibly
     * collect these ids — exactly the device's shape, in miniature.
     */
    @Test
    fun `the classifier's markers arrive by targeted lookup even when the walk reaches nothing`() {
        val tree = node(
            byId = mapOf(
                InstagramSurfaces.ID_TAB_BAR to listOf(node(viewId = InstagramSurfaces.ID_TAB_BAR)),
                InstagramSurfaces.ID_STICKY_HEADER_LIST to
                    listOf(node(viewId = InstagramSurfaces.ID_STICKY_HEADER_LIST))
            )
        )

        val observation = observer.observe(tree, InstagramSurfaces)

        assertTrue(InstagramSurfaces.ID_TAB_BAR in observation.presentViewIds)
        assertTrue(InstagramSurfaces.ID_STICKY_HEADER_LIST in observation.presentViewIds)
        assertEquals(
            "the home feed must classify even when the bounded walk found nothing",
            HostSurface.HOME_FEED,
            InstagramSurfaces.classify(observation)
        )
    }

    /** A tree that goes away mid-read must not take the accessibility service down with it. */
    @Test
    fun `a throwing tree yields an empty observation rather than propagating`() {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.findAccessibilityNodeInfosByViewId(any()) } throws IllegalStateException("stale")
        every { root.childCount } throws IllegalStateException("stale")

        val observation = observer.observe(root, InstagramSurfaces)

        assertEquals(emptySet<String>(), observation.presentViewIds)
    }
}