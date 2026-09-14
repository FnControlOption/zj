package io.github.fncontroloption.zig

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.findFile
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
        val name = myElement.text
        return identifierReferenceCandidates(myElement).firstOrNull { it.name == name }
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.identifierElement, newElementName)

    override fun getVariants(): Array<out LookupElement> {
        val variants = mutableSetOf<LookupElement>()
        for (candidate in identifierReferenceCandidates(myElement)) {
            val value = ZigAnalyzer.resolveValuesFromReferenceTo(candidate as ZigElement).singleOrNull()
            when (val type = value?.typeOf) {
                is ZigFunctionType -> {
                    val (paramNames, paramTypes) = getFunctionParamNamesAndTypes(type)
                    variants.add(
                        LookupElementBuilder.create(candidate)
                            .withIcon(AllIcons.Nodes.Function)
                            .withTailText(getFunctionTailText(paramNames, paramTypes))
                            .withTypeText(getFunctionTypeText(type))
                            .withInsertHandler(FunctionInsertHandler)
                    )
                }

                else -> {
                    variants.add(
                        LookupElementBuilder.create(candidate)
                            .withIcon(candidate.getIcon(0))
                            .withTypeText(type?.toPlainText())
                    )
                }
            }
        }
        return variants.toTypedArray()
    }
}

class ZigTestReference(element: ZigTestDecl, val nameElement: PsiElement) :
    PsiReferenceBase<ZigTestDecl>(element, nameElement.textRangeInParent, true) {

    override fun resolve(): PsiElement? {
        val name = nameElement.text
        return identifierReferenceCandidates(nameElement).firstOrNull { it.name == name }
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.nameElement, newElementName)
}

class ZigLabelReference(element: ZigLabelRef) :
    PsiReferenceBase<ZigLabelRef>(element, element.identifierElement.textRangeInParent, true) {

    override fun resolve(): PsiElement? {
        val name = myElement.identifierElement.text
        var parent = myElement.parent
        while (parent != null) {
            if (parent is ZigLabelable) {
                val labelDecl = parent.labelDecl
                if (labelDecl != null && labelDecl.identifierElement.textMatches(name)) {
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
                if (labelDecl != null) {
                    variants.add(LookupElementBuilder.create(labelDecl))
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
            when (text) {
                "std" -> {
                    val enumerator = run {
                        val file = myElement.containingFile?.originalFile?.virtualFile
                        if (file != null) {
                            val module = ModuleUtilCore.findModuleForFile(file, myElement.project)
                            if (module != null) {
                                return@run OrderEnumerator.orderEntries(module)
                            }
                        }
                        OrderEnumerator.orderEntries(myElement.project)
                    }
                    for (root in enumerator.sdkOnly().sourceRoots) {
                        val virtualFile = root.findFile("std.zig")
                        if (virtualFile != null) {
                            val psiFile = PsiManager.getInstance(myElement.project).findFile(virtualFile)
                            if (psiFile != null) {
                                return psiFile
                            }
                        }
                    }
                    return null
                }
                "builtin" -> return null // TODO: get builtin text from `zig build-exe --show-builtin`
                else -> return null // TODO: resolve imported modules
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
        val oldValue = myElement.value
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
        val name = identifier.text
        val results = mutableSetOf<ResolveResult>()
        for ((containerType, _) in getContainers()) {
            val member = containerType.element.members.firstOrNull {
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
        val originalFile = myElement.containingFile
        val variants = mutableSetOf<LookupElement>()
        for ((containerType, isInstance) in getContainers()) {
            val inSameFile = originalFile == containerType.element.containingFile
            val typeParams = containerType.typeParams
            for (member in containerType.element.members) {
                val initialValues = ZigAnalyzer.resolveValuesFromReferenceTo(member)
                val value = ZigAnalyzer.substituteTypeParams(initialValues, typeParams).singleOrNull()
                val type = value?.typeOf
                if (member is ZigContainerField) {
                    val shouldIncludeField = when (containerType.element.containerKind) {
                        ZigContainerKind.ENUM -> !isInstance
                        else -> isInstance || containerType.element.isTaggedUnion
                    }
                    if (!shouldIncludeField) {
                        continue
                    }
                    variants.add(
                        LookupElementBuilder.create(member)
                            .withIcon(member.getIcon(0))
                            .withTypeText(type?.toPlainText())
                    )
                    continue
                }
                val isPub = when (member) {
                    is ZigVarDecl -> member.isPub
                    is ZigFnDecl -> member.isPub
                    else -> continue
                }
                if (!inSameFile && !isPub) {
                    continue
                }
                when (type) {
                    is ZigFunctionType -> {
                        var (paramNames, paramTypes) = getFunctionParamNamesAndTypes(type)
                        val isMethod = getFunctionReceiverType(paramTypes) == containerType
                        if (isInstance) {
                            if (!isMethod) {
                                continue
                            }
                            paramNames = paramNames.subList(1, paramNames.size)
                            paramTypes = paramTypes.subList(1, paramTypes.size)
                        }
                        variants.add(
                            LookupElementBuilder.create(member)
                                .withIcon(if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function)
                                .withTailText(getFunctionTailText(paramNames, paramTypes))
                                .withTypeText(getFunctionTypeText(type))
                                .withInsertHandler(FunctionInsertHandler)
                        )
                    }

                    else -> {
                        if (isInstance) {
                            continue
                        }
                        variants.add(
                            LookupElementBuilder.create(member)
                                .withIcon(member.getIcon(0))
                                .withTypeText(type?.toPlainText())
                        )
                    }
                }
            }
        }
        return variants.toTypedArray()
    }

    private fun getContainers(): Sequence<Pair<ZigContainerType, Boolean>> {
        return ZigAnalyzer.resolveValues(myElement.leftOperand).asSequence().mapNotNull { left ->
            when (left) {
                is ZigContainerType -> Pair(left, false)
                else -> when (val typeOfLeft = left.typeOf) {
                    is ZigContainerType -> Pair(typeOfLeft, true)
                    is ZigPointerType -> when (typeOfLeft.size) {
                        ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                            is ZigContainerType -> Pair(pointerChild, true)
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
        val types = ZigAnalyzer.inferResultTypes(myElement)
        val name = identifier.text
        return resolveInferredResultTypeReference(types, name)
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.identifierElement, newElementName)
}

class ZigListInitItemReference(element: ZigListInitItem, val identifier: PsiElement) :
    PsiPolyVariantReferenceBase<ZigListInitItem>(element, identifier.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val types = ZigAnalyzer.inferListInitTypes(myElement)
        val name = identifier.text
        return resolveInferredResultTypeReference(types, name)
    }

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.identifierElement, newElementName)
}

private fun resolveInferredResultTypeReference(types: Sequence<ZigValue>, name: String): Array<ResolveResult> {
    val results = mutableSetOf<ResolveResult>()
    for (type in types) {
        var type = type
        while (true) {
            type = when (type) {
                is ZigErrorUnionType -> type.payload
                is ZigOptionalType -> type.child
                is ZigPointerType if type.size == ZigPointerSize.ONE -> type.child
                else -> break
            }
        }
        if (type !is ZigContainerType) {
            continue
        }
        val member = type.element.members.firstOrNull {
            it is PsiNameIdentifierOwner && it.name == name
        }
        if (member != null) {
            results.add(PsiElementResolveResult(member))
        }
    }
    return results.toTypedArray()
}

private fun identifierReferenceCandidates(element: PsiElement): Sequence<PsiNameIdentifierOwner> {
    var it: Iterator<PsiNameIdentifierOwner>? = null
    var element: PsiElement? = element
    return generateSequence {
        while (element != null) {
            if (it?.hasNext() == true) {
                return@generateSequence it!!.next()
            }
            val child = element!!
            val parent = child.parent
            if (parent == null) {
                element = null
                break
            }
            val sequence = when (parent) {
                is ZigContainer ->
                    parent.members
                        .filterIsInstance<PsiNameIdentifierOwner>()
                        .filter { it !is ZigContainerField }

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

private fun getFunctionParamNamesAndTypes(functionType: ZigFunctionType): Pair<List<String?>, List<ZigValue?>> {
    return functionType.element.parameters.map { param ->
        param.name to param.typeElement?.innerElement?.let {
            val initialValues = ZigAnalyzer.resolveValues(it)
            ZigAnalyzer.substituteTypeParams(initialValues, functionType.typeParams).singleOrNull()
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
            val name = paramNames[i]
            if (name != null) {
                append(name)
                append(": ")
            }
            val type = paramTypes[i]
            if (type != null) {
                append(type.toPlainText())
            } else {
                append('_')
            }
        }
        append(')')
    }
}

private fun getFunctionTypeText(functionType: ZigFunctionType): String? {
    val returnType = functionType.element.returnTypeElement?.innerElement?.let {
        val initialValues = ZigAnalyzer.resolveValues(it)
        ZigAnalyzer.substituteTypeParams(initialValues, functionType.typeParams).singleOrNull()
    }
    return returnType?.toPlainText()?.let {
        if (functionType.element.bangTokenElement != null) "!$it" else it
    }
}

private object FunctionInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val editor = context.editor
        val document = editor.document
        val tailOffset = context.tailOffset
        val text = document.charsSequence
        when (context.completionChar) {
            Lookup.NORMAL_SELECT_CHAR -> {
                document.insertString(tailOffset, "()")
                editor.caretModel.moveToOffset(tailOffset + 1)
            }

            Lookup.REPLACE_SELECT_CHAR -> {
                if (text[tailOffset] != '(') {
                    document.insertString(tailOffset, "()")
                }
                editor.caretModel.moveToOffset(tailOffset + 1)
            }

            // TODO
            else -> {}
        }
    }
}
