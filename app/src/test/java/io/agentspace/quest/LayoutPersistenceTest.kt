package io.agentspace.quest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LayoutPersistenceTest {
    @Test fun untrackedStagePoseCannotBecomeTheWorkspaceOrigin() {
        assertFalse(isUsableStageViewerPose(Pose()))
        assertFalse(isUsableStageViewerPose(Pose(Vector3(0f, 0f, 0f), Quaternion(-1f, 0f, 0f, 0f))))
        assertFalse(isUsableStageViewerPose(Pose(Vector3(Float.NaN, 1f, 0f))))
        assertTrue(isUsableStageViewerPose(Pose(Vector3(0f, 1.2f, 0f))))
        assertTrue(isUsableStageViewerPose(Pose(Vector3(-1f, .4f, 2f), Quaternion(0f, 135f, 0f))))
    }

    private fun example(): SavedLayout {
        val w = DemoWorkspace()
        val group = w.detach("api")!!
        w.moveToGroup("ui", group)
        w.selectDashboard("search")
        w.setDraft("ui", "line one\nUnicode: café = test \\ done")
        w.paused = true
        return SavedLayout(w.layoutState(), mapOf(
            -1 to SavedPanel.capture(Pose(Vector3(0f, 0f, 1.7f)), 1f, Pose()),
            group to SavedPanel.capture(Pose(Vector3(-1f, .3f, 1.4f), Quaternion(5f, -35f, 0f)), 1.4f, Pose()),
        ))
    }

    @Test fun versionTwoStoresTabIdsWithoutDelimiterAmbiguityAndReadsVersionOne() {
        val saved = example().let { it.copy(workspace = it.workspace.copy(groups = listOf(SessionGroup(0, listOf("host,one", "session,two"), "host,one")))) }
        val encoded = LayoutCodec.encode(saved)
        assertTrue(encoded.contains("format.version=2"))
        assertEquals(saved, LayoutCodec.decode(encoded))
        val legacy = """format.version=1
restore.location=AROUND_ME
window.count=1
window.0.id=0
window.0.tabs=api,ui
window.0.selected=ui
origin.pose=0,0,0,1,0,0,0
origin.scale=1
"""
        assertEquals(listOf("api", "ui"), LayoutCodec.decode(legacy)!!.workspace.groups.single().sessionIds)
    }

    @Test fun oversizedTabGroupRetainsBoundedEntriesInsteadOfDisappearing() {
        val ids = (0..256).map { "session-$it" }
        val saved = example().let { it.copy(workspace = it.workspace.copy(groups = listOf(SessionGroup(0, ids, ids.first())))) }
        val loaded = LayoutCodec.decode(LayoutCodec.encode(saved))!!
        assertEquals(1, loaded.workspace.groups.size)
        assertEquals(ids.take(256), loaded.workspace.groups.single().sessionIds)
    }

    @Test fun oversizedSaveCannotReplaceLastReadableSnapshot() {
        val directory = Files.createTempDirectory("agentspace-size-limit").toFile()
        try {
            val file = directory.resolve("layout.properties")
            val store = LayoutFileStore(file)
            val initial = example()
            store.save(initial)
            val tooLarge = initial.copy(workspace = initial.workspace.copy(drafts = mapOf("api" to "x".repeat(300000))))
            assertThrows(IllegalArgumentException::class.java) { store.save(tooLarge) }
            assertEquals(initial, LayoutFileStore(file).load())
        } finally { directory.deleteRecursively() }
    }

    @Test fun coldFileReopenRestoresGroupsSelectionDraftsAndPanelSize() {
        val directory = Files.createTempDirectory("agentspace-layout").toFile()
        try {
            val file = directory.resolve("layout.properties")
            val saved = example()
            LayoutFileStore(file).save(saved)
            val loaded = LayoutFileStore(file).load()!!
            assertEquals(saved, loaded)
            val w = DemoWorkspace()
            w.restoreLayoutState(loaded.workspace)
            assertEquals(listOf("api", "ui"), w.group(0)!!.sessionIds)
            assertEquals("ui", w.group(0)!!.selectedId)
            assertEquals("search", w.dashboardSelection)
            assertTrue(w.paused)
            assertEquals(saved.workspace.drafts["ui"], w.draft("ui"))
            assertEquals(1.4f, loaded.panels[0]!!.scale, 0f)
        } finally { directory.deleteRecursively() }
    }

    @Test fun closingWindowsOverwritesSavedLayoutRatherThanResurrectingThem() {
        val directory = Files.createTempDirectory("agentspace-layout").toFile()
        try {
            val file = directory.resolve("layout.properties")
            val store = LayoutFileStore(file)
            store.save(example())
            val w = DemoWorkspace()
            w.restoreLayoutState(store.load()!!.workspace)
            w.closeAll(); w.showGrid()
            store.save(SavedLayout(w.layoutState(), emptyMap()))
            val reopened = LayoutFileStore(file).load()!!
            assertTrue(reopened.workspace.groups.isEmpty())
            assertNull(reopened.workspace.dashboardSelection)
            assertTrue(reopened.panels.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun restorationFiltersMissingSessionsDuplicateOwnershipAndInvalidGroupIds() {
        val w = DemoWorkspace()
        w.restoreLayoutState(WorkspaceLayoutState("missing", listOf(
            SessionGroup(0, listOf("api", "missing", "api"), "missing"),
            SessionGroup(1, listOf("api", "ui"), "api"),
            SessionGroup(1, listOf("docs"), "docs"),
            SessionGroup(99, listOf("search"), "search"),
        ), false, mapOf("missing" to "stale", "ui" to "preserve")))
        assertNull(w.dashboardSelection)
        assertEquals(listOf(SessionGroup(0, listOf("api"), "api"), SessionGroup(1, listOf("ui"), "ui")), w.groups)
        assertEquals("preserve", w.draft("ui"))
        assertEquals("", w.draft("missing"))
    }

    @Test fun arrangementRebasesAroundNewOpeningPositionWithoutLosingOrientation() {
        val oldOrigin = Pose(Vector3(1f, 1.6f, 2f), Quaternion(0f, 35f, 0f))
        val relative = Pose(Vector3(-.8f, .2f, 1.5f), Quaternion(12f, -40f, 3f))
        val saved = SavedPanel.capture(oldOrigin * relative, 1.3f, oldOrigin)
        val newOrigin = Pose(Vector3(-2f, 1.4f, 4f), Quaternion(0f, -75f, 0f))
        val restored = saved.restore(newOrigin)
        val expected = newOrigin * relative
        assertTrue((expected.t - restored.t).length() < .0001f)
        assertTrue(expected.q.isWithinAngleDegrees(restored.q, .1f))
    }

    @Test fun corruptAndUnknownFilesDoNotCrashOrInventLayouts() {
        assertNull(LayoutCodec.decode("not a saved layout"))
        assertNull(LayoutCodec.decode("format.version=999"))
        val text = LayoutCodec.encode(example())
        assertEquals(example(), LayoutCodec.decode(text))
        val broken = text.replace(Regex("(?m)^panel\\.0\\.pose=.*$"), "panel.0.pose=NaN,0,0,1,0,0,0")
        val recovered = LayoutCodec.decode(broken)!!
        assertFalse(recovered.panels.containsKey(0))
        assertEquals(1, recovered.workspace.groups.size)
    }

    @Test fun roomPositionModeKeepsSavedOriginAndAroundMeUsesCurrentViewer() {
        val oldOrigin = SavedPanel.capture(Pose(Vector3(2f, 1.5f, 3f), Quaternion(0f, 35f, 0f)), 1f, Pose())
        val saved = example().copy(origin = oldOrigin)
        val viewer = Pose(Vector3(-2f, 1.7f, -3f), Quaternion(0f, -60f, 0f))
        val room = saved.copy(workspace = saved.workspace.copy(restoreLocation = RestoreLocation.ROOM_POSITION))
        val loaded = LayoutCodec.decode(LayoutCodec.encode(room))!!
        assertEquals(RestoreLocation.ROOM_POSITION, loaded.workspace.restoreLocation)
        assertTrue((restoreWorkspaceOrigin(loaded, viewer).t - oldOrigin.restore(Pose()).t).length() < .0001f)
        assertTrue((restoreWorkspaceOrigin(saved, viewer).t - viewer.t).length() < .0001f)
    }

    @Test fun interruptedPendingWriteLeavesLastCompleteLayoutReadable() {
        val directory = Files.createTempDirectory("agentspace-layout").toFile()
        try {
            val file = directory.resolve("layout.properties")
            LayoutFileStore(file).save(example())
            directory.resolve("layout.properties.pending").writeText("partial write")
            assertEquals(example(), LayoutFileStore(file).load())
        } finally { directory.deleteRecursively() }
    }
}
