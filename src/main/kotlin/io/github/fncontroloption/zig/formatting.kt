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
import com.intellij.lang.ASTNode
import com.intellij.lang.tree.util.children
import com.intellij.psi.TokenType
import io.github.fncontroloption.zig.psi2.ZigArrayAccess
import io.github.fncontroloption.zig.psi2.ZigArrayType
import io.github.fncontroloption.zig.psi2.ZigAsm
import io.github.fncontroloption.zig.psi2.ZigAssignDestructure
import io.github.fncontroloption.zig.psi2.ZigAssignElement
import io.github.fncontroloption.zig.psi2.ZigBinaryExpression
import io.github.fncontroloption.zig.psi2.ZigBlock
import io.github.fncontroloption.zig.psi2.ZigBuiltinCall
import io.github.fncontroloption.zig.psi2.ZigCall
import io.github.fncontroloption.zig.psi2.ZigCatch
import io.github.fncontroloption.zig.psi2.ZigContainerDecl
import io.github.fncontroloption.zig.psi2.ZigContainerField
import io.github.fncontroloption.zig.psi2.ZigElseElement
import io.github.fncontroloption.zig.psi2.ZigErrorSetDecl
import io.github.fncontroloption.zig.psi2.ZigFieldAccess
import io.github.fncontroloption.zig.psi2.ZigFnDecl
import io.github.fncontroloption.zig.psi2.ZigFnProto
import io.github.fncontroloption.zig.psi2.ZigFor
import io.github.fncontroloption.zig.psi2.ZigForPayload
import io.github.fncontroloption.zig.psi2.ZigGroupedExpression
import io.github.fncontroloption.zig.psi2.ZigListInit
import io.github.fncontroloption.zig.psi2.ZigSwitch
import io.github.fncontroloption.zig.psi2.ZigThenElement
import io.github.fncontroloption.zig.psi2.ZigTokenType
import io.github.fncontroloption.zig.psi2.ZigVarDecl
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

private fun addChild(
    blocks: MutableList<FormattingBlock>,
    child: ASTNode,
    indent: Indent,
) = when (child.elementType) {
    TokenType.ERROR_ELEMENT if child.textLength == 0 -> {}
    TokenType.WHITE_SPACE -> {}
    else -> blocks.addLast(ZigFormattingBlock(child, indent))
}

class ZigFormattingBlock(
    node: ASTNode,
    indent: Indent,
    alignment: Alignment? = null,
    wrap: Wrap? = null,
) : AbstractFormattingBlock(node, wrap, alignment) {
    private val myIndent = indent

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

            is ZigBuiltinCall,
            is ZigCall,
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
        return ChildAttributes(Indent.getNoneIndent(), null)
    }

    override fun getIndent() = myIndent
    override fun getSpacing(child1: FormattingBlock?, child2: FormattingBlock) = null
    override fun isLeaf() = myNode.firstChildNode == null
}
