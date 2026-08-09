package dev.agentsdeck.linear

import com.github.claudeagents.api.ExtIssueRef
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Request bodies and defensive parsing for Linear's GraphQL API. Pure — no HTTP, no threads,
 * no platform types — so every shape below is pinned by a test with no network and no IDE.
 *
 * **Only schema the primary docs show.** `issue(id: "ENG-123")` taking the *human* identifier
 * is on Linear's own getting-started page. A full-text field is not: the docs do not name one
 * and secondary sources disagree between `issueSearch(query:)` and `searchIssues(term:)`.
 * Guessing wrong returns a GraphQL error, the provider answers empty by contract, and an empty
 * completion popup looks exactly like "no results" — so the failure would be invisible. Search
 * is therefore structured filtering, which has been in the schema throughout.
 *
 * Every parser answers null/empty on anything it does not recognise. Nothing here throws:
 * the api forbids it, and a `#` keystroke is not a place to learn that a server changed.
 */
internal object LinearQueries {

    const val ENDPOINT = "https://api.linear.app/graphql"

    /** The env-style name the host resolves for us. Named in `ClaudeAgentsApi.resolveSecret`'s own KDoc. */
    const val KEY_NAME = "LINEAR_API_KEY"

    /**
     * `ENG-123` — a team key and a number. Accepted case-insensitively because a user types
     * into a chat, not into Linear; [canonicalize] puts it back in the shape the API wants.
     *
     * Bounded on purpose: this is matched against rendered agent text on the EDT, where a
     * pattern that backtracks has nothing to catch it (EXTENDING.md §7).
     */
    private val IDENTIFIER = Regex("""[A-Za-z][A-Za-z0-9]{0,9}-\d{1,7}""")

    /** `https://linear.app/<workspace>/issue/ENG-123/<slug>` — group 1 is the bare token. */
    val URL_PATTERN = Regex("""https://linear\.app/[\w.-]{1,64}/issue/([A-Za-z][A-Za-z0-9]{0,9}-\d{1,7})""")

    fun isIdentifier(token: String): Boolean = IDENTIFIER.matches(token.trim())

    /** `eng-123` → `ENG-123`. The team key is upper case everywhere Linear renders it. */
    fun canonicalize(token: String): String = token.trim().uppercase()

    /** The team key of an identifier (`ENG` of `ENG-123`), or null when it is not one. */
    fun teamKey(token: String): String? =
        token.trim().takeIf { isIdentifier(it) }?.substringBefore('-')?.uppercase()

    fun issueBody(token: String): String = body(
        """
        query Issue(${'$'}id: String!) {
          issue(id: ${'$'}id) {
            identifier
            title
            description
            url
            state { name type }
          }
        }
        """.trimIndent(),
        JsonObject().apply { addProperty("id", canonicalize(token)) },
    )

    fun searchBody(text: String, limit: Int): String = body(
        """
        query Search(${'$'}term: String!, ${'$'}first: Int!) {
          issues(
            first: ${'$'}first
            filter: { title: { containsIgnoreCase: ${'$'}term } }
            orderBy: updatedAt
          ) {
            nodes {
              identifier
              title
              url
              state { name type }
            }
          }
        }
        """.trimIndent(),
        JsonObject().apply {
            addProperty("term", text.trim())
            addProperty("first", limit.coerceIn(1, 50))
        },
    )

    /**
     * Who the key belongs to, the workspace slug, and the team keys.
     *
     * The team keys are what stop the transcript linker turning *every* `ABC-123` in agent
     * output into a Linear link: a project can use YouTrack ids of the identical shape, and a
     * link to a workspace that has no such team is a 404 dressed as an answer.
     */
    fun viewerBody(): String = body(
        """
        query Viewer {
          viewer {
            name
            organization { name urlKey }
          }
          teams(first: 100) { nodes { key } }
        }
        """.trimIndent(),
        JsonObject(),
    )

    /** One issue, or null — a missing identifier or url makes a row no surface could use. */
    fun parseIssue(response: String): ExtIssueRef? =
        node(response, "issue")?.let(::toRef)

    /** Rows for the completion popup. Unparseable entries are dropped, not failed on. */
    fun parseSearch(response: String): List<ExtIssueRef> {
        val nodes = node(response, "issues")?.asObjectOrNull()?.get("nodes")?.asArrayOrNull()
            ?: return emptyList()
        return nodes.mapNotNull { toRef(it) }
    }

    fun parseViewer(response: String): LinearWorkspaceInfo? {
        val data = json(response)?.asObjectOrNull()?.get("data")?.asObjectOrNull() ?: return null
        val viewer = data["viewer"]?.asObjectOrNull() ?: return null
        val org = viewer["organization"]?.asObjectOrNull()
        val urlKey = org?.get("urlKey")?.string() ?: return null
        val teams = data["teams"]?.asObjectOrNull()?.get("nodes")?.asArrayOrNull()
            ?.mapNotNull { it.asObjectOrNull()?.get("key")?.string()?.uppercase() }
            ?.toSet()
            .orEmpty()
        return LinearWorkspaceInfo(
            urlKey = urlKey,
            workspace = org["name"]?.string() ?: urlKey,
            user = viewer["name"]?.string(),
            teamKeys = teams,
        )
    }

    /**
     * The first GraphQL error message, or null.
     *
     * Read but never thrown: the caller still answers empty, and this is what the settings
     * section says out loud instead. A wrong key and a schema drift both produce an empty
     * popup — only the message tells them apart, and only one of them is the user's to fix.
     */
    fun parseError(response: String): String? {
        val errors = json(response)?.asObjectOrNull()?.get("errors")?.asArrayOrNull() ?: return null
        return errors.firstNotNullOfOrNull { it.asObjectOrNull()?.get("message")?.string() }
            ?: "Linear rejected the request."
    }

    /** The workspace slug out of any issue URL we were handed — the cheapest place it exists. */
    fun urlKeyOf(issueUrl: String): String? =
        Regex("""https://linear\.app/([\w.-]{1,64})/issue/""").find(issueUrl)?.groupValues?.get(1)

    private fun toRef(element: JsonElement?): ExtIssueRef? {
        val node = element?.asObjectOrNull() ?: return null
        val id = node["identifier"]?.string()?.takeIf { it.isNotBlank() } ?: return null
        val url = node["url"]?.string()?.takeIf { it.startsWith("https://") } ?: return null
        val state = node["state"]?.asObjectOrNull()
        val type = state?.get("type")?.string()?.lowercase()
        return ExtIssueRef(
            id = id,
            summary = node["title"]?.string().orEmpty(),
            description = node["description"]?.string()?.takeIf { it.isNotBlank() },
            state = state?.get("name")?.string()?.takeIf { it.isNotBlank() },
            resolved = type == "completed" || type == "canceled",
            url = url,
            // Linear tracks issues; a pull request there is a linked attachment, not an issue.
            isPullRequest = false,
        )
    }

    private fun body(query: String, variables: JsonObject): String =
        JsonObject().apply {
            addProperty("query", query)
            add("variables", variables)
        }.toString()

    private fun json(response: String): JsonElement? =
        runCatching { JsonParser.parseString(response) }.getOrNull()

    private fun node(response: String, field: String): JsonElement? =
        json(response)?.asObjectOrNull()?.get("data")?.asObjectOrNull()?.get(field)

    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.asArrayOrNull(): List<JsonElement>? = takeIf { it.isJsonArray }?.asJsonArray?.toList()

    private fun JsonElement.string(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}

/** What one credential turned out to belong to. */
internal data class LinearWorkspaceInfo(
    val urlKey: String,
    val workspace: String,
    val user: String?,
    val teamKeys: Set<String>,
)
