package io.agentspace.quest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3

/** Project a tile drag onto the dashboard plane, slightly closer to the viewer. */
internal fun detachedPanelPose(
    dashboard: Pose,
    scale: Float,
    dxDp: Float,
    dyDp: Float,
    dpPerMeter: Float,
    viewerPosition: Vector3,
): Pose {
    val dx = (dxDp / dpPerMeter).coerceIn(-1.4f, 1.4f) * scale
    val dy = (-dyDp / dpPerMeter).coerceIn(-0.9f, 0.9f) * scale
    val position = dashboard.t + dashboard.q * Vector3(dx, dy, -0.20f)
    return viewerFacingPose(position, viewerPosition, dashboard.q)
}

internal fun viewerFacingPose(position: Vector3, viewerPosition: Vector3, fallback: Quaternion = Quaternion()): Pose {
    // A panel's readable side is local -Z, so its +Z axis points away from the viewer.
    // Use world up instead of inheriting any tilt/roll from the dashboard.
    val awayFromViewer = position - viewerPosition
    val rotation = if (awayFromViewer.length() < 0.001f) fallback else {
        val up = if (kotlin.math.abs(awayFromViewer.normalize().y) > 0.999f) {
            Vector3(0f, 0f, 1f)
        } else Vector3(0f, 1f, 0f)
        Quaternion.lookRotation(awayFromViewer, up)
    }
    return Pose(position, rotation)
}
