package com.mahadi.claudesessions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.mahadi.claudesessions.model.ClaudeSession

private val LOG = logger<SessionExporterService>()

private class SessionExporterService

/**
 * Exports a session's transcript as a Markdown file, chosen via a native save dialog.
 */
object SessionExporter {

    fun export(project: Project, session: ClaudeSession) {
        val descriptor = FileSaverDescriptor("Export Session", "Save the transcript as Markdown", "md")
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val targetFile = dialog.save(defaultFileName(session))?.file ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val entries = ClaudeTranscriptReader().read(session.transcriptPath)
                targetFile.writeText(buildMarkdown(session, entries))
                notify(project, "Session exported", targetFile.absolutePath, NotificationType.INFORMATION)
            } catch (throwable: Throwable) {
                LOG.warn("Failed exporting session ${session.sessionId}", throwable)
                notify(project, "Export failed", throwable.message ?: "Unknown error", NotificationType.ERROR)
            }
        }
    }

    private fun defaultFileName(session: ClaudeSession): String {
        val safeTitle = session.title.take(48)
            .map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '_' }
            .joinToString("")
        return "$safeTitle.md"
    }

    private fun buildMarkdown(session: ClaudeSession, entries: List<TranscriptEntry>): String = buildString {
        appendLine("# ${session.title}")
        appendLine()
        appendLine("- Project: ${session.projectName} (`${session.projectPath}`)")
        session.gitBranch?.let { appendLine("- Branch: $it") }
        session.model?.let { appendLine("- Model: $it") }
        appendLine("- Messages: ${session.messageCount}")
        appendLine("- Session ID: ${session.sessionId}")
        appendLine()
        appendLine("---")
        for (entry in entries) {
            appendLine()
            appendLine("**${roleText(entry)}**")
            appendLine()
            if (entry.kind == EntryKind.TOOL_USE || entry.kind == EntryKind.TOOL_RESULT) {
                appendLine("```")
                appendLine(entry.text)
                appendLine("```")
            } else {
                appendLine(entry.text)
            }
        }
    }

    private fun roleText(entry: TranscriptEntry): String = when (entry.kind) {
        EntryKind.USER -> "You"
        EntryKind.ASSISTANT -> "Claude"
        EntryKind.THINKING -> "Claude (thinking)"
        EntryKind.TOOL_USE -> "Tool: ${entry.toolName ?: "unknown"}"
        EntryKind.TOOL_RESULT -> "Result"
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Sessions")
            .createNotification(title, content, type)
            .notify(project)
    }
}
