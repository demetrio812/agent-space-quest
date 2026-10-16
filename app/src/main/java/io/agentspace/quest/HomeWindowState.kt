package io.agentspace.quest

import java.util.Base64

/** A Home task identifies its source and content, never a reusable group slot. */
internal data class HomeWindowTarget(val source: String, val kind: String, val id: String) {
    fun encode(): String {
        fun part(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        return "agentspace://window/$kind/${part(source)}/${part(id)}"
    }
    companion object {
        fun decode(value: String?): HomeWindowTarget? = runCatching {
            if (value == null || value.length > 16384 || !value.startsWith("agentspace://window/")) return null
            val parts = value.removePrefix("agentspace://window/").split('/')
            if (parts.size != 3 || parts[0] !in listOf("session", "workspace")) return null
            fun part(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
            HomeWindowTarget(part(parts[1]), parts[0], part(parts[2])).takeIf {
                it.source.isNotBlank() && it.id.isNotBlank() && it.encode() == value
            }
        }.getOrNull()
    }
}

/** Focus changes are irrelevant; transports run while any Home activity is visible. */
internal class HomeVisibility {
    private val owners = mutableSetOf<Int>()
    val active get() = owners.isNotEmpty()
    fun start(owner: Int): Boolean { val wasActive = active; owners.add(owner); return !wasActive && active }
    fun stop(owner: Int): Boolean { val wasActive = active; owners.remove(owner); return wasActive && !active }
}

/** Late destruction of an old activity cannot close a replacement view's group. */
internal class HomeWindowOwners {
    private val targets = mutableMapOf<Int, HomeWindowTarget>()
    fun attach(owner: Int, target: HomeWindowTarget) { targets[owner] = target }
    fun detach(owner: Int): HomeWindowTarget? {
        val target = targets.remove(owner) ?: return null
        return target.takeUnless { it in targets.values }
    }
}

internal fun WorkspaceState.resolveHomeTarget(target: HomeWindowTarget, source: String): Int? {
    if (target.source != source) return null
    val existing = if (target.kind == "session") groupFor(target.id) else workspaceWindow(target.id)
    if (existing != null) return existing.id
    val created = if (target.kind == "session") detach(target.id) else openWorkspace(target.id)
    if (created != null) return created
    if (groups.size >= WorkspaceState.MAX_WINDOWS) return null
    val slot = (0 until WorkspaceState.MAX_WINDOWS).first { group(it) == null }
    val restored = if (target.kind == "session") SessionGroup(slot, listOf(target.id), target.id)
        else SessionGroup(slot, emptyList(), "", target.id, "Saved workspace", overview = true)
    restoreLayoutState(layoutState().copy(groups = groups + restored))
    return group(slot)?.id
}
