package com.github.piotrszybicki.independentintelijaiplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.assertj.core.api.Assertions.assertThat

class BasicWiringServiceTest : BasePlatformTestCase() {
    fun testApplicationServiceReturnsItsWiringMessage() {
        val service = ApplicationManager.getApplication().getService(BasicWiringService::class.java)

        assertThat(service).isNotNull
        assertThat(service.message()).isEqualTo("AICodingAgent wiring is ready")
    }
}
