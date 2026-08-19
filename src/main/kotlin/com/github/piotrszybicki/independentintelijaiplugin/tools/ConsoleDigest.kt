package com.github.piotrszybicki.independentintelijaiplugin.tools

class ConsoleDigest(projectPath: String? = null) {

    companion object {
        private const val MAX_OUTPUT_CHARS = 20_000
        private const val MAX_LINE_CHARS = 2_000
        private const val MIN_RUN_LINES = 3
        private const val MIN_SIMILARITY = 0.5
        private const val MIN_AFFIX_CHARS = 8
        private const val MAX_SAMPLES = 12
        private const val MAX_INLINE_MIDDLE_CHARS = 24
        private const val MIN_SHOWN_AFFIX_CHARS = 4
        private const val MAX_RUNS_PER_SIGNATURE = 3
        private const val MAX_TRACKED_SIGNATURES = 4_000

        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

        fun of(text: String, projectPath: String? = null): String =
            ConsoleDigest(projectPath).apply { append(text) }.snapshot()
    }

    private val prefixes: List<String> = projectPath
        ?.replace('\\', '/')
        ?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }
        ?.let { listOf("file:///$it/", "file://$it/", "$it/", it.replace('/', '\\') + "\\") }
        .orEmpty()

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val runsPerSignature = HashMap<String, Int>()
    private val partial = StringBuilder()
    private val samples = mutableListOf<String>()

    private var retainedChars = 0
    private var droppedLines = 0
    private var suppressedLines = 0

    private var runPrefix: String? = null
    private var runSuffix = ""
    private var runLast = ""
    private var runCount = 0
    private var runMinLength = 0

    fun append(text: String) {
        synchronized(lock) {
            var start = 0
            while (true) {
                val newline = text.indexOf('\n', start)
                if (newline < 0) {
                    partial.append(text, start, text.length)
                    break
                }
                partial.append(text, start, newline)
                consume(partial.toString())
                partial.setLength(0)
                start = newline + 1
            }
            if (partial.length > MAX_LINE_CHARS) {
                consume(partial.toString())
                partial.setLength(0)
            }
        }
    }

    fun snapshot(): String = synchronized(lock) {
        if (partial.isNotEmpty()) {
            consume(partial.toString())
            partial.setLength(0)
        }
        flushRun()

        val body = lines.joinToString("\n").trim()
        if (body.isEmpty() && droppedLines == 0 && suppressedLines == 0) return ""

        buildString {
            if (droppedLines > 0) append("[... $droppedLines earlier line(s) omitted ...]\n")
            append(body)
            if (suppressedLines > 0) {
                append("\n\n[... $suppressedLines further repetitive line(s) suppressed ...]")
            }
        }
    }

    private fun consume(raw: String) {
        val line = clean(raw)
        val prefix = runPrefix
        if (prefix == null) {
            startRun(line)
            return
        }

        if (line != runLast && similarity(runLast, line) < MIN_SIMILARITY) {
            flushRun()
            startRun(line)
            return
        }

        val merged = prefix.commonPrefixWith(line)
        val mergedSuffix = runSuffix.commonSuffixWith(line)
        if (line != runLast && merged.length + mergedSuffix.length < MIN_AFFIX_CHARS) {
            flushRun()
            startRun(line)
            return
        }

        runPrefix = merged
        runSuffix = mergedSuffix
        runLast = line
        runCount++
        runMinLength = minOf(runMinLength, line.length)
        if (samples.size < MAX_SAMPLES) samples.add(line)
    }

    private fun startRun(line: String) {
        runPrefix = line
        runSuffix = line
        runLast = line
        runCount = 1
        runMinLength = line.length
        samples.clear()
        samples.add(line)
    }

    private fun flushRun() {
        var prefix = runPrefix ?: return
        var suffix = runSuffix
        val count = runCount
        val minLength = runMinLength
        val last = runLast
        val members = samples.toList()

        runPrefix = null
        runSuffix = ""
        runLast = ""
        runCount = 0
        runMinLength = 0
        samples.clear()

        val first = members.firstOrNull() ?: return
        if (first.isEmpty()) {
            emit("")
            return
        }
        if (count < MIN_RUN_LINES) {
            members.forEach { emit(it) }
            return
        }

        if (prefix.length > minLength) prefix = prefix.take(minLength)
        if (prefix.length + suffix.length > minLength) {
            suffix = suffix.takeLast(minLength - prefix.length)
        }

        val signature = prefix + "\u0000" + suffix
        val seen = runsPerSignature[signature] ?: 0
        if (seen >= MAX_RUNS_PER_SIGNATURE) {
            suppressedLines += count
            return
        }
        if (runsPerSignature.size < MAX_TRACKED_SIGNATURES) runsPerSignature[signature] = seen + 1

        val remaining = count - members.size

        if (members.all { middleOf(it, prefix, suffix).isEmpty() }) {
            emit(first)
            emit("  ... and ${count - 1} more identical line(s)")
            return
        }

        prefix = prefix.dropLastWhile { it.isLetterOrDigit() }
        suffix = suffix.dropWhile { it.isLetterOrDigit() }

        val inline = members.all { middleOf(it, prefix, suffix).length <= MAX_INLINE_MIDDLE_CHARS }
        if (!inline) {
            if (prefix.length < MIN_SHOWN_AFFIX_CHARS) prefix = ""
            if (suffix.length < MIN_SHOWN_AFFIX_CHARS) suffix = ""
        }

        if (prefix.isEmpty() && suffix.isEmpty()) {
            members.forEach { emit(it) }
            if (remaining > 0) emit("  ... and $remaining more similar line(s), last: $last")
            return
        }

        val middles = members.map { middleOf(it, prefix, suffix) }
        emit("$prefix\u2026$suffix  [$count lines]")

        if (inline) {
            val more = if (remaining > 0) {
                ", ... and $remaining more, last: ${middleOf(last, prefix, suffix)}"
            } else {
                ""
            }
            emit("  " + middles.joinToString(", ") + more)
            return
        }

        for (middle in middles) emit("  $middle")
        if (remaining > 0) {
            emit("  ... and $remaining more, last: ${middleOf(last, prefix, suffix)}")
        }
    }

    private fun middleOf(line: String, prefix: String, suffix: String): String =
        if (line.length < prefix.length + suffix.length) line
        else line.substring(prefix.length, line.length - suffix.length)

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return if (a == b) 1.0 else 0.0
        val prefix = a.commonPrefixWith(b).length
        val suffix = a.substring(prefix).commonSuffixWith(b.substring(prefix)).length
        return (prefix + suffix).toDouble() / minOf(a.length, b.length)
    }

    private fun emit(line: String) {
        lines.addLast(line)
        retainedChars += line.length + 1
        while (retainedChars > MAX_OUTPUT_CHARS && lines.size > 1) {
            retainedChars -= lines.removeFirst().length + 1
            droppedLines++
        }
    }

    private fun clean(raw: String): String {
        val rewrite = raw.lastIndexOf('\r')
        var line = ANSI.replace(if (rewrite < 0) raw else raw.substring(rewrite + 1), "").trimEnd()
        for (prefix in prefixes) line = line.replace(prefix, "", ignoreCase = true)
        return if (line.length > MAX_LINE_CHARS) {
            line.take(MAX_LINE_CHARS) + " ...[${line.length - MAX_LINE_CHARS} characters cut]"
        } else {
            line
        }
    }
}
