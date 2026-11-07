@file:Suppress("UnstableApiUsage")

package io.github.fncontroloption.zj

import com.intellij.CommonBundle
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.ide.highlighter.ModuleFileType
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.ide.wizard.runAfterOpened
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenMode
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.module.GeneralModuleType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.observable.properties.ObservableMutableProperty
import com.intellij.openapi.observable.util.toUiPathProperty
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.ui.BrowseFolderDescriptor.Companion.withPathToTextConvertor
import com.intellij.openapi.ui.BrowseFolderDescriptor.Companion.withTextToPathConvertor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.getCanonicalPath
import com.intellij.openapi.ui.getPresentablePath
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.UIBundle
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.ValidationInfoBuilder
import io.github.fncontroloption.zj.IntelliJModuleNewProjectWizardData.Companion.moduleData
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.MessageFormat

private const val BUILD_ZIG_TEMPLATE =
    """const std = @import("std");

pub fn build(b: *std.Build) void '{'
    const exe = b.addExecutable(.'{'
        .name = "{0}",
        .root_module = b.createModule(.'{'
            .root_source_file = b.path("main.zig"),
            .target = b.graph.host,
        '}'),
    '}');

    b.installArtifact(exe);

    const run_exe = b.addRunArtifact(exe);

    const run_step = b.step("run", "Run the application");
    run_step.dependOn(&run_exe.step);
'}'
"""

private const val MAIN_ZIG_TEMPLATE =
    """const std = @import("std");

pub fn main() !void {
    std.debug.print("Hello World!\n", .{});
}
"""

class ZigNewProjectWizard : LanguageGeneratorNewProjectWizard {
    override val name = "Zig"

    override val icon = ZigIcons.ZIG

    override fun isEnabled(context: WizardContext) =
        context.isCreatingNewProject

    override fun createStep(parent: NewProjectWizardStep): NewProjectWizardStep =
        ZigNewProjectWizardStep(parent)
            .nextStep { IntelliJModuleNewProjectWizardStep(it, GeneralModuleType.TYPE_ID) }
}

private class ZigNewProjectWizardStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
    override fun setupProject(project: Project) {
        val name = baseData!!.name
        val buildFile = WriteAction.computeAndWait<VirtualFile, Throwable> {
            val rootDirectory = VfsUtil.createDirectories(moduleData!!.contentRoot)
            val build = rootDirectory.createChildData(this, "build.zig")
            val main = rootDirectory.createChildData(this, "main.zig")
            VfsUtil.saveText(build, MessageFormat.format(BUILD_ZIG_TEMPLATE, name))
            VfsUtil.saveText(main, MAIN_ZIG_TEMPLATE)
            build
        }
        runAfterOpened(project) { project ->
            val request =
                FileEditorOpenRequest
                    .defaults()
                    .withOpenMode(FileEditorOpenMode.DEFAULT)
                    .withRequestFocus(true)
            FileEditorManager.getInstance(project).requestOpenFile(buildFile, request)
        }
        runAfterOpened(project) { project ->
            ApplicationManager.getApplication().invokeLater({
                val runManager = RunManager.getInstance(project)
                val runConfigType = runConfigurationType<ZigRunConfigurationType>()
                val runConfigSettings = runManager.createConfiguration("Run", runConfigType)
                runManager.addConfiguration(runConfigSettings)
                runManager.selectedConfiguration = runConfigSettings
            }, project.disposed)
        }
    }
}

/** @see com.intellij.ide.projectWizard.generators.IntelliJNewProjectWizardData */
private interface IntelliJModuleNewProjectWizardData {
    val moduleNameProperty: ObservableMutableProperty<String>
    var moduleName: String
    val contentRootProperty: ObservableMutableProperty<String>
    var contentRoot: String
    val moduleFileLocationProperty: ObservableMutableProperty<String>
    var moduleFileLocation: String

    companion object {
        val KEY = Key.create<IntelliJModuleNewProjectWizardData>(IntelliJModuleNewProjectWizardData::class.java.name)

        @JvmStatic
        val NewProjectWizardStep.moduleData: IntelliJModuleNewProjectWizardData?
            get() = data.getUserData(KEY)
    }
}

/** @see com.intellij.ide.projectWizard.generators.IntelliJNewProjectWizardStep */
private class IntelliJModuleNewProjectWizardStep(parent: NewProjectWizardStep, val moduleTypeId: String) :
    AbstractNewProjectWizardStep(parent),
    IntelliJModuleNewProjectWizardData {

    override val moduleNameProperty = propertyGraph.lazyProperty(::suggestModuleName)
    override val contentRootProperty = propertyGraph.lazyProperty(::suggestContentRoot)
    override val moduleFileLocationProperty = propertyGraph.lazyProperty(::suggestModuleFilePath)

    override var moduleName by moduleNameProperty
    override var contentRoot by contentRootProperty
    override var moduleFileLocation by moduleFileLocationProperty

    private var userDefinedContentRoot: Boolean = false
    private var userDefinedModuleFileLocation: Boolean = false

    private fun suggestModuleName() = baseData!!.name
    private fun suggestContentRoot() = "${baseData!!.path}/${baseData!!.name}"
    private fun suggestModuleFilePath() = contentRoot

    init {
        data.putUserData(IntelliJModuleNewProjectWizardData.KEY, this)
        moduleNameProperty.dependsOn(baseData!!.nameProperty, ::suggestModuleName)
        contentRootProperty.dependsOn(baseData!!.nameProperty, ::suggestContentRoot)
        contentRootProperty.dependsOn(baseData!!.pathProperty, ::suggestContentRoot)
        moduleFileLocationProperty.dependsOn(contentRootProperty, ::suggestModuleFilePath)
    }

    override fun setupUI(builder: Panel) {
        builder.collapsibleGroup(UIBundle.message("label.project.wizard.new.project.advanced.settings")) {
            row(UIBundle.message("label.project.wizard.new.project.module.name")) {
                textField()
                    .bindText(moduleNameProperty)
                    .align(AlignX.FILL)
                    .validationOnInput { validateModuleName() }
                    .validationOnApply { validateModuleName() }
            }
            row(UIBundle.message("label.project.wizard.new.project.content.root")) {
                val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle(UIBundle.message("label.project.wizard.new.project.content.root.title"))
                    .withPathToTextConvertor(::getPresentablePath)
                    .withTextToPathConvertor(::getCanonicalPath)
                textFieldWithBrowseButton(fileChooserDescriptor, context.project)
                    .bindText(contentRootProperty.toUiPathProperty())
                    .align(AlignX.FILL)
                    .validationOnApply { validateContentRoot() }
                    .whenTextChangedFromUi { userDefinedContentRoot = true }
            }
            row(UIBundle.message("label.project.wizard.new.project.module.file.location")) {
                val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                    .withTitle(UIBundle.message("label.project.wizard.new.project.module.file.location.title"))
                    .withPathToTextConvertor(::getPresentablePath)
                    .withTextToPathConvertor(::getCanonicalPath)
                textFieldWithBrowseButton(fileChooserDescriptor, context.project)
                    .bindText(moduleFileLocationProperty.toUiPathProperty())
                    .align(AlignX.FILL)
                    .validationOnApply { validateModuleFileLocation() }
                    .whenTextChangedFromUi { userDefinedModuleFileLocation = true }
            }
        }.topGap(TopGap.MEDIUM)
    }

    // @formatter:off

    private fun ValidationInfoBuilder.validateModuleName(): ValidationInfo? {
        if (moduleName.isEmpty()) return error("Enter a module name")

        // Name uniqueness
        val project = context.project
        if (project != null) {
            val module = ModuleManager.getInstance(project).findModuleByName(moduleName)
            if (module != null) return error("Module '${moduleName}' already exists in project. Please specify another name.")
        }
        return null
    }

    private fun ValidationInfoBuilder.validateContentRoot(): ValidationInfo? {
        if (!createDirectoryIfNotExists("The module content root\n", contentRoot,
                userDefinedContentRoot))
            return error("Failed to set directory '$contentRoot' as content root")
        return null
    }

    private fun ValidationInfoBuilder.validateModuleFileLocation(): ValidationInfo? {
        if (moduleFileLocation.isEmpty())
            return error("Enter module file location")
        if (!createDirectoryIfNotExists("The module file directory\n", moduleFileLocation,
                userDefinedModuleFileLocation))
            return error("Failed to set directory '$moduleFileLocation' as module file location")
        return null
    }

    /** @see com.intellij.ide.util.projectWizard.ProjectWizardUtil.createDirectoryIfNotExists */
    private fun createDirectoryIfNotExists(promptPrefix: String, directoryPath: String, promptUser: Boolean): Boolean {
        val dir = Paths.get(directoryPath)

        if (!Files.exists(dir)) {
            if (promptUser) {
                val ide = ApplicationNamesInfo.getInstance().fullProductName
                val message = "$promptPrefix'$dir'\ndoes not exist. It will be created by $ide."
                val answer = Messages.showOkCancelDialog(message, "Directory Does Not Exist", "&Create", "&Cancel", Messages.getQuestionIcon())
                if (answer != Messages.OK) {
                    return false
                }
            }

            try {
                Files.createDirectories(dir)
            }
            catch (e: IOException) {
                Logger.getInstance(this::class.java).warn(e)
                Messages.showErrorDialog("Failed to create directory \"$dir\"", CommonBundle.getErrorTitle())
                return false
            }
        }

        if (!isWritable(dir)) {
            Messages.showErrorDialog("Directory '$dir' is read-only", CommonBundle.getErrorTitle())
            return false
        }

        return true
    }

    private fun isWritable(dir: Path): Boolean {
        if (SystemInfo.isWindows) {
            try {
                Files.deleteIfExists(Files.createTempFile(dir, "probe_", ".txt"))
                return true
            }
            catch (e: IOException) {
                Logger.getInstance(this::class.java).debug(e)
                return false
            }
        }
        else {
            return Files.isWritable(dir)
        }
    }

    // @formatter:on

    override fun setupProject(project: Project) {
        WriteAction.runAndWait<Throwable> {
            val rootDirectory = VfsUtil.createDirectories(contentRoot)
            val moduleFilePath = "${moduleFileLocation}/${moduleName}${ModuleFileType.DOT_DEFAULT_EXTENSION}"
            val module = ModuleManager.getInstance(project).newModule(moduleFilePath, moduleTypeId)
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.inheritSdk()
                model.addContentEntry(rootDirectory)
            }
        }
    }
}
