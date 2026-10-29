package io.github.fncontroloption.zj

import com.intellij.lang.*
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

sealed class AbstractZigParserDefinition : ParserDefinition {
    abstract val language: Language
    override fun createLexer(project: Project?) = ZigLexerAdapter()
    override fun getCommentTokens() = ZigTokenSets.COMMENTS
    override fun getStringLiteralElements() = ZigTokenSets.STRINGS
    override fun createParser(project: Project?) = ZigParser()
    override fun createElement(node: ASTNode) = ZigElementType.createPsiElement(node)
}

class ZigParserDefinition : AbstractZigParserDefinition() {
    override val language get() = ZigLanguage
    override fun getFileNodeType() = ZigFileElementType
    override fun createFile(viewProvider: FileViewProvider) = ZigFile(viewProvider, ZigLanguage)
}

class ZonParserDefinition : AbstractZigParserDefinition() {
    override val language get() = ZonLanguage
    override fun getFileNodeType() = ZonFileElementType
    override fun createFile(viewProvider: FileViewProvider) = ZonFile(viewProvider)
}

class ZigBuiltinFnProtoParserDefinition : AbstractZigParserDefinition() {
    override val language get() = ZigBuiltinFnProtoLanguage
    override fun getFileNodeType() = ZigBuiltinFnProtoFileElementType
    override fun createFile(viewProvider: FileViewProvider) = ZigFile(viewProvider, ZigBuiltinFnProtoLanguage)
}

// Based on https://codeberg.org/ziglang/zig/src/tag/0.17.0/lib/std/zig/Parse.zig
/** @see com.intellij.java.syntax.parser.JavaParser */
class ZigParser : PsiParser {
    private val enclosureStack = ArrayDeque<IElementType?>()
    private val containerStack = ArrayDeque<IElementType?>()

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        enclosureStack.clear()
        containerStack.clear()
        when (root) {
            ZigFileElementType -> {
                containerStack.addLast(null)
                val rootMarker = builder.mark()
                parseEnclosed(builder, null) {
                    parseContainerMember(builder) != null
                }
                rootMarker.done(root)
                containerStack.removeLast()
            }
            ZonFileElementType -> {
                val rootMarker = builder.mark()
                tryExpr(builder)
                if (!builder.eof()) {
                    builder.mark().error("Expected EOF")
                    while (!builder.eof()) {
                        builder.advanceLexer()
                    }
                }
                rootMarker.done(root)
            }
            ZigBuiltinFnProtoFileElementType -> {
                containerStack.addLast(null)
                val rootMarker = builder.mark()
                while (parseContainerMember(builder) != null) {
                    // no-op
                }
                assertBuiltinFnProto(builder)
                check(builder.eof())
                rootMarker.done(root)
                containerStack.removeLast()
            }
            is ZigLazyParseableElementType -> when (root) {
                ZigBlockElementType -> parseBlock(builder, allowLabel = true, lazy = false)
            }
            else -> throw IllegalArgumentException()
        }
        return builder.treeBuilt
    }

    private fun parseContainerMember(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseTestDecl(builder)
            ?: parseContainerComptimeBlock(builder)
            ?: parseVarDecl(builder)
            ?: parseFnDecl(builder)
            ?: parseContainerField(builder)
    }

    private fun tryContainerDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val container = builder.mark()
        eatToken(builder, ZigTokenSets.EXTERN_PACKED)
        val containerToken = builder.tokenType
        if (!eatToken(builder, ZigTokenSets.CONTAINERS)) {
            container.error("Expected a struct, enum or union")
            return null
        }
        containerStack.addLast(containerToken!!)
        parseContainerArg(builder, containerToken)
        if (expectToken(builder, ZigTokenType.L_BRACE)) {
            parseEnclosed(builder, ZigTokenType.R_BRACE) {
                parseContainerMember(builder) != null
            }
            expectToken(builder, ZigTokenType.R_BRACE)
        }
        containerStack.removeLast()
        container.done(ZigElementType.CONTAINER_EXPRESSION)
        return container
    }

    private fun parseContainerArg(builder: PsiBuilder, containerToken: IElementType): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.L_PAREN) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        val isInferredEnum =
            containerToken === ZigTokenType.KEYWORD_UNION
                    && builder.tokenType === ZigTokenType.KEYWORD_ENUM
        if (isInferredEnum) {
            builder.advanceLexer()
            if (!eatToken(builder, ZigTokenType.L_PAREN)) {
                expectToken(builder, ZigTokenType.R_PAREN)
                marker.done(ZigElementType.CONTAINER_ARG)
                return marker
            }
        }
        var expr: PsiBuilder.Marker? = null
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
            if (expr == null) {
                expr = parseExpr(builder)
                expr != null
            } else {
                false
            }
        }
        checkExpectedExpr(builder, expr)
        if (isInferredEnum) {
            expectToken(builder, ZigTokenType.R_PAREN)
        }
        expectToken(builder, ZigTokenType.R_PAREN)
        marker.done(ZigElementType.CONTAINER_ARG)
        return marker
    }

    private fun parseBlock(
        builder: PsiBuilder,
        allowLabel: Boolean,
        lazy: Boolean = true,
    ): PsiBuilder.Marker? {
        val prevToken = builder.rawLookup(-1)
        if (prevToken === ZigTokenType.PERIOD) {
            return null
        }

        val marker = builder.mark()
        if (allowLabel) {
            parseLabelDecl(builder)
        }
        if (!eatToken(builder, ZigTokenType.L_BRACE)) {
            marker.rollbackTo()
            return null
        }
        parseEnclosed(builder, ZigTokenType.R_BRACE, lazy) {
            parseStatement(builder, true) != null
        }
        expectToken(builder, ZigTokenType.R_BRACE)
        if (lazy) {
            marker.collapse(ZigBlockElementType)
        } else {
            marker.done(ZigBlockElementType)
        }
        return marker
    }

    private fun parseBlockExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseBlock(builder, allowLabel = true)
    }

    private fun parseBlockExprStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        // If block found, don't require semicolon
        val blockExpr = parseBlockExpr(builder)
        if (blockExpr != null) {
            return blockExpr
        }
        // Otherwise, require semicolon after clause
        val assignClause = parseAssignClause(builder)
        if (assignClause != null) {
            expectToken(builder, ZigTokenType.SEMICOLON)
            return assignClause
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

    private fun assertBuiltinCall(builder: PsiBuilder): PsiBuilder.Marker {
        assert(builder.tokenType === ZigTokenType.BUILTIN)
        val call = builder.mark()
        builder.advanceLexer()
        if (expectToken(builder, ZigTokenType.L_PAREN)) {
            parseEnclosed(builder, ZigTokenType.R_PAREN) {
                parseCallArg(builder) != null
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
        if (expectToken(builder, ZigTokenType.EQUAL)) {
            tryExpr(builder)
        }
        decl.done(ZigElementType.VAR_DECL)
        expectToken(builder, ZigTokenType.SEMICOLON)
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
            tryTypeExpr(builder)
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

    private fun assertBuiltinFnProto(builder: PsiBuilder): PsiBuilder.Marker {
        val marker = builder.mark()
        check(builder.tokenType === ZigTokenType.BUILTIN)
        builder.advanceLexer()
        check(eatToken(builder, ZigTokenType.L_PAREN))
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
            assertBuiltinFnParam(builder)
            true
        }
        check(eatToken(builder, ZigTokenType.R_PAREN))
        if (!eatToken(builder, ZigTokenType.KEYWORD_ANYTYPE)) {
            val returnType = builder.mark()
            check(parseTypeExpr(builder) != null)
            returnType.done(ZigElementType.TYPE)
        }
        marker.done(ZigElementType.BUILTIN_FN_PROTO)
        return marker
    }

    private fun parseFnProto(builder: PsiBuilder, expectName: Boolean): PsiBuilder.Marker? {
        val proto = builder.mark()
        if (!eatToken(builder, ZigTokenType.KEYWORD_FN)) {
            proto.rollbackTo()
            return null
        }
        if (expectName) {
            expectToken(builder, ZigTokenType.IDENTIFIER)
        } else {
            eatToken(builder, ZigTokenType.IDENTIFIER)
        }
        if (expectToken(builder, ZigTokenType.L_PAREN)) {
            parseEnclosed(builder, ZigTokenType.R_PAREN) {
                parseFnParamDecl(builder) != null
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
        val proto = parseFnProto(builder, true)
        if (proto == null) {
            decl.rollbackTo()
            return null
        }
        proto.drop()
        if (builder.tokenType === ZigTokenType.SEMICOLON) {
            decl.done(ZigElementType.FN_DECL)
            builder.advanceLexer()
            return decl
        }
        parseBlock(builder, allowLabel = false)
        decl.done(ZigElementType.FN_DECL)
        return decl
    }

    private fun assertBuiltinFnParam(builder: PsiBuilder): PsiBuilder.Marker {
        val decl = builder.mark()
        if (eatToken(builder, ZigTokenType.ELLIPSIS3)) {
            decl.drop()
            check(builder.tokenType === ZigTokenType.R_PAREN)
            return decl
        }
        eatToken(builder, ZigTokenSets.NOALIAS_COMPTIME)
        check(eatToken(builder, ZigTokenType.IDENTIFIER))
        if (eatToken(builder, ZigTokenType.COLON)
            && !eatToken(builder, ZigTokenType.KEYWORD_ANYTYPE)
        ) {
            val type = builder.mark()
            check(parseTypeExpr(builder) != null)
            type.done(ZigElementType.TYPE)
        }
        decl.done(ZigElementType.FN_PARAM)
        eatToken(builder, ZigTokenType.COMMA)
        return decl
    }

    private fun parseFnParamDecl(builder: PsiBuilder): PsiBuilder.Marker? {
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
            if (hasName) {
                tryTypeExpr(builder)
            } else if (parseTypeExpr(builder) == null) {
                decl.rollbackTo()
                return null
            }
            type.done(ZigElementType.TYPE)
        }
        decl.done(ZigElementType.FN_PARAM)
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
            parseErrorSetMember(builder) != null
        }
        expectToken(builder, ZigTokenType.R_BRACE)
        marker.done(ZigElementType.ERROR_SET)
        return marker
    }

    private fun parseErrorSetMember(builder: PsiBuilder): PsiBuilder.Marker? {
        val field = builder.mark()
        if (!eatToken(builder, ZigTokenType.IDENTIFIER)) {
            field.rollbackTo()
            return null
        }
        field.done(ZigElementType.ERROR_SET_ITEM)
        eatToken(builder, ZigTokenType.COMMA)
        return field
    }

    private fun parseTestDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        if (!eatToken(builder, ZigTokenType.KEYWORD_TEST)) {
            decl.rollbackTo()
            return null
        }
        eatToken(builder, ZigTokenSets.TEST_NAME)
        parseBlock(builder, allowLabel = false)
        decl.done(ZigElementType.TEST_DECL)
        return decl
    }

    private fun parseContainerComptimeBlock(builder: PsiBuilder): PsiBuilder.Marker? {
        val comptime = builder.mark()
        if (!eatToken(builder, ZigTokenType.KEYWORD_COMPTIME)) {
            comptime.rollbackTo()
            return null
        }
        parseBlock(builder, allowLabel = false)
        comptime.done(ZigElementType.CONTAINER_COMPTIME_BLOCK)
        return comptime
    }

    private fun parseContainerField(builder: PsiBuilder): PsiBuilder.Marker? {
        val prevToken = builder.rawLookup(-1)
        if (prevToken in ZigTokenSets.MUT_TOKENS) {
            return null
        }

        val containerType = containerStack.last()
        val field = builder.mark()
        eatToken(builder, ZigTokenType.KEYWORD_COMPTIME)
        if (eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON)) {
            val type = builder.mark()
            tryTypeExpr(builder)
            type.done(ZigElementType.TYPE)
        } else when (containerType) {
            ZigTokenType.KEYWORD_STRUCT -> {
                val typeExpr = parseTypeExpr(builder)
                if (typeExpr == null) {
                    field.rollbackTo()
                    return null
                }
                typeExpr.precede().done(ZigElementType.TYPE)
            }
            ZigTokenType.KEYWORD_ENUM, ZigTokenType.KEYWORD_UNION -> {
                if (!eatToken(builder, ZigTokenType.IDENTIFIER)) {
                    field.rollbackTo()
                    return null
                }
            }
            else -> {
                field.rollbackTo()
                return null
            }
        }
        parseAlign(builder)
        if (eatToken(builder, ZigTokenType.EQUAL)) {
            tryExpr(builder)
        }
        field.done(ZigElementType.CONTAINER_FIELD)
        if (!eatToken(builder, ZigTokenType.COMMA)) {
            val terminator = when (containerType) {
                null -> null
                else -> ZigTokenType.R_BRACE
            }
            if (builder.tokenType !== terminator) {
                builder.mark().error("Expected ',' after field")
            }
        }
        return field
    }

    private enum class TypeLengthState { START, SENTINEL, DONE }

    private fun tryTypeExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = parseTypeExpr(builder)
        if (expr == null) {
            builder.mark().error("Expected type expression")
        }
        return expr
    }

    private fun parseTypeExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        when (builder.tokenType) {
            ZigTokenType.QUESTION_MARK -> {
                val expr = builder.mark()
                builder.advanceLexer()
                tryTypeExpr(builder)
                expr.done(ZigElementType.OPTIONAL_TYPE)
                return expr
            }
            ZigTokenType.KEYWORD_ANYFRAME if builder.lookAhead(1) === ZigTokenType.ARROW -> {
                val expr = builder.mark()
                builder.advanceLexer()
                builder.advanceLexer()
                tryTypeExpr(builder)
                expr.done(ZigElementType.ANYFRAME_TYPE)
                return expr
            }
            ZigTokenType.ASTERISK -> {
                val expr = builder.mark()
                builder.advanceLexer()
                pointerModifiers(builder)?.drop()
                tryTypeExpr(builder)
                expr.done(ZigElementType.POINTER_TYPE)
                return expr
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
                            if (isArray) {
                                length.done(ZigElementType.TYPE_LENGTH)
                            } else {
                                length.drop()
                            }
                            true
                        }

                        TypeLengthState.SENTINEL -> {
                            when (builder.tokenType) {
                                ZigTokenType.COLON -> {
                                    val sentinel = builder.mark()
                                    builder.advanceLexer()
                                    tryExpr(builder)
                                    lengthState = TypeLengthState.DONE
                                    sentinel.done(ZigElementType.TYPE_SENTINEL)
                                    true
                                }

                                else -> false
                            }
                        }

                        TypeLengthState.DONE -> false
                    }
                }
                expectToken(builder, ZigTokenType.R_BRACKET)
                val modifiers = pointerModifiers(builder)
                if (isArray) {
                    modifiers?.error("Pointer modifier not allowed on array child type")
                } else {
                    modifiers?.drop()
                }
                tryTypeExpr(builder)
                if (isArray) {
                    expr.done(ZigElementType.ARRAY_TYPE)
                } else {
                    expr.done(ZigElementType.POINTER_TYPE)
                }
                return expr
            }
            else -> return parseErrorUnionExpr(builder)
        }
    }

    private fun pointerModifiers(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !in ZigTokenSets.POINTER_MODIFIERS) {
            return null
        }
        val marker = builder.mark()
        while (!builder.eof()) {
            when (builder.tokenType) {
                ZigTokenType.KEYWORD_ALIGN -> {
                    // TODO: parse bit range
                    parseAlign(builder)
                }
                ZigTokenType.KEYWORD_ADDRSPACE -> {
                    parseAddrspace(builder)
                }
                ZigTokenType.KEYWORD_ALLOWZERO,
                ZigTokenType.KEYWORD_CONST,
                ZigTokenType.KEYWORD_VOLATILE -> {
                    builder.advanceLexer()
                }
                else -> break
            }
        }
        return marker
    }

    private fun parseErrorUnionExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        var expr = parseSuffixExpr(builder) ?: return null
        if (!eatToken(builder, ZigTokenType.BANG)) {
            return expr
        }
        expr = expr.precede()
        tryTypeExpr(builder)
        expr.done(ZigElementType.ERROR_UNION)
        return expr
    }

    private fun parseSuffixExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        var res = parsePrimaryTypeExpr(builder) ?: return null
        while (!builder.eof()) {
            res = parseSuffixOp(builder, res) ?: break
        }
        return res
    }

    private fun parseCall(builder: PsiBuilder, lhs: PsiBuilder.Marker): PsiBuilder.Marker? {
        if (!eatToken(builder, ZigTokenType.L_PAREN)) {
            return null
        }
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
            parseCallArg(builder) != null
        }
        expectToken(builder, ZigTokenType.R_PAREN)
        val call = lhs.precede()
        call.done(ZigElementType.CALL)
        return call
    }

    private fun parseCallArg(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = parseExpr(builder) ?: return null
        val arg = expr.precede()
        arg.done(ZigElementType.CALL_ARG)
        if (!eatToken(builder, ZigTokenType.COMMA) && builder.tokenType !== ZigTokenType.R_PAREN) {
            builder.mark().error("Expected ',' after argument")
        }
        return arg
    }

    private fun parseSuffixOp(builder: PsiBuilder, lhs: PsiBuilder.Marker): PsiBuilder.Marker? {
        val type = suffixOp(builder)
        if (type != null) {
            val res = lhs.precede()
            res.done(type)
            return res
        }
        return parseCall(builder, lhs)
    }

    private fun suffixOp(builder: PsiBuilder): IElementType? {
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
                        arrayIndex != null
                    } else if (sliceIndexes == null) {
                        if (builder.tokenType === ZigTokenType.ELLIPSIS2) {
                            sliceIndexes = arrayIndex.precede()
                            arrayIndex.drop()
                            builder.advanceLexer()
                            parseExpr(builder)
                            sliceIndexes.done(ZigElementType.SLICE_INDEXES)
                            true
                        } else {
                            false
                        }
                    } else if (sentinel == null) {
                        if (builder.tokenType === ZigTokenType.COLON) {
                            sentinel = builder.mark()
                            builder.advanceLexer()
                            tryExpr(builder)
                            sentinel.done(ZigElementType.SLICE_SENTINEL)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
                checkExpectedExpr(builder, arrayIndex)
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
                        builder.advanceLexer()
                        builder.mark().error("Expected pointer dereference, optional unwrap, or field access")
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
            ZigTokenType.BUILTIN -> return assertBuiltinCall(builder)
            ZigTokenType.KEYWORD_FN -> return parseFnProto(builder, false)
            ZigTokenType.KEYWORD_IF -> return assertIf(builder, this::tryTypeExpr)
            ZigTokenType.KEYWORD_SWITCH -> return assertSwitchExpr(builder)
            in ZigTokenSets.EXTERN_PACKED, in ZigTokenSets.CONTAINERS -> return tryContainerDecl(builder)
            ZigTokenType.KEYWORD_COMPTIME -> {
                val marker = builder.mark()
                builder.advanceLexer()
                tryTypeExpr(builder)
                marker.done(ZigElementType.COMPTIME_EXPRESSION)
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
                                    val marker = builder.mark()
                                    builder.advanceLexer()
                                    builder.advanceLexer()
                                    builder.advanceLexer()
                                    marker.error("Expected 'while' or 'for'")
                                    marker
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
                        val marker = builder.mark()
                        builder.advanceLexer()
                        marker.error("Expected 'while' or 'for'")
                        marker
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
                            parseInitListEntry(builder) != null
                        }
                        expectToken(builder, ZigTokenType.R_BRACE)
                        marker.done(ZigElementType.LIST_INIT)
                        return marker
                    }
                    else -> {
                        val marker = builder.mark()
                        builder.advanceLexer()
                        builder.mark().error("Expected enum literal")
                        marker.done(ZigElementType.ENUM_LITERAL)
                        return marker
                    }
                }
            }
            ZigTokenType.KEYWORD_ERROR -> {
                val decl = parseErrorSetDecl(builder)
                if (decl != null) {
                    return decl
                }
                val marker = builder.mark()
                builder.advanceLexer()
                expectToken(builder, ZigTokenType.PERIOD)
                expectToken(builder, ZigTokenType.IDENTIFIER)
                marker.done(ZigElementType.ERROR_VALUE)
                return marker
            }
            ZigTokenType.L_PAREN -> return parseGroupedExpression(builder)
            else -> return null
        }
    }

    private fun tryExpr(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = parseExpr(builder)
        checkExpectedExpr(builder, expr)
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
                builder.error("Comparison operators cannot be chained")
            }
            builder.advanceLexer()
            if (operToken === ZigTokenType.KEYWORD_CATCH) {
                parsePayload(builder, ZigElementType.CATCH_PAYLOAD)
            }
            val rhs = parseExprPrecedence(builder, info.prec + 1)
            checkExpectedExpr(builder, rhs)
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
            builder.mark().error("Expected prefix expression")
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
            ZigTokenType.KEYWORD_IF -> return assertIf(builder, this::tryExpr)
            ZigTokenType.KEYWORD_BREAK -> {
                val expr = builder.mark()
                builder.advanceLexer()
                parseLabelRef(builder)
                parseExpr(builder)
                expr.done(ZigElementType.BREAK)
                return expr
            }
            ZigTokenType.KEYWORD_CONTINUE -> {
                val expr = builder.mark()
                builder.advanceLexer()
                parseLabelRef(builder)
                parseExpr(builder)
                expr.done(ZigElementType.CONTINUE)
                return expr
            }
            ZigTokenType.KEYWORD_COMPTIME -> {
                val expr = builder.mark()
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementType.COMPTIME_EXPRESSION)
                return expr
            }
            ZigTokenType.KEYWORD_NOSUSPEND -> {
                val expr = builder.mark()
                builder.advanceLexer()
                tryExpr(builder)
                expr.done(ZigElementType.NOSUSPEND_EXPRESSION)
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
                                    val marker = builder.mark()
                                    builder.advanceLexer()
                                    builder.advanceLexer()
                                    builder.advanceLexer()
                                    marker.error("Expected 'while' or 'for'")
                                    marker
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
                        val marker = builder.mark()
                        builder.advanceLexer()
                        marker.error("Expected 'while' or 'for'")
                        marker
                    }
                }
            }
            ZigTokenType.KEYWORD_FOR -> return parseFor(builder, this::tryExpr)
            ZigTokenType.KEYWORD_WHILE -> return parseWhile(builder, this::tryExpr)
            ZigTokenType.L_BRACE -> return parseBlock(builder, allowLabel = false)
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
            parseInitListEntry(builder) != null
        }
        expectToken(builder, ZigTokenType.R_BRACE)
        init.done(ZigElementType.LIST_INIT)
        return init
    }

    private fun parseInitListEntry(builder: PsiBuilder): PsiBuilder.Marker? {
        val entry = builder.mark()
        if (eatTokens(builder, ZigTokenType.PERIOD, ZigTokenType.IDENTIFIER, ZigTokenType.EQUAL)) {
            tryExpr(builder)
        } else if (parseExpr(builder) == null) {
            entry.rollbackTo()
            return null
        }
        entry.done(ZigElementType.LIST_INIT_ITEM)
        if (!eatToken(builder, ZigTokenType.COMMA) && builder.tokenType !== ZigTokenType.R_BRACE) {
            builder.mark().error("Expected ',' after initializer")
        }
        return entry
    }

    private fun tryStatement(builder: PsiBuilder, isBlockLevel: Boolean = false): PsiBuilder.Marker? {
        val statement = parseStatement(builder, isBlockLevel)
        if (statement == null) {
            if (isBlockLevel) {
                builder.mark().error("Expected expression or var decl")
            } else {
                builder.mark().error("Expected expression or assignment")
            }
        }
        return statement
    }

    private fun parseStatement(builder: PsiBuilder, isBlockLevel: Boolean): PsiBuilder.Marker? {
        when (builder.tokenType) {
            ZigTokenType.KEYWORD_COMPTIME -> {
                val marker = builder.mark()
                builder.advanceLexer()
                if (parseBlockExpr(builder) != null) {
                    marker.done(ZigElementType.COMPTIME_STATEMENT)
                    return marker
                }
                if (isBlockLevel) {
                    tryVarAssignClause(builder)
                } else {
                    tryAssignClause(builder)
                }
                expectToken(builder, ZigTokenType.SEMICOLON)
                marker.done(ZigElementType.COMPTIME_STATEMENT)
                return marker
            }
            ZigTokenType.KEYWORD_NOSUSPEND -> {
                val marker = builder.mark()
                builder.advanceLexer()
                tryBlockExprStatement(builder)
                marker.done(ZigElementType.NOSUSPEND_STATEMENT)
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
                // TODO: report error if C-style container
            }
        }
        val labeledStatement = parseLabeledStatement(builder)
        if (labeledStatement != null) {
            return labeledStatement
        }
        val statement = builder.mark()
        val clause = if (isBlockLevel) {
            parseVarAssignClause(builder)
        } else {
            parseAssignClause(builder)
        }
        if (clause == null) {
            statement.drop()
            return null
        }
        expectToken(builder, ZigTokenType.SEMICOLON)
        statement.done(ZigElementType.CLAUSE_STATEMENT)
        return statement
    }

    private fun assertIfStatement(builder: PsiBuilder): PsiBuilder.Marker {
        assert(builder.tokenType === ZigTokenType.KEYWORD_IF)
        val ifMarker = builder.mark()
        builder.advanceLexer()
        tryCondition(builder)
        parsePtrPayload(builder, ZigElementType.THEN_PAYLOAD)
        var elseRequired = false
        val thenMarker = builder.mark()
        if (parseBlockExpr(builder) == null) {
            if (parseAssignClause(builder) == null) {
                builder.mark().error("Expected block or assignment")
            }
            if (builder.tokenType === ZigTokenType.SEMICOLON) {
                thenMarker.done(ZigElementType.THEN)
                ifMarker.done(ZigElementType.IF)
                builder.advanceLexer()
                return ifMarker
            }
            elseRequired = true
        }
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            parsePayload(builder, ZigElementType.ELSE_PAYLOAD)
            val elseMarker = builder.mark()
            tryStatement(builder)
            elseMarker.done(ZigElementType.ELSE)
        } else if (elseRequired) {
            builder.mark().error("Expected ';' or 'else' after statement")
        }
        ifMarker.done(ZigElementType.IF)
        return ifMarker
    }

    private fun parseLabeledStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        val statement = parseBlockExpr(builder) ?: parseLoopStatement(builder) ?: parseSwitchStatement(builder)
        if (statement != null) {
            return statement
        }
        val marker = builder.mark()
        if (eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON)) {
            val subMarker = builder.mark()
            if (parseTypeExpr(builder) != null) {
                val a = parseAlign(builder)
                val b = parseAddrspace(builder)
                val c = parseLinksection(builder)
                val d = eatToken(builder, ZigTokenType.EQUAL)
                if (d) {
                    parseExpr(builder)
                }
                if (a != null || b != null || c != null || d) {
                    subMarker.drop()
                    marker.error("Expected 'var' or 'const' before variable declaration")
                    eatToken(builder, ZigTokenType.SEMICOLON)
                    return marker
                }
            }
            subMarker.rollbackTo()
            marker.error("Expected 'while', 'for', 'inline', or '{'")
            return marker
        }
        marker.drop()
        return null
    }

    private fun tryVarAssignClause(builder: PsiBuilder): PsiBuilder.Marker? {
        val clause = parseVarAssignClause(builder)
        if (clause == null) {
            builder.mark().error("Expected expression or var decl")
        }
        return null
    }

    private fun parseVarAssignClause(builder: PsiBuilder): PsiBuilder.Marker? {
        val clause = builder.mark()
        val scratch = mutableListOf<PsiBuilder.Marker>()

        while (!builder.eof()) {
            val variable = parseVarDeclProto(builder) ?: parseExpr(builder)
            if (variable == null) {
                clause.rollbackTo()
                return null
            }
            scratch.add(variable)
            if (!eatToken(builder, ZigTokenType.COMMA)) {
                break
            }
        }

        assert(scratch.isNotEmpty())

        if (scratch.size > 1 || scratch[0].tokenType === ZigElementType.VAR_PROTO) {
            expectToken(builder, ZigTokenType.EQUAL)
        } else if (!eatToken(builder, ZigTokenType.EQUAL)) {
            clause.drop()
            return finishAssignClause(builder, scratch[0])
        }

        tryExpr(builder)

        if (scratch.size == 1) {
            if (scratch[0].tokenType === ZigElementType.VAR_PROTO) {
                scratch[0].drop()
                clause.done(ZigElementType.VAR_DECL)
            } else {
                clause.done(ZigElementType.ASSIGN)
            }
        } else {
            clause.done(ZigElementType.ASSIGN_DESTRUCTURE)
        }
        return clause
    }

    private fun assertIf(builder: PsiBuilder, tryBody: (PsiBuilder) -> PsiBuilder.Marker?): PsiBuilder.Marker {
        assert(builder.tokenType === ZigTokenType.KEYWORD_IF)
        val marker = builder.mark()
        builder.advanceLexer()
        tryCondition(builder)
        parsePtrPayload(builder, ZigElementType.THEN_PAYLOAD)
        val thenMarker = builder.mark()
        tryBody(builder)
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            parsePayload(builder, ZigElementType.ELSE_PAYLOAD)
            val elseMarker = builder.mark()
            tryBody(builder)
            elseMarker.done(ZigElementType.ELSE)
        }
        marker.done(ZigElementType.IF)
        return marker
    }

    private fun tryCondition(builder: PsiBuilder): PsiBuilder.Marker {
        val marker = builder.mark()
        if (!expectToken(builder, ZigTokenType.L_PAREN)) {
            marker.done(ZigElementType.CONDITION)
            return marker
        }
        var expr: PsiBuilder.Marker? = null
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
            if (expr == null) {
                expr = parseExpr(builder)
                expr != null
            } else {
                false
            }
        }
        checkExpectedExpr(builder, expr)
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

    private fun parseAssignClause(builder: PsiBuilder): PsiBuilder.Marker? {
        val expr = parseExpr(builder) ?: return null
        return finishAssignClause(builder, expr)
    }

    private fun parseSingleAssignClause(builder: PsiBuilder): PsiBuilder.Marker? {
        val lhs = parseExpr(builder) ?: return null
        val assignType = assignTable[builder.tokenType] ?: return lhs
        val assign = lhs.precede()
        builder.advanceLexer()
        tryExpr(builder)
        assign.done(assignType)
        return assign
    }

    private fun tryAssignClause(builder: PsiBuilder): PsiBuilder.Marker? {
        val clause = parseAssignClause(builder)
        if (clause == null) {
            builder.mark().error("Expected expression or assignment")
        }
        return clause
    }

    private fun finishAssignClause(builder: PsiBuilder, lhs: PsiBuilder.Marker): PsiBuilder.Marker {
        if (builder.tokenType === ZigTokenType.COMMA) {
            return finishAssignDestructure(builder, lhs)
        }
        val assignType = assignTable[builder.tokenType] ?: return lhs
        val assign = lhs.precede()
        builder.advanceLexer()
        tryExpr(builder)
        assign.done(assignType)
        return assign
    }

    private fun finishAssignDestructure(builder: PsiBuilder, lhs: PsiBuilder.Marker): PsiBuilder.Marker {
        val assign = lhs.precede()
        // TODO: use parseUntil
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
        val marker = builder.mark()
        if (eatToken(builder, ZigTokenType.KEYWORD_INLINE)
            || eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON, ZigTokenType.KEYWORD_INLINE)
        ) {
            marker.error("Expected 'while' or 'for'")
            return marker
        }
        marker.drop()
        return null
    }

    private fun parseSwitchStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        val marker = builder.mark()
        parseLabelDecl(builder)
        if (!eatToken(builder, ZigTokenType.KEYWORD_SWITCH)) {
            marker.rollbackTo()
            return null
        }
        trySwitchSuffix(builder)
        marker.done(ZigElementType.SWITCH_STATEMENT)
        return marker
    }

    private fun assertSwitchExpr(builder: PsiBuilder): PsiBuilder.Marker {
        val marker = builder.mark()
        parseLabelDecl(builder)
        assert(builder.tokenType === ZigTokenType.KEYWORD_SWITCH)
        builder.advanceLexer()
        trySwitchSuffix(builder)
        marker.done(ZigElementType.SWITCH_EXPRESSION)
        return marker
    }

    private fun trySwitchSuffix(builder: PsiBuilder) {
        tryCondition(builder)
        if (!expectToken(builder, ZigTokenType.L_BRACE)) {
            return
        }
        parseEnclosed(builder, ZigTokenType.R_BRACE) {
            parseSwitchProng(builder)
            true
        }
        expectToken(builder, ZigTokenType.R_BRACE)
    }

    private fun parseSwitchProng(builder: PsiBuilder): PsiBuilder.Marker {
        val marker = builder.mark()
        eatToken(builder, ZigTokenType.KEYWORD_INLINE)
        if (!eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            parseUntil(builder, ZigTokenType.EQUAL_ANGLE_BRACKET_RIGHT) {
                parseSwitchItem(builder) != null
            }
        }
        expectToken(builder, ZigTokenType.EQUAL_ANGLE_BRACKET_RIGHT)
        parsePtrIndexPayload(builder, ZigElementType.SWITCH_PAYLOAD)
        var clause: PsiBuilder.Marker? = null
        parseUntil(builder, ZigTokenType.COMMA) {
            if (clause == null) {
                clause = parseSingleAssignClause(builder)
                clause != null
            } else {
                false
            }
        }
        if (clause == null) {
            builder.mark().error("Expected expression or assignment")
        }
        marker.done(ZigElementType.SWITCH_PRONG)
        if (!eatToken(builder, ZigTokenType.COMMA) && builder.tokenType !== ZigTokenType.R_BRACE) {
            builder.mark().error("Expected ',' after switch prong")
        }
        return marker
    }

    private fun parseSwitchItem(builder: PsiBuilder): PsiBuilder.Marker? {
        val marker = builder.mark()
        val start = parseExpr(builder)
        if (start == null) {
            marker.rollbackTo()
            return null
        }
        if (eatToken(builder, ZigTokenType.ELLIPSIS3)) {
            tryExpr(builder)
            start.precede().done(ZigElementType.SWITCH_RANGE)
        }
        marker.done(ZigElementType.SWITCH_ITEM)
        if (!eatToken(builder, ZigTokenType.COMMA) && builder.tokenType !== ZigTokenType.EQUAL_ANGLE_BRACKET_RIGHT) {
            builder.mark().error("Expected ',' after switch item")
        }
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
                expr != null
            } else {
                false
            }
        }
        checkExpectedExpr(builder, expr)
        expectToken(builder, ZigTokenType.R_PAREN)
        marker.done(ZigElementType.GROUPED_EXPRESSION)
        return marker
    }

    private fun parseFor(builder: PsiBuilder, tryBody: (PsiBuilder) -> PsiBuilder.Marker?): PsiBuilder.Marker? {
        val forMarker = forPrefix(builder) ?: return null
        val thenMarker = builder.mark()
        tryBody(builder)
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            val elseMarker = builder.mark()
            tryBody(builder)
            elseMarker.done(ZigElementType.ELSE)
        }
        forMarker.done(ZigElementType.FOR)
        return forMarker
    }

    private fun parseForStatement(builder: PsiBuilder): PsiBuilder.Marker? {
        val forMarker = forPrefix(builder) ?: return null
        var elseRequired = false
        val thenMarker = builder.mark()
        if (parseBlockExpr(builder) == null) {
            if (parseAssignClause(builder) == null) {
                builder.mark().error("Expected block or assignment")
            }
            if (builder.tokenType === ZigTokenType.SEMICOLON) {
                thenMarker.done(ZigElementType.THEN)
                forMarker.done(ZigElementType.FOR)
                builder.advanceLexer()
                return forMarker
            }
            elseRequired = true
        }
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            val elseMarker = builder.mark()
            tryStatement(builder)
            elseMarker.done(ZigElementType.ELSE)
        } else if (elseRequired) {
            builder.mark().error("Expected ';' or 'else' after statement")
        }
        forMarker.done(ZigElementType.FOR)
        return forMarker
    }

    private fun forPrefix(builder: PsiBuilder): PsiBuilder.Marker? {
        val forMarker = builder.mark()
        parseLabelDecl(builder)
        eatToken(builder, ZigTokenType.KEYWORD_INLINE)
        if (!eatToken(builder, ZigTokenType.KEYWORD_FOR)) {
            forMarker.rollbackTo()
            return null
        }
        tryForInputs(builder)
        if (builder.tokenType !== ZigTokenType.PIPE) {
            builder.mark().error("Expected loop payload")
            return forMarker
        }
        val payload = builder.mark()
        builder.advanceLexer()
        while (!builder.eof()) {
            val item = builder.mark()
            val asterisk = eatToken(builder, ZigTokenType.ASTERISK)
            val identifier = expectToken(builder, ZigTokenType.IDENTIFIER)
            if (!asterisk && !identifier) {
                item.drop()
                break
            }
            item.done(ZigElementType.PAYLOAD_ITEM)
            when (builder.tokenType) {
                ZigTokenType.COMMA -> builder.advanceLexer()
                ZigTokenType.PIPE -> break
                else -> builder.mark().error("Expected ',' after for capture")
            }
            if (builder.tokenType === ZigTokenType.PIPE) {
                break
            }
        }
        expectToken(builder, ZigTokenType.PIPE)
        payload.done(ZigElementType.FOR_PAYLOAD)
        return forMarker
    }

    private fun tryForInputs(builder: PsiBuilder) {
        if (!expectToken(builder, ZigTokenType.L_PAREN)) {
            return
        }
        var lastInput: PsiBuilder.Marker? = null
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
            val input = parseForInput(builder)
            if (input != null) {
                lastInput = input
                true
            } else {
                false
            }
        }
        checkExpectedExpr(builder, lastInput)
        expectToken(builder, ZigTokenType.R_PAREN)
    }

    private fun parseForInput(builder: PsiBuilder): PsiBuilder.Marker? {
        val input = builder.mark()
        val start = parseExpr(builder)
        if (start == null) {
            input.rollbackTo()
            return null
        }
        if (eatToken(builder, ZigTokenType.ELLIPSIS2)) {
            parseExpr(builder)
            start.precede().done(ZigElementType.FOR_RANGE)
        }
        input.done(ZigElementType.FOR_INPUT)
        if (!eatToken(builder, ZigTokenType.COMMA) && builder.tokenType !== ZigTokenType.R_PAREN) {
            builder.mark().error("Expected ',' after for operand")
        }
        return input
    }

    private fun parseWhile(builder: PsiBuilder, tryBody: (PsiBuilder) -> PsiBuilder.Marker?): PsiBuilder.Marker? {
        val whileMarker = whilePrefix(builder) ?: return null
        val thenMarker = builder.mark()
        tryBody(builder)
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            parsePayload(builder, ZigElementType.ELSE_PAYLOAD)
            val elseMarker = builder.mark()
            tryBody(builder)
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
            if (parseAssignClause(builder) == null) {
                builder.mark().error("Expected block or assignment")
            }
            if (builder.tokenType === ZigTokenType.SEMICOLON) {
                thenMarker.done(ZigElementType.THEN)
                whileMarker.done(ZigElementType.WHILE)
                builder.advanceLexer()
                return whileMarker
            }
            elseRequired = true
        }
        thenMarker.done(ZigElementType.THEN)
        if (eatToken(builder, ZigTokenType.KEYWORD_ELSE)) {
            parsePayload(builder, ZigElementType.ELSE_PAYLOAD)
            val elseMarker = builder.mark()
            tryStatement(builder)
            elseMarker.done(ZigElementType.ELSE)
        } else if (elseRequired) {
            builder.mark().error("Expected ';' or 'else' after statement")
        }
        whileMarker.done(ZigElementType.WHILE)
        return whileMarker
    }

    private fun whilePrefix(builder: PsiBuilder): PsiBuilder.Marker? {
        val whileMarker = builder.mark()
        parseLabelDecl(builder)
        eatToken(builder, ZigTokenType.KEYWORD_INLINE)
        if (!eatToken(builder, ZigTokenType.KEYWORD_WHILE)) {
            whileMarker.rollbackTo()
            return null
        }
        tryCondition(builder)
        parsePtrPayload(builder, ZigElementType.THEN_PAYLOAD)
        parseWhileContinueClause(builder)
        return whileMarker
    }

    private fun parseWhileContinueClause(builder: PsiBuilder): PsiBuilder.Marker? {
        if (!eatToken(builder, ZigTokenType.COLON)) {
            return null
        }
        val marker = builder.mark()
        if (!expectToken(builder, ZigTokenType.L_PAREN)) {
            marker.drop()
            return null
        }
        var clause: PsiBuilder.Marker? = null
        parseEnclosed(builder, ZigTokenType.R_PAREN) {
            if (clause == null) {
                clause = parseAssignClause(builder)
                clause != null
            } else {
                false
            }
        }
        if (clause == null) {
            builder.mark().error("Expected expression or assignment")
        }
        expectToken(builder, ZigTokenType.R_PAREN)
        marker.done(ZigElementType.WHILE_CONTINUE_CLAUSE)
        return marker
    }

    private fun assertAsmExpr(builder: PsiBuilder): PsiBuilder.Marker {
        assert(builder.tokenType === ZigTokenType.KEYWORD_ASM)
        val marker = builder.mark()
        builder.advanceLexer()
        eatToken(builder, ZigTokenType.KEYWORD_VOLATILE)
        if (expectToken(builder, ZigTokenType.L_PAREN)) {
            var expr: PsiBuilder.Marker? = null
            var foundOutputList = false
            var foundInputList = false
            var clobbers: PsiBuilder.Marker? = null
            parseEnclosed(builder, ZigTokenType.R_PAREN) {
                if (expr == null) {
                    expr = parseExpr(builder)
                    expr != null
                } else if (!foundOutputList) {
                    foundOutputList = parseAsmIOList(builder, this::parseAsmOutputItem)
                    foundOutputList
                } else if (!foundInputList) {
                    foundInputList = parseAsmIOList(builder, this::parseAsmInputItem)
                    foundInputList
                } else if (clobbers == null) {
                    clobbers = parseAsmClobbers(builder)
                    clobbers != null
                } else {
                    false
                }
            }
            checkExpectedExpr(builder, expr)
            expectToken(builder, ZigTokenType.R_PAREN)
        }
        marker.done(ZigElementType.ASM)
        return marker
    }

    private fun parseAsmIOList(builder: PsiBuilder, parseItem: (PsiBuilder) -> PsiBuilder.Marker?): Boolean {
        if (builder.tokenType !== ZigTokenType.COLON) {
            return false
        }
        builder.advanceLexer()
        while (!builder.eof()) {
            if (parseItem(builder) == null) {
                break
            }
            if (!eatToken(builder, ZigTokenType.COMMA)
                && builder.tokenType !== ZigTokenType.COLON
                && builder.tokenType !== ZigTokenType.R_PAREN
            ) {
                builder.mark().error("Expected ','")
            }
        }
        return true
    }

    private fun parseAsmOutputItem(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.L_BRACKET) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        var foundIdentifier = false
        parseEnclosed(builder, ZigTokenType.R_BRACKET) {
            if (!foundIdentifier) {
                if (builder.tokenType === ZigTokenType.IDENTIFIER) {
                    foundIdentifier = true
                    builder.advanceLexer()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        if (!foundIdentifier) {
            builder.mark().error("Expected an identifier")
        }
        expectToken(builder, ZigTokenType.R_BRACKET)
        expectToken(builder, ZigTokenType.STRING_LITERAL)
        if (expectToken(builder, ZigTokenType.L_PAREN)) {
            var foundExprOrIdentifier = false
            parseEnclosed(builder, ZigTokenType.R_PAREN) {
                if (!foundExprOrIdentifier) {
                    when (builder.tokenType) {
                        ZigTokenType.ARROW -> {
                            foundExprOrIdentifier = true
                            builder.advanceLexer()
                            tryTypeExpr(builder)
                            true
                        }
                        ZigTokenType.IDENTIFIER -> {
                            foundExprOrIdentifier = true
                            val expr = builder.mark()
                            builder.advanceLexer()
                            expr.done(ZigElementType.IDENTIFIER)
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            if (!foundExprOrIdentifier) {
                builder.mark().error("Expected an identifier")
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
        var foundIdentifier = false
        parseEnclosed(builder, ZigTokenType.R_BRACKET) {
            if (!foundIdentifier) {
                if (builder.tokenType === ZigTokenType.IDENTIFIER) {
                    foundIdentifier = true
                    builder.advanceLexer()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        if (!foundIdentifier) {
            builder.mark().error("Expected an identifier")
        }
        expectToken(builder, ZigTokenType.R_BRACKET)
        expectToken(builder, ZigTokenType.STRING_LITERAL)
        if (expectToken(builder, ZigTokenType.L_PAREN)) {
            var expr: PsiBuilder.Marker? = null
            parseEnclosed(builder, ZigTokenType.R_PAREN) {
                if (expr == null) {
                    expr = parseExpr(builder)
                    expr != null
                } else {
                    false
                }
            }
            checkExpectedExpr(builder, expr)
            expectToken(builder, ZigTokenType.R_PAREN)
        }
        marker.done(ZigElementType.ASM_INPUT_ITEM)
        return marker
    }

    private fun parseAsmClobbers(builder: PsiBuilder): PsiBuilder.Marker? {
        if (builder.tokenType !== ZigTokenType.COLON) {
            return null
        }
        val marker = builder.mark()
        builder.advanceLexer()
        tryExpr(builder)
        marker.done(ZigElementType.ASM_CLOBBERS)
        return marker
    }

    private fun parseLabelDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val marker = builder.mark()
        if (eatTokens(builder, ZigTokenType.IDENTIFIER, ZigTokenType.COLON)) {
            marker.done(ZigElementType.LABEL_DECL)
            return marker
        } else {
            marker.rollbackTo()
            return null
        }
    }

    private fun parseLabelRef(builder: PsiBuilder): PsiBuilder.Marker? {
        val marker = builder.mark()
        if (eatTokens(builder, ZigTokenType.COLON, ZigTokenType.IDENTIFIER)) {
            marker.done(ZigElementType.LABEL_REF)
            return marker
        } else {
            marker.rollbackTo()
            return null
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
                    expr != null
                } else {
                    false
                }
            }
            checkExpectedExpr(builder, expr)
            expectToken(builder, ZigTokenType.R_PAREN)
        }
        marker.done(type)
        return marker
    }

    private fun parseEnclosed(
        builder: PsiBuilder,
        right: IElementType?,
        lazy: Boolean = false,
        parseMember: () -> Boolean,
    ) {
        enclosureStack.addLast(right)
        parseUntil(builder, right, lazy, parseMember)
        enclosureStack.removeLast()
    }

    private fun parseUntil(
        builder: PsiBuilder,
        tokenType: IElementType?,
        lazy: Boolean = false,
        parseMember: () -> Boolean,
    ) {
        while (!builder.eof()) {
            if (builder.tokenType === tokenType) {
                break
            }
            if (enclosureStack.any { builder.tokenType === it }) {
                break
            }
            if (!lazy && parseMember()) {
                continue
            }
            val type = builder.tokenType
            if (lazy) {
                builder.advanceLexer()
            } else {
                val marker = builder.mark()
                builder.advanceLexer()
                marker.error("Unexpected token: ${tokenSymbol(type)}")
            }
            when (type) {
                ZigTokenType.L_BRACE -> {
                    parseEnclosed(builder, ZigTokenType.R_BRACE) { false }
                    if (builder.tokenType === ZigTokenType.R_BRACE) {
                        if (lazy) {
                            builder.advanceLexer()
                        } else {
                            val marker = builder.mark()
                            builder.advanceLexer()
                            marker.error("Unexpected token: }")
                        }
                    }
                }
                ZigTokenType.L_BRACKET -> {
                    parseEnclosed(builder, ZigTokenType.R_BRACKET) { false }
                    if (builder.tokenType === ZigTokenType.R_BRACKET) {
                        if (lazy) {
                            builder.advanceLexer()
                        } else {
                            val marker = builder.mark()
                            builder.advanceLexer()
                            marker.error("Unexpected token: ]")
                        }
                    }
                }
                ZigTokenType.L_PAREN -> {
                    parseEnclosed(builder, ZigTokenType.R_PAREN) { false }
                    if (builder.tokenType === ZigTokenType.R_PAREN) {
                        if (lazy) {
                            builder.advanceLexer()
                        } else {
                            val marker = builder.mark()
                            builder.advanceLexer()
                            marker.error("Unexpected token: )")
                        }
                    }
                }
            }
        }
    }

    private fun checkExpectedExpr(builder: PsiBuilder, marker: PsiBuilder.Marker?) {
        if (marker == null) {
            builder.mark().error("Expected expression")
        }
    }

    private fun expectToken(builder: PsiBuilder, expectedType: IElementType): Boolean {
        if (eatToken(builder, expectedType)) {
            return true
        } else {
            builder.mark().error("Expected '$expectedType', found '${tokenSymbol(builder.tokenType)}'")
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

private fun tokenSymbol(type: IElementType?) = when (type) {
    null -> "EOF"
    TokenType.BAD_CHARACTER -> "bad character"
    else -> type.toString()
}
