# Instagram Detection QA Walkthrough (issues #18, #22)

Run this WHILE `tools/qa/ig-walkthrough-capture.sh` is running in a terminal — it does the
screenshotting/logging, you do the tapping. It will pause on ENTER between every step below;
read the step, do it, then hit ENTER in the terminal.

Whole thing takes about 5 minutes. Nothing here needs precision — real usage is exactly the
point (the QA is about whether *natural* Instagram use trips detection correctly).

## 0. Setup (before the script starts capturing)

1. Open **Nudge**.
2. **Settings** → tap **"Version"** 7 times → a new **"Developer Options"** section appears.
3. Turn **"Debug Logging"** ON (switch next to it).
   - Skipping this means the script's captures will come back empty even if everything
     is working — Nudge only writes these diagnostic logs when this is on.
4. Go to your rules and confirm (or create) one for **Instagram**:
   - **Reels** feature-blocking is turned ON for it.
   - Block mode is **DELAY** or **HARD_BLOCK** — not disabled/off.
   - No daily time limit set on this rule (limits can mask whether detection itself fired).
5. Leave that rule's config screen open — the script's first prompt screenshots it.

Run the script now: `tools/qa/ig-walkthrough-capture.sh`. Follow its prompts; they mirror the
cases below in order.

## The seven cases (~30s each)

### a. Reels tab (issue #18)
Open Instagram. Tap the **Reels** tab at the bottom. Let a reel play for ~10 seconds.
**Expect:** Nudge's block overlay (delay countdown or hard-block screen) appears.

### b. Home feed (issue #18 — fixed)
From Instagram's home feed, scroll down and keep scrolling/watching for ~30 seconds total.
**Expect:** with a Reels HARD block and Tab Vanish on, a black box covers the feed BELOW the story
tray — stories and the top bar stay usable, and the full-screen block screen does NOT appear on the
home feed. If the cover does not appear, that is a finding: the measured markers
(`reels_tray_container`, `action_bar_LinearLayout`, `tab_bar`) may have been renamed by an Instagram
update.

### c. Explore (issue #18 — grid covered, search kept)
Tap **Explore** (magnifying glass). Browse the grid for a few seconds.
**Expect:** with an Explore HARD block, a black box covers the suggestion grid below the search box;
the search box itself stays tappable. Tap into the search box and type: the cover disappears (the
search-active screen has no nav bar), and results/recent searches are untouched. Tapping into a
video still fires the reel-player block if a Reels rule is on.

### d. Explore grid, no video (issue #22 — false positive check)
Tap **Explore** again. This time just **scroll the grid** for ~30 seconds — do NOT tap into any
video or post.
**Expect:** NO overlay should appear. If one does, that's a false positive — note when.

### e. Stories (issue #22 — false positive check)
Tap into someone's **Story** from the top row and watch 2-3 stories through.
**Expect:** NO overlay should appear.

### f. Reels tab then away (issue #22 — false positive check)
Tap the **Reels** tab, then **immediately** tap back to **Home** before a reel has fully loaded
(don't wait for it to play). Then just use the home feed normally for ~30 seconds.
**Expect:** NO overlay should appear late/stuck on the home feed from the aborted Reels-tab
visit. If the overlay appears at a weird moment or won't dismiss, note exactly when.

### g. Messages and Profile tabs (2026-10-07 regression)
Tap the **Messages** tab (DM inbox) and stay there ~10 seconds, open a **DM thread** with someone
and stay ~10 seconds, then go to your **Profile** tab and stay ~10 seconds.
**Expect:** NO overlay and NO black cover on any of the three screens. The small cover over the Reels
tab in the bottom bar is the intended Tab Vanish. Instagram keeps the other tabs' pages attached, so
those trees carry the home feed's nodes; if a full-screen Reels block or the feed cover appears here,
the classifier's page gates have stopped working. Check with the Nudge debug readout (Settings →
Developer Options → Last host-app surface): on Messages it should read `activeTab=DIRECT`, and the
`markers=` line should show the home markers as `present=true visible=false` — that line is what
separates an attached off-screen page from a renamed id.

## When done

The script prints where everything landed (`~/Pictures/screenshots/nudge-qa-ig-*`). You don't
need to do anything else with the files — hand off to whoever's doing the writeup.

If anything felt ambiguous mid-walk (e.g. you weren't sure if a video "counted" as playing, or
the overlay appeared but you're not sure which case triggered it), just say so when you report
back — that's useful signal, not noise.
