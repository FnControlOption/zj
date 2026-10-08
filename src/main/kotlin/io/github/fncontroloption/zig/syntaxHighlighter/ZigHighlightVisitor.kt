package io.github.fncontroloption.zig.syntaxHighlighter

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import io.github.fncontroloption.zig.ZigAnalyzer
import io.github.fncontroloption.zig.ZigComptimeParameterValue
import io.github.fncontroloption.zig.ZigFunctionType
import io.github.fncontroloption.zig.psi2.*
import io.github.fncontroloption.zig.resolveReferences

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
            is ZigFnDecl -> {
                val identifier = element.nameIdentifier ?: return null
                createHighlightInfo(identifier, ZigHighlightInfoTypes.STATIC_METHOD)
            }

            is ZigFnParam -> {
                val identifier = element.nameIdentifier ?: return null
                if (ZigAnalyzer.createGenericParameterValue(element) is ZigComptimeParameterValue) {
                    return createHighlightInfo(identifier, ZigHighlightInfoTypes.COMPTIME_PARAMETER)
                }

                createHighlightInfo(identifier, ZigHighlightInfoTypes.PARAMETER)
            }

            is ZigVarDeclOrProto -> {
                val identifier = element.nameIdentifier ?: return null

                for (value in ZigAnalyzer.resolveValuesFromReferenceTo(element)) {
                    if (value.typeOf is ZigFunctionType) {
                        return createHighlightInfo(identifier, ZigHighlightInfoTypes.STATIC_METHOD)
                    }
                }

                val type = if (element is ZigVarDecl && !element.isLocal) {
                    when (element.mutability) {
                        ZigMutability.CONST -> ZigHighlightInfoTypes.CONSTANT
                        ZigMutability.VAR -> ZigHighlightInfoTypes.STATIC_FIELD
                    }
                } else {
                    when (element.mutability) {
                        ZigMutability.CONST -> ZigHighlightInfoTypes.LOCAL_VARIABLE
                        ZigMutability.VAR -> ZigHighlightInfoTypes.REASSIGNED_LOCAL_VARIABLE
                    }
                }
                createHighlightInfo(identifier, type)
            }

            is ZigPayloadItem -> {
                val identifier = element.nameIdentifier ?: return null
                createHighlightInfo(identifier, ZigHighlightInfoTypes.LOCAL_VARIABLE)
            }

            is ZigContainerFieldImpl -> {
                val identifier = element.nameIdentifier ?: return null
                val type = when (element.container.containerKind) {
                    ZigContainerKind.ENUM -> ZigHighlightInfoTypes.CONSTANT
                    else -> ZigHighlightInfoTypes.INSTANCE_FIELD
                }
                createHighlightInfo(identifier, type)
            }

            is ZigEnumLiteral -> {
                val identifier = element.identifierElement ?: return null
                createHighlightInfoFromReference(element, identifier)
            }

            is ZigIdentifier -> {
                if (element.textMatches("_")) {
                    return null
                }
                if (isZigPrimitiveText(element.text)) {
                    return createHighlightInfo(element, ZigHighlightInfoTypes.KEYWORD)
                }

                createHighlightInfoFromReference(element, element)
            }

            is ZigTestDecl -> {
                val name = element.nameElement ?: return null
                if (name.elementType !== ZigTokenType.IDENTIFIER) {
                    return null
                }
                createHighlightInfoFromReference(element, name)
            }

            is ZigFieldAccess -> {
                val name = element.identifierElement ?: return null
                val text = parseZigIdentifier(name) ?: return null
                for (value in ZigAnalyzer.resolveValues(element.leftOperand)) {
                    if (ZigAnalyzer.getBuiltinFieldType(value.typeOf, text) != null) {
                        return createHighlightInfo(name, ZigHighlightInfoTypes.INSTANCE_FIELD)
                    }
                }
                createHighlightInfoFromReference(element, name)
            }

            is ZigListInitItem -> {
                val name = element.identifierElement ?: return null
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
            val type = when (target) {
                is LightZigTupleField -> ZigHighlightInfoTypes.INSTANCE_FIELD
                else -> createHighlightInfo(target)?.type
            }
            if (type != null) {
                return createHighlightInfo(range, type)
            }
        }
        return null
    }
}
