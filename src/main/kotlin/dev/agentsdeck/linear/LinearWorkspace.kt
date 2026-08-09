package dev.agentsdeck.linear

import java.security.MessageDigest

/**
 * What the configured key turned out to belong to, for the session.
 *
 * Two readers need this and neither may do I/O: [LinearLinkContributor.linkers] runs on the
 * EDT per rendered message, and [LinearSettingsSection.statusLine] runs on the EDT per
 * rebuild. So the probe happens once, on a background thread, and both read the field it
 * leaves behind.
 *
 * **Keyed by a digest of the credential, not by nothing.** A key that is rotated — or a
 * different one resolved out of a project-local MCP `env` block — belongs to a different
 * workspace, and a cache that answered for the previous one would linkify tokens into a
 * workspace the user is no longer in. The digest is what is retained; the key is not.
 */
internal object LinearWorkspace {

    /** Cleared verdict, so a probe that failed for a fixable reason is retried within a session. */
    private const val RETRY_AFTER_NANOS = 5L * 60 * 1_000_000_000

    private data class State(
        val credential: String,
        val info: LinearWorkspaceInfo?,
        val error: String?,
        val probedAtNanos: Long,
        /**
         * True only when [ensure] wrote this. [rememberIssueUrl] also fills [info], with a slug
         * and **no team keys** — so "we know something" and "we asked" are different questions,
         * and a cache that answered the first one would leave the linker permanently refusing.
         */
        val probed: Boolean,
    )

    @Volatile
    private var state: State? = null

    @Volatile
    private var keyPresent: Boolean? = null

    /** EDT-safe. Null until a probe has answered for the credential in force. */
    fun snapshot(): LinearWorkspaceInfo? = state?.info

    /** EDT-safe. The last probe's failure, or null. */
    fun error(): String? = state?.error

    /**
     * EDT-safe. Whether a key resolved at all, or null while nobody has looked.
     *
     * A separate question from [snapshot], and the cheap half: answering it costs a file read
     * and no network, which is why the startup activity answers it and stops there. The
     * settings line can then say "No `LINEAR_API_KEY`" — the one thing the user can act on —
     * without this extension having called linear.app unprompted.
     */
    fun keyPresent(): Boolean? = keyPresent

    /** Background only: the resolution behind it reads a file. */
    fun rememberKeyPresence(present: Boolean) {
        keyPresent = present
    }

    /**
     * Probes [client] for the workspace behind [key], unless this session already did.
     *
     * **Background threads only** — it blocks on a request. A failure is remembered too, so a
     * bad key costs one round trip per five minutes rather than one per keystroke; but it does
     * expire, because "the key is wrong" stops being true the moment the user fixes it.
     */
    fun ensure(key: String, client: LinearClient, nowNanos: Long = System.nanoTime()) {
        val credential = digest(key)
        val current = state?.takeIf { it.credential == credential }
        if (current != null) {
            // Complete means *team keys*, not "we have something". A slug learned from an issue
            // URL fills `info` and answers nothing the linker needs, so a freshness rule phrased
            // as `info != null` would retire the probe that was going to supply them.
            val complete = current.info?.teamKeys?.isNotEmpty() == true
            val backedOff = current.probed && nowNanos - current.probedAtNanos < RETRY_AFTER_NANOS
            if (complete || backedOff) return
        }
        val answer = client.viewer()
        state = State(credential, answer.value, answer.error, nowNanos, probed = true)
    }

    /**
     * The workspace slug out of an issue URL the API already handed us — free, and it is what
     * lets a link exist before anyone opens Settings. It never invents team keys: a link needs
     * both, and a slug alone leaves [LinearLinkContributor] still refusing.
     */
    fun rememberIssueUrl(key: String, url: String) {
        val urlKey = LinearQueries.urlKeyOf(url) ?: return
        val credential = digest(key)
        val current = state?.takeIf { it.credential == credential }
        if (current?.info != null) return
        state = State(
            credential = credential,
            info = LinearWorkspaceInfo(urlKey = urlKey, workspace = urlKey, user = null, teamKeys = emptySet()),
            error = current?.error,
            // Carried, not cleared: a sighting is not an attempt, and zeroing these would let a
            // credential whose probe just failed be retried on every keystroke.
            probedAtNanos = current?.probedAtNanos ?: 0L,
            probed = current?.probed ?: false,
        )
    }

    /** Test seam: the object outlives one case otherwise. */
    fun reset() {
        state = null
        keyPresent = null
    }

    private fun digest(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
