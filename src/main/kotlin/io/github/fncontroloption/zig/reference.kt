package io.github.fncontroloption.zig

import com.intellij.openapi.components.service
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
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigBreak::class.java),
            ZigBreakReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigContinue::class.java),
            ZigContinueReferenceProvider,
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
        val results = mutableSetOf<ResolveResult>()
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
                    val importService = myElement.project.service<ZigImportService>()
                    return importService.stdFile
                }
                /*
                "builtin" -> {
                    val projectService = myElement.project.getService(ZigProjectService::class.java)
                    return projectService.builtinFile
                }
                */
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
        val results = mutableSetOf<ResolveResult>()
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
        val arrayInit = myElement.findParentOfType<ZigArrayInit>() ?: return emptyArray()
        val inferredTypeExprs = if (arrayInit.isTypeInferred()) {
            resolveInferredTypeExprs(arrayInit)
        } else {
            val typeExpr = arrayInit.initTypeExpr?.expr ?: return emptyArray()
            sequenceOf(typeExpr)
        }
        val results = mutableSetOf<ResolveResult>()
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

object ZigBreakReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        val entry = element as ZigBreak
        val name = entry.breakLabel ?: return emptyArray()
        return arrayOf(ZigBreakReference(entry, name))
    }
}

class ZigBreakReference(element: ZigBreak, val label: PsiElement) :
    PsiReferenceBase<ZigBreak>(element, label.textRangeInParent, true) {

    override fun resolve() = findLabeledParent(myElement, label.text)

    override fun handleElementRename(newElementName: String): PsiElement? {
        val oldToken = myElement.breakLabel ?: throw IncorrectOperationException()
        val newToken = createZigIdentifierToken(myElement.project, newElementName)
        oldToken.replace(newToken)
        return myElement
    }
}

object ZigContinueReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        val entry = element as ZigContinue
        val name = entry.continueLabel ?: return emptyArray()
        return arrayOf(ZigContinueReference(entry, name))
    }
}

class ZigContinueReference(element: ZigContinue, val label: PsiElement) :
    PsiReferenceBase<ZigContinue>(element, label.textRangeInParent, true) {

    override fun resolve() = findLabeledParent(myElement, label.text)

    override fun handleElementRename(newElementName: String): PsiElement? {
        val oldToken = myElement.continueLabel ?: throw IncorrectOperationException()
        val newToken = createZigIdentifierToken(myElement.project, newElementName)
        oldToken.replace(newToken)
        return myElement
    }
}

fun findLabeledParent(element: PsiElement, labelName: String): PsiElement? {
    var parent = element.parent
    while (parent != null) {
        val parentLabel = when (parent) {
            is ZigBlock, is ZigFor, is ZigWhile, is ZigSwitch -> parent.nameIdentifier
            else -> null
        }
        if (parentLabel != null && parentLabel.textMatches(labelName)) {
            return parent
        }
        parent = parent.parent
    }
    return null
}

private fun resolveMember(
    lhs: PsiElement,
    name: String,
    visited: MutableSet<PsiElement>,
    results: MutableSet<ResolveResult>,
) {
    for (expr in resolveContainerExpr(lhs, visited)) {
        when (expr) {
            is ZigFile -> {
                val member = expr.containerMembers[name] ?: continue
                results.add(PsiElementResolveResult(member))
            }
            is ZigContainerDecl -> {
                val containerBody = expr.containerDeclBody ?: continue
                val member = containerBody.containerMembers[name] ?: continue
                results.add(PsiElementResolveResult(member))
            }
        }
    }
}

fun resolveIdentifierReference(element: PsiElement): PsiElement? {
    val name = element.text
    var element = element
    var parent = element.parent
    while (parent != null) {
        val target = when (parent) {
            is ZigContainerBody -> parent.containerMembers[name]
            is ZigFnDecl -> parent.fnProto.fnParams?.fnParamDecls[name]
            is ZigBlockBody -> parent.varDeclProtos[name]
            is ZigThen -> {
                parent = parent.parent
                val payload = when (parent) {
                    is ZigIf -> parent.thenPayload
                    is ZigWhile -> parent.whilePayload
                    is ZigFor -> parent.forPayload
                    else -> null
                }
                payload?.payloadEntries[name]
            }
            is ZigElse -> parent.elsePayload?.payloadEntries[name]
            is ZigSwitchResult -> {
                parent = parent.parent
                if (parent is ZigSwitchProng) {
                    parent.switchProngPayload?.payloadEntries[name]
                } else {
                    null
                }
            }
            is ZigCatch -> {
                if (element == parent.catchRhs) {
                    parent.catchPayload?.payloadEntries[name]
                } else {
                    null
                }
            }
            else -> null
        }
        if (target != null && target !is ZigContainerField) {
            return target
        }
        element = parent
        parent = parent.parent
    }
    return null
}

fun resolveInferredTypeExprs(element: PsiElement): Sequence<ZigPsiElement> {
    var element = element
    var parent = element.parent
    if (element is ZigEnumLiteral && parent is ZigCall) {
        element = parent
        parent = parent.parent
    }
    when (parent) {
        is ZigInitExpr -> {
            parent = parent.parent
            when (parent) {
                is ZigVarDecl -> {
                    val typeExpr = parent.varDeclProto.declTypeExpr?.expr ?: return emptySequence()
                    return sequenceOf(typeExpr)
                }
                is ZigContainerField -> {
                    val typeExpr = parent.containerFieldTypeExpr?.expr ?: return emptySequence()
                    return sequenceOf(typeExpr)
                }
                is ZigAssign -> {
                    val expr = parent.lhs ?: return emptySequence()
                    return sequenceOf(expr)
                }
                else -> return emptySequence()
            }
        }
        is ZigInitListEntry -> {
            if (parent.fieldName != null) {
                return resolveReferences(parent).mapNotNull { target ->
                    when (target) {
                        is ZigContainerField -> target.containerFieldTypeExpr?.expr
                        else -> null
                    }
                }
            } else {
                val arrayInit = parent.findParentOfType<ZigArrayInit>() ?: return emptySequence()
                if (arrayInit.isTypeInferred()) {
                    return resolveInferredTypeExprs(arrayInit)
                }
                val typeExpr = arrayInit.initTypeExpr?.expr ?: return emptySequence()
                // TODO: handle tuples
                return sequenceOf(typeExpr)
            }
        }
        is ZigReturn -> {
            val fnDecl = parent.findParentOfType<ZigFnDecl>() ?: return emptySequence()
            val typeExpr = fnDecl.fnProto.fnReturnTypeExpr?.expr ?: return emptySequence()
            return sequenceOf(typeExpr)
        }
        // is ZigEqualEqual, is ZigBangEqual -> return emptySequence() // TODO
        is ZigSwitchItem -> {
            val switch = parent.findParentOfType<ZigSwitch>() ?: return emptySequence()
            val expr = switch.switchCondition?.expr ?: return emptySequence()
            return sequenceOf(expr)
        }
        is ZigSwitchResult -> {
            val switch = parent.findParentOfType<ZigSwitch>() ?: return emptySequence()
            return resolveInferredTypeExprs(switch)
        }
        is ZigAddressOf -> {
            return resolveInferredTypeExprs(parent)
        }
        is ZigCallArgs -> {
            val argIndex = parent.exprs.indexOf(element)
            assert(argIndex >= 0)
            val call = parent.findParentOfType<ZigCall>() ?: return emptySequence()
            val callLhs = call.lhs ?: return emptySequence()
            var paramIndex = argIndex
            if (isPossibleMethodAccess(callLhs)) {
                paramIndex += 1
            }
            return followReferencesRecursive(callLhs, mutableSetOf()).mapNotNull { target ->
                when (target) {
                    is ZigFnProto -> {
                        val paramDecl = target.fnParams?.fnParamDecls?.values?.elementAtOrNull(paramIndex)
                        paramDecl?.fnParamTypeExpr?.expr
                    }
                    else -> null
                }
            }
        }
        else -> return emptySequence()
    }
}

fun resolveReferences(element: PsiElement): Sequence<PsiElement> {
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

fun followReferencesRecursive(element: PsiElement, visited: MutableSet<PsiElement>): Sequence<PsiElement> {
    if (element in visited) {
        return emptySequence()
    }
    visited.add(element)
    when (element) {
        is ZigIf -> {
            val thenExpr = element.thenBranch?.expr
            val elseExpr = element.elseBranch?.expr
            return sequenceOf(thenExpr, elseExpr)
                .filterNotNull()
                .flatMap { followReferencesRecursive(it, visited) }
        }
    }
    return resolveReferences(element)
        .flatMap { target ->
            if (target is ZigVarDeclProto) {
                val typeExpr = target.declTypeExpr?.expr
                if (typeExpr == null) {
                    val parent = target.parent
                    if (parent is ZigVarDecl) {
                        val initExpr = parent.varDeclInitExpr?.expr
                        if (initExpr != null) {
                            return@flatMap followReferencesRecursive(initExpr, visited)
                        }
                    }
                }
            }
            followReferencesRecursive(target, visited)
        }
        .ifEmpty { sequenceOf(element) }
}

fun isPossibleMethodAccess(element: PsiElement): Boolean {
    if (element is ZigFieldAccess) {
        val accessLhs = element.lhs ?: return false
        return isImplicitSelfArg(accessLhs)
    }
    return false
}

fun isImplicitSelfArg(element: PsiElement): Boolean {
    for (target in followReferencesRecursive(element, mutableSetOf())) {
        when (target) {
            is ZigFile, is ZigContainerDecl -> return false
            is ZigCall -> {
                val callLhs = target.lhs ?: return false
                for (target in followReferencesRecursive(callLhs, mutableSetOf())) {
                    val fnProto = target as? ZigFnProto ?: continue
                    val returnTypeExpr = fnProto.fnReturnTypeExpr?.expr ?: continue
                    if (returnTypeExpr.textMatches("type")) {
                        return false
                    }
                }
                return true
            }
        }
    }
    return true
}

private fun resolveContainerExpr(lhs: PsiElement, visited: MutableSet<PsiElement>): Sequence<PsiElement> {
    if (lhs in visited) {
        return emptySequence()
    }
    visited.add(lhs)
    return when (lhs) {
        is ZigBreak, is ZigContinue -> emptySequence()
        is ZigFile, is ZigContainerDecl -> sequenceOf(lhs)
        is ZigAddressOf -> {
            val expr = lhs.expr ?: return emptySequence()
            resolveContainerExpr(expr, visited)
        }
        is ZigDeref -> {
            val expr = lhs.lhs ?: return emptySequence()
            resolveContainerExpr(expr, visited)
        }
        is ZigArrayAccess -> {
            val expr = lhs.lhs ?: return emptySequence()
            resolveContainerExpr(expr, visited)
        }
        is ZigSlice -> {
            val expr = lhs.lhs ?: return emptySequence()
            resolveContainerExpr(expr, visited)
        }
        is ZigUnwrapOptional -> {
            val expr = lhs.lhs ?: return emptySequence()
            resolveContainerExpr(expr, visited)
        }
        is ZigOrelse -> {
            sequenceOf(lhs.orelseLhs, lhs.orelseRhs)
                .filterNotNull()
                .flatMap { resolveContainerExpr(it, visited) }
        }
        is ZigTry -> {
            val expr = lhs.expr ?: return emptySequence()
            resolveContainerExpr(expr, visited)
        }
        is ZigCatch -> {
            sequenceOf(lhs.catchLhs, lhs.catchRhs)
                .filterNotNull()
                .flatMap { resolveContainerExpr(it, visited) }
        }
        is ZigPointerType -> {
            val typeExpr = lhs.child ?: return emptySequence()
            resolveContainerExpr(typeExpr, visited)
        }
        is ZigArrayType -> {
            val typeExpr = lhs.child ?: return emptySequence()
            resolveContainerExpr(typeExpr, visited)
        }
        is ZigOptionalType -> {
            val typeExpr = lhs.child ?: return emptySequence()
            resolveContainerExpr(typeExpr, visited)
        }
        is ZigErrorUnion -> {
            val typeExpr = lhs.rhs ?: return emptySequence()
            resolveContainerExpr(typeExpr, visited)
        }
        is ZigCall -> {
            val callLhs = lhs.lhs ?: return emptySequence()
            followReferencesRecursive(callLhs, visited).flatMap { target ->
                val fnProto = target as? ZigFnProto ?: return@flatMap emptySequence()
                val returnTypeExpr = fnProto.fnReturnTypeExpr?.expr ?: return@flatMap emptySequence()
                val containerExprs = if (returnTypeExpr.textMatches("type")) {
                    val fnDecl = fnProto.parent as? ZigFnDecl ?: return@flatMap emptySequence()
                    val fnBody = fnDecl.fnBody ?: return@flatMap emptySequence()
                    SyntaxTraverser.psiTraverser(fnBody)
                        .expand { it !is ZigContainerDecl }
                        .asSequence()
                        .flatMap { elem ->
                            if (elem is ZigReturn) {
                                val expr = elem.expr ?: return@flatMap emptySequence()
                                return@flatMap resolveContainerExpr(expr, visited)
                            }
                            emptySequence()
                        }
                } else {
                    resolveContainerExpr(returnTypeExpr, visited)
                }
                val callArgs = lhs.callArgs ?: return@flatMap containerExprs
                val fnParamDecls = fnProto.fnParams?.fnParamDecls?.values ?: return@flatMap containerExprs
                containerExprs.flatMap { expr ->
                    // Check if container expression is a generic parameter of the called function
                    val paramIndex = fnParamDecls.indexOf(expr)
                    if (paramIndex >= 0) {
                        // Use the corresponding call argument
                        var argIndex = paramIndex
                        if (isPossibleMethodAccess(callLhs)) {
                            argIndex -= 1
                        }
                        val arg = callArgs.exprs.elementAtOrNull(argIndex)
                        if (arg != null) {
                            resolveContainerExpr(arg, visited)
                        } else {
                            emptySequence()
                        }
                    } else {
                        sequenceOf(expr)
                    }
                }
            }
        }
        is ZigBlock -> {
            val blockBody = lhs.blockBody ?: return emptySequence()
            SyntaxTraverser.psiTraverser(blockBody)
                .expand { it !is ZigContainerDecl }
                .asSequence()
                .flatMap { elem ->
                    if (elem is ZigBreak) {
                        val breakExpr = elem.breakExpr ?: return@flatMap emptySequence()
                        for (target in resolveReferences(elem)) {
                            if (target == lhs) {
                                return@flatMap resolveContainerExpr(breakExpr, visited)
                            }
                        }
                    }
                    emptySequence()
                }
        }
        else -> {
            visited.remove(lhs)
            followReferencesRecursive(lhs, visited).flatMap { target ->
                if (target == lhs) {
                    return@flatMap emptySequence()
                }
                when (target) {
                    is ZigVarDeclProto -> {
                        val typeExpr = target.declTypeExpr?.expr ?: return@flatMap emptySequence()
                        resolveContainerExpr(typeExpr, visited)
                    }
                    is ZigFnParamDecl -> {
                        val typeExpr = target.fnParamTypeExpr?.expr ?: return@flatMap emptySequence()
                        if (typeExpr.textMatches("type")) {
                            sequenceOf(target)
                        } else {
                            resolveContainerExpr(typeExpr, visited)
                        }
                    }
                    is ZigContainerField -> {
                        val typeExpr = target.containerFieldTypeExpr?.expr ?: return@flatMap emptySequence()
                        resolveContainerExpr(typeExpr, visited)
                    }
                    is ZigPayloadEntry -> {
                        val payload = target.parent as? ZigPayload ?: return@flatMap emptySequence()
                        when (val payloadParent = payload.parent) {
                            is ZigIf -> {
                                val expr = payloadParent.ifCondition?.expr ?: return@flatMap emptySequence()
                                resolveContainerExpr(expr, visited)
                            }
                            is ZigWhile -> {
                                val expr = payloadParent.whileCondition?.expr ?: return@flatMap emptySequence()
                                resolveContainerExpr(expr, visited)
                            }
                            is ZigFor -> {
                                val forInputs = payloadParent.forInputs?.forInputExprs ?: return@flatMap emptySequence()
                                val payloadIndex = payload.payloadEntries.values.indexOf(target)
                                val expr = forInputs.elementAtOrNull(payloadIndex) ?: return@flatMap emptySequence()
                                resolveContainerExpr(expr, visited)
                            }
                            is ZigSwitchProng -> {
                                if (payload.payloadEntries.values.first() != target) {
                                    return@flatMap emptySequence()
                                }
                                payloadParent.switchItems
                                    .flatMap { switchItem ->
                                        val switchItemExpr = switchItem.switchItemExpr ?: return@flatMap emptySequence()
                                        resolveReferences(switchItemExpr)
                                    }
                                    .flatMap { target ->
                                        when (target) {
                                            is ZigContainerField -> {
                                                val typeExpr = target.containerFieldTypeExpr?.expr ?: return@flatMap emptySequence()
                                                resolveContainerExpr(typeExpr, visited)
                                            }
                                            else -> emptySequence()
                                        }
                                    }
                            }
                            else -> emptySequence()
                        }
                    }
                    else -> {
                        visited.remove(target)
                        resolveContainerExpr(target, visited)
                    }
                }
            }
        }
    }
}
