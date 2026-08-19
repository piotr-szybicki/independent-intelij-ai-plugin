package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenCounterTest {

    @Test
    fun `loads the encoding rather than falling back to an estimate`() {
        assertTrue("o200k_base did not load; counts are estimates, not tokens", TokenCounter.isExact)
    }

    @Test
    fun `counts nothing as nothing`() {
        assertEquals(0, TokenCounter.count(""))
    }

    @Test
    fun `counts common words as one token each`() {
        assertEquals(2, TokenCounter.count("hello world"))
    }

    @Test
    fun `counts dense punctuation well above the characters-over-four estimate`() {
        val json = """{"a":1,"b":[2,3],"c":{"d":null}}"""
        assertTrue(
            "expected more tokens than the ${json.length / 4}-token estimate",
            TokenCounter.count(json) > json.length / 4,
        )
    }

    @Test
    fun `treats special tokens as ordinary text`() {
        assertTrue(TokenCounter.count("the file said <|endoftext|> and carried on") > 0)
    }
}
