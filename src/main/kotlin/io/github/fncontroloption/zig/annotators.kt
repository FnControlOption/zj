package io.github.fncontroloption.zig

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.text.Strings
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parents

class ZigUnusedAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        annotateUnused(element, holder)
    }
}

private fun annotateUnused(element: PsiElement, holder: AnnotationHolder) {
    when (element) {
        is ZigBlock -> {
            annotateUnusedLabel(element, "block", holder)
            annotateUnusedVariables(element, holder)
        }
        is ZigFnDecl -> {
            annotateUnusedParameters(element, holder)
        }
        is ZigIf -> {
            annotateUnusedCaptures(
                payload = element.thenPayload,
                bodies = listOfNotNull(element.thenElement.innerElement),
                holder = holder
            )
            annotateUnusedCaptures(
                payload = element.elsePayload,
                bodies = listOfNotNull(element.elseElement?.innerElement),
                holder = holder
            )
        }
        is ZigWhile -> {
            annotateUnusedLabel(element, "while loop", holder)
            annotateUnusedCaptures(
                payload = element.thenPayload,
                bodies = listOfNotNull(element.whileContinueExpr?.innerElement, element.thenElement.innerElement),
                holder = holder,
            )
            annotateUnusedCaptures(
                payload = element.elsePayload,
                bodies = listOfNotNull(element.elseElement?.innerElement),
                holder = holder
            )
        }
        is ZigFor -> {
            annotateUnusedLabel(element, "for loop", holder)
            annotateUnusedCaptures(
                payload = element.payload,
                bodies = listOfNotNull(element.thenElement.innerElement),
                holder = holder
            )
        }
        is ZigSwitch -> {
            annotateUnusedLabel(element, "switch", holder)
        }
        is ZigSwitchProng -> {
            annotateUnusedCaptures(element.payload, listOfNotNull(element.body), holder)
        }
        is ZigCatch -> {
            annotateUnusedCaptures(element.payload, listOfNotNull(element.rightOperand), holder)
        }
    }
}

private fun annotateUnusedLabel(labelable: ZigLabelable, kind: String, holder: AnnotationHolder) {
    val identifier = labelable.labelDecl?.identifierElement ?: return
    val name = parseZigIdentifier(identifier) ?: return
    val traverser = SyntaxTraverser.psiTraverser(labelable).expand { it !is ZigContainerExpression }
    for (child in traverser) {
        if (child is ZigLabelRef && name == parseZigIdentifier(child.identifierElement)) {
            return
        }
    }
    holder.newAnnotation(HighlightSeverity.ERROR, "Unused $kind label")
        // TODO: .problemGroup {}
        .range(identifier)
        .create()
}

private class DeclarationInfo(val identifier: PsiElement, val kind: String)

private fun findUsedDeclarations(
    element: PsiElement?,
    declarations: Map<String, DeclarationInfo>,
    used: MutableSet<String>,
) {
    if (element == null || used.size == declarations.size) {
        return
    }
    for (child in SyntaxTraverser.psiTraverser(element)) {
        if (child is ZigIdentifier) {
            if (child.textMatches("_")) continue
            val name = parseZigIdentifier(child) ?: continue
            if (name in declarations) {
                used.add(name)
                if (used.size == declarations.size) {
                    return
                }
            }
        }
    }
}

private fun createUnusedAnnotations(
    declarations: Map<String, DeclarationInfo>,
    used: Set<String>,
    holder: AnnotationHolder,
) {
    if (used.size == declarations.size) {
        return
    }
    for ((name, info) in declarations) {
        if (name in used) {
            continue
        }
        holder.newAnnotation(HighlightSeverity.ERROR, "Unused ${info.kind}")
            // TODO: .problemGroup {}
            .range(info.identifier)
            // TODO: .newFix().registerFix()
            .create()
    }
}

private fun annotateUnusedVariables(block: ZigBlock, holder: AnnotationHolder) {
    val declarations = mutableMapOf<String, DeclarationInfo>()
    val used = mutableSetOf<String>()
    for (statement in block.statements) {
        findUsedDeclarations(statement, declarations, used)
        for (variable in ZigBlock.statementToVarDecls(statement)) {
            val identifier = variable.nameIdentifier ?: continue
            if (identifier.textMatches("_")) continue
            val name = parseZigIdentifier(identifier) ?: continue
            val kind = when (variable.mutability) {
                ZigMutability.CONST -> "local constant"
                ZigMutability.VAR -> "local variable"
            }
            declarations.putIfAbsent(name, DeclarationInfo(identifier, kind))
        }
    }
    createUnusedAnnotations(declarations, used, holder)
}

private fun annotateUnusedParameters(function: ZigFnDecl, holder: AnnotationHolder) {
    val declarations = mutableMapOf<String, DeclarationInfo>()
    val used = mutableSetOf<String>()
    val body = function.body ?: return
    for (parameter in function.parameters) {
        findUsedDeclarations(parameter, declarations, used)
        val identifier = parameter.nameIdentifier ?: continue
        if (identifier.textMatches("_")) continue
        val name = parseZigIdentifier(identifier) ?: continue
        declarations.putIfAbsent(name, DeclarationInfo(identifier, "function parameter"))
    }
    findUsedDeclarations(function.returnTypeElement, declarations, used)
    findUsedDeclarations(body, declarations, used)
    createUnusedAnnotations(declarations, used, holder)
}

private fun annotateUnusedCaptures(payload: ZigPayload?, bodies: List<ZigElement>, holder: AnnotationHolder) {
    if (payload == null || bodies.isEmpty()) return
    val declarations = mutableMapOf<String, DeclarationInfo>()
    val used = mutableSetOf<String>()
    for (item in payload.items) {
        val identifier = item.nameIdentifier ?: continue
        if (identifier.textMatches("_")) continue
        val name = parseZigIdentifier(identifier) ?: continue
        declarations.putIfAbsent(name, DeclarationInfo(identifier, "capture"))
    }
    for (body in bodies) {
        findUsedDeclarations(body, declarations, used)
    }
    createUnusedAnnotations(declarations, used, holder)
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

class ZigUndeclaredLabelAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        annotateUndeclaredLabel(element, holder)
    }
}

private fun annotateUndeclaredLabel(element: PsiElement, holder: AnnotationHolder) {
    if (element !is ZigLabelRef) return
    if (resolveReferences(element).any()) return
    val identifier = element.identifierElement
    val name = parseZigIdentifier(identifier) ?: return
    val message = buildString {
        append("Label not found: '")
        append(name)
        append("'")
    }
    holder.newAnnotation(HighlightSeverity.ERROR, message)
        // TODO: .problemGroup {}
        .range(identifier)
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
        is ZigBlock -> element.statements.flatMap { ZigBlock.statementToVarDecls(it) }
        is ZigFor -> element.payload?.items ?: return
        is ZigSwitchProng -> element.payload?.items ?: return
        else -> return
    }
    val nameToFirstDecl = mutableMapOf<String, ZigNameIdentifierOwner>()
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
    val namedElement = element as? ZigNameIdentifierOwner ?: return
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

        is ZigVarDecl, is ZigFnDecl -> "declaration"

        else -> return
    }
    val containingScope = when (namedElement) {
        is ZigFnParam -> namedElement.containingFunction
        is ZigPayloadItem -> namedElement.containingPayload.parent
        is ZigVarProto -> namedElement.containingAssignDestructure.parent
        is ZigVarDecl, is ZigFnDecl -> namedElement.parent
        else -> throw IllegalStateException()
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
                if (kind == "declaration") {
                    return
                }
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
