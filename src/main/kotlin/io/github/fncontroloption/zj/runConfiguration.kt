package io.github.fncontroloption.zj

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.CommonProgramParametersPanel
import com.intellij.execution.util.ProgramParametersUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.terminal.TerminalExecutionConsoleBuilder
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import kotlin.io.path.Path

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
    var workingDirectory by string()
    var envs by map<String, String>()
    var passParentEnvs by property(true)
}

class ZigRunConfigurationSettingsEditor(project: Project) : SettingsEditor<ZigRunConfiguration>() {
    private val commonProgramParameters = object : CommonProgramParametersPanel(true, project) {
        override fun isMacroSupportEnabled() = true
    }

    override fun resetEditorFrom(s: ZigRunConfiguration) {
        commonProgramParameters.reset(s)
    }

    override fun applyEditorTo(s: ZigRunConfiguration) {
        commonProgramParameters.applyTo(s)
    }

    override fun createEditor() = panel {
        row { cell(commonProgramParameters).align(AlignX.FILL) }
    }
}

class ZigRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<ZigRunConfigurationOptions>(project, factory, name),
    CommonProgramRunConfigurationParameters {

    override fun getOptions() = super.getOptions() as ZigRunConfigurationOptions

    override fun getConfigurationEditor() = ZigRunConfigurationSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val projectService = project.service<ZigProjectPathService>()
        val projectPath = projectService.projectPath.value ?: return null

        val exeService = project.service<ZigExeService>()
        val exePath = exeService.exePath.value ?: return null

        val originalArgs = options.arguments ?: ""
        val expandedArgs = ProgramParametersUtil.expandPathAndMacros(originalArgs, null, project)
        val parsedArgs = ParametersListUtil.parse(expandedArgs)

        val args = buildList {
            addAll(parsedArgs)
            if (!contains("--build-file")) {
                add("--build-file")
                add(projectPath.resolve("build.zig").toString())
            }
        }

        val workDirectory = options.workingDirectory?.let {
            Path(ProgramParametersUtil.expandPathAndMacros(it, null, project))
        } ?: projectPath

        val env = options.envs

        val parentEnvType =
            if (options.passParentEnvs)
                GeneralCommandLine.ParentEnvironmentType.CONSOLE
            else
                GeneralCommandLine.ParentEnvironmentType.NONE

        return object : CommandLineState(environment) {
            override fun createConsole(executor: Executor) =
                TerminalExecutionConsoleBuilder(project).build()

            override fun startProcess(): ProcessHandler {
                val processHandler =
                    ColoredProcessHandler(
                        PtyCommandLine()
                            .withConsoleMode(false)
                            .withExePath(exePath.toString())
                            .withParameters(args)
                            .withWorkingDirectory(workDirectory)
                            .withEnvironment(env)
                            .withParentEnvironmentType(parentEnvType)
                    )
                ProcessTerminatedListener.attach(processHandler, project)
                return processHandler
            }
        }
    }

    override fun setProgramParameters(value: String?) {
        options.arguments = value
    }

    override fun getProgramParameters(): String? {
        return options.arguments
    }

    override fun setWorkingDirectory(value: String?) {
        options.workingDirectory = value
    }

    override fun getWorkingDirectory(): String? {
        return options.workingDirectory
    }

    override fun setEnvs(envs: Map<String, String>) {
        options.envs = envs.toMutableMap()
    }

    override fun getEnvs(): Map<String, String> {
        return options.envs
    }

    override fun setPassParentEnvs(passParentEnvs: Boolean) {
        options.passParentEnvs = passParentEnvs
    }

    override fun isPassParentEnvs(): Boolean {
        return options.passParentEnvs
    }
}
