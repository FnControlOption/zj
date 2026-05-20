package io.github.fncontroloption.zig.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class ZigTestName extends ASTWrapperPsiElement {
    public ZigTestName(@NotNull ASTNode node) {
        super(node);
    }
}
