package com.github.piotrszybicki.independentintelijaiplugin.agents;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import java.util.ArrayList;

/** Adds a worktree to the existing project without closing its window or chat panel. */
public final class AgentWorktreeProjectOpener {
    private AgentWorktreeProjectOpener() {}

    public static Project open(Project currentProject, Path targetPath) {
        VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetPath);
        if (root == null || !root.isDirectory()) return null;
        ModuleManager manager = ModuleManager.getInstance(currentProject);
        boolean attached = false;
        for (Module module : manager.getModules()) {
            for (VirtualFile contentRoot : ModuleRootManager.getInstance(module).getContentRoots()) {
                if (contentRoot.equals(root)) attached = true;
            }
        }
        if (!attached) {
            WriteAction.run(() -> {
                Module module = manager.newModule(
                    targetPath.resolve(".idea").resolve(targetPath.getFileName() + ".iml").toString(), "JAVA_MODULE");
                ModuleRootModificationUtil.updateModel(module, model -> {
                    var entry = model.addContentEntry(root);
                    entry.addExcludeFolder(root.getUrl() + "/.git");
                    entry.addExcludeFolder(root.getUrl() + "/.idea");
                    entry.addExcludeFolder(root.getUrl() + "/.gradle");
                    entry.addExcludeFolder(root.getUrl() + "/build");
                    model.inheritSdk();
                });
            });
        }
        var vcs = ProjectLevelVcsManager.getInstance(currentProject);
        var mappings = new ArrayList<>(vcs.getDirectoryMappings());
        if (mappings.stream().noneMatch(mapping -> mapping.getDirectory().equals(root.getPath()))) {
            mappings.add(new VcsDirectoryMapping(root.getPath(), "Git"));
            vcs.setDirectoryMappings(mappings);
        }
        return currentProject;
    }
}
