package io.github.fncontroloption.zig.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.fncontroloption.zig.psi.ZigTokenSets
import io.github.fncontroloption.zig.psi.ZigTypes

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
    }

    private fun parseContainerDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val container = builder.mark()
        if (!expect(builder, ZigTokenSets.CONTAINERS)) {
            container.drop()
            return null
        }
        parseEnclosed(builder, null, ZigTypes.L_PAREN, ZigTypes.R_PAREN, ZigTypes.CONTAINER_DECL_ARG)
        parseEnclosed(builder, null, ZigTypes.L_BRACE, ZigTypes.R_BRACE, ZigTypes.CONTAINER_DECL_BODY)
        container.done(ZigTypes.CONTAINER_DECL)
        return container
    }

    private fun parseBlock(builder: PsiBuilder): PsiBuilder.Marker? {
        return parseEnclosed(builder, null, ZigTypes.L_BRACE, ZigTypes.R_BRACE, ZigTypes.BLOCK)
    }

    private fun parseBuiltinCall(builder: PsiBuilder): PsiBuilder.Marker? {
        val call = builder.mark()
        if (!expect(builder, ZigTypes.BUILTIN)) {
            call.drop()
            return null
        }
        parseEnclosed(builder, null, ZigTypes.L_PAREN, ZigTypes.R_PAREN, ZigTypes.CALL_ARGS)
        call.done(ZigTypes.BUILTIN_CALL)
        return call
    }

    private fun parseVarDecl(builder: PsiBuilder): PsiBuilder.Marker? {
        val decl = builder.mark()
        if (expect(builder, ZigTypes.KEYWORD_EXTERN)) {
            expect(builder, ZigTypes.STRING_LITERAL)
        } else {
            expect(builder, ZigTypes.KEYWORD_EXPORT)
        }
        expect(builder, ZigTypes.KEYWORD_THREADLOCAL)
        if (!expect(builder, ZigTokenSets.MUT_TOKENS)) {
            decl.rollbackTo()
            return null
        }
        if (!expect(builder, ZigTypes.IDENTIFIER)) {
            decl.rollbackTo()
            return null
        }
        if (expect(builder, ZigTypes.COLON)) {
            // TODO: parse type expressions
            decl.rollbackTo()
            return null
        }
        parseEnclosed(builder, ZigTypes.KEYWORD_ALIGN, ZigTypes.L_PAREN, ZigTypes.R_PAREN, ZigTypes.ALIGN)
        parseEnclosed(builder, ZigTypes.KEYWORD_ADDRSPACE, ZigTypes.L_PAREN, ZigTypes.R_PAREN, ZigTypes.ADDRSPACE)
        parseEnclosed(builder, ZigTypes.KEYWORD_LINKSECTION, ZigTypes.L_PAREN, ZigTypes.R_PAREN, ZigTypes.LINKSECTION)
        if (!expect(builder, ZigTypes.EQUAL)) {
            decl.rollbackTo()
            return null
        }
        parseUntil(builder, ZigTypes.SEMICOLON)
        assert(expect(builder, ZigTypes.SEMICOLON))
        decl.done(ZigTypes.VAR_DECL)
        return decl
    }

    private fun parseUntil(builder: PsiBuilder, terminator: IElementType) {
        var braces = 0
        var brackets = 0
        var parens = 0
        while (!builder.eof()) {
            if (builder.tokenType === terminator && braces == 0 && brackets == 0 && parens == 0) {
                break
            } else if (parse(builder) != null) {
                continue
            } else if (builder.tokenType === ZigTypes.L_BRACE) {
                braces++
            } else if (builder.tokenType === ZigTypes.R_BRACE) {
                braces--
            } else if (builder.tokenType === ZigTypes.L_BRACKET) {
                brackets++
            } else if (builder.tokenType === ZigTypes.R_BRACKET) {
                brackets--
            } else if (builder.tokenType === ZigTypes.L_PAREN) {
                parens++
            } else if (builder.tokenType === ZigTypes.R_PAREN) {
                parens--
            }
            builder.advanceLexer()
        }
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

    private fun expect(builder: PsiBuilder, expectedTypes: TokenSet): Boolean {
        if (builder.tokenType in expectedTypes) {
            builder.advanceLexer()
            return true
        }
        return false
    }
}
