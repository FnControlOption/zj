package io.github.fncontroloption.zig.parser;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import static io.github.fncontroloption.zig.psi.ZigTypes.*;

// Based on https://codeberg.org/ziglang/zig/src/commit/72d954e/lib/std/zig/Parse.zig
public class ZigParser implements PsiParser {
    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        var rootMarker = builder.mark();
        while (!builder.eof()) {
            if (builder.getTokenType() == KEYWORD_FN) {
                var varDeclProtoMarker = builder.mark();
                builder.advanceLexer();
                if (builder.getTokenType() == IDENTIFIER) {
                    builder.advanceLexer();
                }
                varDeclProtoMarker.done(FN_PROTO);
            } else {
                builder.advanceLexer();
            }
        }
        rootMarker.done(root);
        return builder.getTreeBuilt();
    }
}
