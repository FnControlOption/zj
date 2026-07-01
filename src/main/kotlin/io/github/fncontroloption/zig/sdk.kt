package io.github.fncontroloption.zig

import com.intellij.codeInsight.daemon.ProjectSdkSetupValidator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.SdkPopupFactory
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

class ZigSdkType : SdkType("ZigSdk") {
    private val log = Logger.getInstance(ZigSdkType::class.java)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun suggestHomePath() = null

    override fun suggestHomePath(path: Path) = null

    override fun isValidSdkHome(path: String): Boolean {
        val exeName = if (SystemInfoRt.isWindows) "zig.exe" else "zig"
        return Path(path, "lib", "std", "std.zig").exists() &&
                (Path(path, exeName).isExecutable() ||
                        Path(path, "bin", exeName).isExecutable())
    }

    override fun suggestSdkName(currentSdkName: String?, sdkHome: String) = "Zig SDK"

    override fun getPresentableName() = "Zig SDK"

    override fun createAdditionalDataConfigurable(sdkModel: SdkModel, sdkModificator: SdkModificator) = null

    override fun saveAdditionalData(additionalData: SdkAdditionalData, additional: Element) {}

    override fun getIcon() = ZigIcons.ZIG

    override fun getVersionString(sdkHome: String): String? {
        val exeName = if (SystemInfoRt.isWindows) "zig.exe" else "zig"
        return detectFromOutput(Path(sdkHome, exeName))
            ?: detectFromOutput(Path(sdkHome, "bin", exeName))
    }

    /** @see org.jetbrains.jps.model.java.impl.JdkVersionDetectorImpl.detectFromOutput */
    private fun detectFromOutput(zigExe: Path): String? {
        if (!zigExe.isExecutable()) {
            return null
        }
        val process = try {
            with(ProcessBuilder(zigExe.toString(), "version")) {
                redirectOutput(ProcessBuilder.Redirect.PIPE)
                redirectError(ProcessBuilder.Redirect.PIPE)
                start()
            }
        } catch (e: IOException) {
            log.error(zigExe.toString(), e)
            return null
        }
        try {
            process.waitFor()
        } catch (e: InterruptedException) {
            log.error(e)
            process.destroy()
            return null
        }
        return process.inputStream.bufferedReader().readText().trimEnd()
    }

    override fun setupSdkPaths(sdk: Sdk) {
        val std = sdk.homeDirectory?.findFileByRelativePath("lib/std")
        if (std == null) {
            log.error("Cannot find std directory")
            return
        }
        val modificator = sdk.sdkModificator
        modificator.addRoot(std, OrderRootType.CLASSES)
        modificator.addRoot(std, OrderRootType.SOURCES)
        ApplicationManager.getApplication().invokeAndWait {
            runWriteAction {
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
