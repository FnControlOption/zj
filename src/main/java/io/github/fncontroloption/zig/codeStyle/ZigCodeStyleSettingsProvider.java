package io.github.fncontroloption.zig.codeStyle;

import com.intellij.application.options.CodeStyleAbstractConfigurable;
import com.intellij.application.options.CodeStyleAbstractPanel;
import com.intellij.application.options.GenerationCodeStylePanel;
import com.intellij.application.options.TabbedLanguageCodeStylePanel;
import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CodeStyleConfigurable;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider;
import com.intellij.psi.codeStyle.CustomCodeStyleSettings;
import io.github.fncontroloption.zig.ZigLanguage;
import org.jetbrains.annotations.NotNull;

final class ZigCodeStyleSettingsProvider extends CodeStyleSettingsProvider {

  @Override
  public CustomCodeStyleSettings createCustomSettings(@NotNull CodeStyleSettings settings) {
    return new ZigCodeStyleSettings(settings);
  }

  @Override
  public Language getLanguage() {
    return ZigLanguage.INSTANCE;
  }

  @NotNull
  public CodeStyleConfigurable createConfigurable(@NotNull CodeStyleSettings settings,
                                                  @NotNull CodeStyleSettings modelSettings) {
    return new CodeStyleAbstractConfigurable(settings, modelSettings, this.getConfigurableDisplayName()) {
      @Override
      protected @NotNull CodeStyleAbstractPanel createPanel(@NotNull CodeStyleSettings settings) {
        return new ZigCodeStyleMainPanel(getCurrentSettings(), settings);
      }
    };
  }

  private static class ZigCodeStyleMainPanel extends TabbedLanguageCodeStylePanel {

    public ZigCodeStyleMainPanel(CodeStyleSettings currentSettings, CodeStyleSettings settings) {
      super(ZigLanguage.INSTANCE, currentSettings, settings);
    }

    @Override
    protected void initTabs(CodeStyleSettings settings) {
      addTab(new GenerationCodeStylePanel(settings, ZigLanguage.INSTANCE));
    }
  }

}
