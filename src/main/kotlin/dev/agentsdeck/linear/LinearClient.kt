package dev.agentsdeck.linear

import com.github.claudeagents.api.ExtIssueRef
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One POST of a GraphQL body, and the response text.
 *
 * A `fun interface` so every test above this line runs with no network: the real one is
 * [HttpLinearTransport], and the tests hand in a lambda. Null means the request never
 * produced a body at all.
 */
internal fun interface LinearTransport {
    fun post(body: String): String?
}

/** A value and, when something went wrong, the one sentence worth showing a user. */
internal data class LinearAnswer<T>(val value: T, val error: String? = null)

/**
 * Linear's GraphQL API behind three calls.
 *
 * **Every method answers rather than throws** — the `issueProvider` api's contract, and the
 * reason the error travels *beside* the value instead of instead of it: the completion popup
 * wants the empty list, the settings section wants the sentence, and neither should have to
 * catch anything to get it.
 *
 * Blocking. Called on the host's background threads only.
 */
internal class LinearClient(private val transport: LinearTransport) {

    fun issue(token: String): LinearAnswer<ExtIssueRef?> {
        val response = transport.post(LinearQueries.issueBody(token))
            ?: return LinearAnswer(null, OFFLINE)
        LinearQueries.parseError(response)?.let { return LinearAnswer(null, it) }
        return LinearAnswer(LinearQueries.parseIssue(response))
    }

    fun search(text: String, limit: Int): LinearAnswer<List<ExtIssueRef>> {
        val response = transport.post(LinearQueries.searchBody(text, limit))
            ?: return LinearAnswer(emptyList(), OFFLINE)
        LinearQueries.parseError(response)?.let { return LinearAnswer(emptyList(), it) }
        return LinearAnswer(LinearQueries.parseSearch(response))
    }

    fun viewer(): LinearAnswer<LinearWorkspaceInfo?> {
        val response = transport.post(LinearQueries.viewerBody())
            ?: return LinearAnswer(null, OFFLINE)
        LinearQueries.parseError(response)?.let { return LinearAnswer(null, it) }
        val info = LinearQueries.parseViewer(response)
        return LinearAnswer(info, if (info == null) "Linear answered in a shape this build does not read." else null)
    }

    private companion object {
        const val OFFLINE = "Could not reach linear.app."
    }
}

/**
 * The real transport: `Authorization: <key>`, no `Bearer` — that is what a Linear *personal
 * API key* wants ([developers/graphql](https://linear.app/developers/graphql)), and the reason
 * this extension needs no OAuth, no browser redirect and no password store.
 *
 * A non-2xx with no GraphQL body of its own is turned into one, so an HTTP failure and a
 * schema failure arrive at the caller through a single channel rather than two that could be
 * picked between wrongly.
 */
internal class HttpLinearTransport(private val apiKey: String) : LinearTransport {

    override fun post(body: String): String? {
        val request = HttpRequest.newBuilder(URI.create(LinearQueries.ENDPOINT))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            .getOrNull() ?: return null
        val text = response.body().orEmpty()
        if (response.statusCode() in 200..299) return text.ifBlank { null }
        return if (LinearQueries.parseError(text) != null) text else httpError(response.statusCode())
    }

    private fun httpError(status: Int): String {
        val message = when (status) {
            401, 403 -> "Linear rejected the API key."
            429 -> "Linear rate limit reached — try again shortly."
            else -> "Linear returned HTTP $status."
        }
        return """{"errors":[{"message":${quote(message)}}]}"""
    }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)

        val client: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
