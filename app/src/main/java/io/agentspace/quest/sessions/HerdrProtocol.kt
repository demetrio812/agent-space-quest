package io.agentspace.quest.sessions

import org.json.JSONObject
import java.io.IOException

class CatalogException(message: String) : IOException(message)

object HerdrProtocol {
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_ENTRIES = 4096
    private val terminalIdPattern = Regex("[A-Za-z0-9_.:@%+/-]{1,256}")
    private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

    fun parseCatalog(json: String, connectionId: String): List<SessionInfo> {
        if (json.length > MAX_RESPONSE_BYTES || json.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES)
            throw CatalogException("Catalog exceeds the 2 MiB response limit.")
        try {
            val root = JSONObject(json)
            require(!root.hasError())
            val server = root.string("serverIdentity")
            require(validIdentity(server))
            val catalog = root.getJSONObject("catalog")
            require(!catalog.hasError())
            val result = catalog.getJSONObject("result")
            require(!result.hasError())
            val agents = result.getJSONArray("agents")
            require(agents.length() <= MAX_ENTRIES)
            val workspaceNames = mutableMapOf<String, String>()
            root.optJSONArray("workspaces")?.let { rows ->
                require(rows.length() <= MAX_ENTRIES)
                for (i in 0 until rows.length()) rows.optJSONObject(i)?.let {
                    val id = it.string("workspace_id")
                    val name = display(it.string("label"))
                    if (validIdentity(id) && name.isNotBlank()) workspaceNames[id] = name
                }
            }
            val tabNames = mutableMapOf<Pair<String, String>, String>()
            root.optJSONArray("tabs")?.let { rows ->
                require(rows.length() <= MAX_ENTRIES)
                for (i in 0 until rows.length()) rows.optJSONObject(i)?.let {
                    val workspace = it.string("workspace_id")
                    val tab = it.string("tab_id")
                    val label = display(it.string("label"))
                    if (validIdentity(workspace) && validIdentity(tab) && label.isNotBlank() && label != it.opt("number")?.toString())
                        tabNames[workspace to tab] = label
                }
            }
            val seen = mutableSetOf<String>()
            return buildList {
                for (index in 0 until agents.length()) {
                    val item = agents.optJSONObject(index) ?: continue
                    val terminal = item.string("terminal_id")
                    if (!terminalIdPattern.matches(terminal) || !seen.add(terminal)) continue
                    val title = sequenceOf("terminal_title_stripped", "terminal_title", "name")
                        .map { display(item.string(it)) }.firstOrNull { it.isNotBlank() } ?: terminal
                    val cwd = item.string("foreground_cwd").ifBlank { item.string("cwd") }
                    val workspace = item.string("workspace_id").takeIf(::validIdentity).orEmpty()
                    val project = display(cwd.trimEnd('/').substringAfterLast('/')).ifBlank { "Unknown project" }
                    add(SessionInfo(
                        SessionKey("herdr", connectionId, server, terminal),
                        display(item.string("pane_id")), tabNames[workspace to item.string("tab_id")] ?: title,
                        project,
                        display(item.string("agent")).ifBlank { "unknown" },
                        display(item.string("agent_status")).ifBlank { "unknown" },
                        workspace, workspaceNames[workspace] ?: project, title, root.opt("inputEnabled") == true,
                    ))
                }
            }
        } catch (error: CatalogException) {
            throw error
        } catch (_: Exception) {
            throw CatalogException("The Mac returned an invalid or failed Herdr catalog.")
        }
    }

    private fun JSONObject.string(name: String): String = opt(name) as? String ?: ""
    private fun JSONObject.hasError(): Boolean = (has("error") && !isNull("error")) || opt("success") == false || opt("ok") == false
    private fun display(value: String): String = ansi.replace(value.take(4096), "")
        .map { if (Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }
        .joinToString("").replace(Regex("\\s+"), " ").trim().take(256)
}
