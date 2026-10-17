package io.github.fncontroloption.zig

import com.intellij.execution.util.ProgramParametersUtil
import com.intellij.ide.macro.MacrosDialog
import com.intellij.openapi.components.*
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNonNullableProperty
import com.intellij.util.execution.ParametersListUtil

@Service(Service.Level.PROJECT)
@State(name = "ZigSettings", storages = [Storage("zig.xml")])
class ZigSettings(private val project: Project) : SimplePersistentStateComponent<ZigSettings.State>(State()) {
    class State : BaseState() {
        var buildOptions by string()
    }

    val buildOptions: List<String>
        get() {
            val original = state.buildOptions ?: return emptyList()
            val expanded = ProgramParametersUtil.expandPathAndMacros(original, null, project)
            return ParametersListUtil.parse(expanded)
        }
}

class ZigSettingsConfigurable(private val project: Project) : BoundConfigurable("Zig") {
    private val state = project.service<ZigSettings>().state

    override fun createPanel() = panel {
        row("Build options:") {
            val editor = RawCommandLineEditor()
            MacrosDialog.addMacroSupport(editor.editorField, MacrosDialog.Filters.ALL) { false }
            cell(editor).align(AlignX.FILL).bind(
                { editor -> editor.editorField.text },
                { editor, value -> editor.editorField.text = value },
                state::buildOptions.toNonNullableProperty("")
            )
        }
    }

    override fun apply() {
        super.apply()
        val projectPath = findZigProjectPath(project) ?: return
        val projectService = project.service<ZigModulesProjectService>()
        projectService.resetFetchSettings()
        projectService.fetchAndLoadModulesAsync(projectPath)
    }
}
