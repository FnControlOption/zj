package io.github.fncontroloption.zj

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.*

val ZIG_SYSTEM_ID = ProjectSystemId("ZIG")

class ZigExternalSystemProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ZigExternalSystemProjectService>()
    }
}

@Service(Service.Level.PROJECT)
class ZigExternalSystemProjectService(
    private val project: Project,
    coroutineScope: CoroutineScope,
) {
    private val projectTracker = ExternalSystemProjectTracker.getInstance(project)
    private val projectPathFlow = project.service<ZigProjectPathService>().projectPath

    init {
        projectPathFlow.runningFold(null as ZigExternalSystemProjectAware?) { oldProjectAware, path ->
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
