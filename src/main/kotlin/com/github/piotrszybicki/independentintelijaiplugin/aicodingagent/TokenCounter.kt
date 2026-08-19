package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import com.intellij.openapi.diagnostic.Logger
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType

object TokenCounter {

    private val log = Logger.getInstance(TokenCounter::class.java)

    private const val CHARS_PER_TOKEN = 4

    private val encoding: Encoding? by lazy {
        runCatching { Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE) }
            .onFailure { log.warn("Could not load the o200k_base encoding; falling back to estimating tokens", it) }
            .getOrNull()
    }

    val isExact: Boolean get() = encoding != null

    fun count(text: String): Int {
        if (text.isEmpty()) return 0
        val encoding = encoding ?: return text.length / CHARS_PER_TOKEN
        return runCatching { encoding.countTokensOrdinary(text) }
            .getOrElse {
                log.warn("Counting tokens failed; estimating instead", it)
                text.length / CHARS_PER_TOKEN
            }
    }
}
