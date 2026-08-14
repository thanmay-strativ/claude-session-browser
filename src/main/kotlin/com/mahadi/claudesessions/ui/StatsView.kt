package com.mahadi.claudesessions.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mahadi.claudesessions.CacheStatsService
import com.mahadi.claudesessions.HealthCheckService
import com.mahadi.claudesessions.McpRegistrationService
import com.mahadi.claudesessions.SessionMetadataStore
import com.mahadi.claudesessions.SessionTags
import com.mahadi.claudesessions.UsageStatsService
import com.mahadi.claudesessions.model.ClaudeSession
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants
import javax.swing.ToolTipManager

private const val USAGE_WINDOW_DAYS = 7
private const val ACTIVITY_WINDOW_DAYS = 14
private const val ACTIVE_DAYS_WINDOW = 30
private const val BREAKDOWN_LIMIT = 8
private const val HEAVIEST_LIMIT = 5
private const val MODEL_CHART_THRESHOLD = 3
private const val QUIET_SESSION_MESSAGES = 2

/**
 * CSS px for wrapping a health check's detail line. A literal, not [JBUI.scale]d: this is
 * consumed by Swing's HTML renderer, which does not share JBUI's scaling, so scaling it
 * made every card wider than the dialog and clipped the labels.
 */
private const val DETAIL_WRAP_WIDTH = 330

private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT)
private val DAY_TOOLTIP: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ROOT)

/**
 * Read-only dialog summarising session activity.
 *
 * Everything but the usage and activity sections is computed from the already-loaded session list,
 * so it describes the selected account only. Usage reads every account and keeps them in separate
 * cards rather than summing them.
 */
class StatsDialog(
    project: Project,
    private val sessions: List<ClaudeSession>,
) : DialogWrapper(project) {

    init {
        title = "Claude Session Stats"
        init()
    }

    override fun createCenterPanel(): JComponent = JBTabbedPane().apply {
        addTab("Stats", StatsPanel(sessions))
        addTab("Health", HealthPanel())
    }
}

/**
 * Live status of everything the plugin set up in the background: the scheduled launchd
 * jobs, the per-account MCP registrations, and the tools the sync cycle leans on.
 * Exists so "is it actually working?" has an answer inside the IDE instead of in
 * launchctl and log files.
 */
private class HealthPanel : JBPanel<HealthPanel>(BorderLayout()) {

    private val summaryRow = JBPanel<JBPanel<*>>(GridLayout(1, 3, JBUI.scale(8), 0)).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(56))
    }
    private val jobsSection = section("Background jobs").apply { add(Ui.mutedRow("Checking…")) }
    private val toolsSection = section("Tools & integrations").apply { add(Ui.mutedRow("Checking…")) }

    init {
        preferredSize = Dimension(JBUI.scale(520), JBUI.scale(660))
        background = UIUtil.getPanelBackground()

        val content = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 14, 14, 14)
            add(Ui.sectionTitle("Overview"))
            add(summaryRow)
            add(jobsSection)
            add(toolsSection)
        }

        add(
            JBScrollPane(content).apply {
                border = JBUI.Borders.empty()
                verticalScrollBar.unitIncrement = JBUI.scale(16)
                // Height may overflow; width never. A horizontal scrollbar here clipped the
                // check labels and painted itself over the last row.
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )

        loadAsync()
    }

    private fun loadAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val report = HealthCheckService.load()
            ApplicationManager.getApplication().invokeLater(
                {
                    populateSummary(report.jobs + report.tools)
                    populate(jobsSection, "Background jobs", report.jobs)
                    populate(toolsSection, "Tools & integrations", report.tools)
                },
                ModalityState.any(),
            )
        }
    }

    /** Counts first: how many checks are fine, need a look, or are broken. */
    private fun populateSummary(checks: List<HealthCheckService.Check>) {
        val working = checks.count { it.state == HealthCheckService.State.OK }
        val attention = checks.count { it.state == HealthCheckService.State.WARNING }
        val broken = checks.count { it.state == HealthCheckService.State.PROBLEM }

        summaryRow.removeAll()
        summaryRow.add(StatTile(working.toString(), "Working", Ui.GOOD))
        summaryRow.add(StatTile(attention.toString(), "Needs a look", Ui.ATTENTION))
        summaryRow.add(StatTile(broken.toString(), "Not working", if (broken > 0) Ui.BAD else Ui.inkMuted))
        summaryRow.revalidate()
        summaryRow.repaint()
    }

    private fun populate(target: JBPanel<*>, title: String, checks: List<HealthCheckService.Check>) {
        target.removeAll()
        target.add(Ui.sectionTitle(title))
        checks.forEachIndexed { index, check ->
            if (index > 0) target.add(hairlineSpacer())
            target.add(checkCard(check))
        }
        target.revalidate()
        target.repaint()
    }

    /** A hairline with air either side, so stacked cards read as one list of checks. */
    private fun hairlineSpacer(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(5, 2)
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(11))
        add(Hairline(), BorderLayout.CENTER)
    }

    /**
     * Each check is a card carrying its state twice over — as a coloured rail down the
     * edge and as a word in the chip — so the row is readable at a glance and still
     * legible to anyone who cannot separate the hues.
     */
    private fun checkCard(check: HealthCheckService.Check): JComponent {
        val tone = stateColor(check.state)
        val heading = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(
                JBLabel(check.label).apply { font = JBFont.label().asBold() },
                BorderLayout.WEST,
            )
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(Chip(stateWord(check.state), tone))
                },
                BorderLayout.EAST,
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        val body = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(heading)
            add(
                JBLabel("<html><body style='width: ${DETAIL_WRAP_WIDTH}px'>${check.detail}</body></html>").apply {
                    foreground = Ui.inkMuted
                    font = JBFont.small()
                    alignmentX = LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyTop(3)
                }
            )
        }
        return Card(rail = tone).apply {
            border = JBUI.Borders.empty(9, 14)
            add(body, BorderLayout.CENTER)
        }
    }

    private fun stateWord(state: HealthCheckService.State): String = when (state) {
        HealthCheckService.State.OK -> "working"
        HealthCheckService.State.WARNING -> "attention"
        HealthCheckService.State.PROBLEM -> "not working"
        HealthCheckService.State.OFF -> "off"
    }

    private fun stateColor(state: HealthCheckService.State): Color = when (state) {
        HealthCheckService.State.OK -> Ui.GOOD
        HealthCheckService.State.WARNING -> Ui.ATTENTION
        HealthCheckService.State.PROBLEM -> Ui.BAD
        HealthCheckService.State.OFF -> Ui.inkMuted
    }

    private fun section(title: String): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(Ui.sectionTitle(title))
    }
}

private class StatsPanel(private val sessions: List<ClaudeSession>) : JBPanel<StatsPanel>(BorderLayout()) {

    private val activitySection = section(activityTitle()).apply { add(Ui.mutedRow("Reading transcripts…")) }
    private val usageSection = section(usageTitle()).apply { add(Ui.mutedRow("Reading transcripts…")) }
    private val memorySection = section("Claude memory (MCP)").apply { add(Ui.mutedRow("Reading cache…")) }
    private val filesSection = section("Files you return to").apply { add(Ui.mutedRow("Reading cache…")) }
    private val toolsSection = section("Tool use").apply { add(Ui.mutedRow("Reading cache…")) }

    init {
        preferredSize = Dimension(JBUI.scale(520), JBUI.scale(660))
        background = UIUtil.getPanelBackground()

        val content = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 14, 14, 14)
        }

        content.add(overviewSection())
        content.add(activitySection)
        content.add(usageSection)
        content.add(memorySection)
        content.add(chartSection("Heaviest sessions", heaviestBreakdown(), "messages"))
        content.add(chartSection("By project", projectBreakdown(), "messages"))
        content.add(modelSection())
        content.add(chartSection("Top branches", branchBreakdown(), "messages"))
        content.add(chartSection("Top tags", tagBreakdown(), "sessions"))
        content.add(filesSection)
        content.add(toolsSection)
        content.add(housekeepingSection())

        add(
            JBScrollPane(content).apply {
                border = JBUI.Borders.empty()
                verticalScrollBar.unitIncrement = JBUI.scale(16)
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )

        loadUsageAsync()
        loadCacheAsync()
    }

    private fun overviewSection(): JComponent {
        val panel = section("Overview")
        panel.add(
            Ui.mutedRow("Account ${SessionMetadataStore.activeEnvironment().name} — this account's sessions only")
        )
        panel.add(
            tileRow(
                StatTile(Ui.formatCompact(sessions.size.toLong()), "Sessions", Ui.ACCENT),
                StatTile(Ui.formatCompact(sessions.sumOf { it.messageCount }.toLong()), "Messages", Ui.ACCENT),
                StatTile(activeDays().toString(), "Active days / $ACTIVE_DAYS_WINDOW", Ui.ACCENT),
            )
        )
        return panel
    }

    /** Days you actually worked, which says more about a month than the number of sessions does. */
    private fun activeDays(): Int {
        val cutoff = LocalDate.now().minusDays(ACTIVE_DAYS_WINDOW.toLong())
        return sessions
            .map { dayOf(it.lastModifiedMillis) }
            .filter { it.isAfter(cutoff) }
            .distinct()
            .size
    }

    private fun loadUsageAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val usage = UsageStatsService.load(USAGE_WINDOW_DAYS)
            ApplicationManager.getApplication().invokeLater(
                {
                    replaceSection(activitySection, activityTitle()) { populateActivity(usage) }
                    replaceSection(usageSection, usageTitle()) { populateUsage(usage) }
                },
                ModalityState.any(),
            )
        }
    }

    private fun loadCacheAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val stats = CacheStatsService.load()
            val registered = McpRegistrationService.isRegistered()
            ApplicationManager.getApplication().invokeLater(
                {
                    replaceSection(memorySection, "Claude memory (MCP)") {
                        if (stats == null) {
                            memorySection.add(Ui.mutedRow("Couldn't read the cache — tick MCP in the panel to build it"))
                        } else {
                            populateMemory(stats, registered)
                        }
                    }
                    replaceSection(filesSection, "Files you return to") {
                        val files = stats?.topFiles.orEmpty().take(BREAKDOWN_LIMIT)
                        if (files.isEmpty()) {
                            filesSection.add(Ui.mutedRow("Nothing indexed yet"))
                        } else {
                            filesSection.add(fileChartCard(files))
                        }
                    }
                    replaceSection(toolsSection, "Tool use") {
                        val tools = stats?.topTools.orEmpty().take(BREAKDOWN_LIMIT)
                        if (tools.isEmpty()) {
                            toolsSection.add(Ui.mutedRow("Nothing indexed yet"))
                        } else {
                            toolsSection.add(toolChartCard(tools))
                        }
                    }
                },
                ModalityState.any(),
            )
        }
    }

    private fun replaceSection(target: JBPanel<*>, title: String, populate: () -> Unit) {
        target.removeAll()
        target.add(Ui.sectionTitle(title))
        populate()
        target.revalidate()
        target.repaint()
    }

    private fun populateActivity(usage: List<UsageStatsService.EnvironmentUsage>) {
        val account = usage.firstOrNull { it.isActive } ?: usage.firstOrNull()
        if (account == null || account.dailyMessages.isEmpty()) {
            activitySection.add(Ui.mutedRow("No activity in the last $ACTIVITY_WINDOW_DAYS days"))
            return
        }

        val today = LocalDate.now()
        val series = (0 until ACTIVITY_WINDOW_DAYS).map { offset ->
            val day = today.minusDays((ACTIVITY_WINDOW_DAYS - 1 - offset).toLong())
            day to (account.dailyMessages[day] ?: 0)
        }

        activitySection.add(Card().apply { add(DailyActivityChart(series), BorderLayout.CENTER) })
        activitySection.add(Ui.mutedRow(activityCaption(series, account)))
    }

    private fun activityCaption(
        series: List<Pair<LocalDate, Int>>,
        account: UsageStatsService.EnvironmentUsage,
    ): String {
        val busiest = series.maxByOrNull { it.second }
        val workedDays = series.count { it.second > 0 }
        return buildString {
            if (busiest != null && busiest.second > 0) {
                append("Busiest ${busiest.first.format(DAY_LABEL)} (${Ui.formatCompact(busiest.second.toLong())})")
                append(" · ")
            }
            append("worked $workedDays of $ACTIVITY_WINDOW_DAYS days")
            changeText(account)?.let { append(" · ").append(it) }
        }
    }

    private fun changeText(account: UsageStatsService.EnvironmentUsage): String? {
        val change = account.messageChangePercent() ?: return null
        return when {
            change > 0 -> "$change% more than the previous $USAGE_WINDOW_DAYS days"
            change < 0 -> "${-change}% fewer than the previous $USAGE_WINDOW_DAYS days"
            else -> "level with the previous $USAGE_WINDOW_DAYS days"
        }
    }

    private fun populateUsage(usage: List<UsageStatsService.EnvironmentUsage>) {
        if (usage.isEmpty()) {
            usageSection.add(Ui.mutedRow("No Claude environments are configured"))
            return
        }

        val busiest = usage.maxOf { it.messages }.coerceAtLeast(1)
        usage.forEach { account ->
            usageSection.add(accountCard(account, busiest))
            usageSection.add(spacer(6))
        }
    }

    private fun accountCard(account: UsageStatsService.EnvironmentUsage, busiest: Int): JComponent {
        val heading = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel(account.environmentName).apply { font = JBFont.label().asBold() })
            if (account.isActive) add(Chip("selected", Ui.ACCENT))
        }

        val body = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(heading.alignedLeft())
        }

        when {
            !account.rootExists -> body.add(Ui.mutedRow("Session directory not found"))
            account.messages == 0 -> body.add(Ui.mutedRow("No activity in the last $USAGE_WINDOW_DAYS days"))
            else -> {
                body.add(
                    MeterBar(
                        fraction = account.messages.toDouble() / busiest,
                        label = "${account.sessions} session(s) · " +
                            "${Ui.formatCompact(account.messages.toLong())} messages",
                        fill = Ui.ACCENT,
                    )
                )
                changeText(account)?.let { body.add(Ui.mutedRow(it)) }
                body.add(Ui.mutedRow(tokenText(account)))
            }
        }

        return Card().apply { add(body, BorderLayout.CENTER) }
    }

    private fun tokenText(account: UsageStatsService.EnvironmentUsage): String =
        "${Ui.formatCompact(account.promptTokens)} prompt · " +
            "${Ui.formatCompact(account.outputTokens)} output · " +
            "${Ui.formatCompact(account.cacheReadTokens)} cache reads"

    private fun populateMemory(stats: CacheStatsService.CacheStats, registered: Boolean) {
        val notIndexed = (sessions.size - stats.indexedSessions).coerceAtLeast(0)
        val indexedShare = if (sessions.isNotEmpty()) {
            stats.indexedSessions.coerceAtMost(sessions.size).toDouble() / sessions.size
        } else {
            0.0
        }

        val body = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(
                MeterBar(
                    fraction = indexedShare,
                    label = "${stats.indexedSessions} of ${sessions.size} sessions searchable by Claude",
                    fill = if (notIndexed == 0) Ui.GOOD else Ui.ATTENTION,
                )
            )
            add(
                Ui.mutedRow(
                    "${Ui.formatCompact(stats.commits.toLong())} commits · " +
                        "${Ui.formatCompact(stats.filesTouched.toLong())} files touched · " +
                        "${stats.sessionsWithCommits} sessions produced a commit"
                )
            )
            add(Ui.mutedRow(freshnessText(stats)))
            add(
                Ui.mutedRow(
                    if (registered) "Registered with Claude Code" else "Not registered with Claude Code"
                )
            )
            if (notIndexed > 0) {
                add(Ui.mutedRow("$notIndexed session(s) awaiting ingest — Claude can't see these yet"))
            }
            add(
                Ui.mutedRow(
                    "${stats.taggedSessions} tagged · ${stats.subagentSessions} subagent transcripts · " +
                        "${stats.redactedMessages} messages with secrets hidden"
                )
            )
        }
        memorySection.add(Card().apply { add(body, BorderLayout.CENTER) })
    }

    /**
     * How current the cache is. A stale cache means Claude's own searches quietly miss today's
     * work, which is invisible unless it is stated.
     */
    private fun freshnessText(stats: CacheStatsService.CacheStats): String {
        val newest = stats.newestActivity?.let(::parseInstant)
            ?: return "Cache freshness unknown — no indexed activity yet"
        val hours = ChronoUnit.HOURS.between(newest, Instant.now())
        return when {
            hours <= 1 -> "Cache is current"
            hours < 24 -> "Newest indexed session is ${hours}h old"
            else -> "Newest indexed session is ${hours / 24}d old — re-index to catch up"
        }
    }

    private fun parseInstant(value: String): Instant? = try {
        Instant.parse(value)
    } catch (ignored: DateTimeParseException) {
        null
    }

    /** The basename labels the row; the full path only has to be readable in the tooltip. */
    private fun fileChartCard(files: List<CacheStatsService.Counted>): JComponent {
        val entries = files.map { it.label.substringAfterLast('/') to it.count }
        return Card().apply {
            add(HorizontalBarChart(entries, "sessions", files.map { it.label }), BorderLayout.CENTER)
        }
    }

    private fun toolChartCard(tools: List<CacheStatsService.Counted>): JComponent =
        Card().apply {
            add(HorizontalBarChart(tools.map { it.label to it.count }, "calls"), BorderLayout.CENTER)
        }

    private fun modelSection(): JComponent {
        val entries = modelBreakdown()
        if (entries.size >= MODEL_CHART_THRESHOLD) return chartSection("By model", entries, "messages")

        val panel = section("By model")
        if (entries.isEmpty()) {
            panel.add(Ui.mutedRow("No data yet"))
            return panel
        }
        // One or two models make a chart with one bar. The share reads better as a sentence.
        val total = entries.sumOf { it.second }.coerceAtLeast(1)
        panel.add(
            Ui.mutedRow(
                entries.joinToString(" · ") { (label, value) ->
                    "${value * 100 / total}% ${label.substringBefore(" (")}"
                }
            )
        )
        return panel
    }

    private fun housekeepingSection(): JComponent {
        val panel = section("Housekeeping")
        val quiet = sessions.count { it.messageCount <= QUIET_SESSION_MESSAGES }
        val neverTagged = sessions.count { SessionMetadataStore.tags(it.sessionId).isEmpty() }
        panel.add(
            Ui.mutedRow(
                "$quiet session(s) with $QUIET_SESSION_MESSAGES messages or fewer — " +
                    "select them in the list to delete in bulk"
            )
        )
        panel.add(Ui.mutedRow("$neverTagged session(s) you have never tagged by hand"))
        return panel
    }

    private fun chartSection(title: String, entries: List<Pair<String, Int>>, unit: String): JComponent {
        val panel = section(title)
        if (entries.isEmpty()) {
            panel.add(Ui.mutedRow("No data yet"))
            return panel
        }
        panel.add(Card().apply { add(HorizontalBarChart(entries, unit), BorderLayout.CENTER) })
        return panel
    }

    private fun heaviestBreakdown(): List<Pair<String, Int>> =
        sessions.sortedByDescending { it.messageCount }
            .take(HEAVIEST_LIMIT)
            .map { it.title to it.messageCount }

    private fun projectBreakdown(): List<Pair<String, Int>> =
        sessions.groupBy { it.projectName }
            .map { (name, group) -> "$name (${group.size})" to group.sumOf { it.messageCount } }
            .sortedByDescending { it.second }
            .take(BREAKDOWN_LIMIT)

    private fun modelBreakdown(): List<Pair<String, Int>> =
        sessions.groupBy { it.model ?: "unknown" }
            .map { (name, group) -> "$name (${group.size})" to group.sumOf { it.messageCount } }
            .sortedByDescending { it.second }
            .take(BREAKDOWN_LIMIT)

    private fun branchBreakdown(): List<Pair<String, Int>> =
        sessions.filter { !it.gitBranch.isNullOrBlank() && it.gitBranch != "HEAD" }
            .groupBy { it.gitBranch.orEmpty() }
            .map { (branch, group) -> "$branch (${group.size})" to group.sumOf { it.messageCount } }
            .sortedByDescending { it.second }
            .take(BREAKDOWN_LIMIT)

    private fun tagBreakdown(): List<Pair<String, Int>> =
        sessions.flatMap { SessionTags.all(it) }
            .groupingBy { "#$it" }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(BREAKDOWN_LIMIT)

    private fun tileRow(vararg tiles: JComponent): JComponent =
        JBPanel<JBPanel<*>>(GridLayout(1, tiles.size, JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(56))
            tiles.forEach { add(it) }
        }

    private fun section(title: String): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(Ui.sectionTitle(title))
    }

    private fun spacer(height: Int): JComponent = JBPanel<JBPanel<*>>().apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        preferredSize = Dimension(1, JBUI.scale(height))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(height))
    }

    private fun JBPanel<*>.alignedLeft(): JBPanel<*> = apply {
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun dayOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun usageTitle(): String = "Usage — last $USAGE_WINDOW_DAYS days, per account"

    private fun activityTitle(): String = "Activity — last $ACTIVITY_WINDOW_DAYS days"
}

/** A single proportional bar with its caption underneath — the meter form, not a chart. */
private class MeterBar(
    private val fraction: Double,
    private val label: String,
    private val fill: Color,
) : JComponent() {

    private val barHeight = JBUI.scale(8)
    private val labelGap = JBUI.scale(5)

    init {
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        border = JBUI.Borders.emptyTop(6)
        preferredSize = Dimension(JBUI.scale(360), barHeight + labelGap + JBUI.scale(18))
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(graphics: Graphics) {
        val canvas = Ui.antialiased(graphics.create())
        try {
            canvas.color = Ui.TRACK
            canvas.fillRoundRect(0, 0, width, barHeight, barHeight, barHeight)
            Ui.fillBarFromLeft(canvas, 0, 0, (width * fraction.coerceIn(0.0, 1.0)).toInt(), barHeight, fill)

            canvas.color = Ui.inkMuted
            canvas.font = JBFont.small()
            canvas.drawString(label, 0, barHeight + labelGap + canvas.fontMetrics.ascent)
        } finally {
            canvas.dispose()
        }
    }
}

/**
 * Messages per day as columns.
 *
 * A day with no work keeps its slot and shows a flat stub, so a gap reads as "nothing happened"
 * rather than as missing data. Only the peak is labelled directly — a number over every column is
 * noise, and the rest are in the tooltip.
 */
private class DailyActivityChart(
    private val entries: List<Pair<LocalDate, Int>>,
) : JComponent() {

    private val plotHeight = JBUI.scale(70)
    private val axisGap = JBUI.scale(5)

    init {
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(360), plotHeight + axisGap + JBUI.scale(16))
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun getToolTipText(event: MouseEvent): String? {
        if (entries.isEmpty()) return null
        val slot = (width.toDouble() / entries.size).coerceAtLeast(1.0)
        val entry = entries.getOrNull((event.x / slot).toInt()) ?: return null
        return "${entry.first.format(DAY_TOOLTIP)} — ${entry.second} messages"
    }

    override fun paintComponent(graphics: Graphics) {
        if (entries.isEmpty()) return
        val canvas = Ui.antialiased(graphics.create())
        try {
            canvas.font = JBFont.small()
            val metrics = canvas.fontMetrics
            val gap = JBUI.scale(2)
            val slot = width.toDouble() / entries.size
            val barWidth = (slot - gap).toInt().coerceIn(JBUI.scale(3), JBUI.scale(24))
            val maxValue = entries.maxOf { it.second }.coerceAtLeast(1)
            val peakIndex = entries.indices.maxByOrNull { entries[it].second } ?: 0
            val labelRoom = metrics.height + JBUI.scale(2)

            entries.forEachIndexed { index, (_, value) ->
                val left = (index * slot).toInt()
                if (value == 0) {
                    canvas.color = Ui.TRACK
                    canvas.fillRect(left, plotHeight - JBUI.scale(2), barWidth, JBUI.scale(2))
                    return@forEachIndexed
                }
                val barHeight = ((plotHeight - labelRoom) * value.toDouble() / maxValue).toInt()
                Ui.fillColumn(canvas, left, plotHeight, barWidth, barHeight.coerceAtLeast(JBUI.scale(2)), Ui.ACCENT)

                if (index == peakIndex) {
                    canvas.color = Ui.inkMuted
                    canvas.drawString(
                        Ui.formatCompact(value.toLong()),
                        left,
                        plotHeight - barHeight - JBUI.scale(3),
                    )
                }
            }

            canvas.color = Ui.CARD_BORDER
            canvas.drawLine(0, plotHeight, width, plotHeight)

            canvas.color = Ui.inkMuted
            val baseline = plotHeight + axisGap + metrics.ascent
            canvas.drawString(entries.first().first.format(DAY_LABEL), 0, baseline)
            val lastLabel = entries.last().first.format(DAY_LABEL)
            canvas.drawString(lastLabel, width - metrics.stringWidth(lastLabel), baseline)
        } finally {
            canvas.dispose()
        }
    }
}

/**
 * Horizontal bars: label, mark, value. One hue throughout — these are magnitude charts with a
 * single series, so colour carries no identity and every row is directly labelled instead.
 */
private class HorizontalBarChart(
    private val entries: List<Pair<String, Int>>,
    private val unit: String,
    private val tooltips: List<String> = emptyList(),
) : JComponent() {

    private val rowHeight = JBUI.scale(24)
    private val labelWidth = JBUI.scale(148)
    private val valueWidth = JBUI.scale(44)
    private val barHeight = JBUI.scale(12)

    init {
        alignmentX = LEFT_ALIGNMENT
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(360), rowHeight * entries.size)
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun getToolTipText(event: MouseEvent): String? {
        val index = event.y / rowHeight
        val entry = entries.getOrNull(index) ?: return null
        val detail = tooltips.getOrNull(index) ?: entry.first
        return "$detail — ${entry.second} $unit"
    }

    override fun paintComponent(graphics: Graphics) {
        if (entries.isEmpty()) return
        val canvas = Ui.antialiased(graphics.create())
        try {
            canvas.font = JBFont.small()
            val metrics = canvas.fontMetrics
            val maxValue = entries.maxOf { it.second }.coerceAtLeast(1)
            val barAreaWidth = (width - labelWidth - valueWidth).coerceAtLeast(JBUI.scale(30))

            entries.forEachIndexed { index, (label, value) ->
                val rowTop = index * rowHeight
                val baseline = rowTop + rowHeight / 2 + metrics.ascent / 2 - JBUI.scale(1)

                canvas.color = Ui.ink
                canvas.drawString(truncate(label, labelWidth - JBUI.scale(10), metrics), 0, baseline)

                Ui.fillBarFromLeft(
                    canvas,
                    labelWidth,
                    rowTop + (rowHeight - barHeight) / 2,
                    (barAreaWidth * value.toDouble() / maxValue).toInt().coerceAtLeast(JBUI.scale(2)),
                    barHeight,
                    Ui.ACCENT,
                )

                canvas.color = Ui.inkMuted
                canvas.drawString(
                    Ui.formatCompact(value.toLong()),
                    labelWidth + barAreaWidth + JBUI.scale(8),
                    baseline,
                )
            }
        } finally {
            canvas.dispose()
        }
    }

    private fun truncate(text: String, maxWidth: Int, metrics: FontMetrics): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        var truncated = text
        while (truncated.isNotEmpty() && metrics.stringWidth("$truncated…") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated…"
    }
}
