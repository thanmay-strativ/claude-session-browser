package com.mahadi.claudesessions.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mahadi.claudesessions.ClaudeEnvironment
import com.mahadi.claudesessions.SessionMetadataStore
import com.mahadi.claudesessions.SessionSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.io.File
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
 * Manages the Claude environments the panel can switch between.
 *
 * Each environment is one account: its transcript directory plus the executable that
 * drives it (`claude-work` alongside `claude`). Which one is *active* is picked from the
 * toolbar dropdown, not here — this dialog only defines the set.
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

    private val autoRefreshCheckbox = JBCheckBox(
        "Auto-refresh the session list every ${SessionSettings.autoRefreshSeconds()}s",
        SessionSettings.isAutoRefreshEnabled(),
    )

    private var editing: EditableEnvironment? = null
    private var activeEnvironment: EditableEnvironment? = null
    private var loading = false

    init {
        title = "Claude Environments"
        setOKButtonText("Apply")
        loadEnvironments()
        wireListeners()
        init()
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
        loadEditing()
    }

    override fun createCenterPanel(): JComponent {
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

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(JBUI.scale(580), JBUI.scale(500))
            border = JBUI.Borders.empty(8, 10)

            add(
                JBLabel("Environments").apply {
                    font = JBFont.label().asBold()
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            )
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
            add(
                JBPanel<JBPanel<*>>(BorderLayout()).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyTop(10)
                    add(autoRefreshCheckbox, BorderLayout.WEST)
                }
            )
            add(
                JBLabel(
                    "<html>Switch between these from the dropdown in the panel. For a second " +
                        "account, set the config directory (e.g. <code>~/.claude-work</code>) and " +
                        "point the session directory at its <code>projects</code> folder — the same " +
                        "<code>claude</code> binary is used for both.</html>"
                ).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    font = JBFont.small()
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyTop(10)
                }
            )
        }
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
            add(JBLabel(hint).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBFont.small()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(2)
            })
        }

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
        return null
    }

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
        super.doOKAction()
    }
}
