# Surface fixtures — real Instagram view hierarchies, scrubbed

Eight recorded view-hierarchy dumps, replayed by the tests in
`app/src/test/java/com/astraedus/nudge/domain/surfaces/`. They are the L2 layer of
`docs/testing-strategy.md` applied to the two node-tree features in `domain/surfaces/`: the same
`NodeLocator`s and the same `InstagramSurfaces.classify` that run in production are driven against a
real device's tree, so a fixture can **contradict** the code instead of agreeing with it.

## Provenance

| | |
|---|---|
| App | Instagram **447.0.0.55.81** |
| Device | Pixel 3, Android 12 (API 31), 1080x2160 |
| Captured | 2026-09-22 |
| Raw dumps | `~/ops/routes/nudge/research/spike-2026-09-22/*.xml` (not in this repo) |
| Scrubber | `scripts/scrub-surface-fixture.py` |

## The scrubbing rule

**The raw dumps are a transcript of one real person's feed.** They contain usernames, captions,
story labels ("<name>'s story, 0 of 27, Unseen."), ad copy and like counts, in the `text` and
`content-desc` attributes. This repository is PUBLIC, so the raw files can never be committed.

The scrubber keeps **structure byte-for-byte** — `class`, `resource-id`, `bounds`, `clickable`,
`selected`, `index`, `package` and every other attribute are untouched — and **blanks every `text`
and `content-desc` value**, except for a closed allowlist of the strings this feature reasons about:

```
Following   Favorites   Instagram Home Feed
Home        Reels       Message   Search and explore   Profile
```

Those eight are Instagram's own chrome: the five bottom-nav tab content-descriptions, the Home-feed
logo's description, and the two title-dropdown menu labels. Nothing else survives.

It is an **allowlist, not a denylist**, deliberately. A denylist has to anticipate what a caption
looks like, and it is wrong the first time somebody's username is "Following".

`SurfaceFixturePrivacyTest` walks every committed fixture and fails on any `text` or `content-desc`
value outside that allowlist, so an un-scrubbed dump cannot be committed quietly. It is the same
invariant `A11yCapturePrivacyTest` holds over `app/src/test/resources/a11y-captures/`.

To re-scrub after a new spike:

```bash
scripts/scrub-surface-fixture.py <raw>/*.xml --out app/src/test/resources/surface-fixtures
```

## The fixtures

| File | What it is | What it pins |
|---|---|---|
| `home.xml` | Instagram Home feed, at the top | `HOME_FEED` via `title_logo`; `clips_tab` bounds `[216,1896][432,2028]`; every tab FRAME reports `selected=false`, but the ACTIVE Home tab's `tab_icon` child reports `selected=true` (the active-tab signal) |
| `home-scrolled.xml` | The same feed, scrolled down | Still `HOME_FEED` — and **`title_logo` is gone**: the action bar scrolls away, which is why `InstagramSurfaces.classify` needs the `sticky_header_list` rung |
| `following-feed.xml` | The Following screen | A separate full-screen activity: **no `clips_tab`, no `tab_bar`** — classifies `UNKNOWN`, so no cover arms here (there is no nav bar to cover) |
| `relaunch.xml` | Warm relaunch of Instagram | Resumes ON Following, so `UNKNOWN` again |
| `logo-menu.xml` | The title dropdown, open | The recorded popup-window shape: the dump holds the popup ALONE — no `tab_bar`, no `title_logo` — so it reads `UNKNOWN` |
| `coldstart.xml` | Cold start of Instagram | Resets to `HOME_FEED` |
| `variant-link.xml` | After `https://www.instagram.com/?variant=following` | `HOME_FEED` — the parameter is claimed by the app and then ignored |
| `scheme-link.xml` | After `instagram://feed?variant=following` | `HOME_FEED` — the custom scheme does not resolve |

The last two are home-feed trees recorded during the 2026-09-22 spike, and they pin the classifier's
`title_logo` rung across tree variants no other fixture covers. The `Following`/`Favorites` strings
survive scrubbing in `following-feed` / `relaunch` / `logo-menu` as Instagram's own chrome.

## Reading them in a test

Use `SurfaceFixture` (test sources, same package). It parses with a regex, yields plain
`resource-id` / `class` / `text` / `content-desc` / four `Int` bounds per node, and adds no Gradle
dependency.

**Never construct an `android.graphics.Rect` in test sources.** `unitTests.isReturnDefaultValues` is
deliberately NOT set in `app/build.gradle.kts`, so every `android.*` call in a JVM test throws — and
setting it would turn every unmocked android call across ~1550 existing tests into a silent `0`/null.
Bounds are parsed straight to four ints and handed to `TabCoverPlacement.of(left, top, right, bottom)`.
