package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug surface inspector: a process-global last-seen set, shown in Developer Options so a
 * screen with no committed fixture can be reported from the phone.
 *
 * Only ids and a package are ever stored — no text — which is the same privacy rule the committed
 * fixtures are scrubbed under.
 */
class SurfaceInspectorTest {

    @Test
    fun `records the last tree's ids sorted and reports the package`() {
        SurfaceInspector.record(
            packageName = "com.instagram.android",
            viewIds = listOf("z_id", "a_id"),
            activeTab = "SEARCH",
            tabStates = listOf("search_tab=true", "feed_tab=false"),
            nowMs = 42L,
            markers = listOf("title_logo present=true visible=false")
        )

        assertEquals("com.instagram.android", SurfaceInspector.lastPackage)
        assertEquals(listOf("a_id", "z_id"), SurfaceInspector.lastViewIds)
        assertEquals("SEARCH", SurfaceInspector.activeTab)
        assertEquals(listOf("search_tab=true", "feed_tab=false"), SurfaceInspector.tabStates)
        assertEquals(listOf("title_logo present=true visible=false"), SurfaceInspector.markers)
        assertEquals(42L, SurfaceInspector.updatedAtMs)

        val readout = SurfaceInspector.readout()
        assertTrue(readout.contains("package=com.instagram.android"))
        assertTrue(readout.contains("activeTab=SEARCH"))
        assertTrue(readout.contains("tabStates=search_tab=true, feed_tab=false"))
        assertTrue(
            "the marker lines are what separate a renamed id from an attached off-screen page",
            readout.contains("markers=title_logo present=true visible=false")
        )
        assertTrue(readout.contains("a_id"))
        assertTrue(readout.contains("z_id"))
    }
}
