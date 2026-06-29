package io.github.fncontroloption.zig

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import io.github.fncontroloption.zig.psi2.ZigAssignDestructure
import io.github.fncontroloption.zig.psi2.ZigBlock
import io.github.fncontroloption.zig.psi2.ZigCatch
import io.github.fncontroloption.zig.psi2.ZigContainerDecl
import io.github.fncontroloption.zig.psi2.ZigContainerField
import io.github.fncontroloption.zig.psi2.ZigElseElement
import io.github.fncontroloption.zig.psi2.ZigFile
import io.github.fncontroloption.zig.psi2.ZigFnDecl
import io.github.fncontroloption.zig.psi2.ZigIdentifier
import io.github.fncontroloption.zig.psi2.ZigSwitchItem
import io.github.fncontroloption.zig.psi2.ZigSwitchProng
import io.github.fncontroloption.zig.psi2.ZigTestDecl
import io.github.fncontroloption.zig.psi2.ZigThenElement
import io.github.fncontroloption.zig.psi2.ZigVarDecl
import io.github.fncontroloption.zig.psi2.ZigVarProto
import io.github.fncontroloption.zig.psi2.replaceZigIdentifierElement

class ZigIdentifierReference(element: ZigIdentifier) :
    PsiReferenceBase<ZigIdentifier>(element, TextRange(0, element.textLength), true) {

    override fun resolve() = resolveIdentifierReference(myElement)

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.identifierElement, newElementName)
}

class ZigTestReference(element: ZigTestDecl, val name: PsiElement) :
    PsiReferenceBase<ZigTestDecl>(element, name.textRangeInParent, true) {

    override fun resolve() = resolveIdentifierReference(name)

    override fun handleElementRename(newElementName: String) =
        replaceZigIdentifierElement(myElement, myElement.nameElement, newElementName)
}

/* TODO
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
 */

fun resolveIdentifierReference(element: PsiElement): PsiElement? {
    val name = element.text
    var element = element
    var parent = element.parent
    while (parent != null) {
        val target = when (parent) {
            is ZigFile -> parent.members.find { it is PsiNameIdentifierOwner && it.name == name }
            is ZigContainerDecl -> parent.members.find { it is PsiNameIdentifierOwner && it.name == name }
            is ZigFnDecl -> parent.parameters.find { it.name == name }
            is ZigBlock -> parent.statements.firstNotNullOfOrNull { statement ->
                when (statement) {
                    is ZigVarDecl -> if (statement.name == name) statement else null
                    is ZigAssignDestructure -> statement.variables.firstOrNull { variable ->
                        variable is ZigVarProto && variable.name == name
                    }
                    else -> null
                }
            }
            is ZigThenElement -> parent.payload?.items?.find { it.name == name }
            is ZigElseElement -> parent.payload?.items?.find { it.name == name }
            is ZigSwitchProng if element !is ZigSwitchItem -> parent.payload?.items?.find { it.name == name }
            is ZigCatch if element !== parent.leftOperand -> parent.payload?.items?.find { it.name == name }
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

/* TODO
fun resolveInferredTypeExprs(element: PsiElement): Sequence<PsiElement> {
    var element = element
    var parent = element.parent
    if (element is ZigEnumLiteral && parent is ZigCall) {
        element = parent
        parent = parent.parent
    }
    return when (parent) {
        is ZigInitExpr -> {
            parent = parent.parent
            when (parent) {
                is ZigVarDecl -> {
                    val typeExpr = parent.varDeclProto.declTypeExpr?.expr ?: return emptySequence()
                    sequenceOf(typeExpr)
                }
                is ZigContainerField -> {
                    val typeExpr = parent.containerFieldTypeExpr?.expr ?: return emptySequence()
                    sequenceOf(typeExpr)
                }
                is ZigAssign -> {
                    val expr = parent.lhs ?: return emptySequence()
                    sequenceOf(expr)
                }
                else -> emptySequence()
            }
        }
        is ZigInitListEntry -> {
            if (parent.fieldName != null) {
                resolveReferences(parent).mapNotNull { target ->
                    when (target) {
                        is ZigContainerField -> target.containerFieldTypeExpr?.expr
                        else -> null
                    }
                }
            } else {
                val initList = parent.parent as? ZigInitList ?: return emptySequence()
                val initIndex = initList.initListEntries.indexOf(parent)
                assert(initIndex >= 0)
                val arrayInit = initList.parent as? ZigArrayInit ?: return emptySequence()
                val inferredTypeExprs = if (arrayInit.isTypeInferred()) {
                    resolveInferredTypeExprs(arrayInit)
                } else {
                    val typeExpr = arrayInit.initTypeExpr?.expr ?: return emptySequence()
                    sequenceOf(typeExpr)
                }
                inferredTypeExprs.flatMap { typeExpr ->
                    followReferencesRecursive(typeExpr, mutableSetOf()).mapNotNull { target ->
                        when (target) {
                            is ZigContainerDecl -> {
                                // TODO: handle optional tuples, pointers to tuples, arrays of tuples, etc.
                                val body = target.containerDeclBody ?: return@mapNotNull null
                                val field = body.containerFields.elementAtOrNull(initIndex) ?: return@mapNotNull null
                                field.containerFieldTypeExpr?.expr
                            }
                            else -> target
                        }
                    }
                }
            }
        }
        is ZigReturn -> {
            val fnDecl = parent.findParentOfType<ZigFnDecl>() ?: return emptySequence()
            val typeExpr = fnDecl.fnProto.fnReturnTypeExpr?.expr ?: return emptySequence()
            sequenceOf(typeExpr)
        }
        // is ZigEqualEqual, is ZigBangEqual -> emptySequence() // TODO
        is ZigSwitchItem -> {
            val switch = parent.findParentOfType<ZigSwitch>() ?: return emptySequence()
            val expr = switch.switchCondition?.expr ?: return emptySequence()
            sequenceOf(expr)
        }
        is ZigSwitchResult -> {
            val switch = parent.findParentOfType<ZigSwitch>() ?: return emptySequence()
            resolveInferredTypeExprs(switch)
        }
        is ZigAddressOf -> {
            resolveInferredTypeExprs(parent)
        }
        is ZigCallArgs -> {
            val argIndex = parent.exprs.indexOf(element)
            assert(argIndex >= 0)
            when (val call = parent.parent) {
                is ZigCall -> {
                    val callLhs = call.lhs ?: return emptySequence()
                    var paramIndex = argIndex
                    if (isPossibleMethodAccess(callLhs)) {
                        paramIndex += 1
                    }
                    followReferencesRecursive(callLhs, mutableSetOf()).mapNotNull { target ->
                        when (target) {
                            is ZigFnProto -> {
                                val paramDecl = target.fnParams?.fnParamDecls?.values?.elementAtOrNull(paramIndex)
                                paramDecl?.fnParamTypeExpr?.expr
                            }
                            else -> null
                        }
                    }
                }
                is ZigBuiltinCall -> {
                    when (call.builtin.text) {
                        "@as" -> {
                            if (argIndex != 1) {
                                return emptySequence()
                            }
                            val typeExpr = parent.exprs.elementAt(0)
                            sequenceOf(typeExpr)
                        }
                        else -> emptySequence()
                    }
                }
                else -> emptySequence()
            }
        }
        else -> emptySequence()
    }
}
*/

fun resolveReferences(element: PsiElement): Sequence<PsiElement> {
    return element.references.asSequence().flatMap { reference ->
        if (reference is PsiPolyVariantReference) {
            reference.multiResolve(false)
                .asSequence()
                .mapNotNull(ResolveResult::getElement)
        } else {
            sequenceOf(reference.resolve()).filterNotNull()
        }
    }
}

/* TODO
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
        is ZigFile, is ZigContainerDecl -> sequenceOf(lhs)
        is ZigGroupedExpression -> {
            val expr = lhs.expr ?: return emptySequence()
            resolveContainerExpr(expr, visited)
        }
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
        is ZigBuiltinCall if lhs.builtin.text == "@as" -> {
            val callArgs = lhs.builtinCallArgs ?: return emptySequence()
            val expr = callArgs.exprs.firstOrNull() ?: return emptySequence()
            resolveContainerExpr(expr, visited)
        }
        is ZigBuiltinCall if lhs.builtin.text == "@TypeOf" -> {
            val callArgs = lhs.builtinCallArgs ?: return emptySequence()
            callArgs.exprs.flatMap { resolveContainerExpr(it, visited) }
        }
        is ZigIdentifier, is ZigFieldAccess, is ZigBuiltinCall -> {
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
        else -> emptySequence()
    }
}
 */
