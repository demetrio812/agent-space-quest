package io.agentspace.quest

import android.content.Context
import java.security.MessageDigest

internal fun favoriteStorageKey(source: String): String = "favorites-" +
    MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Shared by Home and Immersive; never mixes demo or different connection profiles. */
internal class FavoriteStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("agentspace_favorites", Context.MODE_PRIVATE)
    fun bind(workspace: WorkspaceState, source: String) {
        val key = favoriteStorageKey(source)
        workspace.setFavorites(preferences.getStringSet(key, emptySet()).orEmpty().toSet())
        workspace.onFavoritesChanged = { ids -> preferences.edit().putStringSet(key, ids.toSet()).apply() }
    }
}
