package com.mahadi.claudesessions.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mahadi.claudesessions.ClaudeEnvironment
import com.mahadi.claudesessions.McpRuntime
import com.mahadi.claudesessions.SessionMetadataStore
import com.mahadi.claudesessions.SessionSettings
import com.mahadi.claudesessions.TeamSyncConfig
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

private class EditableEnvironment(
    var name: String,
    var sessionRoot: String,
    var claudeBinary: String,
    var configDir: String,
) {
    override fun toString(): String = name
}

/**
 * The plugin's settings: Claude environments, team knowledge-base sync, and general
 * preferences — one tab each.
 *
 * Each environment is one account: its transcript directory plus the executable that
 * drives it. Which one is *active* is picked from the toolbar dropdown, not here.
 * The team sync tab writes to the shared metadata sidecar, so the bundled Python sync
 * engine reads exactly what was configured here.
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

    private val syncEnabledCheckbox = JBCheckBox("Sync sessions with the team knowledge base")
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
    private val projectsField = JBTextField()
    private val syncHoursField = JBTextField()
    private val syncStatusLabel = JBLabel()

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
        repoUrlField.text = config.repoUrl.orEmpty()
        repoPathField.text = config.repoPath.orEmpty()
        ownerField.text = config.owner.orEmpty()
        projectsField.text = config.projects.joinToString(", ")
        syncHoursField.text = config.syncHours.joinToString(", ")
        updateSyncStatus()
        updateSyncFieldsEnabled()
    }

    private fun updateSyncStatus() {
        syncStatusLabel.text = when {
            !McpRuntime.isInstalled() ->
                "The sync engine installs with session search — tick MCP in the panel toolbar first."
            McpRuntime.isSyncAgentInstalled() ->
                "Scheduled sync is installed. Log: ~/.claude-session-cache/sync.log"
            else ->
                "Scheduled sync is not installed yet — it is set up when you apply with sync enabled."
        }
    }

    private fun updateSyncFieldsEnabled() {
        val enabled = syncEnabledCheckbox.isSelected
        repoUrlField.isEnabled = enabled
        repoPathField.isEnabled = enabled
        ownerField.isEnabled = enabled
        projectsField.isEnabled = enabled
        syncHoursField.isEnabled = enabled
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
        preferredSize = Dimension(JBUI.scale(620), JBUI.scale(520))
        addTab("Environments", environmentsTab())
        addTab("Team Sync", teamSyncTab())
        addTab("General", generalTab())
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
                preferredSize = Dimension(JBUI.scale(560), JBUI.scale(112))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(112))
            }

        return tabPanel(
            "One entry per Claude account. Switch between them from the dropdown in the panel.",
        ) {
            add(listPanel)
            add(field("Name", nameField, "Shown in the toolbar dropdown, e.g. claude or claude-work."))
            add(field("Session directory", sessionRootField, "Where this account's transcripts are read from."))
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
    }

    private fun teamSyncTab(): JComponent = tabPanel(
        "Shares redacted session history with your team through a private git repository, " +
            "and pulls theirs back — so Claude can answer \"what did anyone on the team decide " +
            "about X\". Sessions marked private in the session list never leave this machine.",
    ) {
        add(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(4)
                add(syncEnabledCheckbox, BorderLayout.WEST)
            }
        )
        add(
            field(
                "Repository URL",
                repoUrlField,
                "The private repo's git URL, e.g. git@github.com:your-org/claude-knowledge-base.git. " +
                    "Used to clone when the local path below does not exist yet.",
            )
        )
        add(
            field(
                "Local path",
                repoPathField,
                "Where the repo lives (or should be cloned to) on this machine, " +
                    "e.g. ~/claude-knowledge-base.",
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
        add(
            field(
                "Projects to share",
                projectsField,
                "Comma-separated project names (the folder name, e.g. tourbooker). " +
                    "Only these are exported — personal projects stay local. Empty shares nothing.",
            )
        )
        add(
            field(
                "Sync at (hours)",
                syncHoursField,
                "Comma-separated hours of the day, 0-23. The default 9, 18 syncs at the start " +
                    "and end of the workday; a missed run catches up at the next login.",
            )
        )
        add(
            syncStatusLabel.apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBFont.small()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(12)
            }
        )
    }

    private fun generalTab(): JComponent = tabPanel(
        "Preferences for the session list itself.",
    ) {
        add(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(4)
                add(autoRefreshCheckbox, BorderLayout.WEST)
            }
        )
    }

    private fun tabPanel(description: String, content: JBPanel<JBPanel<*>>.() -> Unit): JComponent =
        JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 12)
            add(
                JBLabel(wrapped(description)).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    font = JBFont.small()
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(6)
                }
            )
            content()
        }

    private fun field(label: String, editor: JComponent, hint: String): JComponent =
        JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(8)
            add(JBLabel(label).apply {
                font = JBFont.label().asBold()
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(editor.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(JBLabel(wrapped(hint)).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBFont.small()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(2)
            })
        }

    /** Long hints must wrap instead of stretching the dialog to their natural width. */
    private fun wrapped(text: String): String =
        "<html><body style='width: ${JBUI.scale(520)}px'>$text</body></html>"

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
        return null
    }

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
            repoPath = expandHome(repoPathField.text.trim()).takeIf { it.isNotEmpty() },
            repoUrl = repoUrlField.text.trim().takeIf { it.isNotEmpty() },
            owner = ownerField.text.trim().takeIf { it.isNotEmpty() },
            projects = projectsField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            syncHours = parsedSyncHours() ?: TeamSyncConfig.DEFAULT_SYNC_HOURS,
        )
        SessionMetadataStore.setTeamSync(syncConfig)
        applyTeamSync(syncConfig)
        super.doOKAction()
    }

    /** Clone if needed and (un)install the scheduled agent — off the EDT, reporting only failures. */
    private fun applyTeamSync(config: TeamSyncConfig) {
        if (!config.enabled) {
            ApplicationManager.getApplication().executeOnPooledThread { McpRuntime.removeSyncAgent() }
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

                indicator.text = "Scheduling the sync agent…"
                val installed = McpRuntime.installSyncAgent(config.syncHours)
                if (!installed.first) {
                    failure = "Scheduling the sync agent failed.\n\n${installed.second}"
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
}
