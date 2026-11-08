package io.github.fncontroloption.zj

import com.intellij.codeInsight.daemon.ProjectSdkSetupValidator
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectBundle
import com.intellij.openapi.projectRoots.*
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.SdkPopupFactory
import com.intellij.openapi.roots.ui.configuration.projectRoot.SdkDownload
import com.intellij.openapi.roots.ui.configuration.projectRoot.SdkDownloadTask
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.io.OSAgnosticPathUtil
import com.intellij.openapi.util.text.Strings
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.ui.components.textFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.io.Decompressor
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.createDirectories
import com.intellij.util.io.delete
import com.intellij.util.system.CpuArch
import com.intellij.util.system.OS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jdom.Element
import java.awt.Component
import java.nio.file.Path
import java.util.function.Consumer
import javax.swing.JComponent
import kotlin.io.path.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = Logger.getInstance(ZigSdkType::class.java)

class ZigSdkType : SdkType("ZigSdk") {

    @Suppress("OVERRIDE_DEPRECATION", "REMOVAL")
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
        val result = runBlocking { exe.run("version", timeout = 50.milliseconds) }
        result.logErrors()
        if (!result.success) return "" // TODO: try to detect Zig version without running `zig version`
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
        ApplicationManager.getApplication().invokeLater {
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

/** @see com.intellij.openapi.projectRoots.impl.jdkDownloader.JdkDownload */
class ZigSdkDownload : SdkDownload {
    private val currentArch: String?
        get() = when (CpuArch.CURRENT) {
            CpuArch.X86 -> "x86"
            CpuArch.X86_64 -> "x86_64"
            CpuArch.ARM32 -> "arm"
            CpuArch.ARM64 -> "aarch64"
            CpuArch.OTHER -> null
            CpuArch.UNKNOWN -> null
        }

    private val currentOS: String?
        get() = when (OS.CURRENT) {
            OS.Windows -> "windows"
            OS.macOS -> "macos"
            OS.Linux -> "linux"
            OS.FreeBSD -> "freebsd"
            OS.OHOS -> null
            OS.Other -> null
        }

    private val packageType: ZigSdkPackageType
        get() = when (OS.CURRENT) {
            OS.Windows -> ZigSdkPackageType.ZIP
            else -> ZigSdkPackageType.TAR_XZ
        }

    override fun supportsDownload(sdkTypeId: SdkTypeId) =
        sdkTypeId is ZigSdkType

    /* TODO
    override fun pickSdk(
        sdkTypeId: SdkTypeId,
        sdkModel: SdkModel,
        parentComponent: JComponent,
        selectedSdk: Sdk?
    ): SdkDownloadTask? {
        val arch = currentArch ?: return null
        val os = currentOS ?: return null
        val dataContext = DataManager.getInstance().getDataContext(parentComponent)
        val project = CommonDataKeys.PROJECT.getData(dataContext)
        if (project?.isDisposed == true) return null
        val dialog = ZigSdkDownloadDialog(project, parentComponent, "Select")
        val (version, location) = dialog.selectVersionAndLocation() ?: return null
        return ZigSdkDownloadTask(arch, os, version, packageType, location)
    }
     */

    override fun showDownloadUI(
        sdkTypeId: SdkTypeId,
        sdkModel: SdkModel,
        parentComponent: JComponent,
        selectedSdk: Sdk?,
        sdkCreatedCallback: Consumer<in SdkDownloadTask>
    ) {
        val arch = currentArch ?: return
        val os = currentOS ?: return
        val dataContext = DataManager.getInstance().getDataContext(parentComponent)
        val project = CommonDataKeys.PROJECT.getData(dataContext)
        if (project?.isDisposed == true) return
        val dialog = ZigSdkDownloadDialog(project, parentComponent, "Download")
        val (version, location) = dialog.selectVersionAndLocation() ?: return
        sdkCreatedCallback.accept(ZigSdkDownloadTask(arch, os, version, packageType, location))
    }
}

/** @see com.intellij.openapi.projectRoots.impl.jdkDownloader.JdkDownloadDialog */
private class ZigSdkDownloadDialog(
    project: Project?,
    parentComponent: Component?,
    okActionText: @NlsContexts.Button String,
) : DialogWrapper(project, parentComponent, false, IdeModalityType.IDE) {
    private lateinit var versionComboBox: ComboBox<String>
    private lateinit var locationField: TextFieldWithBrowseButton

    private val panel = panel {
        row("Version:") {
            versionComboBox =
                comboBox(listOf("0.17.1", "0.17.0")) // TODO
                    .align(AlignX.FILL)
                    .component
        }
        row("Location:") {
            val descriptor =
                FileChooserDescriptorFactory
                    .createSingleFolderDescriptor()
                    .withTitle("Select Path to Install Zig")
            locationField = textFieldWithBrowseButton(project, descriptor)
            locationField.textField.columns = 36
            cell(locationField).align(AlignX.FILL)
        }
    }

    init {
        title = "Download Zig"
        isResizable = false
        setOKButtonText(okActionText)
        init()
    }

    override fun createCenterPanel() = panel

    override fun continuousValidation() = true

    override fun getContinuousValidationThreadToUse() = Alarm.ThreadToUse.POOLED_THREAD

    override fun doValidate(): ValidationInfo? {
        val (_, error) = validateLocation(locationField.text)
        return error?.let { ValidationInfo(it, locationField.textField) }
    }

    fun selectVersionAndLocation(): Pair<String, Path>? {
        if (!showAndGet()) return null
        val version = versionComboBox.selectedItem as String
        val (location, _) = validateLocation(locationField.text)
        if (location == null) return null
        return Pair(version, location)
    }

    /** @see com.intellij.openapi.projectRoots.impl.jdkDownloader.JdkInstallerBase.validateInstallDir */
    private fun validateLocation(selectedPath: String): Pair<Path?, @NlsContexts.DialogMessage String?> {
        if (selectedPath.isBlank()) {
            return null to ProjectBundle.message("dialog.message.error.target.path.empty")
        }
        val targetDir = runCatching { Path.of(OSAgnosticPathUtil.expandUserHome(selectedPath)) }.getOrElse { t ->
            return null to ProjectBundle.message("dialog.message.error.resolving.path")
        }
        if (targetDir.isRegularFile()) {
            return null to ProjectBundle.message("dialog.message.error.target.path.exists.file")
        }
        if (targetDir.isDirectory() && targetDir.toFile().listFiles()?.isNotEmpty() == true) {
            return null to ProjectBundle.message("dialog.message.error.target.path.exists.nonEmpty.dir")
        }
        return targetDir to null
    }
}

/** @see com.intellij.openapi.projectRoots.impl.jdkDownloader.JdkPackageType */
private enum class ZigSdkPackageType(val extension: String) {
    ZIP("zip"),
    TAR_XZ("tar.xz"),
    ;

    fun openDecompressor(archiveFile: Path) = when (this) {
        ZIP -> Decompressor.Zip(archiveFile).withZipExtensions()
        TAR_XZ -> Decompressor.Tar(archiveFile)
    }
}

private class ZigSdkDownloadTask(
    val arch: String,
    val os: String,
    val version: String,
    val packageType: ZigSdkPackageType,
    val location: Path,
) : SdkDownloadTask {
    override fun getSuggestedSdkName() = "Zig $version"
    override fun getPlannedHomeDir() = location.toString()
    override fun getPlannedVersion() = version

    /** @see com.intellij.openapi.projectRoots.impl.jdkDownloader.JdkInstallerBase.installJdkImpl */
    override fun doDownload(indicator: ProgressIndicator) {
        val fullPresentationText = "Zig $version"
        val packageRootPrefix = "zig-$arch-$os-$version"
        val archiveFileName = "$packageRootPrefix.${packageType.extension}"
        val url = "https://ziglang.org/download/$version/$archiveFileName"

        location.createDirectories()

        indicator.text = ProjectBundle.message("progress.text.installing.jdk.1", fullPresentationText)
        indicator.text2 = ProjectBundle.message("progress.text2.downloading.jdk")

        val downloadFile = Path.of(PathManager.getTempPath(), "zig-${System.nanoTime()}-${archiveFileName}")
        try {
            try {
                HttpRequests.request(url)
                    .productNameAsUserAgent()
                    .saveToFile(downloadFile.toFile(), indicator)

                if (!downloadFile.isRegularFile()) {
                    throw RuntimeException("Downloaded file does not exist: $downloadFile")
                }
            } catch (t: Throwable) {
                if (t is ControlFlowException) throw t
                throw RuntimeException("Failed to download $fullPresentationText from $url. ${t.message}", t)
            }

            // TODO: verify size and checksum

            indicator.isIndeterminate = true
            indicator.text2 = ProjectBundle.message("progress.text2.unpacking.jdk")

            try {
                packageType
                    .openDecompressor(downloadFile)
                    .entryFilter { indicator.checkCanceled(); true }
                    .removePrefixPath(packageRootPrefix)
                    .extract(location)
            } catch (t: Throwable) {
                if (t is ControlFlowException) throw t
                throw RuntimeException("Failed to extract $fullPresentationText. ${t.message}", t)
            }
        } catch (t: Throwable) {
            location.delete()
            throw t
        } finally {
            runCatching { downloadFile.delete() }
        }
    }
}

private fun getPossibleZigExePaths(sdkHome: String): Array<Path> {
    val exeName = if (OS.CURRENT == OS.Windows) "zig.exe" else "zig"
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
                        "error" -> Kind.ERROR
                        "note" -> Kind.NOTE
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
