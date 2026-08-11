package dev.agentsdeck.linear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half: which tokens are Linear's, what goes on the wire, and what comes back.
 *
 * Every case runs with no network and no IDE, which is the point of keeping this object free
 * of both.
 */
class LinearQueriesTest {

    @Test
    fun `an identifier is a team key and a number`() {
        assertTrue(LinearQueries.isIdentifier("ENG-123"))
        assertTrue(LinearQueries.isIdentifier("A1-7"))
        assertTrue("a chat is not Linear's own UI", LinearQueries.isIdentifier("eng-123"))
    }

    @Test
    fun `a bare number and a prose word are not`() {
        assertFalse(LinearQueries.isIdentifier("42"))
        assertFalse(LinearQueries.isIdentifier("ENG"))
        assertFalse(LinearQueries.isIdentifier("ENG-"))
        assertFalse(LinearQueries.isIdentifier("-123"))
        assertFalse("the host asks about whole tokens", LinearQueries.isIdentifier("see ENG-123 first"))
    }

    @Test
    fun `the team key comes back upper case, and only from a real identifier`() {
        assertEquals("ENG", LinearQueries.teamKey("eng-123"))
        assertEquals("ENG", LinearQueries.teamKey("ENG-123"))
        assertNull(LinearQueries.teamKey("42"))
    }

    @Test
    fun `the issue query sends the canonical identifier as a variable`() {
        val body = LinearQueries.issueBody(" eng-123 ")
        assertTrue(body.contains("\"id\":\"ENG-123\""))
        assertTrue("the human identifier is what issue(id:) takes", body.contains("issue("))
        assertTrue(body.contains("identifier"))
        assertTrue(body.contains("description"))
    }

    @Test
    fun `the search query filters titles and is bounded`() {
        val body = LinearQueries.searchBody("login bug", 5)
        assertTrue(body.contains("containsIgnoreCase"))
        assertTrue(body.contains("\"term\":\"login bug\""))
        assertTrue(body.contains("\"first\":5"))
    }

    @Test
    fun `an absurd limit is clamped rather than sent`() {
        assertTrue(LinearQueries.searchBody("x", 10_000).contains("\"first\":50"))
        assertTrue(LinearQueries.searchBody("x", 0).contains("\"first\":1"))
    }

    @Test
    fun `a quote in the typed text cannot break the body`() {
        val body = LinearQueries.searchBody("say \"hi\"", 5)
        // Gson escaped it; the point is that this parses at all.
        assertTrue(body.contains("\\\"hi\\\""))
    }

    @Test
    fun `one issue parses into a row`() {
        val ref = LinearQueries.parseIssue(
            """
            {"data":{"issue":{"identifier":"ENG-123","title":"Login fails","description":"Steps…",
            "url":"https://linear.app/acme/issue/ENG-123/login-fails","state":{"name":"In Progress","type":"started"}}}}
            """.trimIndent(),
        )!!
        assertEquals("ENG-123", ref.id)
        assertEquals("Login fails", ref.summary)
        assertEquals("Steps…", ref.description)
        assertEquals("In Progress", ref.state)
        assertFalse(ref.resolved)
        assertFalse("Linear tracks issues, not pull requests", ref.isPullRequest)
    }

    @Test
    fun `done and canceled both read as resolved`() {
        listOf("completed", "canceled").forEach { type ->
            val ref = LinearQueries.parseIssue(
                """{"data":{"issue":{"identifier":"ENG-1","title":"t","url":"https://linear.app/acme/issue/ENG-1",
                "state":{"name":"Done","type":"$type"}}}}""".trimIndent(),
            )!!
            assertTrue("state type $type", ref.resolved)
        }
    }

    @Test
    fun `a row with no url is dropped, not returned half-built`() {
        val rows = LinearQueries.parseSearch(
            """{"data":{"issues":{"nodes":[
            {"identifier":"ENG-1","title":"kept","url":"https://linear.app/acme/issue/ENG-1"},
            {"identifier":"ENG-2","title":"no url"},
            {"title":"no identifier","url":"https://linear.app/acme/issue/ENG-3"}]}}}""".trimIndent(),
        )
        assertEquals(listOf("ENG-1"), rows.map { it.id })
    }

    @Test
    fun `garbage, an error payload and an empty body all parse to nothing`() {
        listOf("", "not json", "[]", """{"data":null}""", """{"errors":[{"message":"bad"}]}""").forEach {
            assertNull("issue from `$it`", LinearQueries.parseIssue(it))
            assertTrue("search from `$it`", LinearQueries.parseSearch(it).isEmpty())
            assertNull("viewer from `$it`", LinearQueries.parseViewer(it))
        }
    }

    @Test
    fun `the first error message is what a user would be shown`() {
        assertEquals(
            "Authentication required",
            LinearQueries.parseError("""{"errors":[{"message":"Authentication required"},{"message":"second"}]}"""),
        )
        assertNull(LinearQueries.parseError("""{"data":{"issue":null}}"""))
    }

    @Test
    fun `an errors array with no message still says something`() {
        assertEquals("Linear rejected the request.", LinearQueries.parseError("""{"errors":[{}]}"""))
    }

    @Test
    fun `the viewer answer carries the slug and every team key`() {
        val info = LinearQueries.parseViewer(
            """{"data":{"viewer":{"name":"Ada","organization":{"name":"Acme","urlKey":"acme"}},
            "teams":{"nodes":[{"key":"ENG"},{"key":"ops"}]}}}""".trimIndent(),
        )!!
        assertEquals("acme", info.urlKey)
        assertEquals("Acme", info.workspace)
        assertEquals("Ada", info.user)
        assertEquals(setOf("ENG", "OPS"), info.teamKeys)
    }

    @Test
    fun `a viewer answer without a slug is not an answer`() {
        assertNull(LinearQueries.parseViewer("""{"data":{"viewer":{"name":"Ada","organization":{"name":"Acme"}}}}"""))
    }

    @Test
    fun `a pasted issue link yields the bare token as group 1`() {
        val match = LinearQueries.URL_PATTERN.find("see https://linear.app/acme/issue/ENG-123/login-fails please")
        assertEquals("ENG-123", match!!.groupValues[1])
        assertTrue(
            "a link with no slug is still a link",
            LinearQueries.URL_PATTERN.containsMatchIn("https://linear.app/acme/issue/ENG-123"),
        )
    }

    @Test
    fun `the workspace slug is readable out of any issue url`() {
        assertEquals("acme", LinearQueries.urlKeyOf("https://linear.app/acme/issue/ENG-1/x"))
        assertNull(LinearQueries.urlKeyOf("https://example.com/acme/issue/ENG-1"))
    }
}
