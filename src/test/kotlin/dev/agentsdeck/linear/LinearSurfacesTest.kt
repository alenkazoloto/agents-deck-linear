package dev.agentsdeck.linear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two surfaces that render without asking anything: the transcript's links and the
 * settings section's lines.
 */
class LinearSurfacesTest {

    private val acme = LinearWorkspaceInfo("acme", "Acme", "Ada", setOf("ENG", "OPS"))

    @Test
    fun `a known team key links, an unknown one stays plain text`() {
        assertEquals("https://linear.app/acme/issue/ENG-123", LinearLinks.target(acme, "ENG-123"))
        assertNull("this workspace has no PROJ team", LinearLinks.target(acme, "PROJ-1"))
    }

    /**
     * The case this gate exists for. `UTF-8`, `SHA-256` and `RFC-822` all match the identifier
     * shape, and a workspace-scoped link to one of them is a 404 wearing an answer's clothes.
     */
    @Test
    fun `version-shaped tokens in agent output are not issues`() {
        listOf("UTF-8", "SHA-256", "RFC-822", "ISO-8601").forEach {
            assertTrue("`$it` looks like one", LinearLinks.IDENTIFIER.matches(it))
            assertNull("`$it` must not link", LinearLinks.target(acme, it))
        }
    }

    @Test
    fun `nothing links before the workspace has been probed`() {
        assertNull(LinearLinks.target(null, "ENG-1"))
        assertNull(
            "a slug learned from a url has no team keys yet",
            LinearLinks.target(LinearWorkspaceInfo("acme", "acme", null, emptySet()), "ENG-1"),
        )
    }

    @Test
    fun `the pattern only claims upper-case tokens`() {
        assertTrue(LinearLinks.IDENTIFIER.containsMatchIn("fixed in ENG-12 today"))
        assertTrue("a branch name is not an issue id", !LinearLinks.IDENTIFIER.containsMatchIn("feature-123"))
    }

    @Test
    fun `the collapsed line says nothing until something is known`() {
        assertNull(LinearStatus.collapsed(info = null, error = null, keyPresent = null))
        assertEquals("No API key", LinearStatus.collapsed(null, null, keyPresent = false))
        assertEquals("API key found", LinearStatus.collapsed(null, null, keyPresent = true))
    }

    /**
     * The no-key state says **"No API key"** and offers two links; it does not name the
     * environment variable, and it no longer describes where this extension looks for one.
     *
     * The reported version read `No LINEAR_API_KEY` over a paragraph naming all three sources
     * `resolveSecret` consults — our own plumbing, described to a reader who wanted a route. The
     * variable is the setup form's to print, on the field they paste into; what belongs here is
     * a place to make a key and a place to hand it over, in that order.
     */
    @Test
    fun `no key names both routes and neither the variable nor where we look for it`() {
        val state = LinearStatus.expanded(null, null, keyPresent = false)
        assertTrue(state, state.startsWith("No API key"))
        assertFalse("the variable belongs on the setup form's field: $state", state.contains(LinearQueries.KEY_NAME))

        val whole = "$state $CREATE_KEY_ACTION $SETUP_ACTION"
        assertTrue("a requirement with no route is a dead end: $whole", whole.contains("Create an API key in Linear"))
        assertTrue("and a key nobody can hand over is another: $whole", whole.contains("Add the key to Agents Deck"))
        listOf("env block", "shell environment", "stdio MCP", "resolveSecret").forEach {
            assertFalse("the mechanism belongs in a code comment: $it", whole.contains(it))
        }
    }

    /**
     * The order is the instruction: mint one at Linear, then hand it to this IDE. Offered the
     * other way round, the first thing a reader with nothing is told is to paste what they have
     * not got — and [WHERE_TO_ADD] is the sentence an older host gets in place of the second link,
     * because a link that answers false is worse than the page it replaced.
     */
    @Test
    fun `the two routes are distinct, ordered, and have a fallback for an older host`() {
        assertNotEquals(CREATE_KEY_ACTION, SETUP_ACTION)
        assertTrue(CREATE_KEY_ACTION, CREATE_KEY_ACTION.contains("Linear"))
        assertTrue(SETUP_ACTION, SETUP_ACTION.contains("Agents Deck"))
        assertTrue(WHERE_TO_ADD, WHERE_TO_ADD.contains("Settings"))
        assertFalse("an action a user clicks may not also name the page it opens", SETUP_ACTION.contains("Settings"))
    }

    @Test
    fun `a failure is stated, not swallowed`() {
        assertEquals(
            "Linear rejected the API key.",
            LinearStatus.collapsed(null, "Linear rejected the API key.", keyPresent = true),
        )
        assertEquals(
            "an empty popup and a rejected key must not read the same",
            "Linear rejected the API key.",
            LinearStatus.expanded(null, "Linear rejected the API key.", keyPresent = true),
        )
    }

    @Test
    fun `a working key names the workspace and never the key`() {
        val line = LinearStatus.expanded(acme, null, keyPresent = true)
        assertEquals("Connected as Ada · Acme · 2 teams", line)
        assertEquals("Ada · Acme", LinearStatus.collapsed(acme, null, keyPresent = true))
    }

    @Test
    fun `a slug-only workspace still reads as connected`() {
        assertEquals(
            "Connected · acme",
            LinearStatus.expanded(LinearWorkspaceInfo("acme", "acme", null, emptySet()), null, keyPresent = true),
        )
    }

    @Test
    fun `everything this extension sends goes to linear`() {
        assertEquals("https://api.linear.app/graphql", LinearQueries.ENDPOINT)
    }
}
