package dev.agentsdeck.linear

import com.github.claudeagents.api.IssueProviderContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The contributed provider itself — what it claims, what it asks for, and what it does when
 * nothing is configured.
 */
class LinearProviderTest {

    @Before
    fun clearSessionState() = LinearWorkspace.reset()

    /** Every request body the provider produced, in order. */
    private val sent = mutableListOf<String>()

    private fun provider(response: (String) -> String?) =
        LinearIssueProvider("key-1", LinearTransport { body -> sent += body; response(body) })

    @Test
    fun `no key means no contribution at all`() {
        val factory = LinearProviderFactory()
        assertTrue(factory.create(context(null)).isEmpty())
        assertTrue("a blank variable is not a credential", factory.create(context("   ")).isEmpty())
    }

    @Test
    fun `a key contributes exactly one provider, under the id the host reserves for us`() {
        val contributed = LinearProviderFactory().create(context("lin_api_123"))
        assertEquals(1, contributed.size)
        assertEquals("linear", contributed.single().id)
    }

    @Test
    fun `the provider claims the identifier shape and nothing else`() {
        val provider = provider { null }
        assertTrue(provider.canResolve("ENG-123"))
        assertFalse("a bare number is YouTrack's or GitHub's, never ours", provider.canResolve("42"))
        assertFalse(provider.canResolve(""))
    }

    @Test
    fun `a typed identifier is looked up, not title-searched`() = runBlocking {
        val provider = provider { issueResponse() }
        val rows = provider.search("ENG-123", 10)
        assertEquals(listOf("ENG-123"), rows.map { it.id })
        assertEquals(1, sent.size)
        assertTrue("the title filter would find only issues mentioning it", sent[0].contains("issue("))
        assertFalse(sent[0].contains("containsIgnoreCase"))
    }

    @Test
    fun `free text goes to the title filter`() = runBlocking {
        val provider = provider { searchResponse() }
        assertEquals(listOf("ENG-1", "ENG-2"), provider.search("login", 10).map { it.id })
        assertTrue(sent.single().contains("containsIgnoreCase"))
    }

    @Test
    fun `a rejected key costs the popup nothing`() = runBlocking {
        val provider = provider { """{"errors":[{"message":"Authentication required"}]}""" }
        assertTrue(provider.search("login", 10).isEmpty())
        assertNull(provider.byId("ENG-1"))
    }

    @Test
    fun `resolving an issue teaches the session its workspace slug`() = runBlocking {
        provider { issueResponse() }.byId("ENG-123")
        assertEquals("acme", LinearWorkspace.snapshot()?.urlKey)
        assertTrue(
            "a slug alone is not a licence to linkify — team keys are still unknown",
            LinearWorkspace.snapshot()!!.teamKeys.isEmpty(),
        )
    }

    @Test
    fun `the pasted-link pattern captures a token the host can resolve`() {
        val pattern = provider { null }.urlPatterns().single()
        val token = pattern.regex.find("https://linear.app/acme/issue/ENG-9/x")!!.groupValues[1]
        assertEquals("ENG-9", token)
    }

    private fun context(secret: String?) = object : IssueProviderContext {
        override val projectDir: String? = "/tmp/project"
        override fun resolveSecret(name: String): String? =
            secret.takeIf { name == LinearQueries.KEY_NAME }
    }

    private fun issueResponse() =
        """{"data":{"issue":{"identifier":"ENG-123","title":"Login fails",
        "url":"https://linear.app/acme/issue/ENG-123/login-fails","state":{"name":"Todo","type":"unstarted"}}}}"""

    private fun searchResponse() =
        """{"data":{"issues":{"nodes":[
        {"identifier":"ENG-1","title":"a","url":"https://linear.app/acme/issue/ENG-1"},
        {"identifier":"ENG-2","title":"b","url":"https://linear.app/acme/issue/ENG-2"}]}}}"""
}
