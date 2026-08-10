package dev.agentsdeck.linear

import com.github.claudeagents.api.ExtIssueRef
import com.github.claudeagents.api.ExtUrlPattern
import com.github.claudeagents.api.ExternalIssueProvider
import com.github.claudeagents.api.IssueProviderContext
import com.github.claudeagents.api.IssueProviderFactory
import com.intellij.openapi.application.ApplicationManager

/**
 * `dev.agentsdeck.jetbrains.issueProvider` — Linear behind the chat's `#` completion, its
 * chips, its hover cards and its prompt enrichment.
 *
 * **No key, no contribution.** An empty list is the api's own way of saying "not configured",
 * and it is what keeps a plugin that is installed but unconfigured invisible: the popup is
 * then exactly the popup the host ships.
 *
 * The credential is a Linear **personal API key** resolved by name through the host's own
 * precedence (process environment, then a configured stdio MCP server's `env` block). Nothing
 * is stored by this extension — there is no settings field to type a key into on purpose, so
 * a secret cannot end up in this plugin's state.
 */
class LinearProviderFactory : IssueProviderFactory {

    override fun create(context: IssueProviderContext): List<ExternalIssueProvider> {
        val key = context.resolveSecret(LinearQueries.KEY_NAME)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        val provider = LinearIssueProvider(key)

        // Fire-and-forget: the workspace probe is what the transcript linker and the settings
        // line read, and neither may block. It must not be awaited here — the popup this call
        // is answering would then wait on a round trip it does not need. `ensure` returns
        // immediately once this session has an answer for this credential.
        ApplicationManager.getApplication()?.executeOnPooledThread {
            LinearWorkspace.ensure(key, provider.client)
        }
        return listOf(provider)
    }
}

/**
 * One configured Linear workspace.
 *
 * Blocking calls in `suspend` bodies on purpose: the extension point states that every method
 * is called on a background thread and that blocking is expected, so wrapping them in a
 * dispatcher would buy a thread hop and nothing else.
 *
 * Nothing here throws. The api's contract is that a failure costs this extension its
 * contribution rather than the user their completion popup, and the message that would have
 * been a stack trace goes to [LinearWorkspace] for the settings section to say out loud.
 */
internal class LinearIssueProvider(
    private val key: String,
    transport: LinearTransport = HttpLinearTransport(key),
) : ExternalIssueProvider {

    internal val client = LinearClient(transport)

    override val id: String = PROVIDER_ID

    /**
     * `ENG-123` yes, `42` no.
     *
     * Claiming the shape is not winning it — YouTrack ids look identical and the host resolves
     * a contested token against each claimant in turn until one actually answers. So this
     * claims what it can look up, which is any team-key-and-number, and loses the ones that
     * are not ours by returning null from [byId].
     */
    override fun canResolve(token: String): Boolean = LinearQueries.isIdentifier(token)

    /**
     * Rows for the popup. A typed full identifier is looked up directly — the structured
     * filter below matches *titles*, so `ENG-123` would otherwise find only issues that
     * mention it.
     */
    override suspend fun search(text: String, limit: Int): List<ExtIssueRef> {
        val typed = text.trim()
        if (LinearQueries.isIdentifier(typed)) return listOfNotNull(byId(typed))
        return client.search(typed, limit).value.onEach { remember(it) }
    }

    override suspend fun byId(token: String): ExtIssueRef? =
        client.issue(token).value?.also { remember(it) }

    override fun urlPatterns(): List<ExtUrlPattern> = listOf(ExtUrlPattern(LinearQueries.URL_PATTERN))

    /** The workspace slug arrives free with any issue the API returned; take it. */
    private fun remember(ref: ExtIssueRef) = LinearWorkspace.rememberIssueUrl(key, ref.url)

    companion object {
        const val PROVIDER_ID = "linear"
    }
}
