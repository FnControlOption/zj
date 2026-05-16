package io.github.fncontroloption.zig;

import com.intellij.lang.Language;

public class ZigLanguage extends Language {

  public static final ZigLanguage INSTANCE = new ZigLanguage();

  private ZigLanguage() {
    super("Zig");
  }

}
