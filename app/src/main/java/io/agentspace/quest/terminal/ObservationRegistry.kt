package io.agentspace.quest.terminal

/** Main-thread ownership and admission policy. Active IDs stay admitted across catalog reorder. */
internal class ObservationRegistry(private val limit: Int = 16) {
    private val catalog = linkedSetOf<String>()
    private var catalogKnown = false
    private val detailRefs = mutableMapOf<String, Int>()
    private val previewRefs = mutableMapOf<String, Int>()
    private val active = linkedSetOf<String>()
    private val halted = mutableSetOf<String>()
    private val closed = mutableSetOf<String>()
    var selected: String? = null
        private set

    fun catalog(ids: List<String>) {
        catalogKnown = true; catalog.clear(); catalog.addAll(ids)
        halted.retainAll(catalog)
        closed.retainAll(catalog)
        if (selected !in catalog) selected = null
    }
    fun retain(id: String, preview: Boolean) {
        val refs = if (preview) previewRefs else detailRefs
        refs[id] = (refs[id] ?: 0) + 1
    }
    fun release(id: String, preview: Boolean) {
        val refs = if (preview) previewRefs else detailRefs
        val count = refs[id] ?: return
        if (count == 1) {
            refs.remove(id)
            if (!preview && selected == id) selected = null
        } else refs[id] = count - 1
    }
    fun watch(id: String) { selected = id }
    fun halt(id: String, terminalClosed: Boolean = false) { halted.add(id); if (terminalClosed) closed.add(id) }
    fun unhalt(id: String) { halted.remove(id); closed.remove(id) }
    fun admitted(id: String) { active.add(id) }
    fun stopped(id: String) { active.remove(id) }
    fun activeIds(): Set<String> = active.toSet()
    fun desired(): List<String> {
        val priority = linkedSetOf<String>()
        selected?.let(priority::add)
        priority.addAll(detailRefs.keys)
        priority.addAll(previewRefs.keys)
        priority.addAll(active.filter { it in catalog })
        priority.addAll(catalog)
        val live = priority.filter { (!catalogKnown || it in catalog) && it !in halted }
        return live.take(limit)
    }
    fun starts(): List<String> = desired().filter { it !in active && it !in halted }
    fun evictions(): List<String> = active.filter { it !in desired() }
    fun retained(id: String): Boolean = id == selected || id in catalog || id in detailRefs || id in previewRefs
    fun queued(id: String): Boolean = (id in catalog || id in detailRefs || id in previewRefs) && id !in active && id !in halted
    fun halted(id: String): Boolean = id in halted
    fun closed(id: String): Boolean = id in closed
    fun clear() { catalog.clear(); catalogKnown = false; detailRefs.clear(); previewRefs.clear(); active.clear(); halted.clear(); closed.clear(); selected = null }
}
