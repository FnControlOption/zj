package io.github.fncontroloption.zig

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.text.Strings
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parents
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
    if (!DumbService.isDumb(namedElement.project)) {
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
    val nameElement = when (element) {
        is ZigIdentifier -> {
            val isValid = when (val parent = element.parent) {
                is ZigAssign -> parent.leftExpression == element
                is ZigAssignDestructure -> parent.variables.contains(element)
                is ZigTypeLength -> parent.expression == element
                is ZigSwitchItem -> parent.expression == element
                else -> false
            }
            if (isValid) {
                return
            }
            element.identifierElement
        }
        is ZigTestDecl -> element.nameElement ?: return
        is ZigVarDeclOrProto -> element.nameIdentifier ?: return
        else -> return
    }
    if (nameElement.elementType !== ZigTokenType.IDENTIFIER) return
    if (!nameElement.textMatches("_")) return
    holder.newAnnotation(HighlightSeverity.ERROR, UNDERSCORE_IDENTIFIER_MESSAGE)
        // TODO: .problemGroup {}
        .range(nameElement)
        // TODO: .newFix().registerFix()
        .create()
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
        append("Use of undeclared identifier '")
        append(name)
        append("'")
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
            append(" member name '")
            append(name)
            append("'")
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
    val nameToFirstDecl = mutableMapOf<String, ZigNamedReferenceable>()
    for (decl in decls) {
        val nameIdentifier = decl.nameIdentifier ?: continue
        if (nameIdentifier.textMatches("_")) continue
        val name = parseZigIdentifier(nameIdentifier) ?: continue
        val firstDecl = nameToFirstDecl[name]
        if (firstDecl == null) {
            nameToFirstDecl[name] = decl
            continue
        }
        val kind = when (firstDecl) {
            is ZigFnParam -> "function parameter"
            is ZigVarDeclOrProto -> when (firstDecl.mutability) {
                ZigMutability.CONST -> "local constant"
                ZigMutability.VAR -> "local variable"
            }
            is ZigPayloadItem -> "capture"
            else -> throw IllegalStateException()
        }
        val message = buildString {
            append("Redeclaration of ")
            append(kind)
            append(" '")
            append(name)
            append("'")
        }
        val tooltip = buildString {
            append("<html>Redeclaration of ")
            append(kind)
            append(" '<a href='#navigation/")
            append(firstDecl.containingFile.viewProvider.virtualFile.path)
            append(':')
            append(firstDecl.textOffset)
            append("'>")
            append(Strings.escapeXmlEntities(name))
            append("</a>'</html>")
        }
        holder.newAnnotation(HighlightSeverity.ERROR, message)
            // TODO: .problemGroup {}
            .range(nameIdentifier)
            .tooltip(tooltip)
            .create()
    }
}

class ZigShadowAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        annotateShadow(element, holder)
    }
}

private fun annotateShadow(element: PsiElement, holder: AnnotationHolder) {
    val namedElement = element as? ZigNamedElement ?: return
    val nameIdentifier = namedElement.nameIdentifier ?: return
    val name = parseZigIdentifier(nameIdentifier) ?: return
    val kind = when (namedElement) {
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
    val containingScope = when (namedElement) {
        is ZigFnParam -> namedElement.containingFunction
        is ZigPayloadItem -> namedElement.containingPayload.parent
        is ZigVarProto -> namedElement.containingAssignDestructure.parent
        is ZigVarDecl -> namedElement.parent
    }
    val candidates = identifierReferenceCandidates(containingScope)
    val shadowed = candidates.firstOrNull { it.name == name } ?: return
    val (message, tooltip) = run {
        val shadowedKind = when (shadowed) {
            is ZigFnParam -> "function parameter"
            is ZigPayloadItem -> "capture"
            is ZigVarDeclOrProto if shadowed.isLocal -> when (shadowed.mutability) {
                ZigMutability.CONST -> "local constant"
                ZigMutability.VAR -> "local variable"
            }
            is ZigVarDecl, is ZigFnDecl -> {
                val message = buildString {
                    append(Strings.capitalize(kind))
                    append(" shadows declaration of '")
                    append(name)
                    append("'")
                }
                val tooltip = buildString {
                    append("<html>")
                    append(Strings.capitalize(kind))
                    append(" shadows declaration of '<a href='#navigation/")
                    append(shadowed.containingFile.viewProvider.virtualFile.path)
                    append(':')
                    append(shadowed.textOffset)
                    append("'>")
                    append(Strings.escapeXmlEntities(name))
                    append("</a>'</html>")
                }
                return@run Pair(message, tooltip)
            }
            else -> return
        }
        val message = buildString {
            append(Strings.capitalize(kind))
            append(" shadows ")
            append(shadowedKind)
            append(" '")
            append(name)
            append("' from outer scope")
        }
        val tooltip = buildString {
            append("<html>")
            append(Strings.capitalize(kind))
            append(" shadows ")
            append(shadowedKind)
            append(" '<a href='#navigation/")
            append(shadowed.containingFile.viewProvider.virtualFile.path)
            append(':')
            append(shadowed.textOffset)
            append("'>")
            append(Strings.escapeXmlEntities(name))
            append("</a>' from outer scope</html>")
        }
        Pair(message, tooltip)
    }
    holder.newAnnotation(HighlightSeverity.ERROR, message)
        // TODO: .problemGroup {}
        .range(nameIdentifier)
        .tooltip(tooltip)
        .create()
}

class ZigAmbiguousReferenceAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        annotateAmbiguousReference(element, holder)
    }
}

private fun annotateAmbiguousReference(element: PsiElement, holder: AnnotationHolder) {
    val nameElement = when (element) {
        is ZigIdentifier -> element.identifierElement
        is ZigTestDecl -> element.nameElement ?: return
        else -> return
    }
    if (nameElement.elementType !== ZigTokenType.IDENTIFIER) return
    if (nameElement.textMatches("_")) return
    if (isZigPrimitiveText(nameElement.text)) return
    val name = parseZigIdentifier(nameElement) ?: return
    val decls = identifierReferenceCandidates(nameElement).filter {
        when (it) {
            is ZigFnDecl -> it.name == name
            is ZigVarDecl -> it.name == name && !it.isLocal
            else -> false
        }
    }
    if (decls.take(2).count() > 1) {
        holder.newAnnotation(HighlightSeverity.ERROR, "Ambiguous reference")
            // TODO: .problemGroup {}
            .range(nameElement)
            .create()
    }
}

class ZigLabelRedefinitionAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        annotateLabelRedefinition(element, holder)
    }
}

private fun annotateLabelRedefinition(element: PsiElement, holder: AnnotationHolder) {
    if (element !is ZigLabelable) return
    val nameIdentifier = element.labelDecl?.nameIdentifier ?: return
    val name = parseZigIdentifier(nameIdentifier) ?: return
    for (parent in element.parents(withSelf = false)) {
        if (parent is ZigContainer) return
        if (parent !is ZigLabelable) continue
        val parentIdentifier = parent.labelDecl?.nameIdentifier ?: continue
        val parentName = parseZigIdentifier(parentIdentifier) ?: continue
        if (parentName != name) continue
        val message = buildString {
            append("Redefinition of label '")
            append(name)
            append("'")
        }
        val tooltip = buildString {
            append("<html>Redefinition of label '<a href='#navigation/")
            append(parentIdentifier.containingFile.viewProvider.virtualFile.path)
            append(':')
            append(parentIdentifier.textOffset)
            append("'>")
            append(Strings.escapeXmlEntities(name))
            append("</a>'</html>")
        }
        holder.newAnnotation(HighlightSeverity.ERROR, message)
            // TODO: .problemGroup {}
            .range(nameIdentifier)
            .tooltip(tooltip)
            .create()
        return
    }
}
