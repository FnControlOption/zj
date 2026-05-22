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
                if (builder.lookAhead(1) == L_BRACE) {
                    throw new UnsupportedOperationException(); // TODO
                } else {
                    expectContainerField();
                    eatToken(COMMA);
                }
            } else if (builder.getTokenType() == KEYWORD_PUB
                    || builder.getTokenType() == KEYWORD_CONST
                    || builder.getTokenType() == KEYWORD_VAR
                    || builder.getTokenType() == KEYWORD_THREADLOCAL
                    || builder.getTokenType() == KEYWORD_EXPORT
                    || builder.getTokenType() == KEYWORD_EXTERN
                    || builder.getTokenType() == KEYWORD_INLINE
                    || builder.getTokenType() == KEYWORD_NOINLINE
                    || builder.getTokenType() == KEYWORD_FN) {
                expectTopLevelDecl();
            } else {
                expectContainerField();
                eatToken(COMMA);
            }
        }
    }

    private void expectContainerField() {
        var marker = builder.mark();
        eatToken(KEYWORD_COMPTIME);
        var nameMarker = builder.mark();
        eatToken(IDENTIFIER);
        nameMarker.done(CONTAINER_FIELD_NAME);
        eatToken(COLON);
        var typeMarker = builder.mark();
        expectTypeExpr();
        typeMarker.done(CONTAINER_FIELD_TYPE);
        parseByteAlign();
        if (eatToken(EQUAL)) {
            var initMarker = builder.mark();
            expectExpr();
            initMarker.done(CONTAINER_FIELD_INIT);
        }
        marker.done(CONTAINER_FIELD);
    }

    private void expectTopLevelDecl() {
        var marker = builder.mark();
        if (builder.getTokenType() == KEYWORD_PUB) {
            builder.advanceLexer();
        }
        var externExportInlineToken = builder.getTokenType();
        if (externExportInlineToken == KEYWORD_EXTERN
                || externExportInlineToken == KEYWORD_EXPORT
                || externExportInlineToken == KEYWORD_INLINE
                || externExportInlineToken == KEYWORD_NOINLINE) {
            throw new UnsupportedOperationException(); // TODO
        }
        builder.rawTokenIndex();
        if (tryParse(this::parseFnProto)) {
            throw new UnsupportedOperationException(); // TODO
        }
        eatToken(KEYWORD_THREADLOCAL);
        if (!tryParse(this::parseVarDeclProto)) {
            marker.drop();
            return;
        }
        if (eatToken(EQUAL)) {
            var initMarker = builder.mark();
            expectExpr();
            initMarker.done(VAR_DECL_INIT);
        }
        eatToken(SEMICOLON);
        marker.done(VAR_DECL);
    }

    private void parseVarDeclProto() {
        if (!eatToken(KEYWORD_CONST) && !eatToken(KEYWORD_VAR)) {
            return;
        }
        if (builder.getTokenType() == IDENTIFIER) {
            var nameMarker = builder.mark();
            builder.advanceLexer();
            nameMarker.done(VAR_DECL_NAME);
        }
        if (eatToken(COLON)) {
            var typeMarker = builder.mark();
            expectTypeExpr();
            typeMarker.done(VAR_DECL_TYPE);
        }
        parseByteAlign();
        parseAddrSpace();
        parseLinkSection();
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
        if (!eatToken(R_BRACE)) throw new UnsupportedOperationException(); // TODO
    }

    private void expectExpr() {
        assertToken(IDENTIFIER); // TODO
    }

    private void expectTypeExpr() {
        assertToken(IDENTIFIER); // TODO
    }

    private void parseByteAlign() {
        if (!eatToken(KEYWORD_ALIGN)) {
            return;
        }
        throw new UnsupportedOperationException(); // TODO
    }

    private void parseAddrSpace() {
        if (!eatToken(KEYWORD_ADDRSPACE)) {
            return;
        }
        throw new UnsupportedOperationException(); // TODO
    }

    private void parseLinkSection() {
        if (!eatToken(KEYWORD_LINKSECTION)) {
            return;
        }
        throw new UnsupportedOperationException(); // TODO
    }

    private void parseFnProto() {
        if (!eatToken(KEYWORD_FN)) {
            return;
        }
        throw new UnsupportedOperationException(); // TODO
    }

    private boolean tryParse(Runnable parse) {
        var oldTokenIndex = builder.rawTokenIndex();
        parse.run();
        var newTokenIndex = builder.rawTokenIndex();
        return oldTokenIndex != newTokenIndex;
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
