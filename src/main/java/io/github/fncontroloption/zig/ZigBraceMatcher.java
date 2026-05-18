package io.github.fncontroloption.zig;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import io.github.fncontroloption.zig.psi.ZigTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ZigBraceMatcher implements PairedBraceMatcher {
    private final BracePair[] pairs = new BracePair[] {
            new BracePair(ZigTypes.L_PAREN, ZigTypes.R_PAREN, false),
            new BracePair(ZigTypes.L_BRACE, ZigTypes.R_BRACE, true),
            new BracePair(ZigTypes.L_BRACKET, ZigTypes.R_BRACKET, false),
    };

    @Override
    public BracePair @NotNull [] getPairs() {
        return pairs;
    }

    @Override
    public boolean isPairedBracesAllowedBeforeType(@NotNull IElementType lbraceType, @Nullable IElementType contextType) {
        return false;
    }

    @Override
    public int getCodeConstructStart(PsiFile file, int openingBraceOffset) {
        // TODO
        return openingBraceOffset;
    }
}
