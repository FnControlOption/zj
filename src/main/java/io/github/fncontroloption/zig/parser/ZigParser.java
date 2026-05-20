package io.github.fncontroloption.zig.parser;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import static io.github.fncontroloption.zig.psi.ZigTypes.*;

// Based on https://codeberg.org/ziglang/zig/src/commit/72d954e/lib/std/zig/Parse.zig
public class ZigParser implements PsiParser {
    private PsiBuilder builder;

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        this.builder = builder;
        parseRoot(root);
        return builder.getTreeBuilt();
    }

    private void parseRoot(IElementType root) {
        var rootMarker = builder.mark();
        while (!builder.eof()) {
            if (builder.getTokenType() == KEYWORD_FN) {
                var varDeclProtoMarker = builder.mark();
                builder.advanceLexer();
                if (builder.getTokenType() == IDENTIFIER) {
                    builder.advanceLexer();
                }
                varDeclProtoMarker.done(FN_PROTO);
            } else if (builder.getTokenType() == MULTILINE_STRING_LITERAL_LINE) {
                var multilineStringLiteralMarker = builder.mark();
                while (builder.getTokenType() == MULTILINE_STRING_LITERAL_LINE) {
                    builder.advanceLexer();
                }
                multilineStringLiteralMarker.done(MULTILINE_STRING_LITERAL);
            } else if (builder.getTokenType() == STRING_LITERAL) {
                var stringLiteralMarker = builder.mark();
                builder.advanceLexer();
                stringLiteralMarker.done(STRING_LITERAL);
            } else {
                builder.advanceLexer();
            }
        }
        rootMarker.done(root);
    }
}
