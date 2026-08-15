package com.mahadi.claudesessions.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mahadi.claudesessions.CacheStatsService
import com.mahadi.claudesessions.ClaudeEnvironment
import com.mahadi.claudesessions.McpRuntime
import com.mahadi.claudesessions.SessionMetadataStore
import com.mahadi.claudesessions.SessionSettings
import com.mahadi.claudesessions.TeamSyncConfig
import com.mahadi.claudesessions.TeamSyncStatusService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent

private class EditableEnvironment(
    var name: String,
    var sessionRoot: String,
    var claudeBinary: String,
    var configDir: String,
) {
    override fun toString(): String = name
}

private const val SCOPE_MINE_LABEL = "My sessions only"
private const val SCOPE_TEAM_LABEL = "The whole team's sessions"

/** CSS px for wrapping hint text; see [SessionSettingsDialog.wrapped] for why it is not scaled. */
private const val HINT_WRAP_WIDTH = 440

/**
 * The plugin's settings: Claude environments, team knowledge-base sync, and general
 * preferences — one tab each, every tab built from titled cards rather than a flat run of
 * fields, so a long form still reads as a few decisions instead of twenty inputs.
 */
class SessionSettingsDialog(private val project: Project) : DialogWrapper(project) {

    private val listModel = CollectionListModel<EditableEnvironment>()
    private val environmentList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "No environments"
    }

    private val nameField = JBTextField()

    private val sessionRootField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Claude Session Directory")
                .withDescription("Directory holding the per-project transcript folders"),
        )
    }

    private val configDirField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Claude Config Directory")
                .withDescription("The account's CLAUDE_CONFIG_DIR, e.g. ~/.claude-work"),
        )
    }

    private val claudeBinaryField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile()
                .withTitle("Claude Executable")
                .withDescription("The claude CLI used to resume, tag and register MCP"),
        )
    }

    private val syncEnabledCheckbox = JBCheckBox("Share my sessions with the team")
    private val pauseCheckbox = JBCheckBox("Pause sharing (keep receiving the team's sessions)")
    private val notifyCheckbox = JBCheckBox("Notify me when a scheduled sync fails")
    private val repoUrlField = JBTextField()
    private val repoPathField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Knowledge-Base Repository")
                .withDescription("Local clone of the team's private knowledge-base repo"),
        )
    }
    private val ownerField = JBTextField()
    private val scopeCombo = JComboBox(arrayOf(SCOPE_MINE_LABEL, SCOPE_TEAM_LABEL))
    private val projectList = CheckBoxList<String>().apply {
        setCheckBoxListListener { _, _ -> updateProjectsSummary() }
    }
    /**
     * A read-only field with a dropdown affordance rather than a button: the macOS
     * look-and-feel centres and upper-cases button text, which turned the selected
     * project into a shouted "TOURBOOKER" with the arrow stranded at the far left.
     */
    private val projectsField = ExtendableTextField().apply {
        isEditable = false
        addExtension(
            ExtendableTextComponent.Extension.create(
                AllIcons.General.ArrowDown,
                "Choose which projects to share",
            ) { showProjectsPopup() }
        )
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (isEnabled) showProjectsPopup()
            }
        })
    }
    private val minMessagesSpinner = JSpinner(SpinnerNumberModel(TeamSyncConfig.DEFAULT_MIN_MESSAGES, 0, 999, 1))
    private val maxAgeSpinner = JSpinner(SpinnerNumberModel(0, 0, 3650, 30))
    private val redactionArea = JBTextArea(4, 20).apply {
        lineWrap = false
        font = JBFont.small()
    }
    private val syncHoursField = JBTextField()
    private val statusBanner = StatusBanner()

    private val autoRefreshCheckbox = JBCheckBox(
        "Auto-refresh the session list every ${SessionSettings.autoRefreshSeconds()}s",
        SessionSettings.isAutoRefreshEnabled(),
    )

    private var editing: EditableEnvironment? = null
    private var activeEnvironment: EditableEnvironment? = null
    private var loading = false

    init {
        title = "Claude Session Browser Settings"
        setOKButtonText("Apply")
        loadEnvironments()
        loadTeamSync()
        wireListeners()
        init()
        prefillOwnerFromGit()
        loadKnownProjectsAsync()
    }

    private fun loadEnvironments() {
        val activeName = SessionMetadataStore.activeEnvironment().name
        SessionMetadataStore.environments().forEach { environment ->
            val editable = EditableEnvironment(
                name = environment.name,
                sessionRoot = environment.sessionRoot,
                claudeBinary = environment.claudeBinary.orEmpty(),
                configDir = environment.configDir ?: deriveConfigDir(environment.sessionRoot),
            )
            listModel.add(editable)
            if (editable.name == activeName) activeEnvironment = editable
        }
        val selected = activeEnvironment ?: listModel.items.firstOrNull()
        editing = selected
        if (selected != null) environmentList.setSelectedValue(selected, true)
    }

    private fun loadTeamSync() {
        val config = SessionMetadataStore.teamSync()
        syncEnabledCheckbox.isSelected = config.enabled
        pauseCheckbox.isSelected = config.paused
        notifyCheckbox.isSelected = config.notifyOnFailure
        repoUrlField.text = config.repoUrl.orEmpty()
        repoPathField.text = config.repoPath.orEmpty()
        ownerField.text = config.owner.orEmpty()
        scopeCombo.selectedItem =
            if (config.defaultScope == TeamSyncConfig.SCOPE_TEAM) SCOPE_TEAM_LABEL else SCOPE_MINE_LABEL
        minMessagesSpinner.value = config.minMessages
        maxAgeSpinner.value = config.maxAgeDays
        redactionArea.text = config.extraRedactionPatterns.joinToString("\n")
        syncHoursField.text = config.syncHours.joinToString(", ")

        config.projects.forEach { projectList.addItem(it, it, true) }
        updateProjectsSummary()
        refreshStatusBanner()
        updateSyncFieldsEnabled()
    }

    /**
     * Offers the projects the cache already knows about, ticked where they are configured.
     * Anything configured but unknown stays in the list — a cache that has not been built
     * yet must never silently drop a project the user chose.
     */
    private fun loadKnownProjectsAsync() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val known = CacheStatsService.load()?.perProject.orEmpty()
            if (known.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater(
                {
                    val alreadyListed = (0 until projectList.itemsCount).mapNotNull { projectList.getItemAt(it) }
                    known
                        .filter { it.label !in alreadyListed }
                        .sortedByDescending { it.count }
                        .forEach { projectList.addItem(it.label, "${it.label}  (${it.count} sessions)", false) }
                    updateProjectsSummary()
                },
                ModalityState.any(),
            )
        }
    }

    /**
     * The project list lives in a dropdown rather than inline: it is as long as the
     * user's project history, and a scrolling box wired into the form pushed everything
     * below it off the visible area.
     */
    private fun showProjectsPopup() {
        if (projectList.itemsCount == 0) return
        val scrollPane = JBScrollPane(projectList).apply {
            preferredSize = Dimension(
                maxOf(projectsField.width, JBUI.scale(320)),
                JBUI.scale(240),
            )
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, projectList)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()
            .showUnderneathOf(projectsField)
    }

    private fun updateProjectsSummary() {
        val selected = selectedProjects()
        projectsField.text = when {
            projectList.itemsCount == 0 -> "No projects indexed yet"
            selected.isEmpty() -> "None selected — nothing will be shared"
            selected.size == 1 -> selected.single()
            selected.size <= 3 -> selected.joinToString(", ")
            else -> "${selected.take(2).joinToString(", ")} +${selected.size - 2} more"
        }
        projectsField.toolTipText = if (selected.isEmpty()) {
            "No projects ticked — team sync would publish nothing."
        } else {
            "<html>Sharing:<br>${selected.joinToString("<br>")}</html>"
        }
    }

    private fun refreshStatusBanner() {
        val config = SessionMetadataStore.teamSync()
        if (!config.enabled) {
            statusBanner.show(Ui.inkMuted, "Not sharing", "Turn this on to build a shared team knowledge base.")
            return
        }
        val status = TeamSyncStatusService.load()
        val nextRun = TeamSyncStatusService.untilNextRun(config.syncHours)
        when {
            config.paused -> statusBanner.show(
                Ui.ATTENTION,
                "Sharing paused",
                "Teammates' sessions still arrive; yours are not published.",
            )

            status == null -> statusBanner.show(
                Ui.ACCENT,
                "Ready",
                "Scheduled" + (nextRun?.let { ", first run $it" } ?: "") + ".",
            )

            !status.ok -> statusBanner.show(
                Ui.BAD,
                "Last sync failed",
                "Failed at '${status.failedStep}'. See Stats → Health.",
            )

            else -> statusBanner.show(
                Ui.GOOD,
                "Sharing " + config.projects.size + " project" + if (config.projects.size == 1) "" else "s",
                buildString {
                    status.finishedAt?.let { append("Synced ").append(TeamSyncStatusService.relativeTime(it)) }
                    nextRun?.let {
                        if (isNotEmpty()) append(" · ")
                        append("next ").append(it)
                    }
                },
            )
        }
    }

    private fun updateSyncFieldsEnabled() {
        val enabled = syncEnabledCheckbox.isSelected
        listOf<JComponent>(
            repoUrlField, repoPathField, ownerField, scopeCombo, projectsField,
            minMessagesSpinner, maxAgeSpinner, redactionArea, syncHoursField,
            pauseCheckbox, notifyCheckbox,
        ).forEach { it.isEnabled = enabled }
    }

    /** Prefills the owner id from `git config user.email`, without ever overwriting a typed one. */
    private fun prefillOwnerFromGit() {
        if (ownerField.text.isNotBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val email = gitUserEmail() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater(
                { if (ownerField.text.isBlank()) ownerField.text = email },
                ModalityState.any(),
            )
        }
    }

    private fun gitUserEmail(): String? = try {
        val process = ProcessBuilder("git", "config", "--get", "user.email")
            .redirectErrorStream(true)
            .start()
        if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
            process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
        } else {
            process.destroyForcibly()
            null
        }
    } catch (throwable: Throwable) {
        null
    }

    private fun wireListeners() {
        environmentList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) onSelectionChanged()
        }
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (loading) return
                editing?.name = nameField.text
                environmentList.repaint()
            }
        })
        syncEnabledCheckbox.addActionListener { updateSyncFieldsEnabled() }
        loadEditing()
    }

    override fun createCenterPanel(): JComponent = JBTabbedPane().apply {
        preferredSize = Dimension(JBUI.scale(660), JBUI.scale(580))
        addTab("Environments", scrollable(environmentsTab()))
        addTab("Team Sync", scrollable(teamSyncTab()))
        addTab("General", scrollable(generalTab()))
    }

    /**
     * Never scrolls sideways: a form has no business having a horizontal scrollbar, and
     * one appearing was what pushed the cards under the vertical bar and clipped their
     * right edge. Content must fit the width; only the height may overflow.
     */
    private fun scrollable(content: JComponent): JComponent = JBScrollPane(content).apply {
        border = JBUI.Borders.empty()
        verticalScrollBar.unitIncrement = JBUI.scale(16)
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    private fun environmentsTab(): JComponent {
        val listPanel = ToolbarDecorator.createDecorator(environmentList)
            .setAddAction { addEnvironment() }
            .setRemoveAction { removeEnvironment() }
            .setRemoveActionUpdater { listModel.size > 1 }
            .disableUpDownActions()
            .createPanel()
            .apply {
                alignmentX = Component.LEFT_ALIGNMENT
                // Narrow preferred width, unbounded maximum: BoxLayout stretches it to the
                // viewport, and a wide preferred width would drag the whole dialog with it.
                preferredSize = Dimension(JBUI.scale(320), JBUI.scale(120))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(120))
            }

        return tabPanel {
            add(
                section(
                    "Accounts",
                    "One entry per Claude account. Switch between them from the dropdown in the panel.",
                ) {
                    add(listPanel)
                }
            )
            add(divider())
            add(
                section("Selected account", null) {
                    add(field("Name", nameField, "Shown in the toolbar dropdown, e.g. claude or claude-work."))
                    add(
                        field(
                            "Session directory",
                            sessionRootField,
                            "Where this account's transcripts are read from.",
                        )
                    )
                    add(
                        field(
                            "Claude config directory",
                            configDirField,
                            "Passed as CLAUDE_CONFIG_DIR when resuming or tagging — this is what makes " +
                                "a second account work off one binary. Blank for the default account.",
                        )
                    )
                    add(
                        field(
                            "Claude executable",
                            claudeBinaryField,
                            "Only needed if this account has its own install. Leave blank to auto-detect.",
                        )
                    )
                }
            )
        }
    }

    private fun teamSyncTab(): JComponent = tabPanel {
        add(statusBanner)
        add(
            section(
                "Team knowledge base",
                "Shares redacted session history through a private git repository and pulls the " +
                    "team's back, so Claude can answer \"what did anyone decide about X\".",
            ) {
                add(checkboxRow(syncEnabledCheckbox))
                add(checkboxRow(pauseCheckbox))
            }
        )
        add(divider())
        add(
            section("Repository", null) {
                add(
                    field(
                        "Repository URL",
                        repoUrlField,
                        "The private repo's git URL. Used to clone when the local path does not exist yet.",
                    )
                )
                add(
                    field(
                        "Local path",
                        repoPathField,
                        "Where the repo lives (or should be cloned to) on this machine.",
                    )
                )
                add(
                    field(
                        "Your id",
                        ownerField,
                        "How teammates see your sessions — your work email is the convention. " +
                            "Prefilled from git config user.email.",
                    )
                )
            }
        )
        add(divider())
        add(
            section(
                "What gets shared",
                "Only ticked projects leave this machine. Anything you untick in the session list " +
                    "stays private — and is retracted from teammates if it already synced.",
            ) {
                add(
                    field(
                        "Projects",
                        projectsField,
                        "Open the list and tick the projects to share. Drawn from your indexed history.",
                    )
                )
                add(
                    numberField(
                        "Skip sessions under",
                        minMessagesSpinner,
                        "messages",
                        "Throwaway sessions carry no decision and only dilute team search. 0 shares everything.",
                    )
                )
                add(
                    numberField(
                        "Skip sessions older than",
                        maxAgeSpinner,
                        "days",
                        "0 means no age limit. Tightening this retracts anything already shared that " +
                            "no longer qualifies.",
                    )
                )
            }
        )
        add(divider())
        add(
            section("Claude's search", null) {
                add(
                    field(
                        "Search by default",
                        scopeCombo,
                        "What Claude searches when it doesn't name a scope. It can always be asked " +
                            "for the team explicitly.",
                    )
                )
            }
        )
        add(divider())
        add(
            section(
                "Safety",
                "Secrets are already redacted by shape before anything is written. These add your own rules.",
            ) {
                add(
                    field(
                        "Extra redaction patterns",
                        JBScrollPane(redactionArea).apply {
                            preferredSize = Dimension(JBUI.scale(320), JBUI.scale(76))
                            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(76))
                        },
                        "One regular expression per line — internal hostnames, customer names. " +
                            "Matches are replaced before export; changing these re-exports affected sessions.",
                    )
                )
                add(checkboxRow(notifyCheckbox))
            }
        )
        add(divider())
        add(
            section("Schedule", null) {
                add(
                    field(
                        "Sync at (hours)",
                        syncHoursField,
                        "Comma-separated hours, 0-23. A missed run catches up at the next login.",
                    )
                )
            }
        )
    }

    private fun generalTab(): JComponent = tabPanel {
        add(
            section("Session list", "Preferences for the panel itself.") {
                add(checkboxRow(autoRefreshCheckbox))
            }
        )
    }

    private fun tabPanel(content: JBPanel<JBPanel<*>>.() -> Unit): JComponent =
        JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            // Wider inset on the right so cards clear the vertical scrollbar instead of
            // running underneath it.
            border = JBUI.Borders.empty(10, 12, 10, 18)
            content()
        }

    /** A hairline with air either side, separating one card of settings from the next. */
    private fun divider(): JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(6, 4)
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(13))
        add(Hairline(), BorderLayout.CENTER)
    }

    /** A titled card: the unit the form is read in, rather than one long ladder of labels. */
    private fun section(
        title: String,
        description: String?,
        content: JBPanel<JBPanel<*>>.() -> Unit,
    ): JComponent {
        val body = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(
                JBLabel(title).apply {
                    font = JBFont.label().asBold()
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            )
            if (description != null) {
                add(
                    JBLabel(wrapped(description)).apply {
                        foreground = Ui.inkMuted
                        font = JBFont.small()
                        alignmentX = Component.LEFT_ALIGNMENT
                        border = JBUI.Borders.emptyTop(2)
                    }
                )
            }
            content()
        }
        return Card().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(10, 12)
            add(body, BorderLayout.CENTER)
        }.also { card ->
            card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        }
    }

    private fun checkboxRow(checkbox: JBCheckBox): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(8)
            maximumSize = Dimension(Int.MAX_VALUE, checkbox.preferredSize.height + JBUI.scale(8))
            add(checkbox, BorderLayout.WEST)
        }

    /** A number input that reads as a sentence: "Skip sessions under [3] messages". */
    private fun numberField(label: String, spinner: JSpinner, unit: String, hint: String): JComponent =
        JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(10)
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
                    add(JBLabel(label).apply { font = JBFont.label().asBold() })
                    add(spinner.apply { preferredSize = Dimension(JBUI.scale(72), JBUI.scale(26)) })
                    add(JBLabel(unit).apply { foreground = Ui.inkMuted })
                }
            )
            add(
                JBLabel(wrapped(hint)).apply {
                    foreground = Ui.inkMuted
                    font = JBFont.small()
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyTop(2)
                }
            )
        }

    private fun field(label: String, editor: JComponent, hint: String): JComponent =
        JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(10)
            add(JBLabel(label).apply {
                font = JBFont.label().asBold()
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(
                editor.apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    if (this !is JBScrollPane) {
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    }
                }
            )
            add(JBLabel(wrapped(hint)).apply {
                foreground = Ui.inkMuted
                font = JBFont.small()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(2)
            })
        }

    /**
     * Long hints must wrap instead of stretching the dialog to their natural width.
     *
     * The width is a literal, deliberately **not** [JBUI.scale]d: the number is CSS px
     * inside Swing's HTML renderer, which does not share JBUI's scaling, so scaling it
     * made every hint twice as wide as intended and forced the whole form to overflow.
     */
    private fun wrapped(text: String): String =
        "<html><body style='width: ${HINT_WRAP_WIDTH}px'>$text</body></html>"

    private fun onSelectionChanged() {
        flushEditing()
        editing = environmentList.selectedValue
        loadEditing()
    }

    private fun loadEditing() {
        loading = true
        try {
            val environment = editing
            nameField.text = environment?.name.orEmpty()
            sessionRootField.text = environment?.sessionRoot.orEmpty()
            configDirField.text = environment?.configDir.orEmpty()
            claudeBinaryField.text = environment?.claudeBinary.orEmpty()
            setFieldsEnabled(environment != null)
        } finally {
            loading = false
        }
    }

    private fun flushEditing() {
        val environment = editing ?: return
        environment.name = nameField.text
        environment.sessionRoot = sessionRootField.text
        environment.configDir = configDirField.text
        environment.claudeBinary = claudeBinaryField.text
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        nameField.isEnabled = enabled
        sessionRootField.isEnabled = enabled
        configDirField.isEnabled = enabled
        claudeBinaryField.isEnabled = enabled
    }

    /**
     * Claude Code keeps transcripts in `<configDir>/projects`, so an existing session path
     * already tells us the config directory — prefilled rather than inferred silently.
     */
    private fun deriveConfigDir(sessionRoot: String): String {
        val root = File(sessionRoot.trim())
        return if (root.name == "projects") root.parent.orEmpty() else ""
    }

    private fun addEnvironment() {
        flushEditing()
        val created = EditableEnvironment(
            name = uniqueName(),
            sessionRoot = "",
            claudeBinary = "",
            configDir = "",
        )
        listModel.add(created)
        environmentList.setSelectedValue(created, true)
        nameField.requestFocusInWindow()
        nameField.selectAll()
    }

    private fun uniqueName(): String {
        val taken = listModel.items.map { it.name.trim().lowercase() }.toSet()
        if ("claude-work" !in taken) return "claude-work"
        var suffix = 2
        while ("claude-$suffix" in taken) suffix++
        return "claude-$suffix"
    }

    private fun removeEnvironment() {
        if (listModel.size <= 1) return
        val index = environmentList.selectedIndex
        if (index < 0) return

        val removed = listModel.getElementAt(index)
        if (activeEnvironment === removed) activeEnvironment = null
        editing = null
        listModel.remove(index)
        environmentList.selectedIndex = index.coerceAtMost(listModel.size - 1)
    }

    override fun doValidate(): ValidationInfo? {
        flushEditing()

        val seen = mutableSetOf<String>()
        for (environment in listModel.items) {
            val name = environment.name.trim()
            if (name.isEmpty()) {
                return ValidationInfo("Give every environment a name.", nameField)
            }
            if (!seen.add(name.lowercase())) {
                return ValidationInfo("Two environments are both named '$name'.", nameField)
            }

            val root = environment.sessionRoot.trim()
            if (root.isEmpty()) {
                return ValidationInfo("Pick a session directory for '$name'.", sessionRootField)
            }
            if (!File(root).isDirectory) {
                return ValidationInfo("'$name': $root is not a directory.", sessionRootField)
            }

            val configDir = environment.configDir.trim()
            if (configDir.isNotEmpty() && !File(configDir).isDirectory) {
                return ValidationInfo("'$name': $configDir is not a directory.", configDirField)
            }

            val binary = environment.claudeBinary.trim()
            if (binary.isNotEmpty() && !File(binary).canExecute()) {
                return ValidationInfo(
                    "'$name': $binary is not an executable file. A shell alias or function " +
                        "cannot be used here — leave this blank and set the config directory instead.",
                    claudeBinaryField,
                )
            }
        }
        return validateTeamSync()
    }

    private fun validateTeamSync(): ValidationInfo? {
        if (!syncEnabledCheckbox.isSelected) return null

        if (!McpRuntime.isInstalled()) {
            return ValidationInfo(
                "Enable Claude session search (the MCP toggle in the panel toolbar) first — " +
                    "team sync runs on the same engine.",
                syncEnabledCheckbox,
            )
        }
        val repoPath = repoPathField.text.trim()
        if (repoPath.isEmpty()) {
            return ValidationInfo("Pick where the knowledge-base repo lives locally.", repoPathField)
        }
        if (!File(expandHome(repoPath)).isDirectory && repoUrlField.text.trim().isEmpty()) {
            return ValidationInfo(
                "$repoPath does not exist. Point at an existing clone, or fill in the " +
                    "repository URL so it can be cloned there.",
                repoPathField,
            )
        }
        if (ownerField.text.trim().isEmpty()) {
            return ValidationInfo("Set your id — teammates' caches label your sessions with it.", ownerField)
        }
        if (parsedSyncHours() == null) {
            return ValidationInfo(
                "Sync hours must be comma-separated numbers between 0 and 23, e.g. 9, 18.",
                syncHoursField,
            )
        }
        invalidRedactionPattern()?.let {
            return ValidationInfo("'$it' is not a valid regular expression.", redactionArea)
        }
        return null
    }

    private fun invalidRedactionPattern(): String? = redactionPatterns().firstOrNull { pattern ->
        runCatching { Regex(pattern) }.isFailure
    }

    private fun redactionPatterns(): List<String> =
        redactionArea.text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun selectedProjects(): List<String> =
        (0 until projectList.itemsCount)
            .mapNotNull { index -> projectList.getItemAt(index)?.takeIf { projectList.isItemSelected(index) } }

    private fun parsedSyncHours(): List<Int>? {
        val text = syncHoursField.text.trim()
        if (text.isEmpty()) return TeamSyncConfig.DEFAULT_SYNC_HOURS
        val hours = text.split(",").map { it.trim().toIntOrNull() ?: return null }
        if (hours.isEmpty() || hours.any { it !in 0..23 }) return null
        return hours.distinct().sorted()
    }

    private fun expandHome(path: String): String =
        if (path.startsWith("~/")) System.getProperty("user.home") + path.substring(1) else path

    override fun doOKAction() {
        flushEditing()
        val environments = listModel.items.map {
            ClaudeEnvironment(
                name = it.name.trim(),
                sessionRoot = it.sessionRoot.trim(),
                claudeBinary = it.claudeBinary.trim().takeIf { binary -> binary.isNotEmpty() },
                configDir = it.configDir.trim().takeIf { dir -> dir.isNotEmpty() },
            )
        }
        val activeName = (activeEnvironment ?: listModel.items.firstOrNull())?.name?.trim().orEmpty()

        SessionMetadataStore.replaceEnvironments(environments, activeName)
        SessionSettings.setAutoRefreshEnabled(autoRefreshCheckbox.isSelected)

        val syncConfig = TeamSyncConfig(
            enabled = syncEnabledCheckbox.isSelected,
            paused = pauseCheckbox.isSelected,
            repoPath = expandHome(repoPathField.text.trim()).takeIf { it.isNotEmpty() },
            repoUrl = repoUrlField.text.trim().takeIf { it.isNotEmpty() },
            owner = ownerField.text.trim().takeIf { it.isNotEmpty() },
            projects = selectedProjects(),
            syncHours = parsedSyncHours() ?: TeamSyncConfig.DEFAULT_SYNC_HOURS,
            defaultScope = if (scopeCombo.selectedItem == SCOPE_TEAM_LABEL) {
                TeamSyncConfig.SCOPE_TEAM
            } else {
                TeamSyncConfig.SCOPE_MINE
            },
            minMessages = minMessagesSpinner.value as? Int ?: TeamSyncConfig.DEFAULT_MIN_MESSAGES,
            maxAgeDays = maxAgeSpinner.value as? Int ?: 0,
            extraRedactionPatterns = redactionPatterns(),
            notifyOnFailure = notifyCheckbox.isSelected,
        )
        SessionMetadataStore.setTeamSync(syncConfig)
        applyTeamSync(syncConfig)
        super.doOKAction()
    }

    /** Clone if needed and reschedule the background job — off the EDT, reporting only failures. */
    private fun applyTeamSync(config: TeamSyncConfig) {
        if (!config.enabled) {
            // Turning sharing off must not stop indexing: the same job drops back to its
            // index-only schedule rather than being removed.
            ApplicationManager.getApplication().executeOnPooledThread {
                if (McpRuntime.isAgentInstalled()) {
                    McpRuntime.installAgent(config)
                } else {
                    McpRuntime.retireLegacySyncAgent()
                }
            }
            return
        }

        object : Task.Backgroundable(project, "Setting up team session sync", false) {
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val repoPath = config.repoPath ?: return
                val repoDir = File(repoPath)

                if (!repoDir.isDirectory) {
                    indicator.text = "Cloning the knowledge-base repo…"
                    val cloned = cloneRepo(config.repoUrl.orEmpty(), repoDir)
                    if (!cloned.first) {
                        failure = "Cloning ${config.repoUrl} failed.\n\n${cloned.second}"
                        return
                    }
                }

                indicator.text = "Scheduling the background job…"
                val installed = McpRuntime.installAgent(config)
                if (!installed.first) {
                    failure = "Scheduling the background job failed.\n\n${installed.second}"
                }
            }

            override fun onFinished() {
                failure?.let { message ->
                    ApplicationManager.getApplication().invokeLater(
                        { Messages.showErrorDialog(project, message, "Team Sync Setup") },
                        ModalityState.any(),
                    )
                }
            }
        }.queue()
    }

    private fun cloneRepo(url: String, target: File): Pair<Boolean, String> = try {
        target.parentFile?.mkdirs()
        val process = ProcessBuilder("git", "clone", url, target.absolutePath)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false to "git clone timed out after 180s."
        } else {
            (process.exitValue() == 0) to process.inputStream.bufferedReader().readText().trim()
        }
    } catch (throwable: Throwable) {
        false to (throwable.message ?: "Unknown error")
    }

    /** State at a glance, above the controls that change it. */
    private class StatusBanner : Card() {

        private val dot = Dot()
        private val headline = JBLabel().apply { font = JBFont.label().asBold() }
        private val detail = JBLabel().apply {
            font = JBFont.small()
            foreground = Ui.inkMuted
        }

        init {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(10, 12)
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(dot)
                    add(
                        JBPanel<JBPanel<*>>().apply {
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            isOpaque = false
                            add(headline)
                            add(detail)
                        }
                    )
                },
                BorderLayout.CENTER,
            )
        }

        fun show(tone: Color, title: String, message: String) {
            dot.tone = tone
            headline.text = title
            detail.text = message
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            repaint()
        }

        private class Dot : JComponent() {
            var tone: Color = Ui.inkMuted

            init {
                val size = JBUI.scale(10)
                preferredSize = Dimension(size, size)
            }

            override fun paintComponent(graphics: Graphics) {
                val canvas = Ui.antialiased(graphics.create())
                try {
                    canvas.color = tone
                    canvas.fillOval(0, 0, width, height)
                } finally {
                    canvas.dispose()
                }
            }
        }
    }
}
