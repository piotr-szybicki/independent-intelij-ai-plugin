package com.github.piotrszybicki.independentintelijaiplugin.cleanup

/**
 * What counts as a Javadoc comment, and how much of the file goes with one when it is taken out.
 *
 * Pure string work, and deliberately so: which comments to cut is decided from the comment's own
 * text, and how much to cut with it from the text around it, so the whole judgement is testable
 * without a project, a document or a PSI tree. [JavadocSweep] is the part that needs those.
 *
 * "Javadoc" here means the syntax rather than the language: a block opening with a slash and two
 * stars, which is a Javadoc comment in Java and a KDoc one in Kotlin, and is written the same way in
 * Groovy, Scala, JS and anything else that borrowed the form. The sweep finds them through
 * [com.intellij.psi.PsiComment], so whichever parser the file has is the one that decides a comment
 * is a comment -- a doc-comment opener inside a string literal is never seen here.
 */
object JavadocComments {

    /**
     * A half-open `[start, end)` span of the file's text to delete.
     *
     * Wider than the comment itself when the comment has a line to itself -- see [removalFor].
     */
    data class Removal(val start: Int, val end: Int)

    /**
     * Whether [text] is a Javadoc comment rather than a plain block or line comment.
     *
     * The length floor rules out the four-character empty block comment, which opens with a slash
     * and two stars and closes immediately while being an ordinary comment; the shortest real
     * Javadoc is five characters. A plain block comment and a line comment are left alone, which is
     * the point: this removes documentation comments, not commented-out code.
     */
    fun isJavadoc(text: String): Boolean =
        text.length >= 5 && text.startsWith("/**") && text.endsWith("*/")

    /**
     * Whether [text] is a Javadoc comment that says nothing -- the stub an IDE writes and nobody
     * ever fills in: an opener, a line holding a lone star, and a closer.
     *
     * Blank once the delimiters and each line's leading stars are off. A comment holding only a tag
     * (`@param x`) is not blank: it may say little, but it says it to a tool.
     */
    fun isBlank(text: String): Boolean {
        if (!isJavadoc(text)) return false
        val inner = text.substring(3, text.length - 2)
        return inner.lineSequence().all { it.trim().trimStart('*').isBlank() }
    }

    /**
     * How much of [fileText] to delete for the comment occupying `[commentStart, commentEnd)`.
     *
     * A comment with nothing but whitespace on either side of it takes its whole line -- or lines --
     * with it, including the line break, because cutting only the comment would leave a run of blank
     * indented lines where the documentation was. A comment sharing its line with code keeps the
     * line: only the comment goes, plus the spaces trailing it, so an argument documented inline
     * closes up to `foo(x)` rather than `foo( x)`.
     */
    fun removalFor(fileText: String, commentStart: Int, commentEnd: Int): Removal {
        val lineStart = fileText.lastIndexOf('\n', commentStart - 1) + 1
        val lineEnd = fileText.indexOf('\n', commentEnd).takeIf { it >= 0 } ?: fileText.length

        val alone = fileText.substring(lineStart, commentStart).isBlank() &&
            fileText.substring(commentEnd, lineEnd).isBlank()
        if (alone) {
            // The line break goes too, unless the comment ends the file and there is none.
            return Removal(lineStart, if (lineEnd < fileText.length) lineEnd + 1 else fileText.length)
        }

        var end = commentEnd
        while (end < fileText.length && (fileText[end] == ' ' || fileText[end] == '\t')) end++
        return Removal(commentStart, end)
    }
}
