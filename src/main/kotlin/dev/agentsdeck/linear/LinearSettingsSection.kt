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
        panel.add(
            dim(
                "Reads ${LinearQueries.KEY_NAME} from your shell environment, from the env block of a " +
                    "configured stdio MCP server, or from a key saved in Settings › Extensions.",
            ).leftAligned(),
        )
        panel.add(
            ActionLink("Create a personal API key in Linear") {
                BrowserUtil.browse(KEY_SETTINGS_URL)
            }.leftAligned(),
        )

        // The one network call this extension makes without the user typing `#`, and it happens
        // only because they opened this section to ask whether the key works.
        var alive = true
        Disposer.register(parent) { alive = false }
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = probe()
            ApplicationManager.getApplication().invokeLater(
                { if (alive) status.text = text },
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
        keyPresent == false -> "No ${LinearQueries.KEY_NAME}"
        keyPresent == true -> "API key found"
        else -> null
    }

    /** The line inside the expanded section, after a probe has been given its chance. */
    fun expanded(info: LinearWorkspaceInfo?, error: String?, keyPresent: Boolean): String = when {
        !keyPresent -> "No ${LinearQueries.KEY_NAME} — Linear issues will not be offered in chat."
        info != null && info.teamKeys.isNotEmpty() ->
            "Connected as ${info.user ?: "this key"} · ${info.workspace} · " +
                "${info.teamKeys.size} ${if (info.teamKeys.size == 1) "team" else "teams"}"
        info != null -> "Connected · ${info.workspace}"
        else -> error ?: "Linear did not answer."
    }
}
