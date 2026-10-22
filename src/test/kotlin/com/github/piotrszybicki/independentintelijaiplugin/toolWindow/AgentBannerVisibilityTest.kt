package com.github.piotrszybicki.independentintelijaiplugin.toolWindow

import com.github.piotrszybicki.independentintelijaiplugin.agents.AgentSession
import com.github.piotrszybicki.independentintelijaiplugin.ui.assertVisible
import com.github.piotrszybicki.independentintelijaiplugin.ui.findComponentByName
import com.github.piotrszybicki.independentintelijaiplugin.ui.runOnEdt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBannerVisibilityTest {
    @Test
    fun `agent banner and optional controls follow session state`() =
        runOnEdt {
            val banner = AgentBanner({}, {})
            banner.component.name = "chat.agentBanner"
            val spec = banner.component.findComponentByName("chat.agentSpecButton")!!
            val parent = banner.component.findComponentByName("chat.parentChatButton")!!

            banner.show(null)
            assertFalse(banner.component.isVisible)

            banner.show(AgentSession("builder", null, null))
            assertTrue(banner.component.isVisible)
            assertFalse(spec.isVisible)
            assertFalse(parent.isVisible)

            banner.show(AgentSession("builder", "parent", "/tmp/spec.md"))
            banner.component.assertVisible("Agent banner")
            spec.assertVisible("Agent specification button")
            parent.assertVisible("Parent chat button")
        }
}
