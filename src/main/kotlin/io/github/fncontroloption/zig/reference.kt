package io.github.fncontroloption.zig

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.elementType
import com.intellij.psi.util.findParentOfType
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ProcessingContext
import io.github.fncontroloption.zig.psi.ZigBlock
import io.github.fncontroloption.zig.psi.ZigBuiltinCall
import io.github.fncontroloption.zig.psi.ZigCall
import io.github.fncontroloption.zig.psi.ZigCallArgs
import io.github.fncontroloption.zig.psi.ZigContainerBody
import io.github.fncontroloption.zig.psi.ZigContainerDecl
import io.github.fncontroloption.zig.psi.ZigContainerField
import io.github.fncontroloption.zig.psi.ZigEnumLiteral
import io.github.fncontroloption.zig.psi.ZigFieldAccess
import io.github.fncontroloption.zig.psi.ZigFile
import io.github.fncontroloption.zig.psi.ZigFnDecl
import io.github.fncontroloption.zig.psi.ZigFnParamDecl
import io.github.fncontroloption.zig.psi.ZigIdentifier
import io.github.fncontroloption.zig.psi.ZigInitExpr
import io.github.fncontroloption.zig.psi.ZigPtrType
import io.github.fncontroloption.zig.psi.ZigStringLiteral
import io.github.fncontroloption.zig.psi.ZigTestDecl
import io.github.fncontroloption.zig.psi.ZigTokenTypes
import io.github.fncontroloption.zig.psi.ZigVarDecl
import io.github.fncontroloption.zig.psi.ZigVarDeclProto
import io.github.fncontroloption.zig.psi.createZigIdentifierToken
import io.github.fncontroloption.zig.psi.createZigStringLiteral

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
        resolveMember(lhs, name.text, results)
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
        var parent = myElement.parent
        if (parent is ZigCall) {
            parent = parent.parent
        }
        val typeExpr = when (parent) {
            is ZigInitExpr -> {
                parent = parent.parent
                when (parent) {
                    is ZigVarDecl -> parent.varDeclProto.declTypeExpr?.expr
                    is ZigContainerField -> parent.containerFieldTypeExpr?.expr
                    else -> return emptyArray()
                }
            }
            is ZigCallArgs -> return emptyArray() // TODO
            else -> return emptyArray()
        }
        if (typeExpr == null) {
            return emptyArray()
        }
        // TODO: reduce optional/pointer/error union to base type
        val results = mutableListOf<ResolveResult>()
        resolveMember(typeExpr, name.text, results)
        return results.toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement? {
        val oldToken = myElement.fieldName ?: throw IncorrectOperationException()
        val newToken = createZigIdentifierToken(myElement.project, newElementName)
        oldToken.replace(newToken)
        return myElement
    }
}

private fun resolveMember(lhs: PsiElement, name: String, results: MutableList<ResolveResult>) {
    when (lhs) {
        is ZigFile -> {
            val member = lhs.containerMembers[name] ?: return
            results.addLast(PsiElementResolveResult(member))
        }
        is ZigContainerDecl -> {
            val containerBody = lhs.containerDeclBody ?: return
            val member = containerBody.containerMembers[name] ?: return
            results.addLast(PsiElementResolveResult(member))
        }
        is ZigBuiltinCall -> {
            for (reference in lhs.references) {
                val target = reference.resolve() ?: continue
                resolveMember(target, name, results)
            }
        }
        is ZigPtrType -> {
            if (lhs.size != ZigPtrType.Size.ONE) {
                return
            }
            val child = lhs.child ?: return
            resolveMember(child, name, results)
        }
        is ZigIdentifier, is ZigFieldAccess -> {
            for (reference in lhs.references) {
                val target = reference.resolve() ?: continue
                when (target) {
                    is ZigVarDeclProto -> {
                        val typeExpr = target.declTypeExpr?.expr
                        if (typeExpr != null && !typeExpr.textMatches("type")) {
                            resolveMember(typeExpr, name, results)
                            continue
                        }
                        if (target.isVar()) {
                            continue
                        }
                        val varDecl = target.parent as? ZigVarDecl ?: continue
                        val initExpr = varDecl.varDeclInitExpr?.expr ?: continue
                        resolveMember(initExpr, name, results)
                    }
                    is ZigFnParamDecl -> {
                        val typeExpr = target.fnParamTypeExpr?.expr ?: continue
                        resolveMember(typeExpr, name, results)
                    }
                    is ZigContainerField -> {
                        val typeExpr = target.containerFieldTypeExpr?.expr ?: continue
                        resolveMember(typeExpr, name, results)
                    }
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
