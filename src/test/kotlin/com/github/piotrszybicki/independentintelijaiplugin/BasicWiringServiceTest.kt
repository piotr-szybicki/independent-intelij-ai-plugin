package com.github.piotrszybicki.independentintelijaiplugin

import com.intellij.openapi.application.ApplicationManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class BasicWiringServiceTest {
    @Test
    fun applicationServiceReturnsItsWiringMessage() {
        val service = ApplicationManager.getApplication().getService(BasicWiringService::class.java)

        assertThat(service).isNotNull
        assertThat(service.message()).isEqualTo("AICodingAgent wiring is ready")
    }
}
