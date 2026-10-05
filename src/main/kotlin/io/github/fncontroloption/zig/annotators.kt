package io.github.fncontroloption.zig

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.siblings
import io.github.fncontroloption.zig.psi2.*

class ZigUnusedAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        annotateUnused(element, holder)
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
    val containingFile = namedElement.containingFile
    val searchScopes = mutableListOf<SearchScope>()
    searchScopes.add(LocalSearchScope(containingFile))
    if (!DumbService.isDumb(element.project)) {
        searchScopes.add(GlobalSearchScope.fileScope(containingFile))
    }
    if (searchScopes.all { ReferencesSearch.search(namedElement, it).findFirst() == null }) {
        holder.newAnnotation(HighlightSeverity.ERROR, "Unused $kind")
            .problemGroup { "UNUSED" }
            .range(nameIdentifier)
            // TODO: .newFix().registerFix()
            .create()
    }
}

class ZigUnderscoreIdentifierAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        annotateInvalidUnderscoreIdentifier(element, holder)
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

class ZigUndeclaredIdentifierAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        annotateUndeclaredIdentifier(element, holder)
    }
}

private fun annotateUndeclaredIdentifier(element: PsiElement, holder: AnnotationHolder) {
    if (element !is ZigIdentifier) return
    if (element.textMatches("_")) return
    if (isZigPrimitiveText(element.text)) return
    if (resolveReferences(element).any()) return
    val name = parseZigIdentifier(element) ?: return
    val message = buildString {
        append("Use of undeclared identifier ")
        escapeZigString(name, this)
    }
    holder.newAnnotation(HighlightSeverity.ERROR, message)
        // TODO: .problemGroup {}
        .range(element)
        .create()
}

class ZigDuplicateMembersAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        annotateDuplicateMembers(element, holder)
    }
}

private fun annotateDuplicateMembers(element: PsiElement, holder: AnnotationHolder) {
    if (element !is ZigContainer) return
    val kind = when (element.containerKind) {
        ZigContainerKind.STRUCT -> "struct"
        ZigContainerKind.OPAQUE -> "opaque"
        ZigContainerKind.ENUM -> "enum"
        ZigContainerKind.UNION -> "union"
    }
    val nameIdentifierMap =
        element.members
            .filterIsInstance<ZigNamedContainerMember>()
            .mapNotNull { it.nameIdentifier }
            .groupBy { parseZigIdentifier(it) }
    for ((name, identifiers) in nameIdentifierMap) {
        assert(identifiers.isNotEmpty())
        if (name == null) continue
        if (identifiers.size == 1) continue
        val message = buildString {
            append("Duplicate ")
            append(kind)
            append(" member name ")
            escapeZigString(name, this)
        }
        for (identifier in identifiers) {
            holder.newAnnotation(HighlightSeverity.ERROR, message)
                // TODO: .problemGroup {}
                .range(identifier)
                .create()
        }
    }
}

class ZigRedeclarationsAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        annotateRedeclarations(element, holder)
    }
}

private fun annotateRedeclarations(element: PsiElement, holder: AnnotationHolder) {
    val decls = when (element) {
        is ZigFnOrBuiltinFnDeclOrProto -> element.parameters
        is ZigBlock -> element.firstChild.siblings().flatMap { statement ->
            when (statement) {
                is ZigVarDecl -> sequenceOf(statement)
                is ZigAssignDestructure -> statement.variables.filterIsInstance<ZigVarProto>()
                else -> emptySequence()
            }
        }
        is ZigFor -> element.payload?.items ?: return
        is ZigSwitchProng -> element.payload?.items ?: return
        else -> return
    }
    val nameToKind = mutableMapOf<String, String>()
    for (decl in decls) {
        val nameIdentifier = decl.nameIdentifier ?: continue
        if (nameIdentifier.textMatches("_")) continue
        val name = parseZigIdentifier(nameIdentifier) ?: continue
        val kind = nameToKind[name]
        if (kind == null) {
            nameToKind[name] = when (decl) {
                is ZigFnParam -> "function parameter"
                is ZigVarDeclOrProto -> when (decl.mutability) {
                    ZigMutability.CONST -> "local constant"
                    ZigMutability.VAR -> "local variable"
                }
                is ZigPayloadItem -> "capture"
                else -> throw IllegalStateException()
            }
            continue
        }
        val message = buildString {
            append("Redeclaration of ")
            append(kind)
            append(" ")
            escapeZigString(name, this)
        }
        holder.newAnnotation(HighlightSeverity.ERROR, message)
            // TODO: .problemGroup {}
            .range(nameIdentifier)
            .create()
    }
}
