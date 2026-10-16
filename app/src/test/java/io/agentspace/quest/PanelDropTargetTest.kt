package io.agentspace.quest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test

class PanelDropTargetTest {
    private val origin = Vector3(0f)
    private val forward = Vector3(0f, 0f, 1f)
    @Test fun nearestVisiblePanelWinsIncludingSourcePanel() {
        val back = PanelBounds(1, Pose(Vector3(0f, 0f, 2f)), 1f, 1f, 1f)
        val source = back.copy(id = -1, pose = Pose(Vector3(0f, 0f, 1f)))
        assertEquals(-1, nearestPanelHit(origin, forward, listOf(back, source))!!.id)
        assertEquals(1, nearestPanelHit(origin, forward, listOf(back))!!.id)
    }
    @Test fun emptySpaceBehindAndParallelRaysAreNotDropTargets() {
        val panel = PanelBounds(0, Pose(Vector3(0f, 0f, 2f)), 1f, 1f, 1f)
        assertNull(panelRayHit(origin, Vector3(1f, 0f, 1f).normalize(), panel))
        assertNull(panelRayHit(origin, Vector3(0f, 0f, -1f), panel))
        assertNull(panelRayHit(origin, Vector3(1f, 0f, 0f), panel))
    }
    @Test fun rotatedScaledPanelsUseTheirCurrentBounds() {
        val rotation = Quaternion(12f, 50f, 7f)
        val panel = PanelBounds(3, Pose(Vector3(1f, 1f, 3f), rotation), 1.5f, 1f, 1f)
        val inside = panel.pose.t + rotation * Vector3(0.7f, 0.6f, 0f)
        assertEquals(3, panelRayHit(origin, inside.normalize(), panel)!!.id)
        val outside = panel.pose.t + rotation * Vector3(0.8f, 0f, 0f)
        assertNull(panelRayHit(origin, outside.normalize(), panel))
    }
}
