package io.github.fncontroloption.zig.psi;

import com.intellij.psi.tree.IElementType;
import io.github.fncontroloption.zig.ZigLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class ZigTokenType extends IElementType {

  public ZigTokenType(@NotNull @NonNls String debugName) {
    super(debugName, ZigLanguage.INSTANCE);
  }

}
