package com.astraedus.nudge.domain.surfaces

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NodeLocator]'s matching rule, and the field order behind it.
 *
 * Every locator wants OR: find the node by its id, or by its label if this host-app build happens
 * to expose only that. The bug this file exists to prevent is that rule drifting to AND — the Reels
 * tab locator carries `clips_tab` AND the description "Reels" precisely so that EITHER can find it,
 * and any inference of "both declared means both required" would silently break Tab Vanish in every
 * non-English locale.
 */
class NodeLocatorTest {

    private val otherId = "com.instagram.android:id/clips_tab"

    @Test
    fun `a locator matches on the view id alone`() {
        val locator = NodeLocator(viewIds = listOf(otherId), contentDescriptions = listOf("Reels"))
        assertTrue(locator.matches(otherId, null, null))
    }

    @Test
    fun `a locator matches on the content description alone`() {
        val locator = NodeLocator(viewIds = listOf(otherId), contentDescriptions = listOf("Reels"))
        assertTrue(
            "a build that exposes the description but not the id must still find the tab",
            locator.matches(null, null, "Reels")
        )
    }

    @Test
    fun `a locator matches on the text alone`() {
        assertTrue(NodeLocator(texts = listOf("Reels")).matches(null, "Reels", null))
    }

    @Test
    fun `a locator does not match a node sharing nothing`() {
        val locator = NodeLocator(viewIds = listOf(otherId), contentDescriptions = listOf("Reels"))
        assertFalse(locator.matches("com.instagram.android:id/feed_tab", null, "Home"))
    }

    @Test
    fun `an empty locator matches nothing and knows it`() {
        val empty = NodeLocator()
        assertTrue(empty.isEmpty)
        assertFalse(empty.matches(otherId, "Reels", "Reels"))
    }

    /**
     * **The regression that would have cost us the tab cover.**
     *
     * The Reels tab locator carries `clips_tab` AND the description "Reels" precisely so that EITHER
     * can find it. If the matching rule were ever derived from "has ids AND has labels", this locator
     * would flip to AND on its own, and the cover would stop appearing on any build or locale that
     * does not expose the English description. OR stays OR, declared not inferred.
     */
    @Test
    fun `a locator carrying both ids and labels still matches on either`() {
        val tab = NodeLocator(viewIds = listOf(otherId), contentDescriptions = listOf("Reels"))
        assertTrue("the id alone must still find the tab", tab.matches(otherId, null, null))
        assertTrue(
            "the description alone must still find the tab — this is the locale fallback",
            tab.matches(null, null, "Reels")
        )
    }

    /** The real Reels locator, read from production rather than reconstructed here (rule (b)). */
    @Test
    fun `the shipped reels tab locator is an OR locator`() {
        val reels = InstagramSurfaces.vanishableTabs.getValue("REELS")
        assertTrue(reels.matches(InstagramSurfaces.ID_CLIPS_TAB, null, null))
        assertTrue(reels.matches(null, null, InstagramSurfaces.DESC_REELS_TAB))
    }
}