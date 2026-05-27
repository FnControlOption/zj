package io.github.fncontroloption.zig.parser;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

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
        if (!builder.eof()) {
            builder.error("Expected EOF, found " + builder.getTokenType());
        }
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
                    var comptimeMarker = builder.mark();
                    builder.advanceLexer();
                    parseBlock();
                    comptimeMarker.done(COMPTIME);
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
        if (parseFnProto()) {
            throw new UnsupportedOperationException(); // TODO
        }
        eatToken(KEYWORD_THREADLOCAL);
        if (!parseVarDeclProto()) {
            marker.drop();
            return;
        }
        if (eatToken(EQUAL)) {
            var initMarker = builder.mark();
            expectExpr();
            initMarker.done(VAR_DECL_INIT);
        }
        if (!eatToken(SEMICOLON)) {
            builder.error("Expected ';' after declaration");
        }
        marker.done(VAR_DECL);
    }

    private boolean parseVarDeclProto() {
        if (!eatToken(KEYWORD_CONST) && !eatToken(KEYWORD_VAR)) {
            return false;
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
        return true;
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

    private boolean parseExpr() {
        return parseExprPrecedence(0);
    }

    private void expectExpr() {
        if (!parseExpr()) {
            builder.error("Expected expression, found " + builder.getTokenType());
        }
    }

    private enum Assoc {
        LEFT,
        NONE,
    }

    private record OperInfo(int prec, IElementType nodeType, Assoc assoc) {
        OperInfo(int prec, IElementType nodeType) {
            this(prec, nodeType, Assoc.LEFT);
        }
    }

    private static final Map<IElementType, OperInfo> operTable = Map.ofEntries(
            Map.entry(KEYWORD_OR, new OperInfo(10, BOOL_OR)),

            Map.entry(KEYWORD_AND, new OperInfo(20, BOOL_AND)),

            Map.entry(EQUAL_EQUAL, new OperInfo(30, EQUAL_EQUAL, Assoc.NONE)),
            Map.entry(BANG_EQUAL, new OperInfo(30, BANG_EQUAL, Assoc.NONE)),
            Map.entry(ANGLE_BRACKET_LEFT, new OperInfo(30, LESS_THAN, Assoc.NONE)),
            Map.entry(ANGLE_BRACKET_RIGHT, new OperInfo(30, GREATER_THAN, Assoc.NONE)),
            Map.entry(ANGLE_BRACKET_LEFT_EQUAL, new OperInfo(30, LESS_OR_EQUAL, Assoc.NONE)),
            Map.entry(ANGLE_BRACKET_RIGHT_EQUAL, new OperInfo(30, GREATER_OR_EQUAL, Assoc.NONE)),

            Map.entry(AMPERSAND, new OperInfo(40, BIT_AND)),
            Map.entry(CARET, new OperInfo(40, BIT_XOR)),
            Map.entry(PIPE, new OperInfo(40, BIT_OR)),
            Map.entry(KEYWORD_ORELSE, new OperInfo(40, ORELSE)),
            Map.entry(KEYWORD_CATCH, new OperInfo(40, CATCH)),

            Map.entry(ANGLE_BRACKET_ANGLE_BRACKET_LEFT, new OperInfo(50, SHL)),
            Map.entry(ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE, new OperInfo(50, SHL_SAT)),
            Map.entry(ANGLE_BRACKET_ANGLE_BRACKET_RIGHT, new OperInfo(50, SHR)),

            Map.entry(PLUS, new OperInfo(60, ADD)),
            Map.entry(MINUS, new OperInfo(60, SUB)),
            Map.entry(PLUS_PLUS, new OperInfo(60, ARRAY_CAT)),
            Map.entry(PLUS_PERCENT, new OperInfo(60, ADD_WRAP)),
            Map.entry(MINUS_PERCENT, new OperInfo(60, SUB_WRAP)),
            Map.entry(PLUS_PIPE, new OperInfo(60, ADD_SAT)),
            Map.entry(MINUS_PIPE, new OperInfo(60, SUB_SAT)),

            Map.entry(PIPE_PIPE, new OperInfo(70, MERGE_ERROR_SETS)),
            Map.entry(ASTERISK, new OperInfo(70, MUL)),
            Map.entry(SLASH, new OperInfo(70, DIV)),
            Map.entry(PERCENT, new OperInfo(70, MOD)),
            Map.entry(ASTERISK_PERCENT, new OperInfo(70, MUL_WRAP)),
            Map.entry(ASTERISK_PIPE, new OperInfo(70, MUL_SAT))
    );

    private boolean parseExprPrecedence(int minPrec) {
        LOG.assertTrue(minPrec >= 0);
        var exprMarker = builder.mark();
        if (!parsePrefixExpr()) {
            exprMarker.drop();
            return false;
        }
        var bannedPrec = -1;
        while (!builder.eof()) {
            var operToken = builder.getTokenType();
            var info = operTable.get(operToken);
            if (info == null || info.prec < minPrec) {
                break;
            }
            if (info.prec == bannedPrec) {
                builder.error("Comparison operators cannot be chained");
            }
            builder.advanceLexer();
            if (operToken == KEYWORD_CATCH) {
                parsePayload();
            }
            if (!parseExprPrecedence(info.prec + 1)) {
                builder.error("Expected expression, found " + builder.getTokenType());
            }
            exprMarker.done(info.nodeType);
            exprMarker = exprMarker.precede();
            if (info.assoc == Assoc.NONE) {
                bannedPrec = info.prec;
            }
        }
        exprMarker.drop();
        return true;
    }

    private boolean parsePayload() {
        var marker = builder.mark();
        if (!eatToken(PIPE)) {
            marker.drop();
            return false;
        }
        expectToken(IDENTIFIER);
        expectToken(PIPE);
        marker.done(PAYLOAD);
        return false;
    }

    private static final Map<IElementType, IElementType> prefixTable = Map.ofEntries(
            Map.entry(BANG, BOOL_NOT),
            Map.entry(TILDE, BIT_NOT),
            Map.entry(MINUS, NEGATION),
            Map.entry(MINUS_PERCENT, NEGATION_WRAP),
            Map.entry(AMPERSAND, ADDRESS_OF),
            Map.entry(KEYWORD_TRY, TRY)
    );

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean parsePrefixExpr() {
        var nodeType = prefixTable.get(builder.getTokenType());
        if (nodeType == null) {
            return parsePrimaryExpr();
        }
        var marker = builder.mark();
        builder.advanceLexer();
        expectPrefixExpr();
        marker.done(nodeType);
        return true;
    }

    private void expectPrefixExpr() {
        if (!parsePrefixExpr()) {
            builder.error("Expected prefix expression, found " + builder.getTokenType());
        }
    }

    private boolean parsePrimaryExpr() {
        return parseCurlySuffixExpr(); // TODO
    }

    private boolean parseCurlySuffixExpr() {
        return parseTypeExpr(); // TODO
    }

    private boolean parseTypeExpr() {
        var tokenType = builder.getTokenType();
        if (tokenType == QUESTION_MARK) {
            var marker = builder.mark();
            builder.advanceLexer();
            expectTypeExpr();
            marker.done(OPTIONAL_TYPE);
            return true;
        } else if (tokenType == KEYWORD_ANYFRAME) {
            if (builder.lookAhead(1) == ARROW) {
                throw new UnsupportedOperationException(); // TODO
            } else {
                return parseErrorUnionExpr();
            }
        } else if (tokenType == ASTERISK) {
            throw new UnsupportedOperationException(); // TODO
        } else if (tokenType == L_BRACKET) {
            throw new UnsupportedOperationException(); // TODO
        } else {
            return parseErrorUnionExpr();
        }
    }

    private void expectTypeExpr() {
        if (!parseTypeExpr()) {
            builder.error("Expected type expression, found " + builder.getTokenType());
        }
    }

    private boolean parseErrorUnionExpr() {
        var marker = builder.mark();
        if (!parseSuffixExpr()) {
            marker.drop();
            return false;
        }
        if (!eatToken(BANG)) {
            marker.drop();
            return true;
        }
        expectTypeExpr();
        marker.done(ERROR_UNION);
        return true;
    }

    private boolean parseSuffixExpr() {
        return parsePrimaryTypeExpr(); // TODO
    }

    private static final Map<IElementType, IElementType> primaryTypeExprTable = Map.ofEntries(
            Map.entry(CHAR_LITERAL, CHAR_LITERAL),
            Map.entry(NUMBER_LITERAL, NUMBER_LITERAL),
            Map.entry(KEYWORD_UNREACHABLE, UNREACHABLE_LITERAL),
            Map.entry(KEYWORD_ANYFRAME, ANYFRAME_LITERAL),
            Map.entry(STRING_LITERAL, STRING_LITERAL)
    );

    private boolean parsePrimaryTypeExpr() {
        var tokenType = builder.getTokenType();
        var nodeType = primaryTypeExprTable.get(tokenType);
        if (nodeType != null) {
            var marker = builder.mark();
            builder.advanceLexer();
            marker.done(nodeType);
            return true;
        } else if (tokenType == BUILTIN) {
            return parseBuiltinCall();
        } else if (tokenType == KEYWORD_FN) {
            throw new UnsupportedOperationException(); // TODO
        } else if (tokenType == KEYWORD_IF) {
            throw new UnsupportedOperationException(); // TODO
        } else if (tokenType == KEYWORD_SWITCH) {
            throw new UnsupportedOperationException(); // TODO
        } else if (tokenType == KEYWORD_EXTERN
                || tokenType == KEYWORD_PACKED) {
            throw new UnsupportedOperationException(); // TODO
        } else if (tokenType == KEYWORD_STRUCT
                || tokenType == KEYWORD_OPAQUE
                || tokenType == KEYWORD_ENUM
                || tokenType == KEYWORD_UNION) {
            throw new UnsupportedOperationException(); // TODO
        } else if (tokenType == KEYWORD_COMPTIME) {
            var marker = builder.mark();
            builder.advanceLexer();
            expectTypeExpr();
            marker.done(COMPTIME);
            return true;
        } else if (tokenType == MULTILINE_STRING_LITERAL_LINE) {
            var marker = builder.mark();
            //noinspection StatementWithEmptyBody
            while (eatToken(MULTILINE_STRING_LITERAL_LINE)) {}
            marker.done(MULTILINE_STRING_LITERAL);
            return true;
        } else if (tokenType == IDENTIFIER) {
            if (builder.lookAhead(1) == COLON) {
                throw new UnsupportedOperationException(); // TODO
            } else {
                var marker = builder.mark();
                builder.advanceLexer();
                marker.done(IDENTIFIER);
                return true;
            }
        } else if (tokenType == KEYWORD_INLINE) {
            throw new UnsupportedOperationException(); // TODO
        } else if (tokenType == KEYWORD_FOR) {
            throw new UnsupportedOperationException(); // TODO
        } else if (tokenType == KEYWORD_WHILE) {
            throw new UnsupportedOperationException(); // TODO
        } else if (tokenType == PERIOD) {
            throw new UnsupportedOperationException(); // TODO
        } else if (tokenType == KEYWORD_ERROR) {
            throw new UnsupportedOperationException(); // TODO
        } else if (tokenType == L_PAREN) {
            var marker = builder.mark();
            builder.advanceLexer();
            expectExpr();
            expectToken(R_PAREN);
            marker.done(GROUPED_EXPRESSION);
            return true;
        } else {
            return false;
        }
    }

    @SuppressWarnings("SameReturnValue")
    private boolean parseBuiltinCall() {
        var marker = builder.mark();
        assertToken(BUILTIN);
        if (!eatToken(L_PAREN)) {
            builder.error("Expected parameter list, found " + builder.getTokenType());
            marker.done(IDENTIFIER);
            return true;
        }
        while (!builder.eof()) {
            if (eatToken(R_PAREN)) {
                break;
            }
            expectExpr();
            if (eatToken(COMMA)) {
                continue;
            }
            if (eatToken(R_PAREN)) {
                break;
            }
            builder.error("Expected ',' after argument");
        }
        marker.done(BUILTIN_CALL);
        return true;
    }

    private boolean parseByteAlign() {
        var marker = builder.mark();
        if (!eatToken(KEYWORD_ALIGN)) {
            marker.drop();
            return false;
        }
        expectToken(L_PAREN);
        expectExpr();
        expectToken(R_PAREN);
        marker.done(ALIGN);
        return true;
    }

    private boolean parseAddrSpace() {
        var marker = builder.mark();
        if (!eatToken(KEYWORD_ADDRSPACE)) {
            marker.drop();
            return false;
        }
        expectToken(L_PAREN);
        expectExpr();
        expectToken(R_PAREN);
        marker.done(ADDRSPACE);
        return true;
    }

    private boolean parseLinkSection() {
        var marker = builder.mark();
        if (!eatToken(KEYWORD_LINKSECTION)) {
            marker.drop();
            return false;
        }
        expectToken(L_PAREN);
        expectExpr();
        expectToken(R_PAREN);
        marker.done(LINKSECTION);
        return true;
    }

    private boolean parseFnProto() {
        if (!eatToken(KEYWORD_FN)) {
            return false;
        }
        throw new UnsupportedOperationException(); // TODO
    }

    private void assertToken(IElementType tokenType) {
        LOG.assertTrue(builder.getTokenType() == tokenType);
        builder.advanceLexer();
    }

    private void expectToken(IElementType tokenType) {
        if (builder.getTokenType() == tokenType) {
            builder.advanceLexer();
        } else {
            builder.error("Expected " + tokenType + ", found " + builder.getTokenType());
        }
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
