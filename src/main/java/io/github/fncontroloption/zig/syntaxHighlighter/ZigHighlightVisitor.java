package io.github.fncontroloption.zig.syntaxHighlighter;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightVisitor;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import io.github.fncontroloption.zig.psi.ZigFile;
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
        /*
        if (element instanceof ZigFnProto proto) {
            var identifier = proto.getNameIdentifier();
            if (identifier != null) {
                holder.add(HighlightInfo.newHighlightInfo(ZigHighlightInfoTypes.STATIC_METHOD).range(identifier).create());
            }
        }
        */
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
