package io.github.fncontroloption.zj

import com.intellij.execution.util.ProgramParametersUtil
import com.intellij.ide.macro.Macro
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service

const val ZIG_BUILD_OPTIONS_MACRO_NAME = "ZigBuildOptions"

class ZigBuildOptionsMacro : Macro() {
    override fun getName() = ZIG_BUILD_OPTIONS_MACRO_NAME
    override fun getDescription() = "Zig build options"
    override fun expand(dataContext: DataContext): String? {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return null
        val settings = project.service<ZigSettings>()
        val original = settings.state.buildOptions ?: return null
        val expanded = ProgramParametersUtil.expandPathAndMacros(original, null, project)
        return expanded
    }
}
