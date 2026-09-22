package io.github.fncontroloption.zig

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.GenerationCodeStylePanel
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.psi.codeStyle.*
import com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions

class ZigCodeStyleSettings(settings: CodeStyleSettings) : CustomCodeStyleSettings("ZigCodeStyleSettings", settings)

class ZigCodeStyleSettingsProvider : CodeStyleSettingsProvider() {
    override fun createCustomSettings(settings: CodeStyleSettings) = ZigCodeStyleSettings(settings)
    override fun getLanguage() = ZigLanguage
    override fun createConfigurable(
        settings: CodeStyleSettings,
        modelSettings: CodeStyleSettings
    ) = ZigCodeStyleConfigurable(settings, modelSettings, configurableDisplayName)
}

class ZigCodeStyleConfigurable(settings: CodeStyleSettings, cloneSettings: CodeStyleSettings, displayName: String?) :
    CodeStyleAbstractConfigurable(settings, cloneSettings, displayName) {
    override fun createPanel(settings: CodeStyleSettings) = ZigCodeStyleMainPanel(currentSettings, settings)
}

class ZigCodeStyleMainPanel(currentSettings: CodeStyleSettings?, settings: CodeStyleSettings) :
    TabbedLanguageCodeStylePanel(ZigLanguage, currentSettings, settings) {
    override fun initTabs(settings: CodeStyleSettings) {
        addTab(GenerationCodeStylePanel(settings, ZigLanguage))
    }
}

class ZigLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage() = ZigLanguage

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: IndentOptions) {
        commonSettings.LINE_COMMENT_AT_FIRST_COLUMN = false
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        if (settingsType == SettingsType.COMMENTER_SETTINGS) {
            consumer.showStandardOptions(
                "LINE_COMMENT_ADD_SPACE",
                "LINE_COMMENT_AT_FIRST_COLUMN",
                "LINE_COMMENT_ADD_SPACE_ON_REFORMAT"
            )
        }
    }

    override fun getCodeSample(settingsType: SettingsType) = null
}
