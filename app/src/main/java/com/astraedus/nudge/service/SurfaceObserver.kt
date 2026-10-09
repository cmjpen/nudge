package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.domain.surfaces.PlatformSurfaces
import com.astraedus.nudge.domain.surfaces.SurfaceObservation
import com.astraedus.nudge.util.NudgeLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads one host-app tree into a pure [SurfaceObservation].
 *
 * ## It decides nothing
 *
 * Which screen the observation means is the adapter's judgement ([PlatformSurfaces.classify]); this
 * class gathers facts and does not classify. It holds no state at all, so there is nothing here
 * that a JVM test of the classification cannot reach.
 *
 * ## Never throws
 *
 * A tree read on a host app whose window has gone away must not take the accessibility service
 * down, because that would stop blocking completely. Every path catches and returns an empty
 * observation, the same rule [HostNodeFinder] and `InAppDetector` run on.
 */
@Singleton
class SurfaceObserver @Inject constructor(
    private val logger: NudgeLogger
) {

    /**
     * One tree read, reduced to the facts [PlatformSurfaces.classify] needs.
     *
     * Collects the union of every id the adapter names — the classifier's markers and the vanishable
     * tabs' locator ids — and the ACTIVE bottom-nav tab. That union is deliberately derived from the
     * adapter rather than hand-listed here: a host-app update that renames an id is then one edit in
     * the adapter, and this method cannot fall out of step with the classifier that consumes its
     * output.
     *
     * It does **not** classify. Which screen the observation means is the adapter's judgement, and
     * keeping the two apart is what lets a committed hierarchy fixture feed the identical classifier
     * production runs.
     *
     * Carries no node text by construction: [SurfaceObservation] has nowhere to put any. Nudge's
     * fixtures are committed to a PUBLIC repo and an observation that carried arbitrary node text
     * would make each one a transcript of somebody's feed.
     */
    fun observe(
        root: AccessibilityNodeInfo,
        surfaces: PlatformSurfaces
    ): SurfaceObservation = try {
        // Adapter-named ids first, by indexed framework lookup: unbounded in depth and cheap, so a
        // tab deep in a long feed is never missed. This includes the classifier's own markers, which
        // is what keeps `classify` correct on a tree too big for the bounded walk below to reach
        // them (device-found 2026-10-07).
        val wantedIds = LinkedHashSet<String>()
        wantedIds += surfaces.classifierViewIds
        surfaces.vanishableTabs.values.forEach { wantedIds += it.viewIds }

        // Then the bounded harvest, which is what makes the set COMPLETE for a classifier keying on
        // markers the adapter never had to publish (Instagram's sticky story-tray header, say).
        // Union, not either/or: the harvest is depth-capped and the targeted lookups are not.
        // Both halves report only what is VISIBLE: the tree carries attached pages, and a marker on
        // one of those must not reach `classify` (see `HostNodeFinder.presentViewIds`).
        val present = HostNodeFinder.presentViewIds(root, wantedIds) +
            HostNodeFinder.harvestViewIds(root, prefix = "${surfaces.packageName}:id/")

        // The active tab, resolved ONCE off the same root and carried in the observation. Presence
        // does not mean on-screen — Instagram keeps the other tabs' pages attached, so the Messages
        // tab's tree also holds the home feed's markers (device-captured 2026-10-07). The classifier
        // reads this as the page's identity before any marker; the Explore cover's gate reads it from
        // the same observation. An indexed lookup of the indicator id, not a walk.
        val activeTab = HostNodeFinder.activeTab(root, surfaces)

        // Debug builds only: keep the latest tree's ids so a screen with no committed fixture can
        // be inspected from the phone itself (Settings → Developer Options → Last host-app surface).
        // A DEEPER walk here than production pays for, deliberately: the first device to use this
        // tool was one whose tree outgrew the production bound, and a diagnostic that is bounded the
        // same way cannot explain the very failure it exists for. No text is stored.
        if (BuildConfig.DEBUG) {
            // The diagnostic keeps the UNFILTERED harvest: a screen that misclassifies is explained
            // by the ids that are present but NOT visible, which the observation deliberately drops.
            // And a stale event whose root is already Nudge's own window records nothing — the last
            // REAL host-app tree must survive, or the readout answers ids=0 exactly when someone is
            // trying to report a screen (device-hit 2026-10-07: the Search tab's readout was
            // clobbered this way).
            //
            // The marker lines are driven by the adapter's own `diagnosticViewIds` — classifier
            // markers PLUS every cover spec node — so a cover that fails to arm with its gate
            // passing (device-reported 2026-10-08, twice) can be explained from the phone: which of
            // its ids was absent, invisible, or duplicated by an attached page.
            val diagnosticIds = present + HostNodeFinder.harvestViewIds(
                root,
                prefix = "${surfaces.packageName}:id/",
                limit = 5_000,
                visibleOnly = false
            )
            if (diagnosticIds.isNotEmpty()) {
                SurfaceInspector.record(
                    packageName = surfaces.packageName,
                    viewIds = diagnosticIds,
                    activeTab = activeTab,
                    tabStates = HostNodeFinder.tabIconStates(root, surfaces),
                    markers = HostNodeFinder.markerDiagnostics(root, surfaces.diagnosticViewIds),
                    nowMs = System.currentTimeMillis()
                )
            }
        }

        SurfaceObservation(
            presentViewIds = present,
            activeTab = activeTab
        )
    } catch (e: Exception) {
        logger.w("surface observe failed package=${surfaces.packageName}", e)
        SurfaceObservation(presentViewIds = emptySet())
    }
}