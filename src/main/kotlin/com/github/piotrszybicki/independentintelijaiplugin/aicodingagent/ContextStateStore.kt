package com.github.piotrszybicki.independentintelijaiplugin.aicodingagent

interface ContextStateStore {
    fun snapshot(): ContextMeter.State

    fun restore(state: ContextMeter.State?)
}
