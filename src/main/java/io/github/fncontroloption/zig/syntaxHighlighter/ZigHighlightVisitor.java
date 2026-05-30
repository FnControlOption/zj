package io.github.fncontroloption.zig.syntaxHighlighter;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.HighlightVisitor;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import io.github.fncontroloption.zig.psi.*;
import org.jetbrains.annotations.NotNull;

/** @see com.intellij.codeInsight.daemon.impl.analysis.JavaNamesHighlightVisitor */
public class ZigHighlightVisitor implements HighlightVisitor {
    private HighlightInfoHolder holder;

    @Override
    public boolean suitableForFile(@NotNull PsiFile psiFile) {
        return psiFile instanceof ZigFile;
    }

    @Override
    public void visit(@NotNull PsiElement element) {
        switch (element) {
            case ZigFnDecl decl -> {
                var identifier = decl.getNameIdentifier();
                if (identifier == null) {
                    return;
                }

                HighlightInfoType type = ZigHighlightInfoTypes.STATIC_METHOD;
                holder.add(HighlightInfo.newHighlightInfo(type).range(identifier).create());
            }

            case ZigVarDecl decl -> {
                var identifier = decl.getNameIdentifier();
                if (identifier == null) {
                    return;
                }

                var scope = PsiTreeUtil.findFirstParent(decl, true, (parent) -> {
                    //noinspection CodeBlock2Expr
                    return parent instanceof ZigBlock || parent instanceof ZigContainerDecl;
                });

                HighlightInfoType type;
                if (scope instanceof ZigBlock) {
                    if (decl.isVar()) {
                        type = ZigHighlightInfoTypes.REASSIGNED_LOCAL_VARIABLE;
                    } else {
                        type = ZigHighlightInfoTypes.LOCAL_VARIABLE;
                    }
                } else {
                    type = ZigHighlightInfoTypes.STATIC_FIELD;
                }

                holder.add(HighlightInfo.newHighlightInfo(type).range(identifier).create());
            }

            default -> {}
        }
    }

    @Override
    public boolean analyze(@NotNull PsiFile psiFile, boolean updateWholeFile, @NotNull HighlightInfoHolder holder, @NotNull Runnable action) {
        this.holder = holder;
        try {
            action.run();
        } finally {
            this.holder = null;
        }
        return true;
    }

    @Override
    @SuppressWarnings("MethodDoesntCallSuperMethod")
    public @NotNull HighlightVisitor clone() {
        return new ZigHighlightVisitor();
    }
}
