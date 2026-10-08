package io.github.fncontroloption.zig

import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parents
import io.github.fncontroloption.zig.psi2.ZigExpression

class ZigExpressionTypeProvider : ExpressionTypeProvider<ZigExpression>() {
    override fun getInformationHint(element: ZigExpression): String {
        val value = ZigAnalyzer.resolveValue(element)
        return StringUtil.escapeXmlEntities(value.typeOf.toPlainText())
    }

    override fun getErrorHint(): String {
        return "No expression found"
    }

    override fun getExpressionsAt(elementAt: PsiElement): List<ZigExpression> {
        return elementAt.parents(withSelf = true).filterIsInstance<ZigExpression>().toList()
    }
}
