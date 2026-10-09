package com.astraedus.nudge.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard on WHERE the Reels tab cover is driven from inside [NudgeAccessibilityService].
 *
 * ## Why this has to read source
 *
 * Every value-level fact about the cover is already tested at its own layer: `TabCoverPresence`
 * decides whether a signal keeps it, `TabCoverDecider` decides show/move/hide, `TabCoverPlacement`
 * does the arithmetic, and `TabCoverOverlayManager` owns the window. All of those stay green if the
 * SERVICE stops calling them, or calls them from the wrong place — which is the only way this feature
 * can actually break the user's screen.
 *
 * `docs/TESTING.md` names this residue explicitly: "nothing at this layer can prove the adapter
 * actually calls the decision class on every callback — that half stays a source-level contract
 * test." Same argument, same shape as [EventDispatchOrderContractTest] next door.
 *
 * The assertions are about PLACEMENT, not spelling, so a faithful refactor re-pins cleanly.
 */
class TabCoverWiringContractTest {

    private val servicePath = "main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt"
    private val observerPath = "main/java/com/astraedus/nudge/service/SurfaceObserver.kt"

    private fun read(relative: String): String {
        val candidates = listOf(File("src/$relative"), File("app/src/$relative"))
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relative not found from ${File("").absolutePath}"))
            .readText()
    }

    /** Comments in this file quote the code they explain, so they must not be read as code. */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    private val code: String by lazy { stripComments(read(servicePath)) }
    private val observerCode: String by lazy { stripComments(read(observerPath)) }

    private fun bodyOf(functionSignature: String): String =
        bodyOfIn(code, functionSignature, servicePath)

    private fun bodyOfIn(source: String, functionSignature: String, path: String): String {
        val start = source.indexOf(functionSignature)
        assertTrue("$functionSignature not found in $path", start >= 0)
        // Brace-match from the signature's opening brace to its close.
        var depth = 0
        var i = source.indexOf('{', start)
        assertTrue("no body for $functionSignature", i >= 0)
        val from = i
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(from, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces after $functionSignature")
    }

    /**
     * THE one that matters. The cover must be torn down from `applyForegroundSignal`, which runs
     * once per event above every early return in `onAccessibilityEvent`.
     *
     * Hiding it from the branches that each mean "the user is somewhere else" instead is the shape
     * that produced #5, #7, #19 and #28 — every one of them a path that returned before something
     * that had to happen. There are at least six such exits here (Home, system surface, our own UI,
     * a live block overlay, a transient window, globally disabled), and a cover left behind by the
     * one nobody thought of is a black rectangle floating over the launcher.
     */
    @Test
    fun `the cover is torn down from the one place that sees every classification`() {
        val body = bodyOf("private fun applyForegroundSignal(signal: ForegroundSignal)")
        assertTrue(
            "applyForegroundSignal must hand the signal to the tab cover manager, so the cover's " +
                "lifetime is decided in the same single place the sitting and the launch guard are. " +
                "Got:\n$body",
            Regex("""tabCoverOverlayManager\(\)\s*\.\s*onForegroundSignal\s*\(\s*signal\s*\)""")
                .containsMatchIn(body)
        )
    }

    /**
     * `applyForegroundSignal` itself has to stay above every early return, which
     * [EventDispatchOrderContractTest] already pins for the other two consumers. This asserts the
     * cover rides that same call rather than being given its own, later call site — a second call
     * site is how the two consumers drift apart.
     */
    @Test
    fun `the cover has no second teardown call site in the event dispatch`() {
        val onEvent = bodyOf("override fun onAccessibilityEvent(event: AccessibilityEvent?)")
        assertTrue(
            "onAccessibilityEvent must not call the tab cover manager directly; it reaches the " +
                "cover through applyForegroundSignal. Got a direct call in:\n$onEvent",
            !onEvent.contains("tabCoverOverlayManager()")
        )
    }

    /**
     * A globally-disabled Nudge must behave as if uninstalled. `hideAllOverlays` is that path for
     * the awareness overlays and for the screen-off broadcast; the cover is enforcement and belongs
     * with them.
     */
    @Test
    fun `hiding all overlays hides the cover too`() {
        val body = bodyOf("private fun hideAllOverlays()")
        assertTrue(
            "hideAllOverlays must hide the tab cover -- it is reached when Nudge is switched off " +
                "and when the screen goes off, and a cover surviving either is enforcement the user " +
                "has turned off. Got:\n$body",
            body.contains("tabCoverOverlayManager()") && body.contains("hide()")
        )
    }

    /**
     * The surface maintenance must reuse the tree read `detectAndEvaluateFeature` already performs.
     * A second `rootInActiveWindow` read on this path is a real cost, not a style point: a device
     * capture measured ~26k content-change events during a few minutes of Instagram use.
     */
    @Test
    fun `surface maintenance rides the existing tree read`() {
        // The signature itself (multi-line in the source): the window flag needs its default, or
        // the content-change path's plain call stops compiling / stops meaning "debounced".
        val detectStart = code.indexOf("private fun detectAndEvaluateFeature(")
        assertTrue("detectAndEvaluateFeature must exist", detectStart >= 0)
        assertTrue(
            "the window flag must have a default so the content-change path's plain call keeps " +
                "meaning 'debounced'",
            code.substring(detectStart, detectStart + 200).contains("fromWindowChange: Boolean = false")
        )

        val body = bodyOf("private fun detectAndEvaluateFeature(")
        assertTrue(
            "detectAndEvaluateFeature must call maintainHostSurfaces with the rootNode it already " +
                "read. Got:\n$body",
            Regex("""maintainHostSurfaces\s*\(\s*packageName\s*,\s*rootNode\s*\)""").containsMatchIn(body)
        )
        assertTrue(
            "detectAndEvaluateFeature must read rootInActiveWindow exactly once and share it; a " +
                "second read on this path is paid on every surviving content-change event",
            Regex("""rootInActiveWindow""").findAll(body).count() == 1
        )
    }

    /**
     * Host-app view ids must not appear in the service. The adapter registry is the seam that makes
     * YouTube a registry entry rather than an edit to this 2000-line file, and an id inlined here is
     * how that seam quietly stops being one.
     */
    @Test
    fun `the service names no host app view ids`() {
        assertTrue(
            "NudgeAccessibilityService must not contain a host app's view ids; they live in the " +
                "PlatformSurfaces adapter",
            !code.contains("com.instagram.android:id/")
        )
    }

    /**
     * EVERY vanishable feature is asked about, not just the first tab found in the tree.
     *
     * The single-cover version used `firstNotNullOfOrNull`, which silently made a second entry dead:
     * whichever tab came first in the adapter map always won the slot, so an Explore hard block would
     * never produce a cover. The loop now walks the ADAPTER's keys, which is also what lets a cover
     * that should go away be hidden (`placement == null`) instead of skipped. Placement, not
     * spelling — a faithful refactor of the loop body re-pins cleanly.
     */
    @Test
    fun `every vanishable feature is evaluated, not just the first found in the tree`() {
        val body = bodyOf("private suspend fun maintainTabCover(")
        assertTrue(
            "maintainTabCover must walk surfaces.vanishableTabs.keys. Got:\n$body",
            Regex("""for \(feature in surfaces\.vanishableTabs\.keys\)""").containsMatchIn(body)
        )
        assertTrue(
            "each feature's own rule decision must be asked (detectedFeature = feature)",
            body.contains("detectedFeature = feature")
        )
    }

    /**
     * Region covers ride the SAME tree read and the SAME window manager as tab covers.
     *
     * The nodes are resolved in `maintainHostSurfaces` (where the read is) and applied through
     * `tabCoverOverlayManager`; a second read or a second window stack is how the two cover kinds
     * would drift apart. The ACTIVE TAB — the page gate in the classifier and the Explore cover's
     * own gate — is part of that one read too, carried on the observation, so neither gate can be
     * fed a second, differently-timed lookup. Pinned at source level because no value-level test can
     * see where a call sits, the same argument as the rest of this file.
     */
    @Test
    fun `content covers ride the existing tree read and the one manager`() {
        val maintain = bodyOf("private fun maintainHostSurfaces(")
        assertTrue(
            "the region placements must be resolved on the same tree read. Got:\n$maintain",
            maintain.contains("HostNodeFinder.contentCoverPlacement(")
        )
        assertTrue(
            "maintainContentCovers must be driven from the same launch as the tab covers",
            maintain.contains("maintainContentCovers(")
        )
        assertTrue(
            "the page gate must read the classifier's own observation, not a second lookup. " +
                "Got:\n$maintain",
            maintain.contains("observation.activeTab")
        )

        val observe = bodyOfIn(observerCode, "fun observe(", observerPath)
        assertTrue(
            "the observation must carry the active tab resolved on the same tree read: it is the " +
                "classifier's page gate and the Explore cover's gate, and with it null both " +
                "silently stop firing. Got:\n$observe",
            observe.contains("HostNodeFinder.activeTab(")
        )

        val apply = bodyOf("private suspend fun maintainContentCovers(")
        assertTrue(
            "the region cover must go through the one window manager. Got:\n$apply",
            apply.contains("tabCoverOverlayManager()")
        )
    }

    /**
     * A covered home feed must not ALSO get the full-screen block: the block covers the stories,
     * which is exactly what the region cover exists to leave usable. And the stand-down must gate on
     * a RESOLVABLE placement — standing down on the feature alone would leave the feed unenforced
     * whenever the cover could not be placed.
     */
    @Test
    fun `the full block stands down when a content cover enforces the surface`() {
        val body = bodyOf("private fun detectAndEvaluateFeature(")
        assertTrue(
            "detectAndEvaluateFeature must have the content-cover stand-down guard. Got:\n$body",
            body.contains("coveredByContentCover")
        )
        assertTrue(
            "the stand-down must gate on the resolvable placement, not on the feature alone",
            body.contains("host.contentCovers[feature.key] != null")
        )
    }
}
