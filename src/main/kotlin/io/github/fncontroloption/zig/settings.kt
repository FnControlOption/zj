package io.github.fncontroloption.zig

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.textFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
@State(
    name = "io.github.fncontroloption.zig.ZigSettingsService",
    storages = [Storage("ZigSettings.xml")]
)
class ZigSettings : SerializablePersistentStateComponent<ZigSettings.State>(State()) {
    data class State(
        @JvmField var zigLibPath: String = "",
    )

    var zigLibPath: String
        get() = state.zigLibPath
        set(value) {
            updateState {
                it.copy(zigLibPath = value)
            }
        }
}

class ZigSettingsComponent(project: Project) {
    private val zigLibPathField = textFieldWithBrowseButton(project, FileChooserDescriptorFactory.singleDir())

    val mainPanel: JPanel = with(FormBuilder.createFormBuilder()) {
        addLabeledComponent(JBLabel("Zig lib path: "), zigLibPathField)
        addComponentFillVertically(JPanel(), 0)
        panel
    }

    var zigLibPathText: String
        get() = zigLibPathField.text
        set(value) {
            zigLibPathField.text = value
        }
}

class ZigSettingsConfigurable(private val project: Project) : Configurable {
    private var _settingsComponent: ZigSettingsComponent? = null
    private val settingsComponent: ZigSettingsComponent
        get() {
            var component = _settingsComponent
            if (component == null) {
                component = ZigSettingsComponent(project)
                _settingsComponent = component
            }
            return component
        }

    override fun getDisplayName() = "Zig"

    override fun createComponent() = settingsComponent.mainPanel

    override fun isModified(): Boolean {
        val settings = project.service<ZigSettings>()
        return settingsComponent.zigLibPathText != settings.zigLibPath
    }

    override fun apply() {
        val settings = project.service<ZigSettings>()
        settings.zigLibPath = settingsComponent.zigLibPathText
        ApplicationManager.getApplication().invokeLater {
            PsiManager.getInstance(project).dropPsiCaches()
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    override fun reset() {
        val settings = project.service<ZigSettings>()
        settingsComponent.zigLibPathText = settings.zigLibPath
    }

    override fun disposeUIResources() {
        _settingsComponent = null
    }
}
