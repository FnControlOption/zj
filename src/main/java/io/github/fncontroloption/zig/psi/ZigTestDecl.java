package io.github.fncontroloption.zig.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class ZigTestDecl extends ASTWrapperPsiElement {
    public ZigTestDecl(@NotNull ASTNode node) {
        super(node);
    }
}
