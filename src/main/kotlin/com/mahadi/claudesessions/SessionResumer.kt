package com.mahadi.claudesessions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
import com.mahadi.claudesessions.model.ClaudeSession
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.datatransfer.StringSelection
import java.io.File

private val LOG = logger<SessionResumerService>()

/** Actions implemented inside `intellij.terminal.frontend`, used only to reach its classloader. */
private val REWORKED_TERMINAL_ACTIONS = listOf("Terminal.OpenInReworkedTerminal", "Terminal.Escape")

private class SessionResumerService

/**
 * Opens a new tab in the integrated Terminal at the session's project directory and
 * runs `claude --resume <id>`.
 *
 * The reworked terminal is used when the IDE ships it, because that is what the `+` button
 * creates and it draws with its own colour scheme. Everything reachable from
 * `TerminalToolWindowManager` — including `createShellWidget` and the no-argument
 * `createNewSession` — hardcodes `TerminalEngine.CLASSIC`, and the classic engine paints from
 * the console colour scheme instead, so a tab opened that way looks unlike every other tab.
 *
 * The classic path stays as a fallback for IDEs without the reworked terminal. There the
 * command is typed into a shell, and the two widget types expose different entry points —
 * `sendCommandToExecute` on the `TerminalWidget` interface, `executeCommand` on
 * `ShellTerminalWidget`. That dispatch goes through the public types rather than reflection,
 * because the interface is implemented by a non-public bridge class whose methods reflection
 * cannot reach. If everything fails, the command is copied to the clipboard.
 */
object SessionResumer {

    fun resume(project: Project, session: ClaudeSession) {
        val workingDir = session.projectPath.takeIf { File(it).isDirectory }
            ?: System.getProperty("user.home")
        val tabName = "resume: ${session.title.take(24)}"
        val arguments = listOf(ClaudeBinaryLocator.resolve(), "--resume", session.sessionId)
        val environment = buildMap {
            put("COLORTERM", "truecolor")
            SessionMetadataStore.claudeConfigDir()?.let { put("CLAUDE_CONFIG_DIR", it) }
        }

        runCatching {
            openReworkedTab(project, workingDir, tabName, arguments, environment)
            LOG.info("Resumed Claude session ${session.sessionId} in the reworked terminal")
            return
        }.onFailure { LOG.info("Reworked terminal unavailable (${it.message}); using a classic tab") }

        val command = buildString {
            environment.forEach { (name, value) -> append("$name=\"$value\" ") }
            append(arguments.joinToString(" "))
        }

        try {
            val manager = TerminalToolWindowManager.getInstance(project)
            val widget = createWidget(manager, workingDir, tabName)
                ?: throw IllegalStateException("No terminal widget could be created")
            runCommand(widget, command)
            manager.toolWindow?.activate(null)
            LOG.info("Resumed Claude session %s via %s".format(session.sessionId, widget.javaClass.name))
        } catch (throwable: Throwable) {
            LOG.warn("Terminal launch failed for session ${session.sessionId}; copying command instead", throwable)
            copyAndNotify(project, workingDir, command)
        }
    }

    /**
     * Opens the tab through the reworked terminal's own builder API.
     *
     * That API lives in a plugin content module (`intellij.terminal.frontend`) which may be
     * absent on other IDE versions, so it is reached reflectively and any failure falls through
     * to the classic tab. Lookups go through the *interfaces*: the implementations are
     * non-public, and reflection cannot invoke their methods.
     *
     * The process is handed to the builder rather than typed into a shell, which also removes
     * the question of whether a command sent to a not-yet-started session survives.
     */
    private fun openReworkedTab(
        project: Project,
        workingDir: String,
        tabName: String,
        arguments: List<String>,
        environment: Map<String, String>,
    ) {
        val loader = reworkedTerminalClassLoader()
        val managerInterface =
            Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager", true, loader)
        val builderInterface =
            Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder", true, loader)

        val manager = managerInterface.getMethod("getInstance", Project::class.java).invoke(null, project)
        var builder: Any? = managerInterface.getMethod("createTabBuilder").invoke(manager)

        fun configure(name: String, type: Class<*>, value: Any) {
            builder = builderInterface.getMethod(name, type).invoke(builder, value)
        }

        configure("workingDirectory", String::class.java, workingDir)
        configure("tabName", String::class.java, tabName)
        configure("shellCommand", List::class.java, arguments)
        configure("envVariables", Map::class.java, environment)
        configure("requestFocus", java.lang.Boolean.TYPE, true)
        configure("closeOnProcessTermination", java.lang.Boolean.TYPE, false)

        builderInterface.getMethod("createTab").invoke(builder)
        TerminalToolWindowManager.getInstance(project).toolWindow?.activate(null)
    }

    /**
     * Classloader that can see the reworked terminal.
     *
     * It ships as a separate plugin content module (`intellij.terminal.frontend`) with its own
     * classloader, so the Terminal plugin's main loader raises `ClassNotFoundException` for it.
     * Declaring a `<module>` dependency would fix that but is mandatory — the plugin would then
     * refuse to load on any IDE without the module. Borrowing the loader from an action the
     * module registers keeps the dependency soft: no action, no reworked tab, classic fallback.
     */
    private fun reworkedTerminalClassLoader(): ClassLoader {
        val actionManager = ActionManager.getInstance()
        val action = REWORKED_TERMINAL_ACTIONS.firstNotNullOfOrNull { actionManager.getAction(it) }
            ?: throw IllegalStateException("No reworked terminal action is registered")
        return action.javaClass.classLoader
    }

    private fun createWidget(manager: TerminalToolWindowManager, workingDir: String, tabName: String): Any? {
        val managerClass = manager.javaClass
        val bool = java.lang.Boolean.TYPE

        runCatching {
            return managerClass
                .getMethod("createShellWidget", String::class.java, String::class.java, bool, bool)
                .invoke(manager, workingDir, tabName, true, true)
        }.onFailure { LOG.info("createShellWidget unavailable: ${it.message}") }

        runCatching {
            return managerClass
                .getMethod("createLocalShellWidget", String::class.java, String::class.java)
                .invoke(manager, workingDir, tabName)
        }.onFailure { LOG.warn("createLocalShellWidget failed: ${it.message}") }

        return null
    }


    /**
     * Sends the command through the widget's public type rather than reflectively.
     *
     * The reworked engine hands back a non-public bridge class, so a reflective lookup on
     * the concrete class fails with an access error even though the method is right there.
     * Each engine exposes exactly one entry point: the new one `sendCommandToExecute`, the
     * classic one `executeCommand`.
     */
    private fun runCommand(widget: Any, command: String) {
        when (widget) {
            is TerminalWidget -> widget.sendCommandToExecute(command)
            is ShellTerminalWidget -> widget.executeCommand(command)
            else -> throw IllegalStateException("Unsupported terminal widget ${widget.javaClass.name}")
        }
    }

    private fun copyAndNotify(project: Project, workingDir: String, command: String) {
        val fullCommand = "cd \"$workingDir\" && $command"
        CopyPasteManager.getInstance().setContents(StringSelection(fullCommand))
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Sessions")
            .createNotification(
                "Couldn't run in terminal — command copied to clipboard",
                fullCommand,
                NotificationType.WARNING,
            )
            .notify(project)
    }
}
