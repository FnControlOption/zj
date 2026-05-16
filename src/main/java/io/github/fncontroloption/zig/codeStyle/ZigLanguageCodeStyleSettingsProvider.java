package io.github.fncontroloption.zig.codeStyle;

import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import io.github.fncontroloption.zig.ZigLanguage;
import org.jetbrains.annotations.NotNull;

final class ZigLanguageCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {

  @NotNull
  @Override
  public Language getLanguage() {
    return ZigLanguage.INSTANCE;
  }

  @Override
  protected void customizeDefaults(@NotNull CommonCodeStyleSettings commonSettings, @NotNull IndentOptions indentOptions) {
    commonSettings.LINE_COMMENT_AT_FIRST_COLUMN = false;
  }

  @Override
  public void customizeSettings(@NotNull CodeStyleSettingsCustomizable consumer, @NotNull SettingsType settingsType) {
    if (settingsType == SettingsType.COMMENTER_SETTINGS) {
      consumer.showStandardOptions(
              "LINE_COMMENT_ADD_SPACE",
              "LINE_COMMENT_AT_FIRST_COLUMN",
              "LINE_COMMENT_ADD_SPACE_ON_REFORMAT"
      );
    }
  }

  @Override
  public String getCodeSample(@NotNull SettingsType settingsType) {
    return null;
  }

}
