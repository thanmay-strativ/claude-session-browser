package com.mahadi.claudesessions.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.mahadi.claudesessions.McpRuntime
import com.mahadi.claudesessions.SessionMetadataStore
import com.mahadi.claudesessions.TeamSyncNotifier
import com.mahadi.claudesessions.TeamSyncStatus
import com.mahadi.claudesessions.TeamSyncStatusService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Timer

private const val REFRESH_INTERVAL_MILLIS = 60_000

/**
 * A one-line strip under the toolbar answering the two questions a background job always
 * raises: did it work, and when does it next run.
 *
 * Hidden entirely when team sync is off, so a solo user never pays for a feature they
 * have not enabled. The countdown is coarse (minutes, not seconds) and repaints once a
 * minute — a live-ticking clock in a tool window is motion without information.
 */
internal class TeamSyncStatusStrip(private val project: Project) : JBPanel<TeamSyncStatusStrip>(BorderLayout()) {

    private val stateDot = StateDot()
    private val summaryLabel = JBLabel().apply { font = JBFont.small() }
    private val countdownLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = Ui.inkMuted
    }
    private val syncNowLabel = ActionLabel("Sync now") { runSyncNow() }
    private val pauseLabel = ActionLabel("Pause") { togglePause() }

    private val refreshTimer = Timer(REFRESH_INTERVAL_MILLIS) { refresh() }.apply { isRepeats = true }
    private var lastSeenStatusStamp = 0L
    private var running = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 8, 6, 8)

        val left = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(stateDot)
            add(summaryLabel)
            add(countdownLabel)
        }
        val right = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(pauseLabel)
            add(syncNowLabel)
        }
        add(left, BorderLayout.CENTER)
        add(right, BorderLayout.EAST)

        refresh()
        refreshTimer.start()
    }

    fun stopTimer() = refreshTimer.stop()

    /** Re-reads the config and the last cycle's status, and hides itself when sync is off. */
    fun refresh() {
        val config = SessionMetadataStore.teamSync()
        if (!config.enabled) {
            isVisible = false
            return
        }
        isVisible = true

        val status = TeamSyncStatusService.load()
        notifyIfNewFailure(status)

        pauseLabel.text = if (config.paused) "Resume" else "Pause"
        pauseLabel.toolTipText = if (config.paused) {
            "Resume publishing your sessions to the team."
        } else {
            "Stop publishing your sessions. Teammates' sessions still arrive."
        }

        val nextRun = TeamSyncStatusService.untilNextRun(config.syncHours)
        val nextClock = TeamSyncStatusService.nextRunClock(config.syncHours)
        countdownLabel.text = when {
            running -> ""
            config.paused -> ""
            nextRun != null && nextClock != null -> "· next $nextClock ($nextRun)"
            else -> ""
        }

        when {
            running -> {
                stateDot.tone = Ui.ACCENT
                summaryLabel.text = "Syncing…"
            }

            config.paused -> {
                stateDot.tone = Ui.ATTENTION
                summaryLabel.text = "Sharing paused — still receiving the team's sessions"
            }

            status == null -> {
                stateDot.tone = Ui.inkMuted
                summaryLabel.text = "Team sync ready — no run yet"
            }

            !status.ok -> {
                stateDot.tone = Ui.BAD
                summaryLabel.text = "Sync failed at '${status.failedStep}'"
            }

            else -> {
                stateDot.tone = Ui.GOOD
                val ago = status.finishedAt?.let { TeamSyncStatusService.relativeTime(it) } ?: "recently"
                summaryLabel.text = "Synced $ago · ${status.movementSummary()}"
            }
        }
        summaryLabel.toolTipText = status?.failedDetail?.takeIf { !status.ok }
            ?: "Sessions shared with your team through the knowledge-base repo."

        stateDot.repaint()
        revalidate()
    }

    /**
     * The panel is the only place that reliably runs while the user is around, so it is
     * where a failed background cycle gets surfaced. Keyed on the status file's timestamp
     * so an unchanged failure is not re-reported on every refresh.
     */
    private fun notifyIfNewFailure(status: TeamSyncStatus?) {
        if (status == null || status.ok) return
        val stamp = TeamSyncStatusService.statusFileTimestamp()
        if (stamp == lastSeenStatusStamp) return
        lastSeenStatusStamp = stamp
        TeamSyncNotifier.reportFailure(project, status)
    }

    private fun togglePause() {
        val config = SessionMetadataStore.teamSync()
        SessionMetadataStore.setTeamSyncPaused(!config.paused)
        refresh()
    }

    private fun runSyncNow() {
        if (running) return
        running = true
        refresh()
        ApplicationManager.getApplication().executeOnPooledThread {
            McpRuntime.runSync()
            ApplicationManager.getApplication().invokeLater(
                {
                    running = false
                    lastSeenStatusStamp = 0L
                    refresh()
                },
                ModalityState.any(),
            )
        }
    }

    /** A small filled circle carrying the run state; always paired with the words beside it. */
    private class StateDot : JComponent() {
        var tone: Color = Ui.inkMuted

        init {
            val size = JBUI.scale(8)
            preferredSize = Dimension(size, size)
            minimumSize = preferredSize
        }

        override fun paintComponent(graphics: Graphics) {
            val canvas = Ui.antialiased(graphics.create())
            try {
                canvas.color = tone
                canvas.fillOval(0, (height - width) / 2, width, width)
            } finally {
                canvas.dispose()
            }
        }
    }

    /** A text button that reads as a link: no chrome to compete with the toolbar above. */
    private class ActionLabel(text: String, private val onClick: () -> Unit) : JBLabel(text) {
        init {
            font = JBFont.small()
            foreground = Ui.ACCENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) = onClick()
            })
        }
    }
}
