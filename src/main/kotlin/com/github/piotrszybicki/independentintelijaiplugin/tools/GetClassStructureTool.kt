package com.github.piotrszybicki.independentintelijaiplugin.tools

import com.intellij.openapi.project.Project

class GetClassStructureTool(
    project: Project,
) : GetFileStructureTool(project, classMode = true)
