package com.astraedus.nudge.domain.surfaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The L2 layer for `domain/surfaces/`: production's own locators and classifier, driven against
 * eight real Instagram view hierarchies recorded on a device.
 *
 * `docs/testing-strategy.md` says replayed device data is the layer this repo underfunds (1.9% of the
 * budget, the largest single bug bucket) and that both of these features are node-tree features, so
 * this is the layer they belong at. The point is that a fixture can **disagree** with the code: every
 * assertion below goes through the same `InstagramSurfaces.classify` and the same `NodeLocator`s that
 * the accessibility service uses, over a tree nobody hand-wrote.
 *
 * Provenance, the app version, the device and the scrubbing rule: `surface-fixtures/README.md`.
 */
class InstagramSurfacesFixtureTest {

    private fun classify(fixture: String): HostSurface =
        InstagramSurfaces.classify(SurfaceFixture.observe(fixture))

    // -----------------------------------------------------------------------------------------
    // Home feed
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the home feed classifies as HOME_FEED`() {
        assertEquals(HostSurface.HOME_FEED, classify("home"))
    }

    /**
     * The scrolled Home feed is still the Home feed — and this is the fixture that forced the
     * `sticky_header_list` rung into [InstagramSurfaces.classify].
     *
     * Instagram's action bar scrolls away with the content, so `title_logo` is simply **absent** from
     * this dump. Classified on `title_logo` alone it would read as OTHER_TAB, and the home-feed
     * content cover's surface gate would drop the cover the moment the user scrolled — hiding it on
     * the very scroll that makes it matter.
     */
    @Test
    fun `the scrolled home feed classifies as HOME_FEED even with the action bar gone`() {
        val ids = SurfaceFixture.observe("home-scrolled").presentViewIds
        assertFalse(
            "the premise of this test is that the action bar has scrolled away",
            InstagramSurfaces.ID_TITLE_LOGO in ids
        )
        assertTrue(InstagramSurfaces.ID_TAB_BAR in ids)
        assertEquals(HostSurface.HOME_FEED, classify("home-scrolled"))
    }

    /**
     * The counterfactual for the rung above: with the scrolled-home marker removed from the
     * observation, the same tree falls through to OTHER_TAB.
     *
     * Rule (d) in spirit — without this, a future Instagram release that renames
     * `sticky_header_list` would leave the test above green for the wrong reason (passing on
     * `title_logo`, which it does not have) and nobody would learn the rung had stopped working.
     */
    @Test
    fun `without the scrolled-home marker the same tree reads as another tab`() {
        val observed = SurfaceFixture.observe("home-scrolled")
        val withoutMarker = observed.copy(
            presentViewIds = observed.presentViewIds - InstagramSurfaces.ID_STICKY_HEADER_LIST
        )
        assertEquals(
            "the sticky_header_list rung is load-bearing; if this is HOME_FEED the rung is dead",
            HostSurface.OTHER_TAB,
            InstagramSurfaces.classify(withoutMarker)
        )
    }

    // -----------------------------------------------------------------------------------------
    // The page gate: presence is not on-screen
    // -----------------------------------------------------------------------------------------

    /**
     * The 2026-10-07 device regression. Instagram's main activity keeps the other tabs' pages
     * attached, so the Messages tab's tree also carries the Home feed's nodes — the device readout
     * below proves `media_group` and the feed's action buttons are in it. The old presence-only
     * ladder called that screen HOME_FEED, which hard-blocked Reels on Messages and left the
     * home-feed content cover floating over it.
     *
     * The selected `tab_icon` is this app's only measured signal for WHICH attached page is visible,
     * so it must be checked before any marker. This test drives the real classifier with the real
     * Home dump's id set — the exact shape the service builds — and only the active tab changed.
     */
    @Test
    fun `the home markers on an active non-home tab are not the home feed`() {
        val onHome = SurfaceFixture.observe("home")
        assertEquals(
            "the premise: on the Home tab this very id set is the home feed",
            HostSurface.HOME_FEED,
            InstagramSurfaces.classify(onHome)
        )

        listOf("DIRECT", "SEARCH", "PROFILE").forEach { tab ->
            assertEquals(
                "$tab is the visible page; the attached Home page's nodes must not override the " +
                    "active tab",
                HostSurface.OTHER_TAB,
                InstagramSurfaces.classify(onHome.copy(activeTab = tab))
            )
        }
    }

    /**
     * The counterfactual for the gate above: with no active-tab signal the markers still win, which
     * is the pre-page-gate behaviour and the right failure mode for a host app that stops exposing
     * tab selection. Delete the gate and the test above goes green for the wrong reason.
     */
    @Test
    fun `without an active tab signal the home markers still win`() {
        val onHome = SurfaceFixture.observe("home")
        assertEquals(
            HostSurface.HOME_FEED,
            InstagramSurfaces.classify(onHome.copy(activeTab = null))
        )
    }

    /**
     * The recorded Messages-tab readout itself, not a synthetic tree: `SurfaceInspector` on the
     * bench, 2026-10-07 21:29, with `activeTab=DIRECT` and yes — the feed's own nodes
     * (`media_group`, `row_feed_button_comment/like/save`, `inline_follow_button`) in the same
     * tree. It must never classify as the Home feed. The `tab_icon` / `sticky_header_list` entries
     * are below the readout's visible fold; omitted here, and irrelevant to this answer.
     */
    @Test
    fun `the recorded messages tab readout is not the home feed`() {
        val dmIds = setOf(
            "com.instagram.android:id/action_bar_title_view",
            "com.instagram.android:id/avatar_container",
            "com.instagram.android:id/cf_hub_recycler_view",
            "com.instagram.android:id/clips_tab",
            "com.instagram.android:id/direct_quick_snap_consumption_preview",
            "com.instagram.android:id/direct_tab",
            "com.instagram.android:id/feed_tab",
            "com.instagram.android:id/igds_action_bar_title",
            "com.instagram.android:id/inbox_refreshable_thread_list_recyclerview",
            "com.instagram.android:id/indicator_background_view",
            "com.instagram.android:id/indicator_icon_view",
            "com.instagram.android:id/inline_follow_button",
            "com.instagram.android:id/location_text",
            "com.instagram.android:id/map_pog_container",
            "com.instagram.android:id/media_group",
            "com.instagram.android:id/media_option_button",
            "com.instagram.android:id/notification",
            "com.instagram.android:id/pog_bubble_text",
            "com.instagram.android:id/pog_music_note_artist_name",
            "com.instagram.android:id/pog_music_note_song_title_text",
            "com.instagram.android:id/pog_music_note_text",
            "com.instagram.android:id/pog_root_view",
            "com.instagram.android:id/profile_tab",
            "com.instagram.android:id/reposts_ufi_icon",
            "com.instagram.android:id/row_feed_button_comment",
            "com.instagram.android:id/row_feed_button_like",
            "com.instagram.android:id/row_feed_button_save"
        )
        val dm = SurfaceObservation(presentViewIds = dmIds, activeTab = "DIRECT")
        assertEquals(HostSurface.OTHER_TAB, InstagramSurfaces.classify(dm))
    }

    // -----------------------------------------------------------------------------------------
    // The Reels tab and its cover placement
    // -----------------------------------------------------------------------------------------

    /**
     * The measured `clips_tab` bounds, through production's own validator.
     *
     * `[216,1896][432,2028]` becomes `x=216, y=1896, width=216, height=132`. Bounds are parsed to
     * four plain `Int`s and handed to [TabCoverPlacement.of]; no `android.graphics.Rect` is
     * constructed anywhere in test sources, because `unitTests.isReturnDefaultValues` is deliberately
     * not set and every `android.*` call in a JVM test therefore throws.
     */
    @Test
    fun `the reels tab in the home fixture yields the measured cover placement`() {
        val locator = InstagramSurfaces.vanishableTabs.getValue("REELS")
        val tab = SurfaceFixture.find("home", locator)
        assertNotNull("clips_tab must be present on the home feed", tab)
        assertEquals(TabCoverPlacement(x = 216, y = 1896, width = 216, height = 132), tab!!.placement())
    }

    /** The same placement on the scrolled feed: the nav bar does not move when the content does. */
    @Test
    fun `the reels tab does not move when the feed scrolls`() {
        val locator = InstagramSurfaces.vanishableTabs.getValue("REELS")
        assertEquals(
            SurfaceFixture.find("home", locator)?.placement(),
            SurfaceFixture.find("home-scrolled", locator)?.placement()
        )
    }

    /**
     * The home-feed CONTENT cover, through production's own arithmetic: from the bottom of the
     * story tray `[0,231][1080,611]` to the top of the nav bar `[0,1896][1080,2028]`, spanning the
     * nav bar's width. The stories and the top bar stay usable; the algorithmic feed below them is
     * what gets covered.
     */
    @Test
    fun `the home feed content cover starts below the story tray and ends at the nav bar`() {
        val spec = InstagramSurfaces.contentCovers.getValue("REELS")
        assertEquals(HostSurface.HOME_FEED, spec.surface)

        val tray = SurfaceFixture.find("home", spec.top)
        val bar = SurfaceFixture.find("home", spec.bottom)
        assertNotNull("reels_tray_container must be present on the home feed", tray)
        assertNotNull("tab_bar must be present on the home feed", bar)

        val placement = ContentCoverPlacement.of(tray!!.placement(), bar!!.placement())
        assertEquals(TabCoverPlacement(x = 0, y = 611, width = 1080, height = 1285), placement)
    }

    /**
     * The scrolled fallback: `home-scrolled` has NO story tray — it scrolls away — so the cover
     * starts at the bottom of the top action bar `[0,77][1080,231]` instead. Everything below the
     * chrome is covered: never a guessed rectangle, and never a gap that leaves the feed exposed.
     */
    @Test
    fun `the home cover falls back to the action bar when the story tray is gone`() {
        val spec = InstagramSurfaces.contentCovers.getValue("REELS")
        assertNull(
            "the premise of this test is that the scrolled fixture has no story tray",
            SurfaceFixture.find("home-scrolled", spec.top)
        )

        val fallback = SurfaceFixture.find("home-scrolled", spec.topFallback!!)
        val bar = SurfaceFixture.find("home-scrolled", spec.bottom)
        assertNotNull(fallback)
        assertNotNull(bar)

        val placement = ContentCoverPlacement.of(fallback!!.placement(), bar!!.placement())
        assertEquals(TabCoverPlacement(x = 0, y = 231, width = 1080, height = 1665), placement)
    }

    /**
     * The Search-screen cover is gated on the Search tab being active, and its top is the Search
     * screen's OWN field. Device-captured 2026-10-08 (SurfaceInspector readout, Search tab,
     * `activeTab=SEARCH`): the Search tree carries `action_bar_search_edit_text` and a VISIBLE
     * `tab_bar`, and carries NO `action_bar_LinearLayout` — the Home action bar this spec originally
     * keyed on — so the placement never resolved and the cover never armed while reels sat in the
     * grid. This is the regression test for that.
     *
     * Still no committed Search-screen fixture, so the rectangle itself remains unpinned
     * (`docs/BACKLOG.md`); what is pinned is the gate and the one id the capture earned, so the
     * geometry gets asserted the day a Search dump is scrubbed in.
     */
    @Test
    fun `the search cover is gated on the search tab and keyed on the search field`() {
        val spec = InstagramSurfaces.contentCovers.getValue("EXPLORE")
        assertEquals("SEARCH", spec.activeTab)
        assertEquals(
            "the top must be the Search screen's own field: the Home action bar it used to key on " +
                "is absent from the Search tree (device-captured 2026-10-08)",
            listOf(InstagramSurfaces.ID_ACTION_BAR_SEARCH_EDIT_TEXT),
            spec.top.viewIds
        )
        assertTrue(
            "the nav bar must stay the bottom node: the user's Search capture read it visible=true",
            spec.bottom == NodeLocator(viewIds = listOf(InstagramSurfaces.ID_TAB_BAR))
        )
    }

    /**
     * CORRECTION of a claim this file used to make. The tab FRAMES never report selection, but the
     * ACTIVE tab's `tab_icon` child does: `home.xml` was recorded on the Home tab, and exactly one
     * of the `tab_icon` nodes is selected — inside `feed_tab`. That is the signal the Search cover's
     * active-tab gate uses; the old "selection is dead" reading was one level too high.
     */
    @Test
    fun `only the active tab's icon reports selected and it belongs to the home tab`() {
        val icons = SurfaceFixture.load("home").filter { it.viewId == InstagramSurfaces.ID_TAB_ICON }
        assertTrue("the dump must contain tab icons at all", icons.isNotEmpty())

        val selected = icons.filter { it.selected }
        assertEquals("exactly one tab icon is selected", 1, selected.size)

        val feed = SurfaceFixture.load("home").first { it.viewId == InstagramSurfaces.ID_FEED_TAB }
        val icon = selected.single()
        assertTrue(
            "the selected icon must sit inside feed_tab, the tab the dump was recorded on",
            icon.left >= feed.left && icon.right <= feed.right &&
                icon.top >= feed.top && icon.bottom <= feed.bottom
        )
    }

    /**
     * The tab FRAMES still report `selected=false`, every one of them, active included — the signal
     * is on the icon child, not the frame. Pinned so nobody "simplifies" the active-tab lookup back
     * to the frame and silently kills it.
     */
    @Test
    fun `no bottom-nav tab frame reports itself as selected`() {
        val tabIds = setOf(
            InstagramSurfaces.ID_CLIPS_TAB,
            InstagramSurfaces.ID_FEED_TAB,
            InstagramSurfaces.ID_SEARCH_TAB,
            InstagramSurfaces.ID_DIRECT_TAB,
            InstagramSurfaces.ID_PROFILE_TAB
        )
        val tabs = SurfaceFixture.load("home").filter { it.viewId in tabIds }
        assertEquals("all five tabs should be present on the home feed", 5, tabs.size)
        tabs.forEach { tab ->
            assertFalse(
                "${tab.viewId} reports selected=true — the frame never does, which is why the " +
                    "active-tab lookup reads the tab_icon child instead",
                tab.selected
            )
        }
        assertTrue("every tab must be clickable for the cover to have a tap to eat", tabs.all { it.clickable })
    }

    // -----------------------------------------------------------------------------------------
    // The Following screen: positively NOT the home feed
    // -----------------------------------------------------------------------------------------

    /**
     * The Following screen has no `title_logo`, no `tab_bar` and no active-tab signal (it is a
     * separate full-screen activity), so it classifies as UNKNOWN — which is the honest answer and
     * the safe one: no content cover's surface gate passes there, so nothing arms over it.
     */
    @Test
    fun `the following feed does not classify as the home feed`() {
        assertEquals(HostSurface.UNKNOWN, classify("following-feed"))
    }

    /** A warm relaunch of Instagram resumes on Following, so it classifies the same way. */
    @Test
    fun `a warm relaunch resumes on the following feed`() {
        assertEquals(HostSurface.UNKNOWN, classify("relaunch"))
    }

    /**
     * **There is no `clips_tab` node at all on the Following screen** — no `tab_bar` either. There is
     * no nav bar under a cover to sit on, so a cover that survived here would be an opaque rectangle
     * over the feed's content; the decider hides it, and the classifier's UNKNOWN above is the first
     * line of that defense.
     *
     * Asserted explicitly, on both Following fixtures, because the absence is the load-bearing fact.
     */
    @Test
    fun `the following screen has no reels tab and no nav bar for a cover to sit on`() {
        val locator = InstagramSurfaces.vanishableTabs.getValue("REELS")
        listOf("following-feed", "relaunch").forEach { fixture ->
            assertTrue(
                "$fixture must contain no clips_tab node",
                SurfaceFixture.findAll(fixture, locator).isEmpty()
            )
            assertNull(
                "$fixture must contain no clips_tab node",
                SurfaceFixture.find(fixture, locator)
            )
            assertFalse(
                "$fixture must contain no tab_bar",
                InstagramSurfaces.ID_TAB_BAR in SurfaceFixture.observe(fixture).presentViewIds
            )
        }
    }

    /**
     * The recorded popup-window shape: `logo-menu.xml` is the title dropdown OPEN, and the dump
     * contains the popup alone — no `tab_bar`, no `title_logo`, no title. A host app's popup window
     * can be the only window an accessibility read sees, and this fixture is the recorded proof.
     * UNKNOWN is the honest classification for it.
     */
    @Test
    fun `the open dropdown dump is a popup window alone and reads as unknown`() {
        val ids = SurfaceFixture.observe("logo-menu").presentViewIds
        assertFalse("no tab_bar in the popup", InstagramSurfaces.ID_TAB_BAR in ids)
        assertFalse("no title_logo in the popup", InstagramSurfaces.ID_TITLE_LOGO in ids)
        assertEquals(HostSurface.UNKNOWN, classify("logo-menu"))
    }

    // -----------------------------------------------------------------------------------------
    // The recorded deep-link dumps: they land on the home feed
    // -----------------------------------------------------------------------------------------

    /**
     * `variant-link` is the tree after opening `https://www.instagram.com/?variant=following` — a
     * URL the app claims and then ignores. `scheme-link` is the tree after
     * `instagram://feed?variant=following`, which does not resolve at all. `coldstart` is a cold
     * start. All three are ordinary Home-feed trees, recorded during the 2026-09-22 spike, and they
     * pin rung 2 of [InstagramSurfaces.classify] across tree variants no other fixture covers.
     */
    @Test
    fun `the deep-link and cold-start dumps are home feeds`() {
        listOf("variant-link", "scheme-link", "coldstart").forEach { fixture ->
            assertEquals(
                "$fixture was recorded as the ordinary Home feed; if this stopped being HOME_FEED " +
                    "the rung has a tree-shape gap",
                HostSurface.HOME_FEED,
                classify(fixture)
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Everything at once
    // -----------------------------------------------------------------------------------------

    /**
     * Every committed fixture classifies to its recorded screen. One map, so adding a fixture without
     * deciding what it is fails the build instead of being replayed by nothing.
     */
    @Test
    fun `every committed fixture classifies to its recorded surface`() {
        val expected = mapOf(
            "home" to HostSurface.HOME_FEED,
            "home-scrolled" to HostSurface.HOME_FEED,
            "coldstart" to HostSurface.HOME_FEED,
            "variant-link" to HostSurface.HOME_FEED,
            "scheme-link" to HostSurface.HOME_FEED,
            // The Following screen and the open dropdown are both screens with no tab_bar, no
            // title_logo and no active-tab signal: separate activity, and popup window alone.
            // UNKNOWN is the honest answer for both, and it is the safe one — no cover arms there.
            "following-feed" to HostSurface.UNKNOWN,
            "relaunch" to HostSurface.UNKNOWN,
            "logo-menu" to HostSurface.UNKNOWN
        )
        assertEquals(
            "a fixture was added or removed without deciding what it classifies as",
            expected.keys.sorted(),
            SurfaceFixture.names()
        )
        expected.forEach { (fixture, surface) -> assertEquals(fixture, surface, classify(fixture)) }
    }

    /**
     * Every fixture is the host app this adapter describes; a dump of something else proves nothing.
     *
     * Ids are allowed to be Instagram's or the framework's (`android:id/content`,
     * `android:id/navigationBarBackground` and friends appear in every real dump). Anything from a
     * third package would mean the fixture is not the tree it claims to be.
     */
    @Test
    fun `every fixture is an instagram tree`() {
        SurfaceFixture.names().forEach { name ->
            val ids = SurfaceFixture.observe(name).presentViewIds
            val own = ids.filter { it.startsWith("${InstagramSurfaces.packageName}:id/") }
            assertTrue("fixture '$name' has no ${InstagramSurfaces.packageName} resource ids", own.isNotEmpty())
            val foreign = ids.filterNot {
                it.startsWith("${InstagramSurfaces.packageName}:id/") || it.startsWith("android:id/")
            }
            assertEquals(
                "fixture '$name' carries ids from another app: $foreign",
                emptyList<String>(),
                foreign
            )
        }
    }
}
