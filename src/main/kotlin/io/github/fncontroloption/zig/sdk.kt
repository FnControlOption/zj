package io.github.fncontroloption.zig

import com.intellij.codeInsight.daemon.ProjectSdkSetupValidator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.*
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.SdkPopupFactory
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDirectory
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
        return getZigExeOutput(sdkHome, "version")?.trimEnd()
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

internal fun getZigExeOutput(sdk: Sdk, vararg args: String): String? {
    if (sdk.sdkType is ZigSdkType) {
        val sdkHome = sdk.homePath ?: return null
        return getZigExeOutput(sdkHome, *args)
    }
    return null
}

/** @see org.jetbrains.jps.model.java.impl.JdkVersionDetectorImpl.detectFromOutput */
private fun getZigExeOutput(sdkHome: String, vararg args: String): String? {
    for (zigExe in getPossibleZigExePaths(sdkHome)) {
        if (!zigExe.isExecutable()) {
            continue
        }

        // Redirect output to temporary files so that we don't have to worry
        // about stdout/stderr buffers becoming full.
        val stdout = FileUtilRt.createTempFile("zig.", ".stdout", true)
        val stderr = FileUtilRt.createTempFile("zig.", ".stderr", true)

        val process = try {
            with(ProcessBuilder(zigExe.toString(), *args)) {
                redirectOutput(stdout)
                redirectError(stderr)
                start()
            }
        } catch (e: IOException) {
            log.error(zigExe.toString(), e)
            return null
        }
        val exitCode: Int
        try {
            exitCode = process.waitFor()
        } catch (e: InterruptedException) {
            log.error(zigExe.toString(), e)
            process.destroy()
            return null
        }
        if (stderr.length() > 0) {
            log.error(zigExe.toString(), stderr.readText())
        }
        if (exitCode != 0) {
            return null
        }
        return stdout.readText()
    }
    return null
}
