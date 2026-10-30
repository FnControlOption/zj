package io.github.fncontroloption.zj

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.backend.workspace.workspaceModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class ZigProjectPathService(project: Project) {
    private val rootManager = ProjectRootManager.getInstance(project)
    private var projectPathMutable = MutableStateFlow<Path?>(null)
    val projectPath = projectPathMutable.asStateFlow()

    fun updateProjectPath() {
        projectPathMutable.value =
            rootManager
                .contentRoots
                .asSequence()
                .filter {
                    val buildFile = it.findChild("build.zig")
                    buildFile != null && buildFile.isValid && !buildFile.isDirectory
                }
                .firstNotNullOfOrNull { it.toNioPathOrNull() }
    }
}

class ZigProjectPathActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.workspaceModel.eventLog.collect {
            for (project in ProjectManager.getInstance().openProjects) {
                val projectService = project.service<ZigProjectPathService>()
                projectService.updateProjectPath()
            }
        }
    }
}

class ZigProjectPathFileListener : AsyncFileListener {
    override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier {
        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                for (project in ProjectManager.getInstance().openProjects) {
                    val projectService = project.service<ZigProjectPathService>()
                    projectService.updateProjectPath()
                }
            }
        }
    }
}
