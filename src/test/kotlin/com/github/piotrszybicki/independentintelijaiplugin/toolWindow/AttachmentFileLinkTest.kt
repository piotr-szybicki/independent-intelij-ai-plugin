package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class AttachmentFileLinkTest {
    @Test
    fun `attachment marker displays only its filename and preserves a file link`() {
        val marker =
            AttachmentFileLink.markdown(
                "C:/work/src/main/kotlin/example/VeryUsefulFile.kt",
                "VeryUsefulFile.kt (lines 12-19)",
            )

        assertThat(marker).contains("VeryUsefulFile.kt (lines 12-19)")
        assertThat(marker).doesNotContain("C:/work/src/main/kotlin/example")
        assertThat(MarkdownRenderer.toHtml(marker)).contains("href=\"aicodingagent-file:")
    }

    @Test
    fun `unresolved attachment remains a non-clickable label`() {
        val marker = AttachmentFileLink.markdown(null, "untitled (whole file)")

        assertThat(marker).isEqualTo("📎 `untitled (whole file)`")
    }
}
