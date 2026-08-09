package dev.agentsdeck.linear

import org.junit.Assert.assertEquals
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
        assertEquals("No LINEAR_API_KEY", LinearStatus.collapsed(null, null, keyPresent = false))
        assertEquals("API key found", LinearStatus.collapsed(null, null, keyPresent = true))
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
