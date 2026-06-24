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
    private enum class EnclosureType { CONTAINER, OTHER }
    private class Enclosure(val type: EnclosureType, val left: IElementType?, val right: IElementType?)

    private val enclosureStack = ArrayDeque<Enclosure>()
    private val containerStack = ArrayDeque<IElementType>()

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        enclosureStack.addLast(Enclosure(EnclosureType.CONTAINER, null, null))
        containerStack.addLast(root)
        val rootMarker = builder.mark()
        while (!builder.eof()) {
            if (parseContainerMember(builder) == null) {
                val invalid = builder.mark()
                builder.advanceLexer()
                invalid.error("Unexpected token")
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
        val body = builder.mark()
        parseBraceEnclosed(builder, EnclosureType.CONTAINER) {
            parseContainerMember(builder)
        }
        body.done(ZigElementTypes.CONTAINER_DECL_BODY)
        containerStack.removeLast()
        container.done(ZigElementTypes.CONTAINER_DECL)
        return container
    }

    private fun parseContainerDeclTag(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        var expr: PsiBuilder.Marker? = null
        parseParenEnclosed(builder) {
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
        if (expr == null) {
            // TODO: mark error
        }
        marker.done(ZigElementTypes.CONTAINER_TAG)
        return marker
    }

    private fun parseBlock(builder: PsiBuilder, allowLabel: Boolean = false): PsiBuilder.Marker? {
        val prevToken = builder.rawLookup(-1)
        if (prevToken === ZigTokenTypes.PERIOD) {
            return null
        }

        val marker = builder.mark()
        if (allowLabel) {
            expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
        }
        if (builder.tokenType !== ZigTokenTypes.L_BRACE) {
            marker.rollbackTo()
            return null
        }
        val body = builder.mark()
        parseBraceEnclosed(builder) {
            tryStatement(builder, true)
        }
        body.done(ZigElementTypes.BLOCK_BODY)
        marker.done(ZigElementTypes.BLOCK)
        return marker
    }

    private fun parseBlockExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseBlock(builder, true)
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
        if (parseCallArgs(builder) == null) {
            // TODO: mark error
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
        parseAlign(builder)
        parseAddrspace(builder)
        parseLinksection(builder)
        proto.done(ZigElementTypes.VAR_DECL_PROTO)
        return proto
    }

    private fun parseAlign(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseParenEnclosedExpr(builder, ZigTokenTypes.KEYWORD_ALIGN, ZigElementTypes.ALIGN)
    }

    private fun parseAddrspace(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseParenEnclosedExpr(builder, ZigTokenTypes.KEYWORD_ADDRSPACE, ZigElementTypes.ADDRSPACE)
    }

    private fun parseLinksection(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseParenEnclosedExpr(builder, ZigTokenTypes.KEYWORD_LINKSECTION, ZigElementTypes.LINKSECTION)
    }

    private fun parseCallconv(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseParenEnclosedExpr(builder, ZigTokenTypes.KEYWORD_CALLCONV, ZigElementTypes.CALLCONV)
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

    private fun parseFnProto(builder: PsiBuilder, requireName: Boolean, terminators: TokenSet?): PsiBuilder.Marker? {
        val proto = builder.mark()
        if (!expect(builder, ZigTokenTypes.KEYWORD_FN)) {
            proto.rollbackTo()
            return null
        }
        val hasName = expect(builder, ZigTokenTypes.IDENTIFIER)
        if (!hasName && requireName) {
            proto.rollbackTo()
            return null
        }
        if (parseFnParams(builder) == null) {
            // TODO: mark error
        }
        parseAlign(builder)
        parseAddrspace(builder)
        parseLinksection(builder)
        parseCallconv(builder)
        expect(builder, ZigTokenTypes.BANG)
        val returnType = builder.mark()
        if (parseTypeExpr(builder) == null) {
            // TODO: mark error
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
        if (parseFnProto(builder, true, ZigTokenSets.SEMICOLON_BRACE) == null) {
            decl.rollbackTo()
            return null
        }
        if (!expect(builder, ZigTokenTypes.SEMICOLON)) {
            parseBlock(builder)
        }
        decl.done(ZigElementTypes.FN_DECL)
        return decl
    }

    private fun parseFnParams(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        parseParenEnclosed(builder) {
            tryFnParamDecl(builder)
        }
        marker.done(ZigElementTypes.FN_PARAMS)
        return marker
    }

    private fun tryFnParamDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        if (expect(builder, ZigTokenTypes.ELLIPSIS3)) {
            decl.done(ZigElementTypes.FN_VARARGS)
            expect(builder, ZigTokenTypes.COMMA)
            if (builder.tokenType !== ZigTokenTypes.R_PAREN) {
                // TODO: mark error
            }
            return decl
        }
        expect(builder, ZigTokenSets.NOALIAS_COMPTIME)
        val hasName = expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
        if (!expect(builder, ZigTokenTypes.KEYWORD_ANYTYPE)) {
            val type = builder.mark()
            if (tryTypeExpr(builder) == null && !hasName) {
                decl.rollbackTo()
                return null
            }
            type.done(ZigElementTypes.TYPE_EXPR)
        }
        decl.done(ZigElementTypes.FN_PARAM_DECL)
        if (!expect(builder, ZigTokenTypes.COMMA) && builder.tokenType !== ZigTokenTypes.R_PAREN) {
            // TODO: mark error
        }
        return decl
    }

    private fun parseErrorSetDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.KEYWORD_ERROR) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        if (builder.tokenType !== ZigTokenTypes.L_BRACE) {
            marker.rollbackTo()
            return null
        }
        parseBraceEnclosed(builder) {
            parseErrorSetMember(builder)
        }
        marker.done(ZigElementTypes.ERROR_SET_DECL)
        return marker
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
        when (enclosure.type) {
            EnclosureType.CONTAINER -> {}
            else -> return null
        }

        val prevToken = builder.rawLookup(-1)
        if (prevToken in ZigTokenSets.MUT_TOKENS) {
            return null
        }

        val containerType = containerStack.last()
        val field = builder.mark()
        expect(builder, ZigTokenTypes.KEYWORD_COMPTIME)
        val hasName = expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
        if (hasName || containerType === ZigTokenTypes.KEYWORD_STRUCT) {
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
        parseAlign(builder)
        if (expect(builder, ZigTokenTypes.EQUAL)) {
            parseInitExpr(builder, ZigTokenSets.COMMA_BRACE)
        }
        field.done(ZigElementTypes.CONTAINER_FIELD)
        if (!expect(builder, ZigTokenTypes.COMMA)) {
            val terminator = when (containerType) {
                ZigParserDefinition.FILE -> null
                else -> ZigTokenTypes.R_BRACE
            }
            if (builder.tokenType !== terminator) {
                // TODO: mark error
            }
        }
        return field
    }

    private enum class TypeLengthState { START, SENTINEL, DONE }

    private fun tryTypeExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = parseTypeExpr(builder)
        if (expr == null) {
            // TODO: mark error
        }
        return expr
    }

    private fun parseTypeExpr(builder: PsiBuilder): PsiBuilder.Marker? {
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
                var isArray = false
                var lengthState = TypeLengthState.START
                parseBracketEnclosed(builder) {
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
                                    isArray = parseExpr(builder) != null
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
            else -> return parseErrorUnionExpr(builder)
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
                    parseAlign(builder)
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
                    parseAddrspace(builder)
                }
                else -> break
            }
        }
        marker.done(ZigElementTypes.POINTER_MODIFIERS)
        return marker
    }

    private fun parseErrorUnionExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        var expr = parseSuffixExpr(builder) ?: return null
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

    private fun parseSuffixExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        var res = parsePrimaryTypeExpr(builder) ?: return null
        while (!builder.eof()) {
            val suffixOp = parseSuffixOp(builder)
            if (suffixOp != null) {
                res = res.precede()
                res.done(suffixOp)
                continue
            }
            res = parseCall(builder, res) ?: return res
        }
        return res
    }

    private fun parseCall(builder: PsiBuilder, lhs: PsiBuilder.Marker): PsiBuilder.Marker? {
        if (parseCallArgs(builder) == null) {
            return null
        }
        val call = lhs.precede()
        call.done(ZigElementTypes.CALL)
        return call
    }

    private fun parseCallArgs(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        parseParenEnclosed(builder) {
            tryCallArg(builder)
        }
        marker.done(ZigElementTypes.CALL_ARGS)
        return marker
    }

    private fun tryCallArg(builder: PsiBuilder): PsiBuilder.Marker? {
        val arg = tryExpr(builder) ?: return null
        if (!expect(builder, ZigTokenTypes.COMMA) && builder.tokenType !== ZigTokenTypes.R_PAREN) {
            // TODO: mark error
        }
        return arg
    }

    private fun parseSuffixOp(builder: PsiBuilder): IElementType? {
        when (builder.tokenType) {
            ZigTokenTypes.L_BRACKET -> {
                var startExpr: PsiBuilder.Marker? = null
                var endExpr: PsiBuilder.Marker? = null
                var sentinel: PsiBuilder.Marker? = null
                val indexes = builder.mark()
                parseBracketEnclosed(builder) {
                    if (startExpr == null) {
                        startExpr = tryExpr(builder)
                        startExpr
                    } else if (endExpr == null) {
                        if (builder.tokenType === ZigTokenTypes.ELLIPSIS2) {
                            endExpr = builder.mark()
                            builder.advanceLexer()
                            parseExpr(builder)
                            endExpr.done(ZigElementTypes.SLICE_END)
                            endExpr
                        } else {
                            null
                        }
                    } else if (sentinel == null) {
                        if (builder.tokenType === ZigTokenTypes.COLON) {
                            sentinel = builder.mark()
                            builder.advanceLexer()
                            tryExpr(builder)
                            sentinel.done(ZigElementTypes.SLICE_SENTINEL)
                            sentinel
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
                indexes.done(ZigElementTypes.INDEXES)
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

    private fun parsePrimaryTypeExpr(builder: PsiBuilder): PsiBuilder.Marker? {
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
            ZigTokenTypes.KEYWORD_FN -> return parseFnProto(builder, false, null)
            ZigTokenTypes.KEYWORD_IF -> return parseIf(builder, this::tryTypeExpr)
            ZigTokenTypes.KEYWORD_SWITCH -> return assertSwitchExpr(builder)
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
                        ZigTokenTypes.KEYWORD_SWITCH -> return assertSwitchExpr(builder)
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
                        parseInitList(builder)
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
            ZigTokenTypes.L_PAREN -> return parseGroupedExpression(builder)
            else -> return null
        }
    }

    private fun tryExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = parseExpr(builder)
        if (expr == null) {
            // TODO: mark error
        }
        return expr
    }

    private fun parseExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseExprPrecedence(builder, 0)
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

    private fun parseExprPrecedence(builder: PsiBuilder, minPrec: Int): PsiBuilder.Marker? {
        assert(minPrec >= 0)
        var expr = parsePrefixExpr(builder) ?: return null
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
            var catchResult: PsiBuilder.Marker? = null
            if (operToken === ZigTokenTypes.KEYWORD_CATCH) {
                parsePayload(builder)
                catchResult = builder.mark()
            }
            if (parseExprPrecedence(builder, info.prec + 1) == null) {
                // TODO: mark error
            }
            catchResult?.done(ZigElementTypes.CATCH_RESULT)
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
        val expr = parsePrefixExpr(builder)
        if (expr == null) {
            // TODO: mark error
        }
        return expr
    }

    private fun parsePrefixExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val nodeType = prefixTable[builder.tokenType] ?: return parsePrimaryExpr(builder)
        val expr = builder.mark()
        builder.advanceLexer()
        tryPrefixExpr(builder)
        expr.done(nodeType)
        return expr
    }

    private fun parsePrimaryExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        when (builder.tokenType) {
            // TODO
            ZigTokenTypes.KEYWORD_IF -> return parseIf(builder, this::tryExpr)
            ZigTokenTypes.KEYWORD_BREAK -> {
                val expr = builder.mark()
                builder.advanceLexer()
                expect(builder, ZigTokenTypes.COLON, ZigTokenTypes.IDENTIFIER)
                parseExpr(builder)
                expr.done(ZigElementTypes.BREAK)
                return expr
            }
            ZigTokenTypes.KEYWORD_CONTINUE -> {
                val expr = builder.mark()
                builder.advanceLexer()
                expect(builder, ZigTokenTypes.COLON, ZigTokenTypes.IDENTIFIER)
                parseExpr(builder)
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
                parseExpr(builder)
                expr.done(ZigElementTypes.RETURN)
                return expr
            }
            ZigTokenTypes.IDENTIFIER -> {
                if (builder.lookAhead(1) === ZigTokenTypes.COLON) {
                    when (builder.lookAhead(2)) {
                        ZigTokenTypes.KEYWORD_INLINE -> {
                            return when (builder.lookAhead(3)) {
                                ZigTokenTypes.KEYWORD_FOR -> parseFor(builder, this::tryExpr)
                                ZigTokenTypes.KEYWORD_WHILE -> parseWhile(builder, this::tryExpr)
                                else -> {
                                    // TODO: mark error
                                    null
                                }
                            }
                        }
                        ZigTokenTypes.KEYWORD_FOR -> return parseFor(builder, this::tryExpr)
                        ZigTokenTypes.KEYWORD_WHILE -> return parseWhile(builder, this::tryExpr)
                    }
                }
                return parseCurlySuffixExpr(builder)
            }
            ZigTokenTypes.KEYWORD_INLINE -> {
                return when (builder.lookAhead(1)) {
                    ZigTokenTypes.KEYWORD_FOR -> parseFor(builder, this::tryExpr)
                    ZigTokenTypes.KEYWORD_WHILE -> parseWhile(builder, this::tryExpr)
                    else -> {
                        // TODO: mark error
                        null
                    }
                }
            }
            ZigTokenTypes.KEYWORD_FOR -> return parseFor(builder, this::tryExpr)
            ZigTokenTypes.KEYWORD_WHILE -> return parseWhile(builder, this::tryExpr)
            ZigTokenTypes.L_BRACE -> return parseBlock(builder)
            else -> return parseCurlySuffixExpr(builder)
        }
    }

    private fun parseCurlySuffixExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val lhs = parseTypeExpr(builder) ?: return null
        val initList = parseInitList(builder) ?: return lhs
        val typeExpr = lhs.precede()
        typeExpr.doneBefore(ZigElementTypes.TYPE_EXPR, initList)
        val init = typeExpr.precede()
        init.done(ZigElementTypes.ARRAY_INIT)
        return init
    }

    private fun parseInitList(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.L_BRACE) {
            return null
        }
        val marker = builder.mark()
        parseBraceEnclosed(builder) {
            tryInitListEntry(builder)
        }
        marker.done(ZigElementTypes.INIT_LIST)
        return marker
    }

    private fun tryInitListEntry(builder: PsiBuilder): PsiBuilder.Marker? {
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
        when (builder.tokenType) {
            ZigTokenTypes.KEYWORD_COMPTIME -> {
                val marker = builder.mark()
                builder.advanceLexer()
                if (parseBlockExpr(builder) == null) {
                    if (isBlockLevel) {
                        tryVarDeclExprStatement(builder)
                    } else {
                        tryAssignExpr(builder)
                        if (!expect(builder, ZigTokenTypes.SEMICOLON)) {
                            // TODO: mark error
                        }
                    }
                }
                marker.done(ZigElementTypes.COMPTIME)
                return marker
            }
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
            ZigTokenTypes.KEYWORD_IF -> return assertIfStatement(builder)
            // TODO
        }
        val labeledStatement = parseLabeledStatement(builder)
        if (labeledStatement != null) {
            return labeledStatement
        }
        if (isBlockLevel) {
            return tryVarDeclExprStatement(builder)
        } else {
            val statement = builder.mark()
            if (tryAssignExpr(builder) == null) {
                statement.rollbackTo()
                return null
            }
            if (!expect(builder, ZigTokenTypes.SEMICOLON)) {
                // TODO mark error
            }
            statement.done(ZigElementTypes.EXPR_STATEMENT)
            return statement
        }
    }

    private fun assertIfStatement(builder: PsiBuilder): PsiBuilder.Marker {
        assert(builder.tokenType === ZigTokenTypes.KEYWORD_IF)
        val ifMarker = builder.mark()
        builder.advanceLexer()
        if (parseCondition(builder) == null) {
            // TODO: mark error
        }
        parsePtrPayload(builder)
        var elseRequired = false;
        val thenMarker = builder.mark()
        if (parseBlockExpr(builder) == null) {
            if (parseAssignExpr(builder) == null) {
                // TODO: mark error
            }
            if (expect(builder, ZigTokenTypes.SEMICOLON)) {
                thenMarker.done(ZigElementTypes.THEN)
                ifMarker.done(ZigElementTypes.IF)
                return ifMarker
            }
            elseRequired = true
        }
        thenMarker.done(ZigElementTypes.THEN)
        if (builder.tokenType === ZigTokenTypes.KEYWORD_ELSE) {
            val elseMarker = builder.mark()
            builder.advanceLexer()
            parsePayload(builder)
            tryStatement(builder, false)
            elseMarker.done(ZigElementTypes.ELSE)
        } else if (elseRequired) {
            // TODO: mark error
        }
        ifMarker.done(ZigElementTypes.IF)
        return ifMarker
    }

    private fun parseLabeledStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        val statement = parseBlockExpr(builder) ?: parseLoopStatement(builder) ?: parseSwitchExpr(builder)
        if (statement != null) {
            return statement
        }
        if (!expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)) {
            return null
        }
        // TODO: mark error
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

        while (!builder.eof()) {
            val varDeclProto = parseVarDeclProto(builder)
            if (varDeclProto != null) {
                scratch.add(varDeclProto)
            } else {
                val expr = parseExpr(builder)
                if (expr == null) {
                    // TODO: mark error
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

    private fun parseIf(builder: PsiBuilder, bodyParseFn: (PsiBuilder) -> PsiBuilder.Marker?): PsiBuilder.Marker? {
        val marker = builder.mark()
        if (!expect(builder, ZigTokenTypes.KEYWORD_IF)) {
            marker.rollbackTo()
            return null
        }
        if (parseCondition(builder) == null) {
            // TODO: mark error
        }
        parsePtrPayload(builder)
        val thenMarker = builder.mark()
        if (bodyParseFn(builder) == null) {
            // TODO: mark error
            return null
        }
        thenMarker.done(ZigElementTypes.THEN)
        if (builder.tokenType === ZigTokenTypes.KEYWORD_ELSE) {
            val elseMarker = builder.mark()
            builder.advanceLexer()
            parsePayload(builder)
            if (bodyParseFn(builder) == null) {
                // TODO: mark error
                return null
            }
            elseMarker.done(ZigElementTypes.ELSE)
        }
        marker.done(ZigElementTypes.IF)
        return marker
    }

    private fun parseCondition(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        var expr: PsiBuilder.Marker? = null
        parseParenEnclosed(builder) {
            if (expr == null) {
                expr = tryExpr(builder)
                expr
            } else {
                null
            }
        }
        if (expr == null) {
            // TODO: mark error
        }
        marker.done(ZigElementTypes.CONDITION)
        return marker
    }

    private val assignTable = mapOf(
        Pair(ZigTokenTypes.ASTERISK_EQUAL, ZigElementTypes.ASSIGN_MUL),
        Pair(ZigTokenTypes.SLASH_EQUAL, ZigElementTypes.ASSIGN_DIV),
        Pair(ZigTokenTypes.PERCENT_EQUAL, ZigElementTypes.ASSIGN_MOD),
        Pair(ZigTokenTypes.PLUS_EQUAL, ZigElementTypes.ASSIGN_ADD),
        Pair(ZigTokenTypes.MINUS_EQUAL, ZigElementTypes.ASSIGN_SUB),
        Pair(ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL, ZigElementTypes.ASSIGN_SHL),
        Pair(ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL, ZigElementTypes.ASSIGN_SHL_SAT),
        Pair(ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL, ZigElementTypes.ASSIGN_SHR),
        Pair(ZigTokenTypes.AMPERSAND_EQUAL, ZigElementTypes.ASSIGN_BIT_AND),
        Pair(ZigTokenTypes.CARET_EQUAL, ZigElementTypes.ASSIGN_BIT_XOR),
        Pair(ZigTokenTypes.PIPE_EQUAL, ZigElementTypes.ASSIGN_BIT_OR),
        Pair(ZigTokenTypes.ASTERISK_PERCENT_EQUAL, ZigElementTypes.ASSIGN_MUL_WRAP),
        Pair(ZigTokenTypes.PLUS_PERCENT_EQUAL, ZigElementTypes.ASSIGN_ADD_WRAP),
        Pair(ZigTokenTypes.MINUS_PERCENT_EQUAL, ZigElementTypes.ASSIGN_SUB_WRAP),
        Pair(ZigTokenTypes.ASTERISK_PIPE_EQUAL, ZigElementTypes.ASSIGN_MUL_SAT),
        Pair(ZigTokenTypes.PLUS_PIPE_EQUAL, ZigElementTypes.ASSIGN_ADD_SAT),
        Pair(ZigTokenTypes.MINUS_PIPE_EQUAL, ZigElementTypes.ASSIGN_SUB_SAT),
        Pair(ZigTokenTypes.EQUAL, ZigElementTypes.ASSIGN),
    )

    private fun parseAssignExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = parseExpr(builder) ?: return null
        return finishAssignExpr(builder, expr)
    }

    private fun parseSingleAssignExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val lhs = parseExpr(builder) ?: return null
        val assignType = assignTable[builder.tokenType] ?: return lhs
        val assign = lhs.precede()
        builder.advanceLexer()
        tryExpr(builder)
        assign.done(assignType)
        return assign
    }

    private fun tryAssignExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = parseAssignExpr(builder)
        if (expr == null) {
            // TODO: mark error
        }
        return expr
    }

    private fun trySingleAssignExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = parseSingleAssignExpr(builder)
        if (expr == null) {
            // TODO: mark error
        }
        return expr
    }

    private fun finishAssignExpr(builder: PsiBuilder, lhs: PsiBuilder.Marker): PsiBuilder.Marker {
        if (builder.tokenType === ZigTokenTypes.COMMA) {
            // TODO
            return lhs
        }
        val assignType = assignTable[builder.tokenType] ?: return lhs
        val assign = lhs.precede()
        builder.advanceLexer()
        tryExpr(builder)
        assign.done(assignType)
        return assign
    }

    private fun parseLoopStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        val statement = parseForStatement(builder) ?: parseWhileStatement(builder)
        if (statement != null) {
            return statement
        }
        if (expect(builder, ZigTokenTypes.KEYWORD_INLINE)) {
            // TODO: mark error
        }
        return null
    }

    private fun parseSwitchExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val marker = builder.mark()
        expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
        if (!expect(builder, ZigTokenTypes.KEYWORD_SWITCH)) {
            marker.rollbackTo()
            return null
        }
        trySwitchSuffix(builder)
        marker.done(ZigElementTypes.SWITCH)
        return marker
    }

    private fun assertSwitchExpr(builder: PsiBuilder): PsiBuilder.Marker {
        val marker = builder.mark()
        expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
        assert(builder.tokenType === ZigTokenTypes.KEYWORD_SWITCH)
        builder.advanceLexer()
        trySwitchSuffix(builder)
        marker.done(ZigElementTypes.SWITCH)
        return marker
    }

    private fun trySwitchSuffix(builder: PsiBuilder) {
        if (parseCondition(builder) == null) {
            // TODO: mark error
        }
        if (parseSwitchProngList(builder) == null) {
            // TODO: mark error
        }
    }

    private fun parseSwitchProngList(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.L_BRACE) {
            return null
        }
        val marker = builder.mark()
        parseBraceEnclosed(builder) {
            parseSwitchProng(builder)
        }
        marker.done(ZigElementTypes.SWITCH_PRONG_LIST)
        return marker
    }

    private fun parseSwitchProng(builder: PsiBuilder): PsiBuilder.Marker? {
        val marker = builder.mark()
        expect(builder, ZigTokenTypes.KEYWORD_INLINE)
        if (!expect(builder, ZigTokenTypes.KEYWORD_ELSE)) {
            if (parseSwitchItem(builder) == null) {
                marker.rollbackTo()
                return null
            }
            while (expect(builder, ZigTokenTypes.COMMA)) {
                if (parseSwitchItem(builder) == null) {
                    break
                }
            }
        }
        if (!expect(builder, ZigTokenTypes.EQUAL_ANGLE_BRACKET_RIGHT)) {
            // TODO: mark error
        }
        parsePtrIndexPayload(builder)
        val result = builder.mark()
        trySingleAssignExpr(builder)
        result.done(ZigElementTypes.SWITCH_RESULT)
        marker.done(ZigElementTypes.SWITCH_PRONG)
        if (!expect(builder, ZigTokenTypes.COMMA) && builder.tokenType !== ZigTokenTypes.R_BRACE) {
            // TODO: mark error
        }
        return marker
    }

    private fun parseSwitchItem(builder: PsiBuilder): PsiBuilder.Marker? {
        val marker = builder.mark()
        if (parseExpr(builder) == null) {
            marker.rollbackTo()
            return null
        }
        if (expect(builder, ZigTokenTypes.ELLIPSIS3)) {
            tryExpr(builder)
        }
        marker.done(ZigElementTypes.SWITCH_ITEM)
        return marker
    }

    private fun parsePayload(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.PIPE) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        val entry = builder.mark()
        if (!expect(builder, ZigTokenTypes.IDENTIFIER)) {
            // TODO: mark error
        }
        entry.done(ZigElementTypes.PAYLOAD_ENTRY)
        if (!expect(builder, ZigTokenTypes.PIPE)) {
            // TODO: mark error
        }
        marker.done(ZigElementTypes.PAYLOAD)
        return marker
    }

    private fun parsePtrPayload(builder: PsiBuilder, includeIndex: Boolean = false): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.PIPE) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        val entry = builder.mark()
        expect(builder, ZigTokenTypes.ASTERISK)
        if (!expect(builder, ZigTokenTypes.IDENTIFIER)) {
            // TODO: mark error
        }
        entry.done(ZigElementTypes.PAYLOAD_ENTRY)
        if (includeIndex) {
            if (expect(builder, ZigTokenTypes.COMMA)) {
                val indexEntry = builder.mark()
                if (!expect(builder, ZigTokenTypes.IDENTIFIER)) {
                    // TODO: mark error
                }
                indexEntry.done(ZigElementTypes.PAYLOAD_ENTRY)
            }
        }
        if (!expect(builder, ZigTokenTypes.PIPE)) {
            // TODO: mark error
        }
        marker.done(ZigElementTypes.PAYLOAD)
        return marker
    }

    private fun parsePtrIndexPayload(builder: PsiBuilder): PsiBuilder.Marker? {
        return parsePtrPayload(builder, true)
    }

    private fun parseGroupedExpression(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        var expr: PsiBuilder.Marker? = null
        parseParenEnclosed(builder) {
            if (expr == null) {
                expr = tryExpr(builder)
                expr
            } else {
                null
            }
        }
        if (expr == null) {
            // TODO: mark error
        }
        marker.done(ZigElementTypes.GROUPED_EXPRESSION)
        return marker
    }

    private fun parseFor(builder: PsiBuilder, bodyParseFn: (PsiBuilder) -> PsiBuilder.Marker?): PsiBuilder.Marker? {
        val forMarker = forPrefix(builder) ?: return null
        val thenMarker = builder.mark()
        if (bodyParseFn(builder) == null) {
            // TODO: mark error
            return null
        }
        thenMarker.done(ZigElementTypes.THEN)
        if (builder.tokenType === ZigTokenTypes.KEYWORD_ELSE) {
            val elseMarker = builder.mark()
            builder.advanceLexer()
            if (bodyParseFn(builder) == null) {
                // TODO: mark error
                return null
            }
            elseMarker.done(ZigElementTypes.ELSE)
        }
        forMarker.done(ZigElementTypes.FOR)
        return forMarker
    }

    private fun parseForStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        val forMarker = forPrefix(builder) ?: return null
        var elseRequired = false;
        val thenMarker = builder.mark()
        if (parseBlockExpr(builder) == null) {
            if (parseAssignExpr(builder) == null) {
                // TODO: mark error
            }
            if (expect(builder, ZigTokenTypes.SEMICOLON)) {
                thenMarker.done(ZigElementTypes.THEN)
                forMarker.done(ZigElementTypes.FOR)
                return forMarker
            }
            elseRequired = true
        }
        thenMarker.done(ZigElementTypes.THEN)
        if (builder.tokenType === ZigTokenTypes.KEYWORD_ELSE) {
            val elseMarker = builder.mark()
            builder.advanceLexer()
            tryStatement(builder, false)
            elseMarker.done(ZigElementTypes.ELSE)
        } else if (elseRequired) {
            // TODO: mark error
        }
        forMarker.done(ZigElementTypes.FOR)
        return forMarker
    }

    private fun forPrefix(builder: PsiBuilder): PsiBuilder.Marker? {
        val forMarker = builder.mark()
        expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
        expect(builder, ZigTokenTypes.KEYWORD_INLINE)
        if (!expect(builder, ZigTokenTypes.KEYWORD_FOR)) {
            forMarker.rollbackTo()
            return null
        }
        if (parseForInputs(builder) == null) {
            // TODO: mark error
        }
        if (builder.tokenType !== ZigTokenTypes.PIPE) {
            // TODO: mark error
            return forMarker
        }
        val payload = builder.mark()
        builder.advanceLexer()
        while (!builder.eof()) {
            val entry = builder.mark()
            expect(builder, ZigTokenTypes.ASTERISK)
            if (expect(builder, ZigTokenTypes.IDENTIFIER)) {
                entry.done(ZigElementTypes.PAYLOAD_ENTRY)
            } else {
                entry.drop()
                // TODO: mark error
            }
            if (!expect(builder, ZigTokenTypes.COMMA)) {
                break
            }
        }
        if (!expect(builder, ZigTokenTypes.PIPE)) {
            // TODO: mark error
        }
        payload.done(ZigElementTypes.PAYLOAD)
        return forMarker
    }

    private fun parseForInputs(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        var hasInput = false
        parseParenEnclosed(builder) {
            val input = tryForInput(builder)
            if (input != null) {
                hasInput = true
            }
            input
        }
        if (!hasInput) {
            // TODO: mark error
        }
        marker.done(ZigElementTypes.FOR_INPUTS)
        return marker
    }

    private fun tryForInput(builder: PsiBuilder): PsiBuilder.Marker? {
        var input = tryExpr(builder) ?: return null
        if (expect(builder, ZigTokenTypes.ELLIPSIS2)) {
            tryExpr(builder)
            input = input.precede()
            input.done(ZigElementTypes.FOR_RANGE)
        }
        if (!expect(builder, ZigTokenTypes.COMMA) && builder.tokenType !== ZigTokenTypes.R_PAREN) {
            // TODO: mark error
        }
        return input
    }

    private fun parseWhile(builder: PsiBuilder, bodyParseFn: (PsiBuilder) -> PsiBuilder.Marker?): PsiBuilder.Marker? {
        val whileMarker = whilePrefix(builder) ?: return null
        val thenMarker = builder.mark()
        if (bodyParseFn(builder) == null) {
            // TODO: mark error
            return null
        }
        thenMarker.done(ZigElementTypes.THEN)
        if (builder.tokenType === ZigTokenTypes.KEYWORD_ELSE) {
            val elseMarker = builder.mark()
            builder.advanceLexer()
            parsePayload(builder)
            if (bodyParseFn(builder) == null) {
                // TODO: mark error
                return null
            }
            elseMarker.done(ZigElementTypes.ELSE)
        }
        whileMarker.done(ZigElementTypes.WHILE)
        return whileMarker
    }

    private fun parseWhileStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        val whileMarker = whilePrefix(builder) ?: return null
        var elseRequired = false
        val thenMarker = builder.mark()
        if (parseBlockExpr(builder) == null) {
            if (parseAssignExpr(builder) == null) {
                // TODO: mark error
            }
            if (expect(builder, ZigTokenTypes.SEMICOLON)) {
                thenMarker.done(ZigElementTypes.THEN)
                whileMarker.done(ZigElementTypes.WHILE)
                return whileMarker
            }
            elseRequired = true
        }
        thenMarker.done(ZigElementTypes.THEN)
        if (builder.tokenType === ZigTokenTypes.KEYWORD_ELSE) {
            val elseMarker = builder.mark()
            builder.advanceLexer()
            parsePayload(builder)
            tryStatement(builder, false)
            elseMarker.done(ZigElementTypes.ELSE)
        } else if (elseRequired) {
            // TODO: mark error
        }
        whileMarker.done(ZigElementTypes.WHILE)
        return whileMarker
    }

    private fun whilePrefix(builder: PsiBuilder): PsiBuilder.Marker? {
        val whileMarker = builder.mark()
        expect(builder, ZigTokenTypes.IDENTIFIER, ZigTokenTypes.COLON)
        expect(builder, ZigTokenTypes.KEYWORD_INLINE)
        if (!expect(builder, ZigTokenTypes.KEYWORD_WHILE)) {
            whileMarker.rollbackTo()
            return null
        }
        if (parseCondition(builder) == null) {
            // TODO: mark error
        }
        parsePtrPayload(builder)
        if (expect(builder, ZigTokenTypes.COLON) && parseWhileContinueExpr(builder) == null) {
            // TODO: mark error
        }
        return whileMarker
    }

    private fun parseWhileContinueExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenTypes.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        var expr: PsiBuilder.Marker? = null
        parseParenEnclosed(builder) {
            if (expr == null) {
                expr = parseAssignExpr(builder)
                expr
            } else {
                null
            }
        }
        if (expr == null) {
            // TODO: mark error
        }
        marker.done(ZigElementTypes.WHILE_CONTINUE_EXPR)
        return marker
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

    private fun parseParenEnclosedExpr(
        builder: PsiBuilder,
        prefix: IElementType,
        type: IElementType,
    ): PsiBuilder.Marker? {
        if (builder.tokenType !== prefix) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        if (builder.tokenType === ZigTokenTypes.L_PAREN) {
            var expr: PsiBuilder.Marker? = null
            parseParenEnclosed(builder) {
                if (expr == null) {
                    expr = tryExpr(builder)
                    expr
                } else {
                    null
                }
            }
            if (expr == null) {
                // TODO: mark error
            }
        } else {
            // TODO: mark error
        }
        marker.done(type)
        return marker
    }

    private fun parseParenEnclosed(builder: PsiBuilder, parseMember: () -> PsiBuilder.Marker?) {
        val enclosure = Enclosure(EnclosureType.OTHER, ZigTokenTypes.L_PAREN, ZigTokenTypes.R_PAREN)
        parseEnclosed(builder, enclosure, parseMember)
    }

    private fun parseBracketEnclosed(builder: PsiBuilder, parseMember: () -> PsiBuilder.Marker?) {
        val enclosure = Enclosure(EnclosureType.OTHER, ZigTokenTypes.L_BRACKET, ZigTokenTypes.R_BRACKET)
        parseEnclosed(builder, enclosure, parseMember)
    }

    private fun parseBraceEnclosed(
        builder: PsiBuilder,
        type: EnclosureType = EnclosureType.OTHER,
        parseMember: () -> PsiBuilder.Marker?,
    ) {
        val enclosure = Enclosure(type, ZigTokenTypes.L_BRACE, ZigTokenTypes.R_BRACE)
        parseEnclosed(builder, enclosure, parseMember)
    }

    private fun parseEnclosed(builder: PsiBuilder, enclosure: Enclosure, parseMember: () -> PsiBuilder.Marker?) {
        if (!expect(builder, enclosure.left)) {
            return
        }
        enclosureStack.addLast(enclosure)
        while (!builder.eof()) {
            if (builder.tokenType === enclosure.right) {
                builder.advanceLexer()
                break
            }
            if (enclosureStack.any { builder.tokenType === it.right}) {
                break
            }
            if (parseMember() != null) {
                continue
            }
            when (builder.tokenType) {
                ZigTokenTypes.L_BRACE -> parseBraceEnclosed(builder) { null }
                ZigTokenTypes.L_BRACKET -> parseBracketEnclosed(builder) { null }
                ZigTokenTypes.L_PAREN -> parseParenEnclosed(builder) { null }
                else -> {
                    val invalid = builder.mark()
                    builder.advanceLexer()
                    invalid.error("Unexpected token")
                }
            }
        }
        enclosureStack.removeLast()
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
