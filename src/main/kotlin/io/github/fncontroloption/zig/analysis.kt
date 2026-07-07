package io.github.fncontroloption.zig

import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.findParentOfType
import io.github.fncontroloption.zig.psi2.*

object ZigAnalyzer {
    fun resolveValues(element: ZigElement): Set<ZigValue> {
        return io.github.fncontroloption.zig.resolveValues(element)
    }

    fun resolveValuesFromReferenceTo(element: ZigElement): Set<ZigValue> {
        return io.github.fncontroloption.zig.resolveValuesFromReferenceTo(element)
    }
}

sealed interface ZigValue {
    val typeOf: ZigValue
}

class ZigUnknown private constructor(override val typeOf: ZigValue) : ZigValue {
    override fun toString() = "ZigUnknown(typeOf=$typeOf)"

    companion object {
        fun of(type: ZigValue): ZigValue {
            return when (type) {
                ZigNullType -> ZigNullValue
                ZigUndefinedType -> ZigUndefinedValue
                ZigVoidType -> ZigVoidValue
                else -> ZigUnknown(type)
            }
        }
    }
}

data object ZigTypeType : ZigValue {
    override val typeOf = this
}

data object ZigBoolType : ZigValue {
    override val typeOf = ZigTypeType
}

data object ZigFalseValue : ZigValue {
    override val typeOf = ZigBoolType
}

data object ZigTrueValue : ZigValue {
    override val typeOf = ZigBoolType
}

data object ZigNullType : ZigValue {
    override val typeOf = ZigTypeType
}

data object ZigNullValue : ZigValue {
    override val typeOf = ZigNullType
}

data object ZigNoreturnType : ZigValue {
    override val typeOf = ZigTypeType
}

data object ZigUndefinedType : ZigValue {
    override val typeOf = ZigTypeType
}

data object ZigUndefinedValue : ZigValue {
    override val typeOf = ZigUndefinedType
}

data object ZigUsizeType : ZigValue {
    override val typeOf = ZigTypeType
}

data object ZigVoidType : ZigValue {
    override val typeOf = ZigTypeType
}

data object ZigVoidValue : ZigValue {
    override val typeOf = ZigVoidType
}

data class ZigContainerType(
    val element: ZigContainer,
    val typeParams: Map<ZigParamDecl, Set<ZigValue>>,
) : ZigValue {
    override val typeOf = ZigTypeType
}

data class ZigFunctionType(
    val element: ZigFnDeclOrProto,
    val typeParams: Map<ZigParamDecl, Set<ZigValue>>,
) : ZigValue {
    override val typeOf = ZigTypeType
}

data class ZigTypeParamType(val element: ZigParamDecl) : ZigValue {
    override val typeOf = ZigTypeType
}

data class ZigOptionalType(val child: ZigValue) : ZigValue {
    override val typeOf = ZigTypeType
}

data class ZigErrorUnionType(val payload: ZigValue) : ZigValue { // TODO: include error set
    override val typeOf = ZigTypeType
}

data class ZigPointerType(val size: ZigPointerSize, val child: ZigValue) : ZigValue {
    override val typeOf = ZigTypeType
}

data class ZigArrayType(val child: ZigValue) : ZigValue { // TODO: include len + sentinel
    override val typeOf = ZigTypeType
}

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
            resolveValues(childElement).asSequence().map { ZigOptionalType(it) }
        }

        is ZigErrorUnion -> {
            val payloadElement = element.payload ?: return emptySequence()
            resolveValues(payloadElement).asSequence().map { ZigErrorUnionType(it) }
        }

        is ZigArrayTypeElement -> {
            val childElement = element.arrayChildElement ?: return emptySequence()
            resolveValues(childElement).asSequence().map { ZigArrayType(it) }
        }

        is ZigPointerTypeElement -> {
            val childElement = element.pointerChildElement ?: return emptySequence()
            val size = element.pointerSize
            resolveValues(childElement).asSequence().map { ZigPointerType(size, it) }
        }

        is ZigGroupedExpression -> {
            val innerElement = element.innerElement ?: return emptySequence()
            resolveValues(innerElement).asSequence()
        }

        is ZigComptime -> {
            val innerElement = element.innerElement ?: return emptySequence()
            resolveValues(innerElement).asSequence()
        }

        is ZigAddressOf -> {
            val operandElement = element.operand ?: return emptySequence()
            resolveValues(operandElement).asSequence().map {
                ZigUnknown.of(ZigPointerType(ZigPointerSize.ONE, it.typeOf))
            }
        }

        is ZigUnwrapOptional -> {
            val operandElement = element.operand ?: return emptySequence()
            resolveValues(operandElement).asSequence().mapNotNull { operand ->
                when (val typeOfOperand = operand.typeOf) {
                    is ZigOptionalType -> ZigUnknown.of(typeOfOperand.child)
                    else -> null
                }
            }
        }

        is ZigTry -> {
            val operandElement = element.operand ?: return emptySequence()
            resolveValues(operandElement).asSequence().mapNotNull { operand ->
                when (val typeOfOperand = operand.typeOf) {
                    is ZigErrorUnionType -> ZigUnknown.of(typeOfOperand.payload)
                    else -> null
                }
            }
        }

        is ZigDeref -> {
            val operandElement = element.operand ?: return emptySequence()
            resolveValues(operandElement).asSequence().mapNotNull { operand ->
                when (val typeOfOperand = operand.typeOf) {
                    is ZigPointerType -> when (typeOfOperand.size) {
                        ZigPointerSize.ONE,
                        ZigPointerSize.C -> ZigUnknown.of(typeOfOperand.child)
                        ZigPointerSize.SLICE,
                        ZigPointerSize.MANY -> null
                    }
                    else -> null
                }
            }
        }

        is ZigIf -> {
            val thenValues = when (val thenElement = element.thenElement.innerElement) {
                null -> emptySequence()
                else -> resolveValues(thenElement).asSequence()
            }
            val elseValues = when (val thenElement = element.elseElement?.innerElement) {
                null -> emptySequence()
                else -> resolveValues(thenElement).asSequence()
            }
            // TODO: peer type resolution
            thenValues + elseValues
        }

        is ZigCatch -> {
            val leftValues = resolveValues(element.leftOperand).asSequence().mapNotNull { left ->
                when (val typeOfLeft = left.typeOf) {
                    is ZigErrorUnionType -> ZigUnknown.of(typeOfLeft.payload)
                    else -> null
                }
            }
            val rightValues = when (val rightElement = element.rightOperand) {
                null -> emptySequence()
                else -> resolveValues(rightElement).asSequence()
            }
            // TODO: peer type resolution?
            leftValues + rightValues
        }

        is ZigOrelse -> {
            val leftValues = resolveValues(element.leftOperand).asSequence().mapNotNull { left ->
                when (val typeOfLeft = left.typeOf) {
                    is ZigOptionalType -> ZigUnknown.of(typeOfLeft.child)
                    else -> null
                }
            }
            val rightValues = when (val rightElement = element.rightOperand) {
                null -> emptySequence()
                else -> resolveValues(rightElement).asSequence()
            }
            // TODO: peer type resolution?
            leftValues + rightValues
        }

        is ZigListInit -> {
            val typeElement = element.typeExpression
            if (typeElement != null) {
                return resolveValues(typeElement).asSequence().map { ZigUnknown.of(it) }
            }
            emptySequence() // TODO: ZigUnknown.of(ZigTupleType)
        }

        is ZigIdentifier -> {
            val primitiveValue = PRIMITIVE_VALUES[element.text]
            if (primitiveValue != null) {
                return sequenceOf(primitiveValue)
            }
            // TODO: handle all primitives
            if (element.isPrimitive) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            element.references.asSequence().flatMap { reference ->
                when (val target = reference.resolve()) {
                    null -> emptySequence()
                    else -> resolveValuesFromReferenceTo(target).asSequence()
                }
            }
        }

        is ZigFieldAccess -> {
            val name = element.identifierElement?.text ?: return emptySequence()
            resolveValues(element.leftOperand).asSequence().flatMap { left ->
                when (left) {
                    is ZigContainerType -> computeContainerDeclValues(left, name)
                    else -> when (val typeOfLeft = left.typeOf) {
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

        is ZigArrayAccess -> {
            resolveValues(element.leftOperand).asSequence().mapNotNull { left ->
                when (val typeOfLeft = left.typeOf) {
                    is ZigArrayType -> ZigUnknown.of(typeOfLeft.child)
                    is ZigPointerType -> when (typeOfLeft.size) {
                        ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                            is ZigArrayType -> ZigUnknown.of(pointerChild.child)
                            // TODO: ZigTupleType
                            else -> null
                        }
                        else -> ZigUnknown.of(typeOfLeft.child)
                    }
                    // TODO: ZigTupleType
                    else -> null
                }
            }
        }

        is ZigBuiltinCall -> when (element.builtinElement.text) {
            "@as" -> {
                val argElement = element.argumentElements.firstOrNull()?.innerElement ?: return emptySequence()
                resolveValues(argElement).asSequence().map { ZigUnknown.of(it) }
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

            // TODO
            else -> emptySequence()
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
                            else -> when (val typeOfLeft = left.typeOf) {
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
                when (val typeOfFunction = function.typeOf) {
                    is ZigFunctionType -> computeFunctionReturnValues(typeOfFunction, actualArgs)
                    else -> emptySequence()
                }
            }
        }

        is ZigBlock -> {
            val labelDecl = element.labelDecl ?: return sequenceOf(ZigUnknown.of(ZigVoidType))
            val labelName = labelDecl.identifierElement.text
            val traverser = SyntaxTraverser.psiTraverser(element).expand { it !is ZigContainerDecl }
            // TODO: peer type resolution
            traverser.asSequence().flatMap { descendant ->
                if (descendant is ZigBreak && labelName == descendant.labelRef?.identifierElement?.text) {
                    when (val breakValue = descendant.breakValue) {
                        null -> sequenceOf(ZigUnknown.of(ZigVoidType))
                        else -> resolveValues(breakValue).asSequence()
                    }
                } else {
                    emptySequence()
                }
            }
        }

        is ZigBreak,
        is ZigContinue,
        is ZigReturn,
        is ZigUnreachableLiteral -> sequenceOf(ZigUnknown.of(ZigNoreturnType))

        is ZigAdd -> emptySequence() // TODO
        is ZigAddSat -> emptySequence() // TODO
        is ZigAddWrap -> emptySequence() // TODO
        is ZigArrayCat -> emptySequence() // TODO
        is ZigBangEqual -> emptySequence() // TODO
        is ZigBitAnd -> emptySequence() // TODO
        is ZigBitNot -> emptySequence() // TODO
        is ZigBitOr -> emptySequence() // TODO
        is ZigBitXor -> emptySequence() // TODO
        is ZigBoolAnd -> emptySequence() // TODO
        is ZigBoolNot -> emptySequence() // TODO
        is ZigBoolOr -> emptySequence() // TODO
        is ZigCharLiteral -> emptySequence() // TODO
        is ZigDiv -> emptySequence() // TODO
        is ZigEnumLiteral -> emptySequence() // TODO
        is ZigEqualEqual -> emptySequence() // TODO
        is ZigErrorSetDecl -> emptySequence() // TODO
        is ZigErrorValue -> emptySequence() // TODO
        is ZigFor -> emptySequence() // TODO
        is ZigGreaterOrEqual -> emptySequence() // TODO
        is ZigGreaterThan -> emptySequence() // TODO
        is ZigLessOrEqual -> emptySequence() // TODO
        is ZigLessThan -> emptySequence() // TODO
        is ZigMergeErrorSets -> emptySequence() // TODO
        is ZigMod -> emptySequence() // TODO
        is ZigMul -> emptySequence() // TODO
        is ZigMulSat -> emptySequence() // TODO
        is ZigMultilineStringLiteral -> emptySequence() // TODO
        is ZigMulWrap -> emptySequence() // TODO
        is ZigNegation -> emptySequence() // TODO
        is ZigNegationWrap -> emptySequence() // TODO
        is ZigNumberLiteral -> emptySequence() // TODO
        is ZigShl -> emptySequence() // TODO
        is ZigShlSat -> emptySequence() // TODO
        is ZigShr -> emptySequence() // TODO
        is ZigSlice -> emptySequence() // TODO
        is ZigStringLiteral -> emptySequence() // TODO
        is ZigSub -> emptySequence() // TODO
        is ZigSubSat -> emptySequence() // TODO
        is ZigSubWrap -> emptySequence() // TODO
        is ZigSwitch -> emptySequence() // TODO
        is ZigWhile -> emptySequence() // TODO
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
                    val paramTypes = resolveValues(paramTypeElement)
                    if (paramTypes.contains(ZigTypeType)) {
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
            is ZigUnknown -> {
                substituteTypeParams(value.typeOf, typeParams).map { ZigUnknown.of(it) }
            }
            ZigTypeType,
            ZigBoolType,
            ZigFalseValue,
            ZigTrueValue,
            ZigNoreturnType,
            ZigNullType,
            ZigNullValue,
            ZigUndefinedType,
            ZigUndefinedValue,
            ZigUsizeType,
            ZigVoidType,
            ZigVoidValue -> sequenceOf(value)
            is ZigContainerType -> {
                if (value.typeParams.isEmpty()) {
                    sequenceOf(value)
                } else {
                    val newTypeParams = value.typeParams + typeParams
                    sequenceOf(ZigContainerType(value.element, newTypeParams))
                }
            }
            is ZigFunctionType -> {
                if (value.typeParams.isEmpty()) {
                    sequenceOf(value)
                } else {
                    val newTypeParams = value.typeParams + typeParams
                    sequenceOf(ZigFunctionType(value.element, newTypeParams))
                }
            }
            is ZigTypeParamType -> when (val values = typeParams[value.element]) {
                null -> emptySequence()
                else -> values.asSequence().flatMap { substituteTypeParams(it, typeParams) }
            }
            is ZigOptionalType -> {
                substituteTypeParams(value.child, typeParams).map { ZigOptionalType(it) }
            }
            is ZigErrorUnionType -> {
                substituteTypeParams(value.payload, typeParams).map { ZigErrorUnionType(it) }
            }
            is ZigArrayType -> {
                substituteTypeParams(value.child, typeParams).map { ZigArrayType(it) }
            }
            is ZigPointerType -> {
                substituteTypeParams(value.child, typeParams).map { ZigPointerType(value.size, it) }
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
    var values = resolveValuesFromReferenceTo(member).asSequence()
    if (type.typeParams.isNotEmpty()) {
        values = values.flatMap { substituteTypeParams(it, type.typeParams) }
    }
    return values
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
            ZigContainerKind.ENUM -> sequenceOf(ZigUnknown.of(type))
            ZigContainerKind.UNION -> emptySequence() // TODO: return instance of union tag if applicable
            else -> emptySequence()
        }
    }
    var values = resolveValuesFromReferenceTo(member).asSequence()
    if (type.typeParams.isNotEmpty()) {
        values = values.flatMap { substituteTypeParams(it, type.typeParams) }
    }
    return values
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
            sequenceOf(ZigUnknown.of(ZigFunctionType(target, typeParams)))
        }

        is ZigVarDecl -> {
            val typeElement = target.typeElement?.innerElement
            if (typeElement != null) {
                return resolveValues(typeElement).asSequence().map { ZigUnknown.of(it) }
            }
            val initElement = target.initializer ?: return emptySequence()
            when (target.mutability) {
                ZigMutability.CONST -> resolveValues(initElement).asSequence()
                ZigMutability.VAR -> resolveValues(initElement).asSequence().map { ZigUnknown.of(it.typeOf) }
            }
        }

        is ZigVarProto -> emptySequence() // TODO

        is ZigParamDecl -> {
            val typeElement = target.typeElement?.innerElement ?: return emptySequence()
            val types = resolveValues(typeElement)
            if (types.contains(ZigTypeType)) {
                return sequenceOf(ZigTypeParamType(target))
            }
            types.asSequence().map { ZigUnknown.of(it) }
        }

        is ZigContainerField -> {
            when (val typeElement = target.typeElement?.innerElement) {
                null -> {
                    // This is likely a tagged union field with an implicit `void` type.
                    sequenceOf(ZigUnknown.of(ZigVoidType))
                }
                else -> resolveValues(typeElement).asSequence().map { ZigUnknown.of(it) }
            }
        }

        is ZigPayloadItem -> {
            // TODO: val isPointer = target.isPointer
            when (val payloadElement = target.containingPayload) {
                is ZigThenPayload -> {
                    val conditionElement =
                        payloadElement.containingIf?.conditionElement?.innerElement
                            ?: payloadElement.containingWhile?.conditionElement?.innerElement
                            ?: return emptySequence()
                    resolveValues(conditionElement).asSequence().mapNotNull { condition ->
                        when (val typeOfCondition = condition.typeOf) {
                            is ZigOptionalType -> ZigUnknown.of(typeOfCondition.child)
                            is ZigErrorUnionType -> ZigUnknown.of(typeOfCondition.payload)
                            else -> null
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
                        return sequenceOf(ZigUnknown.of(ZigUsizeType))
                    }
                    resolveValues(inputElement).asSequence().mapNotNull { input ->
                        when (val typeOfInput = input.typeOf) {
                            is ZigArrayType -> ZigUnknown.of(typeOfInput.child)
                            is ZigPointerType -> when (typeOfInput.size) {
                                ZigPointerSize.SLICE -> ZigUnknown.of(typeOfInput.child)
                                ZigPointerSize.ONE -> when (typeOfInput.child) {
                                    is ZigArrayType -> ZigUnknown.of(typeOfInput.child)
                                    else -> null
                                }
                                else -> null
                            }
                            else -> null
                        }
                    }
                }
            }
        }

        else -> emptySequence()
    }
}

private fun computeFunctionReturnValues(
    type: ZigFunctionType,
    arguments: Sequence<Set<ZigValue>>,
): Sequence<ZigValue> {
    val typeParams = if (type.typeParams.isEmpty()) {
        emptyMap()
    } else {
        type.typeParams.toMutableMap().apply {
            for ((paramDecl, argValues) in type.element.parameters.zip(arguments)) {
                val paramTypeElement = paramDecl.typeElement?.innerElement ?: continue
                val paramTypes = resolveValues(paramTypeElement)
                if (paramTypes.contains(ZigTypeType)) {
                    this[paramDecl] = argValues
                }
            }
        }
    }
    val returnTypeElement = type.element.returnTypeElement?.innerElement ?: return emptySequence()
    val returnTypes = resolveValues(returnTypeElement)
    if (returnTypes.contains(ZigTypeType)) {
        val functionBodyElement = (type.element as? ZigFnDecl)?.body
        if (functionBodyElement != null) {
            val traverser = SyntaxTraverser.psiTraverser(functionBodyElement).expand { it !is ZigContainerDecl }
            return traverser.asSequence().flatMap { descendant ->
                if (descendant is ZigReturn) {
                    when (val returnValue = descendant.returnValue) {
                        null -> emptySequence()
                        else -> {
                            var values = resolveValues(returnValue).asSequence()
                            if (typeParams.isNotEmpty()) {
                                values = values.flatMap { substituteTypeParams(it, typeParams) }
                            }
                            values
                        }
                    }
                } else {
                    emptySequence()
                }
            }
        }
    }
    var values = returnTypes.asSequence()
    if (typeParams.isNotEmpty()) {
        values = values.flatMap { substituteTypeParams(it, typeParams) }
    }
    return values.map { ZigUnknown.of(it) }
}

private val PRIMITIVE_VALUES = mapOf(
    //"anyerror",
    //"anyframe",
    //"anyopaque",
    "bool" to ZigBoolType,
    //"c_int",
    //"c_long",
    //"c_longdouble",
    //"c_longlong",
    //"c_char",
    //"c_short",
    //"c_uint",
    //"c_ulong",
    //"c_ulonglong",
    //"c_ushort",
    //"comptime_float",
    //"comptime_int",
    //"f128",
    //"f16",
    //"f32",
    //"f64",
    //"f80",
    "false" to ZigFalseValue,
    //"isize",
    "noreturn" to ZigNoreturnType,
    "null" to ZigNullValue,
    "true" to ZigTrueValue,
    "type" to ZigTypeType,
    "undefined" to ZigUndefinedValue,
    "usize" to ZigUsizeType,
    "void" to ZigVoidType,
)
