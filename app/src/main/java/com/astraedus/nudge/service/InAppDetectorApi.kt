package com.astraedus.nudge.service

import android.view.accessibility.AccessibilityNodeInfo
import com.astraedus.nudge.domain.surfaces.HostSurface

/** Thin interface extracted for testability. */
interface InAppDetectorApi {
    /**
     * @param hostSurface the network-free classification of the SAME tree read, produced by
     *   `domain/surfaces/`'s `PlatformSurfaces.classify` (Instagram's measured surface markers).
     *   Required rather than optional so the caller has to decide what to pass: it carries the
     *   only reliable home-feed signal on current Instagram, because bottom-nav tab selection is
     *   dead (`selected=false` on every tab). Null is legitimate when no adapter describes the
     *   package, e.g. YouTube and TikTok.
     */
    fun detectFeature(
        packageName: String,
        rootNode: AccessibilityNodeInfo?,
        hostSurface: HostSurface?
    ): InAppDetector.Feature?
}
