package com.github.piotrszybicki.independentintelijaiplugin

import com.intellij.openapi.components.Service

/**
 * Minimal service used to verify that the plugin descriptor and Kotlin code are wired together.
 */
@Service(Service.Level.APP)
class BasicWiringService {
    private val wiringMessage = "AICodingAgent wiring is ready"

    fun message(): String = wiringMessage
}
