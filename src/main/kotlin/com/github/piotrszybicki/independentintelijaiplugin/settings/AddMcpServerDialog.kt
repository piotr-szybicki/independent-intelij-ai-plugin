package com.github.piotrszybicki.independentintelijaiplugin.settings

import com.github.piotrszybicki.independentintelijaiplugin.mcp.McpServerConfig
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import java.awt.BorderLayout
import java.net.URI
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.table.DefaultTableModel
import com.intellij.ui.dsl.builder.panel

class AddMcpServerDialog : DialogWrapper(true) {
    private val serverName = JTextField(30)
    private val address = JTextField(30)
    private val headers = DefaultTableModel(arrayOf("Header", "Value"), 0)
    private val table = JTable(headers)

    init {
        title = "Add MCP Server"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(serverName) }
        row("HTTP address:") { cell(address) }
        row("Headers (optional):") {
            cell(JPanel(BorderLayout()).apply {
                add(JScrollPane(table).apply { preferredSize = java.awt.Dimension(400, 120) }, BorderLayout.CENTER)
                add(JPanel().apply {
                    add(JButton("Add header").apply { addActionListener { headers.addRow(arrayOf("", "")) } })
                    add(JButton("Remove header").apply { addActionListener {
                        if (table.selectedRow >= 0) headers.removeRow(table.selectedRow)
                    } })
                }, BorderLayout.SOUTH)
            })
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (serverName.text.isBlank()) return ValidationInfo("Enter a server name.", serverName)
        val uri = runCatching { URI(address.text.trim()) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https") || uri.host == null) {
            return ValidationInfo("Enter a valid HTTP or HTTPS address.", address)
        }
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val keys = (0 until headers.rowCount).map { headers.getValueAt(it, 0).toString().trim() }
        if (keys.any { it.isEmpty() } || keys.distinct().size != keys.size) {
            return ValidationInfo("Header names must be non-empty and unique.", table)
        }
        return null
    }

    fun configuration(): McpServerConfig = McpServerConfig(
        name = serverName.text.trim(),
        url = address.text.trim(),
        headers = (0 until headers.rowCount).associate {
            headers.getValueAt(it, 0).toString().trim() to headers.getValueAt(it, 1).toString()
        },
    )
}
