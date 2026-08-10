package dev.agentsdeck.linear

import com.github.claudeagents.api.ClaudeAgentsApi
import com.github.claudeagents.api.TranscriptLinkContributor
import com.github.claudeagents.api.TranscriptLinkSpec
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * `ENG-123` in agent output becomes a link to the issue.
 *
 * **Only for team keys this workspace actually has.** The shape is not ours alone — YouTrack
 * ids are identical, and so are `UTF-8`, `SHA-256` and `RFC-822`. Linking every match would
 * aim a link at a workspace that has no such issue, which is a 404 dressed as an answer. So
 * the pattern is broad and the *target* is gated: an unknown team key returns null, the
 * documented way to say "recognised the shape, cannot resolve it", and the token stays plain
 * text.
 *
 * Until [LinearWorkspace] has been probed there are no team keys and this contributes nothing
 * at all — the first `#` lookup or the first visit to Settings fills it.
 *
 * EDT, per rendered message: the list is built here and the target function does string work
 * only. The pattern is bounded and alternation-free — a catastrophic backtrack here freezes
 * the transcript with nothing to catch it (EXTENDING.md §7).
 */
class LinearLinkContributor : TranscriptLinkContributor {

    override fun linkers(project: Project): List<TranscriptLinkSpec> {
        val info = LinearWorkspace.snapshot()?.takeIf { it.teamKeys.isNotEmpty() } ?: return emptyList()
        return listOf(
            // `inCode = false`: the host's own rule is that identifiers stay literal inside
            // code spans, and an issue id is an identifier.
            TranscriptLinkSpec(LinearLinks.IDENTIFIER) { match -> LinearLinks.target(info, match.value) },
        )
    }
}

/**
 * The two decisions the linker makes, kept out of the extension point so both are pinned
 * without a `Project` — which is otherwise the only thing standing between these rules and a
 * test.
 */
internal object LinearLinks {

    /** Upper case only: agent output writes the canonical id, and `foo-123` is a filename. */
    val IDENTIFIER = Regex("""\b[A-Z][A-Z0-9]{0,9}-\d{1,7}\b""")

    /** Null for a team key this workspace does not have — `UTF-8` is the case that matters. */
    fun target(info: LinearWorkspaceInfo?, token: String): String? {
        if (info == null || info.teamKeys.isEmpty()) return null
        val team = LinearQueries.teamKey(token) ?: return null
        if (team !in info.teamKeys) return null
        return "https://linear.app/${info.urlKey}/issue/${LinearQueries.canonicalize(token)}"
    }
}

/**
 * Resolves whether a key exists at all, once per project open, off the EDT — **and makes no
 * network call**.
 *
 * Without it the settings section can say nothing until the user expands it, because the two
 * questions it answers are asked on the EDT and resolving a secret reads a file. The
 * *workspace* probe is deliberately not here: it would send the user's key to linear.app on
 * every IDE start, which is a request nobody asked for. That one happens on first use.
 */
class LinearStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val api = ApplicationManager.getApplication().getService(ClaudeAgentsApi::class.java) ?: return
        val key = runCatching { api.resolveSecret(LinearQueries.KEY_NAME) }.getOrNull()
        LinearWorkspace.rememberKeyPresence(!key.isNullOrBlank())
    }
}
