package io.agentspace.quest.connection

import android.content.Context

class ConnectionProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("agentspace_connection", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { check(preferences.edit().putBoolean("enabled", value).commit()) { "Could not save source preference." } }

    fun load(): ConnectionProfile? {
        return try {
            val id = preferences.getString("id", null) ?: return null
            ConnectionProfile(id, preferences.getString("host", "") ?: "", preferences.getInt("port", 22),
                preferences.getString("user", "") ?: "", preferences.getString("knownHost", "") ?: "").also { it.validate() }
        } catch (_: Exception) { null }
    }

    fun save(profile: ConnectionProfile) {
        profile.validate()
        check(preferences.edit().putString("id", profile.id).putString("host", profile.host)
            .putInt("port", profile.port).putString("user", profile.user).putString("knownHost", profile.knownHost).commit()) {
            "Could not save connection profile."
        }
    }
}
