package io.github.fncontroloption.zj

import com.intellij.execution.util.ProgramParametersUtil
import com.intellij.ide.macro.MacrosDialog
import com.intellij.openapi.components.*
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNonNullableProperty
import com.intellij.util.execution.ParametersListUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
@State(name = "ZigSettings", storages = [Storage("zig.xml")])
class ZigSettings(private val project: Project) : SimplePersistentStateComponent<ZigSettings.State>(State()) {
    class State : BaseState() {
        var buildOptions by string()
        var runAstCheck by property(false)
    }

    val buildOptions: List<String>
        get() {
            val original = state.buildOptions ?: return emptyList()
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
