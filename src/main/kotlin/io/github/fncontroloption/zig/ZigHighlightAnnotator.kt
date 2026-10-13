package io.github.fncontroloption.zig

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType

class ZigHighlightAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val (range, attributes) = getHighlightRangeAndAttributes(element) ?: return
        holder
            .newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(range)
            .textAttributes(attributes)
            .create()
    }
}

private fun getHighlightRangeAndAttributes(element: PsiElement): Pair<PsiElement, TextAttributesKey>? {
    return when (element) {
        is ZigFnDecl -> {
            val identifier = element.nameIdentifier ?: return null
            Pair(identifier, ZigHighlightingColors.STATIC_METHOD)
        }

        is ZigFnParam -> {
            val identifier = element.nameIdentifier ?: return null
            try {
                val genericValue = ZigAnalyzer.createGenericParameterValue(element)
                if (genericValue is ZigComptimeParameterValue) {
                    return Pair(identifier, ZigHighlightingColors.COMPTIME_PARAMETER)
                }
            } catch (_: IndexNotReadyException) {}
            Pair(identifier, ZigHighlightingColors.PARAMETER)
        }

        is ZigVarDeclOrProto -> {
            val identifier = element.nameIdentifier ?: return null
            try {
                val value = ZigAnalyzer.resolveValueFromReferenceTo(element)
                if (value.anyValue { it.typeOf is ZigFunctionType }) {
                    return Pair(identifier, ZigHighlightingColors.STATIC_METHOD)
                }
            } catch (_: IndexNotReadyException) {}
            val attributes = when (element.isLocal) {
                true -> when (element.mutability) {
                    ZigMutability.CONST -> ZigHighlightingColors.LOCAL_VARIABLE
                    ZigMutability.VAR -> ZigHighlightingColors.REASSIGNED_LOCAL_VARIABLE
                }
                false -> when (element.mutability) {
                    ZigMutability.CONST -> ZigHighlightingColors.CONSTANT
                    ZigMutability.VAR -> ZigHighlightingColors.STATIC_FIELD
                }
            }
            Pair(identifier, attributes)
        }

        is ZigPayloadItem -> {
            val identifier = element.nameIdentifier ?: return null
            Pair(identifier, ZigHighlightingColors.LOCAL_VARIABLE)
        }

        is ZigContainerFieldImpl -> {
            val identifier = element.nameIdentifier ?: return null
            val attributes = when (element.container.containerKind) {
                ZigContainerKind.ENUM -> ZigHighlightingColors.CONSTANT
                else -> ZigHighlightingColors.INSTANCE_FIELD
            }
            Pair(identifier, attributes)
        }

        is ZigEnumLiteral -> {
            val identifier = element.identifierElement ?: return null
            val attributes = getHighlightAttributesFromReference(element) ?: return null
            Pair(identifier, attributes)
        }

        is ZigIdentifier -> {
            if (element.textMatches("_")) {
                return null
            }
            if (isZigPrimitiveText(element.text)) {
                return Pair(element, ZigHighlightingColors.KEYWORD)
            }
            val attributes = getHighlightAttributesFromReference(element) ?: return null
            Pair(element, attributes)
        }

        is ZigTestDecl -> {
            val nameElement = element.nameElement ?: return null
            if (nameElement.elementType !== ZigTokenType.IDENTIFIER) return null
            val attributes = getHighlightAttributesFromReference(element) ?: return null
            Pair(nameElement, attributes)
        }

        is ZigFieldAccess -> {
            val identifier = element.identifierElement ?: return null
            val name = parseZigIdentifier(identifier) ?: return null
            try {
                val left = ZigAnalyzer.resolveValue(element.leftOperand)
                if (left.anyValue { ZigAnalyzer.getBuiltinFieldType(it.typeOf, name) != null }) {
                    return Pair(identifier, ZigHighlightingColors.INSTANCE_FIELD)
                }
            } catch (_: IndexNotReadyException) {}
            val attributes = getHighlightAttributesFromReference(element) ?: return null
            Pair(identifier, attributes)
        }

        is ZigListInitItem -> {
            val identifier = element.fieldNameIdentifier ?: return null
            val attributes = getHighlightAttributesFromReference(element) ?: return null
            Pair(identifier, attributes)
        }

        else -> null
    }
}

private fun getHighlightAttributesFromReference(element: PsiElement): TextAttributesKey? {
    try {
        for (target in resolveReferences(element)) {
            when (target) {
                is LightZigTupleField -> return ZigHighlightingColors.INSTANCE_FIELD
                else -> {
                    val (_, attributes) = getHighlightRangeAndAttributes(target) ?: continue
                    return attributes
                }
            }
        }
    } catch (_: IndexNotReadyException) {}
    return null
}
