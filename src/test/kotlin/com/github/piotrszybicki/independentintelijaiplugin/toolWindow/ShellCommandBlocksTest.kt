package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandBlocksTest {
    @Test
    fun `tables render alongside shell blocks with inline formatting and escaped pipes`() {
        val markdown = "| Name | Value |\n| :--- | ---: |\n| **One** | a\\|b |\n\n```bash\necho ok\n```"
        val blocks = ShellCommandBlocks(markdown)
        for (html in listOf(blocks.html, MarkdownRenderer.toHtml(markdown))) {
            assertTrue(html.contains("<table>"))
            assertTrue(html.contains("<strong>One</strong>"))
            assertTrue(html.contains("a|b"))
            assertTrue(html.contains("align=\"right\""))
        }
        assertEquals(listOf("echo ok"), blocks.contents)
    }

    @Test
    fun `each shell block gets its own exact command without the fence newline`() {
        val blocks =
            ShellCommandBlocks("```bash\necho '<hello>'\nprintf world\n```\n\n```powershell\nGet-Location\n```")
        assertEquals(listOf("echo '<hello>'\nprintf world", "Get-Location"), blocks.contents)
        assertTrue(blocks.html.contains("&lt;hello&gt;"))
        assertTrue(blocks.html.contains("id=\"shell-command-1\""))
    }

    @Test
    fun `source code and unlabelled blocks do not get shell controls`() {
        val blocks = ShellCommandBlocks("```kotlin\nprintln(1)\n```\n\n```\nexample output\n```")
        assertFalse(blocks.html.contains("Paste to terminal"))
        assertTrue(blocks.contents.isEmpty())
    }
}
