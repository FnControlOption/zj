package io.github.fncontroloption.zig

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
import com.intellij.lang.tree.util.children
import com.intellij.lang.tree.util.siblings
import com.intellij.psi.TokenType
import com.intellij.psi.util.siblings
import io.github.fncontroloption.zig.psi2.ZigArrayAccess
import io.github.fncontroloption.zig.psi2.ZigArrayType
import io.github.fncontroloption.zig.psi2.ZigAsm
import io.github.fncontroloption.zig.psi2.ZigAssignDestructure
import io.github.fncontroloption.zig.psi2.ZigAssignElement
import io.github.fncontroloption.zig.psi2.ZigBinaryExpression
import io.github.fncontroloption.zig.psi2.ZigBlock
import io.github.fncontroloption.zig.psi2.ZigBuiltinCall
import io.github.fncontroloption.zig.psi2.ZigCall
import io.github.fncontroloption.zig.psi2.ZigCallArg
import io.github.fncontroloption.zig.psi2.ZigCatch
import io.github.fncontroloption.zig.psi2.ZigConditionElement
import io.github.fncontroloption.zig.psi2.ZigContainerDecl
import io.github.fncontroloption.zig.psi2.ZigContainerField
import io.github.fncontroloption.zig.psi2.ZigElementType
import io.github.fncontroloption.zig.psi2.ZigElseElement
import io.github.fncontroloption.zig.psi2.ZigErrorSetDecl
import io.github.fncontroloption.zig.psi2.ZigFieldAccess
import io.github.fncontroloption.zig.psi2.ZigFnDecl
import io.github.fncontroloption.zig.psi2.ZigFnProto
import io.github.fncontroloption.zig.psi2.ZigFor
import io.github.fncontroloption.zig.psi2.ZigForPayload
import io.github.fncontroloption.zig.psi2.ZigGroupedExpression
import io.github.fncontroloption.zig.psi2.ZigIf
import io.github.fncontroloption.zig.psi2.ZigListInit
import io.github.fncontroloption.zig.psi2.ZigListInitItem
import io.github.fncontroloption.zig.psi2.ZigSwitch
import io.github.fncontroloption.zig.psi2.ZigThenElement
import io.github.fncontroloption.zig.psi2.ZigTokenSets
import io.github.fncontroloption.zig.psi2.ZigTokenType
import io.github.fncontroloption.zig.psi2.ZigVarDecl
import io.github.fncontroloption.zig.psi2.ZigWhile
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

    private fun addChild(
        blocks: MutableList<FormattingBlock>,
        child: ASTNode,
        indent: Indent,
    ) = when (child.elementType) {
        TokenType.ERROR_ELEMENT if child.textLength == 0 -> {}

        TokenType.WHITE_SPACE -> {}

        ZigElementType.MULTILINE_STRING_LITERAL -> {
            var indent = Indent.getNormalIndent()
            when (myNode.psi) {
                is ZigListInitItem -> {
                    // The LIST_INIT_ITEM will already be indented, so we don't
                    // need to add an indent here.
                    indent = Indent.getNoneIndent()
                }

                is ZigCallArg -> {
                    val hasTrailingComma = when (val parent = myNode.treeParent.psi) {
                        is ZigBuiltinCall -> parent.hasTrailingComma
                        is ZigCall -> parent.hasTrailingComma
                        else -> false
                    }
                    if (hasTrailingComma) {
                        indent = Indent.getNoneIndent()
                    }
                }
            }
            val wrap = Wrap.createWrap(WrapType.ALWAYS, true)
            blocks.addLast(ZigFormattingBlock(child, indent, wrap = wrap))
        }

        else -> blocks.addLast(ZigFormattingBlock(child, indent))
    }

    override fun buildChildren(): List<FormattingBlock> {
        val blocks = mutableListOf<FormattingBlock>()
        when (val element = myNode.psi) {
            is ZigVarDecl, is ZigAssignDestructure, is ZigContainerField -> {
                var indent = Indent.getNoneIndent()
                for (child in myNode.children()) {
                    addChild(blocks, child, indent)
                    if (child.elementType === ZigTokenType.EQUAL) {
                        indent = Indent.getNormalIndent()
                    }
                }
            }

            is ZigAssignElement -> {
                var indent = Indent.getNoneIndent()
                for (child in myNode.children()) {
                    addChild(blocks, child, indent)
                    if (child.elementType === element.mainTokenType) {
                        indent = Indent.getNormalIndent()
                    }
                }
            }

            is ZigCatch -> {
                var indent = Indent.getNoneIndent()
                for (child in myNode.children()) {
                    addChild(blocks, child, indent)
                    if (child.elementType === ZigTokenType.KEYWORD_CATCH) {
                        indent = Indent.getNormalIndent()
                    }
                }
            }

            is ZigBinaryExpression, is ZigFieldAccess -> {
                val leftOperand = when (element) {
                    is ZigBinaryExpression -> element.leftOperand
                    is ZigFieldAccess -> element.leftOperand
                }
                val leftNode = leftOperand.node
                var indent = Indent.getNoneIndent()
                for (child in myNode.children()) {
                    addChild(blocks, child, indent)
                    if (child === leftNode) {
                        indent = Indent.getNormalIndent()
                    }
                }
            }

            is ZigArrayAccess, is ZigArrayType -> {
                var indent = Indent.getNoneIndent()
                for (child in myNode.children()) {
                    when (child.elementType) {
                        ZigTokenType.L_BRACKET -> {
                            addChild(blocks, child, indent)
                            indent = Indent.getNormalIndent()
                        }

                        ZigTokenType.R_BRACKET -> {
                            indent = Indent.getNoneIndent()
                            addChild(blocks, child, indent)
                        }

                        else -> addChild(blocks, child, indent)
                    }
                }
            }

            is ZigBlock,
            is ZigContainerDecl,
            is ZigErrorSetDecl,
            is ZigListInit,
            is ZigSwitch -> {
                var indent = Indent.getNoneIndent()
                for (child in myNode.children()) {
                    when (child.elementType) {
                        ZigTokenType.L_BRACE -> {
                            addChild(blocks, child, indent)
                            indent = Indent.getNormalIndent()
                        }

                        ZigTokenType.R_BRACE -> {
                            indent = Indent.getNoneIndent()
                            addChild(blocks, child, indent)
                        }

                        else -> addChild(blocks, child, indent)
                    }
                }
            }

            is ZigFnDecl,
            is ZigFnProto,
            is ZigFor,
            is ZigGroupedExpression -> {
                var indent = Indent.getNoneIndent()
                for (child in myNode.children()) {
                    when (child.elementType) {
                        ZigTokenType.L_PAREN -> {
                            addChild(blocks, child, indent)
                            indent = Indent.getNormalIndent()
                        }

                        ZigTokenType.R_PAREN -> {
                            indent = Indent.getNoneIndent()
                            addChild(blocks, child, indent)
                        }

                        else -> addChild(blocks, child, indent)
                    }
                }
            }

            is ZigBuiltinCall,
            is ZigCall -> {
                val hasTrailingComma = when (element) {
                    is ZigBuiltinCall -> element.hasTrailingComma
                    is ZigCall -> element.hasTrailingComma
                }
                var indent = Indent.getNoneIndent()
                for (child in myNode.children()) {
                    when (child.elementType) {
                        ZigTokenType.L_PAREN -> {
                            addChild(blocks, child, indent)
                            if (hasTrailingComma) {
                                indent = Indent.getNormalIndent()
                            }
                        }

                        ZigTokenType.R_PAREN -> {
                            indent = Indent.getNoneIndent()
                            addChild(blocks, child, indent)
                        }

                        else -> addChild(blocks, child, indent)
                    }
                }
            }

            is ZigForPayload -> {
                var indent = Indent.getNoneIndent()
                var outside = true
                for (child in myNode.children()) {
                    when (child.elementType) {
                        ZigTokenType.PIPE -> {
                            if (outside) {
                                addChild(blocks, child, indent)
                                indent = Indent.getNormalIndent()
                                outside = false
                            } else {
                                indent = Indent.getNoneIndent()
                                addChild(blocks, child, indent)
                                outside = true
                            }
                        }

                        else -> addChild(blocks, child, indent)
                    }
                }
            }

            is ZigThenElement, is ZigElseElement -> {
                val innerElement = when (element) {
                    is ZigThenElement -> element.innerElement
                    is ZigElseElement -> element.innerElement
                }
                val indent = when (innerElement) {
                    is ZigBlock -> Indent.getNoneIndent()
                    else -> Indent.getNormalIndent()
                }
                for (child in myNode.children()) {
                    addChild(blocks, child, indent)
                }
            }

            is ZigIf -> {
                val shouldIndentThenComments = element.thenElement.innerElement !is ZigBlock
                val shouldIndentElseComments = element.elseElement?.innerElement !is ZigBlock
                val thenBodyStart = (element.thenPayload ?: element.conditionElement).node.treeNext
                val elseBodyStart = (element.elsePayload ?: element.elseTokenElement)?.node?.treeNext
                var commentIndent = Indent.getNoneIndent()
                for (child in myNode.children()) {
                    var indent = Indent.getNoneIndent()
                    if (child === thenBodyStart) {
                        if (shouldIndentThenComments) {
                            commentIndent = Indent.getNormalIndent()
                        }
                    } else if (child.elementType === ZigTokenType.KEYWORD_ELSE) {
                        commentIndent = Indent.getNoneIndent()
                    } else if (child === elseBodyStart) {
                        if (shouldIndentElseComments) {
                            commentIndent = Indent.getNormalIndent()
                        }
                    } else if (child.elementType in ZigTokenSets.COMMENTS) {
                        indent = commentIndent
                    }
                    addChild(blocks, child, indent)
                }
            }

            is ZigAsm -> {} // TODO

            else -> {
                for (child in myNode.children()) {
                    addChild(blocks, child, Indent.getNoneIndent())
                }
            }
        }
        return blocks
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val prevBlock = subBlocks.getOrNull(newChildIndex - 1) as? ZigFormattingBlock
        val prevNode = prevBlock?.node
        val prevElement = prevNode?.psi
        var indent = Indent.getNoneIndent()
        when (myNode.psi) {
            is ZigVarDecl, is ZigAssignDestructure, is ZigContainerField -> {
                if (prevNode != null) {
                    val isAfterEqual =
                        prevNode
                            .siblings(forward = false, withSelf = true)
                            .any { it.elementType === ZigTokenType.EQUAL }
                    if (isAfterEqual) {
                        indent = Indent.getNormalIndent()
                    }
                }
            }

            is ZigAssignElement,
            is ZigCatch,
            is ZigBinaryExpression, is ZigFieldAccess,
            is ZigArrayAccess, is ZigArrayType -> indent = Indent.getNormalIndent()

            is ZigBlock,
            is ZigContainerDecl,
            is ZigErrorSetDecl,
            is ZigListInit,
            is ZigSwitch -> {
                if (prevNode != null) {
                    val isAfterLBrace =
                        prevNode
                            .siblings(forward = false, withSelf = true)
                            .any { it.elementType === ZigTokenType.L_BRACE }
                    if (isAfterLBrace) {
                        indent = Indent.getNormalIndent()
                    }
                }
            }

            is ZigFnDecl,
            is ZigFnProto,
            is ZigFor,
            is ZigGroupedExpression,
            is ZigBuiltinCall,
            is ZigCall -> {
                if (prevNode != null) {
                    val isAfterLParen =
                        prevNode
                            .siblings(forward = false, withSelf = true)
                            .any { it.elementType === ZigTokenType.L_PAREN }
                    if (isAfterLParen) {
                        indent = Indent.getNormalIndent()
                    }
                }
            }

            is ZigForPayload -> indent = Indent.getNormalIndent()

            is ZigIf, is ZigWhile -> {
                if (prevElement != null) {
                    val isAfterCondition =
                        prevElement
                            .siblings(forward = false, withSelf = true)
                            .any { it is ZigConditionElement }
                    if (isAfterCondition) {
                        indent = Indent.getNormalIndent()
                    }
                }
            }
        }
        return ChildAttributes(indent, null)
    }

    override fun getIndent() = myIndent
    override fun getSpacing(child1: FormattingBlock?, child2: FormattingBlock) = null
    override fun isLeaf() = myNode.firstChildNode == null
}
