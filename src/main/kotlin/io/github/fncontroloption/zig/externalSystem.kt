package io.github.fncontroloption.zig

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.autoimport.*
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.findFileOrDirectory
import com.intellij.openapi.vfs.isFile
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.util.EventDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.*

val ZIG_SYSTEM_ID = ProjectSystemId("ZIG")

class ZigExternalSystemProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.workspaceModel.eventLog.collect {
            for (project in ProjectManager.getInstance().openProjects) {
                val projectService = project.service<ZigExternalSystemProjectService>()
                projectService.updateProjectPath()
            }
        }
    }
}

class ZigExternalSystemAsyncFileListener : AsyncFileListener {
    override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier {
        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                for (project in ProjectManager.getInstance().openProjects) {
                    val projectService = project.service<ZigExternalSystemProjectService>()
                    projectService.updateProjectPath()

                    val settings = project.service<ZigSettings>()
                    if (settings.buildOnSave) {
                        ExternalSystemProjectTracker.getInstance(project).scheduleProjectRefresh()
                    }
                }
            }
        }
    }
}

@Service(Service.Level.PROJECT)
class ZigExternalSystemProjectService(
    private val project: Project,
    coroutineScope: CoroutineScope,
) {
    private val rootManager = ProjectRootManager.getInstance(project)
    private val projectTracker = ExternalSystemProjectTracker.getInstance(project)
    private var projectPathMutable = MutableStateFlow<Path?>(null)
    val projectPath = projectPathMutable.asStateFlow()

    init {
        projectPath.runningFold(null as ZigExternalSystemProjectAware?) { oldProjectAware, path ->
            oldProjectAware?.let { projectTracker.remove(it.projectId) }
            if (path == null) return@runningFold null
            val newProjectAware = ZigExternalSystemProjectAware(project, path)
            projectTracker.register(newProjectAware)
            projectTracker.activate(newProjectAware.projectId)
            projectTracker.markDirty(newProjectAware.projectId)
            projectTracker.scheduleProjectRefresh()
            newProjectAware
        }.launchIn(coroutineScope)
    }

    fun updateProjectPath() {
        projectPathMutable.value =
            rootManager
                .contentRoots
                .asSequence()
                .filter { it.findFileOrDirectory("build.zig")?.isFile == true }
                .firstNotNullOfOrNull { it.toNioPathOrNull() }
    }
}

class ZigExternalSystemProjectAware(
    private val project: Project,
    private val projectPath: Path,
) : ExternalSystemProjectAware {
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
        CoroutineScope(Dispatchers.Default).launch {
            dispatcher.multicaster.onProjectReloadStart()

            edtWriteAction { FileDocumentManager.getInstance().saveAllDocuments() }

            project.service<ZigBuildProjectService>().restartFetch()

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
