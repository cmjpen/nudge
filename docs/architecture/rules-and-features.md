# Rule capabilities and in-app feature detection

Covers what a *rule* can express beyond a plain block: schedules, in-app feature blocking
(Shorts/Reels/TikTok), grayscale, the user-editable overlay message pools, and the rule editor.
**Read before touching `domain/` rule models, `InAppDetector`, `NudgeMessages`, the rule editor, or the Settings screen.**

## Capabilities

- **Schedule-based rules** — day-of-week + time-of-day, overnight schedule support (spans midnight)
- **In-app feature blocking** — YouTube Shorts, Instagram Reels (Reels tab, full-screen player and home feed), TikTok detection via AccessibilityService
- **Content covers** — a hard-blocked Instagram feature can cover a REGION instead of the whole app: the home feed below the story tray (Reels), and the Search screen's suggestion grid below the search box (Explore). The functional chrome — stories, the search field — stays usable. Same overlay machinery as Tab Vanish, one window per covered region.
- **Grayscale mode** — force screen to grayscale (requires ADB: `adb shell pm grant com.astraedus.nudge android.permission.WRITE_SECURE_SETTINGS`). Grayscale guide in Settings.
- **Rotating motivational messages** — shown on overlay screens when blocks trigger. **User-editable (v1.6.0)**: defaults live in `ui/overlay/NudgeMessages.kt` (delayTitles/delaySubtitles/hardBlockMessages); users override via Settings → Personalize → "Edit block messages" (`ui/screens/settings/MessagesEditorScreen.kt`), stored as 3 multiline strings in `NudgePreferences` (`customDelayTitles`/`customDelaySubtitles`/`customHardBlockMessages`, one message per line, empty = defaults). `NudgeMessages.resolvePool(customRaw, default)` is the pure resolver; `BlockOverlayActivity` reads the prefs once via `runBlocking{ first() }` before `setContent` (avoids a default→custom flash) and passes resolved pools into the overlay composables (which still `remember { pool.random() }`).
- **Instagram home feed detection** — the home feed is **Reels-equivalent**: a REELS feature rule covers it, and home-feed scrolling counts toward the interaction counter and auto-kick the same as the Reels tab. It is named by the measured markers in `InstagramSurfaces` (`title_logo` while the action bar is visible, or `tab_bar` + `sticky_header_list` once the feed is scrolled and the action bar has gone), classified by `InstagramSurfaces.classify` and handed to `InAppDetector` on the same tree read. It must never key on bottom-nav tab FRAME selection: every tab frame reports `selected=false` (issue [#18](https://github.com/astraedus/nudge/issues/18), pinned by `InstagramSurfacesFixtureTest`) — though the ACTIVE tab's `tab_icon` child does report it, which is what recognises the Search screen **and what gates every marker-based rung: the main activity keeps the other tabs' pages attached, so one tree read can carry another page's nodes. Device-captured 2026-10-07, the Messages tab's tree contained the home feed's post ids; presence-only classification called it HOME_FEED, hard-blocked Reels on Messages and left the home-feed cover floating there. When the selected tab is known and is not Home/Reels, the classifier answers OTHER_TAB before reading any marker; a null active tab (Following, the player, a popup) falls back to presence — with one more guard under it: `HostNodeFinder` reports an id only when a matching node is VISIBLE to the user, so an attached off-screen page's markers cannot count. That visibility half is what covers screens where Instagram exposes no tab selection at all: the Profile page (`tabStates` all false, device-captured 2026-10-07) had the attached Home page's markers in its tree and a Reels hard block fired there.** With Tab Vanish on, a REELS hard block no longer covers the whole home feed: it covers the feed **below the story tray**, leaving the stories usable. **Explore still has no detector** — no positive marker for that screen has been captured — but a HARD_BLOCK on Explore covers the **Search screen's suggestion grid below the search box**, gated on the Search tab being active. Real Explore detection still needs a device capture; see `docs/BACKLOG.md`.
- **Rule editor UX** — info tooltips on all sections, block mode descriptions, per-app rules summary with enable/disable
- **Settings** — version links to GitHub repo, source code & feedback link
- **Tab Vanish + content covers (v1.18.0 / this change)** — while a rule covering a host app's feature resolves to a
  HARD block, Nudge draws touchable covers over the surfaces that feature owns: the feature's bottom-nav tab
  (Tab Vanish), and — for Instagram — the algorithmic CONTENT of the screens it is about. Per-rule, **default ON**.
  The Reels tab, the home feed below the story tray, and the Search screen's suggestion grid below the search box
  each get their own cover window; a feature can own several at once. See "Tab Vanish and the platform-surface
  adapter" below.

> **Removed: the Following steer (v1.18.0 experimental, removed this change).** It was the one thing Nudge did
> *inside* another app — clicking Instagram's title dropdown and its "Following" row — which depended on Instagram's
> English menu labels and its layout, and it was brittle by construction. The toggle, the `FollowingSteer` state
> machine, `SteerRecipe`, the per-rule `followingSteer` column (dropped in DB migration 11->12) and its export key
> are all gone. The recorded fixtures it produced (`following-feed`, `relaunch`, `logo-menu`, the dead deep links)
> are kept: they still pin that non-home screens never classify as the home feed, which is what keeps the content
> covers from arming there.

## Tab Vanish and the platform-surface adapter

### The problem, and what is actually possible

An `AccessibilityService` cannot edit or remove another app's views — `AccessibilityNodeInfo` is immutable once
delivered. Every OSS Reels blocker therefore does detect-then-BACK, with a visible flicker; Nudge does
detect-then-overlay, which is better but still lands *after* the tap. What we CAN do is draw our own window over
the tab's screen rectangle. `TYPE_ACCESSIBILITY_OVERLAY` (the type the awareness overlays already use) needs no
extra permission and is a *trusted* window — Android 12's untrusted-touch blocking explicitly exempts
accessibility overlays. Drop `FLAG_NOT_TOUCHABLE` and it eats the tap; paint it the nav-bar colour and the icon
is gone.

It is deliberately NOT a complete wall. Reels still open from Explore tiles, profile tabs, DM shares and inline
feed cards, and the existing `clips_viewer_*` player detection stays exactly as it was as the backstop. Tab Vanish
and the content covers are ADDITIONAL enforcement, never a replacement. The Search-screen cover is the clearest
example: it covers the suggestion GRID, not the screen — the search box above it is untouched, and the search-active
screen (no nav bar, keyboard up) has no cover at all. Explore has no detector, so a route that never shows the grid
(a deep link straight into a profile) is not covered.

### Where each decision lives

| Question | Owner |
|---|---|
| Which nodes describe this app's surfaces | `domain/surfaces/PlatformSurfaces` + `InstagramSurfaces` (pure data) |
| Is a cover wanted right now | `BlockDecision.Block.tabVanish`, set from the rule that DECIDED the block |
| Where a tab cover goes | `TabCoverPlacement.of(bounds)` (pure; null for degenerate bounds) |
| Where a region cover goes | `ContentCoverPlacement.of(top, bottom)` (pure; null unless both nodes resolve) |
| Which tab is active (the classifier's page gate, and the Search cover's gate) | `HostNodeFinder.activeTab` + `PlatformSurfaces.activeTabs`/`activeTabIndicatorId`, carried on `SurfaceObservation.activeTab` |
| Show, move, hide or leave alone | `TabCoverDecider` (pure) |
| Is the cover still valid at all | `TabCoverPresence.shouldKeep(signal, coveredPackage)` (pure, exhaustive over `ForegroundSignal`) |
| The windows themselves | `service/TabCoverOverlayManager`, keyed by feature (tab key vs `feature|content`) |
| Turning a locator into a node | `service/HostNodeFinder` — the only place that does node lookups for this feature |

Three constraints that are not obvious and are each a bug already paid for elsewhere in this repo:

- **The cover's views are `AwarenessOverlayWindow` types, never bare ones.** A `TYPE_ACCESSIBILITY_OVERLAY` owned
  by Nudge fires window and content events carrying OUR package. Without the identity class name the
  `EventClassifier` reads them as `OwnUi`, `BlockLaunchGate` moves the foreground to Nudge, and every later block
  is dropped with `DROP_FOREGROUND_MOVED` — so a cover meant to add enforcement would silently remove it. That is
  [#41](https://github.com/astraedus/nudge/issues/41) with a new window; `AwarenessOverlayContractTest` covers the
  new manager for exactly that reason.
- **Teardown happens in ONE place — `applyForegroundSignal`.** The cover is that method's third consumer, beside
  the sitting and the launch guard, and for the same reason: it sits above every early return in
  `onAccessibilityEvent`. Hiding from the six branches that each mean "the user is somewhere else" is the shape
  that produced #5, #7, #19 and #28, each of which was a path that returned before something that had to happen.
  `TabCoverPresence` is exhaustive over `ForegroundSignal` with no `else`, so a new signal forces a decision.
  `AwarenessOverlay` must KEEP the cover: the cover is itself an awareness overlay, so tearing down on that
  signal would have it order itself away the moment it appeared.
- **A tap the cover eats is NOT a confrontation.** No `UsageEvent`, no `wasBlocked` row, no arrival claim. The
  user never saw a block screen, and counting it would inflate the Blocked count the way
  [#36](https://github.com/astraedus/nudge/issues/36) did. `TabCoverCountingContractTest` reads the manager's
  source and forbids the whole vocabulary.

### Which modes vanish the tab

Only a decision that resolves to `HARD_BLOCK`: an unconditional hard block, or a daily budget spent on a
`BlockMode.NONE` + `dailyLimitMinutes` rule — Anti's headline behaviour, *"once you've done your daily timer it'll
just disappear"*. DELAY, HOLD and BREATHING deliberately do NOT vanish the tab: those modes are friction with a
choice at the end of it, and the tab has to stay tappable to reach the interstitial.

The flag is read off the rule that DECIDED the block, not `applicableRules.any { it.tabVanish }`. With `any {}` a
user who switched the cover off on their Reels rule would still get it from some unrelated rule still carrying the
default `true`.

## Following steer (removed)

Removed this change. What remains below is the recorded history of why it was shaped the way it was, kept because
the invariants it forced are now carried by the features that survived it.

Instagram has a real chronological Following feed, but it resets to Home on every cold start and Meta has said it
will never be the default. We cannot filter the algorithmic feed — no view removal, and covering scrolling cards
is not viable — so the steer navigated: click the title dropdown, then "Following".

Measured on Instagram 447.0.0.55.81 (Pixel 3, fixtures in `app/src/test/resources/surface-fixtures/`):

- `title_logo` is NOT clickable; its clickable parent `action_bar_title_view` is. The steer walked up to the nearest
  clickable ancestor for exactly this reason — a lesson that is gone with it.
- Following is a **separate full-screen activity**, not a filter on the Home tab: the header becomes
  `action_bar_title` reading "Following", and `tab_bar` / `clips_tab` are gone from the tree entirely. The Reels
  tab is unreachable from the Following screen, and the cover still hides whenever `clips_tab` is not found.
- **Deep links are dead.** `https://www.instagram.com/?variant=following` is claimed by the app but the parameter
  is ignored, and `instagram://feed?variant=following` does not resolve at all. The `variant-link.xml` and
  `scheme-link.xml` fixtures are the recorded proof, asserted as landing on `HOME_FEED`.

The departure-verdict lesson the steer's visit lifecycle forced is still true and now has one fewer consumer:
a screen-off is not a departure, a departure verdict belongs to `SittingTracker`, and there is no second timer.
See `docs/architecture/accessibility-event-pipeline.md` for that history.

## Adding another app

`PlatformSurfacesRegistry.all` is the one registration point. An app needs: its package name, a
`vanishableTabs` entry per feature, a nav-bar colour per night mode, and a `classify` that maps node presence to a
`HostSurface`. Nothing in `service/` may name a host app's view ids — `InstagramIdsLiveInOneFileTest` enforces
that, with `InAppDetector` grandfathered as the one legacy holder.

**Roadmap — YouTube Shorts tab vanish: same adapter, needs YT selectors.** The Shorts tab node is stable enough;
what is missing is a bench capture of the Shorts tab's bounds, plus a decision on the Shorts shelf inside
Subscriptions (it scrolls, so the cover trick does not apply there). TikTok is a third candidate, unmeasured.
