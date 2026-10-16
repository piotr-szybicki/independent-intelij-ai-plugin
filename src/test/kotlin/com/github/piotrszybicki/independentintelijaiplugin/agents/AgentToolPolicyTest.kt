package com.github.piotrszybicki.independentintelijaiplugin.agents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolPolicyTest {
    @Test
    fun `no value inherits what the chat already has`() {
        assertTrue(AgentToolPolicy.parse(null).saysNothing)
    }

    @Test
    fun `a star takes every tool rather than inheriting`() {
        val policy = AgentToolPolicy.parse("*")
        assertFalse(policy.saysNothing)
        assertTrue(policy.keeps("edit_file_lines"))
        assertTrue(policy.keeps("run_shell_command"))
    }

    @Test
    fun `a list keeps only what it names`() {
        val policy = AgentToolPolicy.parse("read_project_file, find_in_files")
        assertTrue(policy.keeps("read_project_file"))
        assertFalse(policy.keeps("delete_file"))
    }

    @Test
    fun `a minus takes one tool away from all of them`() {
        val policy = AgentToolPolicy.parse("*, -run_shell_command")
        assertTrue(policy.keeps("edit_file_lines"))
        assertFalse(policy.keeps("run_shell_command"))
    }

    @Test
    fun `a minus on its own still keeps the rest`() {
        val policy = AgentToolPolicy.parse("-delete_file")
        assertFalse(policy.saysNothing)
        assertTrue(policy.keeps("create_file"))
        assertFalse(policy.keeps("delete_file"))
    }

    @Test
    fun `a tool list written for an agent is exactly what that agent gets`() {
        val policy =
            AgentToolPolicy.of(
                listOf("read_project_file", "create_file", "edit_file_lines", "run_shell_command"),
            )
        assertFalse(policy.saysNothing)
        assertTrue(policy.keeps("create_file"))
        assertFalse("nothing outside the list", policy.keeps("delete_file"))
    }

    @Test
    fun `a reading-only agent is one that never lists a writing tool`() {
        val policy = AgentToolPolicy.of(listOf("read_project_file", "find_in_files", "git_diff"))
        assertTrue(policy.keeps("read_project_file"))
        listOf("create_file", "edit_file_lines", "delete_file", "safe_delete").forEach {
            assertFalse(it, policy.keeps(it))
        }
    }
}
