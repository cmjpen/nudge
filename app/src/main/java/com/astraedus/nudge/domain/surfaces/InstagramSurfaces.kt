package com.astraedus.nudge.domain.surfaces

/**
 * Instagram's surfaces, measured on **Instagram 447.0.0.55.81, Pixel 3, 1080x2160, 2026-09-22**.
 * The hierarchy dumps that back every constant below are committed, scrubbed, at
 * `app/src/test/resources/surface-fixtures/` and are replayed by the tests in this package.
 *
 * ## This file is the seam
 *
 * **Every `com.instagram.android:id/…` string in the codebase belongs here.** That is the point of
 * the adapter: when Instagram ships a release that renames a view, exactly one file changes, and
 * `InstagramIdsLiveInOneFileTest` fails the build if a second file starts carrying those ids.
 * (`service/InAppDetector.kt` is a grandfathered exception — it predates this seam and still holds
 * the reel-player container ids. It is the ONE legacy holder the test allows.)
 *
 * ## The measured facts, and what each one forced
 *
 * - `tab_bar` sits at `[0,1896][1080,2028]` and stays put while the feed scrolls. It is **absent**
 *   on the Following screen, in the reel player, and in stories and DM threads — which is exactly
 *   why its presence is a usable "a bottom-nav screen is showing" signal, and why the cover has to
 *   be torn down rather than left to float when it goes away.
 * - `clips_tab` sits at `[216,1896][432,2028]` with content-description "Reels".
 * - **Every tab FRAME reports `selected=false` in the dump, the active one included — but the ACTIVE
 *   tab's `tab_icon` child reports `selected=true`.** The signal is one level below the frame, and
 *   [activeTabs]/[activeTabIndicatorId] are what read it. The tab COVER still keys on `clips_tab`
 *   BOUNDS; selection is used to recognise the Search screen and, since the 2026-10-07 capture below,
 *   as the page gate that keeps one tab's attached nodes from impersonating another tab.
 * - **Presence is not on-screen: the main activity keeps the other tabs' pages attached.** Device-
 *   captured 2026-10-07 (`SurfaceInspector` readout, Messages tab): the tree carried `media_group`,
 *   `row_feed_button_like/comment/save` and `inline_follow_button` — the home feed's own nodes — and
 *   a classifier reading presence alone called it HOME_FEED, hard-blocking Reels on Messages and
 *   leaving the home-feed cover floating there. Two defenses, and both are needed: the selected
 *   `tab_icon` is the measured signal for WHICH attached page is showing and [classify] checks it
 *   before any marker, while `HostNodeFinder` drops ids whose nodes are not visible to the user —
 *   which is what covers the Profile page, where Instagram exposes no tab selection at all
 *   (`tabStates` all false, device-captured 2026-10-07) and a Reels hard block fired there too.
 * - `title_logo` (ImageView, content-description "Instagram Home Feed") marks the Home feed, but it
 *   is NOT clickable — its clickable parent is `action_bar_title_view`, a ViewAnimator at
 *   `[132,97][948,210]`.
 * - Following is a SEPARATE full-screen activity: `action_bar_title` reads "Following", and
 *   `tab_bar`/`clips_tab`/`feed_tab` are gone from the tree entirely (`following-feed`, `relaunch`
 *   fixtures — which pin that this screen never classifies as the home feed, so no cover arms there).
 */
object InstagramSurfaces : PlatformSurfaces {

    override val packageName: String = "com.instagram.android"

    // ---------------------------------------------------------------------------------------
    // View ids. THE one place they live.
    // ---------------------------------------------------------------------------------------

    /** The bottom-nav container. Present on every tab screen, absent everywhere else. */
    const val ID_TAB_BAR = "com.instagram.android:id/tab_bar"

    /** The Reels tab. The tab-vanish cover is drawn over THIS node's bounds. */
    const val ID_CLIPS_TAB = "com.instagram.android:id/clips_tab"

    /**
     * The Search/Explore tab ("Discover"). NOT covered: the tab must stay usable so the search box
     * can be reached. The active state of this tab is what gates the search-page content cover.
     */
    const val ID_SEARCH_TAB = "com.instagram.android:id/search_tab"

    /** The Home tab. Present in every bottom-nav dump; used only for active-tab detection. */
    const val ID_FEED_TAB = "com.instagram.android:id/feed_tab"

    /** The Messages tab. Present in every bottom-nav dump; used only for active-tab detection. */
    const val ID_DIRECT_TAB = "com.instagram.android:id/direct_tab"

    /** The Profile tab. Present in every bottom-nav dump; used only for active-tab detection. */
    const val ID_PROFILE_TAB = "com.instagram.android:id/profile_tab"

    /**
     * The icon INSIDE a tab. Every tab shares this id, and the ACTIVE tab's icon reports
     * `selected=true` while every tab FrameLayout reports `selected=false`.
     *
     * This is the one reliable "which tab is active" signal measured on IG 447 (`home.xml`: the
     * active Home tab's `tab_icon` is selected; the other four are not). The old feature-detection
     * code was reported dead because it keyed on the TAB nodes, which never report selection — the
     * signal was one level down all along.
     */
    const val ID_TAB_ICON = "com.instagram.android:id/tab_icon"

    /**
     * The story tray ("reels tray") at the top of the Home feed, `[0,231][1080,611]` in the Home
     * dump. Its bottom edge is where the home-feed content cover starts, leaving the stories usable.
     * ABSENT from `home-scrolled` — the tray scrolls away — which is what [ContentCoverSpec.topFallback]
     * is for.
     */
    const val ID_REELS_TRAY_CONTAINER = "com.instagram.android:id/reels_tray_container"

    /**
     * The top action-bar container, `[0,77][1080,231]` in every Home dump. The fallback top for the
     * home cover once the story tray has scrolled away. **ABSENT from the Search screen** — device-
     * captured 2026-10-08 (SurfaceInspector readout, Search tab): the Explore cover keyed on this as
     * its top and never armed there, because the Search screen's top chrome is its own field
     * ([ID_ACTION_BAR_SEARCH_EDIT_TEXT]).
     */
    const val ID_ACTION_BAR_LINEAR_LAYOUT = "com.instagram.android:id/action_bar_LinearLayout"

    /**
     * The Search screen's search field. THE top node for the Explore content cover: the cover starts
     * at this field's BOTTOM edge, so the box stays tappable and everything below it (the suggestion
     * grid) is covered. Device-captured 2026-10-08 — this id is the only chrome the Search readout
     * showed for that bar, and it was present in every Search tree read.
     */
    const val ID_ACTION_BAR_SEARCH_EDIT_TEXT = "com.instagram.android:id/action_bar_search_edit_text"

    /** The Home-feed logo. Present only on Home, and only while the action bar is not scrolled away. */
    const val ID_TITLE_LOGO = "com.instagram.android:id/title_logo"

    /**
     * The Home feed's sticky story-tray header. The one Home marker that SURVIVES scrolling.
     *
     * Present in every Home-feed dump (`home`, `home-scrolled`, `coldstart`, `variant-link`,
     * `scheme-link`) and absent from every non-home dump. It exists because `title_logo` does not
     * survive a scroll — Instagram's action bar scrolls away — and without a second marker a user
     * scrolling the feed would read as OTHER_TAB, and the home-feed content cover's surface gate
     * would drop the cover the moment they scrolled. See [classify].
     */
    const val ID_STICKY_HEADER_LIST = "com.instagram.android:id/sticky_header_list"

    // ---------------------------------------------------------------------------------------
    // Text labels. Version- and locale-fragile by nature; see NodeLocator.
    // ---------------------------------------------------------------------------------------

    /** `title_logo`'s content description. */
    const val DESC_HOME_FEED = "Instagram Home Feed"

    /** `clips_tab`'s content description. Fallback for [ID_CLIPS_TAB]. */
    const val DESC_REELS_TAB = "Reels"

    /** `search_tab`'s content description. Fallback for [ID_SEARCH_TAB]. */
    const val DESC_SEARCH_TAB = "Search and explore"

    // ---------------------------------------------------------------------------------------
    // Colours. THE one place to adjust them.
    // ---------------------------------------------------------------------------------------

    /**
     * ARGB `0xFFFFFFFF`. **Measured** off the nav bar in light mode on the spike device.
     *
     * An accessibility service cannot sample a host app's pixels, so this is a constant rather than
     * a reading. If a future Instagram release tints the nav bar, this is the one line to change.
     */
    const val NAV_BAR_COLOR_LIGHT: Int = -0x1

    /**
     * ARGB `0xFF000000`. **Assumed, not measured** — the spike only captured light mode. Same
     * caveat and same one line to change as [NAV_BAR_COLOR_LIGHT].
     */
    const val NAV_BAR_COLOR_DARK: Int = -0x1000000

    // ---------------------------------------------------------------------------------------
    // The adapter surface.
    // ---------------------------------------------------------------------------------------

    /**
     * Reels only. The Search/Explore tab is deliberately NOT covered any more: the user needs the
     * search box, so Explore is enforced by covering the suggestion GRID on that screen (see
     * [contentCovers]) rather than by removing the tab. Explore still has no screen detector of its
     * own; [activeTabs] is what recognises the Search screen for that cover.
     */
    override val vanishableTabs: Map<String, NodeLocator> = mapOf(
        "REELS" to NodeLocator(
            viewIds = listOf(ID_CLIPS_TAB),
            contentDescriptions = listOf(DESC_REELS_TAB)
        )
    )

    /**
     * The active tab's `tab_icon` is selected; every tab FrameLayout is not. Measured on IG 447.
     */
    override val activeTabIndicatorId: String = ID_TAB_ICON

    /**
     * The active tabs whose page can legitimately classify as [HostSurface.HOME_FEED]: Home is the
     * feed, and Reels is the same content named by its own tab (the tab opens the reel player, whose
     * `clips_viewer_*` ids are the primary signal; these markers are the fallback).
     *
     * Every other active tab means the visible page is NOT the feed, and [classify] must not let a
     * home-feed marker found ANYWHERE in the tree override that. See [classify] for the capture that
     * forced the rule.
     */
    private val HOME_LIKE_ACTIVE_TABS = setOf("HOME", "REELS")

    /**
     * Every bottom-nav tab, keyed by the surface name the service reasons about. Only `"SEARCH"` is
     * consumed today (the search-page content cover's gate); the rest are declared so the mapping is
     * complete data rather than a one-off lookup.
     */
    override val activeTabs: Map<String, NodeLocator> = mapOf(
        "HOME" to NodeLocator(viewIds = listOf(ID_FEED_TAB)),
        "SEARCH" to NodeLocator(viewIds = listOf(ID_SEARCH_TAB)),
        "REELS" to NodeLocator(viewIds = listOf(ID_CLIPS_TAB)),
        "DIRECT" to NodeLocator(viewIds = listOf(ID_DIRECT_TAB)),
        "PROFILE" to NodeLocator(viewIds = listOf(ID_PROFILE_TAB))
    )

    /**
     * The two algorithmic regions a hard-blocked feature covers instead of blocking the whole app:
     *
     * - **REELS -> the home feed below the story tray.** Top is `reels_tray_container` (so stories
     *   stay usable), falling back to the top action bar once the tray has scrolled away
     *   (`home-scrolled` has no tray), bottom is the nav bar. Gated on the surface classifier
     *   reporting HOME_FEED.
     * - **EXPLORE -> the Search screen's suggestion grid, reels included.** Top is the Search
     *   screen's OWN search field ([ID_ACTION_BAR_SEARCH_EDIT_TEXT] — the cover starts at that
     *   field's bottom edge, so the box stays tappable; device-captured 2026-10-08, because the
     *   `action_bar_LinearLayout` top this spec originally used is a HOME id and absent from the
     *   Search tree, so the cover never armed there), bottom is the nav bar, and the gate is the
     *   Search tab being active — so it can never fire on Messages or Profile. When the user taps
     *   into the field the screen changes and the nav bar disappears, which hides this cover: the
     *   search-active screen is deliberately untouched.
     *
     * Both fail toward HIDING: a missing node means no cover, never a guessed rectangle.
     */
    override val contentCovers: Map<String, ContentCoverSpec> = mapOf(
        "REELS" to ContentCoverSpec(
            top = NodeLocator(viewIds = listOf(ID_REELS_TRAY_CONTAINER)),
            topFallback = NodeLocator(viewIds = listOf(ID_ACTION_BAR_LINEAR_LAYOUT)),
            bottom = NodeLocator(viewIds = listOf(ID_TAB_BAR)),
            surface = HostSurface.HOME_FEED,
            label = "Suggested feed"
        ),
        "EXPLORE" to ContentCoverSpec(
            top = NodeLocator(viewIds = listOf(ID_ACTION_BAR_SEARCH_EDIT_TEXT)),
            bottom = NodeLocator(viewIds = listOf(ID_TAB_BAR)),
            activeTab = "SEARCH",
            label = "Search suggestions"
        )
    )

    /**
     * What [classify] keys on. Resolved by TARGETED lookup, not by the bounded harvest — see the
     * interface KDoc. Device-found 2026-10-07: a feed big enough to exhaust the walk's budget made
     * all three markers invisible, `classify` answered UNKNOWN on the Home feed, and the home cover
     * never armed even though `tab_bar` was findable by id.
     */
    override val classifierViewIds: Set<String> =
        setOf(ID_TITLE_LOGO, ID_TAB_BAR, ID_STICKY_HEADER_LIST)

    override fun navBarColor(nightMode: Boolean): Int =
        if (nightMode) NAV_BAR_COLOR_DARK else NAV_BAR_COLOR_LIGHT

    /**
     * Classify one tree read into a [HostSurface].
     *
     * The ladder, and the evidence for each rung:
     *
     * 1. **The ACTIVE tab names a non-home page -> [HostSurface.OTHER_TAB].** Presence alone does not
     *    mean on-screen: Instagram's main activity keeps the other tabs' pages attached, so the tree
     *    read on one tab carries another tab's nodes. Device-captured 2026-10-07 (`SurfaceInspector`
     *    readout, Instagram on the DM tab, `activeTab=DIRECT`): the Messages tab's tree contained
     *    `media_group`, `row_feed_button_like/comment/save` and `inline_follow_button` — the Home
     *    feed's post nodes — and the old presence-only ladder called that screen the Home feed, which
     *    hard-blocked Reels on Messages and left the home-feed content cover floating over it. The
     *    selected `tab_icon` is the one measured signal that says WHICH attached page is showing, so
     *    when it is available it is checked before any marker. Null (app exposes no selection, or
     *    none is selected — the reel player, a story, a DM thread) falls through to the markers.
     *
     * 2. **`title_logo` present -> [HostSurface.HOME_FEED].** The logo is unique to the Home feed
     *    (`home`, `coldstart`, `variant-link`, `scheme-link`).
     *
     * 3. **`tab_bar` AND `sticky_header_list` present -> [HostSurface.HOME_FEED].** This rung is
     *    the scrolled Home feed, and it is here because the measured dumps forced it:
     *    `home-scrolled` has **no `title_logo`** — Instagram's action bar scrolls away with the
     *    content. Without this rung a scrolled feed falls to rung 4, reads as OTHER_TAB, and the
     *    home-feed content cover's surface gate drops the cover the moment the user scrolls — a
     *    cover that hides itself on the very scroll that makes it matter. `sticky_header_list` is
     *    present in all five Home dumps and in no non-home dump, which is as much as the spike can
     *    prove. Rung 1 is what keeps this fail-open from mattering on another tab that carries the
     *    marker too.
     *
     * 4. **`tab_bar` present, nothing above matched -> [HostSurface.OTHER_TAB].** A bottom-nav
     *    screen that is positively not the Home feed.
     *
     * 5. **Otherwise -> [HostSurface.UNKNOWN].** The reel player, a story, a DM thread, settings.
     *    `tab_bar` is absent from all of them. We know nothing, so we change nothing.
     *
     * Note what is NOT here: tab FRAME selection. Every tab frame reports `selected=false` in
     * the dump, including the active one, so selection is read off the `tab_icon` child instead
     * ([activeTabs] / [activeTabIndicatorId]) and travels in [SurfaceObservation.activeTab].
     */
    override fun classify(observation: SurfaceObservation): HostSurface {
        val activeTab = observation.activeTab
        if (activeTab != null && activeTab !in HOME_LIKE_ACTIVE_TABS) return HostSurface.OTHER_TAB

        val ids = observation.presentViewIds
        if (ID_TITLE_LOGO in ids) return HostSurface.HOME_FEED
        if (ID_TAB_BAR in ids) {
            return if (ID_STICKY_HEADER_LIST in ids) HostSurface.HOME_FEED else HostSurface.OTHER_TAB
        }
        return HostSurface.UNKNOWN
    }
}
