package io.github.fncontroloption.zig

import com.intellij.psi.PsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import io.github.fncontroloption.zig.psi2.ZigContainerDecl
import io.github.fncontroloption.zig.psi2.ZigErrorSetDecl
import io.github.fncontroloption.zig.psi2.ZigFnDecl
import io.github.fncontroloption.zig.psi2.ZigTestDecl
import io.github.fncontroloption.zig.psi2.ZigVarDecl

class ZigBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages() = arrayOf(ZigLanguage)

    override fun acceptElement(element: PsiElement) = when (element) {
        is ZigContainerDecl,
        is ZigErrorSetDecl,
        is ZigFnDecl,
        is ZigTestDecl -> true
        else -> false
    }

    override fun getElementInfo(element: PsiElement) = when (element) {
        is ZigContainerDecl -> {
            val varDecl = element.parent as? ZigVarDecl
            varDecl?.name ?: buildString {
                append(element.containerKindElement.text)
                if (element.containerTagElement != null) {
                    append("(...)")
                }
                append(" {}")
            }
        }

        is ZigErrorSetDecl -> {
            val varDecl = element.parent as? ZigVarDecl
            varDecl?.name ?: "error{}"
        }

        is ZigFnDecl -> "${element.name ?: "..."}()"

        is ZigTestDecl -> {
            val name = element.nameElement?.text
            if (name != null) "test $name" else "test"
        }

        else -> throw IllegalArgumentException()
    }
}
