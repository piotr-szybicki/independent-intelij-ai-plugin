package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenizer behind the tool-output limit.
 *
 * Worth pinning because the two ways it can go wrong are both silent. If the encoding does not load
 * -- a jar the plugin's classloader cannot see the resources of -- [TokenCounter] falls back to
 * dividing by four and nothing anywhere says so; the limit then fires at whatever four characters a
 * token happens to be for that output. And if the special-token variant were used, a file that
 * mentions `<|endoftext|>` would throw from inside the agent loop and take the turn with it.
 */
class TokenCounterTest {

    @Test
    fun `loads the encoding rather than falling back to an estimate`() {
        assertTrue("o200k_base did not load; counts are estimates, not tokens", TokenCounter.isExact)
    }

    @Test
    fun `counts nothing as nothing`() {
        assertEquals(0, TokenCounter.count(""))
    }

    /** Two common words, two tokens -- the one count that is the same in every BPE OpenAI ships. */
    @Test
    fun `counts common words as one token each`() {
        assertEquals(2, TokenCounter.count("hello world"))
    }

    /**
     * The case the estimate gets wrong, and the reason a real tokenizer is here at all: punctuation
     * and short identifiers tokenize far denser than four characters each, so output that looks
     * modest by length is not.
     */
    @Test
    fun `counts dense punctuation well above the characters-over-four estimate`() {
        val json = """{"a":1,"b":[2,3],"c":{"d":null}}"""
        assertTrue(
            "expected more tokens than the ${json.length / 4}-token estimate",
            TokenCounter.count(json) > json.length / 4,
        )
    }

    /** Text a model would refuse to encode is text here, not an exception. */
    @Test
    fun `treats special tokens as ordinary text`() {
        assertTrue(TokenCounter.count("the file said <|endoftext|> and carried on") > 0)
    }
}
