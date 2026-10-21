package io.github.fncontroloption.zig

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.coroutines.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.concurrent.Volatile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class ZigBuildConfig(
    val steps: List<String>,
    val modules: List<Module>
) {
    @Serializable
    data class Module(
        val index: Int,

        @SerialName("is_root_module")
        val isRootModule: Boolean,

        @SerialName("root_source_file")
        val rootSourceFile: String?,

        @SerialName("import_table")
        val importTable: Map<String, Int>,
    )
}

class ZigBuildProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val projectService = project.service<ZigBuildProjectService>()
        projectService.fetchProjectAsync()

        val buildWatcher = project.service<ZigBuildWatcher>()
        buildWatcher.startAsync()

        project.workspaceModel.eventLog.collect {
            projectService.fetchProjectAsync()
            buildWatcher.startAsync()
        }
    }
}

@Service(Service.Level.APP)
class ZigBuildAppService(coroutineScope: CoroutineScope) {
    val printBuildConfigProgramPath = coroutineScope.async {
        val pluginId = PluginId.getId("io.github.fncontroloption.zig")
        val pluginDescriptor = PluginManagerCore.getPlugin(pluginId)!!
        val outputPath = pluginDescriptor.pluginPath.resolve("print_build_config.zig")
        val classLoader = ZigBuildAppService::class.java.classLoader
        val inputStream = classLoader.getResourceAsStream("print_build_config.zig")!!
        val inputText = inputStream.reader().use { it.readText() }
        if (!outputPath.exists() || outputPath.readText() != inputText) {
            outputPath.writeText(inputText)
        }
        outputPath
    }
}

@Service(Service.Level.PROJECT)
class ZigBuildProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    @Volatile
    private var cachedConfig: ZigBuildConfig? = null

    val config: ZigBuildConfig?
        get() = cachedConfig

    @Volatile
    private var cachedErrorMessages: Map<String, List<ZigExe.Message>> = emptyMap()

    val errorMessages: Map<String, List<ZigExe.Message>>
        get() = cachedErrorMessages

    data class FetchSettings(val projectPath: Path, val zigExe: ZigExe)

    @Volatile
    private var cachedFetchSettings: FetchSettings? = null

    val fetchSettings: FetchSettings?
        get() = cachedFetchSettings

    fun resetFetchSettings() {
        cachedFetchSettings = null
    }

    @Volatile
    private var fetchJob: Job? = null

    @Synchronized
    fun fetchProjectAsync(): Job {
        val projectPath = findZigProjectPath(project)
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        val zigExe = sdk?.let { ZigExe.find(it) }
        val fetchSettings = if (projectPath != null && zigExe != null) FetchSettings(projectPath, zigExe) else null
        var job = fetchJob
        if (job != null) {
            if (fetchSettings == cachedFetchSettings) {
                return job
            }
            job.cancel()
        }
        cachedFetchSettings = fetchSettings
        cachedConfig = null
        cachedErrorMessages = emptyMap()
        job = coroutineScope.launch(Dispatchers.IO) {
            if (fetchSettings != null) {
                fetchProject(fetchSettings)
            }
        }
        fetchJob = job
        return job
    }

    private suspend fun fetchProject(fetchSettings: FetchSettings) {
        val configPath = getConfigPath(fetchSettings) ?: return
        cachedConfig = getConfig(fetchSettings, configPath) ?: return
        val projectDir = VirtualFileManager.getInstance().findFileByNioPath(fetchSettings.projectPath)
        projectDir?.refresh(false, true)
    }

    private suspend fun getConfigPath(fetchSettings: FetchSettings): String? {
        val settings = project.service<ZigSettings>()
        val args = buildList {
            add("build")
            add("--print-configuration-path")
            addAll(settings.buildOptions)
            add("--build-file")
            add(fetchSettings.projectPath.resolve("build.zig").toString())
        }
        val printResult = fetchSettings.zigExe.runInToolWindowAndWait(project, args)
        printResult.logErrors()
        if (!printResult.success) {
            cachedErrorMessages = printResult.errorMessages
            DaemonCodeAnalyzer.getInstance(project).restart("ZigBuildProjectService.getConfigPath")
            return null
        }
        return printResult.stdout.trimEnd()
    }

    private suspend fun getConfig(fetchSettings: FetchSettings, configPath: String): ZigBuildConfig? {
        val appService = ApplicationManager.getApplication().service<ZigBuildAppService>()
        val programPath = appService.printBuildConfigProgramPath.await().toString()
        val configResult = fetchSettings.zigExe.run("run", programPath, "--", configPath)
        configResult.logErrors()
        if (!configResult.success) return null
        return Json.decodeFromString<ZigBuildConfig>(configResult.stdout)
    }
}

@Service(Service.Level.PROJECT)
class ZigBuildWatcher(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    @Volatile
    private var cachedProcessHandler: ProcessHandler? = null

    override fun dispose() {
        cachedProcessHandler?.destroyProcess()
    }

    @Volatile
    private var cachedFetchJob: Job? = null

    @Volatile
    private var startJob: Job? = null

    @Synchronized
    fun startAsync(): Job {
        val buildService = project.service<ZigBuildProjectService>()
        val fetchJob = buildService.fetchProjectAsync()
        var job = startJob
        if (job != null && cachedProcessHandler?.isProcessTerminated != true) {
            if (fetchJob == cachedFetchJob) {
                return job
            }
            job.cancel()
        }
        cachedFetchJob = fetchJob
        cachedProcessHandler?.destroyProcess()
        cachedProcessHandler = null
        snapshot = null
        job = coroutineScope.launch(Dispatchers.IO) {
            fetchJob.join()
            start()
        }
        startJob = job
        return job
    }

    private suspend fun start() {
        val settings = project.service<ZigSettings>()
        if (!settings.buildOnSave) {
            return
        }
        val buildService = project.service<ZigBuildProjectService>()
        if (buildService.config == null) {
            return
        }
        val fetchSettings = buildService.fetchSettings ?: return
        val args = buildList {
            add("build")
            addAll(settings.buildArgsOnSave)
            addAll(settings.buildOptions)
            add("--build-file")
            add(fetchSettings.projectPath.resolve("build.zig").toString())
        }
        cachedProcessHandler = fetchSettings.zigExe.runInToolWindow(project, args, Listener(), mostlySilent = true)
    }

    private val psiModificationTracker = PsiModificationTracker.getInstance(project).forLanguage(ZigLanguage)

    inner class Snapshot(val messages: Map<String, List<ZigExe.Message>>) {
        private val stamp = psiModificationTracker.modificationCount
        val isOutdated: Boolean
            get() = stamp < psiModificationTracker.modificationCount
    }

    @Volatile
    private var snapshot: Snapshot? = null

    val messages: Map<String, List<ZigExe.Message>>
        get() = snapshot?.messages.orEmpty()

    val isOutdated: Boolean
        get() = snapshot?.isOutdated ?: true

    private inner class Listener : ProcessListener {
        private val partial = StringBuilder()
        private val cycle = mutableListOf<String>()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            if (outputType != ProcessOutputType.STDERR) {
                return
            }
            partial.append(event.text)
            while (true) {
                val newline = partial.indexOf('\n')
                if (newline < 0) {
                    break
                }
                val line = partial.substring(0, newline)
                partial.delete(0, newline + 1)
                if (line.startsWith("Build Summary: ")) {
                    val messages = ZigExe.Message.parseAll(cycle.asSequence())
                    snapshot = Snapshot(messages)
                    cycle.clear()
                } else {
                    cycle.add(line)
                }
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            val messages = ZigExe.Message.parseAll(cycle.asSequence())
            if (messages.isNotEmpty()) {
                snapshot = Snapshot(messages)
            }
            cycle.clear()
        }
    }
}
