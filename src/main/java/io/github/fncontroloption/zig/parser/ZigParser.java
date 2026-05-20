package io.github.fncontroloption.zig.parser;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.tree.IElementType;
import io.github.fncontroloption.zig.psi.ZigTokenSets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedList;

import static io.github.fncontroloption.zig.psi.ZigTypes.*;

// Based on https://codeberg.org/ziglang/zig/src/commit/72d954e/lib/std/zig/Parse.zig
public class ZigParser implements PsiParser {
    private static final Logger LOG = Logger.getInstance(ZigParser.class);
    private PsiBuilder builder;
    private LinkedList<OpenNode> openNodes;

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        this.builder = builder;
        this.openNodes = new LinkedList<>();
        parseRoot(root);
        LOG.assertTrue(openNodes.isEmpty());
        return builder.getTreeBuilt();
    }

    private void open(PsiBuilder.Marker marker, @Nullable IElementType closingToken) {
        openNodes.addLast(new OpenNode(marker, closingToken));
    }

    private void close(PsiBuilder.Marker marker) {
        var open = openNodes.removeLast();
        LOG.assertTrue(open.marker == marker);
    }

    private void parseRoot(IElementType root) {
        var rootMarker = builder.mark();
        open(rootMarker, null);
        parseContainerMembers(null);
        close(rootMarker);
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

    private void parseBlock() {
        if (builder.getTokenType() != L_BRACE) {
            return;
        }
        var blockMarker = builder.mark();
        open(blockMarker, R_BRACE);
        builder.advanceLexer();
        while (!builder.eof()) {
            if (builder.getTokenType() == R_BRACE) {
                builder.advanceLexer();
                break;
            }
            skipUnexpectedToken();
        }
        close(blockMarker);
        blockMarker.done(BLOCK);
    }

    private void parseContainerDecl() {
        if (!ZigTokenSets.CONTAINER.contains(builder.getTokenType())) {
            return;
        }
        var containerDeclMarker = builder.mark();
        builder.advanceLexer();
        if (builder.getTokenType() == L_BRACE) {
            builder.advanceLexer();
        }
        open(containerDeclMarker, R_BRACE);
        while (!builder.eof()) {
            if (builder.getTokenType() == R_BRACE) {
                builder.advanceLexer();
                break;
            }
            skipUnexpectedToken();
        }
        close(containerDeclMarker);
        containerDeclMarker.done(CONTAINER_DECL);
    }

    private void parseFn() {
        if (builder.getTokenType() != KEYWORD_FN) {
            return;
        }
        var fnProtoMarker = builder.mark();
        builder.advanceLexer();
        if (builder.getTokenType() == IDENTIFIER) {
            builder.advanceLexer();
        }
        fnProtoMarker.done(FN_PROTO);
    }

    private void parseTestDecl() {
        if (builder.getTokenType() != KEYWORD_TEST) {
            return;
        }
        var testDeclMarker = builder.mark();
        builder.advanceLexer();
        if (ZigTokenSets.TEST_NAME.contains(builder.getTokenType())) {
            var testNameMarker = builder.mark();
            builder.advanceLexer();
            testNameMarker.done(TEST_NAME);
        }
        parseBlock();
        testDeclMarker.done(TEST_DECL);
    }

    private void parseMultilineStringLiteral() {
        if (builder.getTokenType() != MULTILINE_STRING_LITERAL_LINE) {
            return;
        }
        var marker = builder.mark();
        while (builder.getTokenType() == MULTILINE_STRING_LITERAL_LINE) {
            builder.advanceLexer();
        }
        marker.done(MULTILINE_STRING_LITERAL);
    }

    private void parseStringLiteral() {
        if (builder.getTokenType() != STRING_LITERAL) {
            return;
        }
        var marker = builder.mark();
        builder.advanceLexer();
        marker.done(STRING_LITERAL);
    }

    private void skipUnexpectedToken() {
        if (ZigTokenSets.CONTAINER.contains(builder.getTokenType())) {
            parseContainerDecl();
        } else if (builder.getTokenType() == KEYWORD_FN) {
            parseFn();
        } else if (builder.getTokenType() == KEYWORD_TEST) {
            parseTestDecl();
        } else if (builder.getTokenType() == L_BRACE) {
            parseBlock();
        } else if (builder.getTokenType() == MULTILINE_STRING_LITERAL_LINE) {
            parseMultilineStringLiteral();
        } else if (builder.getTokenType() == STRING_LITERAL) {
            parseStringLiteral();
        } else {
            builder.advanceLexer();
        }
    }

    private record OpenNode(PsiBuilder.Marker marker, @Nullable IElementType closingToken) {}
}
