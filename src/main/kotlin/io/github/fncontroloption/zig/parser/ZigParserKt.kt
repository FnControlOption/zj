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
    private val containerStack = ArrayDeque<IElementType>()

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        enclosureStack.addLast(root)
        containerStack.addLast(ZigTokenTypes.KEYWORD_STRUCT)
        val rootMarker = builder.mark()
        while (!builder.eof()) {
            if (parseContainerMember(builder) == null) {
                builder.advanceLexer()
            }
        }
        rootMarker.done(root)
        enclosureStack.removeLast()
        containerStack.removeLast()
        return builder.treeBuilt
    }

    private fun parseContainerMember(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseTestDecl(builder)
            ?: parseComptimeBlock(builder)
            ?: parseVarDecl(builder)
            ?: parseFnDecl(builder)
            ?: parseContainerField(builder)
    }

    private fun parseContainerDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val container = builder.mark()
        expect(builder, ZigTokenSets.EXTERN_PACKED)
        val containerToken = builder.tokenType
        if (!expect(builder, ZigTokenSets.CONTAINERS)) {
            container.rollbackTo()
            return null
        }
        containerStack.addLast(containerToken!!)
        parseContainerDeclTag(builder)
        parseBraceEnclosed(builder, null, ZigElementTypes.CONTAINER_DECL_BODY) {
            parseContainerMember(builder)
        }
        containerStack.removeLast()
        container.done(ZigElementTypes.CONTAINER_DECL)
        return container
    }

    private fun parseContainerDeclTag(builder: PsiBuilder): PsiBuilder.Marker? {
        var expr: PsiBuilder.Marker? = null
        return parseParenEnclosed(builder, null, ZigElementTypes.CONTAINER_TAG) {
            if (expr == null) {
                when (builder.tokenType) {
                    ZigTokenTypes.KEYWORD_ENUM -> {
                        when (builder.lookAhead(1)) {
                            ZigTokenTypes.L_PAREN -> {
                                val enum = builder.mark()
                                builder.advanceLexer()
                                parseContainerDeclTag(builder)
                                enum.done(ZigElementTypes.CONTAINER_TAG_ENUM)
                                expr = enum
                            }
                            ZigTokenTypes.R_PAREN -> {
                                val enum = builder.mark()
                                builder.advanceLexer()
                                enum.done(ZigElementTypes.CONTAINER_TAG_ENUM)
                                expr = enum
                            }
                            else -> expr = tryExpr(builder)
                        }
                    }
                    else -> expr = tryExpr(builder)
                }
                expr
            } else {
                null
            }
        }
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
        statement.rollbackTo()
        return null
    }

    private fun parseBuiltinCall(builder: PsiBuilder): PsiBuilder.Marker? {
        val call = builder.mark()
        if (!expect(builder, ZigTokenTypes.BUILTIN)) {
            call.rollbackTo()
            return null
        }
        parseCallArgs(builder)
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
            proto.rollbackTo()
            return null
        }
        if (!expect(builder, ZigTokenTypes.IDENTIFIER)) {
            proto.rollbackTo()
            return null
        }
        if (expect(builder, ZigTokenTypes.COLON)) {
            val type = builder.mark()
            if (tryTypeExpr(builder) == null) {
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
        parseFnParams(builder, requireNames)
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
            decl.rollbackTo()
            return null
        }
        if (!expect(builder, ZigTokenTypes.SEMICOLON)) {
            parseBlock(builder)
        }
        decl.done(ZigElementTypes.FN_DECL)
        return decl
    }

    private fun parseFnParams(builder: PsiBuilder, requireNames: Boolean): PsiBuilder.Marker? {
        return parseParenEnclosed(builder, null, ZigElementTypes.FN_PARAMS) {
            val paramDecl = tryFnParamDecl(builder, requireNames) ?: return@parseParenEnclosed null
            if (!expect(builder, ZigTokenTypes.COMMA) && builder.tokenType !== ZigTokenTypes.R_PAREN) {
                // TODO: mark error
            }
            paramDecl
        }
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
            field.rollbackTo()
            return null
        }
        expect(builder, ZigTokenTypes.COMMA)
        field.done(ZigElementTypes.ERROR_SET_MEMBER)
        return field
    }

    private fun parseTestDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        if (!expect(builder, ZigTokenTypes.KEYWORD_TEST)) {
            decl.rollbackTo()
            return null
        }
        expect(builder, ZigTokenSets.TEST_NAME)
        parseBlock(builder)
        decl.done(ZigElementTypes.TEST_DECL)
        return decl
    }

    private fun parseComptimeBlock(builder: PsiBuilder): PsiBuilder.Marker? {
        val comptime = builder.mark()
        if (!expect(builder, ZigTokenTypes.KEYWORD_COMPTIME)) {
            comptime.rollbackTo()
            return null
        }
        parseBlock(builder)
        comptime.done(ZigElementTypes.COMPTIME)
        return comptime
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
        val hasName = expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
        if (hasName || containerStack.last() === ZigTokenTypes.KEYWORD_STRUCT) {
            val type = builder.mark()
            if (tryTypeExpr(builder) == null) {
                field.rollbackTo()
                return null
            }
            type.done(ZigElementTypes.TYPE_EXPR)
        } else if (!expect(builder, ZigTokenTypes.IDENTIFIER)) {
            field.rollbackTo()
            return null
        }
        parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_ALIGN, ZigElementTypes.ALIGN)
        if (expect(builder, ZigTokenTypes.EQUAL)) {
            parseInitExpr(builder, ZigTokenSets.COMMA_BRACE)
        }
        field.done(ZigElementTypes.CONTAINER_FIELD)
        if (!expect(builder, ZigTokenTypes.COMMA)) {
            val terminator = when (enclosure) {
                ZigParserDefinition.FILE -> null
                ZigElementTypes.CONTAINER_DECL_BODY -> ZigTokenTypes.R_BRACE
                else -> throw IllegalStateException()
            }
            if (builder.tokenType !== terminator) {
                // TODO: mark error
            }
        }
        return field
    }

    private enum class TypeLengthState { START, SENTINEL, DONE }

    private fun tryTypeExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        when (builder.tokenType) {
            ZigTokenTypes.QUESTION_MARK -> {
                val expr = builder.mark()
                builder.advanceLexer()
                if (tryTypeExpr(builder) != null) {
                    expr.done(ZigElementTypes.OPTIONAL_TYPE)
                    return expr
                } else {
                    expr.rollbackTo()
                    return null
                }
            }
            ZigTokenTypes.KEYWORD_ANYFRAME if builder.lookAhead(1) === ZigTokenTypes.ARROW -> {
                val expr = builder.mark()
                builder.advanceLexer()
                builder.advanceLexer()
                if (tryTypeExpr(builder) != null) {
                    expr.done(ZigElementTypes.ANYFRAME_TYPE)
                    return expr
                } else {
                    expr.rollbackTo()
                    return null
                }
            }
            ZigTokenTypes.ASTERISK -> {
                val expr = builder.mark()
                builder.advanceLexer()
                parsePointerModifiers(builder)
                if (tryTypeExpr(builder) != null) {
                    expr.done(ZigElementTypes.POINTER_TYPE)
                    return expr
                } else {
                    expr.rollbackTo()
                    return null
                }
            }
            ZigTokenTypes.L_BRACKET -> {
                val expr = builder.mark()
                var isArray = true
                var lengthState = TypeLengthState.START
                val prefix = parseBracketEnclosed(builder, null, ZigElementTypes.ARRAY_TYPE) {
                    when (lengthState) {
                        TypeLengthState.START -> {
                            val length = builder.mark()
                            when (builder.tokenType) {
                                ZigTokenTypes.ASTERISK -> {
                                    isArray = false
                                    builder.advanceLexer()
                                    when (builder.tokenType) {
                                        ZigTokenTypes.IDENTIFIER if builder.tokenText == "c" -> {
                                            // C pointer: [*c]Foo
                                            builder.advanceLexer()
                                            lengthState = TypeLengthState.DONE
                                        }
                                        else -> {
                                            // Many-item pointer: [*]Foo
                                            lengthState = TypeLengthState.SENTINEL
                                        }
                                    }
                                }
                                else -> {
                                    // Array: [len]Foo
                                    // Slice: []Foo
                                    isArray = tryExpr(builder) != null
                                    lengthState = TypeLengthState.SENTINEL
                                }
                            }
                            length.done(ZigElementTypes.TYPE_LENGTH)
                            length
                        }
                        TypeLengthState.SENTINEL -> {
                            when (builder.tokenType) {
                                ZigTokenTypes.COLON -> {
                                    val sentinel = builder.mark()
                                    builder.advanceLexer()
                                    tryExpr(builder)
                                    lengthState = TypeLengthState.DONE
                                    sentinel.done(ZigElementTypes.TYPE_SENTINEL)
                                    sentinel
                                }
                                else -> null
                            }
                        }
                        TypeLengthState.DONE -> null
                    }
                }
                prefix!!.drop()
                val modifiers = parsePointerModifiers(builder)
                if (tryTypeExpr(builder) == null) {
                    expr.rollbackTo()
                    return null
                }
                if (isArray) {
                    if (modifiers != null) {
                        // TODO: mark error
                    }
                    expr.done(ZigElementTypes.ARRAY_TYPE)
                } else {
                    expr.done(ZigElementTypes.POINTER_TYPE)
                }
                return expr
            }
            else -> return tryErrorUnionExpr(builder)
        }
    }

    private fun parsePointerModifiers(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !in ZigTokenSets.POINTER_MODIFIERS) {
            return null
        }
        val marker = builder.mark()
        while (!builder.eof()) {
            when (builder.tokenType) {
                ZigTokenTypes.KEYWORD_ALIGN -> {
                    parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_ALIGN, ZigElementTypes.ALIGN)
                }
                ZigTokenTypes.KEYWORD_CONST -> {
                    builder.advanceLexer()
                }
                ZigTokenTypes.KEYWORD_VOLATILE -> {
                    builder.advanceLexer()
                }
                ZigTokenTypes.KEYWORD_ALLOWZERO -> {
                    builder.advanceLexer()
                }
                ZigTokenTypes.KEYWORD_ADDRSPACE -> {
                    parseParenEnclosed(builder, ZigTokenTypes.KEYWORD_ADDRSPACE, ZigElementTypes.ADDRSPACE)
                }
                else -> break
            }
        }
        marker.done(ZigElementTypes.POINTER_MODIFIERS)
        return marker
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
        if (parseCallArgs(builder) == null) {
            return null
        }
        val call = lhs.precede()
        call.done(ZigElementTypes.CALL)
        return call
    }

    private fun parseCallArgs(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseParenEnclosed(builder, null, ZigElementTypes.CALL_ARGS) {
            val arg = tryExpr(builder) ?: return@parseParenEnclosed null
            if (!expect(builder, ZigTokenTypes.COMMA) && builder.tokenType !== ZigTokenTypes.R_PAREN) {
                // TODO: mark error
            }
            arg
        }
    }

    private fun parseSuffixOp(builder: PsiBuilder): IElementType? {
        when (builder.tokenType) {
            ZigTokenTypes.L_BRACKET -> {
                var startExpr: PsiBuilder.Marker? = null
                var endExpr: PsiBuilder.Marker? = null
                var sentinel: PsiBuilder.Marker? = null
                parseBracketEnclosed(builder, null, ZigElementTypes.INDEXES) {
                    if (startExpr == null) {
                        startExpr = tryExpr(builder)
                        startExpr
                    } else if (endExpr == null) {
                        if (builder.tokenType === ZigTokenTypes.ELLIPSIS2) {
                            endExpr = builder.mark()
                            builder.advanceLexer()
                            tryExpr(builder)
                            endExpr.done(ZigElementTypes.SLICE_END)
                            endExpr
                        } else {
                            null
                        }
                    } else if (sentinel == null) {
                        if (builder.tokenType === ZigTokenTypes.COLON) {
                            sentinel = builder.mark()
                            builder.advanceLexer()
                            if (tryExpr(builder) == null) {
                                // TODO: mark error
                            }
                            sentinel.done(ZigElementTypes.SLICE_SENTINEL)
                            sentinel
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
                if (endExpr != null) {
                    return ZigElementTypes.SLICE
                }
                return ZigElementTypes.ARRAY_ACCESS
            }
            ZigTokenTypes.PERIOD_ASTERISK -> {
                builder.advanceLexer()
                return ZigElementTypes.DEREF
            }
            ZigTokenTypes.PERIOD -> {
                when (builder.lookAhead(1)) {
                    ZigTokenTypes.IDENTIFIER -> {
                        builder.advanceLexer()
                        builder.advanceLexer()
                        return ZigElementTypes.FIELD_ACCESS
                    }
                    ZigTokenTypes.QUESTION_MARK -> {
                        builder.advanceLexer()
                        builder.advanceLexer()
                        return ZigElementTypes.UNWRAP_OPTIONAL
                    }
                    ZigTokenTypes.L_BRACE -> {
                        return null
                    }
                    else -> {
                        // TODO: mark error
                        builder.advanceLexer()
                        return ZigElementTypes.FIELD_ACCESS
                    }
                }
            }
        }
        return null
    }

    private fun tryPrimaryTypeExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        when (builder.tokenType) {
            ZigTokenTypes.CHAR_LITERAL -> {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementTypes.CHAR_LITERAL)
                return marker
            }
            ZigTokenTypes.NUMBER_LITERAL -> {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementTypes.NUMBER_LITERAL)
                return marker
            }
            ZigTokenTypes.KEYWORD_UNREACHABLE -> {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementTypes.UNREACHABLE_LITERAL)
                return marker
            }
            ZigTokenTypes.KEYWORD_ANYFRAME -> {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementTypes.ANYFRAME_LITERAL)
                return marker
            }
            ZigTokenTypes.STRING_LITERAL -> {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementTypes.STRING_LITERAL)
                return marker
            }
            ZigTokenTypes.BUILTIN -> return parseBuiltinCall(builder)
            ZigTokenTypes.KEYWORD_FN -> return tryFnProto(builder, false, null)
            ZigTokenTypes.KEYWORD_IF -> return tryIf(builder, this::tryTypeExpr)
            ZigTokenTypes.KEYWORD_SWITCH -> return null // TODO
            in ZigTokenSets.EXTERN_PACKED, in ZigTokenSets.CONTAINERS -> return parseContainerDecl(builder)
            ZigTokenTypes.KEYWORD_COMPTIME -> {
                val marker = builder.mark()
                builder.advanceLexer()
                if (tryTypeExpr(builder) == null) {
                    marker.rollbackTo()
                    return null
                }
                marker.done(ZigElementTypes.COMPTIME)
                return marker
            }
            ZigTokenTypes.MULTILINE_STRING_LITERAL_LINE -> return null // TODO
            ZigTokenTypes.IDENTIFIER -> {
                if (builder.lookAhead(1) === ZigTokenTypes.COLON) {
                    when (builder.lookAhead(2)) {
                        ZigTokenTypes.KEYWORD_INLINE -> return null // TODO
                        ZigTokenTypes.KEYWORD_FOR -> return null // TODO
                        ZigTokenTypes.KEYWORD_WHILE -> return null // TODO
                        ZigTokenTypes.KEYWORD_SWITCH -> return null // TODO
                        ZigTokenTypes.L_BRACE -> return parseBlockExpr(builder)
                    }
                }
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementTypes.IDENTIFIER)
                return marker
            }
            ZigTokenTypes.KEYWORD_INLINE -> return null // TODO
            ZigTokenTypes.KEYWORD_FOR -> return null // TODO
            ZigTokenTypes.KEYWORD_WHILE -> return null // TODO
            ZigTokenTypes.PERIOD -> {
                when (builder.lookAhead(1)) {
                    ZigTokenTypes.IDENTIFIER -> {
                        val marker = builder.mark()
                        builder.advanceLexer()
                        builder.advanceLexer()
                        marker.done(ZigElementTypes.ENUM_LITERAL)
                        return marker
                    }
                    ZigTokenTypes.L_BRACE -> {
                        val marker = builder.mark()
                        builder.advanceLexer()
                        parseBraceEnclosed(builder, null, ZigElementTypes.INIT_LIST) {
                            parseInitListEntry(builder)
                        }
                        marker.done(ZigElementTypes.ARRAY_INIT)
                        return marker
                    }
                    else -> return null
                }
            }
            ZigTokenTypes.KEYWORD_ERROR -> {
                val decl = parseErrorSetDecl(builder)
                if (decl != null) {
                    return decl
                }
                val marker = builder.mark()
                builder.advanceLexer()
                expect(builder, ZigTokenTypes.KEYWORD_ERROR)
                if (!expect(builder, ZigTokenTypes.PERIOD)) {
                    marker.rollbackTo()
                    return null
                }
                expect(builder, ZigTokenTypes.IDENTIFIER)
                marker.done(ZigElementTypes.ERROR_VALUE)
                return marker
            }
            ZigTokenTypes.L_PAREN -> {
                var expr: PsiBuilder.Marker? = null
                return parseParenEnclosed(builder, null, ZigElementTypes.GROUPED_EXPRESSION) {
                    if (expr == null) {
                        expr = tryExpr(builder)
                        expr
                    } else {
                        null
                    }
                }
            }
            else -> return null
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
            if (operToken === ZigTokenTypes.KEYWORD_CATCH) {
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
        when (builder.tokenType) {
            // TODO
            ZigTokenTypes.KEYWORD_BREAK -> {
                val expr = builder.mark()
                builder.advanceLexer()
                expect(builder, ZigTokenTypes.COLON, ZigTokenTypes.IDENTIFIER)
                tryExpr(builder)
                expr.done(ZigElementTypes.BREAK)
                return expr
            }
            ZigTokenTypes.KEYWORD_CONTINUE -> {
                val expr = builder.mark()
                builder.advanceLexer()
                expect(builder, ZigTokenTypes.COLON, ZigTokenTypes.IDENTIFIER)
                tryExpr(builder)
                expr.done(ZigElementTypes.CONTINUE)
                return expr
            }
            ZigTokenTypes.KEYWORD_COMPTIME -> {
                val expr = builder.mark()
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementTypes.COMPTIME)
                return expr
            }
            ZigTokenTypes.KEYWORD_NOSUSPEND -> {
                val expr = builder.mark()
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementTypes.NOSUSPEND)
                return expr
            }
            ZigTokenTypes.KEYWORD_RESUME -> {
                val expr = builder.mark()
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementTypes.RESUME)
                return expr
            }
            ZigTokenTypes.KEYWORD_RETURN -> {
                val expr = builder.mark()
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementTypes.RETURN)
                return expr
            }
            // TODO
            ZigTokenTypes.L_BRACE -> return parseBlock(builder)
            else -> return tryCurlySuffixExpr(builder)
        }
    }

    private fun tryCurlySuffixExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val lhs = tryTypeExpr(builder) ?: return null
        val initList = parseBraceEnclosed(builder, null, ZigElementTypes.INIT_LIST) {
            parseInitListEntry(builder)
        }
        if (initList == null) {
            return lhs
        }
        val typeExpr = lhs.precede()
        typeExpr.doneBefore(ZigElementTypes.TYPE_EXPR, initList)
        val init = typeExpr.precede()
        init.done(ZigElementTypes.ARRAY_INIT)
        return init
    }

    private fun parseInitListEntry(builder: PsiBuilder): PsiBuilder.Marker? {
        val entry = builder.mark()
        if (expect(builder, ZigTokenTypes.PERIOD, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.EQUAL)) {
            tryExpr(builder)
        } else if (tryExpr(builder) == null) {
            entry.rollbackTo()
            return null
        }
        if (!expect(builder, ZigTokenTypes.COMMA) && builder.tokenType !== ZigTokenTypes.R_BRACE) {
            // TODO: mark error
        }
        entry.done(ZigElementTypes.INIT_LIST_ENTRY)
        return entry
    }

    private fun tryStatement(builder: PsiBuilder, isBlockLevel: Boolean): PsiBuilder.Marker? {
        if (expect(builder, ZigTokenTypes.KEYWORD_COMPTIME)) {
            // TODO
            return null
        }
        when (builder.tokenType) {
            // TODO
            ZigTokenTypes.KEYWORD_DEFER -> {
                if (isBlockLevel) {
                    val statement = builder.mark()
                    builder.advanceLexer()
                    parseBlockExprStatement(builder)
                    statement.done(ZigElementTypes.DEFER)
                    return statement
                }
            }
            ZigTokenTypes.KEYWORD_ERRDEFER -> {
                if (isBlockLevel) {
                    val statement = builder.mark()
                    builder.advanceLexer()
                    parseBlockExprStatement(builder)
                    statement.done(ZigElementTypes.ERRDEFER)
                    return statement
                }
            }
            // TODO
        }
        val labeledStatement = parseLabeledStatement(builder)
        if (labeledStatement != null) {
            return labeledStatement
        }
        if (isBlockLevel) {
            return tryVarDeclExprStatement(builder)
        } else {
            // TODO
            return null
        }
    }

    private fun parseLabeledStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        val marker = builder.mark()
        val hasLabel = expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
        val statement = parseBlock(builder) ?: parseLoopStatement(builder) ?: parseSwitchExpr(builder)
        if (statement != null) {
            if (hasLabel) {
                marker.done(ZigElementTypes.LABELED_STATEMENT)
                return marker
            } else {
                marker.drop()
                return statement
            }
        }
        if (!hasLabel) {
            marker.rollbackTo()
            return null
        }
        // TODO: mark error
        marker.rollbackTo()
        return null
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
            marker.rollbackTo()
            return null
        }
        parseConditionExpr(builder)
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

    private fun parseConditionExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        var expr: PsiBuilder.Marker? = null
        return parseParenEnclosed(builder, null, ZigElementTypes.CONDITION_EXPR) {
            if (expr == null) {
                expr = tryExpr(builder)
                expr
            } else {
                null
            }
        }
    }

    private fun parseAssignExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = tryExpr(builder) ?: return null
        return finishAssignExpr(builder, expr)
    }

    private fun finishAssignExpr(builder: PsiBuilder, lhs: PsiBuilder.Marker): PsiBuilder.Marker {
        // TODO
        return lhs
    }

    private fun parseLoopStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        // TODO
        return null
    }

    private fun parseSwitchExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        // TODO
        return null
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

    private fun parseBracketEnclosed(
        builder: PsiBuilder,
        prefix: IElementType?,
        type: IElementType,
        parseMember: () -> PsiBuilder.Marker? = { null },
    ): PsiBuilder.Marker? {
        return parseEnclosed(builder, prefix, ZigTokenTypes.L_BRACKET, type, parseMember)
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
            marker.rollbackTo()
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
            // TODO: mark error
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
