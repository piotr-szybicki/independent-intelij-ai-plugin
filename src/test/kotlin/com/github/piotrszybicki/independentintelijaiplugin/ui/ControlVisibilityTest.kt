package com.github.piotrszybicki.independentintelijaiplugin.ui

import org.junit.Test
import java.awt.Container

class ControlVisibilityTest {
    @Test
    fun `manifest entries require named visible controls`() {
        val root = javax.swing.JPanel()
        mainChatControls.forEach { control ->
            root.add(javax.swing.JPanel().apply { name = control.id })
        }

        runOnEdt {
            mainChatControls.forEach { control ->
                root.requireComponent<Container>(control.id).assertVisible(control.description)
            }
        }
    }
}
