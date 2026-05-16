package io.github.fncontroloption.zig;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public final class ZigFileType extends LanguageFileType {

  public static final ZigFileType INSTANCE = new ZigFileType();

  private ZigFileType() {
    super(ZigLanguage.INSTANCE);
  }

  @NotNull
  @Override
  public String getName() {
    return "Zig File";
  }

  @NotNull
  @Override
  public String getDescription() {
    return "Zig language file";
  }

  @NotNull
  @Override
  public String getDefaultExtension() {
    return "zig";
  }

  @Override
  public Icon getIcon() {
    return ZigIcons.ZIG;
  }

}
