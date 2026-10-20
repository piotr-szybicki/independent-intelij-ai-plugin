package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import javax.swing.JTextPane
import javax.swing.SwingUtilities

/** Keeps an Enter that accepts a popup item from submitting and clearing the composer draft. */
internal object ComposerSubmitSuppression {
    private const val SUPPRESS_NEXT_SUBMIT = "independent-ai-plugin.suppress-next-submit"

    fun suppressNextSubmit(input: JTextPane) {
        input.putClientProperty(SUPPRESS_NEXT_SUBMIT, true)
        SwingUtilities.invokeLater { input.putClientProperty(SUPPRESS_NEXT_SUBMIT, false) }
    }

    fun consumeSuppressedSubmit(input: JTextPane): Boolean {
        val suppressed = input.getClientProperty(SUPPRESS_NEXT_SUBMIT) == true
        if (suppressed) input.putClientProperty(SUPPRESS_NEXT_SUBMIT, false)
        return suppressed
    }
}
