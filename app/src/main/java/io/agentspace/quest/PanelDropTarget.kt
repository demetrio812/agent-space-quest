package io.agentspace.quest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import kotlin.math.abs

internal data class PanelBounds(val id: Int, val pose: Pose, val scale: Float, val width: Float, val height: Float)
internal data class PanelRayHit(val id: Int, val distance: Float, val point: Vector3)

/** Test actual transformed panel rectangles, choosing the closest visible surface. */
internal fun panelRayHit(origin: Vector3, direction: Vector3, panel: PanelBounds): PanelRayHit? {
    val inverse = panel.pose.q.inverse()
    val localOrigin = inverse * (origin - panel.pose.t)
    val localDirection = inverse * direction
    if (abs(localDirection.z) < 0.00001f || panel.scale <= 0f) return null
    val distance = -localOrigin.z / localDirection.z
    if (distance <= 0f) return null
    val localPoint = localOrigin + localDirection * distance
    if (abs(localPoint.x) > panel.width * panel.scale / 2f || abs(localPoint.y) > panel.height * panel.scale / 2f) return null
    return PanelRayHit(panel.id, distance, origin + direction * distance)
}

internal fun nearestPanelHit(origin: Vector3, direction: Vector3, panels: List<PanelBounds>): PanelRayHit? =
    panels.mapNotNull { panelRayHit(origin, direction, it) }.minByOrNull { it.distance }
