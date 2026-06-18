package io.github.fncontroloption.zig

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.elementType
import com.intellij.util.ProcessingContext
import io.github.fncontroloption.zig.psi.ZigBlock
import io.github.fncontroloption.zig.psi.ZigBuiltinCall
import io.github.fncontroloption.zig.psi.ZigCallArgs
import io.github.fncontroloption.zig.psi.ZigContainerBody
import io.github.fncontroloption.zig.psi.ZigContainerDecl
import io.github.fncontroloption.zig.psi.ZigContainerField
import io.github.fncontroloption.zig.psi.ZigElementTypes
import io.github.fncontroloption.zig.psi.ZigFieldAccess
import io.github.fncontroloption.zig.psi.ZigFile
import io.github.fncontroloption.zig.psi.ZigFnDecl
import io.github.fncontroloption.zig.psi.ZigFnParamDecl
import io.github.fncontroloption.zig.psi.ZigIdentifier
import io.github.fncontroloption.zig.psi.ZigTestDecl
import io.github.fncontroloption.zig.psi.ZigTokenTypes
import io.github.fncontroloption.zig.psi.ZigVarDecl
import io.github.fncontroloption.zig.psi.ZigVarDeclProto

class ZigReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigElementTypes.STRING_LITERAL),
            ZigStringLiteralReferenceProvider,
        )
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
    }
}

object ZigStringLiteralReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        val callArgs = element.parent as? ZigCallArgs ?: return emptyArray()
        val builtinCall = callArgs.parent as? ZigBuiltinCall ?: return emptyArray()
        if (builtinCall.builtin.textMatches("@import")) {
            return arrayOf(ZigImportReference(element))
        }
        return emptyArray()
    }
}

class ZigImportReference(element: PsiElement) :
    PsiReferenceBase<PsiElement>(element, TextRange(1, element.textLength - 1), true) {

    // TODO: unescape string literal
    val parts = element.text.substring(1, element.text.length - 1).split("/")

    override fun resolve(): PsiElement? {
        val fileName = parts.lastOrNull() ?: return null
        when (fileName) {
            "std" -> {
                val projectService = myElement.project.getService(ZigProjectService::class.java)
                return projectService.stdFile
            }
            "builtin" -> {
                val projectService = myElement.project.getService(ZigProjectService::class.java)
                return projectService.builtinFile
            }
        }
        if (!fileName.endsWith(".zig")) {
            // TODO: resolve imported modules
            return null
        }
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
}

object ZigIdentifierReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        return arrayOf(ZigIdentifierReference(element))
    }
}

class ZigIdentifierReference(element: PsiElement) :
    PsiReferenceBase<PsiElement>(element, TextRange(0, element.textLength), true) {

    override fun resolve(): PsiElement? {
        val name = myElement.text
        var parent = myElement.parent
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

class ZigTestReference(decl: ZigTestDecl, val name: PsiElement) :
    PsiReferenceBase<ZigTestDecl>(decl, name.textRangeInParent, true) {

    private val ref = ZigIdentifierReference(name)

    override fun resolve() = ref.resolve()
}

object ZigFieldAccessReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<out PsiReference> {
        val access = element as ZigFieldAccess
        val name = access.fieldName ?: return emptyArray()
        return arrayOf(ZigFieldAccessReference(access, name))
    }
}

class ZigFieldAccessReference(val access: ZigFieldAccess, val name: PsiElement) :
    PsiPolyVariantReferenceBase<ZigFieldAccess>(access, name.textRangeInParent, true) {

    override fun multiResolve(incompleteCode: Boolean): Array<out ResolveResult> {
        val lhs = access.lhs ?: return emptyArray()
        val results = mutableListOf<ResolveResult>()
        resolveMember(lhs, name.text, results)
        return results.toTypedArray()
    }

    private fun resolveMember(lhs: PsiElement, name: String, results: MutableList<ResolveResult>) {
        when (lhs) {
            is ZigContainerDecl -> {
                val containerBody = lhs.containerDeclBody ?: return
                val declProto = containerBody.containerMembers[name] ?: return
                results.addLast(PsiElementResolveResult(declProto))
            }
            is ZigBuiltinCall -> {
                if (lhs.builtin.textMatches("@import")) {
                    val callArgs = lhs.builtinCallArgs ?: return
                    var child = callArgs.firstChild ?: return
                    if (child.elementType !== ZigTokenTypes.L_PAREN) {
                        return
                    }
                    child = child.nextSibling ?: return
                    if (child.elementType === ZigElementTypes.STRING_LITERAL) {
                        val importRef = ZigImportReference(child)
                        val file = importRef.resolve() as? ZigFile ?: return
                        val declProto = file.containerMembers[name] ?: return
                        results.addLast(PsiElementResolveResult(declProto))
                    }
                }
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
}
