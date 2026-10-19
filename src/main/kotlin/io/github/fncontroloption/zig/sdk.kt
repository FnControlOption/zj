package io.github.fncontroloption.zig

import com.intellij.codeInsight.daemon.ProjectSdkSetupValidator
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.*
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.SdkPopupFactory
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.text.Strings
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDirectory
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdom.Element
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

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
        val result = exe.run("version")
        result.logErrors()
        if (!result.success) return null
        return result.stdout.trimEnd()
    }

    override fun setupSdkPaths(sdk: Sdk) {
        val std = sdk.homeDirectory?.findDirectory("lib/std")
        if (std == null) {
            log.error("Cannot find std directory")
            return
        }
        val modificator = sdk.sdkModificator
        modificator.addRoot(std, OrderRootType.CLASSES)
        modificator.addRoot(std, OrderRootType.SOURCES)
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

    fun run(vararg args: String): Result {
        val processHandler = OSProcessHandler(
            GeneralCommandLine()
                .withExePath(path.toString())
                .withParameters(*args)
                .withCharset(Charsets.UTF_8)
        )
        val processListener = CapturingProcessAdapter()
        processHandler.addProcessListener(processListener)
        processHandler.startNotify()
        processHandler.waitFor()
        val exitCode = processHandler.exitCode
        val stdout = processListener.output.stdout
        val stderr = processListener.output.stderr
        return Result(path, stdout, stderr, exitCode)
    }

    suspend fun runInToolWindow(
        project: Project,
        args: List<String>,
        processListener: ProcessListener,
        mostlySilent: Boolean,
    ): OSProcessHandler {
        val commandLine =
            GeneralCommandLine()
                .withExePath(path.toString())
                .withParameters(args)
                .withCharset(Charsets.UTF_8)

        val processHandler =
            if (mostlySilent)
                OSProcessHandler.Silent(commandLine)
            else
                OSProcessHandler(commandLine)

        processHandler.addProcessListener(processListener)

        withContext(Dispatchers.EDT) {
            val console = getZigBuildConsole(project)
            console?.attachToProcess(processHandler)
            processHandler.startNotify()
        }

        return processHandler
    }

    suspend fun runInToolWindowAndWait(
        project: Project,
        args: List<String>,
    ): Result {
        val processListener = CapturingProcessAdapter()
        val processHandler = runInToolWindow(project, args, processListener, mostlySilent = false)
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
        if (exitCode != 0) {
            withContext(Dispatchers.EDT) {
                val toolWindowManager = ToolWindowManager.getInstance(project)
                val toolWindow = toolWindowManager.getToolWindow("Zig")
                toolWindow?.activate(null)
            }
        }
        val stdout = processListener.output.stdout
        val stderr = processListener.output.stderr
        return Result(path, stdout, stderr, exitCode)
    }

    companion object {
        fun find(sdk: Sdk): ZigExe? {
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
