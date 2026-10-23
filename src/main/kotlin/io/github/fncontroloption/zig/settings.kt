package io.github.fncontroloption.zig

import com.intellij.execution.util.ProgramParametersUtil
import com.intellij.ide.macro.MacrosDialog
import com.intellij.openapi.components.*
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.dsl.builder.*
import com.intellij.util.execution.ParametersListUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
@State(name = "ZigSettings", storages = [Storage("zig.xml")])
class ZigSettings(private val project: Project) : SimplePersistentStateComponent<ZigSettings.State>(State()) {
    class State : BaseState() {
        var buildOptions by string()
        var buildOnSave by property(true)
        var buildArgsOnSave by string("check --watch $${ZIG_BUILD_OPTIONS_MACRO_NAME}$")
        var runAstCheck by property(false)
    }

    val buildOptions: List<String>
        get() {
            val original = state.buildOptions ?: return emptyList()
            val expanded = ProgramParametersUtil.expandPathAndMacros(original, null, project)
            return ParametersListUtil.parse(expanded)
        }

    val buildOnSave: Boolean
        get() = state.buildOnSave

    val buildArgsOnSave: List<String>
        get() {
            val original = state.buildArgsOnSave ?: return emptyList()
            val expanded = ProgramParametersUtil.expandPathAndMacros(original, null, project)
            return ParametersListUtil.parse(expanded)
        }

    val runAstCheck: Boolean
        get() = state.runAstCheck
}

class ZigSettingsConfigurable(private val project: Project) : BoundConfigurable("Zig") {
    private val state = project.service<ZigSettings>().state

    override fun createPanel() = panel {
        row("Build options:") {
            val editor = RawCommandLineEditor()
            MacrosDialog.addMacroSupport(editor.editorField, MacrosDialog.Filters.ALL) { false }
            cell(editor)
                .comment(
                    listOf(
                        "Specify options to pass to '<b>zig build</b>'",
                        "For example: <code>-Dtarget=aarch64-linux-gnu</code>",
                        "The reusable macro for this setting is <code>$${ZIG_BUILD_OPTIONS_MACRO_NAME}</code>$",
                    ).joinToString("<br>")
                )
                .align(AlignX.FILL)
                .bind(
                    { editor -> editor.editorField.text },
                    { editor, value -> editor.editorField.text = value },
                    state::buildOptions.toNonNullableProperty("")
                )
        }
        row {
            val buildOnSave = checkBox("Build on save:").bindSelected(state::buildOnSave)
            val buildArgsOnSave = expandableTextField()
            MacrosDialog.addMacroSupport(buildArgsOnSave.component, MacrosDialog.Filters.ALL) { false }
            buildArgsOnSave
                .enabledIf(buildOnSave.selected)
                .comment("Specify arguments to pass to '<b>zig build</b>' on save")
                .align(AlignX.FILL)
                .bindText(state::buildArgsOnSave.toNonNullableProperty(""))
        }
        row {
            checkBox("<html>Run '<b>zig ast-check</b>' on save</html>")
                .bindSelected(state::runAstCheck)
        }
    }

    override fun apply() {
        super.apply()
        CoroutineScope(Dispatchers.Default).launch {
            project.service<ZigBuildProjectService>().restartFetch()
        }
    }
}
