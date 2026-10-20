package io.github.fncontroloption.zig

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

fun getZigBuildConsole(project: Project): ConsoleView? {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Zig") ?: return null
    val buildOnSave = toolWindow.contentManager.findContent("Build") ?: return null
    val panel = buildOnSave.component as SimpleToolWindowPanel
    val console = panel.content as ConsoleView
    return console
}

class ZigToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console

        val panel = SimpleToolWindowPanel(false, true)
        panel.setContent(console.component)

        // Note: action group must be created after panel
        val actionGroup = DefaultActionGroup(*console.createConsoleActions())
        val toolbar = ActionManager.getInstance().createActionToolbar("Zig", actionGroup, false)
        toolbar.targetComponent = console.component
        panel.toolbar = toolbar.component

        val content = ContentFactory.getInstance().createContent(panel, "Build", true)
        Disposer.register(content, console)
        toolWindow.contentManager.addContent(content)
    }
}
