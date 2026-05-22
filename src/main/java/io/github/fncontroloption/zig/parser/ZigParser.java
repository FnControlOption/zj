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
        parseContainerMembers();
        while (!builder.eof()) {
            builder.advanceLexer();
        }
        rootMarker.done(root);
    }

    private void parseContainerMembers() {
        while (!builder.eof() && builder.getTokenType() != R_BRACE) {
            if (builder.getTokenType() == KEYWORD_TEST) {
                expectTestDecl();
            } else if (builder.getTokenType() == KEYWORD_COMPTIME) {
                builder.advanceLexer(); // TODO
            } else if (builder.getTokenType() == KEYWORD_PUB
                    || builder.getTokenType() == KEYWORD_CONST
                    || builder.getTokenType() == KEYWORD_VAR
                    || builder.getTokenType() == KEYWORD_THREADLOCAL
                    || builder.getTokenType() == KEYWORD_EXPORT
                    || builder.getTokenType() == KEYWORD_EXTERN
                    || builder.getTokenType() == KEYWORD_INLINE
                    || builder.getTokenType() == KEYWORD_NOINLINE
                    || builder.getTokenType() == KEYWORD_FN) {
                builder.advanceLexer(); // TODO
            } else {
                builder.advanceLexer(); // TODO
            }
        }
    }

    private void expectTestDecl() {
        var marker = builder.mark();
        assertToken(KEYWORD_TEST);
        if (builder.getTokenType() == STRING_LITERAL
                || builder.getTokenType() == IDENTIFIER) {
            var nameMarker = builder.mark();
            builder.advanceLexer();
            nameMarker.done(TEST_DECL_NAME);
        }
        parseBlock();
        marker.done(TEST_DECL);
    }

    private void parseBlock() {
        if (!eatToken(L_BRACE)) {
            return;
        }
        // TODO
        eatToken(R_BRACE);
    }

    private void assertToken(IElementType tokenType) {
        LOG.assertTrue(builder.getTokenType() == tokenType);
        builder.advanceLexer();
    }

    private boolean eatToken(IElementType tokenType) {
        if (builder.getTokenType() == tokenType) {
            builder.advanceLexer();
            return true;
        } else {
            return false;
        }
    }
}
