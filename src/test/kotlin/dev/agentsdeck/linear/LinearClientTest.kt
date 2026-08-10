package dev.agentsdeck.linear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three failure shapes a caller must not be able to tell apart by catching something:
 * no answer at all, a GraphQL error, and an answer in a shape this build does not read.
 * All three return a value *and* a sentence, and none of them throws.
 */
class LinearClientTest {

    @Test
    fun `no response at all is offline, not empty`() {
        val client = LinearClient { null }
        assertEquals("Could not reach linear.app.", client.search("x", 5).error)
        assertTrue(client.search("x", 5).value.isEmpty())
        assertEquals("Could not reach linear.app.", client.issue("ENG-1").error)
        assertNull(client.issue("ENG-1").value)
    }

    @Test
    fun `a graphql error travels beside the empty answer`() {
        val client = LinearClient { """{"errors":[{"message":"Authentication required"}]}""" }
        val answer = client.search("x", 5)
        assertTrue(answer.value.isEmpty())
        assertEquals("Authentication required", answer.error)
    }

    @Test
    fun `a shape we cannot read is said out loud rather than reported as no workspace`() {
        val answer = LinearClient { """{"data":{"viewer":{"name":"Ada"}}}""" }.viewer()
        assertNull(answer.value)
        assertEquals("Linear answered in a shape this build does not read.", answer.error)
    }

    @Test
    fun `a good answer carries no error`() {
        val answer = LinearClient {
            """{"data":{"issue":{"identifier":"ENG-1","title":"t","url":"https://linear.app/acme/issue/ENG-1"}}}"""
        }.issue("ENG-1")
        assertEquals("ENG-1", answer.value?.id)
        assertNull(answer.error)
    }
}
