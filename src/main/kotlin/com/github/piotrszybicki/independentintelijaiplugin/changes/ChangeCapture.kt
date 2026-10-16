package com.github.piotrszybicki.independentintelijaiplugin.changes

interface ChangeCapture {
    fun beginCapture()

    fun endCapture()

    fun flushToDisk()
}
