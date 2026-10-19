package io.github.fncontroloption.zig

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.autoimport.*
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.EventDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.*

val ZIG_SYSTEM_ID = ProjectSystemId("ZIG")

@Service(Service.Level.PROJECT)
private class ZigExternalSystemProjectService(val coroutineScope: CoroutineScope)

class ZigExternalSystemProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // TODO: detect deletion/creation of build.zig
        ZigExternalSystemProjectAware.register(project)
    }
}

class ZigExternalSystemProjectAware(
    private val project: Project,
    private val projectPath: Path,
) : ExternalSystemProjectAware {
    companion object {
        fun register(project: Project) {
            val projectPath = findZigProjectPath(project) ?: return
            val projectAware = ZigExternalSystemProjectAware(project, projectPath)
            val projectTracker = ExternalSystemProjectTracker.getInstance(project)
            projectTracker.register(projectAware)
            projectTracker.activate(projectAware.projectId)
        }
    }

    override val projectId = ExternalSystemProjectId(ZIG_SYSTEM_ID, projectPath.toString())

    override val settingsFiles: Set<String>
        get() {
            val result = mutableSetOf(
                projectPath.resolve("build.zig").toString(),
                projectPath.resolve("build.zig.zon").toString(),
            )
            // TODO: follow build.zig imports
            return result
        }

    private val dispatcher = EventDispatcher.create(ExternalSystemProjectEventListener::class.java)

    override fun subscribe(listener: ExternalSystemProjectListener, parentDisposable: Disposable) {
        val adapter = ExternalSystemProjectEventListenerAdapter(listener)
        dispatcher.addListener(adapter, parentDisposable)
    }

    override fun reloadProject(context: ExternalSystemProjectReloadContext) {
        project.service<ZigExternalSystemProjectService>().coroutineScope.launch {
            dispatcher.multicaster.onProjectReloadStart()

            edtWriteAction { FileDocumentManager.getInstance().saveAllDocuments() }

            val buildService = project.service<ZigBuildProjectService>()
            buildService.resetFetchSettings()
            buildService.fetchProjectAsync().join()

            val buildWatcher = project.service<ZigBuildWatcher>()
            buildWatcher.startAsync().join()

            val modulesService = project.service<ZigModulesProjectService>()
            modulesService.reloadModulesAsync().join()

            dispatcher.multicaster.onProjectReloadFinish(ExternalSystemRefreshStatus.SUCCESS)
        }
    }
}

private interface ExternalSystemProjectEventListener : EventListener {
    fun onProjectReloadStart()
    fun onProjectReloadFinish(status: ExternalSystemRefreshStatus)
    fun onSettingsFilesListChange()
}

private class ExternalSystemProjectEventListenerAdapter(
    val listener: ExternalSystemProjectListener
) : ExternalSystemProjectEventListener {
    override fun onProjectReloadStart() = listener.onProjectReloadStart()
    override fun onProjectReloadFinish(status: ExternalSystemRefreshStatus) = listener.onProjectReloadFinish(status)
    override fun onSettingsFilesListChange() = listener.onSettingsFilesListChange()
}
