package io.agentspace.quest

/** Physical dashboard dimensions in metres; saved with normalized entity scale. */
data class DashboardSize(val width: Float = 1.4f, val height: Float = .96f) {
    fun scaled(x: Float, y: Float): DashboardSize = fromSaved(width * x, height * y)

    companion object {
        fun fromSaved(width: Float?, height: Float?): DashboardSize =
            if (width == null || height == null || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) DashboardSize()
            else DashboardSize(width.coerceIn(.72f, 2.8f), height.coerceIn(.30f, 1.8f))
    }
}

internal fun dashboardUsesStrip(contentHeightDp: Float): Boolean = contentHeightDp < 260f

internal fun dashboardShowsWorkspaceRail(contentWidthDp: Float, contentHeightDp: Float): Boolean =
    contentWidthDp >= 880f && contentHeightDp >= 330f
