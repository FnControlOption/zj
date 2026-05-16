package io.github.fncontroloption.zig.lexer;

import com.intellij.lexer.FlexAdapter;

public class ZigLexerAdapter extends FlexAdapter {

  public ZigLexerAdapter() {
    super(new ZigLexer(null));
  }

}
