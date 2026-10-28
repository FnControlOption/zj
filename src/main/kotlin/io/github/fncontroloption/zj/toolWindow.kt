package io.github.fncontroloption.zj

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.TerminalExecutionConsole
import com.intellij.ui.content.ContentFactory

private val ZIG_CONSOLE_KEY = Key.create<ConsoleView>("ZIG_CONSOLE_KEY")

enum class ZigToolWindowTab(
    val displayName: String,
    val allowsAnsiEscapes: Boolean,
    val toolbarPlace: String,
) {
    FETCH("Fetch", true, "ZigFetch"),
    BUILD("Build", false, "ZigBuild"),
}

fun activateZigToolWindow(project: Project, tab: ZigToolWindowTab) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Zig") ?: return
    val content = toolWindow.contentManager.findContent(tab.displayName) ?: return
    toolWindow.contentManager.setSelectedContent(content)
    toolWindow.activate(null)
}

fun getZigToolWindowConsole(project: Project, tab: ZigToolWindowTab): ConsoleView? {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Zig") ?: return null
    val content = toolWindow.contentManager.findContent(tab.displayName) ?: return null
    val console = content.getUserData(ZIG_CONSOLE_KEY) ?: return null
    return console
}

class ZigToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        for (tab in ZigToolWindowTab.entries) {
            addToolWindowTab(project, toolWindow, tab)
        }
    }

    private fun addToolWindowTab(project: Project, toolWindow: ToolWindow, tab: ZigToolWindowTab) {
        val console =
            if (tab.allowsAnsiEscapes)
                TerminalExecutionConsole(project, null)
            else
                TextConsoleBuilderFactory.getInstance().createBuilder(project).console

        val panel = SimpleToolWindowPanel(false, true)
        panel.setContent(console.component)

        // Note: action group must be created after panel
        val actionGroup = DefaultActionGroup(*console.createConsoleActions())
        val toolbar = ActionManager.getInstance().createActionToolbar(tab.toolbarPlace, actionGroup, false)
        toolbar.targetComponent = console.component
        panel.toolbar = toolbar.component

        val content = ContentFactory.getInstance().createContent(panel, tab.displayName, true)
        Disposer.register(content, console)
        content.putUserData(ZIG_CONSOLE_KEY, console)
        toolWindow.contentManager.addContent(content)
    }
}
