package io.github.fncontroloption.zig

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.util.findParentInFile
import com.intellij.psi.util.findParentOfType
import io.github.fncontroloption.zig.psi2.*

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
        if (element is ZigFieldAccess) {
            return generateBuiltinFieldDoc(element, originalElement)
        }
        val definition = getDefinition(element) ?: return null
        val sb = StringBuilder()
        HtmlChunk.text(definition)
            .wrapWith(DocumentationMarkup.PRE_ELEMENT)
            .wrapWith(DocumentationMarkup.DEFINITION_ELEMENT)
            .appendTo(sb)

        if (element is ZigReferenceable) {
            val typeParams = getOriginalContainerType(originalElement)?.typeParams ?: emptyMap()
            val initialValues = ZigAnalyzer.resolveValuesFromReferenceTo(element)
            val substitutedValues = ZigAnalyzer.substituteTypeParams(initialValues, typeParams)
            for (value in substitutedValues) {
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

    private fun generateBuiltinFieldDoc(element: ZigFieldAccess, originalElement: PsiElement?): String? {
        if (originalElement == null || element.identifierElement !== originalElement) {
            return null
        }
        val name = parseZigIdentifier(originalElement) ?: return null
        val left = ZigAnalyzer.resolveValues(element.leftOperand).singleOrNull() ?: return null
        val type = ZigAnalyzer.getBuiltinFieldType(left.typeOf, name) ?: return null
        val sb = StringBuilder()
        HtmlChunk.text(name)
            .wrapWith(DocumentationMarkup.PRE_ELEMENT)
            .wrapWith(DocumentationMarkup.DEFINITION_ELEMENT)
            .appendTo(sb)
        type.toHtmlChunk()
            .wrapWith(DocumentationMarkup.PRE_ELEMENT)
            .wrapWith(DocumentationMarkup.DEFINITION_ELEMENT)
            .appendTo(sb)
        return sb.toString()
    }

    override fun getDocumentationElementForLink(
        psiManager: PsiManager,
        link: String,
        context: PsiElement
    ): PsiElement? {
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
        is ZigContainerFieldImpl -> element.text
        is ZigFnDecl -> {
            val text = element.text
            val body = element.body ?: return text
            text.substring(0, body.textRangeInParent.startOffset)
        }
        is ZigLabelDecl -> element.identifierElement.text
        is ZigFnParam -> element.text
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
        is LightZigTupleField -> {
            var originalText: String? = null
            if (element.navigationElement != element) {
                originalText = element.navigationElement.text
            }
            originalText ?: element.container.fieldTypes[element.fieldIndex].toPlainText()
        }
        else -> null
    }
}

private fun findDoc(element: PsiElement): String? {
    if (element is ZigFile) {
        return findFileDoc(element)
    }
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

private fun findFileDoc(element: PsiElement): String? {
    val list = mutableListOf<String>()
    var child = element.firstChild
    while (true) {
        when (child) {
            is PsiComment if child.tokenType == ZigTokenType.CONTAINER_DOC_COMMENT -> {
                val text = child.text
                assert(text.startsWith("//!"))
                list.add(text.substring(3))
            }
            is PsiWhiteSpace -> {
                // no-op
            }
            else -> break
        }
        child = child.nextSibling
    }
    if (list.isEmpty()) {
        return null
    }
    return list.joinToString("")
}

private fun getOriginalContainerType(originalElement: PsiElement?): IZigContainerType? {
    when (val zigElement = originalElement?.findParentOfType<ZigElement>(strict = false)) {
        is ZigFieldAccess -> {
            if (zigElement.identifierElement == originalElement) {
                val leftValues = ZigAnalyzer.resolveValues(zigElement.leftOperand)
                return leftValues.singleOrNull()?.typeOf as? IZigContainerType
            }
        }

        is ZigListInitItem -> {
            if (zigElement.identifierElement == originalElement) {
                val leftTypes = ZigAnalyzer.resolveOrInferStructInitTypes(zigElement.containingListInit)
                return leftTypes.singleOrNull()
            }
        }

        else -> {}
    }

    return null
}
