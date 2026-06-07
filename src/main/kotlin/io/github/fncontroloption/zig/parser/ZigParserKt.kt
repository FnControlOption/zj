package io.github.fncontroloption.zig.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.fncontroloption.zig.psi.ZigElementTypes
import io.github.fncontroloption.zig.psi.ZigTokenSets
import io.github.fncontroloption.zig.psi.ZigTokenTypes

// Based on https://codeberg.org/ziglang/zig/src/commit/72d954e/lib/std/zig/Parse.zig
/** @see com.intellij.java.syntax.parser.JavaParser */
class ZigParserKt : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val rootMarker = builder.mark()
        while (!builder.eof()) {
            if (parse(builder) == null) {
                builder.advanceLexer()
            }
        }
        rootMarker.done(root)
        return builder.treeBuilt
    }

    private fun parse(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseContainerDecl(builder)
            ?: parseBlock(builder)
            ?: parseBuiltinCall(builder)
            ?: parseVarDecl(builder)
            ?: parseFnDecl(builder)
            ?: parseErrorSetDecl(builder)
            ?: parseTestDecl(builder)
    }

    private fun parseContainerDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val container = builder.mark()
        if (!expect(builder, ZigTokenSets.CONTAINERS)) {
            container.drop()
            return null
        }
        parseParenEnclosed(builder, null, ZigElementTypes.CONTAINER_DECL_ARG)
        parseBraceEnclosed(builder, null, ZigElementTypes.CONTAINER_DECL_BODY)
        container.done(ZigElementTypes.CONTAINER_DECL)
        return container
    }

    private fun parseBlock(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseBraceEnclosed(builder, null, ZigElementTypes.BLOCK)
    }

    private fun parseBuiltinCall(builder: PsiBuilder): PsiBuilder.Marker? {
        val call = builder.mark()
        if (!expect(builder, ZigTokenTypes.BUILTIN)) {
            call.drop()
            return null
        }
        if (tryCallArgs(builder) == null) {
            parseParenEnclosed(builder, null, ZigElementTypes.CALL_ARGS)
        }
        call.done(ZigElementTypes.BUILTIN_CALL)
        return call
    }

    private fun parseVarDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        if (expect(builder, ZigTokenTypes.KEYWORD_EXTERN)) {
            expect(builder, ZigTokenTypes.STRING_LITERAL)
        } else {
            expect(builder, ZigTokenTypes.KEYWORD_EXPORT)
        }
        expect(builder, ZigTokenTypes.KEYWORD_THREADLOCAL)
        if (parseVarDeclProto(builder) == null) {
            decl.rollbackTo()
            return null
        }
        if (!expect(builder, ZigTokenTypes.EQUAL)) {
            decl.rollbackTo()
            return null
        }
        val init = builder.mark()
        if (tryExpr(builder) == null) {
            parseUntil(builder, ZigTokenTypes.SEMICOLON)
        }
        init.done(ZigElementTypes.VAR_DECL_INIT)
        expect(builder, ZigTokenTypes.SEMICOLON)
        decl.done(ZigElementTypes.VAR_DECL)
        return decl
    }

    private fun parseVarDeclProto(builder: PsiBuilder): PsiBuilder.Marker? {
        val proto = builder.mark()
        if (!expect(builder, ZigTokenSets.MUT_TOKENS)) {
            proto.drop()
            return null
        }
        if (!expect(builder, ZigTokenTypes.IDENTIFIER)) {
            proto.rollbackTo()
            return null
        }
        if (expect(builder, ZigTokenTypes.COLON)) {
            val type = builder.mark()
            if (tryTypeExpr(builder) == null) {
                type.drop()
                proto.rollbackTo()
                return null
            }
            type.done(ZigElementTypes.VAR_DECL_TYPE)
        }
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_ALIGN, ZigElementTypes.ALIGN)
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_ADDRSPACE, ZigElementTypes.ADDRSPACE)
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_LINKSECTION, ZigElementTypes.LINKSECTION)
        proto.done(ZigElementTypes.VAR_DECL_PROTO)
        return proto
    }

    private fun tryFnProto(builder: PsiBuilder, requireNames: Boolean, terminators: TokenSet?): PsiBuilder.Marker? {
        val proto = builder.mark()
        if (expect(builder, ZigTokenTypes.KEYWORD_EXTERN)) {
            expect(builder, ZigTokenTypes.STRING_LITERAL)
        } else {
            expect(builder, ZigTokenSets.EXPORT_INLINE)
        }
        if (!expect(builder, ZigTokenTypes.KEYWORD_FN)) {
            proto.rollbackTo()
            return null
        }
        val hasName = expect(builder, ZigTokenTypes.IDENTIFIER)
        if (!hasName && requireNames) {
            proto.rollbackTo()
            return null
        }
        if (tryFnParams(builder, requireNames) == null) {
            parseParenEnclosed(builder, null, ZigElementTypes.FN_PARAMS)
        }
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_ALIGN, ZigElementTypes.ALIGN)
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_ADDRSPACE, ZigElementTypes.ADDRSPACE)
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_LINKSECTION, ZigElementTypes.LINKSECTION)
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_CALLCONV, ZigElementTypes.CALLCONV)
        val returnType = builder.mark()
        expect(builder, ZigTokenTypes.BANG)
        if (tryTypeExpr(builder) == null) {
            if (terminators == null) {
                proto.rollbackTo()
                return null
            }
            parseUntil(builder, terminators)
        }
        returnType.done(ZigElementTypes.FN_RETURN_TYPE)
        proto.done(ZigElementTypes.FN_PROTO)
        return proto
    }

    private fun parseFnDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        if (tryFnProto(builder, true, ZigTokenSets.SEMICOLON_BRACE) == null) {
            decl.drop()
            return null
        }
        if (!expect(builder, ZigTokenTypes.SEMICOLON)) {
            parseBlock(builder)
        }
        decl.done(ZigElementTypes.FN_DECL)
        return decl
    }

    private fun tryFnParams(builder: PsiBuilder, requireNames: Boolean): PsiBuilder.Marker? {
        val params = builder.mark()
        if (!expect(builder, ZigTokenTypes.L_PAREN)) {
            params.drop()
            return null
        }
        while (!builder.eof()) {
            if (expect(builder, ZigTokenTypes.R_PAREN)) {
                break
            }
            val decl = builder.mark()
            expect(builder, ZigTokenSets.NOALIAS_COMPTIME)
            val hasName = expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
            if (!hasName && requireNames) {
                decl.drop()
                params.rollbackTo()
                return null
            }
            if (tryTypeExpr(builder) == null) {
                decl.drop()
                params.rollbackTo()
                return null
            }
            decl.done(ZigElementTypes.FN_PARAM_DECL)
            if (expect(builder, ZigTokenTypes.R_PAREN)) {
                break
            }
            expect(builder, ZigTokenTypes.COMMA)
        }
        params.done(ZigElementTypes.FN_PARAMS)
        return params
    }

    private fun parseErrorSetDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseBraceEnclosed(builder, ZigTokenTypes.KEYWORD_ERROR, ZigElementTypes.ERROR_SET_DECL)
    }

    private fun parseTestDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        if (!expect(builder, ZigTokenTypes.KEYWORD_TEST)) {
            decl.drop()
            return null
        }
        expect(builder, ZigTokenSets.TEST_NAME)
        parseBlock(builder)
        decl.done(ZigElementTypes.TEST_DECL)
        return decl
    }

    private fun tryTypeExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = builder.mark()
        if (expect(builder, ZigTokenTypes.QUESTION_MARK)) {
            if (tryTypeExpr(builder) != null) {
                expr.done(ZigElementTypes.OPTIONAL_TYPE)
                return expr
            } else {
                expr.rollbackTo()
                return null
            }
        } else if (expect(builder, ZigTokenTypes.KEYWORD_ANYFRAME, ZigTokenTypes.ARROW)) {
            if (tryTypeExpr(builder) != null) {
                expr.done(ZigElementTypes.ANYFRAME_TYPE)
                return expr
            } else {
                expr.rollbackTo()
                return null
            }
        } else if (expect(builder, ZigTokenTypes.ASTERISK)) {
            parsePtrModifiers(builder)
            if (tryTypeExpr(builder) != null) {
                expr.done(ZigElementTypes.PTR_TYPE)
                return expr
            } else {
                expr.rollbackTo()
                return null
            }
        } else if (expect(builder, ZigTokenTypes.L_BRACKET)) {
            if (!expect(builder, ZigTokenTypes.R_BRACKET)) {
                // TODO
                expr.rollbackTo()
                return null
            }
            parsePtrModifiers(builder)
            if (tryTypeExpr(builder) != null) {
                expr.done(ZigElementTypes.SLICE_TYPE)
                return expr
            } else {
                expr.rollbackTo()
                return null
            }
        } else {
            expr.drop()
            return tryErrorUnionExpr(builder)
        }
    }

    private fun parsePtrModifiers(builder: PsiBuilder) {
        // TODO
        expect(builder, ZigTokenTypes.KEYWORD_CONST)
    }

    private fun tryErrorUnionExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        var expr = trySuffixExpr(builder) ?: return null
        if (!expect(builder, ZigTokenTypes.BANG)) {
            return expr
        }
        expr = expr.precede()
        if (tryTypeExpr(builder) == null) {
            expr.rollbackTo()
            return null
        }
        expr.done(ZigElementTypes.ERROR_UNION)
        return expr
    }

    private fun trySuffixExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        var res = tryPrimaryTypeExpr(builder) ?: return null
        while (!builder.eof()) {
            val suffixOp = parseSuffixOp(builder)
            if (suffixOp != null) {
                res = res.precede()
                res.done(suffixOp)
                continue
            }
            res = tryCall(builder, res) ?: return res
        }
        return res
    }

    private fun tryCall(builder: PsiBuilder, lhs: PsiBuilder.Marker): PsiBuilder.Marker? {
        val call = lhs.precede()
        var callArgs = tryCallArgs(builder)
        if (callArgs == null) {
            callArgs = parseParenEnclosed(builder, null, ZigElementTypes.CALL_ARGS)
        }
        if (callArgs == null) {
            call.drop()
            return null
        }
        call.done(ZigElementTypes.CALL)
        return call
    }

    private fun tryCallArgs(builder: PsiBuilder): PsiBuilder.Marker? {
        val args = builder.mark()
        if (!expect(builder, ZigTokenTypes.L_PAREN)) {
            args.drop()
            return null
        }
        while (!builder.eof()) {
            if (expect(builder, ZigTokenTypes.R_PAREN)) {
                break
            }
            if (tryExpr(builder) == null) {
                args.rollbackTo()
                return null
            }
            if (expect(builder, ZigTokenTypes.R_PAREN)) {
                break
            }
            expect(builder, ZigTokenTypes.COMMA)
        }
        args.done(ZigElementTypes.CALL_ARGS)
        return args
    }

    private fun parseSuffixOp(builder: PsiBuilder): IElementType? {
        val marker = builder.mark()
        if (expect(builder, ZigTokenTypes.PERIOD)) {
            if (expect(builder, ZigTokenTypes.QUESTION_MARK)) {
                marker.drop()
                return ZigElementTypes.UNWRAP_OPTIONAL
            } else {
                expect(builder, ZigTokenTypes.IDENTIFIER)
                marker.drop()
                return ZigElementTypes.FIELD_ACCESS
            }
        }
        // TODO
        marker.rollbackTo()
        return null
    }

    private fun tryPrimaryTypeExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val marker = builder.mark()
        if (expect(builder, ZigTokenTypes.CHAR_LITERAL)) {
            marker.done(ZigElementTypes.CHAR_LITERAL)
            return marker
        } else if (expect(builder, ZigTokenTypes.NUMBER_LITERAL)) {
            marker.done(ZigElementTypes.NUMBER_LITERAL)
            return marker
        } else if (expect(builder, ZigTokenTypes.KEYWORD_UNREACHABLE)) {
            marker.done(ZigElementTypes.UNREACHABLE_LITERAL)
            return marker
        } else if (expect(builder, ZigTokenTypes.KEYWORD_ANYFRAME)) {
            marker.done(ZigElementTypes.ANYFRAME_LITERAL)
            return marker
        } else if (expect(builder, ZigTokenTypes.STRING_LITERAL)) {
            marker.done(ZigElementTypes.STRING_LITERAL)
            return marker
        } else if (expect(builder, ZigTokenTypes.IDENTIFIER)) {
            if (builder.tokenType === ZigTokenTypes.COLON) {
                // TODO
                marker.rollbackTo()
                return null
            }
            marker.done(ZigElementTypes.IDENTIFIER)
            return marker
        } else if (builder.tokenType === ZigTokenTypes.KEYWORD_FN) {
            marker.drop()
            return tryFnProto(builder, false, null)
        }
        // TODO
        marker.rollbackTo()
        return null
    }

    private fun tryExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        // TODO
        return tryExprPrecedence(builder, 0)
    }

    private fun tryExprPrecedence(builder: PsiBuilder, minPrec: Int): PsiBuilder.Marker? {
        // TODO
        return tryPrefixExpr(builder)
    }

    private fun tryPrefixExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        // TODO
        return tryPrimaryExpr(builder)
    }

    private fun tryPrimaryExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        // TODO
        return tryCurlySuffixExpr(builder)
    }

    private fun tryCurlySuffixExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        // TODO
        return tryTypeExpr(builder)
    }

    private fun parseUntil(builder: PsiBuilder, terminator: IElementType) {
        parseUntil(builder) { it === terminator }
    }

    private fun parseUntil(builder: PsiBuilder, terminators: TokenSet) {
        parseUntil(builder) { it in terminators }
    }

    private inline fun parseUntil(builder: PsiBuilder, terminator: (IElementType?) -> Boolean) {
        var braces = 0
        var brackets = 0
        var parens = 0
        while (!builder.eof()) {
            if (terminator(builder.tokenType) && braces == 0 && brackets == 0 && parens == 0) {
                break
            } else if (parse(builder) != null) {
                continue
            } else if (builder.tokenType === ZigTokenTypes.L_BRACE) {
                braces++
            } else if (builder.tokenType === ZigTokenTypes.R_BRACE) {
                braces--
            } else if (builder.tokenType === ZigTokenTypes.L_BRACKET) {
                brackets++
            } else if (builder.tokenType === ZigTokenTypes.R_BRACKET) {
                brackets--
            } else if (builder.tokenType === ZigTokenTypes.L_PAREN) {
                parens++
            } else if (builder.tokenType === ZigTokenTypes.R_PAREN) {
                parens--
            }
            builder.advanceLexer()
        }
    }

    private fun parseParenEnclosed(builder: PsiBuilder, prefix: IElementType?, type: IElementType): PsiBuilder.Marker? {
        return parseEnclosed(builder, prefix, ZigTokenTypes.L_PAREN, ZigTokenTypes.R_PAREN, type)
    }

    private fun parseBraceEnclosed(builder: PsiBuilder, prefix: IElementType?, type: IElementType): PsiBuilder.Marker? {
        return parseEnclosed(builder, prefix, ZigTokenTypes.L_BRACE, ZigTokenTypes.R_BRACE, type)
    }

    private fun parseEnclosed(
        builder: PsiBuilder,
        prefix: IElementType?,
        left: IElementType,
        right: IElementType,
        type: IElementType,
    ): PsiBuilder.Marker? {
        val marker = builder.mark()
        if (prefix != null && !expect(builder, prefix)) {
            marker.drop()
            return null
        }
        if (!expect(builder, left)) {
            marker.rollbackTo()
            return null
        }
        var depth = 1
        while (!builder.eof() && depth > 0) {
            if (parse(builder) != null) {
                continue
            } else if (builder.tokenType === left) {
                depth += 1
            } else if (builder.tokenType === right) {
                depth -= 1
            }
            builder.advanceLexer()
        }
        marker.done(type)
        return marker
    }

    private fun expect(builder: PsiBuilder, expectedType: IElementType?): Boolean {
        if (builder.tokenType === expectedType) {
            builder.advanceLexer()
            return true
        }
        return false
    }

    private fun expect(builder: PsiBuilder, vararg expectedTypes: IElementType?): Boolean {
        for (i in expectedTypes.indices) {
            if (builder.lookAhead(i) != expectedTypes[i]) {
                return false
            }
        }
        repeat(expectedTypes.size) {
            builder.advanceLexer()
        }
        return true
    }

    private fun expect(builder: PsiBuilder, expectedTypes: TokenSet): Boolean {
        if (builder.tokenType in expectedTypes) {
            builder.advanceLexer()
            return true
        }
        return false
    }
}
