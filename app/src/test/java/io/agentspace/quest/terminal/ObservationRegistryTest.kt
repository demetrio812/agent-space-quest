package io.agentspace.quest.terminal

import org.junit.Assert.*
import org.junit.Test

class ObservationRegistryTest {
    @Test fun twoViewsOneAdmissionAndReorderDoesNotChurn() {
        val registry = ObservationRegistry(3)
        registry.catalog(listOf("a", "b", "c"))
        registry.retain("b", preview = true)
        registry.retain("b", preview = false)
        assertEquals(listOf("b", "a", "c"), registry.starts())
        registry.starts().forEach(registry::admitted)
        registry.catalog(listOf("c", "b", "a"))
        assertTrue(registry.starts().isEmpty())
        assertTrue(registry.evictions().isEmpty())
        registry.release("b", preview = true)
        assertTrue(registry.evictions().isEmpty())
    }

    @Test fun selectedSessionTakesCappedSlotAndHaltedDoesNotRetry() {
        val registry = ObservationRegistry(2)
        registry.catalog(listOf("a", "b", "c"))
        registry.starts().forEach(registry::admitted)
        registry.watch("c")
        assertEquals(listOf("b"), registry.evictions())
        registry.stopped("b")
        assertEquals(listOf("c"), registry.starts())
        registry.halt("c")
        assertEquals(listOf("b"), registry.starts())
        registry.watch("c")
        registry.unhalt("c")
        assertEquals(listOf("c"), registry.starts())
    }

    @Test fun catalogRemovalEvictsOnlyUnreferencedSession() {
        val registry = ObservationRegistry(2)
        registry.catalog(listOf("a", "b"))
        registry.starts().forEach(registry::admitted)
        registry.retain("a", preview = true)
        registry.catalog(listOf("c"))
        assertEquals(listOf("a", "b"), registry.evictions())
        assertTrue(registry.retained("a"))
        registry.release("a", preview = true)
        assertFalse(registry.retained("a"))
    }

    @Test fun closedSessionDoesNotOccupyCapacityAndMissingSessionCannotStayLive() {
        val registry = ObservationRegistry(2)
        registry.catalog(listOf("a", "b", "c"))
        registry.starts().forEach(registry::admitted)
        registry.stopped("a")
        registry.halt("a", terminalClosed = true)
        assertEquals(listOf("c"), registry.starts())
        assertTrue(registry.closed("a"))
        registry.retain("b", preview = false)
        registry.watch("b")
        registry.catalog(listOf("a", "c"))
        assertEquals(listOf("b"), registry.evictions())
        registry.release("b", preview = false)
        assertFalse(registry.retained("b"))
    }
    @Test fun largeCatalogKeepsSixteenUniqueStreamsWhileDetailsMoveAndViewsDuplicate() {
        val registry = ObservationRegistry(16)
        val ids = (0 until 500).map { "s-$it" }
        registry.catalog(ids)
        registry.starts().forEach(registry::admitted)
        repeat(100) { n ->
            val id = ids[400 + n]
            registry.retain(id, preview = false)
            registry.retain(id, preview = false)
            registry.watch(id)
            registry.evictions().forEach(registry::stopped)
            registry.starts().forEach(registry::admitted)
            assertEquals(16, registry.activeIds().size)
            assertTrue(id in registry.activeIds())
            registry.release(id, preview = false)
            assertTrue(registry.evictions().isEmpty())
            registry.release(id, preview = false)
            registry.catalog(ids.reversed())
            assertTrue(registry.evictions().isEmpty())
        }
    }

}
