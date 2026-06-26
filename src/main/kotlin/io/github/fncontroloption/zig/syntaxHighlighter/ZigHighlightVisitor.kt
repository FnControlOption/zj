package io.github.fncontroloption.zig.syntaxHighlighter

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import io.github.fncontroloption.zig.followReferencesRecursive
import io.github.fncontroloption.zig.psi.*
import io.github.fncontroloption.zig.resolveReferences
import java.util.regex.Pattern

private val PRIMITIVES = setOf(
    "anyerror",
    "anyframe",
    "anyopaque",
    "bool",
    "c_int",
    "c_long",
    "c_longdouble",
    "c_longlong",
    "c_char",
    "c_short",
    "c_uint",
    "c_ulong",
    "c_ulonglong",
    "c_ushort",
    "comptime_float",
    "comptime_int",
    "f128",
    "f16",
    "f32",
    "f64",
    "f80",
    "false",
    "isize",
    "noreturn",
    "null",
    "true",
    "type",
    "undefined",
    "usize",
    "void"
)

private val INT_PATTERN: Pattern = Pattern.compile("\\A[iu]\\d+\\Z")

/** @see com.intellij.codeInsight.daemon.impl.analysis.JavaNamesHighlightVisitor */
class ZigHighlightVisitor : HighlightVisitor {
    private var holder: HighlightInfoHolder? = null

    override fun suitableForFile(psiFile: PsiFile): Boolean {
        return psiFile is ZigFile
    }

    override fun visit(element: PsiElement) {
        val info = createHighlightInfo(element)
        if (info != null) {
            holder!!.add(info)
        }
    }

    private fun createHighlightInfo(element: PsiElement): HighlightInfo? {
        return when (element) {
            is ZigFnProto -> {
                val identifier = element.nameIdentifier ?: return null
                createHighlightInfo(identifier, ZigHighlightInfoTypes.STATIC_METHOD)
            }

            is ZigFnParamDecl -> {
                val identifier = element.nameIdentifier ?: return null
                val paramTypeExpr = element.fnParamTypeExpr ?: return null
                val paramType = paramTypeExpr.expr
                if (paramType is ZigIdentifier && paramType.textMatches("type")) {
                    return createHighlightInfo(identifier, ZigHighlightInfoTypes.TYPE_PARAMETER)
                }

                createHighlightInfo(identifier, ZigHighlightInfoTypes.PARAMETER)
            }

            is ZigVarDeclProto -> {
                val identifier = element.nameIdentifier ?: return null

                if (element.isConst()) {
                    val parent = element.parent
                    if (parent is ZigVarDecl) {
                        val initExpr = parent.varDeclInitExpr
                        if (initExpr != null) {
                            val expr = initExpr.expr
                            if (expr != null) {
                                for (target in followReferencesRecursive(expr, mutableSetOf())) {
                                    if (target is ZigFnProto) {
                                        return createHighlightInfo(identifier, ZigHighlightInfoTypes.STATIC_METHOD)
                                    }
                                }
                            }
                        }
                    }
                }

                val type = when (element.declType) {
                    ZigVarDeclProto.Type.LOCAL_CONST -> ZigHighlightInfoTypes.LOCAL_VARIABLE
                    ZigVarDeclProto.Type.LOCAL_VAR -> ZigHighlightInfoTypes.REASSIGNED_LOCAL_VARIABLE
                    ZigVarDeclProto.Type.GLOBAL_CONST -> ZigHighlightInfoTypes.CONSTANT
                    ZigVarDeclProto.Type.GLOBAL_VAR -> ZigHighlightInfoTypes.STATIC_FIELD
                }
                createHighlightInfo(identifier, type)
            }

            is ZigContainerField -> {
                val identifier = element.nameIdentifier ?: return null

                val containerDecl = element.parentOfType<ZigContainerDecl>()
                if (containerDecl != null && containerDecl.isEnum()) {
                    return createHighlightInfo(identifier, ZigHighlightInfoTypes.CONSTANT)
                }

                createHighlightInfo(identifier, ZigHighlightInfoTypes.INSTANCE_FIELD)
            }

            is ZigIdentifier -> {
                val text = element.text

                if (PRIMITIVES.contains(text) || INT_PATTERN.matcher(text).matches()) {
                    return createHighlightInfo(element, ZigHighlightInfoTypes.KEYWORD)
                }

                createHighlightInfoFromReference(element, element)
            }

            is ZigFieldAccess -> {
                val name = element.fieldName ?: return null
                createHighlightInfoFromReference(element, name)
            }

            is ZigEnumLiteral -> {
                val name = element.fieldName ?: return null
                createHighlightInfo(name, ZigHighlightInfoTypes.CONSTANT)
            }

            is ZigInitListEntry -> {
                val name = element.fieldName ?: return null
                createHighlightInfoFromReference(element, name)
            }

            is ZigTestDecl -> {
                val name = element.testName ?: return null
                createHighlightInfoFromReference(element, name)
            }

            else -> null
        }
    }

    override fun analyze(
        psiFile: PsiFile,
        updateWholeFile: Boolean,
        holder: HighlightInfoHolder,
        action: Runnable
    ): Boolean {
        this.holder = holder
        try {
            action.run()
        } finally {
            this.holder = null
        }
        return true
    }

    override fun clone() = ZigHighlightVisitor()

    private fun createHighlightInfo(element: PsiElement, type: HighlightInfoType): HighlightInfo? {
        return HighlightInfo.newHighlightInfo(type).range(element).create()
    }

    private fun createHighlightInfoFromReference(element: PsiElement, range: PsiElement): HighlightInfo? {
        for (target in resolveReferences(element)) {
            val info = createHighlightInfo(target)
            if (info != null) {
                return createHighlightInfo(range, info.type)
            }
        }
        return null
    }
}
