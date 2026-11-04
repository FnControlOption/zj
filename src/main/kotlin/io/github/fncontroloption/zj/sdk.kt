package io.github.fncontroloption.zj

import com.intellij.codeInsight.daemon.ProjectSdkSetupValidator
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.*
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.SdkPopupFactory
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.text.Strings
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.ide.progress.ModalTaskOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jdom.Element
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = Logger.getInstance(ZigSdkType::class.java)

class ZigSdkType : SdkType("ZigSdk") {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun suggestHomePath() = null

    override fun suggestHomePath(path: Path) = null

    override fun isValidSdkHome(path: String): Boolean {
        return Path(path, "lib", "std", "std.zig").exists() &&
                getPossibleZigExePaths(path).any { it.isExecutable() }
    }

    override fun suggestSdkName(currentSdkName: String?, sdkHome: String) = "Zig SDK"

    override fun getPresentableName() = "Zig SDK"

    override fun createAdditionalDataConfigurable(sdkModel: SdkModel, sdkModificator: SdkModificator) = null

    override fun saveAdditionalData(additionalData: SdkAdditionalData, additional: Element) {}

    override fun getIcon() = ZigIcons.ZIG

    override fun getVersionString(sdkHome: String): String? {
        val exe = ZigExe.find(sdkHome) ?: return null
        val result = runBlocking { exe.run("version", timeout = 10.milliseconds) }
        result.logErrors()
        if (!result.success) return null
        return result.stdout.trimEnd()
    }

    override fun setupSdkPaths(sdk: Sdk) {
        val lib = sdk.homeDirectory?.findChild("lib")
        if (lib == null || !lib.isValid || !lib.isDirectory) {
            log.error("Cannot find lib directory")
            return
        }
        val modificator = sdk.sdkModificator
        modificator.addRoot(lib, OrderRootType.CLASSES)
        modificator.addRoot(lib, OrderRootType.SOURCES)
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                modificator.commitChanges()
            }
        }
    }
}

class ZigProjectSdkSetupValidator : ProjectSdkSetupValidator {
    override fun isApplicableFor(project: Project, file: VirtualFile) =
        file.fileType == ZigFileType

    override fun getErrorMessage(project: Project, file: VirtualFile): String? {
        val module = ModuleUtilCore.findModuleForFile(file, project)
        if (module != null) {
            val roots = ModuleRootManager.getInstance(module)
            if (!roots.isSdkInherited) {
                return when (roots.sdk?.sdkType) {
                    is ZigSdkType -> null
                    null -> "Module SDK is not defined"
                    else -> "Module SDK is not a Zig SDK"
                }
            }
        }
        return when (ProjectRootManager.getInstance(project).projectSdk?.sdkType) {
            is ZigSdkType -> null
            null -> "Project SDK is not defined"
            else -> "Project SDK is not a Zig SDK"
        }
    }

    override fun getFixHandler(project: Project, file: VirtualFile) =
        SdkPopupFactory.newBuilder()
            .withProject(project)
            .withSdkTypeFilter { it is ZigSdkType }
            .updateSdkForFile(file)
            .buildEditorNotificationPanelHandler()
}

private fun getPossibleZigExePaths(sdkHome: String): Array<Path> {
    val exeName = if (SystemInfoRt.isWindows) "zig.exe" else "zig"
    return arrayOf(
        Path(sdkHome, exeName),
        Path(sdkHome, "bin", exeName),
    )
}

data class ZigExe(val path: Path) {
    class Message(
        val line: Int,
        val column: Int,
        val kind: Kind,
        val text: String,
    ) {
        enum class Kind { ERROR, NOTE }

        companion object {
            private val MESSAGE_REGEX = """\s*(.*):(\d+):(\d+): (error|note): (.*)\s*""".toRegex()

            fun parseAll(lines: Sequence<String>): Map<String, List<Message>> {
                val result = mutableMapOf<String, MutableList<Message>>()
                for (line in lines) {
                    // TODO: handle multiline message, f.e. duplicate struct member @"foo\nbar"
                    val match = MESSAGE_REGEX.matchEntire(line) ?: continue
                    val path = match.groupValues[1]
                    val line = match.groupValues[2].toInt() - 1
                    val column = match.groupValues[3].toInt() - 1
                    val kind = when (match.groupValues[4]) {
                        "error" -> Message.Kind.ERROR
                        "note" -> Message.Kind.NOTE
                        else -> throw IllegalStateException()
                    }
                    val text = Strings.capitalize(match.groupValues[5])
                    val messages = result.getOrPut(path) { mutableListOf() }
                    messages.add(Message(line, column, kind, text))
                }
                return result
            }
        }
    }

    class Result(
        val exe: Path,
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
    ) {
        val success: Boolean
            get() = exitCode == 0

        fun logErrors() {
            if (stderr.isNotEmpty()) {
                log.error(exe.toString(), stderr)
            }
        }

        val errorMessages: Map<String, List<Message>> by lazy {
            Message.parseAll(stderr.lineSequence())
        }
    }

    @Suppress("RedundantSuspendModifier")
    suspend fun run(vararg args: String, timeout: Duration? = null): Result {
        val processHandler = OSProcessHandler(
            GeneralCommandLine()
                .withExePath(path.toString())
                .withParameters(*args)
                .withCharset(Charsets.UTF_8)
        )
        val processListener = CapturingProcessAdapter()
        processHandler.addProcessListener(processListener)
        processHandler.startNotify()
        if (timeout != null) {
            processHandler.waitFor(timeout.inWholeMilliseconds)
        } else {
            processHandler.waitFor()
        }
        val exitCode = processHandler.exitCode
        val stdout = processListener.output.stdout
        val stderr = processListener.output.stderr
        return Result(path, stdout, stderr, exitCode)
    }

    suspend fun runInToolWindow(
        project: Project,
        tab: ZigToolWindowTab,
        args: List<String>,
        workingDirectory: Path?,
        processListener: ProcessListener,
        mostlySilent: Boolean,
    ): OSProcessHandler {
        val commandLine =
            if (tab.allowsAnsiEscapes)
                PtyCommandLine()
                    .withConsoleMode(false)
                    .withExePath(path.toString())
                    .withParameters(args)
                    .withWorkingDirectory(workingDirectory)
            else
                GeneralCommandLine()
                    .withExePath(path.toString())
                    .withParameters(args)
                    .withWorkingDirectory(workingDirectory)

        val processHandler =
            if (tab.allowsAnsiEscapes)
                if (mostlySilent)
                    KillableColoredProcessHandler.Silent(commandLine).apply { setShouldKillProcessSoftly(false) }
                else
                    KillableColoredProcessHandler(commandLine).apply { setShouldKillProcessSoftly(false) }
            else
                if (mostlySilent)
                    OSProcessHandler.Silent(commandLine)
                else
                    OSProcessHandler(commandLine)

        processHandler.addProcessListener(processListener)

        withContext(Dispatchers.EDT) {
            val console = getZigToolWindowConsole(project, tab)
            console?.attachToProcess(processHandler)
            processHandler.startNotify()
            activateZigToolWindow(project, tab)
        }

        return processHandler
    }

    suspend fun runInToolWindowAndWait(
        project: Project,
        tab: ZigToolWindowTab,
        args: List<String>,
        workingDirectory: Path?,
    ): Result {
        val processListener = CapturingProcessAdapter()
        val processHandler = runInToolWindow(project, tab, args, workingDirectory, processListener, false)
        try {
            while (!processHandler.waitFor(100)) {
                ProgressManager.checkCanceled()
            }
        } finally {
            if (!processHandler.isProcessTerminated) {
                processHandler.destroyProcess()
            }
        }
        val exitCode = processHandler.exitCode
        val stdout = processListener.output.stdout
        val stderr = processListener.output.stderr
        return Result(path, stdout, stderr, exitCode)
    }

    companion object {
        fun find(sdk: Sdk?): ZigExe? {
            if (sdk == null) return null
            if (sdk.sdkType !is ZigSdkType) return null
            val sdkHome = sdk.homePath ?: return null
            return find(sdkHome)
        }

        fun find(sdkHome: String): ZigExe? {
            for (path in getPossibleZigExePaths(sdkHome)) {
                if (path.isExecutable()) {
                    return ZigExe(path)
                }
            }
            return null
        }
    }
}

class ZigExeProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.workspaceModel.eventLog.collect {
            project.service<ZigExeService>().updateExePath()
        }
    }
}

@Service(Service.Level.PROJECT)
class ZigExeService(project: Project) {
    private val rootManager = ProjectRootManager.getInstance(project)
    private val exePathMutable = MutableStateFlow<Path?>(null)
    val exePath = exePathMutable.asStateFlow()
    fun updateExePath() {
        exePathMutable.value = ZigExe.find(rootManager.projectSdk)?.path
    }
}
