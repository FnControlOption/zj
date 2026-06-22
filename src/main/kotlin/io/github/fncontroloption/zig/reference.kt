package io.github.fncontroloption.zig

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.elementType
import com.intellij.psi.util.findParentOfType
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ProcessingContext
import io.github.fncontroloption.zig.psi.*

class ZigReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigIdentifier::class.java),
            ZigIdentifierReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigTestDecl::class.java),
            ZigTestDeclReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigFieldAccess::class.java),
            ZigFieldAccessReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigBuiltinCall::class.java),
            ZigBuiltinCallReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigEnumLiteral::class.java),
            ZigEnumLiteralReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigInitListEntry::class.java),
            ZigInitListEntryReferenceProvider,
        )
    }
}

object ZigIdentifierReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        val identifier = element as ZigIdentifier
        return arrayOf(ZigIdentifierReference(identifier))
    }
}

class ZigIdentifierReference(element: ZigIdentifier) :
    PsiReferenceBase<ZigIdentifier>(element, TextRange(0, element.textLength), true) {

    override fun resolve() = resolveIdentifierReference(myElement)

    override fun handleElementRename(newElementName: String): PsiElement? {
        val oldToken = myElement.identifierToken ?: throw IncorrectOperationException()
        val newToken = createZigIdentifierToken(myElement.project, newElementName)
        oldToken.replace(newToken)
        return myElement
    }
}

object ZigTestDeclReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        val decl = element as ZigTestDecl
        val name = decl.testName
        if (name?.elementType === ZigTokenTypes.IDENTIFIER) {
            return arrayOf(ZigTestReference(decl, name))
        }
        return emptyArray()
    }
}

class ZigTestReference(element: ZigTestDecl, val name: PsiElement) :
    PsiReferenceBase<ZigTestDecl>(element, name.textRangeInParent, true) {

    override fun resolve() = resolveIdentifierReference(name)

    override fun handleElementRename(newElementName: String): PsiElement? {
        val oldToken = myElement.testName ?: throw IncorrectOperationException()
        val newToken = createZigIdentifierToken(myElement.project, newElementName)
        oldToken.replace(newToken)
        return myElement
    }
}

object ZigFieldAccessReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        val access = element as ZigFieldAccess
        val name = access.fieldName ?: return emptyArray()
        return arrayOf(ZigFieldAccessReference(access, name))
    }
}

class ZigFieldAccessReference(element: ZigFieldAccess, val name: PsiElement) :
    PsiPolyVariantReferenceBase<ZigFieldAccess>(element, name.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val lhs = myElement.lhs ?: return emptyArray()
        val results = mutableListOf<ResolveResult>()
        resolveMember(lhs, name.text, mutableSetOf(), results)
        return results.toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement? {
        val oldToken = myElement.fieldName ?: throw IncorrectOperationException()
        val newToken = createZigIdentifierToken(myElement.project, newElementName)
        oldToken.replace(newToken)
        return myElement
    }
}

object ZigBuiltinCallReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        val builtinCall = element as ZigBuiltinCall
        val builtin = builtinCall.builtin
        if (builtin.textMatches("@This")) {
            return arrayOf(ZigThisReference(builtinCall))
        }
        if (builtin.textMatches("@import")) {
            return arrayOf(ZigImportReference(builtinCall))
        }
        return emptyArray()
    }
}

class ZigThisReference(element: ZigBuiltinCall) :
    PsiReferenceBase<ZigBuiltinCall>(element, TextRange(0, element.textLength), true) {

    override fun resolve(): PsiElement? {
        val containerDecl = myElement.findParentOfType<ZigContainerDecl>()
        return containerDecl ?: myElement.containingFile
    }
}

class ZigImportReference(element: ZigBuiltinCall) :
    PsiReferenceBase<ZigBuiltinCall>(element, TextRange(0, element.textLength), true) {

    override fun resolve(): PsiElement? {
        val text = importArg?.literalValue ?: return null
        if (!text.endsWith(".zig") && !text.endsWith(".zon")) {
            when (text) {
                "std" -> {
                    val projectService = myElement.project.getService(ZigProjectService::class.java)
                    return projectService.stdFile
                }
                "builtin" -> {
                    val projectService = myElement.project.getService(ZigProjectService::class.java)
                    return projectService.builtinFile
                }
                else -> {
                    // TODO: resolve imported modules
                    return null
                }
            }
        }
        val parts = text.split("/")
        val fileName = parts.lastOrNull() ?: return null
        var directory = myElement.containingFile?.containingDirectory ?: return null
        parts.subList(0, parts.size - 1).forEach { dirName ->
            if (dirName == "..") {
                directory = directory.parentDirectory ?: return null
            } else if (dirName != ".") {
                directory = directory.findSubdirectory(dirName) ?: return null
            }
        }
        return directory.findFile(fileName)
    }

    override fun handleElementRename(newElementName: String): PsiElement? {
        val oldArg = importArg ?: throw IncorrectOperationException()
        val oldValue = oldArg.literalValue
        val oldParts = oldValue.split("/")
        if (oldParts.isEmpty()) {
            throw IncorrectOperationException()
        }
        val newValue = buildString {
            for (i in 0 until oldParts.lastIndex) {
                append(oldParts[i])
                append('/')
            }
            append(newElementName)
        }
        val newArg = createZigStringLiteral(myElement.project, newValue)
        oldArg.replace(newArg)
        return myElement
    }

    private val importArg: ZigStringLiteral?
        get() {
            val callArgs = myElement.builtinCallArgs ?: return null
            val child = callArgs.firstChild ?: return null
            if (child.elementType !== ZigTokenTypes.L_PAREN) {
                return null
            }
            return child.nextSibling as? ZigStringLiteral
        }

    override fun bindToElement(element: PsiElement): PsiElement? {
        val oldArg = importArg ?: throw IncorrectOperationException()
        val psiFile = element as? PsiFile ?: throw IncorrectOperationException()
        val importedFile = psiFile.virtualFile ?: throw IncorrectOperationException()
        val containingDir = myElement.containingFile?.virtualFile?.parent ?: throw IncorrectOperationException()
        val relativePath = containingDir.toNioPath().relativize(importedFile.toNioPath())
        val newValue = buildString {
            append(relativePath.getName(0))
            for (i in 1 until relativePath.nameCount) {
                append('/')
                append(relativePath.getName(i))
            }
        }
        val newArg = createZigStringLiteral(myElement.project, newValue)
        oldArg.replace(newArg)
        return myElement
    }
}

object ZigEnumLiteralReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        val literal = element as ZigEnumLiteral
        val name = literal.fieldName ?: return emptyArray()
        return arrayOf(ZigEnumLiteralReference(literal, name))
    }
}

class ZigEnumLiteralReference(element: ZigEnumLiteral, val name: PsiElement) :
    PsiPolyVariantReferenceBase<ZigEnumLiteral>(element, name.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val results = mutableListOf<ResolveResult>()
        for (typeExpr in resolveInferredTypeExprs(myElement)) {
            resolveMember(typeExpr, name.text, mutableSetOf(), results)
        }
        return results.toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement? {
        val oldToken = myElement.fieldName ?: throw IncorrectOperationException()
        val newToken = createZigIdentifierToken(myElement.project, newElementName)
        oldToken.replace(newToken)
        return myElement
    }
}

object ZigInitListEntryReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        val entry = element as ZigInitListEntry
        val name = entry.fieldName ?: return emptyArray()
        return arrayOf(ZigInitListEntryReference(entry, name))
    }
}

class ZigInitListEntryReference(element: ZigInitListEntry, val name: PsiElement) :
    PsiPolyVariantReferenceBase<ZigInitListEntry>(element, name.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val initList = myElement.parent as? ZigInitList ?: return emptyArray()
        val arrayInit = initList.parent as? ZigArrayInit ?: return emptyArray()
        val inferredTypeExprs = if (arrayInit.isTypeInferred()) {
            resolveInferredTypeExprs(arrayInit)
        } else {
            val typeExpr = arrayInit.initTypeExpr?.expr ?: return emptyArray()
            setOf(typeExpr)
        }
        val results = mutableListOf<ResolveResult>()
        for (typeExpr in inferredTypeExprs) {
            resolveMember(typeExpr, name.text, mutableSetOf(), results)
        }
        return results.toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement? {
        val oldToken = myElement.fieldName ?: throw IncorrectOperationException()
        val newToken = createZigIdentifierToken(myElement.project, newElementName)
        oldToken.replace(newToken)
        return myElement
    }
}

private fun resolveMember(
    lhs: PsiElement,
    name: String,
    visited: MutableSet<PsiElement>,
    results: MutableList<ResolveResult>,
) {
    if (lhs in visited) {
        return
    }
    visited.add(lhs)
    when (lhs) {
        is ZigVarDeclProto -> {
            val typeExpr = lhs.declTypeExpr?.expr ?: return
            resolveMember(typeExpr, name, visited, results)
        }
        is ZigFnParamDecl -> {
            val typeExpr = lhs.fnParamTypeExpr?.expr ?: return
            resolveMember(typeExpr, name, visited, results)
        }
        is ZigContainerField -> {
            val typeExpr = lhs.containerFieldTypeExpr?.expr ?: return
            resolveMember(typeExpr, name, visited, results)
        }
        is ZigFile -> {
            val member = lhs.containerMembers[name] ?: return
            results.addLast(PsiElementResolveResult(member))
        }
        is ZigContainerDecl -> {
            val containerBody = lhs.containerDeclBody ?: return
            val member = containerBody.containerMembers[name] ?: return
            results.addLast(PsiElementResolveResult(member))
        }
        is ZigAddressOf -> {
            val expr = lhs.expr ?: return
            resolveMember(expr, name, visited, results)
        }
        is ZigDeref -> {
            val derefLhs = lhs.lhs ?: return
            resolveMember(derefLhs, name, visited, results)
        }
        is ZigUnwrapOptional -> {
            val unwrapLhs = lhs.lhs ?: return
            resolveMember(unwrapLhs, name, visited, results)
        }
        is ZigTry -> {
            val expr = lhs.expr ?: return
            resolveMember(expr, name, visited, results)
        }
        is ZigPointerType -> {
            val child = lhs.child ?: return
            resolveMember(child, name, visited, results)
        }
        is ZigOptionalType -> {
            val child = lhs.child ?: return
            resolveMember(child, name, visited, results)
        }
        is ZigErrorUnion -> {
            val rhs = lhs.rhs ?: return
            resolveMember(rhs, name, visited, results)
        }
        is ZigCall -> {
            val callLhs = lhs.lhs ?: return
            forEachReferenceLeaf(callLhs, visited) { target ->
                val fnProto = target as? ZigFnProto ?: return@forEachReferenceLeaf
                val returnTypeExpr = fnProto.fnReturnTypeExpr?.expr ?: return@forEachReferenceLeaf
                if (returnTypeExpr.textMatches("type")) {
                    val fnDecl = fnProto.parent as? ZigFnDecl ?: return@forEachReferenceLeaf
                    val fnBody = fnDecl.fnBody ?: return@forEachReferenceLeaf
                    val psiTraverser = SyntaxTraverser.psiTraverser(fnBody).expand { it !is ZigContainerDecl }
                    for (elem in psiTraverser) {
                        if (elem is ZigReturn) {
                            val returnExpr = elem.expr ?: return@forEachReferenceLeaf
                            resolveMember(returnExpr, name, visited, results)
                        }
                    }
                } else {
                    resolveMember(returnTypeExpr, name, visited, results)
                }
            }
        }
        is ZigIf -> {
            lhs.thenBranch?.expr?.let { resolveMember(it, name, visited, results) }
            lhs.elseBranch?.expr?.let { resolveMember(it, name, visited, results) }
        }
        else -> {
            visited.remove(lhs)
            forEachReferenceLeaf(lhs, visited) { target ->
                if (target != lhs) {
                    visited.remove(target)
                    resolveMember(target, name, visited, results)
                }
            }
        }
    }
}

fun resolveIdentifierReference(element: PsiElement): PsiElement? {
    val name = element.text
    var parent = element.parent
    while (parent != null) {
        val target = when (parent) {
            is ZigContainerBody -> parent.containerMembers[name]
            is ZigFnDecl -> parent.fnProto.fnParams?.fnParamDecls[name]
            is ZigBlock -> parent.varDeclProtos[name]
            else -> null
        }
        if (target != null && target !is ZigContainerField) {
            return target
        }
        parent = parent.parent
    }
    return null
}

fun forEachReferenceLeaf(element: PsiElement, visited: MutableSet<PsiElement>, block: (PsiElement) -> Unit) {
    if (element in visited) {
        return
    }
    visited.add(element)
    var isLeaf = true
    for (target in multiResolveReferences(element)) {
        isLeaf = false
        if (target is ZigVarDeclProto) {
            val typeExpr = target.declTypeExpr?.expr
            if (typeExpr == null) {
                val parent = target.parent
                if (parent is ZigVarDecl) {
                    val initExpr = parent.varDeclInitExpr?.expr
                    if (initExpr != null) {
                        forEachReferenceLeaf(initExpr, visited, block)
                        continue
                    }
                }
            }
        }
        forEachReferenceLeaf(target, visited, block)
    }
    if (isLeaf) {
        block(element)
        return
    }
}

fun resolveInferredTypeExprs(element: PsiElement): Set<ZigPsiElement> {
    var parent = element.parent
    if (element is ZigEnumLiteral && parent is ZigCall) {
        parent = parent.parent
    }
    when (parent) {
        is ZigInitExpr -> {
            parent = parent.parent
            when (parent) {
                is ZigVarDecl -> {
                    val typeExpr = parent.varDeclProto.declTypeExpr?.expr ?: return emptySet()
                    return setOf(typeExpr)
                }
                is ZigContainerField -> {
                    val typeExpr = parent.containerFieldTypeExpr?.expr ?: return emptySet()
                    return setOf(typeExpr)
                }
                else -> return emptySet()
            }
        }
        is ZigInitListEntry -> {
            return buildSet {
                for (target in multiResolveReferences(parent)) {
                    when (target) {
                        is ZigContainerField -> {
                            val typeExpr = target.containerFieldTypeExpr?.expr ?: continue
                            add(typeExpr)
                        }
                    }
                }
            }
        }
        is ZigCallArgs -> return emptySet() // TODO
        is ZigReturn -> return emptySet() // TODO
        else -> return emptySet()
    }
}

fun multiResolveReferences(element: PsiElement): Sequence<PsiElement> {
    return element.references.asSequence().flatMap { reference ->
        if (reference is PsiPolyVariantReference) {
            reference.multiResolve(false)
                .asSequence()
                .filter(ResolveResult::isValidResult)
                .mapNotNull(ResolveResult::getElement)
        } else {
            sequenceOf(reference.resolve()).filterNotNull()
        }
    }
}
