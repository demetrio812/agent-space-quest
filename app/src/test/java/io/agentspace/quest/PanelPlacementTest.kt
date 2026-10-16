package io.agentspace.quest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class PanelPlacementTest {
    private fun assertFacesViewer(panel: Pose, viewer: Vector3) {
        // Spatial panels display their readable side along local -Z.
        val normal = panel.q * Vector3(0f, 0f, -1f)
        val toViewer = (viewer - panel.t).normalize()
        assertEquals("Readable face points at the viewer", 1f, normal.dot(toViewer), 0.0001f)
        assertEquals("Panel has no sideways roll", 0f, (panel.q * Vector3(1f, 0f, 0f)).y, 0.0001f)
    }

    @Test fun sideAndVerticalDetachesFaceViewerWithoutMovingTheDrop() {
        val viewer = Vector3(0f)
        val dashboard = Pose(Vector3(0f, 0f, 1.65f), Quaternion())
        for (dx in listOf(-540f, 540f)) {
            val panel = detachedPanelPose(dashboard, 1f, dx, -360f, 720f, viewer)
            assertEquals(dx / 720f, panel.t.x, 0.0001f)
            assertEquals(0.5f, panel.t.y, 0.0001f)
            assertEquals(1.45f, panel.t.z, 0.0001f)
            assertFacesViewer(panel, viewer)
        }
    }

    @Test fun movedViewerAndRotatedDashboardDoNotLeakDashboardRotation() {
        val viewer = Vector3(0.7f, 1.4f, -0.4f)
        val dashboard = Pose(Vector3(-0.4f, 1.6f, 1.8f), Quaternion(15f, 35f, 25f))
        val panel = detachedPanelPose(dashboard, 1.3f, -400f, 280f, 720f, viewer)
        assertFacesViewer(panel, viewer)
    }
}
