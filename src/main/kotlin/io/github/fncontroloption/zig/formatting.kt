package io.github.fncontroloption.zig

import com.intellij.formatting.ASTBlock
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block as FormattingBlock
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Wrap
import com.intellij.formatting.WrapType
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.fncontroloption.zig.psi2.ZigElementType
import io.github.fncontroloption.zig.psi2.ZigFileElementType
import io.github.fncontroloption.zig.psi2.ZigTokenType
import com.intellij.psi.formatter.common.AbstractBlock as AbstractFormattingBlock

class ZigFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val settings = formattingContext.codeStyleSettings
        val node = formattingContext.node
        val rootBlock = ZigFormattingBlock(node, Indent.getNoneIndent())
        return FormattingModelProvider.createFormattingModelForPsiFile(file, rootBlock, settings)
    }
}

class ZigFormattingBlock(
    node: ASTNode,
    indent: Indent,
    alignment: Alignment? = null,
    wrap: Wrap? = null,
) : AbstractFormattingBlock(node, wrap, alignment) {
    private val myIndent = indent

    override fun buildChildren() = buildList {
        when (node.elementType) {
            ZigElementType.ASM -> {} // TODO

            ZigElementType.THEN, ZigElementType.ELSE -> {
                val hasBlock = childNodeOfType(myNode, ZigElementType.BLOCK) != null
                var child = myNode.firstChildNode
                while (child != null) {
                    when (child.elementType) {
                        TokenType.ERROR_ELEMENT if child.textLength == 0 -> {}

                        TokenType.WHITE_SPACE -> {}

                        else -> {
                            val indent = when (hasBlock) {
                                true -> Indent.getNoneIndent()
                                false -> Indent.getNormalIndent()
                            }
                            addLast(ZigFormattingBlock(child, indent))
                        }
                    }
                    child = child.treeNext
                }
            }

            ZigElementType.CATCH -> {
                var indent = Indent.getNoneIndent()
                var child = myNode.firstChildNode
                while (child != null) {
                    when (child.elementType) {
                        TokenType.ERROR_ELEMENT if child.textLength == 0 -> {}

                        TokenType.WHITE_SPACE -> {}

                        ZigTokenType.KEYWORD_CATCH -> {
                            addLast(ZigFormattingBlock(child, indent))
                            indent = Indent.getNormalIndent()
                        }

                        else -> {
                            addLast(ZigFormattingBlock(child, indent))
                        }
                    }
                    child = child.treeNext
                }
            }

            in BINARY_OPS -> {
                val indent = when (shouldIndentBinaryOp(myNode)) {
                    true -> Indent.getNormalIndent()
                    false -> Indent.getNoneIndent()
                }
                var child = myNode.firstChildNode
                while (child != null) {
                    when (child.elementType) {
                        TokenType.ERROR_ELEMENT if child.textLength == 0 -> {}

                        TokenType.WHITE_SPACE -> {}

                        else -> addLast(ZigFormattingBlock(child, indent))
                    }
                    child = child.treeNext
                }
            }

            in ASSIGN_OPS -> {
                var indent = Indent.getNoneIndent()
                var child = myNode.firstChildNode
                while (child != null) {
                    when (child.elementType) {
                        TokenType.ERROR_ELEMENT if child.textLength == 0 -> {}

                        TokenType.WHITE_SPACE -> {}

                        in ASSIGN_TOKENS -> {
                            addLast(ZigFormattingBlock(child, indent))
                            indent = Indent.getNormalIndent()
                        }

                        ZigTokenType.SEMICOLON, ZigTokenType.COMMA -> {
                            indent = Indent.getNoneIndent()
                            addLast(ZigFormattingBlock(child, indent))
                        }

                        else -> {
                            addLast(ZigFormattingBlock(child, indent))
                        }
                    }
                    child = child.treeNext
                }
            }

            in PAYLOADS -> {
                var outsidePayload = true
                var indent = Indent.getNoneIndent()
                var child = myNode.firstChildNode
                while (child != null) {
                    when (child.elementType) {
                        TokenType.ERROR_ELEMENT if child.textLength == 0 -> {}

                        TokenType.WHITE_SPACE -> {}

                        ZigTokenType.PIPE -> {
                            if (outsidePayload) {
                                addLast(ZigFormattingBlock(child, indent))
                                indent = Indent.getNormalIndent()
                                outsidePayload = false
                            } else {
                                indent = Indent.getNoneIndent()
                                addLast(ZigFormattingBlock(child, indent))
                                outsidePayload = true
                            }
                        }

                        else -> {
                            addLast(ZigFormattingBlock(child, indent))
                        }
                    }
                    child = child.treeNext
                }
            }

            in BRACE_ENCLOSED, in BRACKET_ENCLOSED, in PAREN_ENCLOSED -> {
                var indent = Indent.getNoneIndent()
                var child = myNode.firstChildNode
                while (child != null) {
                    when (child.elementType) {
                        TokenType.ERROR_ELEMENT if child.textLength == 0 -> {}

                        TokenType.WHITE_SPACE -> {}

                        ZigTokenType.L_BRACE, ZigTokenType.L_BRACKET, ZigTokenType.L_PAREN -> {
                            addLast(ZigFormattingBlock(child, indent))
                            indent = Indent.getNormalIndent()
                        }

                        ZigTokenType.R_BRACE, ZigTokenType.R_BRACKET, ZigTokenType.R_PAREN -> {
                            indent = Indent.getNoneIndent()
                            addLast(ZigFormattingBlock(child, indent))
                        }

                        else -> {
                            addLast(ZigFormattingBlock(child, indent))
                        }
                    }
                    child = child.treeNext
                }
            }

            is ZigElementType, ZigFileElementType -> {
                var child = myNode.firstChildNode
                while (child != null) {
                    when (child.elementType) {
                        TokenType.ERROR_ELEMENT if child.textLength == 0 -> {}

                        TokenType.WHITE_SPACE -> {}

                        ZigElementType.MULTILINE_STRING_LITERAL -> {
                            val wrap = Wrap.createWrap(WrapType.ALWAYS, true)
                            addLast(ZigFormattingBlock(child, Indent.getNoneIndent(), wrap = wrap))
                        }

                        else -> {
                            addLast(ZigFormattingBlock(child, Indent.getNoneIndent()))
                        }
                    }
                    child = child.treeNext
                }
            }

            is ZigTokenType, TokenType.BAD_CHARACTER -> {}

            TokenType.ERROR_ELEMENT -> {
                if (node.textLength == 0) {
                    throw IllegalStateException("Unexpected empty error block")
                }
            }

            TokenType.WHITE_SPACE -> throw IllegalStateException("Unexpected whitespace block")

            else -> throw IllegalStateException("Unexpected node type ${node.elementType}")
        }
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val indent = when (myNode.elementType) {
            in BRACE_ENCLOSED,
            in BRACKET_ENCLOSED,
            in PAREN_ENCLOSED,
            in ASSIGN_OPS -> Indent.getNormalIndent()

            in BINARY_OPS if shouldIndentBinaryOp(myNode) -> Indent.getNormalIndent()

            else -> {
                val prevBlock = subBlocks.getOrNull(newChildIndex - 1) as? ASTBlock
                when (prevBlock?.node?.elementType) {
                    ZigElementType.CONDITION,
                    ZigTokenType.KEYWORD_CATCH,
                    ZigTokenType.KEYWORD_ELSE,
                    in PAYLOADS -> Indent.getNormalIndent()

                    else -> Indent.getNoneIndent()
                }
            }
        }
        return ChildAttributes(indent, null)
    }

    override fun getIndent() = myIndent
    override fun getSpacing(child1: FormattingBlock?, child2: FormattingBlock) = null
    override fun isLeaf() = myNode.firstChildNode == null
}

private fun childNodeOfType(node: ASTNode, type: IElementType): ASTNode? {
    var child = node.firstChildNode
    while (child != null) {
        if (child.elementType === type) {
            return child
        }
        child = child.treeNext
    }
    return null
}

private fun shouldIndentBinaryOp(node: ASTNode): Boolean {
    var node = node
    var prev = node.treePrev
    while (prev == null) {
        node = node.treeParent ?: return false
        prev = node.treePrev
    }
    return prev.elementType !== TokenType.WHITE_SPACE || !prev.textContains('\n')
}

private val BINARY_OPS = TokenSet.create(
    ZigElementType.ADD,
    ZigElementType.ADD_SAT,
    ZigElementType.ADD_WRAP,
    ZigElementType.ARRAY_CAT,
    ZigElementType.BANG_EQUAL,
    ZigElementType.BIT_AND,
    ZigElementType.BIT_OR,
    ZigElementType.BIT_XOR,
    ZigElementType.BOOL_AND,
    ZigElementType.BOOL_OR,
    ZigElementType.DIV,
    ZigElementType.EQUAL_EQUAL,
    ZigElementType.GREATER_OR_EQUAL,
    ZigElementType.GREATER_THAN,
    ZigElementType.LESS_OR_EQUAL,
    ZigElementType.LESS_THAN,
    ZigElementType.MERGE_ERROR_SETS,
    ZigElementType.MOD,
    ZigElementType.MUL,
    ZigElementType.MUL_SAT,
    ZigElementType.MUL_WRAP,
    ZigElementType.ORELSE,
    ZigElementType.SHL,
    ZigElementType.SHL_SAT,
    ZigElementType.SHR,
    ZigElementType.SUB,
    ZigElementType.SUB_SAT,
    ZigElementType.SUB_WRAP,
)

private val BINARY_TOKENS = TokenSet.create(
    ZigTokenType.AMPERSAND,
    ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT,
    ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE,
    ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT,
    ZigTokenType.ANGLE_BRACKET_LEFT,
    ZigTokenType.ANGLE_BRACKET_LEFT_EQUAL,
    ZigTokenType.ANGLE_BRACKET_RIGHT,
    ZigTokenType.ANGLE_BRACKET_RIGHT_EQUAL,
    ZigTokenType.ASTERISK,
    ZigTokenType.ASTERISK_PERCENT,
    ZigTokenType.ASTERISK_PIPE,
    ZigTokenType.BANG_EQUAL,
    ZigTokenType.CARET,
    ZigTokenType.EQUAL_EQUAL,
    ZigTokenType.KEYWORD_AND,
    ZigTokenType.KEYWORD_OR,
    ZigTokenType.KEYWORD_ORELSE,
    ZigTokenType.MINUS,
    ZigTokenType.MINUS_PERCENT,
    ZigTokenType.MINUS_PIPE,
    ZigTokenType.PERCENT,
    ZigTokenType.PIPE,
    ZigTokenType.PIPE_PIPE,
    ZigTokenType.PLUS,
    ZigTokenType.PLUS_PERCENT,
    ZigTokenType.PLUS_PIPE,
    ZigTokenType.PLUS_PLUS,
    ZigTokenType.SLASH,
)

private val ASSIGN_OPS = TokenSet.create(
    ZigElementType.ASSIGN,
    ZigElementType.ASSIGN_ADD,
    ZigElementType.ASSIGN_ADD_SAT,
    ZigElementType.ASSIGN_ADD_WRAP,
    ZigElementType.ASSIGN_BIT_AND,
    ZigElementType.ASSIGN_BIT_OR,
    ZigElementType.ASSIGN_BIT_XOR,
    ZigElementType.ASSIGN_DESTRUCTURE,
    ZigElementType.ASSIGN_DIV,
    ZigElementType.ASSIGN_MOD,
    ZigElementType.ASSIGN_MUL,
    ZigElementType.ASSIGN_MUL_SAT,
    ZigElementType.ASSIGN_MUL_WRAP,
    ZigElementType.ASSIGN_SHL,
    ZigElementType.ASSIGN_SHL_SAT,
    ZigElementType.ASSIGN_SHR,
    ZigElementType.ASSIGN_SUB,
    ZigElementType.ASSIGN_SUB_SAT,
    ZigElementType.ASSIGN_SUB_WRAP,
    ZigElementType.CONTAINER_FIELD,
    ZigElementType.VAR_DECL,
)

private val ASSIGN_TOKENS = TokenSet.create(
    ZigTokenType.AMPERSAND_EQUAL,
    ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL,
    ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL,
    ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL,
    ZigTokenType.ANGLE_BRACKET_LEFT_EQUAL,
    ZigTokenType.ANGLE_BRACKET_RIGHT_EQUAL,
    ZigTokenType.ASTERISK_EQUAL,
    ZigTokenType.ASTERISK_PERCENT_EQUAL,
    ZigTokenType.ASTERISK_PIPE_EQUAL,
    ZigTokenType.CARET_EQUAL,
    ZigTokenType.EQUAL,
    ZigTokenType.MINUS_EQUAL,
    ZigTokenType.MINUS_PERCENT_EQUAL,
    ZigTokenType.MINUS_PIPE_EQUAL,
    ZigTokenType.PERCENT_EQUAL,
    ZigTokenType.PIPE_EQUAL,
    ZigTokenType.PLUS_EQUAL,
    ZigTokenType.PLUS_PERCENT_EQUAL,
    ZigTokenType.PLUS_PIPE_EQUAL,
    ZigTokenType.SLASH_EQUAL,
)

private val PAYLOADS = TokenSet.create(
    ZigElementType.CATCH_PAYLOAD,
    ZigElementType.ELSE_PAYLOAD,
    ZigElementType.FOR_PAYLOAD,
    ZigElementType.THEN_PAYLOAD,
    ZigElementType.SWITCH_PAYLOAD,
)

private val BRACE_ENCLOSED = TokenSet.create(
    ZigElementType.BLOCK,
    ZigElementType.CONTAINER_DECL,
    ZigElementType.ERROR_SET_DECL,
    ZigElementType.LIST_INIT,
    ZigElementType.SWITCH,
)

private val BRACKET_ENCLOSED = TokenSet.create(
    ZigElementType.ARRAY_ACCESS,
    ZigElementType.ARRAY_TYPE,
)

private val PAREN_ENCLOSED = TokenSet.create(
    ZigElementType.BUILTIN_CALL,
    ZigElementType.CALL,
    ZigElementType.FN_DECL,
    ZigElementType.FN_PROTO,
    ZigElementType.FOR,
)
