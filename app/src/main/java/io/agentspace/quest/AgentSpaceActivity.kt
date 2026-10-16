package io.agentspace.quest

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.*
import com.meta.spatial.isdk.IsdkGrabConstraints
import com.meta.spatial.isdk.IsdkPanelResize
import com.meta.spatial.isdk.IsdkSystem
import com.meta.spatial.isdk.ResizeMode
import com.meta.spatial.runtime.PointerEvent
import com.meta.spatial.runtime.PointerEventType
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.ReferenceSpace
import kotlin.math.roundToInt
import com.meta.spatial.toolkit.*
import com.meta.spatial.vr.VRFeature
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import java.io.File

/** Spatial workspace; floating entities correspond to groups, not transport sessions. */
@OptIn(SpatialSDKExperimentalAPI::class)
class AgentSpaceActivity : AppSystemActivity() {
    private val workspace = WorkspaceState()
    private val favorites by lazy { FavoriteStore(this) }
    private fun bindFavorites() = favorites.bind(workspace, if (workspace.isDemo) "demo" else "live:${catalog.profiles.load()?.id ?: "unconfigured"}")
    private val terminal by lazy { io.agentspace.quest.terminal.TerminalController(this) }
    private val catalog by lazy { CatalogController(this, workspace) { terminal.updateCatalog(it) } }
    private val presentation by lazy { getSharedPreferences("agentspace_ui", MODE_PRIVATE) }
    private val backgroundListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "passthrough_enabled") main.post { if (sceneReady) applyBackground() }
    }
    private var showConnection by mutableStateOf(false)
    private var liveLayoutId: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val windows = linkedMapOf<Int, Entity>()
    private var dashboard: Entity? = null
    private var dashboardSurface: PanelSceneObject? = null
    private val resizeTicker = object : Runnable {
        override fun run() {
            if (!resumed) return
            syncDashboardSurface()
            main.postDelayed(this, 160)
        }
    }

    // Simple handles update both Scale axes continuously. Match the Android surface to
    // those physical bounds, rather than waiting for Relayout's release-only redraw.
    private fun syncDashboardSurface() {
        val entity = dashboard ?: return
        val panel = dashboardSurface ?: return
        val display = panel.display ?: return
        val size = dashboardSize(entity)
        val pixelsPerMeter = DP_PER_METER * display.surfaceDPI / 160f
        val width = (size.width * pixelsPerMeter).roundToInt()
        val height = (size.height * pixelsPerMeter).roundToInt()
        if (display.widthInPx != width || display.heightInPx != height) panel.resize(width, height)
    }

    private fun dashboardSize(entity: Entity): DashboardSize {
        val base = entity.getComponent<PanelDimensions>().dimensions
        val scale = entity.getComponent<Scale>().scale
        return DashboardSize(base.x, base.y).scaled(scale.x, scale.y)
    }

    private fun setDashboardSize(size: DashboardSize) {
        val entity = dashboard ?: return
        val base = entity.getComponent<PanelDimensions>().dimensions
        entity.setComponent(Scale(Vector3(size.width / base.x, size.height / base.y, 1f)))
        syncDashboardSurface()
    }
    private var sceneReady = false
    private var resumed = false
    private var layoutRestored = false
    private var recoverPanels = false
    private val restoreWhenTracked = object : Runnable {
        override fun run() {
            if (!sceneReady || !resumed || layoutRestored) return
            val viewer = if (BuildConfig.DEBUG && intent.getBooleanExtra("resizeProbe", false)) Pose(Vector3(0f, 1.6f, 0f)) else scene.getViewerPose()
            if (!isUsableStageViewerPose(viewer)) { main.postDelayed(this, 100); return }
            restoreLayout(viewer)
            layoutRestored = true
            if (BuildConfig.DEBUG && intent.getBooleanExtra("workspaceProbe", false)) {
                val probe = object : Runnable {
                    private var attempts = 0
                    override fun run() {
                        val candidate = workspace.sessions.filter { it.available }.groupBy { it.workspaceId }.values.firstOrNull { it.size > 1 }
                        if (candidate == null && attempts++ < 40) { main.postDelayed(this, 250); return }
                        candidate?.firstOrNull()?.workspaceId?.let { id -> workspace.openWorkspace(id)?.let { syncWindows(it, poseInFront(.35f, -.05f, 1.35f)) } }
                    }
                }
                main.post(probe)
            }
            if (recoverPanels) { recoverPanels = false; recenterWorkspace() }
        }
    }
    private val layoutStores = mutableMapOf<String, LayoutFileStore>()
    private val layoutStore: LayoutFileStore
        get() {
            val name = liveLayoutId?.let { "workspace-layout-live-$it.properties" } ?: "workspace-layout.properties"
            return layoutStores.getOrPut(name) { LayoutFileStore(File(filesDir, name)) }
        }
    private var workspaceOrigin: Pose? = null
    private var saveStatus by mutableStateOf("Layout saves automatically")
    private val saveTicker = object : Runnable {
        override fun run() { saveLayout(); main.postDelayed(this, 1000) }
    }
    private var isdk: IsdkSystem? = null
    private var dragUi by mutableStateOf<TabDragUi?>(null)
    private data class Press(val source: Entity, val pointerType: Int)
    private val presses = mutableMapOf<Int, Press>()
    private data class Drag(
        val sessionId: String, val sourceWindow: Int, val press: Press?,
        var point: Vector3, val startPoint: Vector3, var moved: Boolean = false,
        var hitWindow: Int? = null,
    )
    private var drag: Drag? = null
    private val pointerObserver: (PointerEvent) -> Unit = { event -> main.post { processPointer(event) } }
    private val ticker = object : Runnable {
        override fun run() { workspace.tick(); main.postDelayed(this, 1800) }
    }
    private val dragTicker = object : Runnable {
        override fun run() { if (drag != null) { updateNativeDrag(); main.postDelayed(this, 16) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        presentation.registerOnSharedPreferenceChangeListener(backgroundListener)
        if (BuildConfig.DEBUG) intent.getStringExtra("backgroundProbe")?.let { mode ->
            if (mode in listOf("dark", "passthrough")) presentation.edit().putBoolean("passthrough_enabled", mode == "passthrough").apply()
        }
        recoverPanels = BuildConfig.DEBUG && intent.getBooleanExtra("recoverPanels", false)
        if (catalog.profiles.enabled) {
            liveLayoutId = catalog.profiles.load()?.id ?: "unconfigured"
            workspace.setSource(false)
        }
    }

    private fun switchSource(live: Boolean) {
        saveLayout()
        catalog.stop()
        terminal.clear()
        cancelDrag()
        windows.values.forEach { it.destroy() }; windows.clear()
        dashboardSurface = null
        dashboard?.destroy(); dashboard = null
        liveLayoutId = if (live) catalog.profiles.load()?.id ?: "unconfigured" else null
        workspace.setSource(!live)
        catalog.profiles.enabled = live
        bindFavorites()
        showConnection = false
        layoutRestored = false
        if (sceneReady) main.post(restoreWhenTracked)
        catalog.start()
    }

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this), ComposeFeature())
    override fun registerPanels(): List<PanelRegistration> = buildList {
        add(ComposeViewPanelRegistration(DASHBOARD_PANEL,
            composeViewCreator = { _, context -> ComposeView(context).apply { setContent {
                if (showConnection) {
                    ConnectionUi(catalog, onConnect = { switchSource(true) }, onDemo = { switchSource(false) }, onClose = { showConnection = false })
                } else CompositionLocalProvider(LocalSwitchMode provides { saveLayout(); HomeNavigation.enterHome(this@AgentSpaceActivity) }) { DashboardUi(workspace, dragUi, dragActions(DASHBOARD_WINDOW), ::resetLayout,
                    onCloseAll = { cancelDrag(); workspace.closeAll(); syncWindows() }, saveStatus = saveStatus,
                    connectionStatus = catalog.status, onConnection = { showConnection = true }, terminal = terminal, onExpand = ::expandDashboard,
                    showLaunchTip = !(BuildConfig.DEBUG && intent.getBooleanExtra("resizeProbe", false)),
                    onOpenWorkspace = { id ->
                        workspace.openWorkspace(id)?.let { slot -> syncWindows(slot, poseInFront(.35f, -.05f, 1.35f)) }
                    }) }
            } } }, settingsCreator = { entity ->
                val size = entity.getComponent<PanelDimensions>().dimensions
                settings(size.x, size.y)
            },
            panelSetupWithComposeView = { view, panel, entity ->
                dashboardSurface = panel
                if (BuildConfig.DEBUG) {
                    view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        Log.i("AgentSpaceResize", "view=${view.width}x${view.height} density=${view.resources.displayMetrics.density} scale=${entity.getComponent<Scale>().scale}")
                    }
                    if (intent.getBooleanExtra("resizeProbe", false)) {
                        main.postDelayed({
                            val size = dashboardSize(entity)
                            fun simulateHandleResize(target: DashboardSize) {
                                val base = entity.getComponent<PanelDimensions>().dimensions
                                // Same component update as ISDK Simple; the normal ticker must relayout.
                                entity.setComponent(Scale(Vector3(target.width / base.x, target.height / base.y, 1f)))
                            }
                            fun capture(name: String) {
                                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                                view.draw(android.graphics.Canvas(bitmap))
                                File(filesDir, "resize-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                                bitmap.recycle()
                                File(filesDir, "resize-$name.txt").writeText("${view.width}x${view.height}; ${dashboardSize(entity)}")
                            }
                            capture("before")
                            simulateHandleResize(DashboardSize(size.width, .32f))
                            main.postDelayed({ capture("shallow"); simulateHandleResize(DashboardSize(.76f, .96f)) }, 2500)
                            main.postDelayed({ capture("narrow"); simulateHandleResize(size) }, 5000)
                        }, 2000)
                    }
                }
            }))
        repeat(WorkspaceState.MAX_WINDOWS) { slot ->
            add(ComposeViewPanelRegistration(FIRST_GROUP_PANEL + slot,
                composeViewCreator = { _, context -> ComposeView(context).apply { setContent {
                    FloatingWindowUi(workspace, slot, dragUi, dragActions(slot),
                        onClose = { cancelDrag(); workspace.closeGroup(slot); syncWindows() },
                        onCloseTab = { id -> cancelDrag(); workspace.closeTab(slot, id); syncWindows() },
                        onResize = { factor -> resizeWindow(slot, factor) },
                        connectionStatus = catalog.status, terminal = terminal, showTips = !(BuildConfig.DEBUG && intent.getBooleanExtra("resizeProbe", false)),
                        onRecenter = { windows[slot]?.setComponent(Transform(poseInFront(0f, 0f, 1.3f))); saveLayout() })
                } } }, settingsCreator = { settings(GROUP_WIDTH, PANEL_HEIGHT) },
                panelSetupWithComposeView = { view, _, _ ->
                    if (BuildConfig.DEBUG && intent.getBooleanExtra("workspaceProbe", false) && workspace.group(slot)?.workspaceId != null) main.postDelayed({
                        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                        view.draw(android.graphics.Canvas(bitmap))
                        File(filesDir, "workspace-native.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                        File(filesDir, "workspace-native.txt").writeText("window=$slot size=${view.width}x${view.height} sessions=${workspace.group(slot)?.sessionIds?.size}")
                    }, 1800)
                }))
        }
    }

    private fun settings(width: Float, height: Float) = UIPanelSettings(
        shape = QuadShapeOptions(width = width, height = height),
        display = DpPerMeterDisplayOptions(dpPerMeter = DP_PER_METER),
        style = PanelStyleOptions(themeResourceId = R.style.PanelTheme),
    )

    override fun onSceneReady() {
        super.onSceneReady()
        applyBackground()
        // Stage coordinates keep the play-area origin stable for the room-position option.
        scene.setReferenceSpace(ReferenceSpace.STAGE)
        main.post {
            if (!sceneReady) {
                sceneReady = true
                main.post(restoreWhenTracked)
                isdk = systemManager.findSystem<IsdkSystem>().also { it.registerObserver(pointerObserver) }
                Log.i(TAG, "Scene ready: scrollable dashboard; ${workspace.sessions.size} sessions")
            }
        }
    }
    private fun applyBackground() {
        val passthrough = presentation.getBoolean("passthrough_enabled", true)
        scene.setBackfillColor(com.meta.spatial.core.Color4(.015f, .022f, .035f, if (passthrough) 0f else 1f))
        scene.enablePassthrough(passthrough)
        Log.i(TAG, "Background: ${if (passthrough) "passthrough" else "dark"}")
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        bindFavorites()
        main.removeCallbacks(restoreWhenTracked); main.post(restoreWhenTracked)
        catalog.start()
        terminal.resume()
        main.removeCallbacks(ticker); main.postDelayed(ticker, 1800)
        main.removeCallbacks(resizeTicker); main.post(resizeTicker)
        main.removeCallbacks(saveTicker); main.postDelayed(saveTicker, 1000)
    }
    override fun onPause() {
        saveLayout()
        resumed = false
        main.removeCallbacks(restoreWhenTracked)
        main.removeCallbacks(resizeTicker)
        catalog.stop()
        terminal.pause()
        cancelDrag(); presses.clear(); main.removeCallbacks(ticker); main.removeCallbacks(saveTicker)
        super.onPause()
    }
    override fun onDestroy() {
        sceneReady = false
        presentation.unregisterOnSharedPreferenceChangeListener(backgroundListener)
        catalog.destroy()
        terminal.destroy()
        isdk?.unregisterObserver(pointerObserver)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun restoreLayout(viewer: Pose) {
        val saved = try { layoutStore.load() } catch (error: Exception) {
            Log.w(TAG, "Could not read saved layout", error); null
        }
        val origin = saved?.let { restoreWorkspaceOrigin(it, viewer) } ?: openingWorkspaceOrigin(viewer)
        workspaceOrigin = origin
        if (saved != null) workspace.restoreLayoutState(saved.workspace)
        val dashboardPose = saved?.panels?.get(DASHBOARD_WINDOW)
        val restoredSize = (saved?.dashboardSize ?: DashboardSize()).scaled(dashboardPose?.scale ?: 1f, dashboardPose?.scale ?: 1f)
        dashboard = createPanel(DASHBOARD_PANEL, dashboardPose?.restore(origin) ?: poseInFront(0f, -0.10f, 1.65f), restoredSize)
        workspace.groups.forEach { group ->
            val placement = saved?.panels?.get(group.id)
            windows[group.id] = createPanel(FIRST_GROUP_PANEL + group.id, placement?.restore(origin) ?: defaultWindowPose(group.id))
            placement?.let { windows[group.id]?.setComponent(Scale(Vector3(it.scale))) }
        }
        Log.i(TAG, "Layout restored: mode=${workspace.restoreLocation}, groups=${workspace.groups.size}, selected=${workspace.dashboardSelection}")
    }

    private fun saveLayout() {
        if (BuildConfig.DEBUG && intent.getBooleanExtra("resizeProbe", false)) return
        if (!sceneReady || !layoutRestored) return
        val origin = workspaceOrigin ?: return
        val dashboardEntity = dashboard ?: return
        try {
            val panels = buildMap {
                fun capture(id: Int, entity: Entity) {
                    put(id, SavedPanel.capture(entity.getComponent<Transform>().transform, if (id == DASHBOARD_WINDOW) 1f else entity.getComponent<Scale>().scale.x, origin))
                }
                capture(DASHBOARD_WINDOW, dashboardEntity)
                windows.forEach { (id, entity) -> capture(id, entity) }
            }
            val dimensions = dashboardSize(dashboardEntity)
            layoutStore.save(SavedLayout(workspace.layoutState(), panels, SavedPanel.capture(origin, 1f, Pose()),
                dimensions))
            saveStatus = "Layout saves automatically"
        } catch (error: Exception) {
            saveStatus = "Couldn't save layout"
            Log.e(TAG, "Could not save workspace layout", error)
        }
    }

    private fun createPanel(registration: Int, pose: Pose, dashboardSize: DashboardSize = DashboardSize()) = Entity.create(buildList {
        add(Panel(registration))
        add(Transform(pose))
        add(Scale(Vector3(1f)))
        add(Grabbable(true, GrabbableType.FACE))
        // Dashboard corners own width/height. One grab moves it without a competing pinch zoom.
        add(IsdkGrabConstraints(scaleMinMax = if (registration == DASHBOARD_PANEL) Vector2(.1f, 10f) else Vector2(MIN_PANEL_SCALE, MAX_PANEL_SCALE), maxGrabbers = if (registration == DASHBOARD_PANEL) 1 else 2))
        if (registration == DASHBOARD_PANEL) {
            add(PanelDimensions(Vector2(dashboardSize.width, dashboardSize.height)))
            add(IsdkPanelResize(enabled = true, resizeMode = ResizeMode.Simple,
                minDimensions = Vector2(.72f, .30f), maxDimensions = Vector2(2.8f, 1.8f), preserveAspectRatio = false))
        } else {
            // Native corner handles change the same uniform scale as the size buttons.
            // Keeping the aspect ratio also keeps the tab-drop bounds accurate.
            add(IsdkPanelResize(
                enabled = true,
                resizeMode = ResizeMode.Simple,
                minDimensions = Vector2(GROUP_WIDTH * MIN_PANEL_SCALE, PANEL_HEIGHT * MIN_PANEL_SCALE),
                maxDimensions = Vector2(GROUP_WIDTH * MAX_PANEL_SCALE, PANEL_HEIGHT * MAX_PANEL_SCALE),
                preserveAspectRatio = true,
            ))
        }
    })
    private fun panel(id: Int): Entity? = if (id == DASHBOARD_WINDOW) dashboard else windows[id]
    private fun windowId(entity: Entity): Int? = when {
        dashboard?.id == entity.id -> DASHBOARD_WINDOW
        else -> windows.entries.firstOrNull { it.value.id == entity.id }?.key
    }
    private fun panelBounds(): List<PanelBounds> = buildList {
        dashboard?.let { add(bounds(DASHBOARD_WINDOW, it)) }
        windows.forEach { (id, entity) -> add(bounds(id, entity)) }
    }
    private fun bounds(id: Int, entity: Entity): PanelBounds {
        if (id == DASHBOARD_WINDOW) {
            val size = dashboardSize(entity)
            return PanelBounds(id, entity.getComponent<Transform>().transform, 1f, size.width, size.height)
        }
        return PanelBounds(id, entity.getComponent<Transform>().transform, entity.getComponent<Scale>().scale.x, GROUP_WIDTH, PANEL_HEIGHT)
    }

    private fun syncWindows(placedGroup: Int? = null, placement: Pose? = null) {
        val alive = workspace.groups.map { it.id }.toSet()
        windows.keys.filter { it !in alive }.forEach { windows.remove(it)?.destroy() }
        workspace.groups.forEach { group ->
            if (group.id !in windows) windows[group.id] = createPanel(FIRST_GROUP_PANEL + group.id,
                if (group.id == placedGroup && placement != null) placement else defaultWindowPose(group.id))
            else if (group.id == placedGroup && placement != null) windows[group.id]?.setComponent(Transform(placement))
        }
        Log.i(TAG, "Groups: " + workspace.groups.joinToString { "${it.id}=[${it.sessionIds.joinToString()}]" })
        saveLayout()
    }

    private fun dragActions(source: Int) = TabDragActions(
        start = { sessionId -> beginDrag(source, sessionId) },
        move = { x, y -> moveComposeDrag(source, x, y) },
        end = { finishDrag() }, cancel = { cancelDrag() },
    )
    private fun beginDrag(source: Int, sessionId: String) {
        if (!sceneReady || drag != null) return
        val entity = panel(source) ?: return
        val origin = entity.getComponent<Transform>().transform.t
        drag = Drag(sessionId, source, presses[source], origin, origin)
        dragUi = TabDragUi(sessionId, source)
        Log.i(TAG, "Tab drag started: $sessionId from=$source nativePointer=${presses[source] != null}")
        // The first Compose move supplies the precise tile position before polling the native ray.
        main.removeCallbacks(dragTicker)
        main.postDelayed(dragTicker, 16)
    }
    private fun moveComposeDrag(source: Int, xDp: Float, yDp: Float) {
        val active = drag ?: return
        if (active.sourceWindow != source) return
        val sourceEntity = panel(source) ?: return
        val b = bounds(source, sourceEntity)
        val point = b.pose.t + b.pose.q * Vector3(
            (xDp / DP_PER_METER - b.width / 2f) * b.scale,
            (b.height / 2f - yDp / DP_PER_METER) * b.scale, 0f)
        if (!dragInitialized) {
            drag = active.copy(point = point, startPoint = point)
            dragInitialized = true
        } else {
            active.point = point
            active.moved = active.moved || (point - active.startPoint).length() > 0.035f
        }
        if (active.press == null) {
            val viewer = scene.getViewerPose().t
            updateDropTarget(viewer, (point - viewer).normalize())
        }
    }
    private var dragInitialized = false
    private fun updateNativeDrag() {
        val active = drag ?: return
        val input = active.press?.source ?: return
        if (input.willBeDeleted()) { cancelDrag(); return }
        val inputPose = getAbsoluteTransform(input)
        val rayOrigin = inputPose.t
        val rayDirection = inputPose.q * Vector3.Forward
        val entity = panel(active.sourceWindow) ?: return
        val b = bounds(active.sourceWindow, entity)
        val normal = b.pose.q * Vector3.Forward
        val denominator = rayDirection.dot(normal)
        val distance = if (kotlin.math.abs(denominator) > 0.001f) (b.pose.t - rayOrigin).dot(normal) / denominator else -1f
        val point = rayOrigin + rayDirection * (if (distance in 0.2f..5f) distance else (active.startPoint - rayOrigin).length().coerceIn(0.4f, 3f))
        active.point = point
        if (dragInitialized) active.moved = active.moved || (point - active.startPoint).length() > 0.035f
        updateDropTarget(rayOrigin, rayDirection)
    }
    private fun updateDropTarget(origin: Vector3, direction: Vector3) {
        val active = drag ?: return
        val hit = nearestPanelHit(origin, direction, panelBounds())
        active.hitWindow = hit?.id
        val target = hit?.id?.takeIf { it != active.sourceWindow && active.moved && workspace.group(it)?.workspaceId == null }
        if (dragUi?.target != target) dragUi = TabDragUi(active.sessionId, active.sourceWindow, target)
    }
    private fun processPointer(event: PointerEvent) {
        if (!sceneReady) return
        val target = event.hitInfo.entity?.let(::windowId)
        if (event.type == PointerEventType.Select.id && target != null) presses[target] = Press(event.source, event.pointerType)
        val active = drag
        val samePointer = active?.press?.let { it.source.id == event.source.id && it.pointerType == event.pointerType } == true
        if (samePointer) when (event.type) {
            PointerEventType.Unselect.id -> finishDrag()
            PointerEventType.Cancel.id -> cancelDrag()
        }
        if (event.type == PointerEventType.Unselect.id || event.type == PointerEventType.Cancel.id) {
            presses.entries.removeAll { it.value.source.id == event.source.id && it.value.pointerType == event.pointerType }
            // Run after native grab/resize and Compose selection changes have settled.
            main.postDelayed({ saveLayout() }, 50)
        }
    }
    private fun finishDrag() {
        val active = drag ?: return
        updateNativeDrag()
        if (drag !== active) return
        val target = active.hitWindow
        val shouldMove = active.moved
        val point = active.point
        cancelDrag()
        if (!shouldMove || target == active.sourceWindow) return
        when {
            target == DASHBOARD_WINDOW -> {
                workspace.returnToDashboard(active.sessionId); syncWindows()
                Log.i(TAG, "Returned tab ${active.sessionId} to dashboard")
            }
            target != null && workspace.group(target)?.workspaceId != null -> return
            target != null -> {
                workspace.moveToGroup(active.sessionId, target); syncWindows()
                Log.i(TAG, "Grouped tab ${active.sessionId} into window $target")
            }
            else -> {
                val viewer = scene.getViewerPose().t
                val position = point - (point - viewer).normalize() * 0.20f
                val groupId = workspace.detach(active.sessionId) ?: return
                syncWindows(groupId, viewerFacingPose(position, viewer))
                Log.i(TAG, "Detached tab ${active.sessionId} into window $groupId")
            }
        }
    }
    private fun cancelDrag() {
        drag = null; dragUi = null; dragInitialized = false
        main.removeCallbacks(dragTicker)
    }
    private fun resizeWindow(id: Int, factor: Float) {
        val entity = windows[id] ?: return
        entity.setComponent(Scale(Vector3((entity.getComponent<Scale>().scale.x * factor).coerceIn(MIN_PANEL_SCALE, MAX_PANEL_SCALE))))
        saveLayout()
    }
    /** Recovery keeps panel sizes, tabs and their relative arrangement. */
    private fun recenterWorkspace() {
        val entity = dashboard ?: return
        val viewer = scene.getViewerPose()
        if (!isUsableStageViewerPose(viewer)) return
        val delta = poseInFront(0f, -.10f, 1.65f) * entity.getComponent<Transform>().transform.inverse()
        (listOf(entity) + windows.values).forEach {
            it.setComponent(Transform(delta * it.getComponent<Transform>().transform))
        }
        workspaceOrigin = openingWorkspaceOrigin(viewer)
        saveLayout()
        Log.i(TAG, "Workspace recentered with tabs and sizes preserved")
    }

    private fun expandDashboard() {
        val entity = dashboard ?: return
        val size = dashboardSize(entity)
        if (size.height < .72f) setDashboardSize(size.copy(height = PANEL_HEIGHT))
    }

    private fun resetLayout() {
        cancelDrag()
        dashboard?.setComponent(Transform(poseInFront(0f, -0.10f, 1.65f)))
        setDashboardSize(DashboardSize())
        windows.forEach { (id, entity) -> entity.setComponents(Transform(defaultWindowPose(id)), Scale(Vector3(1f))) }
        workspaceOrigin = openingWorkspaceOrigin(scene.getViewerPose())
        saveLayout()
    }
    private fun defaultWindowPose(index: Int): Pose {
        val angles = listOf(-52f, 52f, -100f, 100f, -148f, 148f)
        return poseInFront(angles[index % angles.size], if (index >= angles.size) 0.95f else -0.03f, 1.65f)
    }
    private fun poseInFront(angle: Float, vertical: Float, distance: Float): Pose {
        val viewer = scene.getViewerPose()
        val forward = viewer.q * Vector3.Forward
        val yaw = Math.toDegrees(atan2(forward.x.toDouble(), forward.z.toDouble())).toFloat() + angle
        val radians = Math.toRadians(yaw.toDouble())
        return viewerFacingPose(viewer.t + Vector3(sin(radians).toFloat() * distance, vertical, cos(radians).toFloat() * distance), viewer.t)
    }
    companion object {
        private const val TAG = "AgentSpace"
        private const val DASHBOARD_PANEL = 70001
        private const val FIRST_GROUP_PANEL = 70010
        private const val DP_PER_METER = 720f
        private const val DASHBOARD_WIDTH = 1.40f
        private const val GROUP_WIDTH = 1.18f
        private const val PANEL_HEIGHT = 0.96f
        private const val MIN_PANEL_SCALE = 0.65f
        private const val MAX_PANEL_SCALE = 1.65f
    }
}
