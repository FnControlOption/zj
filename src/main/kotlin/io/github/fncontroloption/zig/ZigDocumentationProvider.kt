package io.github.fncontroloption.zig

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import io.github.fncontroloption.zig.psi.ZigFnDecl
import io.github.fncontroloption.zig.psi.ZigFnParamDecl
import io.github.fncontroloption.zig.psi.ZigFnProto
import io.github.fncontroloption.zig.psi.ZigTokenTypes
import io.github.fncontroloption.zig.psi.ZigVarDecl
import org.jetbrains.annotations.Nls

class ZigDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): @Nls String? {
        val docHolder = findDocHolder(element) ?: return null
        val doc = findDoc(docHolder) ?: return null
        return convertMarkdownToHtml(doc)
    }
}

private fun findDocHolder(element: PsiElement): PsiElement? {
    return when (element) {
        is ZigVarDecl, is ZigFnParamDecl -> element
        is ZigFnProto -> when (val parent = element.parent) {
            is ZigFnDecl -> parent
            else -> null
        }
        else -> null
    }
}

private fun findDoc(element: PsiElement): String? {
    val list = mutableListOf<String>()
    var prev = element.prevSibling
    while (true) {
        when (prev) {
            is PsiComment if prev.tokenType == ZigTokenTypes.DOC_COMMENT -> {
                val text = prev.text
                assert(text.startsWith("///"))
                list.add(text.substring(3))
            }
            is PsiWhiteSpace if !prev.textContains('\n') -> {
                // no-op
            }
            else -> break
        }
        prev = prev.prevSibling
    }
    if (list.isEmpty()) {
        return null
    }
    list.reverse()
    return list.joinToString("")
}
