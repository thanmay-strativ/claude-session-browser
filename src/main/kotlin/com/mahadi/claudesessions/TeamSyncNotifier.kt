package com.mahadi.claudesessions

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

private const val LAST_REPORTED_FAILURE = "com.mahadi.claudesessions.lastReportedSyncFailure"
/** Must match the `notificationGroup` id registered in plugin.xml. */
private const val NOTIFICATION_GROUP = "Claude Sessions"

/**
 * Surfaces a failed scheduled sync, which is otherwise silent: the agent runs in the
 * background, so a rejected push or a blocked secret would sit unnoticed until someone
 * happened to open the Health view.
 *
 * Each cycle is reported at most once — keyed by the run's own finish time — so a
 * failure that stays unfixed does not nag on every panel refresh.
 */
object TeamSyncNotifier {

    fun reportFailure(project: Project, status: TeamSyncStatus) {
        if (status.ok || !SessionMetadataStore.teamSync().notifyOnFailure) return

        val runKey = status.finishedAt?.toString() ?: return
        val properties = PropertiesComponent.getInstance()
        if (properties.getValue(LAST_REPORTED_FAILURE) == runKey) return
        properties.setValue(LAST_REPORTED_FAILURE, runKey)

        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Team session sync failed",
                "The '${status.failedStep}' step failed: ${status.failedDetail.orEmpty().take(200)}\n\n" +
                    "Open Stats → Health for the details.",
                NotificationType.WARNING,
            )
            .notify(project)
    }
}
