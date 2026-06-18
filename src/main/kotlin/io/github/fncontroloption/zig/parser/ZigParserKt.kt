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
    private val enclosureStack = ArrayDeque<IElementType>()

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        enclosureStack.addLast(root)
        val rootMarker = builder.mark()
        while (!builder.eof()) {
            if (parseContainerMember(builder) == null) {
                builder.advanceLexer()
            }
        }
        rootMarker.done(root)
        enclosureStack.removeLast()
        return builder.treeBuilt
    }

    private fun parseContainerMember(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseTestDecl(builder)
            ?: parseVarDecl(builder)
            ?: parseFnDecl(builder)
            ?: parseContainerField(builder)
    }

    private fun parseContainerDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val container = builder.mark()
        expect(builder, ZigTokenSets.EXTERN_PACKED)
        if (!expect(builder, ZigTokenSets.CONTAINERS)) {
            container.rollbackTo()
            return null
        }
        parseParenEnclosed(builder, null, ZigElementTypes.CONTAINER_DECL_ARG)
        parseBraceEnclosed(builder, null, ZigElementTypes.CONTAINER_DECL_BODY) {
            parseContainerMember(builder)
        }
        container.done(ZigElementTypes.CONTAINER_DECL)
        return container
    }

    private fun parseBlock(builder: PsiBuilder): PsiBuilder.Marker? {
        val prevToken = builder.rawLookup(-1)
        if (prevToken === ZigTokenTypes.PERIOD) {
            return null
        }

        return parseBraceEnclosed(builder, null, ZigElementTypes.BLOCK) {
            tryStatement(builder, true)
        }
    }

    private fun parseBlockExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = builder.mark()
        expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
        if (builder.tokenType !== ZigTokenTypes.L_BRACE) {
            expr.rollbackTo()
            return null
        }
        parseBlock(builder)
        expr.done(ZigElementTypes.BLOCK_EXPR)
        return expr
    }

    private fun parseBlockExprStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        // If block found, don't require semicolon
        val blockExpr = parseBlockExpr(builder)
        if (blockExpr != null) {
            return blockExpr
        }
        // Otherwise, require semicolon after expression
        val statement = builder.mark()
        val assignExpr = parseAssignExpr(builder)
        if (assignExpr != null) {
            if (!expect(builder, ZigTokenTypes.SEMICOLON)) {
                // TODO
                statement.rollbackTo()
                return null
            }
            statement.done(ZigElementTypes.EXPR_STATEMENT)
            return statement
        }
        statement.drop()
        return null
    }

    private fun parseBuiltinCall(builder: PsiBuilder): PsiBuilder.Marker? {
        return tryBuiltinCall(builder) ?: parseParenEnclosed(builder, ZigTokenTypes.BUILTIN, ZigElementTypes.CALL_ARGS)
    }

    private fun tryBuiltinCall(builder: PsiBuilder): PsiBuilder.Marker? {
        val call = builder.mark()
        if (!expect(builder, ZigTokenTypes.BUILTIN)) {
            call.drop()
            return null
        }
        if (tryCallArgs(builder) == null) {
            call.rollbackTo()
            return null
        }
        call.done(ZigElementTypes.BUILTIN_CALL)
        return call
    }

    private fun parseVarDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        expect(builder, ZigTokenTypes.KEYWORD_PUB)
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
        parseInitExpr(builder, ZigTokenTypes.SEMICOLON)
        if (!expect(builder, ZigTokenTypes.SEMICOLON)) {
            // TODO: mark error
        }
        decl.done(ZigElementTypes.VAR_DECL)
        return decl
    }

    private fun parseVarDeclProto(builder: PsiBuilder): PsiBuilder.Marker? {
        val prevToken = builder.rawLookup(-1)
        if (prevToken in ZigTokenSets.ASTERISK_BRACKET_PAREN) {
            // e.g. *const Foo, []const Foo, *align(16) const Foo
            return null
        }
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
            type.done(ZigElementTypes.TYPE_EXPR)
        }
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_ALIGN, ZigElementTypes.ALIGN)
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_ADDRSPACE, ZigElementTypes.ADDRSPACE)
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_LINKSECTION, ZigElementTypes.LINKSECTION)
        proto.done(ZigElementTypes.VAR_DECL_PROTO)
        return proto
    }

    private fun parseInitExpr(builder: PsiBuilder, terminator: IElementType) {
        parseInitExpr(builder) { it === terminator }
    }

    private fun parseInitExpr(builder: PsiBuilder, terminator: TokenSet) {
        parseInitExpr(builder) { it in terminator }
    }

    private fun parseInitExpr(builder: PsiBuilder, terminator: (IElementType?) -> Boolean) {
        val init = builder.mark()
        if (tryExpr(builder) == null) {
            parseUntil(builder, terminator)
        }
        init.done(ZigElementTypes.INIT_EXPR)
    }

    private fun tryFnProto(builder: PsiBuilder, requireNames: Boolean, terminators: TokenSet?): PsiBuilder.Marker? {
        val proto = builder.mark()
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
        expect(builder, ZigTokenTypes.BANG)
        val returnType = builder.mark()
        if (tryTypeExpr(builder) == null) {
            if (terminators == null) {
                proto.rollbackTo()
                return null
            }
            parseUntil(builder, terminators)
        }
        returnType.done(ZigElementTypes.TYPE_EXPR)
        proto.done(ZigElementTypes.FN_PROTO)
        return proto
    }

    private fun parseFnDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        expect(builder, ZigTokenTypes.KEYWORD_PUB)
        if (expect(builder, ZigTokenTypes.KEYWORD_EXTERN)) {
            expect(builder, ZigTokenTypes.STRING_LITERAL)
        } else {
            expect(builder, ZigTokenSets.EXPORT_INLINE)
        }
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
            if (tryFnParamDecl(builder, requireNames) == null) {
                params.rollbackTo()
                return null
            }
            if (expect(builder, ZigTokenTypes.R_PAREN)) {
                break
            }
            expect(builder, ZigTokenTypes.COMMA)
        }
        params.done(ZigElementTypes.FN_PARAMS)
        return params
    }

    private fun tryFnParamDecl(builder: PsiBuilder, requireNames: Boolean): PsiBuilder.Marker? {
        val decl = builder.mark()
        if (expect(builder, ZigTokenTypes.ELLIPSIS3)) {
            decl.done(ZigElementTypes.FN_VARARGS)
            return decl
        }
        expect(builder, ZigTokenSets.NOALIAS_COMPTIME)
        val hasName = expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
        if (!hasName && requireNames) {
            decl.rollbackTo()
            return null
        }
        if (!expect(builder, ZigTokenTypes.KEYWORD_ANYTYPE)) {
            val type = builder.mark()
            if (tryTypeExpr(builder) == null) {
                type.drop()
                decl.rollbackTo()
                return null
            }
            type.done(ZigElementTypes.TYPE_EXPR)
        }
        decl.done(ZigElementTypes.FN_PARAM_DECL)
        return decl
    }

    private fun parseErrorSetDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseBraceEnclosed(builder, ZigTokenTypes.KEYWORD_ERROR, ZigElementTypes.ERROR_SET_DECL) {
            parseErrorSetMember(builder)
        }
    }

    private fun parseErrorSetMember(builder: PsiBuilder): PsiBuilder.Marker? {
        val field = builder.mark()
        if (!expect(builder, ZigTokenTypes.IDENTIFIER)) {
            field.drop()
            return null
        }
        expect(builder, ZigTokenTypes.COMMA)
        field.drop()
        return field
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

    private fun parseContainerField(builder: PsiBuilder): PsiBuilder.Marker? {
        val enclosure = enclosureStack.last()
        when (enclosure) {
            ZigParserDefinition.FILE, ZigElementTypes.CONTAINER_DECL_BODY -> {}
            else -> return null
        }

        val prevToken = builder.rawLookup(-1)
        if (prevToken in ZigTokenSets.MUT_TOKENS) {
            return null
        }

        val field = builder.mark()
        expect(builder, ZigTokenTypes.KEYWORD_COMPTIME)
        if (!expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)) {
            field.rollbackTo()
            return null
        }
        val type = builder.mark()
        if (tryTypeExpr(builder) == null) {
            type.drop()
            field.rollbackTo()
            return null
        }
        type.done(ZigElementTypes.TYPE_EXPR)
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_ALIGN, ZigElementTypes.ALIGN)
        if (expect(builder, ZigTokenTypes.EQUAL)) {
            parseInitExpr(builder, ZigTokenSets.COMMA_BRACE)
        }
        expect(builder, ZigTokenTypes.COMMA)
        field.done(ZigElementTypes.CONTAINER_FIELD)
        return field
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
                expr.done(ZigElementTypes.PTR_TYPE)
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
                if (!expect(builder, ZigTokenTypes.IDENTIFIER)) {
                    // TODO: mark error
                }
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
        } else if (builder.tokenType === ZigTokenTypes.BUILTIN) {
            marker.drop()
            return parseBuiltinCall(builder)
        } else if (builder.tokenType === ZigTokenTypes.KEYWORD_FN) {
            marker.drop()
            return tryFnProto(builder, false, null)
        } else if (builder.tokenType === ZigTokenTypes.KEYWORD_IF) {
            marker.rollbackTo()
            return tryIf(builder, this::tryTypeExpr)
        } else if (builder.tokenType === ZigTokenTypes.KEYWORD_SWITCH) {
            // TODO
            marker.rollbackTo()
            return null
        } else if (builder.tokenType in ZigTokenSets.EXTERN_PACKED || builder.tokenType in ZigTokenSets.CONTAINERS) {
            marker.drop()
            return parseContainerDecl(builder)
        } else if (expect(builder, ZigTokenTypes.KEYWORD_COMPTIME)) {
            if (tryTypeExpr(builder) == null) {
                marker.rollbackTo()
                return null
            }
            marker.done(ZigElementTypes.COMPTIME)
            return marker
        } else if (expect(builder, ZigTokenTypes.MULTILINE_STRING_LITERAL_LINE)) {
            // TODO
            marker.rollbackTo()
            return null
        } else if (expect(builder, ZigTokenTypes.KEYWORD_INLINE)) {
            // TODO
            marker.rollbackTo()
            return null
        } else if (expect(builder, ZigTokenTypes.KEYWORD_FOR)) {
            // TODO
            marker.rollbackTo()
            return null
        } else if (expect(builder, ZigTokenTypes.KEYWORD_WHILE)) {
            // TODO
            marker.rollbackTo()
            return null
        } else if (expect(builder, ZigTokenTypes.PERIOD)) {
            // TODO
            marker.rollbackTo()
            return null
        } else if (builder.tokenType === ZigTokenTypes.KEYWORD_ERROR) {
            val decl = parseErrorSetDecl(builder)
            if (decl != null) {
                marker.drop()
                return decl
            }
            expect(builder, ZigTokenTypes.KEYWORD_ERROR)
            if (!expect(builder, ZigTokenTypes.PERIOD)) {
                marker.rollbackTo()
                return null
            }
            expect(builder, ZigTokenTypes.IDENTIFIER)
            marker.done(ZigElementTypes.ERROR_VALUE)
            return marker
        } else if (expect(builder, ZigTokenTypes.L_PAREN)) {
            if (tryExpr(builder) == null) {
                marker.rollbackTo()
                return null
            }
            if (!expect(builder, ZigTokenTypes.R_PAREN)) {
                marker.rollbackTo()
                return null
            }
            marker.done(ZigElementTypes.GROUPED_EXPRESSION)
            return marker
        } else {
            marker.drop()
            return null

        }
    }

    private fun tryExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        // TODO
        return tryExprPrecedence(builder, 0)
    }

    private enum class Assoc { LEFT, NONE }

    private class OperInfo(val prec: Int, val nodeType: IElementType, val assoc: Assoc = Assoc.LEFT)

    private val operTable = mapOf(
        Pair(ZigTokenTypes.KEYWORD_OR, OperInfo(10, ZigElementTypes.BOOL_OR)),

        Pair(ZigTokenTypes.KEYWORD_AND, OperInfo(20, ZigElementTypes.BOOL_AND)),

        Pair(ZigTokenTypes.EQUAL_EQUAL, OperInfo(30, ZigElementTypes.EQUAL_EQUAL, Assoc.NONE)),
        Pair(ZigTokenTypes.BANG_EQUAL, OperInfo(30, ZigElementTypes.BANG_EQUAL, Assoc.NONE)),
        Pair(ZigTokenTypes.ANGLE_BRACKET_LEFT, OperInfo(30, ZigElementTypes.LESS_THAN, Assoc.NONE)),
        Pair(ZigTokenTypes.ANGLE_BRACKET_RIGHT, OperInfo(30, ZigElementTypes.GREATER_THAN, Assoc.NONE)),
        Pair(ZigTokenTypes.ANGLE_BRACKET_LEFT_EQUAL, OperInfo(30, ZigElementTypes.LESS_OR_EQUAL, Assoc.NONE)),
        Pair(ZigTokenTypes.ANGLE_BRACKET_RIGHT_EQUAL, OperInfo(30, ZigElementTypes.GREATER_OR_EQUAL, Assoc.NONE)),

        Pair(ZigTokenTypes.AMPERSAND, OperInfo(40, ZigElementTypes.BIT_AND)),
        Pair(ZigTokenTypes.CARET, OperInfo(40, ZigElementTypes.BIT_XOR)),
        Pair(ZigTokenTypes.PIPE, OperInfo(40, ZigElementTypes.BIT_OR)),
        Pair(ZigTokenTypes.KEYWORD_ORELSE, OperInfo(40, ZigElementTypes.ORELSE)),
        Pair(ZigTokenTypes.KEYWORD_CATCH, OperInfo(40, ZigElementTypes.CATCH)),

        Pair(ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT, OperInfo(50, ZigElementTypes.SHL)),
        Pair(ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE, OperInfo(50, ZigElementTypes.SHL_SAT)),
        Pair(ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT, OperInfo(50, ZigElementTypes.SHR)),

        Pair(ZigTokenTypes.PLUS, OperInfo(60, ZigElementTypes.ADD)),
        Pair(ZigTokenTypes.MINUS, OperInfo(60, ZigElementTypes.SUB)),
        Pair(ZigTokenTypes.PLUS_PLUS, OperInfo(60, ZigElementTypes.ARRAY_CAT)),
        Pair(ZigTokenTypes.PLUS_PERCENT, OperInfo(60, ZigElementTypes.ADD_WRAP)),
        Pair(ZigTokenTypes.MINUS_PERCENT, OperInfo(60, ZigElementTypes.SUB_WRAP)),
        Pair(ZigTokenTypes.PLUS_PIPE, OperInfo(60, ZigElementTypes.ADD_SAT)),
        Pair(ZigTokenTypes.MINUS_PIPE, OperInfo(60, ZigElementTypes.SUB_SAT)),

        Pair(ZigTokenTypes.PIPE_PIPE, OperInfo(70, ZigElementTypes.MERGE_ERROR_SETS)),
        Pair(ZigTokenTypes.ASTERISK, OperInfo(70, ZigElementTypes.MUL)),
        Pair(ZigTokenTypes.SLASH, OperInfo(70, ZigElementTypes.DIV)),
        Pair(ZigTokenTypes.PERCENT, OperInfo(70, ZigElementTypes.MOD)),
        Pair(ZigTokenTypes.ASTERISK_PERCENT, OperInfo(70, ZigElementTypes.MUL_WRAP)),
        Pair(ZigTokenTypes.ASTERISK_PIPE, OperInfo(70, ZigElementTypes.MUL_SAT)),
    )

    private fun tryExprPrecedence(builder: PsiBuilder, minPrec: Int): PsiBuilder.Marker? {
        assert(minPrec >= 0)
        var expr = tryPrefixExpr(builder) ?: return null
        var bannedPrec = -1
        while (!builder.eof()) {
            val operToken = builder.tokenType
            val info = operTable[operToken]
            if (info == null || info.prec < minPrec) {
                break
            }
            if (info.prec == bannedPrec) {
                builder.error("Comparison operators cannot be chained");
            }
            builder.advanceLexer()
            if (operToken == ZigTokenTypes.KEYWORD_CATCH) {
                // TODO: parsePayload
            }
            if (tryExprPrecedence(builder, info.prec + 1) == null) {
                // TODO: mark error
            }
            expr = expr.precede()
            expr.done(info.nodeType)
            if (info.assoc == Assoc.NONE) {
                bannedPrec = info.prec
            }
        }
        return expr
    }

    private val prefixTable = mapOf(
        Pair(ZigTokenTypes.BANG, ZigElementTypes.BOOL_NOT),
        Pair(ZigTokenTypes.MINUS, ZigElementTypes.NEGATION),
        Pair(ZigTokenTypes.TILDE, ZigElementTypes.BIT_NOT),
        Pair(ZigTokenTypes.MINUS_PERCENT, ZigElementTypes.NEGATION_WRAP),
        Pair(ZigTokenTypes.AMPERSAND, ZigElementTypes.ADDRESS_OF),
        Pair(ZigTokenTypes.KEYWORD_TRY, ZigElementTypes.TRY),
    )

    private fun tryPrefixExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val nodeType = prefixTable[builder.tokenType] ?: return tryPrimaryExpr(builder)
        val expr = builder.mark()
        builder.advanceLexer()
        if (tryPrefixExpr(builder) == null) {
            // TODO: mark error
        }
        expr.done(nodeType)
        return expr
    }

    private fun tryPrimaryExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = builder.mark()
        when (builder.tokenType) {
            // TODO
            ZigTokenTypes.KEYWORD_BREAK -> {
                builder.advanceLexer()
                expect(builder, ZigTokenTypes.COLON, ZigTokenTypes.IDENTIFIER)
                tryExpr(builder)
                expr.done(ZigElementTypes.BREAK)
            }
            ZigTokenTypes.KEYWORD_CONTINUE -> {
                builder.advanceLexer()
                expect(builder, ZigTokenTypes.COLON, ZigTokenTypes.IDENTIFIER)
                tryExpr(builder)
                expr.done(ZigElementTypes.CONTINUE)
            }
            ZigTokenTypes.KEYWORD_COMPTIME -> {
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementTypes.COMPTIME)
            }
            ZigTokenTypes.KEYWORD_NOSUSPEND -> {
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementTypes.NOSUSPEND)
            }
            ZigTokenTypes.KEYWORD_RESUME -> {
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementTypes.RESUME)
            }
            ZigTokenTypes.KEYWORD_RETURN -> {
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementTypes.RETURN)
            }
            // TODO
            ZigTokenTypes.L_BRACE -> {
                expr.drop()
                return parseBlock(builder)
            }
            else -> {
                expr.drop()
                return tryCurlySuffixExpr(builder)
            }
        }
        return expr
    }

    private fun tryCurlySuffixExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        // TODO
        return tryTypeExpr(builder)
    }

    private fun tryStatement(builder: PsiBuilder, isBlockLevel: Boolean): PsiBuilder.Marker? {
        val statement = builder.mark()
        if (expect(builder, ZigTokenTypes.KEYWORD_COMPTIME)) {
            // TODO
            statement.drop()
            return null
        }
        when (builder.tokenType) {
            // TODO
            ZigTokenTypes.KEYWORD_DEFER -> {
                if (isBlockLevel) {
                    builder.advanceLexer()
                    parseBlockExprStatement(builder)
                    statement.done(ZigElementTypes.DEFER)
                    return statement
                }
            }
            ZigTokenTypes.KEYWORD_ERRDEFER -> {
                if (isBlockLevel) {
                    builder.advanceLexer()
                    parseBlockExprStatement(builder)
                    statement.done(ZigElementTypes.ERRDEFER)
                    return statement
                }
            }
            // TODO
        }
        // TODO
        if (isBlockLevel) {
            statement.drop()
            return tryVarDeclExprStatement(builder)
        } else {
            // TODO
            statement.drop()
            return null
        }
    }

    private fun tryVarDeclExprStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        /*
        val prevToken = builder.rawLookup(-1)
        if (prevToken === ZigTokenTypes.EQUAL_ANGLE_BRACKET_RIGHT) {
            // e.g. `switch (a) { .b => c = d, ... }`
            return null
        }
        */

        val statement = builder.mark()
        val scratch = mutableListOf<PsiBuilder.Marker>()

        while (true) {
            val varDeclProto = parseVarDeclProto(builder)
            if (varDeclProto != null) {
                scratch.add(varDeclProto)
            } else {
                val expr = tryExpr(builder)
                if (expr == null) {
                    statement.rollbackTo()
                    return null
                }
                scratch.add(expr)
            }
            if (!expect(builder, ZigTokenTypes.COMMA)) {
                break
            }
        }

        assert(scratch.isNotEmpty())

        if (!expect(builder, ZigTokenTypes.EQUAL)) {
            if (scratch.size > 1) {
                // TODO
                statement.rollbackTo()
                return null
            }
            val lhs = scratch[0]
            if (lhs.tokenType === ZigElementTypes.VAR_DECL_PROTO) {
                // TODO
                statement.rollbackTo()
                return null
            }
            val expr = finishAssignExpr(builder, lhs)
            if (!expect(builder, ZigTokenTypes.SEMICOLON)) {
                // TODO
                statement.rollbackTo()
                return null
            }
            statement.done(ZigElementTypes.EXPR_STATEMENT)
            return expr
        }

        parseInitExpr(builder, ZigTokenTypes.SEMICOLON)
        if (!expect(builder, ZigTokenTypes.SEMICOLON)) {
            // TODO: mark error
        }

        if (scratch.size == 1 && scratch[0].tokenType === ZigElementTypes.VAR_DECL_PROTO) {
            statement.done(ZigElementTypes.VAR_DECL)
            return statement
        }

        statement.done(ZigElementTypes.ASSIGN)
        return statement
    }

    private fun tryIf(builder: PsiBuilder, bodyParseFn: (PsiBuilder) -> PsiBuilder.Marker?): PsiBuilder.Marker? {
        val marker = builder.mark()
        if (!expect(builder, ZigTokenTypes.KEYWORD_IF)) {
            marker.drop()
            return null
        }
        if (tryConditionExpr(builder) == null) {
            parseParenEnclosed(builder, null, ZigElementTypes.CONDITION_EXPR)
        }
        // TODO: parsePtrPayload
        val thenExpr = bodyParseFn(builder)
        if (thenExpr == null) {
            marker.rollbackTo()
            return null
        }
        thenExpr.precede().done(ZigElementTypes.THEN_EXPR)
        if (expect(builder, ZigTokenTypes.KEYWORD_ELSE)) {
            // TODO: parsePayload
            val elseExpr = bodyParseFn(builder)
            if (elseExpr == null) {
                marker.rollbackTo()
                return null
            }
            elseExpr.precede().done(ZigElementTypes.ELSE_EXPR)
        }
        marker.done(ZigElementTypes.IF)
        return marker
    }

    private fun tryConditionExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val condition = builder.mark()
        if (!expect(builder, ZigTokenTypes.L_PAREN)) {
            condition.drop()
            return null
        }
        if (tryExpr(builder) == null) {
            condition.rollbackTo()
            return null
        }
        if (!expect(builder, ZigTokenTypes.R_PAREN)) {
            condition.rollbackTo()
            return null
        }
        condition.done(ZigElementTypes.CONDITION_EXPR)
        return condition
    }

    private fun parseAssignExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = tryExpr(builder) ?: return null
        return finishAssignExpr(builder, expr)
    }

    private fun finishAssignExpr(builder: PsiBuilder, lhs: PsiBuilder.Marker): PsiBuilder.Marker {
        // TODO
        return lhs
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
            if (braces == 0 && brackets == 0 && parens == 0 && terminator(builder.tokenType)) {
                break
            }
            when (builder.tokenType) {
                ZigTokenTypes.L_BRACE -> braces++
                ZigTokenTypes.R_BRACE -> braces--
                ZigTokenTypes.L_BRACKET -> brackets++
                ZigTokenTypes.R_BRACKET -> brackets--
                ZigTokenTypes.L_PAREN -> parens++
                ZigTokenTypes.R_PAREN -> parens--
            }
            builder.advanceLexer()
        }
    }

    private fun parseParenEnclosed(
        builder: PsiBuilder,
        prefix: IElementType?,
        type: IElementType,
        parseMember: () -> PsiBuilder.Marker? = { null },
    ): PsiBuilder.Marker? {
        return parseEnclosed(builder, prefix, ZigTokenTypes.L_PAREN, type, parseMember)
    }

    private fun parseBraceEnclosed(
        builder: PsiBuilder,
        prefix: IElementType?,
        type: IElementType,
        parseMember: () -> PsiBuilder.Marker?,
    ): PsiBuilder.Marker? {
        return parseEnclosed(builder, prefix, ZigTokenTypes.L_BRACE, type, parseMember)
    }

    private fun parseEnclosed(
        builder: PsiBuilder,
        prefix: IElementType?,
        left: IElementType,
        type: IElementType,
        parseMember: () -> PsiBuilder.Marker?,
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
        enclosureStack.addLast(type)
        var braces = 0
        var brackets = 0
        var parens = 0
        when (left) {
            ZigTokenTypes.L_BRACE -> braces++
            ZigTokenTypes.L_BRACKET -> brackets++
            ZigTokenTypes.L_PAREN -> parens++
            else -> throw IllegalArgumentException("Unexpected token type $left")
        }
        while (!builder.eof()) {
            val depth = braces + brackets + parens
            if (depth == 0) {
                break
            }
            if (depth == 1 && parseMember() != null) {
                continue
            }
            when (builder.tokenType) {
                ZigTokenTypes.L_BRACE -> braces++
                ZigTokenTypes.R_BRACE -> braces--
                ZigTokenTypes.L_BRACKET -> brackets++
                ZigTokenTypes.R_BRACKET -> brackets--
                ZigTokenTypes.L_PAREN -> parens++
                ZigTokenTypes.R_PAREN -> parens--
            }
            builder.advanceLexer()
        }
        enclosureStack.removeLast()
        marker.done(type)
        return marker
    }

    private fun expect(builder: PsiBuilder, expectedType: IElementType?): Boolean {
        return expect(builder) { it === expectedType }
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
        return expect(builder) { it in expectedTypes }
    }

    private inline fun expect(builder: PsiBuilder, expectedType: (IElementType?) -> Boolean): Boolean {
        if (expectedType(builder.tokenType)) {
            builder.advanceLexer()
            return true
        }
        return false
    }
}
