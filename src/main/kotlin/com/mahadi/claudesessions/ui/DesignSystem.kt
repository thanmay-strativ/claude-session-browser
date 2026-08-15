package com.mahadi.claudesessions.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import java.util.Locale
import javax.swing.Icon
import javax.swing.JComponent

/**
 * The plugin's visual language: one data colour, reserved status colours, card surfaces and
 * the few painted primitives every view is assembled from.
 *
 * Both palettes are validated rather than picked by eye — each passes the OKLCH lightness
 * band for its own surface, the chroma floor, colour-vision separation and 3:1 contrast.
 * The previous blue/violet pair failed badly (ΔE 1.3 under deuteranopia: identical), and
 * bright Darcula-style values sit outside the dark band, which is why the dark steps here
 * are deeper than an automatic lightening of the light ones.
 *
 * [ACCENT] is the only colour data marks ever wear: every chart here plots a single series,
 * where magnitude is the message and hue variety would just be decoration. [GOOD] and
 * [ATTENTION] are reserved for state and always ship next to a word, never as colour alone.
 */
internal object Ui {

    val ACCENT = JBColor(0x3B82F6, 0x3D7DD6)
    val GOOD = JBColor(0x0E9F6E, 0x35A382)
    val ATTENTION = JBColor(0xB45309, 0xBE8B1E)
    val BAD = JBColor(0xC53030, 0xD45B5B)

    /**
     * Who is speaking in a transcript. Blue and green are an adjacent validated pair (worst-case
     * ΔE 10.6 protan, 18.3 normal-vision), and a transcript plots nothing, so reusing the status
     * green here cannot be mistaken for a status mark.
     */
    val ROLE_YOU = ACCENT
    val ROLE_CLAUDE = GOOD

    val CARD_SURFACE = JBColor(0xFFFFFF, 0x313438)
    val CARD_BORDER = JBColor(0xE1E4E8, 0x393B40)

    /**
     * The faintest rule that still separates: barely-there in both themes, so a long list
     * gains rhythm without the page turning into a grid. Deliberately lighter than
     * [CARD_BORDER] — this divides items *inside* one surface, it does not enclose them.
     */
    val HAIRLINE: JBColor = JBColor(
        ColorUtil.withAlpha(Color(0x0B, 0x0D, 0x12), 0.055),
        ColorUtil.withAlpha(Color(0xFF, 0xFF, 0xFF), 0.055),
    )
    val TRACK = JBColor(0xE8EDF5, 0x3A3D41)
    val CHIP_SURFACE = JBColor(0xEEF2F8, 0x383B41)
    val CODE_SURFACE = JBColor(0xF6F7F9, 0x2B2D30)
    val ACCENT_WASH = JBColor(0xDCE8FD, 0x2F3E58)

    val ink: Color get() = UIUtil.getLabelForeground()
    val inkMuted: Color get() = UIUtil.getContextHelpForeground()

    const val RADIUS = 10
    const val BAR_RADIUS = 4

    fun sectionTitle(title: String): JComponent = JBLabel(title).apply {
        font = JBFont.label().asBold()
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(14, 2, 6, 0)
    }

    fun mutedRow(text: String): JComponent = JBLabel(text).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        foreground = inkMuted
        border = JBUI.Borders.empty(1, 2)
    }

    /** Compact figures for tiles and dense rows: 1,284 → 1.3k, 4,295,769 → 4.3M. */
    fun formatCompact(value: Long): String = when {
        value >= 1_000_000_000 -> "%.1fB".format(Locale.ROOT, value / 1_000_000_000.0)
        value >= 1_000_000 -> "%.1fM".format(Locale.ROOT, value / 1_000_000.0)
        value >= 1_000 -> "%.1fk".format(Locale.ROOT, value / 1_000.0)
        else -> value.toString()
    }

    fun relativeTime(millis: Long): String {
        val deltaSeconds = (System.currentTimeMillis() - millis) / 1000
        return when {
            deltaSeconds < 60 -> "just now"
            deltaSeconds < 3600 -> "${deltaSeconds / 60}m ago"
            deltaSeconds < 86_400 -> "${deltaSeconds / 3600}h ago"
            deltaSeconds < 604_800 -> "${deltaSeconds / 86_400}d ago"
            deltaSeconds < 2_592_000 -> "${deltaSeconds / 604_800}w ago"
            else -> "${deltaSeconds / 2_592_000}mo ago"
        }
    }

    fun antialiased(graphics: Graphics): Graphics2D = (graphics as Graphics2D).apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    /**
     * A bar with a rounded data end and a square baseline, so the mark visibly grows from
     * the axis instead of floating. Rounding both ends detaches short bars from the origin.
     */
    fun fillBarFromLeft(graphics: Graphics2D, x: Int, y: Int, barWidth: Int, barHeight: Int, color: Color) {
        if (barWidth <= 0) return
        graphics.color = color
        val radius = JBUI.scale(BAR_RADIUS)
        graphics.fillRoundRect(x, y, barWidth, barHeight, radius, radius)
        graphics.fillRect(x, y, minOf(radius, barWidth), barHeight)
    }

    /** The same mark stood upright: rounded cap, square foot on the baseline at [bottom]. */
    fun fillColumn(graphics: Graphics2D, x: Int, bottom: Int, barWidth: Int, barHeight: Int, color: Color) {
        if (barHeight <= 0 || barWidth <= 0) return
        graphics.color = color
        val radius = JBUI.scale(BAR_RADIUS)
        val top = bottom - barHeight
        graphics.fillRoundRect(x, top, barWidth, barHeight, radius, radius)
        graphics.fillRect(x, bottom - minOf(radius, barHeight), barWidth, minOf(radius, barHeight))
    }
}

/**
 * A bar chart, drawn rather than borrowed.
 *
 * The platform ships no chart icon — its "profiler" icons are semicircular gauges, which
 * at 16px read as a cloud rather than as statistics. Three ascending columns over a
 * baseline say what the button opens, and echo the column charts inside it. Painted in
 * the toolbar icon tone so it sits level with the gear and refresh beside it.
 */
internal object BarChartIcon : Icon {

    private val tone = JBColor(0x6C707E, 0xCED0D6)

    override fun getIconWidth(): Int = JBUI.scale(16)

    override fun getIconHeight(): Int = JBUI.scale(16)

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val canvas = Ui.antialiased(graphics.create())
        try {
            canvas.color = tone
            // Geometry is authored on a 16x16 grid and scaled, so it stays true at any DPI.
            val unit = getIconWidth() / 16f
            fun bar(left: Float, top: Float, barWidth: Float, barHeight: Float) {
                canvas.fill(
                    Rectangle2D.Float(
                        x + left * unit,
                        y + top * unit,
                        barWidth * unit,
                        barHeight * unit,
                    )
                )
            }
            bar(2f, 9f, 3f, 4f)
            bar(6.5f, 5.5f, 3f, 7.5f)
            bar(11f, 2.5f, 3f, 10.5f)
            bar(1.5f, 13.5f, 13f, 1f)
        } finally {
            canvas.dispose()
        }
    }
}

/**
 * A one-pixel rule for separating stacked items.
 *
 * Height is left unscaled on purpose: [JBUI.scale] would thicken it to two or three
 * device pixels on a HiDPI display, which is exactly the heaviness this is avoiding.
 * [inset] keeps the rule clear of the text it divides.
 */
internal class Hairline(private val inset: Int = 0) : JComponent() {

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        preferredSize = Dimension(1, 1)
        maximumSize = Dimension(Int.MAX_VALUE, 1)
        minimumSize = Dimension(1, 1)
    }

    override fun paintComponent(graphics: Graphics) {
        graphics.color = Ui.HAIRLINE
        graphics.fillRect(inset, 0, (width - inset * 2).coerceAtLeast(0), 1)
    }
}

/**
 * A raised rounded surface — the unit every section groups its content into.
 *
 * [rail] paints a rounded accent along the left edge instead of a hard border line, which is
 * how one speaker is marked out from another without giving it a second colour.
 */
internal open class Card(
    private val radius: Int = Ui.RADIUS,
    private val surface: Color = Ui.CARD_SURFACE,
    private val rail: Color? = null,
) : JBPanel<Card>(BorderLayout()) {

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(10, 12)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(graphics: Graphics) {
        val canvas = Ui.antialiased(graphics.create())
        try {
            val scaledRadius = JBUI.scale(radius)
            canvas.color = surface
            canvas.fillRoundRect(0, 0, width - 1, height - 1, scaledRadius, scaledRadius)
            canvas.color = Ui.CARD_BORDER
            canvas.drawRoundRect(0, 0, width - 1, height - 1, scaledRadius, scaledRadius)
            rail?.let {
                val railWidth = JBUI.scale(3)
                canvas.color = it
                canvas.fillRoundRect(0, 0, railWidth * 2, height - 1, railWidth * 2, railWidth * 2)
                canvas.color = surface
                canvas.fillRect(railWidth, 0, railWidth, height - 1)
            }
        } finally {
            canvas.dispose()
        }
        super.paintComponent(graphics)
    }
}

/**
 * One figure and its label. The accent is a short rail rather than a full-width underline,
 * which keeps the colour as a quiet marker instead of a second data mark.
 */
internal class StatTile(value: String, label: String, accent: Color) : Card(8) {

    init {
        border = JBUI.Borders.empty(9, 12)
        val text = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(
                JBLabel(value).apply {
                    font = JBFont.label().asBold().biggerOn(5f)
                    foreground = Ui.ink
                },
                BorderLayout.NORTH,
            )
            add(
                JBLabel(label).apply {
                    font = JBFont.small()
                    foreground = Ui.inkMuted
                },
                BorderLayout.SOUTH,
            )
        }
        add(AccentRail(accent), BorderLayout.WEST)
        add(text, BorderLayout.CENTER)
    }

    private class AccentRail(private val color: Color) : JComponent() {
        init {
            preferredSize = Dimension(JBUI.scale(3), JBUI.scale(28))
        }

        override fun paintComponent(graphics: Graphics) {
            val canvas = Ui.antialiased(graphics.create())
            try {
                canvas.color = color
                canvas.fillRoundRect(0, 0, width, height, width, width)
            } finally {
                canvas.dispose()
            }
        }
    }
}

/**
 * Who is speaking: a filled pill in the role's colour, carrying an icon and the role name.
 *
 * The colour arrives as the fill and the icon, never as the text — small text in a mid-tone hue
 * misses 4.5:1 against any surface it sits on, so the label stays in ink and the colour does its
 * work around it.
 */
internal class RoleBadge(
    private val text: String,
    private val tone: Color,
    private val icon: Icon,
) : JComponent() {

    private val horizontalPadding = JBUI.scale(7)
    private val gap = JBUI.scale(4)

    init {
        font = JBFont.small().asBold()
        val metrics = getFontMetrics(font)
        preferredSize = Dimension(
            horizontalPadding * 2 + icon.iconWidth + gap + metrics.stringWidth(text),
            maxOf(metrics.height, icon.iconHeight) + JBUI.scale(5),
        )
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(graphics: Graphics) {
        val canvas = Ui.antialiased(graphics.create())
        try {
            canvas.color = ColorUtil.withAlpha(tone, 0.18)
            canvas.fillRoundRect(0, 0, width, height, height, height)

            icon.paintIcon(this, canvas, horizontalPadding, (height - icon.iconHeight) / 2)

            canvas.font = font
            val metrics = canvas.fontMetrics
            canvas.color = Ui.ink
            canvas.drawString(
                text,
                horizontalPadding + icon.iconWidth + gap,
                (height - metrics.height) / 2 + metrics.ascent,
            )
        } finally {
            canvas.dispose()
        }
    }
}

/**
 * A flat dropdown: the current value, a chevron, and a popup on click.
 *
 * Deliberately neither a `JComboBox` nor a `JButton`. The platform combo paints a tall native
 * control with its own focus ring and arrow well, which is what made the toolbar row look
 * bolted-together; and the macOS look-and-feel upper-cases `JButton` text — the trap that once
 * turned a project name into "TOURBOOKER". Painting it here keeps these controls in the same
 * chip language as the rest of the panel, identical in both themes.
 */
internal class DropdownChip(
    text: String,
    private val onOpen: (DropdownChip) -> Unit,
) : JComponent() {

    private var label: String = text
    private var hovered = false
    private var armed = false

    private val horizontalPadding = JBUI.scale(9)
    private val verticalPadding = JBUI.scale(5)
    private val chevron = JBUI.scale(8)
    private val gap = JBUI.scale(7)

    init {
        font = JBFont.label()
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(event: MouseEvent) = repaintWith { hovered = true }

            override fun mouseExited(event: MouseEvent) = repaintWith { hovered = false; armed = false }

            override fun mousePressed(event: MouseEvent) = repaintWith { armed = true }

            override fun mouseReleased(event: MouseEvent) {
                val wasArmed = armed
                repaintWith { armed = false }
                if (wasArmed && contains(event.point)) onOpen(this@DropdownChip)
            }
        })
    }

    private fun repaintWith(change: () -> Unit) {
        change()
        repaint()
    }

    fun setLabel(text: String) {
        if (label == text) return
        label = text
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val metrics = getFontMetrics(font)
        return Dimension(
            horizontalPadding * 2 + metrics.stringWidth(label) + gap + chevron,
            metrics.height + verticalPadding * 2,
        )
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(graphics: Graphics) {
        val canvas = Ui.antialiased(graphics.create())
        try {
            val radius = JBUI.scale(8)
            canvas.color = when {
                armed -> Ui.TRACK
                hovered -> Ui.ACCENT_WASH
                else -> Ui.CHIP_SURFACE
            }
            canvas.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
            canvas.color = Ui.CARD_BORDER
            canvas.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)

            canvas.font = font
            val metrics = canvas.fontMetrics
            canvas.color = Ui.ink
            canvas.drawString(label, horizontalPadding, (height - metrics.height) / 2 + metrics.ascent)

            paintChevron(canvas, width - horizontalPadding - chevron, height / 2)
        } finally {
            canvas.dispose()
        }
    }

    private fun paintChevron(canvas: Graphics2D, left: Int, middle: Int) {
        canvas.color = Ui.inkMuted
        canvas.stroke = BasicStroke(JBUIScale.scale(1.3f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val half = chevron / 2
        val drop = JBUI.scale(2)
        canvas.drawLine(left, middle - drop, left + half, middle + drop)
        canvas.drawLine(left + half, middle + drop, left + chevron, middle - drop)
    }
}

/** A pill badge: quiet metadata that stays readable without shouting like a coloured word. */
internal class Chip(
    private val text: String,
    private val tone: Color? = null,
) : JComponent() {

    private val horizontalPadding = JBUI.scale(7)
    private val verticalPadding = JBUI.scale(2)

    init {
        font = JBFont.small()
        val metrics = getFontMetrics(font)
        preferredSize = Dimension(
            metrics.stringWidth(text) + horizontalPadding * 2 + if (tone != null) JBUI.scale(10) else 0,
            metrics.height + verticalPadding * 2,
        )
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(graphics: Graphics) {
        val canvas = Ui.antialiased(graphics.create())
        try {
            canvas.font = font
            val metrics = canvas.fontMetrics
            canvas.color = Ui.CHIP_SURFACE
            canvas.fillRoundRect(0, 0, width, height, height, height)

            var textLeft = horizontalPadding
            if (tone != null) {
                val dot = JBUI.scale(6)
                canvas.color = tone
                canvas.fillOval(textLeft, (height - dot) / 2, dot, dot)
                textLeft += dot + JBUI.scale(4)
            }
            canvas.color = Ui.inkMuted
            canvas.drawString(text, textLeft, (height - metrics.height) / 2 + metrics.ascent)
        } finally {
            canvas.dispose()
        }
    }
}
