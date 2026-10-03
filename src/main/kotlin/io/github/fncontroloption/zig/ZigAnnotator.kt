package io.github.fncontroloption.zig

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import io.github.fncontroloption.zig.psi2.*

class ZigAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        annotateUnused(element, holder)
        annotateInvalidUnderscoreIdentifier(element, holder)
    }
}

private fun annotateUnused(element: PsiElement, holder: AnnotationHolder) {
    val namedElement = element as? ZigNamedElement ?: return
    val nameIdentifier = namedElement.nameIdentifier ?: return
    val kind = when (namedElement) {
        is ZigLabelDecl ->
            "label"

        is ZigFnParam if !nameIdentifier.textMatches("_") && namedElement.containingFunction.body != null ->
            "function parameter"

        is ZigPayloadItem if !nameIdentifier.textMatches("_") ->
            "capture"

        is ZigVarDeclOrProto if !nameIdentifier.textMatches("_") && namedElement.isLocal ->
            when (namedElement.mutability) {
                ZigMutability.CONST -> "local constant"
                ZigMutability.VAR -> "local variable"
            }

        else -> return
    }
    val searchScope = LocalSearchScope(namedElement.containingFile)
    val query = ReferencesSearch.search(namedElement, searchScope)
    if (query.findFirst() == null) {
        holder.newAnnotation(HighlightSeverity.ERROR, "Unused $kind")
            .problemGroup { "UNUSED" }
            .range(nameIdentifier)
            // TODO: .newFix().registerFix()
            .create()
    }
}

private const val UNDERSCORE_IDENTIFIER_MESSAGE = """'_' used as an identifier without @"_" syntax"""

private fun annotateInvalidUnderscoreIdentifier(element: PsiElement, holder: AnnotationHolder) {
    if (element is ZigVarDeclOrProto) {
        val nameIdentifier = element.nameIdentifier ?: return
        if (nameIdentifier.textMatches("_")) {
            holder.newAnnotation(HighlightSeverity.ERROR, UNDERSCORE_IDENTIFIER_MESSAGE)
                // TODO: .problemGroup {}
                .range(nameIdentifier)
                // TODO: .newFix().registerFix()
                .create()
        }
    }
}
