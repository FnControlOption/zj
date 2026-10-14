@file:Suppress("UnstableApiUsage")

package io.github.fncontroloption.zig

import com.intellij.ide.util.ModuleRendererFactory
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.model.Pointer
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.documentation.*
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.*
import com.intellij.psi.util.findParentOfType

const val ZIG_DOCUMENTATION_PROTOCOL = "zig_doc://"
const val ZIG_DOCUMENTATION_OFFSET_SEPARATOR = $$"$zigDocumentationOffset$"

class ZigPsiDocumentationTargetProvider : PsiDocumentationTargetProvider {
    override fun documentationTargets(element: PsiElement, originalElement: PsiElement?): List<DocumentationTarget> {
        val shouldResolveReferences = when (element) {
            is ZigIdentifier -> element.identifierElement === originalElement
            is ZigTestDecl -> element.nameElement === originalElement
            is ZigFieldAccess -> element.identifierElement === originalElement
            is ZigEnumLiteral -> element.identifierElement === originalElement
            is ZigListInitItem -> element.fieldNameIdentifier === originalElement
            else -> false
        }
        if (shouldResolveReferences) {
            val references = resolveReferences(element).filterIsInstance<ZigElement>().toList()
            if (references.isNotEmpty()) {
                return references.map { ZigPsiDocumentationTarget(it, originalElement) }
            }
        }
        if (element is ZigElement) {
            return listOf(ZigPsiDocumentationTarget(element, originalElement))
        }
        return emptyList()
    }
}

private class ZigPsiDocumentationTargetPointer(
    val elementPointer: Pointer<out ZigElement>,
    val originalElementPointer: Pointer<out PsiElement>?,
) : Pointer<ZigPsiDocumentationTarget> {
    override fun dereference(): ZigPsiDocumentationTarget? {
        val element = elementPointer.dereference() ?: return null
        val originalElement = originalElementPointer?.let { it.dereference() ?: return null }
        return ZigPsiDocumentationTarget(element, originalElement, this)
    }
}

/** @see com.intellij.lang.documentation.psi.PsiElementDocumentationTarget */
private class ZigPsiDocumentationTarget(
    val element: ZigElement,
    val originalElement: PsiElement?,
    val pointer: ZigPsiDocumentationTargetPointer = ZigPsiDocumentationTargetPointer(
        elementPointer = element.createSmartPointer(),
        originalElementPointer = originalElement?.createSmartPointer(),
    ),
) : DocumentationTarget {
    override fun createPointer() = pointer

    /** @see com.intellij.codeInsight.navigation.targetPresentation */
    override fun computePresentation(): TargetPresentation {
        val moduleTextWithIcon = ModuleRendererFactory.findInstance(element).getModuleTextWithIcon(element)
        val presentation = (element as? ZigNavigatableElement)?.presentation
        return TargetPresentation
            .builder(presentation?.presentableText ?: "")
            .icon(presentation?.getIcon(false))
            .containerText(element.containingFile.name)
            .locationText(moduleTextWithIcon?.text, moduleTextWithIcon?.icon)
            .presentation()
    }

    override val navigatable: ZigNavigatableElement?
        get() = element as? ZigNavigatableElement

    override fun computeDocumentationHint(): String? {
        if (element !is ZigNavigatableElement) {
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

    override fun computeDocumentation(): DocumentationResult? {
        val html = generateBuiltinFieldDoc() ?: generateNavigatableDoc() ?: return null
        return DocumentationResult.documentation(html)
    }

    private fun generateNavigatableDoc(): String? {
        if (element !is ZigNavigatableElement) {
            return null
        }
        val definition = getDefinition(element) ?: return null
        val sb = StringBuilder()
        HtmlChunk.text(definition)
            .wrapWith(DocumentationMarkup.PRE_ELEMENT)
            .wrapWith(DocumentationMarkup.DEFINITION_ELEMENT)
            .appendTo(sb)

        if (element is ZigNamedElement) {
            val initialValue = ZigAnalyzer.resolveValueFromReferenceTo(element)
            val values =
                containerTypes(originalElement)
                    .map { ZigAnalyzer.substituteGenericParams(initialValue, it.genericParams) }
                    .toSet()
                    .ifEmpty { setOf(initialValue) }
            ZigMultiValue.from(values).forEachValue { value ->
                val chunk = when (value) {
                    is ZigAnytypeParameterValue if value.param.dereference() == element -> {
                        createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "anytype")
                    }
                    is ZigUnknown -> {
                        value.typeOf.toHtmlChunk()
                    }
                    else -> {
                        val typeChunk = value.typeOf.toHtmlChunk()
                        val valueChunk = value.toHtmlChunk()
                        HtmlChunk.fragment(typeChunk, HtmlChunk.text(" = "), valueChunk)
                    }
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

    private fun generateBuiltinFieldDoc(): String? {
        if (element !is ZigFieldAccess) {
            return null
        }
        if (originalElement == null || element.identifierElement !== originalElement) {
            return null
        }
        val name = parseZigIdentifier(originalElement) ?: return null
        val left = ZigAnalyzer.resolveValue(element.leftOperand)
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
}

class ZigPsiDocumentationLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        if (target !is ZigPsiDocumentationTarget) return null
        if (!url.startsWith(ZIG_DOCUMENTATION_PROTOCOL)) return null
        val link = url.substring(ZIG_DOCUMENTATION_PROTOCOL.length)
        val context = target.element
        val psiManager = PsiManager.getInstance(context.project)
        val offsetIdx = link.lastIndexOf(ZIG_DOCUMENTATION_OFFSET_SEPARATOR)
        val vfsUrl = if (offsetIdx < 0) link else link.substring(0, offsetIdx)
        val virtualFile = VirtualFileManager.getInstance().findFileByUrl(vfsUrl) ?: return null
        val file = psiManager.findFile(virtualFile) ?: return null
        if (file !is ZigFile) return null
        if (offsetIdx < 0) return LinkResolveResult.resolvedTarget(ZigPsiDocumentationTarget(file, null))
        val offset = link.substring(offsetIdx + ZIG_DOCUMENTATION_OFFSET_SEPARATOR.length).toInt()
        val parent = file.findElementAt(offset)?.findParentOfType<ZigNavigatableElement>()
        if (parent == null || parent == context) return null
        return LinkResolveResult.resolvedTarget(ZigPsiDocumentationTarget(parent, null))
    }
}

private fun getDefinition(element: ZigNavigatableElement): String? {
    return when (element) {
        is ZigFile -> element.name.substringBeforeLast('.')
        is ZigContainerExpression -> element.text
        is ZigContainerFieldImpl -> element.text
        is ZigBuiltinFnProto -> element.text
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
                return if (newline < 0) text else "${text.substring(0, newline)}..."
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
    }
}

private fun findDoc(element: ZigNavigatableElement): String? {
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

private fun findFileDoc(element: ZigFile): String? {
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

/**
 * @see ZigFieldAccessReference.multiResolve
 * @see ZigListInitItemReference.multiResolve
 * @see ZigEnumLiteralReference.multiResolve
 */
private fun containerTypes(originalElement: PsiElement?): Sequence<ZigContainerType> {
    when (val zigElement = originalElement?.findParentOfType<ZigElement>()) {
        is ZigFieldAccess -> {
            if (zigElement.identifierElement == originalElement) {
                val leftValue = ZigAnalyzer.resolveValue(zigElement.leftOperand)
                return ZigAnalyzer.fieldAccessContainers(leftValue).map { (containerType, _) -> containerType }
            }
        }

        is ZigListInitItem -> {
            if (zigElement.fieldNameIdentifier == originalElement) {
                val listType = ZigAnalyzer.resolveOrInferListInitType(zigElement.containingListInit)
                return listType.asValueSequence().filterIsInstance<ZigContainerType>()
            }
        }

        is ZigEnumLiteral -> {
            if (zigElement.identifierElement == originalElement) {
                val resultType = ZigAnalyzer.inferResultType(zigElement)
                return resultType.asValueSequence().mapNotNull { ZigAnalyzer.findEnumLiteralContainerType(it) }
            }
        }

        else -> {}
    }

    return emptySequence()
}
