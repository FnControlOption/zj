package io.github.fncontroloption.zig.parser;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import static io.github.fncontroloption.zig.psi.ZigTypes.*;

// Based on https://codeberg.org/ziglang/zig/src/commit/72d954e/lib/std/zig/Parse.zig
public class ZigParser implements PsiParser {
    private static final Logger LOG = Logger.getInstance(ZigParser.class);
    private PsiBuilder builder;

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        this.builder = builder;
        parseRoot(root);
        return builder.getTreeBuilt();
    }

    private void parseRoot(IElementType root) {
        var rootMarker = builder.mark();
        parseContainerMembers(null);
        rootMarker.done(root);
    }

    private void parseContainerMembers(@Nullable IElementType closingToken) {
        while (!builder.eof()) {
            if (builder.getTokenType() == closingToken) {
                break;
            }
            skipUnexpectedToken();
        }
    }

    private void expectFn() {
        LOG.assertTrue(builder.getTokenType() == KEYWORD_FN);
        var fnProtoMarker = builder.mark();
        builder.advanceLexer();
        if (builder.getTokenType() == IDENTIFIER) {
            builder.advanceLexer();
        }
        fnProtoMarker.done(FN_PROTO);
    }

    private void expectMultilineStringLiteral() {
        LOG.assertTrue(builder.getTokenType() == MULTILINE_STRING_LITERAL_LINE);
        var marker = builder.mark();
        while (builder.getTokenType() == MULTILINE_STRING_LITERAL_LINE) {
            builder.advanceLexer();
        }
        marker.done(MULTILINE_STRING_LITERAL);
    }

    private void expectStringLiteral() {
        LOG.assertTrue(builder.getTokenType() == STRING_LITERAL);
        var marker = builder.mark();
        builder.advanceLexer();
        marker.done(STRING_LITERAL);
    }

    private void skipUnexpectedToken() {
        if (builder.getTokenType() == KEYWORD_FN) {
            expectFn();
        } else if (builder.getTokenType() == MULTILINE_STRING_LITERAL_LINE) {
            expectMultilineStringLiteral();
        } else if (builder.getTokenType() == STRING_LITERAL) {
            expectStringLiteral();
        } else {
            builder.advanceLexer();
        }
    }
}
