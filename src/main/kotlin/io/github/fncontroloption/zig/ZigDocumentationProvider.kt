package io.github.fncontroloption.zig

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.findParentInFile
import io.github.fncontroloption.zig.psi2.ZigContainerField
import io.github.fncontroloption.zig.psi2.ZigElement
import io.github.fncontroloption.zig.psi2.ZigFile
import io.github.fncontroloption.zig.psi2.ZigFnDecl
import io.github.fncontroloption.zig.psi2.ZigLabelDecl
import io.github.fncontroloption.zig.psi2.ZigParamDecl
import io.github.fncontroloption.zig.psi2.ZigPayloadItem
import io.github.fncontroloption.zig.psi2.ZigTokenType
import io.github.fncontroloption.zig.psi2.ZigVarDecl
import io.github.fncontroloption.zig.psi2.ZigVarProto

const val ZIG_DOCUMENTATION_OFFSET_SEPARATOR = $$"$zigDocumentationOffset$"

class ZigDocumentationProvider : AbstractDocumentationProvider() {
    override fun getQuickNavigateInfo(element: PsiElement, originalElement: PsiElement?): String? {
        if (element !is ZigElement) {
            return null
        }
        val definition = getDefinition(element) ?: return null
        val sb = StringBuilder()
        HtmlChunk.text(definition)
            .wrapWith(DocumentationMarkup.PRE_ELEMENT)
            .wrapWith(DocumentationMarkup.DEFINITION_ELEMENT)
            .appendTo(sb)
        return sb.toString()
    }

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

        if (hasValue(element)) {
            for (value in ZigAnalyzer.resolveValuesFromReferenceTo(element)) {
                var chunk = value.typeOf.toHtmlChunk()
                if (value !is ZigUnknown) {
                    chunk = HtmlChunk.fragment(chunk, HtmlChunk.text(" = "), value.toHtmlChunk())
                }
                chunk
                    .wrapWith(DocumentationMarkup.PRE_ELEMENT)
                    .wrapWith(DocumentationMarkup.DEFINITION_ELEMENT)
                    .appendTo(sb)
            }
        }

        val doc = findDoc(element)
        if (doc != null) {
            HtmlChunk.raw(convertMarkdownToHtml(doc))
                .wrapWith(DocumentationMarkup.CONTENT_ELEMENT)
                .appendTo(sb)
        }

        return sb.toString()
    }

    override fun getDocumentationElementForLink(psiManager: PsiManager, link: String, context: PsiElement): PsiElement? {
        val offsetIdx = link.lastIndexOf(ZIG_DOCUMENTATION_OFFSET_SEPARATOR)
        val url = if (offsetIdx < 0) link else link.substring(0, offsetIdx)
        val virtualFile = VirtualFileManager.getInstance().findFileByUrl(url) ?: return null
        val file = psiManager.findFile(virtualFile) ?: return null
        if (offsetIdx < 0) return file
        val offset = link.substring(offsetIdx + ZIG_DOCUMENTATION_OFFSET_SEPARATOR.length).toInt()
        return file.findElementAt(offset)?.findParentInFile { it is PsiNamedElement }
    }
}

private fun getDefinition(element: PsiElement): String? {
    return when (element) {
        is ZigFile -> element.name.substringBeforeLast('.')
        is ZigContainerField -> element.text
        is ZigFnDecl -> {
            val text = element.text
            val body = element.body ?: return text
            text.substring(0, body.textRangeInParent.startOffset)
        }
        is ZigLabelDecl -> element.identifierElement.text
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

private fun hasValue(element: PsiElement): Boolean {
    return element !is ZigLabelDecl
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
