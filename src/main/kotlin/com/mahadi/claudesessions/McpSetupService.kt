package com.mahadi.claudesessions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Turning Claude's session search on and off.
 *
 * Lives outside the panel because the switch does: it is a General setting now rather than a
 * toolbar tick. The work is the same whichever control asks for it — unpack the bundled server,
 * build its virtualenv, index the history, register with Claude Code, schedule the refresh — and
 * it runs under a progress indicator because the first run downloads dependencies.
 *
 * Every entry point reports back on the EDT with whether session search ended up on, so the
 * caller can settle its own control instead of guessing.
 */
object McpSetupService {

    fun isRegisteredAsync(onResult: (Boolean) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val registered = McpRegistrationService.isRegistered()
            ApplicationManager.getApplication().invokeLater({ onResult(registered) }, ModalityState.any())
        }
    }

    fun enable(project: Project, onResult: (Boolean) -> Unit) {
        object : Task.Backgroundable(project, "Setting up Claude session search", true) {
            private var failure: String? = null
            private var scheduled = false

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                if (!McpRuntime.isInstalled()) {
                    val failed = McpRuntime.install { indicator.text = it }.firstOrNull { !it.ok }
                    if (failed != null) {
                        failure = "${failed.label} failed.\n\n${failed.detail}"
                        return
                    }
                }
                if (indicator.isCanceled) return

                indicator.text = "Indexing your sessions…"
                val (indexed, indexOutput) = McpRuntime.ingest()
                if (!indexed) {
                    failure = "Indexing failed.\n\n${indexOutput.takeLast(600)}"
                    return
                }
                if (indicator.isCanceled) return

                indicator.text = "Registering with Claude Code…"
                val registration = McpRegistrationService.register()
                if (!registration.success) {
                    failure = "Registering with Claude Code failed.\n\n${registration.output}"
                    return
                }

                indicator.text = "Scheduling the background job…"
                scheduled = McpRuntime.installAgent(SessionMetadataStore.teamSync()).first
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater(
                    {
                        val message = failure
                        if (message != null) {
                            Messages.showErrorDialog(project, message, "Couldn't Enable Session Search")
                            onResult(false)
                        } else {
                            Messages.showInfoMessage(project, enabledMessage(scheduled), "Session Search Enabled")
                            onResult(true)
                        }
                    },
                    ModalityState.any(),
                )
            }
        }.queue()
    }

    fun disable(project: Project, onResult: (Boolean) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = McpRegistrationService.unregister()
            if (result.success) {
                // One agent serves both features now, so it may only go when team sync is off too.
                val teamSync = SessionMetadataStore.teamSync()
                if (teamSync.enabled) McpRuntime.installAgent(teamSync) else McpRuntime.removeAgent()
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (result.success) {
                        onResult(false)
                    } else {
                        Messages.showErrorDialog(
                            project,
                            result.output.ifBlank { "The claude CLI reported a failure." },
                            "Couldn't Remove MCP Server",
                        )
                        onResult(true)
                    }
                },
                ModalityState.any(),
            )
        }
    }

    private fun enabledMessage(scheduled: Boolean): String {
        val refreshNote = if (scheduled) {
            "The cache re-indexes itself on a schedule."
        } else {
            "Automatic re-indexing could not be scheduled — ask Claude to run refresh_cache " +
                "when you need it current."
        }
        return "Claude can now search your past sessions.\n\n" +
            "Restart any running Claude Code session to pick this up.\n" +
            refreshNote
    }
}
