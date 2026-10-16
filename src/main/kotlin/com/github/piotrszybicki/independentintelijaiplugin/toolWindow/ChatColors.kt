package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.util.Locale

internal object ChatColors {
    val background: Color get() = UIUtil.getPanelBackground()
    val foreground: Color get() = UIUtil.getLabelForeground()
    val muted: Color get() = UIUtil.getContextHelpForeground()
    val accent: Color get() = JBUI.CurrentTheme.Link.Foreground.ENABLED
    val separator: Color get() = JBColor.namedColor("Group.separatorColor", 0xEBECF0, 0x393B40)
    val error: Color get() = JBColor.namedColor("Component.errorFocusColor", 0xE53E4D, 0x8B3C3C)
    val warning: Color get() = JBColor.namedColor("Component.warningFocusColor", 0xE0A200, 0xA07800)
    val userBubble: Color get() = mix(background, accent, if (JBColor.isBright()) 0.10 else 0.22)
    val userBubbleBorder: Color get() = mix(background, accent, if (JBColor.isBright()) 0.28 else 0.38)
    val aiBubble: Color get() = mix(background, foreground, 0.03)
    val aiBubbleBorder: Color get() = mix(background, foreground, 0.16)
    val toolGroup: Color get() = mix(background, foreground, 0.04)
    val toolGroupBorder: Color get() = mix(background, foreground, 0.12)
    val card: Color get() = mix(background, foreground, 0.05)
    val cardHover: Color get() = mix(background, foreground, 0.10)
    val cardBorder: Color get() = mix(background, foreground, 0.14)
    val codeBackground: Color get() = mix(background, foreground, 0.07)

    fun mix(
        base: Color,
        tint: Color,
        tintRatio: Double,
    ): Color {
        fun channel(
            a: Int,
            b: Int,
        ) = (a * (1 - tintRatio) + b * tintRatio).toInt().coerceIn(0, MAX_COLOR_CHANNEL)
        return Color(channel(base.red, tint.red), channel(base.green, tint.green), channel(base.blue, tint.blue))
    }

    fun hex(color: Color): String = String.format(Locale.ROOT, "#%02x%02x%02x", color.red, color.green, color.blue)

    private const val MAX_COLOR_CHANNEL = 255
}
