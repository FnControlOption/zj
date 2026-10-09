package io.github.fncontroloption.zig

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
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
        CachedValueProvider.Result.create(
            targets.toSet(),
            PsiModificationTracker.MODIFICATION_COUNT,
            ZigBuiltinService.getModificationTracker(element.project),
            ZigBuiltinFunctionsService.getModificationTracker(element.project),
            ZigModulesProjectService.getModificationTracker(element.project),
        )
    }
    return cached.asSequence()
}

class ZigIdentifierReference(element: ZigIdentifier) :
    PsiPolyVariantReferenceBase<ZigIdentifier>(element, TextRange(0, element.textLength), true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        if (myElement.textMatches("_")) return emptyArray()
        val name = parseZigIdentifier(myElement) ?: return emptyArray()
        return identifierReferenceCandidates(myElement)
            .filter { it.name == name }
            .map { PsiElementResolveResult(it) }
            .toList()
            .toTypedArray()
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(
            myElement,
            myElement.identifierElement,
            newElementName,
            ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING,
        )

    override fun getVariants(): Array<out LookupElement> {
        val variants = mutableSetOf<LookupElement>()
        for (candidate in identifierReferenceCandidates(myElement)) {
            val name = candidate.name ?: continue
            when (val type = ZigAnalyzer.resolveValueFromReferenceTo(candidate).typeOf) {
                is ZigFunctionType -> {
                    val (params, paramTypes) = getFunctionParamTypes(type)
                    val hasVarargs = type.element.hasVarargs
                    val isMethod = when (val receiverType = getFunctionReceiverType(paramTypes)) {
                        is ZigContainerType -> receiverType.element == candidate.parent
                        else -> false
                    }
                    variants.add(
                        buildLookupElement(candidate, name, ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING)
                            .withIcon(if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function)
                            .withTailText(getFunctionTailText(params, paramTypes, hasVarargs))
                            .withTypeText(getFunctionTypeText(type))
                            .withInsertHandler(FunctionInsertHandler(params.isNotEmpty() || hasVarargs))
                    )
                }

                else -> {
                    variants.add(
                        createLookupElementWithType(
                            candidate,
                            name,
                            type,
                            ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING,
                        )
                    )
                }
            }
        }
        return variants.toTypedArray()
    }
}

class ZigTestReference(element: ZigTestDecl, val nameElement: PsiElement) :
    PsiPolyVariantReferenceBase<ZigTestDecl>(element, nameElement.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val name = parseZigIdentifier(nameElement) ?: return emptyArray()
        return identifierReferenceCandidates(nameElement)
            .filter { it.name == name }
            .map { PsiElementResolveResult(it) }
            .toList()
            .toTypedArray()
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(
            myElement,
            myElement.nameElement,
            newElementName,
            ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING,
        )

    override fun getVariants(): Array<out LookupElement> {
        val variants = mutableSetOf<LookupElement>()
        for (candidate in identifierReferenceCandidates(nameElement)) {
            val name = candidate.name ?: continue
            when (candidate) {
                is ZigFnDecl -> {}
                is ZigVarDecl -> if (candidate.isLocal) continue
                else -> continue
            }
            val icon = when (val type = ZigAnalyzer.resolveValueFromReferenceTo(candidate).typeOf) {
                is ZigFunctionType -> {
                    val (_, paramTypes) = getFunctionParamTypes(type)
                    val isMethod = when (val receiverType = getFunctionReceiverType(paramTypes)) {
                        is ZigContainerType -> receiverType.element == candidate.parent
                        else -> false
                    }
                    if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function
                }
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
        replaceZigIdentifierElement(
            myElement,
            myElement.identifierElement,
            newElementName,
            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
        )

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

sealed class ZigFileReference(element: ZigStringLiteral) :
    PsiPolyVariantReferenceBase<ZigStringLiteral>(element, TextRange(1, element.textLength - 1), true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val text = myElement?.value ?: return emptyArray()
        return multiResolve(text)
    }

    protected open fun multiResolve(text: String): Array<out ResolveResult> {
        val parts = text.split("/")
        val fileName = parts.lastOrNull() ?: return emptyArray()
        var directory = myElement.containingFile?.originalFile?.containingDirectory ?: return emptyArray()
        for (dirName in parts.subList(0, parts.size - 1)) {
            if (dirName == "..") {
                directory = directory.parentDirectory ?: return emptyArray()
            } else if (dirName != ".") {
                directory = directory.findSubdirectory(dirName) ?: return emptyArray()
            }
        }
        val file = directory.findFile(fileName) ?: return emptyArray()
        return arrayOf(PsiElementResolveResult(file))
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

class ZigEmbedFileReference(element: ZigStringLiteral) : ZigFileReference(element)

class ZigImportReference(element: ZigStringLiteral) : ZigFileReference(element) {
    override fun multiResolve(text: String): Array<out ResolveResult> {
        if (text.endsWith(".zig") || text.endsWith(".zon")) {
            return super.multiResolve(text)
        }
        return when (text) {
            "std" -> {
                val file = ZigAnalyzer.findStdFile(myElement, "std.zig") ?: return emptyArray()
                arrayOf(PsiElementResolveResult(file))
            }
            "builtin" -> {
                val file = ZigAnalyzer.findBuiltinFile(myElement) ?: return emptyArray()
                arrayOf(PsiElementResolveResult(file))
            }
            else -> {
                val project = myElement.project
                val virtualFile = myElement.containingFile?.originalFile?.virtualFile ?: return emptyArray()
                val psiManager = PsiManager.getInstance(project)
                val modulesService = project.service<ZigModulesProjectService>()
                val results = mutableSetOf<ResolveResult>()
                for (moduleIndex in modulesService.getFileModules(virtualFile)) {
                    val module = modulesService.modules[moduleIndex] ?: continue
                    val importIndex = module.importTable[text] ?: continue
                    val importModule = modulesService.modules[importIndex] ?: continue
                    val importFile = modulesService.getRootSourceVirtualFile(importModule) ?: continue
                    val psiFile = psiManager.findFile(importFile) ?: continue
                    results.add(PsiElementResolveResult(psiFile))
                }
                results.toTypedArray()
            }
        }
    }
}

class ZigFieldAccessReference(element: ZigFieldAccess, val identifier: PsiElement) :
    PsiPolyVariantReferenceBase<ZigFieldAccess>(element, identifier.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val leftValue = ZigAnalyzer.resolveValue(myElement.leftOperand)
        val name = parseZigIdentifier(identifier) ?: return emptyArray()
        val results = mutableSetOf<ResolveResult>()
        for ((containerType, _) in getContainers(leftValue)) {
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
        replaceZigIdentifierElement(
            myElement,
            myElement.identifierElement,
            newElementName,
            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
        )

    override fun getVariants(): Array<out LookupElement> {
        val leftValue = ZigAnalyzer.resolveValue(myElement.leftOperand)
        val originalFile = myElement.containingFile
        val variants = mutableSetOf<LookupElement>()
        leftValue.forEachValue { left ->
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
        for ((containerType, isInstance) in getContainers(leftValue)) {
            for (member in containerType.getNamedMembersVisibleFrom(originalFile)) {
                val name = member.name ?: continue
                if (member is ZigContainerField) {
                    if (containerType.containerKind == ZigContainerKind.ENUM) {
                        if (isInstance || member.nameIdentifier?.textMatches("_") == true) {
                            continue
                        }
                        variants.add(
                            createLookupElementWithType(
                                member,
                                name,
                                containerType,
                                ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                            )
                        )
                    } else if (isInstance) {
                        val initialValue = ZigAnalyzer.resolveValueFromReferenceTo(member)
                        val value = ZigAnalyzer.substituteTypeParams(initialValue, containerType.typeParams)
                        variants.add(
                            createLookupElementWithType(
                                member,
                                name,
                                value.typeOf,
                                ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                            )
                        )
                    } else if (containerType.isTaggedUnion) {
                        val type = ZigAnalyzer.resolveUnionTagType(containerType)
                        variants.add(
                            createLookupElementWithType(
                                member,
                                name,
                                type,
                                ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                            )
                        )
                    }
                    continue
                }
                val initialValue = ZigAnalyzer.resolveValueFromReferenceTo(member)
                val value = ZigAnalyzer.substituteTypeParams(initialValue, containerType.typeParams)
                when (val type = value.typeOf) {
                    is ZigFunctionType -> {
                        var (params, paramTypes) = getFunctionParamTypes(type, containerType.typeParams)
                        val hasVarargs = type.element.hasVarargs
                        val isMethod = getFunctionReceiverType(paramTypes) == containerType
                        if (isInstance) {
                            if (!isMethod) {
                                continue
                            }
                            params = params.subList(1, params.size)
                            paramTypes = paramTypes.subList(1, paramTypes.size)
                        }
                        variants.add(
                            buildLookupElement(member, name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)
                                .withIcon(if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function)
                                .withTailText(getFunctionTailText(params, paramTypes, hasVarargs))
                                .withTypeText(getFunctionTypeText(type))
                                .withInsertHandler(FunctionInsertHandler(params.isNotEmpty() || hasVarargs))
                        )
                    }

                    else -> {
                        if (isInstance) {
                            continue
                        }
                        variants.add(
                            createLookupElementWithType(
                                member,
                                name,
                                type,
                                ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                            )
                        )
                    }
                }
            }
        }
        return variants.toTypedArray()
    }

    private fun getContainers(leftValue: ZigValue): Set<Pair<IZigContainerType, Boolean>> {
        val result = mutableSetOf<Pair<IZigContainerType, Boolean>>()
        leftValue.forEachValue { left ->
            when (left) {
                is IZigContainerType -> result += Pair(left, false)
                else -> when (val typeOfLeft = left.typeOf) {
                    is IZigContainerType -> result += Pair(typeOfLeft, true)
                    is ZigPointerType -> when (typeOfLeft.size) {
                        ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                            is IZigContainerType -> result += Pair(pointerChild, true)
                            else -> {}
                        }
                        else -> {}
                    }
                    else -> {}
                }
            }
        }
        return result
    }
}

class ZigEnumLiteralReference(element: ZigEnumLiteral, val identifier: PsiElement) :
    PsiPolyVariantReferenceBase<ZigEnumLiteral>(element, identifier.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val resultType = ZigAnalyzer.inferResultType(myElement)
        val name = parseZigIdentifier(identifier) ?: return emptyArray()
        val results = mutableSetOf<ResolveResult>()
        resultType.forEachValue { originalType ->
            val containerType = ZigAnalyzer.findEnumLiteralContainerType(originalType) ?: return@forEachValue
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
        replaceZigIdentifierElement(
            myElement,
            myElement.identifierElement,
            newElementName,
            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
        )

    override fun getVariants(): Array<out LookupElement> {
        val parentElement = myElement.parent
        if (parentElement is ZigListInitItem && !parentElement.isStructField) {
            val resultType = ZigAnalyzer.resolveOrInferListInitType(parentElement.containingListInit)
            var foundContainer = false
            val variants = mutableSetOf<LookupElement>()
            resultType.forEachValue { containerType ->
                if (containerType is IZigContainerType) {
                    foundContainer = true
                    variants.addAll(getFieldInitVariants(containerType))
                }
            }
            if (foundContainer) {
                return variants.toTypedArray()
            }
        }
        val originalFile = myElement.containingFile
        val variants = mutableSetOf<LookupElement>()
        ZigAnalyzer.inferResultType(myElement).forEachValue { originalType ->
            val containerType = ZigAnalyzer.findEnumLiteralContainerType(originalType) ?: return@forEachValue
            for (member in containerType.getNamedMembersVisibleFrom(originalFile)) {
                val name = member.name ?: continue
                if (member is ZigContainerField) {
                    if (containerType.containerKind == ZigContainerKind.ENUM) {
                        if (member.nameIdentifier?.textMatches("_") == true) {
                            continue
                        }
                        variants.add(
                            createLookupElementWithType(
                                member,
                                name,
                                containerType,
                                ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                            )
                        )
                    } else if (containerType.isTaggedUnion) {
                        val initialValue = ZigAnalyzer.resolveValueFromReferenceTo(member)
                        val value = ZigAnalyzer.substituteTypeParams(initialValue, containerType.typeParams)
                        val type = value.typeOf
                        if (type == ZigVoidType) {
                            variants.add(
                                createLookupElementWithType(
                                    member,
                                    name,
                                    containerType,
                                    ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                                )
                            )
                        } else {
                            // TODO: add specialized completion item for non-void tagged union field
                        }
                    }
                    continue
                }
                val initialValue = ZigAnalyzer.resolveValueFromReferenceTo(member)
                val value = ZigAnalyzer.substituteTypeParams(initialValue, containerType.typeParams)
                when (val type = value.typeOf) {
                    is ZigFunctionType -> {
                        // TODO: check if return type can coerce to original type
                        val (params, paramTypes) = getFunctionParamTypes(type, containerType.typeParams)
                        val hasVarargs = type.element.hasVarargs
                        val isMethod = getFunctionReceiverType(paramTypes) == containerType
                        variants.add(
                            buildLookupElement(member, name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)
                                .withIcon(if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function)
                                .withTailText(getFunctionTailText(params, paramTypes, hasVarargs))
                                .withTypeText(getFunctionTypeText(type))
                                .withInsertHandler(FunctionInsertHandler(params.isNotEmpty() || hasVarargs))
                        )
                    }

                    else -> {
                        // TODO: check if decl type can coerce to original type
                        variants.add(
                            createLookupElementWithType(
                                member,
                                name,
                                type,
                                ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                            )
                        )
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
        val resultType = ZigAnalyzer.resolveOrInferListInitType(myElement.containingListInit)
        val name = parseZigIdentifier(identifier) ?: return emptyArray()
        val results = mutableSetOf<ResolveResult>()
        resultType.forEachValue { containerType ->
            if (containerType !is IZigContainerType) return@forEachValue
            val member = containerType.members.firstOrNull {
                it is ZigContainerField && it.name == name
            }
            if (member != null) {
                results.add(PsiElementResolveResult(member))
            }
        }
        return results.toTypedArray()
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(
            myElement,
            myElement.identifierElement,
            newElementName,
            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
        )

    override fun getVariants(): Array<out LookupElement> {
        val resultType = ZigAnalyzer.resolveOrInferListInitType(myElement.containingListInit)
        val variants = mutableSetOf<LookupElement>()
        resultType.forEachValue { containerType ->
            if (containerType !is IZigContainerType) return@forEachValue
            variants.addAll(getFieldInitVariants(containerType))
        }
        return variants.toTypedArray()
    }
}

class ZigBuiltinCallReference(element: ZigBuiltinCall) :
    PsiReferenceBase<ZigBuiltinCall>(element, element.builtinElement.textRangeInParent, true) {

    override fun resolve(): PsiElement? {
        val name = myElement.builtinElement.text
        val builtin = ZigBuiltinFunctionsService.getBuiltinFnProto(myElement.project, name) ?: return null
        return builtin
    }

    override fun getVariants(): Array<out LookupElement> {
        val variants = mutableSetOf<LookupElement>()
        for (builtin in ZigBuiltinFunctionsService.getAll(myElement.project)) {
            val name = builtin.builtinElement.text
            val (params, paramTypes) = getFunctionParamTypes(builtin, emptyMap())
            val hasVarargs = builtin.hasVarargs
            variants.add(
                LookupElementBuilder.create(builtin, name)
                    .withIcon(AllIcons.Nodes.Function)
                    .withTailText(getFunctionTailText(params, paramTypes, hasVarargs))
                    .withTypeText(getFunctionTypeText(builtin, emptyMap()))
                    .withInsertHandler(FunctionInsertHandler(params.isNotEmpty() || hasVarargs))
            )
        }
        return variants.toTypedArray()
    }
}

internal fun identifierReferenceCandidates(element: PsiElement): Sequence<ZigNameIdentifierOwner> {
    var it: Iterator<ZigNameIdentifierOwner>? = null
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
            if (element is PsiFile) {
                break
            }
            val child = element!!
            val parent = child.parent
            if (parent == null) {
                element = null
                break
            }
            val sequence = when (parent) {
                is ZigContainer -> parent.members.filterIsInstance<ZigNamedContainerDecl>()

                is ZigFnOrBuiltinFnDeclOrProto -> parent.parameters.takeWhile { it !== child }

                is ZigBlock -> child.siblings(forward = false, withSelf = false).flatMap { statement ->
                    when (statement) {
                        is ZigVarDecl -> sequenceOf(statement)
                        is ZigAssignDestructure -> statement.variables.filterIsInstance<ZigVarProto>()
                        is ZigComptime -> when (val inner = statement.innerElement) {
                            is ZigVarDecl -> sequenceOf(inner)
                            is ZigAssignDestructure -> inner.variables.filterIsInstance<ZigVarProto>()
                            else -> emptySequence()
                        }
                        else -> emptySequence()
                    }
                }

                is ZigThenElement -> parent.payload?.items

                is ZigElseElement -> parent.payload?.items

                is ZigSwitchProng if child !is ZigSwitchItem -> parent.payload?.items

                is ZigCatch if child !== parent.leftOperand -> parent.payload?.items

                is ZigWhile if child === parent.whileContinueExpr -> parent.thenPayload?.items

                else -> null
            }
            it = sequence?.iterator()
            element = parent
        }
        null
    }
}

internal fun getFunctionParamTypes(
    functionType: ZigFunctionType,
    typeParams: Map<ZigFnParam, ZigValue?> = functionType.typeParams,
): Pair<List<ZigFnParam>, List<ZigValue?>> {
    return getFunctionParamTypes(functionType.element, typeParams)
}

internal fun getFunctionParamTypes(
    functionElement: ZigFnOrBuiltinFnDeclOrProto,
    typeParams: Map<ZigFnParam, ZigValue?>,
): Pair<List<ZigFnParam>, List<ZigValue?>> {
    return functionElement.parameters.map { param ->
        param to param.typeElement?.expression?.let {
            val initialValue = ZigAnalyzer.resolveValue(it)
            ZigAnalyzer.substituteTypeParams(initialValue, typeParams)
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

private fun getFunctionTailText(
    params: List<ZigFnParam>,
    paramTypes: List<ZigValue?>,
    hasVarargs: Boolean,
): String {
    return buildString {
        append('(')
        for (i in params.indices) {
            if (i > 0) {
                append(", ")
            }
            append(getParameterText(params[i], paramTypes[i]))
        }
        if (hasVarargs) {
            if (params.isNotEmpty()) {
                append(", ")
            }
            append("...")
        }
        append(')')
    }
}

internal fun getParameterText(param: ZigFnParam, type: ZigValue?): String {
    return buildString {
        val name = param.name
        if (name != null) {
            append(name)
            append(": ")
        }
        if (param.isAnytype) {
            assert(type == null)
            append("anytype")
        } else if (type != null) {
            append(type.toPlainText())
        } else {
            append('_')
        }
    }
}

private fun getFunctionTypeText(functionType: ZigFunctionType): String {
    return getFunctionTypeText(functionType.element, functionType.typeParams)
}

private fun getFunctionTypeText(
    functionElement: ZigFnOrBuiltinFnDeclOrProto,
    typeParams: Map<ZigFnParam, ZigValue?>,
): String {
    if (functionElement is ZigBuiltinFnProto && functionElement.returnsAnytype) {
        return "anytype"
    }
    val returnType = functionElement.returnTypeElement?.expression?.let {
        val initialValue = ZigAnalyzer.resolveValue(it)
        ZigAnalyzer.substituteTypeParams(initialValue, typeParams)
    }
    if (returnType == null) {
        return "_"
    }
    val text = returnType.toPlainText()
    return if (functionElement.bangTokenElement != null) "!$text" else text
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

private fun getFieldInitVariants(containerType: IZigContainerType): Sequence<LookupElement> {
    if (containerType.containerKind == ZigContainerKind.ENUM) {
        return emptySequence()
    }
    return containerType.members.filterIsInstance<ZigContainerField>().mapNotNull { member ->
        val name = member.name ?: return@mapNotNull null
        val initialValue = ZigAnalyzer.resolveValueFromReferenceTo(member)
        val value = ZigAnalyzer.substituteTypeParams(initialValue, containerType.typeParams)
        buildLookupElement(member, name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)
            .withIcon(AllIcons.Nodes.Field)
            .withTailText(" =")
            .withTypeText(value.typeOf.toPlainText())
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
