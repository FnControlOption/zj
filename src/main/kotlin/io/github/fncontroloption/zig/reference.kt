package io.github.fncontroloption.zig

import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.util.parents
import com.intellij.psi.util.siblings
import com.intellij.util.IncorrectOperationException

/** @see com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry.getReferencesFromProviders */
fun resolveReferences(element: PsiElement): Sequence<PsiElement> {
    return element.references.asSequence().flatMap { reference ->
        if (reference is ZigPolyVariantReference<*>) {
            reference.resolveSequence().mapNotNull { it.element }
        } else {
            val target = reference.resolve()
            if (target != null) sequenceOf(target) else emptySequence()
        }
    }
}

sealed class ZigPolyVariantReference<T : ZigElement>(
    element: T,
    textRange: TextRange,
    soft: Boolean
) : PsiPolyVariantReferenceBase<T>(element, textRange, soft) {
    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        return resolveSequence().toSet().toTypedArray()
    }

    abstract fun resolveSequence(): Sequence<ResolveResult>
}

class ZigIdentifierReference(element: ZigIdentifier) :
    ZigPolyVariantReference<ZigIdentifier>(element, TextRange(0, element.textLength), true) {

    override fun resolveSequence(): Sequence<ResolveResult> {
        if (myElement.textMatches("_")) return emptySequence()
        val name = parseZigIdentifier(myElement) ?: return emptySequence()
        return identifierReferenceCandidates(myElement)
            .filter { it.name == name }
            .map { PsiElementResolveResult(it) }
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
    ZigPolyVariantReference<ZigTestDecl>(element, nameElement.textRangeInParent, true) {

    override fun resolveSequence(): Sequence<ResolveResult> {
        val name = parseZigIdentifier(nameElement) ?: return emptySequence()
        return identifierReferenceCandidates(nameElement)
            .filter { it.name == name }
            .map { PsiElementResolveResult(it) }
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
    ZigPolyVariantReference<ZigStringLiteral>(element, TextRange(1, element.textLength - 1), true) {

    override fun resolveSequence(): Sequence<ResolveResult> {
        val text = myElement?.value ?: return emptySequence()
        return resolveSequence(text)
    }

    protected open fun resolveSequence(text: String): Sequence<ResolveResult> {
        val parts = text.split("/")
        val fileName = parts.lastOrNull() ?: return emptySequence()
        var directory = myElement.containingFile?.originalFile?.containingDirectory ?: return emptySequence()
        for (dirName in parts.subList(0, parts.size - 1)) {
            if (dirName == "..") {
                directory = directory.parentDirectory ?: return emptySequence()
            } else if (dirName != ".") {
                directory = directory.findSubdirectory(dirName) ?: return emptySequence()
            }
        }
        val file = directory.findFile(fileName) ?: return emptySequence()
        return sequenceOf(PsiElementResolveResult(file))
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
    override fun resolveSequence(text: String): Sequence<ResolveResult> {
        if (text.endsWith(".zig") || text.endsWith(".zon")) {
            return super.resolveSequence(text)
        }
        return when (text) {
            "std" -> {
                val project = myElement.project
                val virtualFile = myElement.containingFile?.originalFile?.virtualFile
                val module = virtualFile?.let { ModuleUtilCore.findModuleForFile(it, project) }
                val stdFile = ZigAnalyzer.findZigLibFile(project, module, "std/std.zig") ?: return emptySequence()
                val psiManager = PsiManager.getInstance(project)
                val stdPsiFile = psiManager.findFile(stdFile) ?: return emptySequence()
                sequenceOf(PsiElementResolveResult(stdPsiFile))
            }
            "builtin" -> {
                val file = ZigAnalyzer.findBuiltinFile(myElement) ?: return emptySequence()
                sequenceOf(PsiElementResolveResult(file))
            }
            else -> {
                val project = myElement.project
                val virtualFile = myElement.containingFile?.originalFile?.virtualFile ?: return emptySequence()
                val projectService = project.service<ZigExternalSystemProjectService>()
                val projectPath = projectService.projectPath.value ?: return emptySequence()
                val psiManager = PsiManager.getInstance(project)
                val modulesService = project.service<ZigModulesProjectService>()
                val importedModules = when (text) {
                    "root" -> {
                        modulesService.getFileRootModules(virtualFile)
                            .asSequence()
                            .mapNotNull { modulesService.modules[it] }
                            .ifEmpty { modulesService.modules.values.asSequence().filter { it.isRootModule } }
                    }
                    else -> {
                        modulesService.getFileModules(virtualFile)
                            .asSequence()
                            .mapNotNull { modulesService.modules[it] }
                            .mapNotNull { it.importTable[text] }
                            .mapNotNull { modulesService.modules[it] }
                    }
                }
                importedModules
                    .mapNotNull { it.rootSourceFile }
                    .mapNotNull { projectPath.resolve(it) }
                    .mapNotNull { VirtualFileManager.getInstance().findFileByNioPath(it) }
                    .mapNotNull { psiManager.findFile(it) }
                    .map { PsiElementResolveResult(it) }
            }
        }
    }
}

class ZigFieldAccessReference(element: ZigFieldAccess, val identifier: PsiElement) :
    ZigPolyVariantReference<ZigFieldAccess>(element, identifier.textRangeInParent, true) {

    override fun resolveSequence(): Sequence<ResolveResult> {
        val name = parseZigIdentifier(identifier) ?: return emptySequence()
        val leftValue = ZigAnalyzer.resolveValue(myElement.leftOperand)
        return ZigAnalyzer.fieldAccessContainers(leftValue).mapNotNull { (containerType, _) ->
            val member = containerType.members.firstOrNull {
                it is ZigNamedContainerMember && it.name == name
            }
            if (member != null) {
                PsiElementResolveResult(member)
            } else {
                null
            }
        }
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
    ZigPolyVariantReference<ZigEnumLiteral>(element, identifier.textRangeInParent, true) {

    override fun resolveSequence(): Sequence<ResolveResult> {
        val name = parseZigIdentifier(identifier) ?: return emptySequence()
        val resultType = ZigAnalyzer.inferResultType(myElement)
        return resultType.asValueSequence().mapNotNull { originalType ->
            val containerType = ZigAnalyzer.findEnumLiteralContainerType(originalType) ?: return@mapNotNull null
            val member = containerType.members.firstOrNull {
                it is ZigNamedContainerMember && it.name == name
            }
            if (member != null) {
                PsiElementResolveResult(member)
            } else {
                null
            }
        }
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
    ZigPolyVariantReference<ZigListInitItem>(element, identifier.textRangeInParent, true) {

    override fun resolveSequence(): Sequence<ResolveResult> {
        val name = parseZigIdentifier(identifier) ?: return emptySequence()
        val listType = ZigAnalyzer.resolveOrInferListInitType(myElement.containingListInit)
        return listType.asValueSequence().mapNotNull { type ->
            val containerType = type as? ZigContainerType ?: return@mapNotNull null
            val member = containerType.members.firstOrNull {
                it is ZigContainerField && it.name == name
            }
            if (member != null) {
                PsiElementResolveResult(member)
            } else {
                null
            }
        }
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

                is ZigFnOrBuiltinFnDeclOrProto
                    if (child in parent.parameters || child === parent.returnTypeElement || child === parent.body)
                    -> parent.parameters.takeWhile { it !== child }

                is ZigBlock ->
                    child.siblings(forward = false, withSelf = false)
                        .filterIsInstance<ZigElement>()
                        .flatMap { ZigBlock.statementToVarDecls(it) }

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
