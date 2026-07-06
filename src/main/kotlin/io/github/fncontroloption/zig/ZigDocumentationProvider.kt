package io.github.fncontroloption.zig

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import io.github.fncontroloption.zig.psi2.ZigContainerField
import io.github.fncontroloption.zig.psi2.ZigElement
import io.github.fncontroloption.zig.psi2.ZigFnDecl
import io.github.fncontroloption.zig.psi2.ZigLabelDecl
import io.github.fncontroloption.zig.psi2.ZigParamDecl
import io.github.fncontroloption.zig.psi2.ZigPayloadItem
import io.github.fncontroloption.zig.psi2.ZigTokenType
import io.github.fncontroloption.zig.psi2.ZigVarDecl
import io.github.fncontroloption.zig.psi2.ZigVarProto

class ZigDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        if (element !is ZigElement) {
            return null
        }
        val definition = getDefinition(element) ?: return null
        val sb = StringBuilder()
        HtmlChunk.text(definition)
            .wrapWith(DocumentationMarkup.PRE_ELEMENT)
            .wrapWith(DocumentationMarkup.DEFINITION_ELEMENT)
            .appendTo(sb)
        for (value in ZigAnalyzer.resolveValuesFromReferenceTo(element)) {
            HtmlChunk.text(value.toString())
                .wrapWith(DocumentationMarkup.PRE_ELEMENT)
                .wrapWith(DocumentationMarkup.DEFINITION_ELEMENT)
                .appendTo(sb)
        }
        val doc = findDoc(element)
        if (doc != null) {
            HtmlChunk.raw(convertMarkdownToHtml(doc))
                .wrapWith(DocumentationMarkup.CONTENT_ELEMENT)
                .appendTo(sb)
        }
        return sb.toString()
    }
}

private fun getDefinition(element: PsiElement): String? {
    return when (element) {
        is ZigContainerField -> element.text
        is ZigFnDecl -> {
            val text = element.text
            val body = element.body ?: return text
            text.substring(0, body.textRangeInParent.startOffset)
        }
        is ZigLabelDecl -> element.text
        is ZigParamDecl -> element.text
        is ZigPayloadItem -> element.text
        is ZigVarDecl -> {
            val text = element.text
            val typeElement = element.typeElement
            if (typeElement == null) {
                val newline = text.indexOf('\n')
                return if (newline < 0) text else "${text.substring(0, newline)} ..."
            }
            text.substring(0, typeElement.textRangeInParent.endOffset)
        }
        is ZigVarProto -> element.text
        else -> null
    }
}

private fun findDoc(element: PsiElement): String? {
    val list = mutableListOf<String>()
    var prev = element.prevSibling
    while (true) {
        when (prev) {
            is PsiComment if prev.tokenType == ZigTokenType.DOC_COMMENT -> {
                val text = prev.text
                assert(text.startsWith("///"))
                list.add(text.substring(3))
            }
            is PsiWhiteSpace -> {
                // no-op
            }
            else -> break
        }
        prev = prev.prevSibling
    }
    if (list.isEmpty()) {
        return null
    }
    list.reverse()
    return list.joinToString("")
}
