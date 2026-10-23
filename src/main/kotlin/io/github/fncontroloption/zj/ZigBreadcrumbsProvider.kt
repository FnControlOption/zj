package io.github.fncontroloption.zj

import com.intellij.psi.PsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider

class ZigBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages() = arrayOf(ZigLanguage)

    override fun acceptElement(element: PsiElement) = when (element) {
        is ZigContainerExpression,
        is ZigErrorSet,
        is ZigFnDecl,
        is ZigTestDecl -> true
        else -> false
    }

    override fun getElementInfo(element: PsiElement) = when (element) {
        is ZigContainerExpression -> {
            val varDecl = element.parent as? ZigVarDecl
            varDecl?.name ?: buildString {
                append(element.containerKindElement.text)
                if (element.containerArgElement != null) {
                    append("(...)")
                }
                append(" {}")
            }
        }

        is ZigErrorSet -> {
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
