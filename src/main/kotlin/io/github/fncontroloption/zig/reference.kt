package io.github.fncontroloption.zig

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.util.ProcessingContext
import io.github.fncontroloption.zig.psi.ZigBlock
import io.github.fncontroloption.zig.psi.ZigBuiltinCall
import io.github.fncontroloption.zig.psi.ZigCallArgs
import io.github.fncontroloption.zig.psi.ZigContainer
import io.github.fncontroloption.zig.psi.ZigElementTypes
import io.github.fncontroloption.zig.psi.ZigFnDecl

class ZigReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigElementTypes.STRING_LITERAL),
            ZigStringLiteralReferenceProvider,
        )
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ZigElementTypes.IDENTIFIER),
            ZigIdentifierReferenceProvider,
        )
    }
}

object ZigStringLiteralReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<out PsiReference> {
        val callArgs = element.parent as? ZigCallArgs ?: return emptyArray()
        val builtinCall = callArgs.parent as? ZigBuiltinCall ?: return emptyArray()
        if (builtinCall.builtin.textMatches("@import")) {
            return arrayOf(ZigImportReference(element))
        }
        return arrayOf()
    }
}

class ZigImportReference(element: PsiElement) : PsiReferenceBase<PsiElement>(element, TextRange(1, element.textLength - 1)) {
    // TODO: unescape string literal
    val parts = element.text.substring(1, element.text.length - 1).split("/")

    override fun resolve(): PsiElement? {
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
}

object ZigIdentifierReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<out PsiReference> {
        return arrayOf(ZigIdentifierReference(element))
    }
}

class ZigIdentifierReference(element: PsiElement) : PsiReferenceBase<PsiElement>(element, TextRange(0, element.textLength)) {
    override fun resolve(): PsiElement? {
        val name = myElement.text
        var parent = myElement.parent
        while (parent != null) {
            val decl = when (parent) {
                is ZigContainer -> parent.decls[name]
                is ZigFnDecl -> parent.fnProto.fnParams?.fnParamDecls[name]
                is ZigBlock -> parent.varDecls[name]
                else -> null
            }
            if (decl != null) {
                return decl
            }
            parent = parent.parent
        }
        return null
    }
}
