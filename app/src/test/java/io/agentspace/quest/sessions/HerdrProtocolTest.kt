package io.agentspace.quest.sessions

import org.junit.Assert.*
import org.junit.Test

class HerdrProtocolTest {
    @Test fun customTabLabelsAreScopedAndKeepTheOriginalTerminalTitle() {
        val json = """{"serverIdentity":"s","tabs":[{"workspace_id":"w1","tab_id":"tab","number":1,"label":"My tab"},{"workspace_id":"w2","tab_id":"tab","number":2,"label":"2"}],"catalog":{"result":{"agents":[{"terminal_id":"a","workspace_id":"w1","tab_id":"tab","terminal_title":"Generated title"},{"terminal_id":"b","workspace_id":"w2","tab_id":"tab","terminal_title":"Second title"}]}}}"""
        val sessions = HerdrProtocol.parseCatalog(json, "c")
        assertEquals("My tab", sessions[0].title)
        assertEquals("Generated title", sessions[0].terminalTitle)
        assertEquals("Second title", sessions[1].title)
        assertEquals("a", sessions[0].key.terminalId)
    }

    @Test fun workspaceGroupingUsesHerdrIdentityAndLabelInsteadOfDirectoryName() {
        val json = """{"serverIdentity":"s","workspaces":[{"workspace_id":"w1","label":"Workbench"},{"workspace_id":"w2","label":"Second"}],"catalog":{"result":{"agents":[{"terminal_id":"t1","workspace_id":"w1","cwd":"/same/repo"},{"terminal_id":"t2","workspace_id":"w2","cwd":"/same/repo"}]}}}"""
        val sessions = HerdrProtocol.parseCatalog(json, "c")
        assertEquals("w1", sessions[0].workspaceId)
        assertEquals("Workbench", sessions[0].workspaceLabel)
        assertNotEquals(sessions[0].workspaceId, sessions[1].workspaceId)
    }

    @Test fun inputDefaultsToDisabledUntilGateExplicitlyEnablesIt() {
        val json = envelope("""[{"terminal_id":"t1"}]""")
        assertFalse(HerdrProtocol.parseCatalog(json, "c")[0].inputEnabled)
        assertTrue(HerdrProtocol.parseCatalog(org.json.JSONObject(json).put("inputEnabled", true).toString(), "c")[0].inputEnabled)
    }

    private fun envelope(agents: String, server: String = "mac-epoch-1") =
        """{"serverIdentity":"$server","catalog":{"result":{"agents":$agents}}}"""

    @Test fun stableIdentitySurvivesRenameReorderAndIncludesServer() {
        val first = HerdrProtocol.parseCatalog(envelope("""[{"terminal_id":"t1","pane_id":"%1","terminal_title":"Old"},{"terminal_id":"t2"}]"""), "connection")
        val second = HerdrProtocol.parseCatalog(envelope("""[{"terminal_id":"t2"},{"terminal_id":"t1","terminal_title":"New"}]"""), "connection")
        assertEquals(first[0].key, second[1].key)
        assertNotEquals(first[0].key, HerdrProtocol.parseCatalog(envelope("""[{"terminal_id":"t1"}]""", "mac-epoch-2"), "connection")[0].key)
    }

    @Test fun unknownStatusOptionalFieldsAndDisplayControlsAreHandled() {
        val sessions = HerdrProtocol.parseCatalog(envelope("""[{"terminal_id":"t1","agent_status":"future-status","terminal_title_stripped":"Build\u001b[31m\nNow","foreground_cwd":"/example/projects/app","agent":"codex"},{"terminal_id":"t2"}]"""), "connection")
        assertEquals("future-status", sessions[0].reportedStatus)
        assertEquals("Build Now", sessions[0].title)
        assertEquals("app", sessions[0].project)
        assertEquals("unknown", sessions[1].reportedStatus)
        assertEquals("t2", sessions[1].title)
    }

    @Test fun malformedEntriesAreSkippedWithoutInventingIdsOrDuplicates() {
        val sessions = HerdrProtocol.parseCatalog(envelope("""[null,{},42,{"terminal_id":"bad\nidentity"},{"terminal_id":"t1"},{"terminal_id":"t1"},{"terminal_id":"t2"}]"""), "connection")
        assertEquals(listOf("t1", "t2"), sessions.map { it.key.terminalId })
    }

    @Test fun errorsMissingEnvelopeAndOversizeAreFailuresButEmptyArrayIsValid() {
        val invalid = listOf("{}", "[]", """{"serverIdentity":"s","catalog":{"error":"offline"}}""", """{"serverIdentity":"s","catalog":{"result":{}}}""", """{"serverIdentity":"","catalog":{"result":{"agents":[]}}}""", """{"serverIdentity":"s","catalog":{"error":"bad","result":{"agents":[]}}}""", "x".repeat(2 * 1024 * 1024 + 1))
        invalid.forEach { json ->
            assertThrows(CatalogException::class.java) { HerdrProtocol.parseCatalog(json, "connection") }
        }
        assertTrue(HerdrProtocol.parseCatalog(envelope("[]"), "connection").isEmpty())
    }

    @Test fun structuredIdentityRoundTripsWithoutLayoutSeparators() {
        val key = SessionKey("herdr", "connection,one", "server/epoch,2", "terminal:1")
        assertFalse(key.encode().contains(','))
        assertEquals(key, SessionKey.decode(key.encode()))
        assertNull(SessionKey.decode("broken"))
        assertNull(SessionKey.decode(""))
    }
}
