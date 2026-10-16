package io.agentspace.quest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** ROOM_POSITION is relative to the current Quest play area, not a persistent spatial anchor. */
enum class RestoreLocation { AROUND_ME, ROOM_POSITION }

data class WorkspaceLayoutState(
    val dashboardSelection: String?,
    val groups: List<SessionGroup>,
    val paused: Boolean,
    val drafts: Map<String, String>,
    val restoreLocation: RestoreLocation = RestoreLocation.AROUND_ME,
    val dashboardWorkspace: String? = null,
)

/** Value-only pose for stable equality, validation and serialization. */
data class SavedPanel(
    val x: Float, val y: Float, val z: Float,
    val qw: Float, val qx: Float, val qy: Float, val qz: Float,
    val scale: Float,
) {
    fun restore(origin: Pose): Pose = origin * Pose(Vector3(x, y, z), Quaternion(qw, qx, qy, qz).normalize())
    companion object {
        fun capture(pose: Pose, scale: Float, origin: Pose): SavedPanel {
            val local = origin.inverse() * pose
            return SavedPanel(local.t.x, local.t.y, local.t.z, local.q.w, local.q.x, local.q.y, local.q.z, scale)
        }
        fun identity() = SavedPanel(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}

data class SavedLayout(
    val workspace: WorkspaceLayoutState,
    val panels: Map<Int, SavedPanel>,
    val origin: SavedPanel = SavedPanel.identity(),
    val dashboardSize: DashboardSize = DashboardSize(),
)

internal fun openingWorkspaceOrigin(viewer: Pose): Pose = Pose(viewer.t, viewer.q.removePitchAndRoll())
internal fun restoreWorkspaceOrigin(layout: SavedLayout, viewer: Pose): Pose =
    if (layout.workspace.restoreLocation == RestoreLocation.ROOM_POSITION) layout.origin.restore(Pose()) else openingWorkspaceOrigin(viewer)

/** Versioned, bounded text format; properties escaping preserves drafts and Unicode. */
object LayoutCodec {
    private const val MAX_WINDOWS = 64
    private const val MAX_SESSIONS = 256
    private const val MAX_BYTES = 262144
    fun encode(layout: SavedLayout): String {
        val p = Properties()
        p.setProperty("format.version", "2")
        p.setProperty("dashboard.width", layout.dashboardSize.width.toString())
        p.setProperty("dashboard.height", layout.dashboardSize.height.toString())
        p.setProperty("restore.location", layout.workspace.restoreLocation.name)
        p.setProperty("dashboard.selected", layout.workspace.dashboardSelection.orEmpty())
        p.setProperty("dashboard.workspace", layout.workspace.dashboardWorkspace.orEmpty())
        p.setProperty("updates.paused", layout.workspace.paused.toString())
        p.setProperty("window.count", layout.workspace.groups.size.toString())
        layout.workspace.groups.forEachIndexed { i, group ->
            p.setProperty("window.$i.id", group.id.toString())
            p.setProperty("window.$i.tabCount", group.sessionIds.size.toString())
            group.sessionIds.forEachIndexed { tab, id -> p.setProperty("window.$i.tab.$tab", id) }
            p.setProperty("window.$i.selected", group.selectedId)
            group.workspaceId?.let { p.setProperty("window.$i.workspace", it) }
            p.setProperty("window.$i.workspaceLabel", group.workspaceLabel)
            p.setProperty("window.$i.overview", group.overview.toString())
        }
        layout.workspace.drafts.forEach { (id, value) -> p.setProperty("draft.$id", value) }
        fun putPose(prefix: String, panel: SavedPanel) {
            p.setProperty("$prefix.pose", listOf(panel.x, panel.y, panel.z, panel.qw, panel.qx, panel.qy, panel.qz).joinToString(","))
            p.setProperty("$prefix.scale", panel.scale.toString())
        }
        putPose("origin", layout.origin)
        layout.panels.forEach { (id, panel) -> putPose("panel.$id", panel) }
        return StringWriter().also { p.store(it, "AgentSpace layout v2") }.toString()
    }

    fun decode(text: String): SavedLayout? {
        if (text.length > MAX_BYTES) return null
        return try {
            val p = Properties().apply { load(StringReader(text)) }
            val version = p.getProperty("format.version")
            if (version != "1" && version != "2") return null
            val count = p.getProperty("window.count")?.toIntOrNull() ?: return null
            if (count !in 0..MAX_WINDOWS) return null
            val groups = (0 until count).mapNotNull { i ->
                val id = p.getProperty("window.$i.id")?.toIntOrNull() ?: return@mapNotNull null
                val workspaceId = p.getProperty("window.$i.workspace")?.takeIf { it.isNotBlank() }
                val tabs = if (version == "1") p.getProperty("window.$i.tabs").orEmpty().split(',').filter { it.isNotBlank() }.take(MAX_SESSIONS)
                else {
                    val tabCount = p.getProperty("window.$i.tabCount")?.toIntOrNull() ?: return@mapNotNull null
                    if (tabCount < 0 || (tabCount == 0 && workspaceId == null)) return@mapNotNull null
                    (0 until minOf(tabCount, MAX_SESSIONS)).mapNotNull { tab -> p.getProperty("window.$i.tab.$tab")?.takeIf { it.isNotBlank() } }
                }
                if (tabs.isEmpty() && workspaceId == null) null else SessionGroup(id, tabs,
                    p.getProperty("window.$i.selected") ?: tabs.firstOrNull().orEmpty(), workspaceId,
                    p.getProperty("window.$i.workspaceLabel").orEmpty(), p.getProperty("window.$i.overview").toBoolean())
            }
            fun readPose(prefix: String): SavedPanel? {
                val values = p.getProperty("$prefix.pose")?.split(',')?.map { it.toFloatOrNull() ?: return null } ?: return null
                if (values.size != 7 || values.any { !it.isFinite() }) return null
                if (values.take(3).any { kotlin.math.abs(it) > 50f }) return null
                val normSquared = values.drop(3).sumOf { (it * it).toDouble() }
                if (normSquared !in 0.01..100.0) return null
                val scale = p.getProperty("$prefix.scale")?.toFloatOrNull() ?: return null
                if (!scale.isFinite() || scale <= 0f) return null
                return SavedPanel(values[0], values[1], values[2], values[3], values[4], values[5], values[6], scale.coerceIn(.65f, 1.65f))
            }
            val origin = readPose("origin") ?: return null
            val panelIds = listOf(-1) + groups.map { it.id }.filter { it in 0 until MAX_WINDOWS }
            val panels = panelIds.distinct().mapNotNull { id -> readPose("panel.$id")?.let { id to it } }.toMap()
            val drafts = p.stringPropertyNames().filter { it.startsWith("draft.") }.take(MAX_SESSIONS)
                .associate { it.removePrefix("draft.") to p.getProperty(it).take(16384) }
            val location = runCatching { RestoreLocation.valueOf(p.getProperty("restore.location")) }.getOrDefault(RestoreLocation.AROUND_ME)
            SavedLayout(WorkspaceLayoutState(p.getProperty("dashboard.selected")?.takeIf { it.isNotBlank() }, groups,
                p.getProperty("updates.paused").toBoolean(), drafts, location, p.getProperty("dashboard.workspace")?.takeIf { it.isNotBlank() }), panels, origin,
                DashboardSize.fromSaved(p.getProperty("dashboard.width")?.toFloatOrNull(), p.getProperty("dashboard.height")?.toFloatOrNull()))
        } catch (_: IllegalArgumentException) { null }
    }
}

/** Replace one complete snapshot atomically; a process kill cannot leave half a layout. */
class LayoutFileStore(private val file: File) {
    private var lastSaved: SavedLayout? = null
    fun load(): SavedLayout? {
        if (!file.isFile || file.length() > 262144) return null
        return LayoutCodec.decode(file.readText(Charsets.UTF_8)).also { lastSaved = it }
    }
    fun save(layout: SavedLayout) {
        if (layout == lastSaved && file.exists()) return
        val bytes = LayoutCodec.encode(layout).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 262144) { "Saved layout exceeds the size limit; previous snapshot kept." }
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, file.name + ".pending")
        try {
            FileOutputStream(pending).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            try {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            lastSaved = layout
        } finally { pending.delete() }
    }
}

/** STAGE tracking initially reports an identity pose before the headset is located. */
internal fun isUsableStageViewerPose(viewer: Pose): Boolean =
    listOf(viewer.t.x, viewer.t.y, viewer.t.z, viewer.q.w, viewer.q.x, viewer.q.y, viewer.q.z).all { it.isFinite() } &&
        viewer.t.length() > .05f &&
        (viewer.q.w * viewer.q.w + viewer.q.x * viewer.q.x + viewer.q.y * viewer.q.y + viewer.q.z * viewer.q.z) > .5f
