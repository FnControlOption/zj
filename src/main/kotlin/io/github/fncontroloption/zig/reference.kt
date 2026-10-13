package io.github.fncontroloption.zig

import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.util.*
import com.intellij.util.IncorrectOperationException

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
}

class ZigLabelReference(element: ZigLabelRef) :
    PsiReferenceBase<ZigLabelRef>(element, element.identifierElement.textRangeInParent, true) {

    override fun resolve(): ZigLabelDecl? {
        val name = parseZigIdentifier(myElement.identifierElement) ?: return null
        for (parent in myElement.parents(withSelf = false)) {
            if (parent is ZigContainer) {
                break
            }
            if (parent is ZigLabelable) {
                val labelDecl = parent.labelDecl
                if (labelDecl != null && parseZigIdentifier(labelDecl.identifierElement) == name) {
                    return labelDecl
                }
            }
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
            "root" -> {
                val project = myElement.project
                val virtualFile = myElement.containingFile?.originalFile?.virtualFile ?: return emptyArray()
                val psiManager = PsiManager.getInstance(project)
                val modulesService = project.service<ZigModulesProjectService>()
                val rootIndexes = modulesService.getFileRootModules(virtualFile)
                val rootModules = if (rootIndexes.isNotEmpty()) {
                    rootIndexes.asSequence().mapNotNull { modulesService.modules[it] }
                } else {
                    modulesService.modules.values.asSequence().filter { it.isRootModule }
                }
                rootModules
                    .mapNotNull { modulesService.getRootSourceVirtualFile(it) }
                    .mapNotNull { psiManager.findFile(it) }
                    .map { PsiElementResolveResult(it) }
                    .toSet()
                    .toTypedArray()
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
        ZigAnalyzer.forEachFieldAccessContainer(leftValue) { containerType, _ ->
            val member = containerType.members.firstOrNull {
                it is ZigNamedContainerMember && it.name == name
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
                it is ZigNamedContainerMember && it.name == name
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
}

class ZigListInitItemReference(element: ZigListInitItem, val identifier: PsiElement) :
    PsiPolyVariantReferenceBase<ZigListInitItem>(element, identifier.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val listType = ZigAnalyzer.resolveOrInferListInitType(myElement.containingListInit)
        val name = parseZigIdentifier(identifier) ?: return emptyArray()
        val results = mutableSetOf<ResolveResult>()
        listType.forEachValue { type ->
            val containerType = type as? IZigContainerType ?: return@forEachValue
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
            myElement.fieldNameIdentifier,
            newElementName,
            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
        )
}

class ZigBuiltinCallReference(element: ZigBuiltinCall) :
    PsiReferenceBase<ZigBuiltinCall>(element, element.builtinElement.textRangeInParent, true) {

    override fun resolve(): PsiElement? {
        val name = myElement.builtinElement.text
        val builtin = ZigBuiltinFunctionsService.getBuiltinFnProto(myElement.project, name) ?: return null
        return builtin
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
