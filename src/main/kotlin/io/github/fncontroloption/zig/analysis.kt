package io.github.fncontroloption.zig

import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
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
import io.github.fncontroloption.zig.psi2.ZigIf
import io.github.fncontroloption.zig.psi2.ZigMutability
import io.github.fncontroloption.zig.psi2.ZigOptionalTypeElement
import io.github.fncontroloption.zig.psi2.ZigParamDecl
import io.github.fncontroloption.zig.psi2.ZigPayloadItem
import io.github.fncontroloption.zig.psi2.ZigPointerSize
import io.github.fncontroloption.zig.psi2.ZigPointerTypeElement
import io.github.fncontroloption.zig.psi2.ZigReturn
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

data class ZigContainerType(
    val element: ZigContainer,
    val typeParams: Map<ZigParamDecl, Set<ZigValue>>,
) : ZigType

data class ZigFunctionType(
    val element: ZigFnDeclOrProto,
    val typeParams: Map<ZigParamDecl, Set<ZigValue>>,
) : ZigType

data class ZigTypeParamType(val element: ZigParamDecl) : ZigType

data class ZigOptionalType(val child: ZigType) : ZigType

data class ZigErrorUnionType(val payload: ZigType) : ZigType // TODO: include error set

data class ZigPointerType(val size: ZigPointerSize, val child: ZigType) : ZigType

// TODO: ZigArrayType

private fun resolveValues(element: ZigElement): Set<ZigValue> {
    return CachedValuesManager.getCachedValue(element) {
        RecursionManager.doPreventingRecursion(element, true) {
            val values = computeValuesUncached(element).toSet()
            CachedValueProvider.Result.create(values, PsiModificationTracker.MODIFICATION_COUNT)
        }
    } ?: emptySet()
}

private fun computeValuesUncached(element: ZigElement): Sequence<ZigValue> {
    return when (element) {
        is ZigContainerDecl -> {
            val typeParams = findUnboundTypeParams(element)
            sequenceOf(ZigContainerType(element, typeParams))
        }

        is ZigFnProto -> {
            val typeParams = findUnboundTypeParams(element)
            sequenceOf(ZigFunctionType(element, typeParams))
        }

        is ZigOptionalTypeElement -> {
            val childElement = element.optionalChildElement ?: return emptySequence()
            resolveValues(childElement)
                .asSequence()
                .filterIsInstance<ZigType>()
                .map { ZigOptionalType(it) }
        }

        is ZigErrorUnion -> {
            val payloadElement = element.payload ?: return emptySequence()
            resolveValues(payloadElement)
                .asSequence()
                .filterIsInstance<ZigType>()
                .map { ZigErrorUnionType(it) }
        }

        is ZigPointerTypeElement -> {
            val childElement = element.pointerChildElement ?: return emptySequence()
            val size = element.pointerSize
            resolveValues(childElement)
                .asSequence()
                .filterIsInstance<ZigType>()
                .map { ZigPointerType(size, it) }
        }

        is ZigGroupedExpression -> {
            val innerElement = element.innerElement ?: return emptySequence()
            resolveValues(innerElement).asSequence()
        }

        is ZigFieldAccess -> {
            val name = element.identifierElement?.text ?: return emptySequence()
            resolveValues(element.leftOperand).asSequence().flatMap { left ->
                when (left) {
                    is ZigContainerType -> computeContainerDeclValues(left, name)
                    is ZigType -> emptySequence()
                    is ZigOpaqueValue -> when (val typeOfLeft = left.type) {
                        is ZigContainerType -> computeContainerInstanceFieldValues(typeOfLeft, name)
                        is ZigPointerType -> when (typeOfLeft.size) {
                            ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                                is ZigContainerType -> computeContainerInstanceFieldValues(pointerChild, name)
                                else -> emptySequence()
                            }
                            else -> emptySequence()
                        }
                        else -> emptySequence()
                    }
                }
            }
        }

        is ZigIdentifier -> {
            if (element.text == "bool") {
                return sequenceOf(ZigBoolType)
            }
            // TODO: handle all primitives
            if (element.isPrimitive) {
                return emptySequence()
            }
            element.references.asSequence().flatMap { reference ->
                when (val target = reference.resolve()) {
                    null -> emptySequence()
                    else -> resolveValuesFromReferenceTo(target).asSequence()
                }
            }
        }

        is ZigBuiltinCall -> when (element.builtinElement.text) {
            "@as" -> {
                val argElement = element.argumentElements.firstOrNull()?.innerElement ?: return emptySequence()
                resolveValues(argElement)
                    .asSequence()
                    .filterIsInstance<ZigType>()
                    .map { ZigOpaqueValue(it) }
            }
            "@import" -> {
                val argElement = element.argumentElements.firstOrNull()?.innerElement ?: return emptySequence()
                if (argElement !is ZigStringLiteral) {
                    return emptySequence()
                }
                argElement.references.asSequence().mapNotNull { reference ->
                    when (val target = reference.resolve()) {
                        is ZigFile -> ZigContainerType(target, emptyMap())
                        else -> null
                    }
                }
            }
            "@This" -> {
                val containerElement = element.findParentOfType<ZigContainer>() ?: return emptySequence()
                val typeParams = findUnboundTypeParams(containerElement)
                sequenceOf(ZigContainerType(containerElement, typeParams))
            }
            else -> emptySequence() // TODO
        }

        is ZigAddressOf -> {
            val operandElement = element.operand ?: return emptySequence()
            resolveValues(operandElement)
                .asSequence()
                .filterIsInstance<ZigOpaqueValue>()
                .map { ZigOpaqueValue(ZigPointerType(ZigPointerSize.ONE, it.type)) }
        }

        is ZigTry -> {
            val operandElement = element.operand ?: return emptySequence()
            resolveValues(operandElement).asSequence().mapNotNull { operand ->
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
            val callInfoPairs = when (val functionElement = element.functionElement) {
                is ZigFieldAccess -> {
                    val name = functionElement.identifierElement?.text ?: return emptySequence()
                    resolveValues(functionElement.leftOperand).asSequence().flatMap { left ->
                        when (left) {
                            is ZigContainerType -> {
                                computeContainerDeclValues(left, name).map { Pair(null, it) }
                            }
                            is ZigType -> emptySequence()
                            is ZigOpaqueValue -> when (val typeOfLeft = left.type) {
                                is ZigContainerType -> {
                                    computeContainerDeclValues(typeOfLeft, name).map { Pair(left, it) }
                                }
                                is ZigPointerType -> when (typeOfLeft.size) {
                                    ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                                        is ZigContainerType -> {
                                            computeContainerDeclValues(pointerChild, name).map { Pair(left, it) }
                                        }
                                        else -> emptySequence()
                                    }
                                    else -> emptySequence()
                                }
                                else -> emptySequence()
                            }
                        }
                    }
                }
                else -> resolveValues(functionElement).asSequence().map { Pair(null, it) }
            }
            val initialArgs = element.arguments.map { resolveValues(it) }.toList()
            callInfoPairs.flatMap { (selfArg, function) ->
                val actualArgs = when (selfArg) {
                    null -> initialArgs.asSequence()
                    else -> sequenceOf(setOf(selfArg)) + initialArgs.asSequence()
                }
                when (function) {
                    is ZigType -> emptySequence()
                    is ZigOpaqueValue -> when (val typeOfFunction = function.type) {
                        is ZigFunctionType -> computeFunctionReturnValues(typeOfFunction, actualArgs)
                        else -> emptySequence()
                    }
                }
            }
        }

        is ZigBlock -> {
            val labelDecl = element.labelDecl ?: return emptySequence() // TODO: return void value
            val labelName = labelDecl.identifierElement.text
            val traverser = SyntaxTraverser.psiTraverser(element).expand { it !is ZigContainerDecl }
            traverser.asSequence().flatMap { descendant ->
                if (descendant is ZigBreak && labelName == descendant.labelRef?.identifierElement?.text) {
                    when (val breakValue = descendant.breakValue) {
                        null -> emptySequence() // TODO: return void value
                        else -> resolveValues(breakValue).asSequence()
                    }
                } else {
                    emptySequence()
                }
            }
        }

        is ZigIf -> {
            val thenElement = element.thenElement.innerElement
            val elseElement = element.elseElement?.innerElement
            val thenValues = if (thenElement != null) resolveValues(thenElement) else emptySet()
            val elseValues = if (elseElement != null) resolveValues(elseElement) else emptySet()
            thenValues.asSequence() + elseValues.asSequence()
        }

        else -> emptySequence()
    }
}

private fun findUnboundTypeParams(element: ZigElement): Map<ZigParamDecl, Set<ZigValue>> {
    return buildMap {
        var element: PsiElement? = element
        while (element != null) {
            if (element is ZigFnDecl) {
                for (paramDecl in element.parameters) {
                    val paramTypeElement = paramDecl.typeElement?.innerElement ?: continue
                    if (paramTypeElement.textMatches("type")) {
                        this[paramDecl] = emptySet()
                    }
                }
            }
            element = element.parent
        }
    }
}

private fun substituteTypeParams(
    value: ZigValue,
    typeParams: Map<ZigParamDecl, Set<ZigValue>>,
): Sequence<ZigValue> {
    return RecursionManager.doPreventingRecursion(value, true) {
        when (value) {
            is ZigOpaqueValue -> {
                substituteTypeParams(value.type, typeParams)
                    .filterIsInstance<ZigType>()
                    .map { ZigOpaqueValue(it) }
            }
            ZigBoolType -> sequenceOf(ZigBoolType)
            is ZigUnknownType -> sequenceOf(value)
            is ZigContainerType -> {
                val newTypeParams = value.typeParams + typeParams
                sequenceOf(ZigContainerType(value.element, newTypeParams))
            }
            is ZigFunctionType -> {
                val newTypeParams = value.typeParams + typeParams
                sequenceOf(ZigFunctionType(value.element, newTypeParams))
            }
            is ZigTypeParamType -> when (val values = typeParams[value.element]) {
                null -> emptySequence()
                else -> values.asSequence().flatMap { substituteTypeParams(it, typeParams) }
            }
            is ZigOptionalType -> {
                substituteTypeParams(value.child, typeParams)
                    .filterIsInstance<ZigType>()
                    .map { ZigOptionalType(it) }
            }
            is ZigErrorUnionType -> {
                substituteTypeParams(value.payload, typeParams)
                    .filterIsInstance<ZigType>()
                    .map { ZigErrorUnionType(it) }
            }
            is ZigPointerType -> {
                substituteTypeParams(value.child, typeParams)
                    .filterIsInstance<ZigType>()
                    .map { ZigPointerType(value.size, it) }
            }
        }
    } ?: emptySequence()
}

private fun computeContainerInstanceFieldValues(type: ZigContainerType, name: String): Sequence<ZigValue> {
    if (type.element.containerKind == ZigContainerKind.ENUM) {
        return emptySequence()
    }
    val member = type.element.members.firstOrNull {
        it is PsiNameIdentifierOwner && it.name == name
    }
    if (member == null || member !is ZigContainerField) {
        return emptySequence()
    }
    return resolveValuesFromReferenceTo(member)
        .asSequence()
        .flatMap { substituteTypeParams(it, type.typeParams) }
}

private fun computeContainerDeclValues(type: ZigContainerType, name: String): Sequence<ZigValue> {
    val member = type.element.members.firstOrNull {
        it is PsiNameIdentifierOwner && it.name == name
    }
    if (member == null) {
        return emptySequence()
    }
    if (member is ZigContainerField) {
        return when (type.element.containerKind) {
            ZigContainerKind.ENUM -> sequenceOf(ZigOpaqueValue(type))
            ZigContainerKind.UNION -> emptySequence() // TODO: return instance of union tag if applicable
            else -> emptySequence()
        }
    }
    return resolveValuesFromReferenceTo(member)
        .asSequence()
        .flatMap { substituteTypeParams(it, type.typeParams) }
}

private fun resolveValuesFromReferenceTo(target: ZigElement): Set<ZigValue> {
    return CachedValuesManager.getCachedValue(target) {
        RecursionManager.doPreventingRecursion(target, true) {
            val values = computeValuesFromReferenceUncached(target).toSet()
            CachedValueProvider.Result.create(values, PsiModificationTracker.MODIFICATION_COUNT)
        }
    } ?: emptySet()
}

private fun computeValuesFromReferenceUncached(target: ZigElement): Sequence<ZigValue> {
    return when (target) {
        is ZigFnDecl -> {
            val typeParams = findUnboundTypeParams(target)
            sequenceOf(ZigOpaqueValue(ZigFunctionType(target, typeParams)))
        }

        is ZigVarDecl -> {
            val typeElement = target.typeElement?.innerElement
            if (typeElement != null) {
                return resolveValues(typeElement)
                    .asSequence()
                    .filterIsInstance<ZigType>()
                    .map { ZigOpaqueValue(it) }
            }
            val initElement = target.initializer ?: return emptySequence()
            when (target.mutability) {
                ZigMutability.CONST -> resolveValues(initElement).asSequence()
                ZigMutability.VAR -> resolveValues(initElement).asSequence().map { init ->
                    when (init) {
                        is ZigType -> ZigUnknownType()
                        is ZigOpaqueValue -> ZigOpaqueValue(init.type)
                    }
                }
            }
        }

        is ZigParamDecl -> {
            val typeElement = target.typeElement?.innerElement ?: return emptySequence()
            if (typeElement.textMatches("type")) {
                return sequenceOf(ZigTypeParamType(target))
            }
            resolveValues(typeElement)
                .asSequence()
                .filterIsInstance<ZigType>()
                .map { ZigOpaqueValue(it) }
        }

        is ZigContainerField -> {
            val typeElement = target.typeElement?.innerElement
            if (typeElement == null) {
                // This is likely a tagged union field with an implicit `void` type.
                return emptySequence() // TODO: return void value
            }
            resolveValues(typeElement)
                .asSequence()
                .filterIsInstance<ZigType>()
                .map { ZigOpaqueValue(it) }
        }

        is ZigPayloadItem -> {
            when (val payloadElement = target.containingPayload) {
                is ZigThenPayload -> {
                    val conditionElement =
                        payloadElement.containingIf?.conditionElement?.innerElement
                            ?: payloadElement.containingWhile?.conditionElement?.innerElement
                            ?: return emptySequence()
                    resolveValues(conditionElement).asSequence().mapNotNull { condition ->
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
                is ZigElsePayload -> emptySequence() // TODO
                is ZigCatchPayload -> emptySequence() // TODO
                is ZigSwitchPayload -> emptySequence() // TODO
                is ZigForPayload -> {
                    val payloadIndex = payloadElement.items.indexOf(target)
                    val forInputs = payloadElement.containingFor.inputs
                    val inputElement = forInputs.elementAtOrNull(payloadIndex)?.innerElement ?: return emptySequence()
                    if (inputElement is ZigForRange) {
                        return emptySequence() // TODO: return usize value
                    }
                    resolveValues(inputElement).asSequence().mapNotNull { input ->
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

        else -> emptySequence() // TODO
    }
}

private fun computeFunctionReturnValues(
    type: ZigFunctionType,
    arguments: Sequence<Set<ZigValue>>,
): Sequence<ZigValue> {
    val typeParams = type.typeParams.toMutableMap()
    for ((paramDecl, argValues) in type.element.parameters.zip(arguments)) {
        val paramTypeElement = paramDecl.typeElement?.innerElement ?: continue
        if (paramTypeElement.textMatches("type")) {
            typeParams[paramDecl] = argValues
        }
    }
    val returnTypeElement = type.element.returnTypeElement?.innerElement ?: return emptySequence()
    if (returnTypeElement.textMatches("type")) {
        val functionBodyElement = (type.element as? ZigFnDecl)?.body
        if (functionBodyElement != null) {
            val traverser = SyntaxTraverser.psiTraverser(functionBodyElement).expand { it !is ZigContainerDecl }
            return traverser.asSequence().flatMap { descendant ->
                if (descendant is ZigReturn) {
                    when (val returnValue = descendant.returnValue) {
                        null -> emptySequence()
                        else -> {
                            resolveValues(returnValue)
                                .asSequence()
                                .flatMap { substituteTypeParams(it, typeParams) }
                        }
                    }
                } else {
                    emptySequence()
                }
            }
        }
    }
    return resolveValues(returnTypeElement)
        .asSequence()
        .flatMap { substituteTypeParams(it, typeParams) }
        .filterIsInstance<ZigType>()
        .map { ZigOpaqueValue(it) }
}
