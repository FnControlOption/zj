package io.github.fncontroloption.zig

import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.util.ProgramParametersUtil
import com.intellij.ide.macro.MacrosDialog
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.terminal.TerminalExecutionConsole
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil

class ZigRunConfigurationType : SimpleConfigurationType(
    id = "ZigRunConfiguration",
    name = "Zig",
    description = "Zig run configuration",
    icon = NotNullLazyValue.createValue { ZigIcons.ZIG },
) {
    override fun createTemplateConfiguration(project: Project) =
        ZigRunConfiguration(project, this, "Zig")

    override fun getOptionsClass() = ZigRunConfigurationOptions::class.java
}

class ZigRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var arguments by string("build run $${ZIG_BUILD_OPTIONS_MACRO_NAME}$")
}

class ZigRunConfigurationSettingsEditor : SettingsEditor<ZigRunConfiguration>() {
    private val argumentsField = ExpandableTextField()

    init {
        MacrosDialog.addMacroSupport(argumentsField, MacrosDialog.Filters.ALL) { false }
    }

    override fun resetEditorFrom(s: ZigRunConfiguration) {
        argumentsField.text = s.state?.arguments ?: ""
    }

    override fun applyEditorTo(s: ZigRunConfiguration) {
        s.state?.arguments = argumentsField.text
    }

    override fun createEditor() = panel {
        row("Arguments:") { cell(argumentsField).align(AlignX.FILL) }
    }
}

class ZigRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<ZigRunConfigurationOptions>(project, factory, name) {
    override fun getConfigurationEditor() = ZigRunConfigurationSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val projectService = project.service<ZigExternalSystemProjectService>()
        val projectPath = projectService.projectPath.value ?: return null

        val exeService = project.service<ZigExeService>()
        val exePath = exeService.exePath.value ?: return null

        val originalArgs = state?.arguments ?: ""
        val expandedArgs = ProgramParametersUtil.expandPathAndMacros(originalArgs, null, project)
        val parsedArgs = ParametersListUtil.parse(expandedArgs)

        val args = buildList {
            addAll(parsedArgs)
            if (!contains("--build-file")) {
                add("--build-file")
                add(projectPath.resolve("build.zig").toString())
            }
        }

        return object : CommandLineState(environment) {
            override fun createConsole(executor: Executor) =
                TerminalExecutionConsole(project, null)

            override fun startProcess(): ProcessHandler {
                val processHandler =
                    ColoredProcessHandler(
                        PtyCommandLine()
                            .withConsoleMode(false)
                            .withExePath(exePath.toString())
                            .withParameters(args)
                    )
                ProcessTerminatedListener.attach(processHandler, project)
                return processHandler
            }
        }
    }
}
