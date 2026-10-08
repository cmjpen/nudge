package com.astraedus.nudge.service

/**
 * Debug-only record of the last host-app tree read, so a screen with no committed fixture can be
 * inspected without a laptop and adb.
 *
 * The repo's capture-first rule (`docs/testing-strategy.md` (c)) needs a real tree to design a
 * selector against. The bench workflow gets one with `scripts/a11y-capture.sh`; a user on a phone
 * with a build in their hand does not have that script. `SurfaceObserver.observe` already
 * walks the tree on every debounced host-app tick and already collects every view id in it, so
 * keeping the latest set costs nothing and turns "send me an accessibility dump" into "open the
 * screen, then open Nudge → Settings → Developer Options → Last host-app surface".
 *
 * **Debug builds only, and it holds view ids and a package name — never text.** The same privacy
 * rule the surface fixtures are scrubbed under: a resource id is structure, and node text on these
 * screens is somebody's feed.
 */
object SurfaceInspector {

    /** Package of the last recorded tree, or null when nothing has been recorded yet. */
    @Volatile
    var lastPackage: String? = null
        private set

    /** Every view id seen in the last recorded tree, sorted for a stable readout. */
    @Volatile
    var lastViewIds: List<String> = emptyList()
        private set

    /** The active bottom-nav tab the adapter resolved, or null — the Search cover's gate. */
    @Volatile
    var activeTab: String? = null
        private set

    /** Every tab icon seen, as `tab=selected` — the raw material of the active-tab signal. */
    @Volatile
    var tabStates: List<String> = emptyList()
        private set

    /**
     * One line per classifier marker, `present` and `visible` — the two facts that separate a
     * renamed id (`present=false`) from an attached off-screen page (`present=true visible=false`).
     * See `HostNodeFinder.markerDiagnostics`.
     */
    @Volatile
    var markers: List<String> = emptyList()
        private set

    /** Wall-clock time of the last record, for the UI to say how stale the dump is. */
    @Volatile
    var updatedAtMs: Long = 0L
        private set

    /**
     * The last few cover/surface decisions, oldest first.
     *
     * This is the half a static id dump cannot answer: a cover that appears and then disappears has
     * a REASON, and every reason is decided in code that a phone user cannot read out of logcat.
     * Forty entries is roughly the last minute and a half of debounced ticks.
     */
    private const val MAX_EVENTS = 40
    private val events = ArrayDeque<String>()

    fun record(
        packageName: String,
        viewIds: Collection<String>,
        activeTab: String?,
        tabStates: List<String>,
        nowMs: Long,
        markers: List<String> = emptyList()
    ) {
        lastPackage = packageName
        lastViewIds = viewIds.sorted()
        this.activeTab = activeTab
        this.tabStates = tabStates
        this.markers = markers
        updatedAtMs = nowMs
    }

    /** Append one decision line; keeps only the newest [MAX_EVENTS]. */
    fun event(line: String) {
        synchronized(events) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(line)
        }
    }

    private fun eventsSnapshot(): List<String> = synchronized(events) { events.toList() }

    /** Everything the Developer Options row shows, or a plain "nothing captured" line. */
    fun readout(): String {
        val pkg = lastPackage ?: return "Nothing captured yet. Open Instagram, then come back here."
        return buildString {
            appendLine("package=$pkg")
            appendLine("activeTab=${activeTab ?: "(none detected)"}")
            appendLine("tabStates=${tabStates.ifEmpty { listOf("(none found)") }.joinToString(", ")}")
            if (markers.isNotEmpty()) {
                appendLine("markers=${markers.joinToString("; ")}")
            }
            appendLine("capturedAtMs=$updatedAtMs")
            appendLine("ids=${lastViewIds.size}")
            lastViewIds.forEach { appendLine(it) }
            val snapshot = eventsSnapshot()
            if (snapshot.isNotEmpty()) {
                appendLine("--- last cover decisions (oldest first) ---")
                snapshot.forEach { appendLine(it) }
            }
        }
    }
}
