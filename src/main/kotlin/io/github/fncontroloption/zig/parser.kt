package io.github.fncontroloption.zig

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.fncontroloption.zig.psi2.ZigElementType
import io.github.fncontroloption.zig.psi2.ZigFile
import io.github.fncontroloption.zig.psi2.ZigFileElementType
import io.github.fncontroloption.zig.psi2.ZigTokenSets
import io.github.fncontroloption.zig.psi2.ZigTokenType

class ZigParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = ZigLexerAdapter()
    override fun getCommentTokens() = ZigTokenSets.COMMENTS
    override fun getStringLiteralElements() = ZigTokenSets.STRINGS
    override fun createParser(project: Project?) = ZigParser()
    override fun getFileNodeType() = ZigFileElementType
    override fun createFile(viewProvider: FileViewProvider) = ZigFile(viewProvider)
    override fun createElement(node: ASTNode) = ZigElementType.createPsiElement(node)
}

// Based on https://codeberg.org/ziglang/zig/src/commit/72d954e/lib/std/zig/Parse.zig
/** @see com.intellij.java.syntax.parser.JavaParser */
class ZigParser : PsiParser {
    private enum class EnclosureType { CONTAINER, OTHER }
    private class Enclosure(val type: EnclosureType, val right: IElementType?)

    private val enclosureStack = ArrayDeque<Enclosure>()
    private val containerStack = ArrayDeque<IElementType>()

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        containerStack.addLast(root)
        val rootMarker = builder.mark()
        parseEnclosed(builder, null, EnclosureType.CONTAINER) {
            parseContainerMember(builder)
        }
        rootMarker.done(root)
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
        eatToken(builder, ZigTokenSets.EXTERN_PACKED)
        val containerToken = builder.tokenType
        if (!eatToken(builder, ZigTokenSets.CONTAINERS)) {
            container.rollbackTo()
            return null
        }
        containerStack.addLast(containerToken!!)
        parseContainerDeclTag(builder)
        if (expectToken(builder, ZigTokenType.L_BRACE)) {
            parseEnclosed(builder, ZigTokenType.R_BRACE, EnclosureType.CONTAINER) {
                parseContainerMember(builder)
            }
            expectToken(builder, ZigTokenType.R_BRACE)
        }
        containerStack.removeLast()
        container.done(ZigElementType.CONTAINER_DECL)
        return container
    }

    private fun parseContainerDeclTag(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        var expr: PsiBuilder.Marker? = null
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
            if (expr == null) {
                when (builder.tokenType) {
                    ZigTokenType.KEYWORD_ENUM -> {
                        when (builder.lookAhead(1)) {
                            ZigTokenType.L_PAREN -> {
                                val enum = builder.mark()
                                builder.advanceLexer()
                                builder.advanceLexer()
                                var enumExpr: PsiBuilder.Marker? = null
                                parseEnclosed(builder, ZigTokenType.R_PAREN) {
                                    if (enumExpr == null) {
                                        enumExpr = parseExpr(builder)
                                        enumExpr
                                    } else {
                                        null
                                    }
                                }
                                if (enumExpr == null) {
                                    // TODO: mark error
                                }
                                expectToken(builder, ZigTokenType.R_PAREN)
                                enum.done(ZigElementType.CONTAINER_TAG_ENUM)
                                expr = enum
                            }

                            ZigTokenType.R_PAREN -> {
                                val enum = builder.mark()
                                builder.advanceLexer()
                                enum.done(ZigElementType.CONTAINER_TAG_ENUM)
                                expr = enum
                            }

                            else -> expr = parseExpr(builder)
                        }
                    }

                    else -> expr = parseExpr(builder)
                }
                expr
            } else {
                null
            }
        }
        if (expr == null) {
            // TODO: mark error
        }
        expectToken(builder, ZigTokenType.R_PAREN)
        marker.done(ZigElementType.CONTAINER_TAG)
        return marker
    }

    private fun parseBlock(builder: PsiBuilder, allowLabel: Boolean = false): PsiBuilder.Marker? {
        val prevToken = builder.rawLookup(-1)
        if (prevToken === ZigTokenType.PERIOD) {
            return null
        }

        val marker = builder.mark()
        if (allowLabel) {
            eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON)
        }
        if (!eatToken(builder, ZigTokenType.L_BRACE)) {
            marker.rollbackTo()
            return null
        }
        parseEnclosed(builder, ZigTokenType.R_BRACE) {
            tryStatement(builder, true)
        }
        expectToken(builder, ZigTokenType.R_BRACE)
        marker.done(ZigElementType.BLOCK)
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
        val assignExpr = parseAssignExpr(builder)
        if (assignExpr != null) {
            expectToken(builder, ZigTokenType.SEMICOLON)
            return assignExpr
        }
        return null
    }

    private fun tryBlockExprStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = parseBlockExprStatement(builder)
        if (expr == null) {
            builder.mark().error("Expected block or expression")
        }
        return expr
    }

    private fun parseBuiltinCall(builder: PsiBuilder): PsiBuilder.Marker? {
        val call = builder.mark()
        if (!eatToken(builder, ZigTokenType.BUILTIN)) {
            call.rollbackTo()
            return null
        }
        if (expectToken(builder, ZigTokenType.L_PAREN)) {
            parseEnclosed(builder, ZigTokenType.R_PAREN) {
                tryCallArg(builder)
            }
            expectToken(builder, ZigTokenType.R_PAREN)
        }
        call.done(ZigElementType.BUILTIN_CALL)
        return call
    }

    private fun parseVarDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        eatToken(builder, ZigTokenType.KEYWORD_PUB)
        if (eatToken(builder, ZigTokenType.KEYWORD_EXTERN)) {
            eatToken(builder, ZigTokenType.STRING_LITERAL)
        } else {
            eatToken(builder, ZigTokenType.KEYWORD_EXPORT)
        }
        eatToken(builder, ZigTokenType.KEYWORD_THREADLOCAL)
        val proto = parseVarDeclProto(builder)
        if (proto == null) {
            decl.rollbackTo()
            return null
        }
        proto.drop()
        if (!eatToken(builder, ZigTokenType.EQUAL)) {
            decl.rollbackTo()
            return null
        }
        tryExpr(builder)
        expectToken(builder, ZigTokenType.SEMICOLON)
        decl.done(ZigElementType.VAR_DECL)
        return decl
    }

    private fun parseVarDeclProto(builder: PsiBuilder): PsiBuilder.Marker? {
        val prevToken = builder.rawLookup(-1)
        if (prevToken in ZigTokenSets.ASTERISK_BRACKET_PAREN) {
            // e.g. *const Foo, []const Foo, *align(16) const Foo
            return null
        }
        val proto = builder.mark()
        if (!eatToken(builder, ZigTokenSets.MUT_TOKENS)) {
            proto.rollbackTo()
            return null
        }
        if (!eatToken(builder, ZigTokenType.IDENTIFIER)) {
            proto.rollbackTo()
            return null
        }
        if (eatToken(builder, ZigTokenType.COLON)) {
            val type = builder.mark()
            if (tryTypeExpr(builder) == null) {
                proto.rollbackTo()
                return null
            }
            type.done(ZigElementType.TYPE)
        }
        parseAlign(builder)
        parseAddrspace(builder)
        parseLinksection(builder)
        proto.done(ZigElementType.VAR_PROTO)
        return proto
    }

    private fun parseAlign(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseParenEnclosedExpr(builder, ZigTokenType.KEYWORD_ALIGN, ZigElementType.ALIGN_MODIFIER)
    }

    private fun parseAddrspace(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseParenEnclosedExpr(builder, ZigTokenType.KEYWORD_ADDRSPACE, ZigElementType.ADDRSPACE_MODIFIER)
    }

    private fun parseLinksection(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseParenEnclosedExpr(builder, ZigTokenType.KEYWORD_LINKSECTION, ZigElementType.LINKSECTION_MODIFIER)
    }

    private fun parseCallconv(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseParenEnclosedExpr(builder, ZigTokenType.KEYWORD_CALLCONV, ZigElementType.CALLCONV_MODIFIER)
    }

    private fun parseFnProto(builder: PsiBuilder, requireName: Boolean, terminators: TokenSet?): PsiBuilder.Marker? {
        val proto = builder.mark()
        if (!eatToken(builder, ZigTokenType.KEYWORD_FN)) {
            proto.rollbackTo()
            return null
        }
        val hasName = eatToken(builder, ZigTokenType.IDENTIFIER)
        if (!hasName && requireName) {
            proto.rollbackTo()
            return null
        }
        if (expectToken(builder, ZigTokenType.L_PAREN)) {
            parseEnclosed(builder, ZigTokenType.R_PAREN) {
                tryFnParamDecl(builder)
            }
            expectToken(builder, ZigTokenType.R_PAREN)
        }
        parseAlign(builder)
        parseAddrspace(builder)
        parseLinksection(builder)
        parseCallconv(builder)
        eatToken(builder, ZigTokenType.BANG)
        val returnType = builder.mark()
        if (parseTypeExpr(builder) == null) {
            builder.mark().error("Expected return type expression")
            if (terminators == null) {
                proto.rollbackTo()
                return null
            }
            parseUntil(builder, terminators)
        }
        returnType.done(ZigElementType.TYPE)
        proto.done(ZigElementType.FN_PROTO)
        return proto
    }

    private fun parseFnDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        eatToken(builder, ZigTokenType.KEYWORD_PUB)
        if (eatToken(builder, ZigTokenType.KEYWORD_EXTERN)) {
            eatToken(builder, ZigTokenType.STRING_LITERAL)
        } else {
            eatToken(builder, ZigTokenSets.EXPORT_INLINE)
        }
        val proto = parseFnProto(builder, true, ZigTokenSets.SEMICOLON_BRACE)
        if (proto == null) {
            decl.rollbackTo()
            return null
        }
        proto.drop()
        if (!eatToken(builder, ZigTokenType.SEMICOLON)) {
            parseBlock(builder)
        }
        decl.done(ZigElementType.FN_DECL)
        return decl
    }

    private fun tryFnParamDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        if (eatToken(builder, ZigTokenType.ELLIPSIS3)) {
            decl.drop()
            eatToken(builder, ZigTokenType.COMMA)
            if (builder.tokenType !== ZigTokenType.R_PAREN) {
                builder.mark().error("Function prototype has parameter after varargs")
            }
            return decl
        }
        eatToken(builder, ZigTokenSets.NOALIAS_COMPTIME)
        val hasName = eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON)
        if (!eatToken(builder, ZigTokenType.KEYWORD_ANYTYPE)) {
            val type = builder.mark()
            if (tryTypeExpr(builder) == null && !hasName) {
                decl.rollbackTo()
                return null
            }
            type.done(ZigElementType.TYPE)
        }
        decl.done(ZigElementType.PARAM_DECL)
        if (!eatToken(builder, ZigTokenType.COMMA) && builder.tokenType !== ZigTokenType.R_PAREN) {
            builder.mark().error("Expected ',' after parameter")
        }
        return decl
    }

    private fun parseErrorSetDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.KEYWORD_ERROR) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        if (!eatToken(builder, ZigTokenType.L_BRACE)) {
            marker.rollbackTo()
            return null
        }
        parseEnclosed(builder, ZigTokenType.R_BRACE) {
            parseErrorSetMember(builder)
        }
        expectToken(builder, ZigTokenType.R_BRACE)
        marker.done(ZigElementType.ERROR_SET_DECL)
        return marker
    }

    private fun parseErrorSetMember(builder: PsiBuilder): PsiBuilder.Marker? {
        val field = builder.mark()
        if (!eatToken(builder, ZigTokenType.IDENTIFIER)) {
            field.rollbackTo()
            return null
        }
        eatToken(builder, ZigTokenType.COMMA)
        field.done(ZigElementType.ERROR_SET_ITEM)
        return field
    }

    private fun parseTestDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        if (!eatToken(builder, ZigTokenType.KEYWORD_TEST)) {
            decl.rollbackTo()
            return null
        }
        eatToken(builder, ZigTokenSets.TEST_NAME)
        parseBlock(builder)
        decl.done(ZigElementType.TEST_DECL)
        return decl
    }

    private fun parseComptimeBlock(builder: PsiBuilder): PsiBuilder.Marker? {
        val comptime = builder.mark()
        if (!eatToken(builder, ZigTokenType.KEYWORD_COMPTIME)) {
            comptime.rollbackTo()
            return null
        }
        parseBlock(builder)
        comptime.done(ZigElementType.COMPTIME)
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
        eatToken(builder, ZigTokenType.KEYWORD_COMPTIME)
        val hasName = eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON)
        if (hasName || containerType === ZigTokenType.KEYWORD_STRUCT) {
            val type = builder.mark()
            if (tryTypeExpr(builder) == null) {
                field.rollbackTo()
                return null
            }
            type.done(ZigElementType.TYPE)
        } else if (!eatToken(builder, ZigTokenType.IDENTIFIER)) {
            field.rollbackTo()
            return null
        }
        parseAlign(builder)
        if (eatToken(builder, ZigTokenType.EQUAL)) {
            tryExpr(builder)
        }
        field.done(ZigElementType.CONTAINER_FIELD)
        if (!eatToken(builder, ZigTokenType.COMMA)) {
            val terminator = when (containerType) {
                ZigFileElementType -> null
                else -> ZigTokenType.R_BRACE
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
            ZigTokenType.QUESTION_MARK -> {
                val expr = builder.mark()
                builder.advanceLexer()
                if (tryTypeExpr(builder) != null) {
                    expr.done(ZigElementType.OPTIONAL_TYPE)
                    return expr
                } else {
                    expr.rollbackTo()
                    return null
                }
            }
            ZigTokenType.KEYWORD_ANYFRAME if builder.lookAhead(1) === ZigTokenType.ARROW -> {
                val expr = builder.mark()
                builder.advanceLexer()
                builder.advanceLexer()
                if (tryTypeExpr(builder) != null) {
                    expr.done(ZigElementType.ANYFRAME_TYPE)
                    return expr
                } else {
                    expr.rollbackTo()
                    return null
                }
            }
            ZigTokenType.ASTERISK -> {
                val expr = builder.mark()
                builder.advanceLexer()
                parsePointerModifiers(builder)
                if (tryTypeExpr(builder) != null) {
                    expr.done(ZigElementType.POINTER_TYPE)
                    return expr
                } else {
                    expr.rollbackTo()
                    return null
                }
            }
            ZigTokenType.L_BRACKET -> {
                val expr = builder.mark()
                builder.advanceLexer()
                var isArray = false
                var lengthState = TypeLengthState.START
                parseEnclosed(builder, ZigTokenType.R_BRACKET) {
                    when (lengthState) {
                        TypeLengthState.START -> {
                            val length = builder.mark()
                            when (builder.tokenType) {
                                ZigTokenType.ASTERISK -> {
                                    isArray = false
                                    builder.advanceLexer()
                                    when (builder.tokenType) {
                                        ZigTokenType.IDENTIFIER if builder.tokenText == "c" -> {
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
                            length.done(ZigElementType.TYPE_LENGTH)
                            length
                        }

                        TypeLengthState.SENTINEL -> {
                            when (builder.tokenType) {
                                ZigTokenType.COLON -> {
                                    val sentinel = builder.mark()
                                    builder.advanceLexer()
                                    tryExpr(builder)
                                    lengthState = TypeLengthState.DONE
                                    sentinel.done(ZigElementType.TYPE_SENTINEL)
                                    sentinel
                                }

                                else -> null
                            }
                        }

                        TypeLengthState.DONE -> null
                    }
                }
                expectToken(builder, ZigTokenType.R_BRACKET)
                val hasModifiers = parsePointerModifiers(builder)
                if (tryTypeExpr(builder) == null) {
                    expr.rollbackTo()
                    return null
                }
                if (isArray) {
                    if (hasModifiers) {
                        // TODO: mark error
                    }
                    expr.done(ZigElementType.ARRAY_TYPE)
                } else {
                    expr.done(ZigElementType.POINTER_TYPE)
                }
                return expr
            }
            else -> return parseErrorUnionExpr(builder)
        }
    }

    private fun parsePointerModifiers(builder: PsiBuilder): Boolean {
        if (builder.tokenType !in ZigTokenSets.POINTER_MODIFIERS) {
            return false
        }
        while (!builder.eof()) {
            when (builder.tokenType) {
                ZigTokenType.KEYWORD_ALIGN -> {
                    parseAlign(builder)
                }
                ZigTokenType.KEYWORD_CONST -> {
                    builder.advanceLexer()
                }
                ZigTokenType.KEYWORD_VOLATILE -> {
                    builder.advanceLexer()
                }
                ZigTokenType.KEYWORD_ALLOWZERO -> {
                    builder.advanceLexer()
                }
                ZigTokenType.KEYWORD_ADDRSPACE -> {
                    parseAddrspace(builder)
                }
                else -> break
            }
        }
        return true
    }

    private fun parseErrorUnionExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        var expr = parseSuffixExpr(builder) ?: return null
        if (!eatToken(builder, ZigTokenType.BANG)) {
            return expr
        }
        expr = expr.precede()
        if (tryTypeExpr(builder) == null) {
            expr.rollbackTo()
            return null
        }
        expr.done(ZigElementType.ERROR_UNION)
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
        if (!eatToken(builder, ZigTokenType.L_PAREN)) {
            return null
        }
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
            tryCallArg(builder)
        }
        expectToken(builder, ZigTokenType.R_PAREN)
        val call = lhs.precede()
        call.done(ZigElementType.CALL)
        return call
    }

    private fun tryCallArg(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = tryExpr(builder) ?: return null
        val arg = expr.precede()
        arg.done(ZigElementType.CALL_ARG)
        if (!eatToken(builder, ZigTokenType.COMMA) && builder.tokenType !== ZigTokenType.R_PAREN) {
            // TODO: mark error
        }
        return arg
    }

    private fun parseSuffixOp(builder: PsiBuilder): IElementType? {
        when (builder.tokenType) {
            ZigTokenType.L_BRACKET -> {
                builder.advanceLexer()
                var arrayIndex: PsiBuilder.Marker? = null
                var sliceIndexes: PsiBuilder.Marker? = null
                var sentinel: PsiBuilder.Marker? = null
                parseEnclosed(builder, ZigTokenType.R_BRACKET) {
                    if (arrayIndex == null) {
                        val expr = parseExpr(builder)
                        if (expr != null) {
                            arrayIndex = expr.precede()
                            arrayIndex.done(ZigElementType.ARRAY_INDEX)
                        }
                        arrayIndex
                    } else if (sliceIndexes == null) {
                        if (builder.tokenType === ZigTokenType.ELLIPSIS2) {
                            sliceIndexes = arrayIndex.precede()
                            arrayIndex.drop()
                            builder.advanceLexer()
                            parseExpr(builder)
                            sliceIndexes.done(ZigElementType.SLICE_INDEXES)
                            sliceIndexes
                        } else {
                            null
                        }
                    } else if (sentinel == null) {
                        if (builder.tokenType === ZigTokenType.COLON) {
                            sentinel = builder.mark()
                            builder.advanceLexer()
                            tryExpr(builder)
                            sentinel.done(ZigElementType.SLICE_SENTINEL)
                            sentinel
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
                if (arrayIndex == null) {
                    // TODO: mark error
                }
                expectToken(builder, ZigTokenType.R_BRACKET)
                if (sliceIndexes != null) {
                    return ZigElementType.SLICE
                }
                return ZigElementType.ARRAY_ACCESS
            }
            ZigTokenType.PERIOD_ASTERISK -> {
                builder.advanceLexer()
                return ZigElementType.DEREF
            }
            ZigTokenType.PERIOD -> {
                when (builder.lookAhead(1)) {
                    ZigTokenType.IDENTIFIER -> {
                        builder.advanceLexer()
                        builder.advanceLexer()
                        return ZigElementType.FIELD_ACCESS
                    }
                    ZigTokenType.QUESTION_MARK -> {
                        builder.advanceLexer()
                        builder.advanceLexer()
                        return ZigElementType.UNWRAP_OPTIONAL
                    }
                    ZigTokenType.L_BRACE -> {
                        return null
                    }
                    else -> {
                        // TODO: mark error
                        builder.advanceLexer()
                        return ZigElementType.FIELD_ACCESS
                    }
                }
            }
        }
        return null
    }

    private fun parsePrimaryTypeExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        when (builder.tokenType) {
            ZigTokenType.CHAR_LITERAL -> {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementType.CHAR_LITERAL)
                return marker
            }
            ZigTokenType.NUMBER_LITERAL -> {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementType.NUMBER_LITERAL)
                return marker
            }
            ZigTokenType.KEYWORD_UNREACHABLE -> {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementType.UNREACHABLE_LITERAL)
                return marker
            }
            ZigTokenType.KEYWORD_ANYFRAME -> {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementType.ANYFRAME_LITERAL)
                return marker
            }
            ZigTokenType.STRING_LITERAL -> {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementType.STRING_LITERAL)
                return marker
            }
            ZigTokenType.BUILTIN -> return parseBuiltinCall(builder)
            ZigTokenType.KEYWORD_FN -> return parseFnProto(builder, false, null)
            ZigTokenType.KEYWORD_IF -> return parseIf(builder, this::tryTypeExpr)
            ZigTokenType.KEYWORD_SWITCH -> return assertSwitchExpr(builder)
            in ZigTokenSets.EXTERN_PACKED, in ZigTokenSets.CONTAINERS -> return parseContainerDecl(builder)
            ZigTokenType.KEYWORD_COMPTIME -> {
                val marker = builder.mark()
                builder.advanceLexer()
                if (tryTypeExpr(builder) == null) {
                    marker.rollbackTo()
                    return null
                }
                marker.done(ZigElementType.COMPTIME)
                return marker
            }
            ZigTokenType.MULTILINE_STRING_LITERAL_LINE -> {
                val marker = builder.mark()
                while (builder.tokenType === ZigTokenType.MULTILINE_STRING_LITERAL_LINE) {
                    builder.advanceLexer()
                }
                marker.done(ZigElementType.MULTILINE_STRING_LITERAL)
                return marker
            }
            ZigTokenType.IDENTIFIER -> {
                if (builder.lookAhead(1) === ZigTokenType.COLON) {
                    when (builder.lookAhead(2)) {
                        ZigTokenType.KEYWORD_INLINE -> {
                            return when (builder.lookAhead(3)) {
                                ZigTokenType.KEYWORD_FOR -> parseFor(builder, this::tryTypeExpr)
                                ZigTokenType.KEYWORD_WHILE -> parseWhile(builder, this::tryTypeExpr)
                                else -> {
                                    // TODO: mark error
                                    null
                                }
                            }
                        }
                        ZigTokenType.KEYWORD_FOR -> return parseFor(builder, this::tryTypeExpr)
                        ZigTokenType.KEYWORD_WHILE -> return parseWhile(builder, this::tryTypeExpr)
                        ZigTokenType.KEYWORD_SWITCH -> return assertSwitchExpr(builder)
                        ZigTokenType.L_BRACE -> return parseBlockExpr(builder)
                    }
                }
                val marker = builder.mark()
                builder.advanceLexer()
                marker.done(ZigElementType.IDENTIFIER)
                return marker
            }
            ZigTokenType.KEYWORD_INLINE -> {
                return when (builder.lookAhead(1)) {
                    ZigTokenType.KEYWORD_FOR -> parseFor(builder, this::tryTypeExpr)
                    ZigTokenType.KEYWORD_WHILE -> parseWhile(builder, this::tryTypeExpr)
                    else -> {
                        // TODO: mark error
                        null
                    }
                }
            }
            ZigTokenType.KEYWORD_FOR -> return parseFor(builder, this::tryTypeExpr)
            ZigTokenType.KEYWORD_WHILE -> return parseWhile(builder, this::tryTypeExpr)
            ZigTokenType.PERIOD -> {
                when (builder.lookAhead(1)) {
                    ZigTokenType.IDENTIFIER -> {
                        val marker = builder.mark()
                        builder.advanceLexer()
                        builder.advanceLexer()
                        marker.done(ZigElementType.ENUM_LITERAL)
                        return marker
                    }
                    ZigTokenType.L_BRACE -> {
                        val marker = builder.mark()
                        builder.advanceLexer()
                        builder.advanceLexer()
                        parseEnclosed(builder, ZigTokenType.R_BRACE) {
                            tryInitListEntry(builder)
                        }
                        expectToken(builder, ZigTokenType.R_BRACE)
                        marker.done(ZigElementType.LIST_INIT)
                        return marker
                    }
                    else -> return null
                }
            }
            ZigTokenType.KEYWORD_ERROR -> {
                val decl = parseErrorSetDecl(builder)
                if (decl != null) {
                    return decl
                }
                val marker = builder.mark()
                builder.advanceLexer()
                eatToken(builder, ZigTokenType.KEYWORD_ERROR)
                if (!eatToken(builder, ZigTokenType.PERIOD)) {
                    marker.rollbackTo()
                    return null
                }
                eatToken(builder, ZigTokenType.IDENTIFIER)
                marker.done(ZigElementType.ERROR_VALUE)
                return marker
            }
            ZigTokenType.L_PAREN -> return parseGroupedExpression(builder)
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
        Pair(ZigTokenType.KEYWORD_OR, OperInfo(10, ZigElementType.BOOL_OR)),

        Pair(ZigTokenType.KEYWORD_AND, OperInfo(20, ZigElementType.BOOL_AND)),

        Pair(ZigTokenType.EQUAL_EQUAL, OperInfo(30, ZigElementType.EQUAL_EQUAL, Assoc.NONE)),
        Pair(ZigTokenType.BANG_EQUAL, OperInfo(30, ZigElementType.BANG_EQUAL, Assoc.NONE)),
        Pair(ZigTokenType.ANGLE_BRACKET_LEFT, OperInfo(30, ZigElementType.LESS_THAN, Assoc.NONE)),
        Pair(ZigTokenType.ANGLE_BRACKET_RIGHT, OperInfo(30, ZigElementType.GREATER_THAN, Assoc.NONE)),
        Pair(ZigTokenType.ANGLE_BRACKET_LEFT_EQUAL, OperInfo(30, ZigElementType.LESS_OR_EQUAL, Assoc.NONE)),
        Pair(ZigTokenType.ANGLE_BRACKET_RIGHT_EQUAL, OperInfo(30, ZigElementType.GREATER_OR_EQUAL, Assoc.NONE)),

        Pair(ZigTokenType.AMPERSAND, OperInfo(40, ZigElementType.BIT_AND)),
        Pair(ZigTokenType.CARET, OperInfo(40, ZigElementType.BIT_XOR)),
        Pair(ZigTokenType.PIPE, OperInfo(40, ZigElementType.BIT_OR)),
        Pair(ZigTokenType.KEYWORD_ORELSE, OperInfo(40, ZigElementType.ORELSE)),
        Pair(ZigTokenType.KEYWORD_CATCH, OperInfo(40, ZigElementType.CATCH)),

        Pair(ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT, OperInfo(50, ZigElementType.SHL)),
        Pair(ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE, OperInfo(50, ZigElementType.SHL_SAT)),
        Pair(ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT, OperInfo(50, ZigElementType.SHR)),

        Pair(ZigTokenType.PLUS, OperInfo(60, ZigElementType.ADD)),
        Pair(ZigTokenType.MINUS, OperInfo(60, ZigElementType.SUB)),
        Pair(ZigTokenType.PLUS_PLUS, OperInfo(60, ZigElementType.ARRAY_CAT)),
        Pair(ZigTokenType.PLUS_PERCENT, OperInfo(60, ZigElementType.ADD_WRAP)),
        Pair(ZigTokenType.MINUS_PERCENT, OperInfo(60, ZigElementType.SUB_WRAP)),
        Pair(ZigTokenType.PLUS_PIPE, OperInfo(60, ZigElementType.ADD_SAT)),
        Pair(ZigTokenType.MINUS_PIPE, OperInfo(60, ZigElementType.SUB_SAT)),

        Pair(ZigTokenType.PIPE_PIPE, OperInfo(70, ZigElementType.MERGE_ERROR_SETS)),
        Pair(ZigTokenType.ASTERISK, OperInfo(70, ZigElementType.MUL)),
        Pair(ZigTokenType.SLASH, OperInfo(70, ZigElementType.DIV)),
        Pair(ZigTokenType.PERCENT, OperInfo(70, ZigElementType.MOD)),
        Pair(ZigTokenType.ASTERISK_PERCENT, OperInfo(70, ZigElementType.MUL_WRAP)),
        Pair(ZigTokenType.ASTERISK_PIPE, OperInfo(70, ZigElementType.MUL_SAT)),
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
            if (operToken === ZigTokenType.KEYWORD_CATCH) {
                parsePayload(builder, ZigElementType.CATCH_PAYLOAD)
            }
            if (parseExprPrecedence(builder, info.prec + 1) == null) {
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
        Pair(ZigTokenType.BANG, ZigElementType.BOOL_NOT),
        Pair(ZigTokenType.MINUS, ZigElementType.NEGATION),
        Pair(ZigTokenType.TILDE, ZigElementType.BIT_NOT),
        Pair(ZigTokenType.MINUS_PERCENT, ZigElementType.NEGATION_WRAP),
        Pair(ZigTokenType.AMPERSAND, ZigElementType.ADDRESS_OF),
        Pair(ZigTokenType.KEYWORD_TRY, ZigElementType.TRY),
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
            ZigTokenType.KEYWORD_ASM -> return assertAsmExpr(builder)
            ZigTokenType.KEYWORD_IF -> return parseIf(builder, this::tryExpr)
            ZigTokenType.KEYWORD_BREAK -> {
                val expr = builder.mark()
                builder.advanceLexer()
                eatTokens(builder, ZigTokenType.COLON, ZigTokenType.IDENTIFIER)
                parseExpr(builder)
                expr.done(ZigElementType.BREAK)
                return expr
            }
            ZigTokenType.KEYWORD_CONTINUE -> {
                val expr = builder.mark()
                builder.advanceLexer()
                eatTokens(builder, ZigTokenType.COLON, ZigTokenType.IDENTIFIER)
                parseExpr(builder)
                expr.done(ZigElementType.CONTINUE)
                return expr
            }
            ZigTokenType.KEYWORD_COMPTIME -> {
                val expr = builder.mark()
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementType.COMPTIME)
                return expr
            }
            ZigTokenType.KEYWORD_NOSUSPEND -> {
                val expr = builder.mark()
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementType.NOSUSPEND)
                return expr
            }
            ZigTokenType.KEYWORD_RESUME -> {
                val expr = builder.mark()
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementType.RESUME)
                return expr
            }
            ZigTokenType.KEYWORD_RETURN -> {
                val expr = builder.mark()
                builder.advanceLexer()
                parseExpr(builder)
                expr.done(ZigElementType.RETURN)
                return expr
            }
            ZigTokenType.IDENTIFIER -> {
                if (builder.lookAhead(1) === ZigTokenType.COLON) {
                    when (builder.lookAhead(2)) {
                        ZigTokenType.KEYWORD_INLINE -> {
                            return when (builder.lookAhead(3)) {
                                ZigTokenType.KEYWORD_FOR -> parseFor(builder, this::tryExpr)
                                ZigTokenType.KEYWORD_WHILE -> parseWhile(builder, this::tryExpr)
                                else -> {
                                    // TODO: mark error
                                    null
                                }
                            }
                        }
                        ZigTokenType.KEYWORD_FOR -> return parseFor(builder, this::tryExpr)
                        ZigTokenType.KEYWORD_WHILE -> return parseWhile(builder, this::tryExpr)
                    }
                }
                return parseCurlySuffixExpr(builder)
            }
            ZigTokenType.KEYWORD_INLINE -> {
                return when (builder.lookAhead(1)) {
                    ZigTokenType.KEYWORD_FOR -> parseFor(builder, this::tryExpr)
                    ZigTokenType.KEYWORD_WHILE -> parseWhile(builder, this::tryExpr)
                    else -> {
                        // TODO: mark error
                        null
                    }
                }
            }
            ZigTokenType.KEYWORD_FOR -> return parseFor(builder, this::tryExpr)
            ZigTokenType.KEYWORD_WHILE -> return parseWhile(builder, this::tryExpr)
            ZigTokenType.L_BRACE -> return parseBlock(builder)
            else -> return parseCurlySuffixExpr(builder)
        }
    }

    private fun parseCurlySuffixExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val lhs = parseTypeExpr(builder) ?: return null
        if (!eatToken(builder, ZigTokenType.L_BRACE)) {
            return lhs
        }
        val init = lhs.precede()
        parseEnclosed(builder, ZigTokenType.R_BRACE) {
            tryInitListEntry(builder)
        }
        expectToken(builder, ZigTokenType.R_BRACE)
        init.done(ZigElementType.LIST_INIT)
        return init
    }

    private fun tryInitListEntry(builder: PsiBuilder): PsiBuilder.Marker? {
        val entry = builder.mark()
        if (eatTokens(builder, ZigTokenType.PERIOD, ZigTokenType.IDENTIFIER, ZigTokenType.EQUAL)) {
            tryExpr(builder)
        } else if (tryExpr(builder) == null) {
            entry.rollbackTo()
            return null
        }
        if (!eatToken(builder, ZigTokenType.COMMA) && builder.tokenType !== ZigTokenType.R_BRACE) {
            // TODO: mark error
        }
        entry.done(ZigElementType.LIST_INIT_ITEM)
        return entry
    }

    private fun tryStatement(builder: PsiBuilder, isBlockLevel: Boolean): PsiBuilder.Marker? {
        when (builder.tokenType) {
            ZigTokenType.KEYWORD_COMPTIME -> {
                val marker = builder.mark()
                builder.advanceLexer()
                if (parseBlockExpr(builder) == null) {
                    if (isBlockLevel) {
                        tryVarDeclExprStatement(builder)
                    } else {
                        tryAssignExpr(builder)
                        expectToken(builder, ZigTokenType.SEMICOLON)
                    }
                }
                marker.done(ZigElementType.COMPTIME)
                return marker
            }
            ZigTokenType.KEYWORD_NOSUSPEND -> {
                val marker = builder.mark()
                builder.advanceLexer()
                tryBlockExprStatement(builder)
                marker.done(ZigElementType.NOSUSPEND)
                return marker
            }
            ZigTokenType.KEYWORD_SUSPEND -> {
                val marker = builder.mark()
                builder.advanceLexer()
                tryBlockExprStatement(builder)
                marker.done(ZigElementType.SUSPEND)
                return marker
            }
            ZigTokenType.KEYWORD_DEFER -> {
                if (isBlockLevel) {
                    val statement = builder.mark()
                    builder.advanceLexer()
                    tryBlockExprStatement(builder)
                    statement.done(ZigElementType.DEFER)
                    return statement
                }
            }
            ZigTokenType.KEYWORD_ERRDEFER -> {
                if (isBlockLevel) {
                    val statement = builder.mark()
                    builder.advanceLexer()
                    tryBlockExprStatement(builder)
                    statement.done(ZigElementType.ERRDEFER)
                    return statement
                }
            }
            ZigTokenType.KEYWORD_IF -> return assertIfStatement(builder)
            ZigTokenType.KEYWORD_ENUM, ZigTokenType.KEYWORD_STRUCT, ZigTokenType.KEYWORD_UNION -> {
                // TODO: parse C-style container
            }
        }
        val labeledStatement = parseLabeledStatement(builder)
        if (labeledStatement != null) {
            return labeledStatement
        }
        if (isBlockLevel) {
            return tryVarDeclExprStatement(builder)
        } else {
            val expr = tryAssignExpr(builder)
            if (expr == null) {
                return null
            }
            expectToken(builder, ZigTokenType.SEMICOLON)
            return expr
        }
    }

    private fun assertIfStatement(builder: PsiBuilder): PsiBuilder.Marker {
        assert(builder.tokenType === ZigTokenType.KEYWORD_IF)
        val ifMarker = builder.mark()
        builder.advanceLexer()
        if (parseCondition(builder) == null) {
            // TODO: mark error
        }
        parsePtrPayload(builder, ZigElementType.THEN_PAYLOAD)
        var elseRequired = false;
        val thenMarker = builder.mark()
        if (parseBlockExpr(builder) == null) {
            if (parseAssignExpr(builder) == null) {
                // TODO: mark error
            }
            if (eatToken(builder, ZigTokenType.SEMICOLON)) {
                thenMarker.done(ZigElementType.THEN)
                ifMarker.done(ZigElementType.IF)
                return ifMarker
            }
            elseRequired = true
        }
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            parsePayload(builder, ZigElementType.ELSE_PAYLOAD)
            val elseMarker = builder.mark()
            tryStatement(builder, false)
            elseMarker.done(ZigElementType.ELSE)
        } else if (elseRequired) {
            // TODO: mark error
        }
        ifMarker.done(ZigElementType.IF)
        return ifMarker
    }

    private fun parseLabeledStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        val statement = parseBlockExpr(builder) ?: parseLoopStatement(builder) ?: parseSwitchExpr(builder)
        if (statement != null) {
            return statement
        }
        if (!eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON)) {
            return null
        }
        // TODO: mark error
        return null
    }

    private fun tryVarDeclExprStatement(builder: PsiBuilder): PsiBuilder.Marker? {
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
            if (!eatToken(builder, ZigTokenType.COMMA)) {
                break
            }
        }

        assert(scratch.isNotEmpty())

        if (scratch.size > 1 || scratch[0].tokenType === ZigElementType.VAR_PROTO) {
            expectToken(builder, ZigTokenType.EQUAL)
        } else if (!eatToken(builder, ZigTokenType.EQUAL)) {
            statement.drop()
            val expr = finishAssignExpr(builder, scratch[0])
            expectToken(builder, ZigTokenType.SEMICOLON)
            return expr;
        }

        tryExpr(builder)
        expectToken(builder, ZigTokenType.SEMICOLON)

        if (scratch.size == 1) {
            if (scratch[0].tokenType === ZigElementType.VAR_PROTO) {
                scratch[0].drop()
                statement.done(ZigElementType.VAR_DECL)
            } else {
                statement.done(ZigElementType.ASSIGN)
            }
        } else {
            statement.done(ZigElementType.ASSIGN_DESTRUCTURE)
        }
        return statement
    }

    private fun parseIf(builder: PsiBuilder, bodyParseFn: (PsiBuilder) -> PsiBuilder.Marker?): PsiBuilder.Marker? {
        val marker = builder.mark()
        if (!eatToken(builder, ZigTokenType.KEYWORD_IF)) {
            marker.rollbackTo()
            return null
        }
        if (parseCondition(builder) == null) {
            // TODO: mark error
        }
        parsePtrPayload(builder, ZigElementType.THEN_PAYLOAD)
        val thenMarker = builder.mark()
        if (bodyParseFn(builder) == null) {
            // TODO: mark error
        }
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            parsePayload(builder, ZigElementType.ELSE_PAYLOAD)
            val elseMarker = builder.mark()
            if (bodyParseFn(builder) == null) {
                // TODO: mark error
            }
            elseMarker.done(ZigElementType.ELSE)
        }
        marker.done(ZigElementType.IF)
        return marker
    }

    private fun parseCondition(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        var expr: PsiBuilder.Marker? = null
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
            if (expr == null) {
                expr = parseExpr(builder)
                expr
            } else {
                null
            }
        }
        if (expr == null) {
            // TODO: mark error
        }
        expectToken(builder, ZigTokenType.R_PAREN)
        marker.done(ZigElementType.CONDITION)
        return marker
    }

    private val assignTable = mapOf(
        Pair(ZigTokenType.ASTERISK_EQUAL, ZigElementType.ASSIGN_MUL),
        Pair(ZigTokenType.SLASH_EQUAL, ZigElementType.ASSIGN_DIV),
        Pair(ZigTokenType.PERCENT_EQUAL, ZigElementType.ASSIGN_MOD),
        Pair(ZigTokenType.PLUS_EQUAL, ZigElementType.ASSIGN_ADD),
        Pair(ZigTokenType.MINUS_EQUAL, ZigElementType.ASSIGN_SUB),
        Pair(ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL, ZigElementType.ASSIGN_SHL),
        Pair(ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL, ZigElementType.ASSIGN_SHL_SAT),
        Pair(ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL, ZigElementType.ASSIGN_SHR),
        Pair(ZigTokenType.AMPERSAND_EQUAL, ZigElementType.ASSIGN_BIT_AND),
        Pair(ZigTokenType.CARET_EQUAL, ZigElementType.ASSIGN_BIT_XOR),
        Pair(ZigTokenType.PIPE_EQUAL, ZigElementType.ASSIGN_BIT_OR),
        Pair(ZigTokenType.ASTERISK_PERCENT_EQUAL, ZigElementType.ASSIGN_MUL_WRAP),
        Pair(ZigTokenType.PLUS_PERCENT_EQUAL, ZigElementType.ASSIGN_ADD_WRAP),
        Pair(ZigTokenType.MINUS_PERCENT_EQUAL, ZigElementType.ASSIGN_SUB_WRAP),
        Pair(ZigTokenType.ASTERISK_PIPE_EQUAL, ZigElementType.ASSIGN_MUL_SAT),
        Pair(ZigTokenType.PLUS_PIPE_EQUAL, ZigElementType.ASSIGN_ADD_SAT),
        Pair(ZigTokenType.MINUS_PIPE_EQUAL, ZigElementType.ASSIGN_SUB_SAT),
        Pair(ZigTokenType.EQUAL, ZigElementType.ASSIGN),
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
        if (builder.tokenType === ZigTokenType.COMMA) {
            return finishAssignDestructureExpr(builder, lhs)
        }
        val assignType = assignTable[builder.tokenType] ?: return lhs
        val assign = lhs.precede()
        builder.advanceLexer()
        tryExpr(builder)
        assign.done(assignType)
        return assign
    }

    private fun finishAssignDestructureExpr(builder: PsiBuilder, lhs: PsiBuilder.Marker): PsiBuilder.Marker {
        val assign = lhs.precede()
        while (eatToken(builder, ZigTokenType.COMMA)) {
            tryExpr(builder)
        }
        expectToken(builder, ZigTokenType.EQUAL)
        tryExpr(builder)
        assign.done(ZigElementType.ASSIGN_DESTRUCTURE)
        return assign
    }

    private fun parseLoopStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        val statement = parseForStatement(builder) ?: parseWhileStatement(builder)
        if (statement != null) {
            return statement
        }
        if (eatToken(builder, ZigTokenType.KEYWORD_INLINE)) {
            // TODO: mark error
        }
        return null
    }

    private fun parseSwitchExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val marker = builder.mark()
        eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON)
        if (!eatToken(builder, ZigTokenType.KEYWORD_SWITCH)) {
            marker.rollbackTo()
            return null
        }
        trySwitchSuffix(builder)
        marker.done(ZigElementType.SWITCH)
        return marker
    }

    private fun assertSwitchExpr(builder: PsiBuilder): PsiBuilder.Marker {
        val marker = builder.mark()
        eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON)
        assert(builder.tokenType === ZigTokenType.KEYWORD_SWITCH)
        builder.advanceLexer()
        trySwitchSuffix(builder)
        marker.done(ZigElementType.SWITCH)
        return marker
    }

    private fun trySwitchSuffix(builder: PsiBuilder) {
        if (parseCondition(builder) == null) {
            // TODO: mark error
        }
        if (!expectToken(builder, ZigTokenType.L_BRACE)) {
            return
        }
        parseEnclosed(builder, ZigTokenType.R_BRACE) {
            parseSwitchProng(builder)
        }
        expectToken(builder, ZigTokenType.R_BRACE)
    }

    private fun parseSwitchProng(builder: PsiBuilder): PsiBuilder.Marker? {
        val marker = builder.mark()
        eatToken(builder, ZigTokenType.KEYWORD_INLINE)
        if (!eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            if (parseSwitchItem(builder) == null) {
                marker.rollbackTo()
                return null
            }
            while (eatToken(builder, ZigTokenType.COMMA)) {
                if (parseSwitchItem(builder) == null) {
                    break
                }
            }
        }
        expectToken(builder, ZigTokenType.EQUAL_ANGLE_BRACKET_RIGHT)
        parsePtrIndexPayload(builder, ZigElementType.SWITCH_PAYLOAD)
        trySingleAssignExpr(builder)
        marker.done(ZigElementType.SWITCH_PRONG)
        if (!eatToken(builder, ZigTokenType.COMMA) && builder.tokenType !== ZigTokenType.R_BRACE) {
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
        if (eatToken(builder, ZigTokenType.ELLIPSIS3)) {
            tryExpr(builder)
        }
        marker.done(ZigElementType.SWITCH_ITEM)
        return marker
    }

    private fun parsePayload(builder: PsiBuilder, type: IElementType): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.PIPE) {
            return null
        }
        val payload = builder.mark()
        builder.advanceLexer()
        val entry = builder.mark()
        expectToken(builder, ZigTokenType.IDENTIFIER)
        entry.done(ZigElementType.PAYLOAD_ITEM)
        expectToken(builder, ZigTokenType.PIPE)
        payload.done(type)
        return payload
    }

    private fun parsePtrPayload(
        builder: PsiBuilder,
        type: IElementType,
        includeIndex: Boolean = false
    ): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.PIPE) {
            return null
        }
        val payload = builder.mark()
        builder.advanceLexer()
        val entry = builder.mark()
        eatToken(builder, ZigTokenType.ASTERISK)
        expectToken(builder, ZigTokenType.IDENTIFIER)
        entry.done(ZigElementType.PAYLOAD_ITEM)
        if (includeIndex) {
            if (eatToken(builder, ZigTokenType.COMMA)) {
                val indexEntry = builder.mark()
                expectToken(builder, ZigTokenType.IDENTIFIER)
                indexEntry.done(ZigElementType.PAYLOAD_ITEM)
            }
        }
        expectToken(builder, ZigTokenType.PIPE)
        payload.done(type)
        return payload
    }

    private fun parsePtrIndexPayload(builder: PsiBuilder, type: IElementType): PsiBuilder.Marker? {
        return parsePtrPayload(builder, type, true)
    }

    private fun parseGroupedExpression(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        var expr: PsiBuilder.Marker? = null
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
            if (expr == null) {
                expr = parseExpr(builder)
                expr
            } else {
                null
            }
        }
        if (expr == null) {
            // TODO: mark error
        }
        expectToken(builder, ZigTokenType.R_PAREN)
        marker.done(ZigElementType.GROUPED_EXPRESSION)
        return marker
    }

    private fun parseFor(builder: PsiBuilder, bodyParseFn: (PsiBuilder) -> PsiBuilder.Marker?): PsiBuilder.Marker? {
        val forMarker = forPrefix(builder) ?: return null
        val thenMarker = builder.mark()
        if (bodyParseFn(builder) == null) {
            // TODO: mark error
        }
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            val elseMarker = builder.mark()
            if (bodyParseFn(builder) == null) {
                // TODO: mark error
            }
            elseMarker.done(ZigElementType.ELSE)
        }
        forMarker.done(ZigElementType.FOR)
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
            if (eatToken(builder, ZigTokenType.SEMICOLON)) {
                thenMarker.done(ZigElementType.THEN)
                forMarker.done(ZigElementType.FOR)
                return forMarker
            }
            elseRequired = true
        }
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            val elseMarker = builder.mark()
            tryStatement(builder, false)
            elseMarker.done(ZigElementType.ELSE)
        } else if (elseRequired) {
            // TODO: mark error
        }
        forMarker.done(ZigElementType.FOR)
        return forMarker
    }

    private fun forPrefix(builder: PsiBuilder): PsiBuilder.Marker? {
        val forMarker = builder.mark()
        eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON)
        eatToken(builder, ZigTokenType.KEYWORD_INLINE)
        if (!eatToken(builder, ZigTokenType.KEYWORD_FOR)) {
            forMarker.rollbackTo()
            return null
        }
        parseForInputs(builder)
        if (builder.tokenType !== ZigTokenType.PIPE) {
            // TODO: mark error
            return forMarker
        }
        val payload = builder.mark()
        builder.advanceLexer()
        while (!builder.eof()) {
            val entry = builder.mark()
            eatToken(builder, ZigTokenType.ASTERISK)
            if (eatToken(builder, ZigTokenType.IDENTIFIER)) {
                entry.done(ZigElementType.PAYLOAD_ITEM)
            } else {
                entry.drop()
                // TODO: mark error
            }
            if (!eatToken(builder, ZigTokenType.COMMA)) {
                break
            }
        }
        expectToken(builder, ZigTokenType.PIPE)
        payload.done(ZigElementType.FOR_PAYLOAD)
        return forMarker
    }

    private fun parseForInputs(builder: PsiBuilder) {
        if (!expectToken(builder, ZigTokenType.L_PAREN)) {
            return
        }
        var hasInput = false
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
            val input = parseForInput(builder)
            if (input != null) {
                hasInput = true
            }
            input
        }
        if (!hasInput) {
            // TODO: mark error
        }
        expectToken(builder, ZigTokenType.R_PAREN)
    }

    private fun parseForInput(builder: PsiBuilder): PsiBuilder.Marker? {
        val input = builder.mark()
        if (parseExpr(builder) == null) {
            input.rollbackTo()
            return null
        }
        if (eatToken(builder, ZigTokenType.ELLIPSIS2)) {
            tryExpr(builder)
        }
        input.done(ZigElementType.FOR_INPUT)
        if (!eatToken(builder, ZigTokenType.COMMA) && builder.tokenType !== ZigTokenType.R_PAREN) {
            // TODO: mark error
        }
        return input
    }

    private fun parseWhile(builder: PsiBuilder, bodyParseFn: (PsiBuilder) -> PsiBuilder.Marker?): PsiBuilder.Marker? {
        val whileMarker = whilePrefix(builder) ?: return null
        val thenMarker = builder.mark()
        if (bodyParseFn(builder) == null) {
            // TODO: mark error
        }
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            parsePayload(builder, ZigElementType.ELSE_PAYLOAD)
            val elseMarker = builder.mark()
            if (bodyParseFn(builder) == null) {
                // TODO: mark error
            }
            elseMarker.done(ZigElementType.ELSE)
        }
        whileMarker.done(ZigElementType.WHILE)
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
            if (eatToken(builder, ZigTokenType.SEMICOLON)) {
                thenMarker.done(ZigElementType.THEN)
                whileMarker.done(ZigElementType.WHILE)
                return whileMarker
            }
            elseRequired = true
        }
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            parsePayload(builder, ZigElementType.THEN_PAYLOAD)
            val elseMarker = builder.mark()
            tryStatement(builder, false)
            elseMarker.done(ZigElementType.ELSE)
        } else if (elseRequired) {
            // TODO: mark error
        }
        whileMarker.done(ZigElementType.WHILE)
        return whileMarker
    }

    private fun whilePrefix(builder: PsiBuilder): PsiBuilder.Marker? {
        val whileMarker = builder.mark()
        eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON)
        eatToken(builder, ZigTokenType.KEYWORD_INLINE)
        if (!eatToken(builder, ZigTokenType.KEYWORD_WHILE)) {
            whileMarker.rollbackTo()
            return null
        }
        if (parseCondition(builder) == null) {
            // TODO: mark error
        }
        parsePtrPayload(builder, ZigElementType.THEN_PAYLOAD)
        if (eatToken(builder, ZigTokenType.COLON) && parseWhileContinueExpr(builder) == null) {
            // TODO: mark error
        }
        return whileMarker
    }

    private fun parseWhileContinueExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        var expr: PsiBuilder.Marker? = null
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
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
        expectToken(builder, ZigTokenType.R_PAREN)
        marker.done(ZigElementType.WHILE_CONTINUE_EXPR)
        return marker
    }

    private fun assertAsmExpr(builder: PsiBuilder): PsiBuilder.Marker {
        assert(builder.tokenType === ZigTokenType.KEYWORD_ASM)
        val marker = builder.mark()
        builder.advanceLexer()
        eatToken(builder, ZigTokenType.KEYWORD_VOLATILE)
        if (expectToken(builder, ZigTokenType.L_PAREN)) {
            var expr: PsiBuilder.Marker? = null
            var outputList: PsiBuilder.Marker? = null
            var inputList: PsiBuilder.Marker? = null
            var clobbers: PsiBuilder.Marker? = null
            parseEnclosed(builder, ZigTokenType.R_PAREN) {
                if (expr == null) {
                    expr = parseExpr(builder)
                    expr
                } else if (outputList == null) {
                    outputList = parseAsmIOList(builder, this::parseAsmOutputItem)
                    outputList
                } else if (inputList == null) {
                    inputList = parseAsmIOList(builder, this::parseAsmInputItem)
                    inputList
                } else if (clobbers == null) {
                    clobbers = parseClobbers(builder)
                    clobbers
                } else {
                    null
                }
            }
            if (expr == null) {
                // TODO: mark error
            }
            expectToken(builder, ZigTokenType.R_PAREN)
        }
        marker.done(ZigElementType.ASM)
        return marker
    }

    private fun parseAsmIOList(builder: PsiBuilder, parseItem: (PsiBuilder) -> PsiBuilder.Marker?): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.COLON) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        while (!builder.eof()) {
            if (parseItem(builder) == null) {
                break
            }
            if (!eatToken(builder, ZigTokenType.COMMA)
                && builder.tokenType !== ZigTokenType.COLON
                && builder.tokenType !== ZigTokenType.R_PAREN) {
                // TODO: mark error
            }
        }
        marker.drop()
        return marker
    }

    private fun parseAsmOutputItem(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.L_BRACKET) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        var identifier: PsiBuilder.Marker? = null
        parseEnclosed(builder, ZigTokenType.R_BRACKET) {
            if (identifier == null) {
                if (builder.tokenType === ZigTokenType.IDENTIFIER) {
                    identifier = builder.mark()
                    builder.advanceLexer()
                    identifier.drop()
                }
                identifier
            } else {
                null
            }
        }
        if (identifier == null) {
            // TODO: mark error
        }
        expectToken(builder, ZigTokenType.R_BRACKET)
        expectToken(builder, ZigTokenType.STRING_LITERAL)
        if (expectToken(builder, ZigTokenType.L_PAREN)) {
            var expr: PsiBuilder.Marker? = null
            parseEnclosed(builder, ZigTokenType.R_PAREN) {
                if (expr == null) {
                    when (builder.tokenType) {
                        ZigTokenType.ARROW -> {
                            expr = builder.mark()
                            builder.advanceLexer()
                            tryTypeExpr(builder)
                            expr!!.drop()
                        }
                        ZigTokenType.IDENTIFIER -> {
                            expr = builder.mark()
                            builder.advanceLexer()
                            expr.done(ZigElementType.IDENTIFIER)
                        }
                    }
                    expr
                } else {
                    null
                }
            }
            if (expr == null) {
                // TODO: mark error
            }
            expectToken(builder, ZigTokenType.R_PAREN)
        }
        marker.done(ZigElementType.ASM_OUTPUT_ITEM)
        return marker
    }

    private fun parseAsmInputItem(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.L_BRACKET) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        var identifier: PsiBuilder.Marker? = null
        parseEnclosed(builder, ZigTokenType.R_BRACKET) {
            if (identifier == null) {
                if (builder.tokenType === ZigTokenType.IDENTIFIER) {
                    identifier = builder.mark()
                    builder.advanceLexer()
                    identifier.drop()
                }
                identifier
            } else {
                null
            }
        }
        if (identifier == null) {
            // TODO: mark error
        }
        expectToken(builder, ZigTokenType.R_BRACKET)
        expectToken(builder, ZigTokenType.STRING_LITERAL)
        if (expectToken(builder, ZigTokenType.L_PAREN)) {
            var expr: PsiBuilder.Marker? = null
            parseEnclosed(builder, ZigTokenType.R_PAREN) {
                if (expr == null) {
                    expr = parseExpr(builder)
                    expr
                } else {
                    null
                }
            }
            if (expr == null) {
                // TODO: mark error
            }
            expectToken(builder, ZigTokenType.R_PAREN)
        }
        marker.done(ZigElementType.ASM_INPUT_ITEM)
        return marker
    }

    private fun parseClobbers(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.COLON) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        tryExpr(builder)
        marker.done(ZigElementType.ASM_CLOBBERS)
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
                ZigTokenType.L_BRACE -> braces++
                ZigTokenType.R_BRACE -> braces--
                ZigTokenType.L_BRACKET -> brackets++
                ZigTokenType.R_BRACKET -> brackets--
                ZigTokenType.L_PAREN -> parens++
                ZigTokenType.R_PAREN -> parens--
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
        if (expectToken(builder, ZigTokenType.L_PAREN)) {
            var expr: PsiBuilder.Marker? = null
            parseEnclosed(builder, ZigTokenType.R_PAREN) {
                if (expr == null) {
                    expr = parseExpr(builder)
                    expr
                } else {
                    null
                }
            }
            if (expr == null) {
                // TODO: mark error
            }
            expectToken(builder, ZigTokenType.R_PAREN)
        }
        marker.done(type)
        return marker
    }

    private fun parseEnclosed(
        builder: PsiBuilder,
        right: IElementType?,
        type: EnclosureType = EnclosureType.OTHER,
        parseMember: () -> PsiBuilder.Marker?,
    ) {
        enclosureStack.addLast(Enclosure(type, right))
        while (!builder.eof()) {
            if (builder.tokenType === right) {
                break
            }
            if (enclosureStack.any { builder.tokenType === it.right}) {
                break
            }
            if (parseMember() != null) {
                continue
            }
            val type = builder.tokenType
            with(builder.mark()) {
                builder.advanceLexer()
                error("Unexpected token")
            }
            when (type) {
                ZigTokenType.L_BRACE -> {
                    parseEnclosed(builder, ZigTokenType.R_BRACE) { null }
                    if (builder.tokenType === ZigTokenType.R_BRACE) {
                        val invalid = builder.mark()
                        builder.advanceLexer()
                        invalid.error("Unexpected token")
                    }
                }
                ZigTokenType.L_BRACKET -> {
                    parseEnclosed(builder, ZigTokenType.R_BRACKET) { null }
                    if (builder.tokenType === ZigTokenType.R_BRACKET) {
                        with(builder.mark()) {
                            builder.advanceLexer()
                            error("Unexpected token")
                        }
                    }
                }
                ZigTokenType.L_PAREN -> {
                    parseEnclosed(builder, ZigTokenType.R_PAREN) { null }
                    if (builder.tokenType === ZigTokenType.R_PAREN) {
                        with(builder.mark()) {
                            builder.advanceLexer()
                            error("Unexpected token")
                        }
                    }
                }
            }
        }
        enclosureStack.removeLast()
    }

    private fun expectToken(builder: PsiBuilder, expectedType: IElementType): Boolean {
        if (eatToken(builder, expectedType)) {
            return true
        } else {
            val actual = builder.tokenType?.toString() ?: "EOF"
            builder.mark().error("Expected '$expectedType', found '$actual'")
            return false
        }
    }

    private fun eatToken(builder: PsiBuilder, expectedType: IElementType?): Boolean {
        return eatToken(builder) { it === expectedType }
    }

    private fun eatTokens(builder: PsiBuilder, vararg expectedTypes: IElementType?): Boolean {
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

    private fun eatToken(builder: PsiBuilder, expectedTypes: TokenSet): Boolean {
        return eatToken(builder) { it in expectedTypes }
    }

    private inline fun eatToken(builder: PsiBuilder, expectedType: (IElementType?) -> Boolean): Boolean {
        if (expectedType(builder.tokenType)) {
            builder.advanceLexer()
            return true
        }
        return false
    }
}
