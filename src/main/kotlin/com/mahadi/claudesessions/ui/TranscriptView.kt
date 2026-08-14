package com.mahadi.claudesessions.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mahadi.claudesessions.ClaudeTranscriptReader
import com.mahadi.claudesessions.EntryKind
import com.mahadi.claudesessions.SessionFileTracker
import com.mahadi.claudesessions.SessionResumer
import com.mahadi.claudesessions.SessionTags
import com.mahadi.claudesessions.TranscriptEntry
import com.mahadi.claudesessions.model.ClaudeSession
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

private const val HEADER_TEXT_MAX_WIDTH = 620
private const val HEADER_TAG_LIMIT = 4

/**
 * Read-only viewer for one session: a header carrying the session's identity and the resume
 * action, above the conversation as a stack of message cards.
 *
 * The two speakers are marked out and everything between them is kept quiet: your turns carry a
 * blue badge on a tinted card with an accent rail, Claude's a green badge on the plain card
 * surface, and tool traffic sits on the code surface in monospace behind a grey chip. Giving all
 * five kinds their own hue would make a long transcript harder to scan, not easier.
 */
class TranscriptView(
    private val project: Project,
    private val session: ClaudeSession,
) : JBPanel<TranscriptView>(BorderLayout()) {

    private var filesTouched: List<String> = emptyList()

    /**
     * Icon leads, text follows — the layout every `JButton` uses, so nothing can land on top of
     * anything else. Putting the icon after the text meant the button kept the width it was sized
     * at before the file count was known, and the longer text then ran under the icon.
     */
    private val filesTouchedButton = JButton(AllIcons.General.ArrowDown).apply {
        iconTextGap = JBUI.scale(6)
        isFocusable = false
        isVisible = false
        font = JBFont.small()
        addActionListener { showFilesTouchedPopup() }
    }

    private val filesTouchedRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(4))).apply {
        isOpaque = false
        add(filesTouchedButton)
    }

    init {
        background = UIUtil.getPanelBackground()
        add(buildHeader(), BorderLayout.NORTH)

        val messages = JBPanel<JBPanel<*>>(VerticalLayout(JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(14, 18)
            isOpaque = false
        }

        add(
            JBScrollPane(messages).apply {
                border = JBUI.Borders.empty()
                viewport.isOpaque = false
                isOpaque = false
                verticalScrollBar.unitIncrement = JBUI.scale(16)
            },
            BorderLayout.CENTER,
        )

        loadAsync(messages)
    }

    private fun buildHeader(): JComponent {
        val titles = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(
                JBLabel(session.title).apply {
                    font = JBFont.label().asBold().biggerOn(3f)
                    alignmentX = LEFT_ALIGNMENT
                    // WEST keeps its full preferred width, so an unbounded title would run under
                    // the Continue button. Capped here, the label ellipsises instead.
                    maximumSize = Dimension(JBUI.scale(HEADER_TEXT_MAX_WIDTH), Int.MAX_VALUE)
                }
            )
            add(metaChips())
            add(filesTouchedRow.apply { alignmentX = LEFT_ALIGNMENT })
        }

        val continueButton = JButton("Continue session", AllIcons.Actions.Execute).apply {
            toolTipText = "Resume in a new Terminal tab (claude --resume ${session.sessionId})"
            addActionListener { SessionResumer.resume(project, session) }
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(Ui.CARD_BORDER, 0, 0, 1, 0),
                JBUI.Borders.empty(14, 18),
            )
            add(titles, BorderLayout.WEST)
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(continueButton)
                },
                BorderLayout.EAST,
            )
        }
    }

    private fun metaChips(): JComponent =
        JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(Chip(session.projectName, Ui.ACCENT))
            add(Chip(SimpleDateFormat("MMM d, yyyy · HH:mm").format(Date(session.lastModifiedMillis))))
            add(Chip("${session.messageCount} messages"))
            session.model?.let { add(Chip(it)) }
            session.gitBranch?.takeIf { it.isNotBlank() && it != "HEAD" }?.let { add(Chip(it)) }
            // Bounded: this row lays out on one line, so an unbounded tag list widens the header
            // until it reaches the Continue button.
            SessionTags.all(session).take(HEADER_TAG_LIMIT).forEach { add(Chip("#$it")) }
        }

    private fun loadAsync(messages: JBPanel<*>) {
        messages.add(Ui.mutedRow("Loading conversation…"))

        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = try {
                ClaudeTranscriptReader().read(session.transcriptPath)
            } catch (throwable: Throwable) {
                emptyList()
            }
            val filesTouched = try {
                SessionFileTracker.filesTouched(session.transcriptPath)
            } catch (throwable: Throwable) {
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater {
                messages.removeAll()
                if (entries.isEmpty()) {
                    messages.add(Ui.mutedRow("No readable messages in this session."))
                } else {
                    entries.forEach { messages.add(messageCard(it)) }
                }
                messages.revalidate()
                messages.repaint()
                showFilesTouched(filesTouched)
            }
        }
    }

    private fun showFilesTouched(paths: List<String>) {
        filesTouched = paths
        if (paths.isEmpty()) return
        filesTouchedButton.text = "Files touched (${paths.size})"
        filesTouchedButton.isVisible = true
        filesTouchedButton.revalidate()
        revalidate()
        repaint()
    }

    /**
     * The full list as a searchable dropdown. Choosing an entry copies its path — the list is
     * something to act on, so it belongs behind a click rather than a tooltip that vanishes when
     * you reach for it, and truncating to the first few files hid the rest entirely.
     */
    private fun showFilesTouchedPopup() {
        if (filesTouched.isEmpty()) return

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(filesTouched)
            .setTitle("Files touched (${filesTouched.size})")
            .setRenderer(FilePathRenderer())
            .setNamerForFiltering { it }
            .setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            .setItemsChosenCallback { chosen ->
                if (chosen.isEmpty()) return@setItemsChosenCallback
                CopyPasteManager.getInstance()
                    .setContents(StringSelection(chosen.joinToString(System.lineSeparator())))
            }
            .setAdText("Enter copies the path · type to filter · ⌘-click for several")
            .setMovable(true)
            .setResizable(true)
            .createPopup()
            .showUnderneathOf(filesTouchedButton)
    }

    private fun messageCard(entry: TranscriptEntry): JComponent {
        val isUser = entry.kind == EntryKind.USER
        val card = Card(
            surface = surfaceFor(entry.kind),
            rail = if (isUser) Ui.ACCENT else null,
        ).apply {
            border = JBUI.Borders.empty(9, if (isUser) 16 else 12, 10, 12)
        }

        val heading = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
            isOpaque = false
            add(roleBadge(entry.kind))
            entry.toolName?.takeIf { entry.kind == EntryKind.TOOL_USE }?.let { add(Chip(it)) }
        }

        val body = JTextArea(entry.text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = if (isMonospace(entry.kind)) Ui.inkMuted else Ui.ink
            font = if (isMonospace(entry.kind)) {
                JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f)))
            } else {
                UIUtil.getLabelFont()
            }
            border = JBUI.Borders.emptyTop(4)
        }

        card.add(heading, BorderLayout.NORTH)
        card.add(body, BorderLayout.CENTER)
        return card
    }

    private fun surfaceFor(kind: EntryKind): Color = when (kind) {
        EntryKind.USER -> Ui.ACCENT_WASH
        EntryKind.ASSISTANT -> Ui.CARD_SURFACE
        else -> Ui.CODE_SURFACE
    }

    /** The two speakers get a coloured, icon-bearing badge; the machinery between them stays quiet. */
    private fun roleBadge(kind: EntryKind): JComponent = when (kind) {
        EntryKind.USER -> RoleBadge("YOU", Ui.ROLE_YOU, AllIcons.General.User)
        EntryKind.ASSISTANT -> RoleBadge("CLAUDE", Ui.ROLE_CLAUDE, AllIcons.Actions.Lightning)
        EntryKind.THINKING -> Chip("thinking")
        EntryKind.TOOL_USE -> Chip("tool")
        EntryKind.TOOL_RESULT -> Chip("result")
    }

    private fun isMonospace(kind: EntryKind): Boolean =
        kind == EntryKind.TOOL_USE || kind == EntryKind.TOOL_RESULT
}

/** File name first, its directory after it in grey — the name is what identifies the row. */
private class FilePathRenderer : ColoredListCellRenderer<String>() {

    override fun customizeCellRenderer(
        list: JList<out String>,
        value: String,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        icon = AllIcons.FileTypes.Any_type
        append(value.substringAfterLast('/'), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        val directory = value.substringBeforeLast('/', "")
        if (directory.isNotEmpty()) {
            append("  $directory", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }
}
