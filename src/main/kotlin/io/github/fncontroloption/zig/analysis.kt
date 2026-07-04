package io.github.fncontroloption.zig

import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.findParentOfType
import io.github.fncontroloption.zig.psi2.ZigAddressOf
import io.github.fncontroloption.zig.psi2.ZigBlock
import io.github.fncontroloption.zig.psi2.ZigBreak
import io.github.fncontroloption.zig.psi2.ZigBuiltinCall
import io.github.fncontroloption.zig.psi2.ZigCall
import io.github.fncontroloption.zig.psi2.ZigCatchPayload
import io.github.fncontroloption.zig.psi2.ZigContainer
import io.github.fncontroloption.zig.psi2.ZigContainerDecl
import io.github.fncontroloption.zig.psi2.ZigContainerField
import io.github.fncontroloption.zig.psi2.ZigContainerKind
import io.github.fncontroloption.zig.psi2.ZigElement
import io.github.fncontroloption.zig.psi2.ZigElsePayload
import io.github.fncontroloption.zig.psi2.ZigErrorUnion
import io.github.fncontroloption.zig.psi2.ZigFieldAccess
import io.github.fncontroloption.zig.psi2.ZigFile
import io.github.fncontroloption.zig.psi2.ZigFnDecl
import io.github.fncontroloption.zig.psi2.ZigFnDeclOrProto
import io.github.fncontroloption.zig.psi2.ZigFnProto
import io.github.fncontroloption.zig.psi2.ZigForPayload
import io.github.fncontroloption.zig.psi2.ZigForRange
import io.github.fncontroloption.zig.psi2.ZigGroupedExpression
import io.github.fncontroloption.zig.psi2.ZigIdentifier
import io.github.fncontroloption.zig.psi2.ZigMutability
import io.github.fncontroloption.zig.psi2.ZigOptionalTypeElement
import io.github.fncontroloption.zig.psi2.ZigParamDecl
import io.github.fncontroloption.zig.psi2.ZigPayloadItem
import io.github.fncontroloption.zig.psi2.ZigPointerSize
import io.github.fncontroloption.zig.psi2.ZigPointerTypeElement
import io.github.fncontroloption.zig.psi2.ZigStringLiteral
import io.github.fncontroloption.zig.psi2.ZigSwitchPayload
import io.github.fncontroloption.zig.psi2.ZigThenPayload
import io.github.fncontroloption.zig.psi2.ZigTry
import io.github.fncontroloption.zig.psi2.ZigVarDecl

object ZigAnalyzer {
    fun resolveValues(element: ZigElement): Set<ZigValue> {
        return io.github.fncontroloption.zig.resolveValues(element)
    }

    fun resolveValuesFromReferenceTo(element: ZigElement): Set<ZigValue> {
        return io.github.fncontroloption.zig.resolveValuesFromReferenceTo(element)
    }
}

sealed interface ZigValue

class ZigOpaqueValue(val type: ZigType) : ZigValue

sealed interface ZigType : ZigValue

class ZigUnknownType : ZigType

data object ZigBoolType : ZigType

data class ZigContainerType(val element: ZigContainer) : ZigType

data class ZigFunctionType(val element: ZigFnDeclOrProto) : ZigType

data class ZigOptionalType(val child: ZigType) : ZigType

data class ZigErrorUnionType(val payload: ZigType) : ZigType // TODO: include error set

data class ZigPointerType(val size: ZigPointerSize, val child: ZigType) : ZigType

// TODO: ZigArrayType

private fun resolveValues(element: ZigElement): Set<ZigValue> {
    return CachedValuesManager.getCachedValue(element) {
        val values = computeValuesUncached(element).toSet()
        CachedValueProvider.Result.create(values, PsiModificationTracker.MODIFICATION_COUNT)
    }
}

private fun computeValuesUncached(element: ZigElement): Collection<ZigValue> {
    return when (element) {
        is ZigContainerDecl -> listOf(ZigContainerType(element))

        is ZigFnProto -> listOf(ZigFunctionType(element))

        is ZigOptionalTypeElement -> {
            val childElement = element.optionalChildElement ?: return emptyList()
            resolveValues(childElement).filterIsInstance<ZigType>().map { ZigOptionalType(it) }
        }

        is ZigErrorUnion -> {
            val payloadElement = element.payload ?: return emptyList()
            resolveValues(payloadElement).filterIsInstance<ZigType>().map { ZigErrorUnionType(it) }
        }

        is ZigPointerTypeElement -> {
            val childElement = element.pointerChildElement ?: return emptyList()
            val size = element.pointerSize
            resolveValues(childElement).filterIsInstance<ZigType>().map { ZigPointerType(size, it) }
        }

        is ZigGroupedExpression -> {
            val innerElement = element.innerElement ?: return emptyList()
            resolveValues(innerElement)
        }

        is ZigFieldAccess -> {
            val name = element.identifierElement?.text ?: return emptyList()
            resolveValues(element.leftOperand).flatMap { left ->
                when (left) {
                    is ZigContainerType -> computeContainerDeclValues(left, name)
                    is ZigType -> emptyList()
                    is ZigOpaqueValue -> when (val typeOfLeft = left.type) {
                        is ZigContainerType -> computeContainerInstanceFieldValues(typeOfLeft, name)
                        is ZigPointerType -> when (typeOfLeft.size) {
                            ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                                is ZigContainerType -> computeContainerInstanceFieldValues(pointerChild, name)
                                else -> emptyList()
                            }
                            else -> emptyList()
                        }
                        else -> emptyList()
                    }
                }
            }
        }

        is ZigIdentifier -> {
            if (element.text == "bool") {
                return listOf(ZigBoolType)
            }
            // TODO: handle all primitives
            if (element.isPrimitive) {
                return emptyList()
            }
            element.references.flatMap { reference ->
                when (val target = reference.resolve()) {
                    null -> emptyList()
                    else -> computeValuesFromReferenceTo(target)
                }
            }
        }

        is ZigBuiltinCall -> when (element.builtinElement.text) {
            "@as" -> {
                val argElement = element.argumentElements.firstOrNull()?.innerElement ?: return emptyList()
                resolveValues(argElement).filterIsInstance<ZigType>().map { ZigOpaqueValue(it) }
            }
            "@import" -> {
                val argElement = element.argumentElements.firstOrNull()?.innerElement ?: return emptyList()
                if (argElement !is ZigStringLiteral) {
                    return emptyList()
                }
                argElement.references.mapNotNull { reference ->
                    when (val target = reference.resolve()) {
                        is ZigFile -> ZigContainerType(target)
                        else -> null
                    }
                }
            }
            "@This" -> {
                val containerElement = element.findParentOfType<ZigContainer>() ?: return emptyList()
                listOf(ZigContainerType(containerElement))
            }
            else -> emptyList() // TODO
        }

        is ZigAddressOf -> {
            val operandElement = element.operand ?: return emptyList()
            resolveValues(operandElement)
                .filterIsInstance<ZigOpaqueValue>()
                .map { ZigOpaqueValue(ZigPointerType(ZigPointerSize.ONE, it.type)) }
        }

        is ZigTry -> {
            val operandElement = element.operand ?: return emptyList()
            resolveValues(operandElement).mapNotNull { operand ->
                when (operand) {
                    is ZigOpaqueValue -> when (val typeOfOperand = operand.type) {
                        is ZigErrorUnionType -> ZigOpaqueValue(typeOfOperand.payload)
                        else -> null
                    }
                    else -> null
                }
            }
        }

        is ZigCall -> {
            val functionValues = when (val functionElement = element.functionElement) {
                is ZigFieldAccess -> {
                    val name = functionElement.identifierElement?.text ?: return emptyList()
                    resolveValues(functionElement.leftOperand).flatMap { left ->
                        when (left) {
                            is ZigContainerType -> computeContainerDeclValues(left, name)
                            is ZigType -> emptyList()
                            is ZigOpaqueValue -> when (val typeOfLeft = left.type) {
                                is ZigContainerType -> computeContainerDeclValues(typeOfLeft, name)
                                is ZigPointerType -> when (typeOfLeft.size) {
                                    ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                                        is ZigContainerType -> computeContainerDeclValues(pointerChild, name)
                                        else -> emptyList()
                                    }
                                    else -> emptyList()
                                }
                                else -> emptyList()
                            }
                        }
                    }
                }
                else -> resolveValues(functionElement)
            }
            functionValues.flatMap { function ->
                when (function) {
                    is ZigType -> emptyList()
                    is ZigOpaqueValue -> when (val typeOfFunction = function.type) {
                        is ZigFunctionType -> {
                            val returnTypeElement = typeOfFunction.element.returnTypeElement?.innerElement
                            if (returnTypeElement != null) {
                                resolveValues(returnTypeElement).filterIsInstance<ZigType>().map { ZigOpaqueValue(it) }
                            } else {
                                emptyList()
                            }
                        }
                        else -> emptyList()
                    }
                }
            }
        }

        is ZigBlock -> {
            val labelDecl = element.labelDecl ?: return emptyList() // TODO: return void value
            val labelName = labelDecl.identifierElement.text
            val traverser = SyntaxTraverser.psiTraverser(element).expand { it !is ZigContainerDecl }
            val values = mutableSetOf<ZigValue>()
            for (descendant in traverser) {
                if (descendant is ZigBreak) {
                    val labelRef = descendant.labelRef ?: continue
                    if (labelName == labelRef.identifierElement.text) {
                        when (val breakValue = descendant.breakValue) {
                            null -> {} // TODO: add void value
                            else -> values.addAll(resolveValues(breakValue))
                        }
                    }
                }
            }
            values
        }

        else -> emptyList()
    }
}

private fun computeContainerInstanceFieldValues(type: ZigContainerType, name: String): Collection<ZigValue> {
    if (type.element.containerKind == ZigContainerKind.ENUM) {
        return emptyList()
    }
    val member = type.element.members.firstOrNull {
        it is PsiNameIdentifierOwner && it.name == name
    }
    if (member == null || member !is ZigContainerField) {
        return emptyList()
    }
    return resolveValuesFromReferenceTo(member)
}

private fun computeContainerDeclValues(type: ZigContainerType, name: String): Collection<ZigValue> {
    val member = type.element.members.firstOrNull {
        it is PsiNameIdentifierOwner && it.name == name
    }
    if (member == null) {
        return emptyList()
    }
    if (member is ZigContainerField) {
        return when (type.element.containerKind) {
            ZigContainerKind.ENUM -> listOf(ZigOpaqueValue(type))
            ZigContainerKind.UNION -> emptyList() // TODO: return instance of union tag if applicable
            else -> emptyList()
        }
    }
    return resolveValuesFromReferenceTo(member)
}

private fun resolveValuesFromReferenceTo(target: ZigElement): Set<ZigValue> {
    return CachedValuesManager.getCachedValue(target) {
        val values = computeValuesFromReferenceTo(target).toSet()
        CachedValueProvider.Result.create(values, PsiModificationTracker.MODIFICATION_COUNT)
    }
}

private fun computeValuesFromReferenceTo(target: ZigElement): Collection<ZigValue> {
    return when (target) {
        is ZigFnDecl -> listOf(ZigOpaqueValue(ZigFunctionType(target)))

        is ZigVarDecl -> {
            val typeElement = target.typeElement?.innerElement
            if (typeElement != null) {
                return resolveValues(typeElement).filterIsInstance<ZigType>().map { ZigOpaqueValue(it) }
            }
            val initElement = target.initializer ?: return emptyList()
            when (target.mutability) {
                ZigMutability.CONST -> resolveValues(initElement)
                ZigMutability.VAR -> resolveValues(initElement).map { init ->
                    when (init) {
                        is ZigType -> ZigUnknownType()
                        is ZigOpaqueValue -> ZigOpaqueValue(init.type)
                    }
                }
            }
        }

        is ZigParamDecl -> {
            val typeElement = target.typeElement?.innerElement ?: return emptyList()
            resolveValues(typeElement).filterIsInstance<ZigType>().map { ZigOpaqueValue(it) }
        }

        is ZigContainerField -> {
            val typeElement = target.typeElement?.innerElement
            if (typeElement == null) {
                // This is likely a tagged union field with an implicit `void` type.
                return emptyList() // TODO: return void value
            }
            resolveValues(typeElement).filterIsInstance<ZigType>().map { ZigOpaqueValue(it) }
        }

        is ZigPayloadItem -> {
            when (val payloadElement = target.containingPayload) {
                is ZigThenPayload -> {
                    val conditionElement =
                        payloadElement.containingIf?.conditionElement?.innerElement
                            ?: payloadElement.containingWhile?.conditionElement?.innerElement
                            ?: return emptyList()
                    resolveValues(conditionElement).mapNotNull { condition ->
                        when (condition) {
                            is ZigType -> null
                            is ZigOpaqueValue -> when (val typeOfCondition = condition.type) {
                                is ZigOptionalType -> ZigOpaqueValue(typeOfCondition.child)
                                is ZigErrorUnionType -> ZigOpaqueValue(typeOfCondition.payload)
                                else -> null
                            }
                        }
                    }
                }
                is ZigElsePayload -> emptyList() // TODO
                is ZigCatchPayload -> emptyList() // TODO
                is ZigSwitchPayload -> emptyList() // TODO
                is ZigForPayload -> {
                    val payloadIndex = payloadElement.items.indexOf(target)
                    val forInputs = payloadElement.containingFor.inputs
                    val inputElement = forInputs.elementAtOrNull(payloadIndex)?.innerElement ?: return emptyList()
                    if (inputElement is ZigForRange) {
                        return emptyList() // TODO: return usize value
                    }
                    resolveValues(inputElement).mapNotNull { input ->
                        when (input) {
                            is ZigType -> null
                            is ZigOpaqueValue -> when (val typeOfInput = input.type) {
                                is ZigPointerType -> when (typeOfInput.size) {
                                    ZigPointerSize.SLICE -> ZigOpaqueValue(typeOfInput.child)
                                    ZigPointerSize.ONE -> when (typeOfInput.child) {
                                        // is ZigArrayType -> TODO
                                        else -> null
                                    }
                                    else -> null
                                }
                                // is ZigArrayType -> TODO
                                else -> null
                            }
                        }
                    }
                }
            }
        }

        else -> emptyList() // TODO
    }
}
