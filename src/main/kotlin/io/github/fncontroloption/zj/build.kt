package io.github.fncontroloption.zj

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
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
        project.service<ZigBuildProjectService>()
        project.service<ZigBuildWatcher>()
    }
}

@Service(Service.Level.APP)
class ZigBuildAppService(coroutineScope: CoroutineScope) {
    val printBuildConfigProgramPath = coroutineScope.async {
        val pluginId = PluginId.getId("io.github.fncontroloption.zj")
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
    sealed interface FetchResult {
        object None : FetchResult
        data class Ok(val buildConfig: ZigBuildConfig) : FetchResult
        data class Err(val errorMessages: Map<String, List<ZigExe.Message>>) : FetchResult
    }

    private val projectPathFlow = project.service<ZigExternalSystemProjectService>().projectPath
    private val exePathFlow = project.service<ZigExeService>().exePath
    private val fetchRestartFlow = MutableSharedFlow<Unit>()
    private val initialFetch: Deferred<FetchResult> = CompletableDeferred(FetchResult.None)

    val deferredFetch =
        combine(projectPathFlow, exePathFlow, ::Pair)
            .combine(fetchRestartFlow) { (projectPath, exePath), _ ->
                coroutineScope.async { fetchProject(projectPath, exePath) }
            }
            .runningFold(initialFetch) { oldDeferred, newDeferred ->
                oldDeferred.cancel()
                newDeferred
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, initialFetch)

    suspend fun restartFetch() {
        fetchRestartFlow.emit(Unit)
    }

    private suspend fun fetchProject(projectPath: Path?, exePath: Path?): FetchResult {
        if (projectPath == null || exePath == null) return FetchResult.None
        val fetchResult = getBuildConfig(projectPath, exePath)
        when (fetchResult) {
            FetchResult.None -> {}
            is FetchResult.Err -> {}
            is FetchResult.Ok -> {
                val projectDir = VirtualFileManager.getInstance().findFileByNioPath(projectPath)
                projectDir?.refresh(false, true)
            }
        }
        return fetchResult
    }

    private suspend fun getBuildConfig(projectPath: Path, exePath: Path): FetchResult {
        val settings = project.service<ZigSettings>()
        val args = buildList {
            add("build")
            add("--print-configuration-path")
            addAll(settings.buildOptions)
            if (!contains("--build-file")) {
                add("--build-file")
                add(projectPath.resolve("build.zig").toString())
            }
        }
        val printResult = ZigExe(exePath).runInToolWindowAndWait(project, args)
        printResult.logErrors()
        if (!printResult.success) {
            return FetchResult.Err(printResult.errorMessages)
        }
        val configPath = printResult.stdout.trimEnd()
        val appService = ApplicationManager.getApplication().service<ZigBuildAppService>()
        val programPath = appService.printBuildConfigProgramPath.await().toString()
        val configResult = ZigExe(exePath).run("run", programPath, "--", configPath)
        configResult.logErrors()
        if (!configResult.success) {
            return FetchResult.Err(configResult.errorMessages)
        }
        val buildConfig = Json.decodeFromString<ZigBuildConfig>(configResult.stdout)
        return FetchResult.Ok(buildConfig)
    }
}

@Service(Service.Level.PROJECT)
class ZigBuildWatcher(
    private val project: Project,
    coroutineScope: CoroutineScope,
) : Disposable {
    private val daemonCodeAnalyzer = DaemonCodeAnalyzer.getInstance(project)
    private val projectPathFlow = project.service<ZigExternalSystemProjectService>().projectPath
    private val exePathFlow = project.service<ZigExeService>().exePath
    private val deferredFetchFlow = project.service<ZigBuildProjectService>().deferredFetch
    private val cachedProcessHandler =
        combine(projectPathFlow, exePathFlow, ::Pair)
            .combine(deferredFetchFlow) { (projectPath, exePath), deferredFetch ->
                messages = emptyMap()
                val fetchResult = try {
                    deferredFetch.await()
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    return@combine null
                }
                when (fetchResult) {
                    ZigBuildProjectService.FetchResult.None -> return@combine null
                    is ZigBuildProjectService.FetchResult.Err -> return@combine null
                    is ZigBuildProjectService.FetchResult.Ok -> {}
                }
                if (projectPath == null || exePath == null) return@combine null
                start(projectPath, exePath)
            }
            .runningFold(null as ProcessHandler?) { oldProcessHandler, newProcessHandler ->
                oldProcessHandler?.destroyProcess()
                newProcessHandler
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)

    override fun dispose() {
        cachedProcessHandler.value?.destroyProcess()
    }

    private suspend fun start(projectPath: Path, exePath: Path): ProcessHandler? {
        val settings = project.service<ZigSettings>()
        if (!settings.buildOnSave) {
            return null
        }
        val args = buildList {
            add("build")
            addAll(settings.buildArgsOnSave)
            if (!contains("--build-file")) {
                add("--build-file")
                add(projectPath.resolve("build.zig").toString())
            }
        }
        return ZigExe(exePath).runInToolWindow(project, args, Listener(), mostlySilent = true)
    }

    @Volatile
    var messages: Map<String, List<ZigExe.Message>> = emptyMap()
        private set

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
                    messages = ZigExe.Message.parseAll(cycle.asSequence())
                    daemonCodeAnalyzer.restart("ZigBuildWatcher.Listener.onTextAvailable")
                    cycle.clear()
                } else {
                    cycle.add(line)
                }
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            val parsed = ZigExe.Message.parseAll(cycle.asSequence())
            if (parsed.isNotEmpty()) {
                messages = parsed
                daemonCodeAnalyzer.restart("ZigBuildWatcher.Listener.processTerminated")
            }
            cycle.clear()
        }
    }
}
