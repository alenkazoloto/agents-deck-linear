package dev.agentsdeck.linear

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The session cache two EDT readers depend on: the transcript linker and the settings line.
 *
 * The cases that matter are the ones about *when it asks again* — a cache that never re-asks
 * outlives the mistake the user just fixed, and one that always asks spends a round trip per
 * keystroke.
 */
class LinearWorkspaceTest {

    private var calls = 0

    @Before
    fun clear() = LinearWorkspace.reset()

    @After
    fun clearAfter() = LinearWorkspace.reset()

    private fun client(response: () -> String?) =
        LinearClient(LinearTransport { calls++; response() })

    @Test
    fun `one probe per credential per session`() {
        val client = client { VIEWER }
        repeat(5) { LinearWorkspace.ensure("key-1", client) }
        assertEquals(1, calls)
        assertEquals("acme", LinearWorkspace.snapshot()?.urlKey)
        assertEquals(setOf("ENG"), LinearWorkspace.snapshot()?.teamKeys)
    }

    @Test
    fun `a different key is a different workspace, so it is asked about`() {
        val client = client { VIEWER }
        LinearWorkspace.ensure("key-1", client)
        LinearWorkspace.ensure("key-2", client)
        assertEquals(2, calls)
    }

    @Test
    fun `a failed probe is remembered briefly and then retried`() {
        val client = client { """{"errors":[{"message":"Authentication required"}]}""" }
        LinearWorkspace.ensure("key-1", client, nowNanos = 0)
        LinearWorkspace.ensure("key-1", client, nowNanos = MINUTE)
        assertEquals("a bad key must not cost a round trip per keystroke", 1, calls)
        assertEquals("Authentication required", LinearWorkspace.error())

        LinearWorkspace.ensure("key-1", client, nowNanos = 6 * MINUTE)
        assertEquals("but the user may have fixed it", 2, calls)
    }

    @Test
    fun `a slug learned from an issue url does not count as having asked`() {
        LinearWorkspace.rememberIssueUrl("key-1", "https://linear.app/acme/issue/ENG-1/x")
        assertEquals("acme", LinearWorkspace.snapshot()?.urlKey)
        assertTrue(LinearWorkspace.snapshot()!!.teamKeys.isEmpty())

        LinearWorkspace.ensure("key-1", client { VIEWER })
        assertEquals("the team keys are still missing, so the probe is still owed", 1, calls)
        assertEquals(setOf("ENG"), LinearWorkspace.snapshot()?.teamKeys)
    }

    /**
     * A sighting is not an attempt. If learning a slug reset the backoff, a workspace whose
     * `viewer` query fails while its issue lookups succeed would be probed once per popup.
     */
    @Test
    fun `a url sighting after a failed probe does not reopen the retry window`() {
        val client = client { """{"errors":[{"message":"nope"}]}""" }
        LinearWorkspace.ensure("key-1", client, nowNanos = 0)
        LinearWorkspace.rememberIssueUrl("key-1", "https://linear.app/acme/issue/ENG-1/x")
        LinearWorkspace.ensure("key-1", client, nowNanos = MINUTE)
        assertEquals(1, calls)

        LinearWorkspace.ensure("key-1", client, nowNanos = 6 * MINUTE)
        assertEquals(2, calls)
    }

    @Test
    fun `a real probe is not overwritten by a later url sighting`() {
        LinearWorkspace.ensure("key-1", client { VIEWER })
        LinearWorkspace.rememberIssueUrl("key-1", "https://linear.app/acme/issue/ENG-1/x")
        assertEquals(setOf("ENG"), LinearWorkspace.snapshot()?.teamKeys)
    }

    @Test
    fun `an unreadable url teaches nothing`() {
        LinearWorkspace.rememberIssueUrl("key-1", "https://example.com/whatever")
        assertNull(LinearWorkspace.snapshot())
    }

    private companion object {
        const val MINUTE = 60L * 1_000_000_000
        const val VIEWER =
            """{"data":{"viewer":{"name":"Ada","organization":{"name":"Acme","urlKey":"acme"}},
            "teams":{"nodes":[{"key":"ENG"}]}}}"""
    }
}
