package com.astraedus.nudge.domain.surfaces

/**
 * Everything Nudge knows about ONE host app's on-screen surfaces, as pure data.
 *
 * Two features need it: **Tab Vanish** (draw a cover over the bottom-nav tab that opens a
 * hard-blocked feature, so the icon disappears and the tap is eaten) and the **content covers**
 * (blank an algorithmic region — the home feed below the story tray, the Search screen's suggestion
 * grid — instead of blocking the whole app).
 *
 * Both are node-tree features, and node trees are the thing a host app changes without warning.
 * Putting every selector behind this interface means a host-app update is a data edit in one file
 * ([InstagramSurfaces]) rather than an archaeology session across `service/`, and it means the
 * same selectors that run in production can be driven against a recorded hierarchy dump by an
 * ordinary JVM test. Nothing in this package imports `android.*`.
 */
interface PlatformSurfaces {

    /** The host app this adapter describes, e.g. `com.instagram.android`. */
    val packageName: String

    /**
     * Feature key (matches `InAppDetector.Feature.key`, e.g. `"REELS"`) -> the bottom-nav tab that
     * opens it.
     *
     * Keyed by the feature string rather than by the enum because the enum lives in `service/` and
     * this package may not depend on it — `domain` does not import `service`. The integrator wires
     * `Feature.key` in at the call site.
     */
    val vanishableTabs: Map<String, NodeLocator>

    /**
     * The ARGB colour the tab cover is painted with, so the vanished tab reads as empty nav bar
     * rather than as a black rectangle.
     *
     * Takes the system night-mode flag because host apps follow the system theme and there is no
     * way to sample a host app's actual pixel colour from an accessibility service.
     */
    fun navBarColor(nightMode: Boolean): Int

    /** Which of this app's screens the given node-presence observation is. */
    fun classify(observation: SurfaceObservation): HostSurface

    /**
     * The view ids [classify] keys on, so the observer can resolve them with the framework's
     * indexed lookup instead of relying on the bounded tree walk.
     *
     * This exists because the harvest that builds [SurfaceObservation.presentViewIds] is
     * deliberately bounded (`HostNodeFinder.harvestViewIds`), and a real host-app tree can be large
     * enough that the walk never reaches a marker deep in it. Device-found 2026-10-07: on an
     * Instagram build with a big feed, the walk returned ZERO ids, `classify` answered UNKNOWN on
     * the Home feed, and the home cover's surface gate failed even though a targeted
     * `findAccessibilityNodeInfosByViewId(tab_bar)` found the node. The classifier's marker set is
     * small and stable, so asking for it directly is cheaper AND correct at any tree size.
     *
     * Defaults to empty so an adapter that never declares its markers keeps the old behaviour.
     */
    val classifierViewIds: Set<String>
        get() = emptySet()

    /**
     * Bottom-nav tabs whose ACTIVE state is exposed in the tree, keyed by an adapter-defined surface
     * key (e.g. `"SEARCH"`). Empty for an adapter whose host app exposes none.
     *
     * This exists because some surfaces have no positive container marker of their own. Instagram's
     * Search/Explore screen is the worked example: it has no unique view id we have measured, but
     * the ACTIVE tab's `tab_icon` child reports `selected=true` while every tab FrameLayout reports
     * `selected=false` (measured on IG 447, `home.xml`; see `InstagramSurfacesFixtureTest`). That
     * distinction is the one reliable "which tab is this" signal we have.
     */
    val activeTabs: Map<String, NodeLocator>
        get() = emptyMap()

    /** The child id whose `selected` state marks the active tab, or null when the app exposes none. */
    val activeTabIndicatorId: String?
        get() = null

    /**
     * Regions a hard-blocked feature may COVER instead of blocking whole, keyed by feature key
     * (matching `InAppDetector.Feature.key`).
     *
     * This is Tab Vanish's sibling for content that is not a tab: Instagram's home feed below the
     * story tray, and the Search screen's suggestion grid below the search box. Both leave the
     * functional chrome (stories, the search field) usable, which is the whole point — a cover over
     * a scrolling feed is only viable where the covered region is exactly the part the user does not
     * need to reach.
     */
    val contentCovers: Map<String, ContentCoverSpec>
        get() = emptyMap()

    /**
     * Every id the DEBUG readout (`SurfaceInspector`, via `HostNodeFinder.markerDiagnostics`) should
     * report `present=/visible=/matches=` for: the classifier's markers, the vanishable tabs, and
     * every content-cover spec node — top, fallback and bottom.
     *
     * DERIVED from the adapter's own declarations, never hand-listed, so a cover added tomorrow is
     * diagnosable from the phone the day it ships, and this list can never drift from the selectors
     * it exists to explain. Exists for the 2026-10-08 report family: a content cover can fail to arm
     * with its gate passing and its ids present in the tree, and the facts that separate the three
     * causes (a renamed id; an attached off-screen duplicate ahead of the live node; a single node
     * that is present but not visible) are per-id present/visible/match-count — facts the phone-side
     * readout is the only place a user in the field can reach.
     */
    val diagnosticViewIds: Set<String>
        get() = buildSet {
            addAll(classifierViewIds)
            vanishableTabs.values.forEach { locator -> addAll(locator.viewIds) }
            contentCovers.values.forEach { spec ->
                addAll(spec.top.viewIds)
                spec.topFallback?.viewIds?.let(::addAll)
                addAll(spec.bottom.viewIds)
            }
        }
}

/**
 * A rectangle to cover on a host-app surface, derived from two nodes: it starts at the BOTTOM of
 * [top] and ends at the TOP of [bottom], spanning [bottom]'s width.
 *
 * @param top the node whose bottom edge is the cover's top (Instagram: the story tray).
 * @param topFallback used when [top] is not on screen — the scrolled-away story tray. Null means
 *   "no cover" rather than a guessed rectangle.
 * @param bottom the node whose top edge is the cover's bottom (Instagram: the nav bar).
 * @param surface only active while the classifier reports this surface; null = no surface gate.
 * @param activeTab only active while the adapter's active tab is this key; null = no tab gate.
 * @param label what the covered region is, for the cover's TalkBack description.
 *
 * Every gate fails toward HIDING the cover. A cover drawn in the wrong place is worse than no cover:
 * it hides something the user needed or eats a tap meant for it.
 */
data class ContentCoverSpec(
    val top: NodeLocator,
    val bottom: NodeLocator,
    val label: String,
    val topFallback: NodeLocator? = null,
    val surface: HostSurface? = null,
    val activeTab: String? = null
)

/**
 * The pure arithmetic for a [ContentCoverSpec]'s rectangle, separated from the node lookups so a
 * recorded hierarchy can drive it (`docs/testing-strategy.md` rule (b)).
 */
object ContentCoverPlacement {
    /**
     * The rectangle between the two nodes, or null when either is absent or the result is not a
     * real rectangle (see [TabCoverPlacement.of]). The cover spans [bottom]'s width so it lines up
     * with the chrome underneath it.
     */
    fun of(top: TabCoverPlacement?, bottom: TabCoverPlacement?): TabCoverPlacement? {
        if (top == null || bottom == null) return null
        return TabCoverPlacement.of(
            left = bottom.x,
            top = top.y + top.height,
            right = bottom.x + bottom.width,
            bottom = bottom.y
        )
    }
}

/**
 * Which screen of the host app is showing.
 *
 * Deliberately coarse. Nudge only needs to answer two questions — is this the home feed (the
 * surface the content covers and the Reels-equivalent feature detection gate on), and do we know
 * anything at all — and a coarse closed set keeps every consumer's `when` exhaustive.
 */
enum class HostSurface {
    /** The app's main/home feed: the algorithmic feed the content covers gate on. */
    HOME_FEED,

    /** A different bottom-nav tab (search, messages, profile): positively not the home feed. */
    OTHER_TAB,

    /** We recognise nothing. Reel player, story, DM thread, settings — change NOTHING. */
    UNKNOWN
}

/**
 * ONE tree read, reduced to the facts a classifier needs — and deliberately nothing else.
 *
 * It holds no nodes. The service walks the live tree once, collects the resource ids it saw and the
 * active tab, and hands over this value; a JVM test builds the identical value from a recorded
 * hierarchy dump. Because it is plain data, the classification RULE is the same object under test
 * as in production, which is the only way a fixture can contradict the code instead of agreeing
 * with it.
 *
 * Keeping nodes out is also a privacy constraint. Nudge has no internet permission and its device
 * fixtures are committed to a PUBLIC repository; an observation that carried arbitrary node text
 * would make every future fixture a transcript of someone's feed. It carries ids and selection
 * state only — structure, never content. See `app/src/test/resources/surface-fixtures/README.md`.
 *
 * @param presentViewIds every fully-qualified resource id seen in this tree read **and visible to
 *   the user**. The visibility half is load-bearing, not a tidy-up: a host app that keeps other
 *   pages attached (Instagram) leaves their ids in the tree while a different page is on screen, and
 *   a classifier reading presence alone called the Messages and Profile pages the Home feed
 *   (device-reported 2026-10-07). `HostNodeFinder.presentViewIds` / `harvestViewIds` are where the
 *   filter lives.
 * @param activeTab the active bottom-nav tab the adapter resolved from this same tree read, or null
 *   when the app exposes no selection or none is selected. It is the page's own identity, and it has
 *   to live in the observation because node PRESENCE does not mean on-screen: Instagram's main
 *   activity keeps the other tabs' pages attached, so a DM inbox's tree also carries the home feed's
 *   `title_logo` / `sticky_header_list` / feed-post ids. Device-captured 2026-10-07 (`SurfaceInspector`
 *   readout, Instagram on the DM tab): `activeTab=DIRECT` in a tree that contained `media_group`,
 *   `row_feed_button_like/comment/save` and `inline_follow_button`. A classifier reading presence
 *   alone called that screen the Home feed, which hard-blocked Reels on the Messages tab and left the
 *   home-feed content cover floating over it. The classifier's page gate reads this field; see
 *   `InstagramSurfaces.classify`.
 */
data class SurfaceObservation(
    val presentViewIds: Set<String>,
    val activeTab: String? = null
)
