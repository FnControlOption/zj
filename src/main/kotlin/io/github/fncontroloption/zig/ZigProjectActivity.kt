package io.github.fncontroloption.zig

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.backend.workspace.workspaceModel

class ZigProjectActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        readAction {
            project.service<ZigBuiltinFunctionsService>().loadBuiltinFunctions()
        }
        project.workspaceModel.eventLog.collect {
            project.service<ZigBuiltinService>().updateCachedBuiltinFile()
        }
    }
}
