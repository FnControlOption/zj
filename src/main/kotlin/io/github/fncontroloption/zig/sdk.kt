package io.github.fncontroloption.zig

import com.intellij.codeInsight.daemon.ProjectSdkSetupValidator
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.*
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.SdkPopupFactory
import com.intellij.openapi.util.NlsContexts.TabTitle
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDirectory
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import org.jdom.Element
import java.io.IOException
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
        val result = exe.run("version") ?: return null
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

class ZigExeResult(
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
}

class ZigExe(val path: Path) {
    /** @see org.jetbrains.jps.model.java.impl.JdkVersionDetectorImpl.detectFromOutput */
    fun run(vararg args: String): ZigExeResult? {
        // Redirect output to temporary files so that we don't have to worry
        // about stdout/stderr buffers becoming full.
        val stdout = FileUtilRt.createTempFile("zig.", ".stdout", true)
        val stderr = FileUtilRt.createTempFile("zig.", ".stderr", true)

        val process = try {
            with(ProcessBuilder(path.toString(), *args)) {
                redirectOutput(stdout)
                redirectError(stderr)
                start()
            }
        } catch (e: IOException) {
            log.error(path.toString(), e)
            return null
        }
        val exitCode: Int
        try {
            exitCode = process.waitFor()
        } catch (e: InterruptedException) {
            log.error(path.toString(), e)
            process.destroy()
            return null
        }
        return ZigExeResult(path, stdout.readText(), stderr.readText(), exitCode)
    }

    suspend fun runInToolWindow(
        project: Project,
        @TabTitle title: String,
        args: List<String>,
    ): ZigExeResult {
        val processHandler = OSProcessHandler(
            GeneralCommandLine()
                .withExePath(path.toString())
                .withParameters(args)
                .withCharset(Charsets.UTF_8)
        )
        val processListener = CapturingProcessAdapter()
        processHandler.addProcessListener(processListener)
        edtWriteAction {
            RunContentExecutor(project, processHandler)
                .withActivateToolWindow(false)
                .withTitle(title)
                .run()
        }
        processHandler.waitFor()
        val exitCode = processHandler.exitCode
        if (exitCode != 0) {
            edtWriteAction {
                val toolWindowManager = ToolWindowManager.getInstance(project)
                val toolWindow = toolWindowManager.getToolWindow(ToolWindowId.RUN)
                toolWindow?.activate(null)
            }
        }
        val stdout = processListener.output.stdout
        val stderr = processListener.output.stderr
        return ZigExeResult(path, stdout, stderr, exitCode)
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
