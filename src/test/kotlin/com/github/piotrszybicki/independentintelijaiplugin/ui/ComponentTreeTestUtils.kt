package com.github.piotrszybicki.independentintelijaiplugin.ui

import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container
import javax.swing.SwingUtilities

fun Container.findComponentByName(name: String): Component? {
    if (this.name == name) return this
    for (child in components) {
        if (child.name == name) return child
        if (child is Container) {
            child.findComponentByName(name)?.let { return it }
        }
    }
    return null
}

inline fun <reified T : Component> Container.requireComponent(name: String): T {
    val component =
        findComponentByName(name)
            ?: throw AssertionError("Could not find component named '$name'. Tree:\n${describeTree()}")
    if (!T::class.java.isInstance(component)) {
        throw AssertionError("Component '$name' is ${component.javaClass.name}, expected ${T::class.java.name}.")
    }
    return T::class.java.cast(component)
}

fun Component.assertVisible(description: String) {
    assertTrue("$description should be visible", isVisible)
}

fun Component.assertEnabled(description: String) {
    assertTrue("$description should be enabled", isEnabled)
}

fun <T> runOnEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}

fun Container.describeTree(indent: String = ""): String =
    buildString {
        append(indent)
            .append(javaClass.simpleName)
            .append(" name=")
            .append(name)
            .append('\n')
        components.forEach { child ->
            append(indent)
                .append("  ")
                .append(child.javaClass.simpleName)
                .append(" name=")
                .append(child.name)
                .append('\n')
            if (child is Container) append(child.describeTree("$indent  "))
        }
    }
