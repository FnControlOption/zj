package io.github.fncontroloption.zig

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.siblings
import com.intellij.util.IncorrectOperationException
import io.github.fncontroloption.zig.psi2.*

/** @see com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry.getReferencesFromProviders */
fun resolveReferences(element: PsiElement): Sequence<PsiElement> {
    val cached = CachedValuesManager.getCachedValue(element) {
        val targets = element.references.asSequence().flatMap { reference ->
            if (reference is PsiPolyVariantReference) {
                val results = reference.multiResolve(false)
                results.asSequence().mapNotNull { it.element }
            } else {
                val target = reference.resolve()
                if (target != null) sequenceOf(target) else emptySequence()
            }
        }
        CachedValueProvider.Result.create(targets.toSet(), PsiModificationTracker.MODIFICATION_COUNT)
    }
    return cached.asSequence()
}

class ZigIdentifierReference(element: ZigIdentifier) :
    PsiReferenceBase<ZigIdentifier>(element, TextRange(0, element.textLength), true) {

    override fun resolve(): PsiElement? {
        if (element.textMatches("_")) return null
        val name = parseZigIdentifier(myElement) ?: return null
        return identifierReferenceCandidates(myElement).firstOrNull { it.name == name }
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.identifierElement, newElementName)

    override fun getVariants(): Array<out LookupElement> {
        val variants = mutableSetOf<LookupElement>()
        for (candidate in identifierReferenceCandidates(myElement)) {
            val name = candidate.name ?: continue
            val values = ZigAnalyzer.resolveValuesFromReferenceTo(candidate)
            val types = values.map { it.typeOf }.toSet()
            when (val type = types.singleOrNull()) {
                is ZigFunctionType -> {
                    // TODO: handle varargs
                    val (paramNames, paramTypes) = getFunctionParamNamesAndTypes(type)
                    val isMethod = when (val receiverType = getFunctionReceiverType(paramTypes)) {
                        is ZigContainerType -> receiverType.element == candidate.parent
                        else -> false
                    }
                    variants.add(
                        buildLookupElement(candidate, name, ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING)
                            .withIcon(if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function)
                            .withTailText(getFunctionTailText(paramNames, paramTypes))
                            .withTypeText(getFunctionTypeText(type))
                            .withInsertHandler(FunctionInsertHandler(paramNames.isNotEmpty()))
                    )
                }

                else -> {
                    variants.add(createLookupElementWithType(candidate, name, type, ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING))
                }
            }
        }
        return variants.toTypedArray()
    }
}

class ZigTestReference(element: ZigTestDecl, val nameElement: PsiElement) :
    PsiReferenceBase<ZigTestDecl>(element, nameElement.textRangeInParent, true) {

    override fun resolve(): PsiElement? {
        val name = parseZigIdentifier(nameElement) ?: return null
        return identifierReferenceCandidates(nameElement).firstOrNull { it.name == name }
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.nameElement, newElementName)

    override fun getVariants(): Array<out LookupElement> {
        val variants = mutableSetOf<LookupElement>()
        for (candidate in identifierReferenceCandidates(nameElement)) {
            val name = candidate.name ?: continue
            when (candidate) {
                is ZigFnDecl -> {}
                is ZigVarDecl -> if (candidate.isLocal) continue
                else -> continue
            }
            val values = ZigAnalyzer.resolveValuesFromReferenceTo(candidate)
            val types = values.map { it.typeOf }.toSet()
            val icon = when (val type = types.singleOrNull()) {
                is ZigFunctionType -> {
                    val (_, paramTypes) = getFunctionParamNamesAndTypes(type)
                    val isMethod = when (val receiverType = getFunctionReceiverType(paramTypes)) {
                        is ZigContainerType -> receiverType.element == candidate.parent
                        else -> false
                    }
                    if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function
                }
                null -> null
                else -> candidate.getIcon(0)
            }
            variants.add(buildLookupElement(candidate, name, ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING).withIcon(icon))
        }
        return variants.toTypedArray()
    }
}

class ZigLabelReference(element: ZigLabelRef) :
    PsiReferenceBase<ZigLabelRef>(element, element.identifierElement.textRangeInParent, true) {

    override fun resolve(): PsiElement? {
        val name = parseZigIdentifier(myElement.identifierElement) ?: return null
        var parent = myElement.parent
        while (parent != null) {
            if (parent is ZigLabelable) {
                val labelDecl = parent.labelDecl
                if (labelDecl != null && parseZigIdentifier(labelDecl.identifierElement) == name) {
                    return labelDecl
                }
            }
            parent = parent.parent
        }
        return null
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.identifierElement, newElementName)

    override fun getVariants(): Array<out LookupElement> {
        val variants = mutableSetOf<LookupElement>()
        var parent = myElement.parent
        while (parent != null) {
            if (parent is ZigLabelable) {
                val labelDecl = parent.labelDecl
                val label = parseZigIdentifier(labelDecl?.identifierElement)
                if (labelDecl != null && label != null) {
                    variants.add(buildLookupElement(labelDecl, label, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
                }
            }
            parent = parent.parent
        }
        return variants.toTypedArray()
    }
}

class ZigImportReference(element: ZigStringLiteral) :
    PsiReferenceBase<ZigStringLiteral>(element, TextRange(1, element.textLength - 1), true) {

    override fun resolve(): PsiElement? {
        val text = myElement?.value ?: return null
        if (!text.endsWith(".zig") && !text.endsWith(".zon")) {
            return when (text) {
                "std" -> ZigAnalyzer.findStdFile(myElement, "std.zig")
                "builtin" -> null // TODO: get builtin text from `zig build-exe --show-builtin`
                else -> null // TODO: resolve imported modules
            }
        }
        val parts = text.split("/")
        val fileName = parts.lastOrNull() ?: return null
        var directory = myElement.containingFile?.originalFile?.containingDirectory ?: return null
        parts.subList(0, parts.size - 1).forEach { dirName ->
            if (dirName == "..") {
                directory = directory.parentDirectory ?: return null
            } else if (dirName != ".") {
                directory = directory.findSubdirectory(dirName) ?: return null
            }
        }
        return directory.findFile(fileName)
    }

    override fun handleElementRename(newElementName: String): PsiElement {
        val oldValue = myElement.value ?: throw IncorrectOperationException()
        val oldParts = oldValue.split("/")
        if (oldParts.isEmpty()) {
            throw IncorrectOperationException()
        }
        val newValue = buildString {
            for (i in 0..<oldParts.lastIndex) {
                append(oldParts[i])
                append('/')
            }
            append(newElementName)
        }
        return myElement.replace(createZigStringLiteral(myElement.project, newValue))
    }

    override fun bindToElement(element: PsiElement): PsiElement {
        val psiFile = element as? PsiFile ?: throw IncorrectOperationException()
        val importedFile = psiFile.virtualFile ?: throw IncorrectOperationException()
        val containingDir = myElement.containingFile?.originalFile?.containingDirectory?.virtualFile
            ?: throw IncorrectOperationException()
        val relativePath = containingDir.toNioPath().relativize(importedFile.toNioPath())
        val newValue = nioPathToUnixString(relativePath)
        myElement.replace(createZigStringLiteral(myElement.project, newValue))
        return element
    }
}

class ZigFieldAccessReference(element: ZigFieldAccess, val identifier: PsiElement) :
    PsiPolyVariantReferenceBase<ZigFieldAccess>(element, identifier.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val leftValues = ZigAnalyzer.resolveValues(myElement.leftOperand)
        val name = parseZigIdentifier(identifier) ?: return emptyArray()
        val results = mutableSetOf<ResolveResult>()
        for ((containerType, _) in getContainers(leftValues)) {
            val member = containerType.members.firstOrNull {
                it is PsiNameIdentifierOwner && it.name == name
            }
            if (member != null) {
                results.add(PsiElementResolveResult(member))
            }
        }
        return results.toTypedArray()
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.identifierElement, newElementName)

    override fun getVariants(): Array<out LookupElement> {
        val leftValues = ZigAnalyzer.resolveValues(myElement.leftOperand)
        val originalFile = myElement.containingFile
        val variants = mutableSetOf<LookupElement>()
        for (left in leftValues) {
            val typeOfLeft = left.typeOf
            when (typeOfLeft) {
                is ZigOptionalType -> {
                    variants.add(LookupElementBuilder.create("?").withTypeText(typeOfLeft.child.toPlainText()))
                }
                is ZigPointerType if typeOfLeft.size == ZigPointerSize.ONE -> {
                    variants.add(LookupElementBuilder.create("*").withTypeText(typeOfLeft.child.toPlainText()))
                }
                else -> {}
            }
            for (fieldName in ZigAnalyzer.getBuiltinFields(typeOfLeft)) {
                val fieldType = ZigAnalyzer.getBuiltinFieldType(typeOfLeft, fieldName)
                variants.add(
                    LookupElementBuilder.create(renderZigIdentifier(fieldName, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
                        .withLookupString(fieldName)
                        .withLookupString(escapeZigIdentifier(fieldName))
                        .withIcon(AllIcons.Nodes.Field)
                        .withTypeText(fieldType?.toPlainText())
                )
            }
        }
        for ((containerType, isInstance) in getContainers(leftValues)) {
            for (member in containerType.getNamedMembersVisibleFrom(originalFile)) {
                val name = member.name ?: continue
                if (member is ZigContainerField) {
                    if (containerType.containerKind == ZigContainerKind.ENUM) {
                        if (isInstance || member.nameIdentifier?.textMatches("_") == true) {
                            continue
                        }
                        variants.add(createLookupElementWithType(member, name, containerType, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
                    } else if (isInstance) {
                        val initialValues = ZigAnalyzer.resolveValuesFromReferenceTo(member)
                        val values = ZigAnalyzer.substituteTypeParams(initialValues, containerType.typeParams)
                        val types = values.map { it.typeOf }.toSet()
                        variants.add(createLookupElementWithType(member, name, types.singleOrNull(), ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
                    } else if (containerType is ZigContainerType && containerType.element.isTaggedUnion) {
                        val types = ZigAnalyzer.resolveUnionTagTypes(containerType)
                        variants.add(createLookupElementWithType(member, name, types.singleOrNull(), ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
                    }
                    continue
                }
                val initialValues = ZigAnalyzer.resolveValuesFromReferenceTo(member)
                val values = ZigAnalyzer.substituteTypeParams(initialValues, containerType.typeParams)
                val types = values.map { it.typeOf }.toSet()
                when (val type = types.singleOrNull()) {
                    is ZigFunctionType -> {
                        // TODO: handle varargs
                        var (paramNames, paramTypes) = getFunctionParamNamesAndTypes(type, containerType.typeParams)
                        val isMethod = getFunctionReceiverType(paramTypes) == containerType
                        if (isInstance) {
                            if (!isMethod) {
                                continue
                            }
                            paramNames = paramNames.subList(1, paramNames.size)
                            paramTypes = paramTypes.subList(1, paramTypes.size)
                        }
                        variants.add(
                            buildLookupElement(member, name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)
                                .withIcon(if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function)
                                .withTailText(getFunctionTailText(paramNames, paramTypes))
                                .withTypeText(getFunctionTypeText(type))
                                .withInsertHandler(FunctionInsertHandler(paramNames.isNotEmpty()))
                        )
                    }

                    else -> {
                        if (isInstance) {
                            continue
                        }
                        variants.add(createLookupElementWithType(member, name, type, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
                    }
                }
            }
        }
        return variants.toTypedArray()
    }

    private fun getContainers(leftValues: Set<ZigValue>): Sequence<Pair<IZigContainerType, Boolean>> {
        return leftValues.asSequence().mapNotNull { left ->
            when (left) {
                is IZigContainerType -> Pair(left, false)
                else -> when (val typeOfLeft = left.typeOf) {
                    is IZigContainerType -> Pair(typeOfLeft, true)
                    is ZigPointerType -> when (typeOfLeft.size) {
                        ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                            is IZigContainerType -> Pair(pointerChild, true)
                            else -> null
                        }
                        else -> null
                    }
                    else -> null
                }
            }
        }
    }
}

class ZigEnumLiteralReference(element: ZigEnumLiteral, val identifier: PsiElement) :
    PsiPolyVariantReferenceBase<ZigEnumLiteral>(element, identifier.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val types = ZigAnalyzer.inferEnumLiteralTypes(myElement)
        val name = parseZigIdentifier(identifier) ?: return emptyArray()
        val results = mutableSetOf<ResolveResult>()
        for ((_, containerType) in types) {
            val member = containerType.members.firstOrNull {
                it is PsiNameIdentifierOwner && it.name == name
            }
            if (member != null) {
                results.add(PsiElementResolveResult(member))
            }
        }
        return results.toTypedArray()
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.identifierElement, newElementName)

    override fun getVariants(): Array<out LookupElement> {
        val parentElement = myElement.parent
        if (parentElement is ZigListInitItem) {
            val types = ZigAnalyzer.resolveOrInferStructInitTypes(parentElement.containingListInit).toList()
            if (types.isNotEmpty()) {
                val variants = mutableSetOf<LookupElement>()
                for (containerType in types) {
                    variants.addAll(getFieldInitVariants(containerType))
                }
                return variants.toTypedArray()
            }
        }
        val originalFile = myElement.containingFile
        val variants = mutableSetOf<LookupElement>()
        for ((originalType, containerType) in ZigAnalyzer.inferEnumLiteralTypes(myElement)) {
            for (member in containerType.getNamedMembersVisibleFrom(originalFile)) {
                val name = member.name ?: continue
                if (member is ZigContainerField) {
                    if (containerType.containerKind == ZigContainerKind.ENUM) {
                        if (member.nameIdentifier?.textMatches("_") == true) {
                            continue
                        }
                        variants.add(createLookupElementWithType(member, name, containerType, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
                    } else if (containerType is ZigContainerType && containerType.element.isTaggedUnion) {
                        val initialValues = ZigAnalyzer.resolveValuesFromReferenceTo(member)
                        val values = ZigAnalyzer.substituteTypeParams(initialValues, containerType.typeParams)
                        val types = values.map { it.typeOf }.toSet()
                        val type = types.singleOrNull()
                        if (type == ZigVoidType) {
                            variants.add(createLookupElementWithType(member, name, containerType, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
                        } else {
                            // TODO: add specialized completion item for non-void tagged union field
                        }
                    }
                    continue
                }
                val initialValues = ZigAnalyzer.resolveValuesFromReferenceTo(member)
                val values = ZigAnalyzer.substituteTypeParams(initialValues, containerType.typeParams)
                val types = values.map { it.typeOf }.toSet()
                when (val type = types.singleOrNull()) {
                    is ZigFunctionType -> {
                        // TODO: handle varargs
                        // TODO: check if return type can coerce to original type
                        val (paramNames, paramTypes) = getFunctionParamNamesAndTypes(type, containerType.typeParams)
                        val isMethod = getFunctionReceiverType(paramTypes) == containerType
                        variants.add(
                            buildLookupElement(member, name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)
                                .withIcon(if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function)
                                .withTailText(getFunctionTailText(paramNames, paramTypes))
                                .withTypeText(getFunctionTypeText(type))
                                .withInsertHandler(FunctionInsertHandler(paramNames.isNotEmpty()))
                        )
                    }

                    else -> {
                        // TODO: check if decl type can coerce to original type
                        variants.add(createLookupElementWithType(member, name, type, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
                    }
                }
            }
        }
        return variants.toTypedArray()
    }
}

class ZigListInitItemReference(element: ZigListInitItem, val identifier: PsiElement) :
    PsiPolyVariantReferenceBase<ZigListInitItem>(element, identifier.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val types = ZigAnalyzer.resolveOrInferStructInitTypes(myElement.containingListInit)
        val name = parseZigIdentifier(identifier) ?: return emptyArray()
        val results = mutableSetOf<ResolveResult>()
        for (containerType in types) {
            val member = containerType.element.members.firstOrNull {
                it is ZigContainerField && it.name == name
            }
            if (member != null) {
                results.add(PsiElementResolveResult(member))
            }
        }
        return results.toTypedArray()
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.identifierElement, newElementName)

    override fun getVariants(): Array<out LookupElement> {
        val types = ZigAnalyzer.resolveOrInferStructInitTypes(myElement.containingListInit)
        val variants = mutableSetOf<LookupElement>()
        for (containerType in types) {
            variants.addAll(getFieldInitVariants(containerType))
        }
        return variants.toTypedArray()
    }
}

private fun identifierReferenceCandidates(element: PsiElement): Sequence<ZigNamedReferenceable> {
    var it: Iterator<ZigNamedReferenceable>? = null
    var element: PsiElement? = element
    return generateSequence {
        while (element != null) {
            if (it?.hasNext() == true) {
                val candidate = it!!.next()
                if (candidate.nameIdentifier?.textMatches("_") == true) {
                    continue
                }
                return@generateSequence candidate
            }
            val child = element!!
            val parent = child.parent
            if (parent == null) {
                element = null
                break
            }
            val sequence = when (parent) {
                is ZigContainer -> parent.members.filterIsInstance<ZigNamedContainerDecl>()

                is ZigFnDecl -> parent.parameters.takeWhile { it !== child }

                is ZigBlock -> child.siblings(forward = false, withSelf = false).flatMap { statement ->
                    when (statement) {
                        is ZigVarDecl -> sequenceOf(statement)
                        is ZigAssignDestructure -> statement.variables.filterIsInstance<ZigVarProto>()
                        else -> emptySequence()
                    }
                }

                is ZigThenElement -> parent.payload?.items

                is ZigElseElement -> parent.payload?.items

                is ZigSwitchProng if child !is ZigSwitchItem -> parent.payload?.items

                is ZigCatch if child !== parent.leftOperand -> parent.payload?.items

                else -> null
            }
            it = sequence?.iterator()
            element = parent
        }
        null
    }
}

internal fun getFunctionParamNamesAndTypes(
    functionType: ZigFunctionType,
    typeParams: Map<ZigFnParam, Set<ZigValue>> = functionType.typeParams,
): Pair<List<String?>, List<ZigValue?>> {
    return functionType.element.parameters.map { param ->
        param.name to param.typeElement?.innerElement?.let {
            val initialValues = ZigAnalyzer.resolveValues(it)
            val values = ZigAnalyzer.substituteTypeParams(initialValues, typeParams)
            values.singleOrNull()
        }
    }.unzip()
}

private fun getFunctionReceiverType(paramTypes: List<ZigValue?>): ZigValue? {
    val firstParamType = paramTypes.firstOrNull() ?: return null
    if (firstParamType is ZigPointerType && firstParamType.size == ZigPointerSize.ONE) {
        return firstParamType.child
    }
    return firstParamType
}

private fun getFunctionTailText(paramNames: List<String?>, paramTypes: List<ZigValue?>): String {
    return buildString {
        append('(')
        for (i in paramNames.indices) {
            if (i > 0) {
                append(", ")
            }
            append(getParameterText(paramNames[i], paramTypes[i]))
        }
        append(')')
    }
}

internal fun getParameterText(name: String?, type: ZigValue?): String {
    return buildString {
        if (name != null) {
            append(name)
            append(": ")
        }
        if (type != null) {
            append(type.toPlainText())
        } else {
            append('_')
        }
    }
}

private fun getFunctionTypeText(functionType: ZigFunctionType): String? {
    val returnType = functionType.element.returnTypeElement?.innerElement?.let {
        val initialValues = ZigAnalyzer.resolveValues(it)
        val values = ZigAnalyzer.substituteTypeParams(initialValues, functionType.typeParams)
        values.singleOrNull()
    }
    return returnType?.toPlainText()?.let {
        if (functionType.element.bangTokenElement != null) "!$it" else it
    }
}

private class FunctionInsertHandler(val hasParams: Boolean) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val project = context.project
        val editor = context.editor
        val document = editor.document
        val tailOffset = context.tailOffset
        val text = document.charsSequence
        when (context.completionChar) {
            Lookup.NORMAL_SELECT_CHAR -> {
                document.insertString(tailOffset, "()")
                if (hasParams) {
                    editor.caretModel.moveToOffset(tailOffset + 1)
                } else {
                    editor.caretModel.moveToOffset(tailOffset + 2)
                }
                AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
            }

            Lookup.REPLACE_SELECT_CHAR,
            Lookup.AUTO_INSERT_SELECT_CHAR -> {
                val tail = text.subSequence(tailOffset, text.length)
                var foundCallArgs = false
                if (tail.startsWith("()")) {
                    // no-op
                } else if (tail.startsWith('(')) {
                    foundCallArgs = true
                } else {
                    document.insertString(tailOffset, "()")
                }
                if (hasParams || foundCallArgs) {
                    editor.caretModel.moveToOffset(tailOffset + 1)
                } else {
                    editor.caretModel.moveToOffset(tailOffset + 2)
                }
                AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
            }

            // TODO
            else -> {}
        }
    }
}

private object FieldInitInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val editor = context.editor
        val document = editor.document
        val tailOffset = context.tailOffset
        val text = document.charsSequence
        when (context.completionChar) {
            Lookup.NORMAL_SELECT_CHAR -> {
                document.insertString(tailOffset, " = ")
                editor.caretModel.moveToOffset(tailOffset + 3)
            }

            Lookup.REPLACE_SELECT_CHAR,
            Lookup.AUTO_INSERT_SELECT_CHAR -> {
                val tail = text.subSequence(tailOffset, text.length)
                if (tail.startsWith(" = ")) {
                    // no-op
                } else if (tail.startsWith(" =")) {
                    document.insertString(tailOffset + 2, " ")
                } else if (tail.startsWith(' ')) {
                    document.insertString(tailOffset + 1, "= ")
                } else {
                    document.insertString(tailOffset, " = ")
                }
                editor.caretModel.moveToOffset(tailOffset + 3)
            }

            // TODO
            else -> {}
        }
    }
}

private fun getFieldInitVariants(containerType: ZigContainerType): Sequence<LookupElement> {
    if (containerType.element.containerKind == ZigContainerKind.ENUM) {
        return emptySequence()
    }
    return containerType.element.members.filterIsInstance<ZigContainerField>().mapNotNull { member ->
        val name = member.name ?: return@mapNotNull null
        val initialValues = ZigAnalyzer.resolveValuesFromReferenceTo(member)
        val values = ZigAnalyzer.substituteTypeParams(initialValues, containerType.typeParams)
        val types = values.map { it.typeOf }.toSet()
        buildLookupElement(member, name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)
            .withIcon(AllIcons.Nodes.Field)
            .withTailText(" =")
            .withTypeText(types.singleOrNull()?.toPlainText())
            .withInsertHandler(FieldInitInsertHandler)
    }
}

private fun createLookupElementWithType(
    element: ZigNamedElement,
    name: String,
    type: ZigValue?,
    quote: ZigIdQuoteBehavior,
): LookupElement {
    return if (type != null) {
        buildLookupElement(element, name, quote)
            .withIcon(element.getIcon(0))
            .withTypeText(type.toPlainText())
    } else {
        buildLookupElement(element, name, quote)
    }
}

private fun buildLookupElement(
    element: ZigNamedElement,
    name: String,
    quote: ZigIdQuoteBehavior,
): LookupElementBuilder =
    LookupElementBuilder.create(element, renderZigIdentifier(name, quote))
        .withLookupString(name)
        .withLookupString(escapeZigIdentifier(name))
