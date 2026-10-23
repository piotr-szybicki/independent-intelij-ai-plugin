package com.github.piotrszybicki.independentintelijaiplugin.agents;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.ide.impl.OpenProjectTaskBuilder;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import kotlin.Unit;

/** Keeps the IDE's version-specific task construction out of plugin bytecode. */
public final class AgentWorktreeProjectOpener {
    private AgentWorktreeProjectOpener() {}

    public static Project open(Project currentProject, Path targetPath) {
        // Kotlin's public DSL inlines an internal constructor. Java calls the JVM builder
        // directly, so construction uses the running IDE's implementation instead.
        OpenProjectTask options = new OpenProjectTaskBuilder().build(builder -> {
            builder.setProjectToClose(currentProject);
            builder.setForceOpenInNewFrame(false);
            builder.setForceReuseFrame(true);
            return Unit.INSTANCE;
        });
        return ProjectUtil.openOrImport(targetPath, options);
    }
}
