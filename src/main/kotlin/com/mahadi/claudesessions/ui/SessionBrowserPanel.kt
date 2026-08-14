package com.mahadi.claudesessions.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mahadi.claudesessions.ClaudeBinaryLocator
import com.mahadi.claudesessions.ClaudeEnvironment
import com.mahadi.claudesessions.ClaudeSessionScanner
import com.mahadi.claudesessions.ClaudeSessionVirtualFile
import com.mahadi.claudesessions.ClaudeTranscriptReader
import com.mahadi.claudesessions.McpRegistrationService
import com.mahadi.claudesessions.McpRuntime
import com.mahadi.claudesessions.SessionAutoTagger
import com.mahadi.claudesessions.SessionDeleter
import com.mahadi.claudesessions.SessionExporter
import com.mahadi.claudesessions.SessionFileTracker
import com.mahadi.claudesessions.SessionMetadataStore
import com.mahadi.claudesessions.SessionResumer
import com.mahadi.claudesessions.SessionSettings
import com.mahadi.claudesessions.SessionTags
import com.mahadi.claudesessions.model.ClaudeSession
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.tree.TreeCellRenderer
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

private val LOG = logger<SessionBrowserPanel>()

private const val AUTO_REFRESH_INTERVAL_MILLIS = 30_000
private const val SESSION_ROW_HEIGHT = 40
private const val GROUP_ROW_HEIGHT = 26
private const val INDENT_PER_LEVEL = 20

/**
 * Width of the strip at the *start* of a row that resumes the session when clicked.
 *
 * Leading, not trailing, and that is the whole point: a row's left edge is its indent, which the
 * renderer and the click handler both get from the same place. The right edge is derived from a
 * viewport width the tree caches, so the painted arrow and its hit target could disagree — and it
 * put the arrow underneath the overlay scrollbar besides.
 */
private fun resumeZoneWidth(): Int = JBUI.scale(24)

/**
 * Space to keep clear at the right of a row.
 *
 * The IDE draws overlay scrollbars *on top of* the viewport rather than beside it, so the
 * viewport's own width still includes the strip the scrollbar covers. A row sized to that full
 * width puts the resume arrow underneath the scrollbar.
 */
private fun scrollbarReserve(tree: JTree): Int {
    val scrollBar = UIUtil.getParentOfType(JScrollPane::class.java, tree)?.verticalScrollBar
    val barWidth = if (scrollBar != null && scrollBar.isVisible) scrollBar.width else 0
    return maxOf(barWidth, JBUI.scale(12)) + JBUI.scale(4)
}

private data class ProjectGroup(
    val name: String,
    val path: String,
    val sessions: List<ClaudeSession>,
)

private enum class SessionFilter(private val label: String) {
    ALL("All sessions"),
    TODAY("Today"),
    THIS_WEEK("This week"),
    PINNED("Pinned"),
    UNTAGGED("Untagged"),
    ;

    override fun toString(): String = label
}

/**
 * The left tool-window panel: a searchable, project-grouped tree of every Claude
 * Code session. Double-click (or Enter) opens the transcript viewer.
 */
class SessionBrowserPanel(
    private val project: Project,
) : JBPanel<SessionBrowserPanel>(BorderLayout()) {

    private val scanner = ClaudeSessionScanner()
    private val rootNode = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private val cellRenderer = SessionRowRenderer()
    private val searchField = SearchTextField()
    private val contentSearchCheckbox = JBCheckBox("Content").apply {
        isFocusable = false
        toolTipText = "Also search inside message text (slower)"
    }
    private val filterCombo = JComboBox(SessionFilter.entries.toTypedArray()).apply {
        isFocusable = false
        font = font.deriveFont(font.size2D - 1f)
        addActionListener { rebuildTree(searchField.text) }
    }
    private val environmentModel = DefaultComboBoxModel<ClaudeEnvironment>()
    private val environmentCombo = JComboBox(environmentModel).apply {
        isFocusable = false
        font = font.deriveFont(font.size2D - 1f)
        addActionListener { onEnvironmentSelected() }
    }
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        isFocusable = false
        addActionListener { reload() }
    }
    private val mcpToggle = JBCheckBox("MCP").apply {
        isFocusable = false
        font = font.deriveFont(font.size2D - 1f)
        addActionListener { toggleMcp() }
    }
    private val autoRefreshTimer = Timer(AUTO_REFRESH_INTERVAL_MILLIS) {
        if (!project.isDisposed) reload()
    }.apply { isRepeats = true }
    private var allSessions: List<ClaudeSession> = emptyList()
    private var contentMatchIds: Set<String> = emptySet()
    private var searchGeneration = 0
    private var suppressEnvironmentEvents = false

    init {
        background = UIUtil.getPanelBackground()
        add(buildToolbar(), BorderLayout.NORTH)
        configureTree()
        add(JBScrollPane(tree), BorderLayout.CENTER)
        reload()
        applyAutoRefreshSetting()
        upgradeMcpIfStale()
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && !isDisplayable) {
                autoRefreshTimer.stop()
            }
        }
    }


    private fun buildToolbar(): JComponent {
        searchField.textEditor.emptyText.text = "Search title, branch, tag, project…"
        searchField.toolTipText = "Matches the title, opening message, project, git branch and " +
            "tags (including the auto-derived ticket id). Tick Content to search message text too."
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = onSearchChanged()
        })
        contentSearchCheckbox.addActionListener { onSearchChanged() }

        val stats = JButton("Stats").apply {
            toolTipText = "Show session statistics"
            isFocusable = false
            addActionListener { StatsDialog(project, allSessions).show() }
        }

        val settings = JButton(AllIcons.General.GearPlain).apply {
            toolTipText = "Add or edit Claude environments"
            isFocusable = false
            addActionListener { openSettings() }
        }

        val actions = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(2), 0)).apply {
            isOpaque = false
            add(stats, BorderLayout.WEST)
            add(
                JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(2), 0)).apply {
                    isOpaque = false
                    add(settings, BorderLayout.WEST)
                    add(refreshButton, BorderLayout.EAST)
                },
                BorderLayout.EAST,
            )
        }

        val searchRow = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(4), 0)).apply {
            background = UIUtil.getPanelBackground()
            add(searchField, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }

        val filterOptions = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(contentSearchCheckbox, BorderLayout.WEST)
            add(mcpToggle, BorderLayout.EAST)
        }

        val filterRow = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(environmentCombo, BorderLayout.WEST)
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(filterCombo)
                },
                BorderLayout.CENTER,
            )
            add(filterOptions, BorderLayout.EAST)
        }

        refreshEnvironmentCombo()
        refreshMcpToggleState()

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 6, 4, 6)
            background = UIUtil.getPanelBackground()
            add(searchRow, BorderLayout.NORTH)
            add(filterRow, BorderLayout.SOUTH)
        }
    }

    private fun configureTree() {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.cellRenderer = cellRenderer
        tree.rowHeight = 0
        tree.border = JBUI.Borders.empty(4, 4, 4, 0)

        // Rows size themselves from the viewport width, and the tree caches those sizes. Without
        // this the resume arrow keeps the position it had at the width the rows were first laid
        // out for. Re-setting rowHeight is what invalidates that cache; expansion state survives.
        tree.addComponentListener(object : ComponentAdapter() {
            private var lastWidth = 0

            override fun componentResized(event: ComponentEvent) {
                if (tree.width == lastWidth) return
                lastWidth = tree.width
                tree.rowHeight = 1
                tree.rowHeight = 0
            }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                when (event.clickCount) {
                    1 -> handleInlineResumeClick(event)
                    2 -> selectedSession()?.let(::openTranscript)
                }
            }

            override fun mousePressed(event: MouseEvent) = maybeShowPopup(event)
            override fun mouseReleased(event: MouseEvent) = maybeShowPopup(event)
        })

        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER) selectedSession()?.let(::openTranscript)
            }
        })
    }

    private fun maybeShowPopup(event: MouseEvent) {
        if (!event.isPopupTrigger) return
        val path = tree.getPathForLocation(event.x, event.y) ?: return
        if (tree.selectionPaths?.contains(path) != true) {
            tree.selectionPath = path
        }
        val sessions = selectedSessions()
        if (sessions.isEmpty()) return
        buildContextMenu(sessions).show(tree, event.x, event.y)
    }

    private fun openSettings() {
        val previousRoot = SessionMetadataStore.sessionRoot()
        if (!SessionSettingsDialog(project).showAndGet()) return

        applyAutoRefreshSetting()
        refreshEnvironmentCombo()
        if (SessionMetadataStore.sessionRoot() != previousRoot) {
            searchField.text = ""
            contentMatchIds = emptySet()
        }
        reload()
    }

    /**
     * Rebuilds the environment dropdown from the store. Suppressed while repopulating so
     * the model's own selection events aren't mistaken for the user switching account.
     */
    private fun refreshEnvironmentCombo() {
        val environments = SessionMetadataStore.environments()
        val active = SessionMetadataStore.activeEnvironment()

        suppressEnvironmentEvents = true
        try {
            environmentModel.removeAllElements()
            environments.forEach(environmentModel::addElement)
            environmentModel.selectedItem = environments.firstOrNull { it.name == active.name }
                ?: environments.firstOrNull()
        } finally {
            suppressEnvironmentEvents = false
        }
        environmentCombo.isVisible = environments.isNotEmpty()
        updateEnvironmentTooltips(active)
    }

    private fun updateEnvironmentTooltips(environment: ClaudeEnvironment) {
        environmentCombo.toolTipText = "<html><b>${environment.name}</b><br>" +
            "Sessions: ${environment.sessionRoot}<br>" +
            "Binary: ${environment.claudeBinary ?: ClaudeBinaryLocator.resolve()}<br>" +
            "CLAUDE_CONFIG_DIR: ${environment.configDir ?: "default"}<br><br>" +
            "Switch account here; add one with the gear button.</html>"
        refreshButton.toolTipText = "Rescan ${environment.sessionRoot}"
    }

    private fun onEnvironmentSelected() {
        if (suppressEnvironmentEvents) return
        val selected = environmentCombo.selectedItem as? ClaudeEnvironment ?: return
        if (selected.name == SessionMetadataStore.activeEnvironment().name) return

        SessionMetadataStore.setActiveEnvironment(selected.name)
        searchField.text = ""
        contentMatchIds = emptySet()
        updateEnvironmentTooltips(selected)
        refreshMcpToggleState()
        reload()
    }

    private fun applyAutoRefreshSetting() {
        if (SessionSettings.isAutoRefreshEnabled()) {
            if (!autoRefreshTimer.isRunning) autoRefreshTimer.start()
        } else {
            autoRefreshTimer.stop()
        }
    }

    private fun refreshMcpToggleState() {
        mcpToggle.isEnabled = false
        mcpToggle.toolTipText = "Checking MCP registration…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val registered = McpRegistrationService.isRegistered()
            ApplicationManager.getApplication().invokeLater(
                {
                    mcpToggle.isSelected = registered
                    mcpToggle.isEnabled = true
                    mcpToggle.toolTipText = if (registered) {
                        "Claude can search these sessions. Untick to remove the MCP server."
                    } else {
                        "Tick to let Claude search these sessions via the local cache (claude mcp add)."
                    }
                },
                ModalityState.any(),
            )
        }
    }

    private fun toggleMcp() {
        if (mcpToggle.isSelected) enableMcp() else disableMcp()
    }

    /**
     * Brings an already-installed session cache up to this plugin build's version.
     *
     * Only the enable path ever installed the server, so someone who ticked MCP months ago kept
     * running that month's Python — new queries the plugin asks for would just come back empty.
     * Silent by design: nothing was broken and nothing is being asked of the user, so it reports
     * only when it fails.
     */
    private fun upgradeMcpIfStale() {
        if (!McpRuntime.isInstalled() || !McpRuntime.isStale()) return

        object : Task.Backgroundable(project, "Updating Claude session cache", false) {
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val failed = McpRuntime.install { indicator.text = it }.firstOrNull { !it.ok }
                failure = failed?.let { "${it.label}: ${it.detail}" }
            }

            override fun onFinished() {
                failure?.let { LOG.warn("Could not update the bundled session cache — $it") }
            }
        }.queue()
    }

    /**
     * One click does the whole setup: unpack the bundled server, build its virtualenv,
     * index the sessions, and register it with Claude Code. Runs under a progress
     * indicator because the first run downloads dependencies.
     */
    private fun enableMcp() {
        mcpToggle.isEnabled = false
        object : Task.Backgroundable(project, "Setting up Claude session search", true) {
            private var failure: String? = null
            private var scheduled = false

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                if (!McpRuntime.isInstalled()) {
                    val steps = McpRuntime.install { indicator.text = it }
                    val failed = steps.firstOrNull { !it.ok }
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

                indicator.text = "Scheduling daily refresh…"
                scheduled = McpRuntime.installRefreshAgent().first
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater(
                    {
                        mcpToggle.isEnabled = true
                        val message = failure
                        if (message != null) {
                            mcpToggle.isSelected = false
                            Messages.showErrorDialog(project, message, "Couldn't Enable Session Search")
                        } else {
                            refreshMcpToggleState()
                            val refreshNote = if (scheduled) {
                                "The cache re-indexes itself daily."
                            } else {
                                "Automatic daily re-indexing could not be scheduled — " +
                                    "ask Claude to run refresh_cache when you need it current."
                            }
                            Messages.showInfoMessage(
                                project,
                                "Claude can now search your past sessions.\n\n" +
                                    "Restart any running Claude Code session to pick this up.\n" +
                                    refreshNote,
                                "Session Search Enabled",
                            )
                        }
                    },
                    ModalityState.any(),
                )
            }
        }.queue()
    }

    private fun disableMcp() {
        mcpToggle.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = McpRegistrationService.unregister()
            if (result.success) McpRuntime.removeRefreshAgent()
            ApplicationManager.getApplication().invokeLater(
                {
                    mcpToggle.isEnabled = true
                    if (!result.success) {
                        mcpToggle.isSelected = true
                        Messages.showErrorDialog(
                            project,
                            result.output.ifBlank { "The claude CLI reported a failure." },
                            "Couldn't Remove MCP Server",
                        )
                        return@invokeLater
                    }
                    refreshMcpToggleState()
                },
                ModalityState.any(),
            )
        }
    }

    /**
     * The row's leading [resumeZoneWidth] strip is the play button.
     *
     * The target is measured from `bounds.x` alone — the row's own start. The previous version
     * measured back from `bounds.width`, which is the cached layout width rather than the width the
     * renderer painted with, so a resize could leave the target sitting somewhere other than under
     * the arrow.
     */
    private fun handleInlineResumeClick(event: MouseEvent) {
        val path = tree.getPathForLocation(event.x, event.y) ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val session = node.userObject as? ClaudeSession ?: return
        val bounds = tree.getPathBounds(path) ?: return

        if (event.x - bounds.x <= resumeZoneWidth()) {
            SessionResumer.resume(project, session)
        }
    }

    private fun buildContextMenu(sessions: List<ClaudeSession>): JPopupMenu {
        if (sessions.size > 1) {
            return JPopupMenu().apply {
                add(menuItem("Delete ${sessions.size} sessions…") { deleteSessions(sessions) })
            }
        }

        val session = sessions.single()
        return JPopupMenu().apply {
            add(menuItem("Open transcript") { openTranscript(session) })
            add(menuItem("Continue in Terminal") { SessionResumer.resume(project, session) })
            addSeparator()
            add(menuItem("Rename title…") { renameSession(session) })
            if (SessionMetadataStore.customTitle(session.sessionId) != null) {
                add(menuItem("Reset title") { resetTitle(session) })
            }
            add(menuItem("Edit tags…") { editTags(session) })
            add(menuItem("Suggest tags (AI)…") { suggestTags(session) })
            add(menuItem(if (SessionMetadataStore.isPinned(session.sessionId)) "Unpin session" else "Pin session") {
                togglePin(session)
            })
            add(
                menuItem(
                    if (SessionMetadataStore.isExcludedFromSync(session.sessionId)) "Unmark private" else "Mark private"
                ) { togglePrivate(session) }
            )
            addSeparator()
            add(menuItem("Export as Markdown…") { SessionExporter.export(project, session) })
            addSeparator()
            add(menuItem("Copy session id") { copyToClipboard(session.sessionId) })
            add(menuItem("Copy resume command") {
                copyToClipboard(
                    "cd \"${session.projectPath}\" && " +
                        "${ClaudeBinaryLocator.resolve()} --resume ${session.sessionId}"
                )
            })
            add(menuItem("Reveal transcript in Finder") {
                RevealFileAction.openFile(session.transcriptPath.toFile())
            })
            addSeparator()
            add(menuItem("Delete session…") { deleteSessions(listOf(session)) })
        }
    }

    private fun menuItem(text: String, action: () -> Unit): javax.swing.JMenuItem =
        javax.swing.JMenuItem(text).apply { addActionListener { action() } }

    private fun openTranscript(session: ClaudeSession) {
        FileEditorManager.getInstance(project).openFile(ClaudeSessionVirtualFile.of(session), true)
    }

    private fun renameSession(session: ClaudeSession) {
        val newTitle = Messages.showInputDialog(
            project,
            "Title for this session:",
            "Rename Session",
            null,
            session.title,
            null,
        )?.trim()
        if (newTitle.isNullOrEmpty()) return

        SessionMetadataStore.setTitle(session.sessionId, newTitle)
        allSessions = allSessions.map {
            if (it.sessionId == session.sessionId) it.copy(title = newTitle) else it
        }
        rebuildTree(searchField.text)
    }

    private fun resetTitle(session: ClaudeSession) {
        SessionMetadataStore.clearTitle(session.sessionId)
        reload()
    }

    private fun editTags(session: ClaudeSession) {
        val currentTags = SessionMetadataStore.tags(session.sessionId).joinToString(", ")
        val input = Messages.showInputDialog(
            project,
            "Comma-separated tags:",
            "Edit Tags",
            null,
            currentTags,
            null,
        ) ?: return

        SessionMetadataStore.setTags(session.sessionId, input.split(","))
        rebuildTree(searchField.text)
    }

    private fun suggestTags(session: ClaudeSession) {
        tree.setPaintBusy(true)
        SessionAutoTagger.suggestTags(session) { suggested ->
            tree.setPaintBusy(false)
            if (suggested.isEmpty()) {
                Messages.showWarningDialog(project, "Couldn't get tag suggestions for this session.", "Suggest Tags")
                return@suggestTags
            }
            val merged = (SessionMetadataStore.tags(session.sessionId) + suggested).distinct()
            SessionMetadataStore.setTags(session.sessionId, merged)
            rebuildTree(searchField.text)
        }
    }

    private fun togglePin(session: ClaudeSession) {
        SessionMetadataStore.setPinned(session.sessionId, !SessionMetadataStore.isPinned(session.sessionId))
        rebuildTree(searchField.text)
    }

    private fun togglePrivate(session: ClaudeSession) {
        SessionMetadataStore.setExcludedFromSync(
            session.sessionId,
            !SessionMetadataStore.isExcludedFromSync(session.sessionId),
        )
        rebuildTree(searchField.text)
    }

    private fun deleteSessions(sessions: List<ClaudeSession>) {
        val message = if (sessions.size == 1) {
            "Delete \"${sessions.first().title}\" and all its data? This cannot be undone."
        } else {
            "Delete ${sessions.size} sessions and all their data? This cannot be undone."
        }
        val confirmed = Messages.showYesNoDialog(
            project,
            message,
            "Delete Claude Session" + if (sessions.size > 1) "s" else "",
            "Delete",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return

        val fileEditorManager = FileEditorManager.getInstance(project)
        var failureCount = 0
        for (session in sessions) {
            fileEditorManager.closeFile(ClaudeSessionVirtualFile.of(session))
            if (!SessionDeleter.delete(session)) failureCount++
        }
        if (failureCount > 0) {
            Messages.showErrorDialog(
                project,
                "$failureCount of ${sessions.size} session(s) couldn't be fully deleted. Check idea.log for details.",
                "Delete Failed",
            )
        }

        val deletedIds = sessions.map { it.sessionId }.toSet()
        allSessions = allSessions.filterNot { it.sessionId in deletedIds }
        rebuildTree(searchField.text)
    }

    private fun selectedSession(): ClaudeSession? {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? ClaudeSession
    }

    private fun selectedSessions(): List<ClaudeSession> =
        (tree.selectionPaths ?: emptyArray())
            .mapNotNull { (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ClaudeSession }

    private fun copyToClipboard(text: String) =
        CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun reload() {
        tree.setPaintBusy(true)
        tree.emptyText.text = "Loading sessions…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val sessions = try {
                scanner.scan()
            } catch (throwable: Throwable) {
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater {
                allSessions = sessions
                tree.setPaintBusy(false)
                tree.emptyText.text = if (sessions.isEmpty()) "No Claude sessions found" else "No matches"
                rebuildTree(searchField.text)
            }
        }
    }

    private fun onSearchChanged() {
        contentMatchIds = emptySet()
        rebuildTree(searchField.text)
        triggerContentSearch(searchField.text)
    }

    private fun quickMatches(session: ClaudeSession, filter: String): Boolean =
        session.title.lowercase().contains(filter) ||
            session.firstPrompt.lowercase().contains(filter) ||
            session.projectName.lowercase().contains(filter) ||
            session.projectPath.lowercase().contains(filter) ||
            session.gitBranch?.lowercase()?.contains(filter) == true ||
            SessionTags.all(session).any { it.lowercase().contains(filter) }

    private fun matchesQuickFilters(session: ClaudeSession): Boolean {
        val ageMillis = System.currentTimeMillis() - session.lastModifiedMillis
        return when (filterCombo.selectedItem as? SessionFilter ?: SessionFilter.ALL) {
            SessionFilter.ALL -> true
            SessionFilter.TODAY -> ageMillis <= 86_400_000L
            SessionFilter.THIS_WEEK -> ageMillis <= 604_800_000L
            SessionFilter.PINNED -> SessionMetadataStore.isPinned(session.sessionId)
            SessionFilter.UNTAGGED -> SessionMetadataStore.tags(session.sessionId).isEmpty()
        }
    }

    private fun triggerContentSearch(rawFilter: String?) {
        val filter = rawFilter?.trim().orEmpty()
        if (!contentSearchCheckbox.isSelected || filter.length < 2) return

        val generation = ++searchGeneration
        val sessionsSnapshot = allSessions
        tree.setPaintBusy(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val matches = mutableSetOf<String>()
            for (session in sessionsSnapshot) {
                if (generation != searchGeneration) break
                val entries = try {
                    ClaudeTranscriptReader().read(session.transcriptPath)
                } catch (throwable: Throwable) {
                    emptyList()
                }
                if (entries.any { it.text.contains(filter, ignoreCase = true) }) {
                    matches.add(session.sessionId)
                }
            }
            ApplicationManager.getApplication().invokeLater {
                if (generation != searchGeneration) return@invokeLater
                tree.setPaintBusy(false)
                contentMatchIds = matches
                rebuildTree(searchField.text)
            }
        }
    }

    private fun rebuildTree(rawFilter: String?) {
        val filter = rawFilter?.trim()?.lowercase().orEmpty()
        val filtered = allSessions.filter { session ->
            matchesQuickFilters(session) &&
                (filter.isEmpty() || quickMatches(session, filter) || session.sessionId in contentMatchIds)
        }

        val currentBase = project.basePath
        val groups = filtered
            .groupBy { it.projectPath }
            .map { (path, sessions) ->
                ProjectGroup(
                    name = sessions.first().projectName,
                    path = path,
                    sessions = sessions.sortedWith(
                        compareByDescending<ClaudeSession> { SessionMetadataStore.isPinned(it.sessionId) }
                            .thenByDescending { it.lastModifiedMillis },
                    ),
                )
            }
            .sortedWith(
                compareByDescending<ProjectGroup> { it.path == currentBase }
                    .thenByDescending { group -> group.sessions.maxOf { it.lastModifiedMillis } },
            )

        rootNode.removeAllChildren()
        for (group in groups) {
            val groupNode = DefaultMutableTreeNode(group)
            for (session in group.sessions) groupNode.add(DefaultMutableTreeNode(session))
            rootNode.add(groupNode)
        }
        treeModel.reload()

        if (filter.isNotEmpty()) {
            expandAll()
        } else if (rootNode.childCount > 0) {
            tree.expandPath(TreePath(arrayOf<Any>(rootNode, rootNode.getChildAt(0))))
        }
    }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

}

/**
 * Paints a session as two lines — title above, metadata and tags below — with the resume
 * affordance parked in a fixed-width zone at the row's right edge.
 *
 * Hand-painted rather than assembled from labels because a tree renderer is asked for its
 * appearance hundreds of times while scrolling; and painted rather than text-fragment based
 * because fragments are a single line, which is what made the old row a run-on sentence.
 *
 * The row width is derived from the viewport and deliberately left a few pixels short: the
 * tree would otherwise gain a horizontal scrollbar. [resumeZoneWidth] is the one number the
 * painter and [SessionBrowserPanel.handleInlineResumeClick] must agree on, and both read the
 * same row width, so the arrow cannot drift away from its own hit target.
 */
private class SessionRowRenderer : JComponent(), TreeCellRenderer {

    private var group: ProjectGroup? = null
    private var session: ClaudeSession? = null
    private var tags: List<String> = emptyList()
    private var pinned = false
    private var isSelected = false
    private var hasTreeFocus = false

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): JComponent {
        isSelected = selected
        hasTreeFocus = tree.hasFocus()
        group = null
        session = null
        tags = emptyList()
        pinned = false

        when (val userObject = (value as? DefaultMutableTreeNode)?.userObject) {
            is ProjectGroup -> {
                group = userObject
                toolTipText = userObject.path
            }

            is ClaudeSession -> {
                session = userObject
                tags = SessionTags.all(userObject)
                pinned = SessionMetadataStore.isPinned(userObject.sessionId)
                toolTipText = sessionTooltip(userObject)
            }
        }

        val depth = if (session != null) 2 else 1
        val available = tree.visibleRect.width.takeIf { it > 0 } ?: tree.width
        val width = (available - JBUI.scale(INDENT_PER_LEVEL * depth) - scrollbarReserve(tree))
            .coerceAtLeast(JBUI.scale(200))
        preferredSize = Dimension(width, JBUI.scale(if (session != null) SESSION_ROW_HEIGHT else GROUP_ROW_HEIGHT))
        return this
    }

    override fun paintComponent(graphics: Graphics) {
        val canvas = Ui.antialiased(graphics.create())
        try {
            if (isSelected) {
                canvas.color = UIUtil.getTreeSelectionBackground(hasTreeFocus)
                canvas.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
            }
            group?.let { paintGroup(canvas, it) }
            session?.let { paintSession(canvas, it) }
        } finally {
            canvas.dispose()
        }
    }

    private fun paintGroup(canvas: Graphics2D, group: ProjectGroup) {
        canvas.font = JBFont.label().asBold()
        val metrics = canvas.fontMetrics
        val baseline = (height - metrics.height) / 2 + metrics.ascent

        canvas.color = primaryInk()
        canvas.drawString(group.name, 0, baseline)

        val countLeft = metrics.stringWidth(group.name) + JBUI.scale(8)
        canvas.font = JBFont.small()
        canvas.color = secondaryInk()
        canvas.drawString(
            "${group.sessions.size}",
            countLeft,
            (height - canvas.fontMetrics.height) / 2 + canvas.fontMetrics.ascent,
        )
    }

    private fun paintSession(canvas: Graphics2D, session: ClaudeSession) {
        val titleFont = JBFont.label()
        val metaFont = JBFont.small()
        val titleMetrics = canvas.getFontMetrics(titleFont)
        val metaMetrics = canvas.getFontMetrics(metaFont)

        val topPadding = JBUI.scale(6)
        val titleBaseline = topPadding + titleMetrics.ascent
        val metaBaseline = titleBaseline + JBUI.scale(4) + metaMetrics.ascent
        val contentLeft = resumeZoneWidth()

        paintResumeButton(canvas)

        var titleLeft = contentLeft
        if (pinned) {
            val dot = JBUI.scale(6)
            canvas.color = if (isSelected) primaryInk() else Ui.ACCENT
            canvas.fillOval(titleLeft, titleBaseline - titleMetrics.ascent / 2 - dot / 2, dot, dot)
            titleLeft += dot + JBUI.scale(6)
        }

        canvas.font = titleFont
        canvas.color = primaryInk()
        val titleRoom = width - titleLeft - JBUI.scale(4)
        canvas.drawString(truncate(session.title, titleRoom, titleMetrics), titleLeft, titleBaseline)

        canvas.font = metaFont
        canvas.color = secondaryInk()
        val meta = buildMeta(session)
        canvas.drawString(meta, contentLeft, metaBaseline)

        paintChips(canvas, contentLeft + metaMetrics.stringWidth(meta) + JBUI.scale(6), metaBaseline, metaMetrics)
    }

    /**
     * Escaped: a prompt is arbitrary text, and Swing renders a tooltip starting with `<html>`.
     * The branch is repeated in full here because its chip may have been shortened to fit.
     */
    private fun sessionTooltip(session: ClaudeSession): String {
        val preview = session.firstPrompt.take(200).ifBlank { session.title }
        return buildString {
            append("<html>").append(StringUtil.escapeXmlEntities(preview))
            session.gitBranch?.takeIf { it.isNotBlank() }?.let {
                append("<br><br>Branch: ").append(StringUtil.escapeXmlEntities(it))
            }
            append("<br><br><i>Double-click to read · click the play button to continue</i></html>")
        }
    }

    private fun buildMeta(session: ClaudeSession): String = buildString {
        append(Ui.relativeTime(session.lastModifiedMillis))
        session.model?.let { append("  ·  ").append(it) }
        append("  ·  ").append(session.messageCount).append(" msg")
    }

    /**
     * The branch comes first and is never dropped — it says what the session was working on, so it
     * outranks tags for whatever room is left. Tags fall off the end when they stop fitting; the
     * branch shortens instead, losing its path prefix before any of its name.
     */
    private fun paintChips(canvas: Graphics2D, startLeft: Int, baseline: Int, metrics: FontMetrics) {
        val limit = width - JBUI.scale(4)
        var left = startLeft

        branchName()?.let { branch ->
            val label = fittingBranch(branch, limit - left - chipPadding(), metrics)
            if (label.isNotEmpty()) left = drawChip(canvas, label, left, baseline, metrics)
        }

        for (tag in tags) {
            val label = "#$tag"
            if (left + chipWidth(label, metrics) > limit) break
            left = drawChip(canvas, label, left, baseline, metrics)
        }
    }

    /**
     * `HEAD` is kept rather than hidden: a session run mid-merge genuinely had no branch checked
     * out, and printing nothing there looks like a missing value instead of a detached head.
     */
    private fun branchName(): String? = session?.gitBranch?.takeIf { it.isNotBlank() }

    private fun fittingBranch(branch: String, room: Int, metrics: FontMetrics): String {
        if (metrics.stringWidth(branch) <= room) return branch
        val leaf = branch.substringAfterLast('/')
        if (metrics.stringWidth(leaf) <= room) return leaf
        return truncate(leaf, room, metrics)
    }

    private fun chipPadding(): Int = JBUI.scale(12)

    private fun chipWidth(label: String, metrics: FontMetrics): Int =
        metrics.stringWidth(label) + chipPadding()

    private fun drawChip(
        canvas: Graphics2D,
        label: String,
        left: Int,
        baseline: Int,
        metrics: FontMetrics,
    ): Int {
        val chipWidth = chipWidth(label, metrics)
        val chipHeight = metrics.height + JBUI.scale(2)
        canvas.color = if (isSelected) selectedChipSurface() else Ui.CHIP_SURFACE
        canvas.fillRoundRect(
            left,
            baseline - metrics.ascent - JBUI.scale(1),
            chipWidth,
            chipHeight,
            chipHeight,
            chipHeight,
        )
        canvas.color = secondaryInk()
        canvas.drawString(label, left + JBUI.scale(6), baseline)
        return left + chipWidth + JBUI.scale(4)
    }

    /**
     * A play button rather than a bare glyph: a 9px triangle on its own gave no sense of being
     * clickable, and its target was easy to miss. The disc fills the leading zone and is centred
     * across both text lines.
     */
    private fun paintResumeButton(canvas: Graphics2D) {
        val diameter = JBUI.scale(18)
        val left = JBUI.scale(1)
        val top = (height - diameter) / 2

        canvas.color = if (isSelected) selectedChipSurface() else Ui.CHIP_SURFACE
        canvas.fillOval(left, top, diameter, diameter)

        val size = JBUI.scale(8)
        val glyphLeft = left + (diameter - size) / 2 + JBUI.scale(1)
        val glyphTop = top + (diameter - size) / 2
        canvas.color = if (isSelected) primaryInk() else Ui.GOOD
        canvas.fillPolygon(
            intArrayOf(glyphLeft, glyphLeft + size, glyphLeft),
            intArrayOf(glyphTop, glyphTop + size / 2, glyphTop + size),
            3,
        )
    }

    private fun truncate(text: String, maxWidth: Int, metrics: FontMetrics): String {
        if (maxWidth <= 0) return ""
        if (metrics.stringWidth(text) <= maxWidth) return text
        var truncated = text
        while (truncated.isNotEmpty() && metrics.stringWidth("$truncated…") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated…"
    }

    private fun primaryInk(): Color =
        if (isSelected) UIUtil.getTreeSelectionForeground(hasTreeFocus) else Ui.ink

    private fun secondaryInk(): Color =
        if (isSelected) ColorUtil.withAlpha(UIUtil.getTreeSelectionForeground(hasTreeFocus), 0.75) else Ui.inkMuted

    private fun selectedChipSurface(): Color =
        ColorUtil.withAlpha(UIUtil.getTreeSelectionForeground(hasTreeFocus), 0.16)
}
