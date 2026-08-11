package dev.agentsdeck.linear

import com.github.claudeagents.api.ClaudeAgentsApi
import com.github.claudeagents.api.SectionText
import com.github.claudeagents.api.SettingsSectionContributor
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings › Connections › Integrations › **Linear** — which key is in force, who it belongs
 * to, and what to do when there is none.
 *
 * **Always available**, unlike the Tessl section's project gate: this extension is installed
 * on purpose and does exactly one thing, so a user who has it and no key needs somewhere to
 * be told which variable to set. Hiding the section would hide the only instruction.
 */
class LinearSettingsSection : SettingsSectionContributor {

    override val id = "linear.connection"

    override val title = "Linear"

    /**
     * EDT, every rebuild — fields only, never a resolution.
     *
     * Silent while nothing is known: the startup activity answers the key question without a
     * network call, and the workspace behind the key is filled by the first real use. Saying
     * "Not connected" before either has happened would be a guess in the grammar of a fact.
     */
    override fun statusLine(project: Project): String? =
        LinearStatus.collapsed(LinearWorkspace.snapshot(), LinearWorkspace.error(), LinearWorkspace.keyPresent())

    override fun createContent(project: Project, parent: Disposable): JComponent {
        val panel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(4)
        }

        val status = SectionText.help("Checking the API key…")
        panel.add(status.leftAligned())

        // Two links in the order the work happens — mint one at Linear, then put it into this
        // IDE — shown only to a reader who has no working key. A connected user gets one line.
        //
        // This replaces a permanent paragraph naming all three places `resolveSecret` looks. That
        // sentence described our plumbing to someone who wanted a route, and the one place it is
        // worth saying is the setup form, which prints the resolved source beside the field.
        val needsKey = mutableListOf<JComponent>()
        needsKey += ActionLink(CREATE_KEY_ACTION) { BrowserUtil.browse(KEY_SETTINGS_URL) }
        needsKey += if (LinearSetup.hostCanOpenSetup()) {
            ActionLink(SETUP_ACTION) { LinearSetup.openSetup(project) }
        } else {
            dim(WHERE_TO_ADD)
        }
        needsKey.forEach {
            it.isVisible = false
            panel.add(it.leftAligned())
        }

        // The one network call this extension makes without the user typing `#`, and it happens
        // only because they opened this section to ask whether the key works.
        var alive = true
        Disposer.register(parent) { alive = false }
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = probe()
            val missing = LinearWorkspace.keyPresent() == false
            ApplicationManager.getApplication().invokeLater(
                {
                    if (alive) {
                        status.text = text
                        needsKey.forEach { it.isVisible = missing }
                    }
                },
                ModalityState.any(),
            )
        }
        return panel
    }

    /** Background. Resolves the key, then asks Linear who it belongs to. */
    private fun probe(): String {
        val api = ApplicationManager.getApplication().getService(ClaudeAgentsApi::class.java)
            ?: return "Agents Deck is not available."
        val key = runCatching { api.resolveSecret(LinearQueries.KEY_NAME) }.getOrNull()?.trim()
        LinearWorkspace.rememberKeyPresence(!key.isNullOrEmpty())
        if (!key.isNullOrEmpty()) LinearWorkspace.ensure(key, LinearClient(HttpLinearTransport(key)))
        return LinearStatus.expanded(
            LinearWorkspace.snapshot(),
            LinearWorkspace.error(),
            keyPresent = !key.isNullOrEmpty(),
        )
    }

    private fun dim(text: String) = SectionText.help(text)

    private fun <T : JComponent> T.leftAligned(): T = apply { alignmentX = Component.LEFT_ALIGNMENT }

    private companion object {
        const val KEY_SETTINGS_URL = "https://linear.app/settings/api"
    }
}

/**
 * The pair of links a section with no credential owes, and the host capability behind the second
 * one.
 *
 * Worded as a sequence rather than as two verbs — "Create…" then "Add the key…" — because the two
 * read alike otherwise and a reader with neither clicks whichever came first. Granola's Meetings
 * tab and Sentry's section carry the same pair in the same order; the three extensions are
 * separate Gradle builds, so the shape is shared by convention and pinned by each one's own test.
 */
internal const val CREATE_KEY_ACTION = "Create an API key in Linear"

internal const val SETUP_ACTION = "Add the key to Agents Deck"

/** What replaces [SETUP_ACTION] on a host with no key form: a destination, not an action. */
internal const val WHERE_TO_ADD = "Add it under Settings › Extensions."

/**
 * Whether this host can open the key form itself, and the call that opens it.
 *
 * Feature-checked on `apiVersion`, never on the host plugin's version string — a surface that
 * offered the link on an older host would paint a link that answers false and does nothing.
 */
internal object LinearSetup {

    fun hostCanOpenSetup(): Boolean = runCatching {
        val api = ApplicationManager.getApplication()?.getService(ClaudeAgentsApi::class.java) ?: return false
        api.apiVersion >= SETUP_API_VERSION
    }.getOrDefault(false)

    /** **EDT** — it shows a modal. Answers whether the host had a form to open. */
    fun openSetup(project: Project): Boolean = runCatching {
        val api = ApplicationManager.getApplication()?.getService(ClaudeAgentsApi::class.java) ?: return false
        api.openExtensionSetup(project, LinearQueries.KEY_NAME)
    }.getOrDefault(false)

    /** The `apiVersion` that added `openExtensionSetup`. */
    private const val SETUP_API_VERSION = 3
}

/**
 * What the section says, kept out of the Swing so both lines are pinned without a settings
 * page — and so the "visibly degraded, never broken" claim is a test rather than a screenshot
 * of one lucky state.
 *
 * Neither line ever carries the key itself: the user knows what they set, and a settings page
 * is a place people take screenshots of.
 */
internal object LinearStatus {

    /**
     * The dimmed line beside the collapsed title.
     *
     * Null while nothing is known — the startup activity answers the key question without a
     * network call, and until it has, "Not connected" would be a guess wearing the grammar of
     * a fact.
     */
    fun collapsed(info: LinearWorkspaceInfo?, error: String?, keyPresent: Boolean?): String? = when {
        info != null -> info.user?.let { "$it · ${info.workspace}" } ?: info.workspace
        error != null -> error
        keyPresent == false -> NO_KEY
        keyPresent == true -> "API key found"
        else -> null
    }

    /**
     * What a reader sees when there is no credential — **"No API key"**, not the name of the
     * environment variable it would have been read from.
     *
     * `No LINEAR_API_KEY` answers a question a first-time reader was not asking. The variable
     * matters to whoever is exporting one, and the place that is done is the setup form, which
     * prints the name on the field itself.
     */
    const val NO_KEY = "No API key"

    /** The line inside the expanded section, after a probe has been given its chance. */
    fun expanded(info: LinearWorkspaceInfo?, error: String?, keyPresent: Boolean): String = when {
        !keyPresent -> "$NO_KEY — Linear issues will not be offered in chat."
        info != null && info.teamKeys.isNotEmpty() ->
            "Connected as ${info.user ?: "this key"} · ${info.workspace} · " +
                "${info.teamKeys.size} ${if (info.teamKeys.size == 1) "team" else "teams"}"
        info != null -> "Connected · ${info.workspace}"
        else -> error ?: "Linear did not answer."
    }
}
