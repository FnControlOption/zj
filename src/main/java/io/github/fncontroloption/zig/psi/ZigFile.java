package io.github.fncontroloption.zig.psi;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import io.github.fncontroloption.zig.ZigFileType;
import io.github.fncontroloption.zig.ZigLanguage;
import org.jetbrains.annotations.NotNull;

public class ZigFile extends PsiFileBase {

  public ZigFile(@NotNull FileViewProvider viewProvider) {
    super(viewProvider, ZigLanguage.INSTANCE);
  }

  @NotNull
  @Override
  public FileType getFileType() {
    return ZigFileType.INSTANCE;
  }

  @Override
  public String toString() {
    return "Zig File";
  }

}
