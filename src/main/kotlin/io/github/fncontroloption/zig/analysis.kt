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

    fun resolveValuesFromReferenceTo(target: ZigElement): Set<ZigValue> {
        return io.github.fncontroloption.zig.resolveValuesFromReferenceTo(target)
    }

    fun substituteTypeParams(
        value: ZigValue,
        typeParams: Map<ZigParamDecl, Set<ZigValue>>,
    ): Sequence<ZigValue> {
        return io.github.fncontroloption.zig.substituteTypeParams(value, typeParams)
    }

    fun inferResultTypes(childElement: ZigElement): Sequence<ZigValue> {
        return io.github.fncontroloption.zig.inferResultTypes(childElement)
    }
}

sealed interface ZigValue {
    val typeOf: ZigValue
    fun toText(): String
}

class ZigUnknown private constructor(override val typeOf: ZigValue) : ZigValue {
    override fun toText(): String {
        if (typeOf is ZigUnknown && typeOf.typeOf == ZigTypeType) {
            return "_"
        }
        return "@as(${typeOf.toText()}, _)"
    }

    override fun toString() = "ZigUnknown(typeOf=$typeOf)"

    companion object {
        fun of(type: ZigValue): ZigValue {
            return when (type) {
                ZigNullType -> ZigNullValue
                ZigUndefinedType -> ZigUndefinedValue
                ZigVoidType -> ZigVoidValue
                is ZigFixedIntType if type.bits == 0 -> ZigIntValue(type, 0)
                else -> ZigUnknown(type)
            }
        }

        fun ofUnknownType() = ZigUnknown(ZigUnknown(ZigTypeType))
    }
}

data object ZigTypeType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "type"
}

data object ZigAnyerrorType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "anyerror"
}

data object ZigAnyopaqueType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "anyopaque"
}

data object ZigBoolType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "bool"
}

data object ZigFalseValue : ZigValue {
    override val typeOf: ZigBoolType
        get() = ZigBoolType

    override fun toText() = "false"
}

data object ZigTrueValue : ZigValue {
    override val typeOf: ZigBoolType
        get() = ZigBoolType

    override fun toText() = "true"
}

data object ZigNullType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "@TypeOf(null)"
}

data object ZigNullValue : ZigValue {
    override val typeOf: ZigNullType
        get() = ZigNullType

    override fun toText() = "null"
}

data object ZigNoreturnType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "noreturn"
}

data object ZigUndefinedType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "@TypeOf(undefined)"
}

data object ZigUndefinedValue : ZigValue {
    override val typeOf: ZigUndefinedType
        get() = ZigUndefinedType

    override fun toText() = "undefined"
}

data object ZigVoidType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "void"
}

data object ZigVoidValue : ZigValue {
    override val typeOf: ZigVoidType
        get() = ZigVoidType

    override fun toText() = "{}"
}

data object ZigEnumLiteralType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "@EnumLiteral()"
}

data class ZigContainerType(
    val element: ZigContainer,
    val typeParams: Map<ZigParamDecl, Set<ZigValue>>,
) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = getFullNameStartingFrom(element, typeParams)
}

data class ZigFunctionType(
    val element: ZigFnDeclOrProto,
    val typeParams: Map<ZigParamDecl, Set<ZigValue>>,
) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = buildString {
            append("fn (")
            for ((i, param) in element.parameters.withIndex()) {
                if (i > 0) {
                    append(", ")
                }
                val paramTypes = param.typeElement?.innerElement?.let { resolveValues(it) } ?: emptySet()
                append(paramTypes.singleOrNull()?.toText() ?: "_")
            }
            append(") ")
            val returnTypes = element.returnTypeElement?.innerElement?.let { resolveValues(it) } ?: emptySet()
            append(returnTypes.singleOrNull()?.toText() ?: "_")
        }
}

data class ZigTypeParamType(val element: ZigParamDecl) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = element.name ?: "_"
}

data class ZigOptionalType(val child: ZigValue) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "?${child.toText()}"
}

data class ZigErrorSetType(val names: Set<String>) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = buildString {
            append("error{")
            for ((i, name) in names.withIndex()) {
                if (i > 0) {
                    append(',')
                }
                append(name)
            }
            append('}')
        }
}

data class ZigErrorSetValue(override val typeOf: ZigErrorSetType, val name: String) : ZigValue {
    override fun toText() = when (typeOf.names.singleOrNull()) {
            name -> "error.${name}"
            else -> "@as(${typeOf.toText()}, error.${name})"
        }
}

data class ZigErrorUnionType(val errorSet: ZigValue, val payload: ZigValue) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "${errorSet.toText()}!${payload.toText()}"
}

data class ZigPointerType(
    val size: ZigPointerSize,
    val sentinel: ZigValue?,
    // TODO: include modifiers (const, volatile, allowzero, addrspace, align)
    val child: ZigValue,
) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = buildString {
            when (size) {
                ZigPointerSize.ONE -> append('*')
                ZigPointerSize.C -> append("[*c]")
                ZigPointerSize.MANY, ZigPointerSize.SLICE -> {
                    append('[')
                    when (size) {
                        ZigPointerSize.MANY -> append('*')
                        ZigPointerSize.SLICE -> {}
                    }
                    if (sentinel != null) {
                        append(':')
                        append(sentinel.toText())
                    }
                    append(']')
                }
            }
            append(child.toText())
        }
}

data class ZigArrayType(val length: ZigValue, val sentinel: ZigValue?, val child: ZigValue) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = buildString {
            append('[')
            append(this@ZigArrayType.length.toText())
            if (sentinel != null) {
                append(':')
                append(sentinel.toText())
            }
            append(']')
            append(child.toText())
        }
}

data class ZigTupleType(val fields: List<ZigValue>) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = buildString {
            append("struct { ")
            for ((i, field) in fields.withIndex()) {
                if (i > 0) {
                    append(", ")
                }
                append(field.toText())
            }
            append(" }")
        }
}

sealed interface ZigFloatType : ZigValue {
    override val typeOf: ZigTypeType
}

data object ZigComptimeFloatType : ZigFloatType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "comptime_float"
}

data class ZigFixedFloatType(val bits: Int) : ZigFloatType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "f${bits}"
}

data object ZigCLongDoubleType : ZigFloatType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "c_longdouble"
}

sealed interface ZigIntType : ZigValue {
    override val typeOf: ZigTypeType
}

data class ZigIntValue(override val typeOf: ZigIntType, val value: Int) : ZigValue {
    override fun toText() = when (typeOf) {
            ZigComptimeIntType -> "$value"
            else -> "@as(${typeOf.toText()}, ${value})"
        }
}

data object ZigComptimeIntType : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "comptime_int"
}

enum class ZigSignedness { SIGNED, UNSIGNED }

data class ZigFixedIntType(val signedness: ZigSignedness, val bits: Int) : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = when (signedness) {
            ZigSignedness.SIGNED -> "i${bits}"
            ZigSignedness.UNSIGNED -> "u${bits}"
        }
}

data object ZigIsizeType : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "isize"
}

data object ZigUsizeType : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "usize"
}

data object ZigCCharType : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "c_char"
}

data class ZigCShortType(val signedness: ZigSignedness) : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "c_short"
}

data class ZigCIntType(val signedness: ZigSignedness) : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "c_int"
}

data class ZigCLongType(val signedness: ZigSignedness) : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "c_long"
}

data class ZigCLongLongType(val signedness: ZigSignedness) : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toText() = "c_longlong"
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
            val tupleType = run {
                val fields = mutableListOf<ZigValue>()
                for (member in element.members) {
                    if (member !is ZigContainerField) {
                        return@run null
                    }
                    if (member.nameIdentifier != null) {
                        return@run null
                    }
                    val types = when (val typeElement = member.typeElement?.innerElement) {
                        null -> emptySequence()
                        else -> resolveValues(typeElement).asSequence()
                    }
                    // For efficiency, limit each field to a single possible type.
                    fields.add(types.singleOrNull() ?: ZigUnknown.of(ZigTypeType))
                }
                if (fields.isEmpty()) {
                    return@run null
                }
                ZigTupleType(fields)
            }
            if (tupleType != null) {
                return sequenceOf(tupleType)
            }
            val typeParams = findUnboundTypeParams(element)
            sequenceOf(ZigContainerType(element, typeParams))
        }

        is ZigFnProto -> {
            val typeParams = findUnboundTypeParams(element)
            sequenceOf(ZigFunctionType(element, typeParams))
        }

        is ZigOptionalTypeElement -> {
            val childElement = element.optionalChildElement
                ?: return sequenceOf(ZigOptionalType(ZigUnknown.of(ZigTypeType)))
            resolveValues(childElement).asSequence().map { ZigOptionalType(it) }
        }

        is ZigErrorSetDecl -> {
            val names = element.setItems.map { it.identifierElement.text }
            sequenceOf(ZigErrorSetType(names.toSortedSet()))
        }

        is ZigErrorValueElement -> {
            val name = element.identifierElement?.text ?: return sequenceOf(ZigUnknown.ofUnknownType())
            val type = ZigErrorSetType(setOf(name))
            sequenceOf(ZigErrorSetValue(type, name))
        }

        is ZigErrorUnion -> {
            val errorSet = resolveValues(element.errorSet).singleOrNull()
                ?: ZigUnknown.of(ZigTypeType)
            val payloadElement = element.payload
                ?: return sequenceOf(ZigErrorUnionType(errorSet, ZigUnknown.of(ZigTypeType)))
            resolveValues(payloadElement).asSequence().map { ZigErrorUnionType(errorSet, it) }
        }

        is ZigArrayTypeElement -> {
            val length = resolveValues(element.lengthElement.innerElement).singleOrNull() ?: ZigUnknown.ofUnknownType()
            val sentinel = element.sentinelElement?.let { outerElement ->
                outerElement.innerElement?.let { resolveValues(it) }?.singleOrNull() ?: ZigUnknown.ofUnknownType()
            }
            val childElement = element.arrayChildElement
                ?: return sequenceOf(ZigArrayType(length, sentinel, ZigUnknown.of(ZigTypeType)))
            resolveValues(childElement).asSequence().map { ZigArrayType(length, sentinel, it) }
        }

        is ZigPointerTypeElement -> {
            val size = element.pointerSize
            val sentinel = element.sentinelElement?.let { outerElement ->
                outerElement.innerElement?.let { resolveValues(it) }?.singleOrNull() ?: ZigUnknown.ofUnknownType()
            }
            val childElement = element.pointerChildElement
                ?: return sequenceOf(ZigPointerType(size, sentinel, ZigUnknown.of(ZigTypeType)))
            resolveValues(childElement).asSequence().map { ZigPointerType(size, sentinel, it) }
        }

        is ZigGroupedExpression -> {
            val innerElement = element.innerElement ?: return sequenceOf(ZigUnknown.ofUnknownType())
            resolveValues(innerElement).asSequence()
        }

        is ZigComptime -> {
            val innerElement = element.innerElement ?: return sequenceOf(ZigUnknown.ofUnknownType())
            resolveValues(innerElement).asSequence()
        }

        is ZigAddressOf -> {
            val operandElement = element.operand ?: return sequenceOf(ZigUnknown.ofUnknownType())
            resolveValues(operandElement).asSequence().map {
                ZigUnknown.of(ZigPointerType(ZigPointerSize.ONE, null, it.typeOf))
            }
        }

        is ZigUnwrapOptional -> {
            val operandElement = element.operand ?: return sequenceOf(ZigUnknown.ofUnknownType())
            resolveValues(operandElement).asSequence().map { operand ->
                when (val typeOfOperand = operand.typeOf) {
                    is ZigOptionalType -> ZigUnknown.of(typeOfOperand.child)
                    else -> ZigUnknown.ofUnknownType()
                }
            }
        }

        is ZigTry -> {
            val operandElement = element.operand ?: return sequenceOf(ZigUnknown.ofUnknownType())
            resolveValues(operandElement).asSequence().map { operand ->
                when (val typeOfOperand = operand.typeOf) {
                    is ZigErrorUnionType -> ZigUnknown.of(typeOfOperand.payload)
                    else -> ZigUnknown.ofUnknownType()
                }
            }
        }

        is ZigDeref -> {
            val operandElement = element.operand ?: return sequenceOf(ZigUnknown.ofUnknownType())
            resolveValues(operandElement).asSequence().map { operand ->
                when (val typeOfOperand = operand.typeOf) {
                    is ZigPointerType -> when (typeOfOperand.size) {
                        ZigPointerSize.ONE,
                        ZigPointerSize.C -> ZigUnknown.of(typeOfOperand.child)
                        ZigPointerSize.SLICE,
                        ZigPointerSize.MANY -> ZigUnknown.ofUnknownType()
                    }
                    else -> ZigUnknown.ofUnknownType()
                }
            }
        }

        is ZigIf -> {
            val thenValues = when (val thenElement = element.thenElement.innerElement) {
                null -> sequenceOf(ZigUnknown.ofUnknownType())
                else -> resolveValues(thenElement).asSequence()
            }
            val elseValues = when (val outerElement = element.elseElement) {
                null -> sequenceOf(ZigVoidValue)
                else -> when (val innerElement = outerElement.innerElement) {
                    null -> sequenceOf(ZigUnknown.ofUnknownType())
                    else -> resolveValues(innerElement).asSequence()
                }
            }
            // TODO: peer type resolution
            thenValues + elseValues
        }

        is ZigCatch -> {
            val leftValues = resolveValues(element.leftOperand).asSequence().map { left ->
                when (val typeOfLeft = left.typeOf) {
                    is ZigErrorUnionType -> ZigUnknown.of(typeOfLeft.payload)
                    else -> ZigUnknown.ofUnknownType()
                }
            }
            val rightValues = when (val rightElement = element.rightOperand) {
                null -> sequenceOf(ZigUnknown.ofUnknownType())
                else -> resolveValues(rightElement).asSequence()
            }
            // TODO: peer type resolution?
            leftValues + rightValues
        }

        is ZigOrelse -> {
            val leftValues = resolveValues(element.leftOperand).asSequence().map { left ->
                when (val typeOfLeft = left.typeOf) {
                    is ZigOptionalType -> ZigUnknown.of(typeOfLeft.child)
                    else -> ZigUnknown.ofUnknownType()
                }
            }
            val rightValues = when (val rightElement = element.rightOperand) {
                null -> sequenceOf(ZigUnknown.ofUnknownType())
                else -> resolveValues(rightElement).asSequence()
            }
            // TODO: peer type resolution?
            leftValues + rightValues
        }

        is ZigMergeErrorSets -> {
            val rightElement = element.rightOperand
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            val rightType = resolveValues(rightElement).singleOrNull() as? ZigErrorSetType
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            val leftElement = element.leftOperand
            val leftType = resolveValues(leftElement).singleOrNull() as? ZigErrorSetType
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            val names = leftType.names + rightType.names
            sequenceOf(ZigErrorSetType(names.toSortedSet()))
        }

        is ZigListInit -> {
            val typeElement = element.typeExpression
            if (typeElement != null) {
                return resolveValues(typeElement).asSequence().map { ZigUnknown.of(it) }
            }
            val fieldTypes = mutableListOf<ZigValue>()
            for (item in element.initItems) {
                if (item.isStructField) {
                    return sequenceOf(ZigUnknown.ofUnknownType())
                }
                val values = when (val expressionElement = item.expression) {
                    null -> emptySequence()
                    else -> resolveValues(expressionElement).asSequence()
                }
                // For efficiency, limit each field to a single possible type.
                val value = values.singleOrNull() ?: ZigUnknown.ofUnknownType()
                fieldTypes.add(value.typeOf)
            }
            sequenceOf(ZigUnknown.of(ZigTupleType(fieldTypes)))
        }

        is ZigIdentifier -> {
            val text = element.text
            val primitiveValue = PRIMITIVE_VALUES[text]
            if (primitiveValue != null) {
                return sequenceOf(primitiveValue)
            }
            val intType = toFixedIntType(text)
            if (intType != null) {
                return sequenceOf(intType)
            }
            resolveReferences(element).flatMap { target ->
                when (target) {
                    is ZigElement -> resolveValuesFromReferenceTo(target).asSequence()
                    else -> sequenceOf(ZigUnknown.ofUnknownType())
                }
            }
        }

        is ZigFieldAccess -> {
            val name = element.identifierElement?.text ?: return sequenceOf(ZigUnknown.ofUnknownType())
            resolveValues(element.leftOperand).asSequence().flatMap { left ->
                when (left) {
                    is ZigErrorSetType -> {
                        if (name in left.names) {
                            sequenceOf(ZigErrorSetValue(left, name))
                        } else {
                            sequenceOf(ZigUnknown.of(left))
                        }
                    }
                    is ZigContainerType -> computeContainerDeclValues(left, name)
                    else -> when (val typeOfLeft = left.typeOf) {
                        is ZigContainerType -> computeContainerInstanceFieldValues(typeOfLeft, name)
                        is ZigTupleType -> sequenceOf(computeFieldAccessOnTuple(typeOfLeft, name))
                        is ZigPointerType -> when (typeOfLeft.size) {
                            ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                                is ZigContainerType -> computeContainerInstanceFieldValues(pointerChild, name)
                                else -> sequenceOf(ZigUnknown.ofUnknownType())
                            }
                            else -> sequenceOf(ZigUnknown.ofUnknownType())
                        }
                        else -> sequenceOf(ZigUnknown.ofUnknownType())
                    }
                }
            }
        }

        is ZigArrayAccess -> {
            val indexElement = element.indexElement?.innerElement
            resolveValues(element.leftOperand).asSequence().map { left ->
                when (val typeOfLeft = left.typeOf) {
                    is ZigArrayType -> ZigUnknown.of(typeOfLeft.child)
                    is ZigPointerType -> when (typeOfLeft.size) {
                        ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                            is ZigArrayType -> ZigUnknown.of(pointerChild.child)
                            is ZigTupleType -> computeArrayAccessOnTuple(pointerChild, indexElement)
                            else -> ZigUnknown.ofUnknownType()
                        }
                        else -> ZigUnknown.of(typeOfLeft.child)
                    }
                    is ZigTupleType -> computeArrayAccessOnTuple(typeOfLeft, indexElement)
                    else -> ZigUnknown.ofUnknownType()
                }
            }
        }

        is ZigSlice -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO

        is ZigAdd -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigAddSat -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigAddWrap -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigArrayCat -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigBangEqual -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigBitAnd -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigBitOr -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigBitXor -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigBoolAnd -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigBoolOr -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigDiv -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigEqualEqual -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigGreaterOrEqual -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigGreaterThan -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigLessOrEqual -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigLessThan -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigMod -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigMul -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigMulSat -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigMulWrap -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigShl -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigShlSat -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigShr -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigSub -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigSubSat -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigSubWrap -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO

        is ZigBitNot -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigBoolNot -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigNegation -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigNegationWrap -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO

        is ZigBuiltinCall -> when (element.builtinElement.text) {
            "@as" -> {
                val argElement = element.argumentElements.firstOrNull()?.innerElement
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                resolveValues(argElement).asSequence().map { ZigUnknown.of(it) }
            }

            "@import" -> {
                val argElement = element.argumentElements.firstOrNull()?.innerElement
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                if (argElement !is ZigStringLiteral) {
                    return sequenceOf(ZigUnknown.ofUnknownType())
                }
                resolveReferences(argElement).map { target ->
                    when (target) {
                        is ZigFile -> ZigContainerType(target, emptyMap())
                        else -> ZigUnknown.ofUnknownType()
                    }
                }
            }

            "@This" -> {
                val containerElement = element.findParentOfType<ZigContainer>()
                    ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
                val typeParams = findUnboundTypeParams(containerElement)
                sequenceOf(ZigContainerType(containerElement, typeParams))
            }

            "@EnumLiteral" -> sequenceOf(ZigEnumLiteralType)

            // TODO
            else -> sequenceOf(ZigUnknown.ofUnknownType())
        }

        is ZigCall -> {
            computeCalledFuncAndArgs(element).flatMap { (function, _, arguments) ->
                when (val typeOfFunction = function.typeOf) {
                    is ZigFunctionType -> computeFunctionReturnValues(typeOfFunction, arguments)
                    else -> sequenceOf(ZigUnknown.ofUnknownType())
                }
            }
        }

        is ZigBlock -> {
            val values = computeValuesFromLabeledBreaks(element)
            values.ifEmpty { sequenceOf(ZigVoidValue) }
        }

        is ZigSwitch -> {
            var values = computeValuesFromLabeledBreaks(element)
            values += element.prongs.flatMap { prongElement ->
                prongElement.expression?.let { resolveValues(it).asSequence() }
                    ?: sequenceOf(ZigUnknown.ofUnknownType())
            }
            values.ifEmpty { sequenceOf(ZigUnknown.ofUnknownType()) }
        }

        is ZigFor -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
        is ZigWhile -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO

        is ZigStringLiteral, is ZigMultilineStringLiteral -> {
            val length = ZigUnknown.ofUnknownType() // TODO
            val u8 = ZigFixedIntType(ZigSignedness.UNSIGNED, 8)
            val array = ZigArrayType(length, ZigIntValue(ZigComptimeIntType, 0), u8)
            val pointer = ZigPointerType(ZigPointerSize.ONE, null, array)
            sequenceOf(ZigUnknown.of(pointer))
        }

        is ZigNumberLiteral -> {
            val value = element.text.toIntOrNull() ?: return sequenceOf(ZigUnknown.ofUnknownType())
            sequenceOf(ZigIntValue(ZigComptimeIntType, value))
        }

        is ZigCharLiteral -> sequenceOf(ZigUnknown.of(ZigComptimeIntType))

        is ZigEnumLiteral -> sequenceOf(ZigUnknown.of(ZigEnumLiteralType))

        is ZigBreak,
        is ZigContinue,
        is ZigReturn,
        is ZigUnreachableLiteral -> sequenceOf(ZigUnknown.of(ZigNoreturnType))

        else -> sequenceOf(ZigUnknown.ofUnknownType())
    }
}

private fun toFixedIntType(text: String): ZigFixedIntType? {
    if (text.length < 2) {
        return null
    }
    val signedness = when (text[0]) {
        'i' -> ZigSignedness.SIGNED
        'u' -> ZigSignedness.UNSIGNED
        else -> return null
    }
    val bits = text.substring(1).toIntOrNull() ?: return null
    return ZigFixedIntType(signedness, bits)
}

private fun computeFieldAccessOnTuple(type: ZigTupleType, name: String): ZigValue {
    val isEscaped = name.startsWith("@\"") && name.endsWith("\"")
    if (!isEscaped) {
        return ZigUnknown.ofUnknownType()
    }
    val index = name.substring(2, name.length - 1).toIntOrNull() ?: return ZigUnknown.ofUnknownType()
    val fieldType = type.fields.getOrNull(index) ?: return ZigUnknown.ofUnknownType()
    return ZigUnknown.of(fieldType)
}

private fun computeArrayAccessOnTuple(type: ZigTupleType, indexElement: ZigElement?): ZigValue {
    if (indexElement == null) {
        return ZigUnknown.ofUnknownType()
    }
    // TODO: allow multiple possible index values and return a Sequence?
    val index = resolveValues(indexElement).singleOrNull() ?: return ZigUnknown.ofUnknownType()
    if (index !is ZigIntValue) {
        return ZigUnknown.ofUnknownType()
    }
    val fieldType = type.fields.getOrNull(index.value) ?: return ZigUnknown.ofUnknownType()
    return ZigUnknown.of(fieldType)
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

private fun computeTypeParams(
    function: ZigFunctionType,
    arguments: Sequence<Set<ZigValue>>,
): Map<ZigParamDecl, Set<ZigValue>> {
    if (function.typeParams.isEmpty()) {
        return emptyMap()
    }
    return function.typeParams.toMutableMap().apply {
        for ((paramDecl, argValues) in function.element.parameters.zip(arguments)) {
            val paramTypeElement = paramDecl.typeElement?.innerElement ?: continue
            val paramTypes = resolveValues(paramTypeElement)
            if (paramTypes.contains(ZigTypeType)) {
                this[paramDecl] = argValues
            }
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
            ZigAnyerrorType,
            ZigAnyopaqueType,
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
            ZigVoidValue,
            ZigEnumLiteralType,
            is ZigErrorSetType,
            is ZigErrorSetValue,
            is ZigFloatType,
            is ZigIntType,
            is ZigIntValue -> sequenceOf(value)
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
                null -> sequenceOf(value)
                else -> values.asSequence().flatMap { substituteTypeParams(it, typeParams) }
            }
            is ZigOptionalType -> {
                substituteTypeParams(value.child, typeParams).map { ZigOptionalType(it) }
            }
            is ZigErrorUnionType -> {
                substituteTypeParams(value.payload, typeParams).map { ZigErrorUnionType(value.errorSet, it) }
            }
            is ZigArrayType -> {
                substituteTypeParams(value.child, typeParams).map { ZigArrayType(value.length, value.sentinel, it) }
            }
            is ZigPointerType -> {
                substituteTypeParams(value.child, typeParams).map { ZigPointerType(value.size, value.sentinel, it) }
            }
            is ZigTupleType -> {
                val fields = value.fields.map { field ->
                    val substitutions = substituteTypeParams(field, typeParams)
                    // For efficiency, limit each field to a single possible type.
                    substitutions.singleOrNull() ?: ZigUnknown.of(ZigTypeType)
                }
                sequenceOf(ZigTupleType(fields))
            }
        }
    } ?: sequenceOf(value)
}

private fun computeContainerInstanceFieldValues(type: ZigContainerType, name: String): Sequence<ZigValue> {
    if (type.element.containerKind == ZigContainerKind.ENUM) {
        return sequenceOf(ZigUnknown.ofUnknownType())
    }
    val member = type.element.members.firstOrNull {
        it is PsiNameIdentifierOwner && it.name == name
    }
    if (member == null || member !is ZigContainerField) {
        return sequenceOf(ZigUnknown.ofUnknownType())
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
        return sequenceOf(ZigUnknown.ofUnknownType())
    }
    if (member is ZigContainerField) {
        return when (type.element.containerKind) {
            ZigContainerKind.ENUM -> sequenceOf(ZigUnknown.of(type))
            ZigContainerKind.UNION -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO: return instance of union tag if applicable
            else -> sequenceOf(ZigUnknown.ofUnknownType())
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
            val initElement = target.initializer ?: return sequenceOf(ZigUnknown.ofUnknownType())
            when (target.mutability) {
                ZigMutability.CONST -> resolveValues(initElement).asSequence()
                ZigMutability.VAR -> resolveValues(initElement).asSequence().map { ZigUnknown.of(it.typeOf) }
            }
        }

        is ZigVarProto -> {
            val typeElement = target.typeElement?.innerElement
            if (typeElement != null) {
                return resolveValues(typeElement).asSequence().map { ZigUnknown.of(it) }
            }
            val destructure = target.containingAssignDestructure
            val initElement = destructure.initializer ?: return sequenceOf(ZigUnknown.ofUnknownType())
            val destructureIndex = destructure.variables.indexOf(target)
            assert(destructureIndex >= 0)
            resolveValues(initElement).asSequence().map { init ->
                when (val typeOfInit = init.typeOf) {
                    is ZigTupleType -> {
                        when (val fieldType = typeOfInit.fields.getOrNull(destructureIndex)) {
                            null -> ZigUnknown.ofUnknownType()
                            else -> ZigUnknown.of(fieldType)
                        }
                    }
                    else -> ZigUnknown.ofUnknownType()
                }
            }
        }

        is ZigParamDecl -> {
            val typeElement = target.typeElement?.innerElement ?: return sequenceOf(ZigUnknown.ofUnknownType())
            val types = resolveValues(typeElement)
            if (types.contains(ZigTypeType)) {
                return sequenceOf(ZigTypeParamType(target))
            }
            types.asSequence().map { ZigUnknown.of(it) }
        }

        is ZigContainerField -> {
            when (val typeElement = target.typeElement?.innerElement) {
                null -> when (target.container.containerKind) {
                    ZigContainerKind.STRUCT,
                    ZigContainerKind.OPAQUE -> sequenceOf(ZigUnknown.ofUnknownType())
                    ZigContainerKind.ENUM -> {
                        val typeParams = findUnboundTypeParams(target.container)
                        sequenceOf(ZigUnknown.of(ZigContainerType(target.container, typeParams)))
                    }
                    ZigContainerKind.UNION -> when ((target.container as ZigContainerDecl).containerTagElement) {
                        null -> sequenceOf(ZigUnknown.ofUnknownType())
                        else -> sequenceOf(ZigVoidValue)
                    }
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
                            ?: return sequenceOf(ZigUnknown.ofUnknownType())
                    resolveValues(conditionElement).asSequence().map { condition ->
                        when (val typeOfCondition = condition.typeOf) {
                            is ZigOptionalType -> ZigUnknown.of(typeOfCondition.child)
                            is ZigErrorUnionType -> ZigUnknown.of(typeOfCondition.payload)
                            else -> ZigUnknown.ofUnknownType()
                        }
                    }
                }

                is ZigElsePayload -> {
                    val conditionElement =
                        payloadElement.containingIf?.conditionElement?.innerElement
                            ?: payloadElement.containingWhile?.conditionElement?.innerElement
                            ?: return sequenceOf(ZigUnknown.ofUnknownType())
                    resolveValues(conditionElement).asSequence().map { condition ->
                        when (val typeOfCondition = condition.typeOf) {
                            is ZigErrorUnionType -> ZigUnknown.of(typeOfCondition.errorSet)
                            else -> ZigUnknown.ofUnknownType()
                        }
                    }
                }

                is ZigCatchPayload -> {
                    val leftElement = payloadElement.containingCatch.leftOperand
                    resolveValues(leftElement).asSequence().map { condition ->
                        when (val typeOfCondition = condition.typeOf) {
                            is ZigErrorUnionType -> ZigUnknown.of(typeOfCondition.errorSet)
                            else -> ZigUnknown.ofUnknownType()
                        }
                    }
                }

                is ZigSwitchPayload -> when (payloadElement.items.indexOf(target)) {
                    0 -> {
                        val switchProngElement = payloadElement.containingSwitchProng
                        val switchItemElements = switchProngElement.items.map { it.innerElement }.toList()
                        if (switchItemElements.isNotEmpty()) {
                            val switchItemValues = switchItemElements.map { resolveValues(it) }
                            if (switchItemValues.all { it.singleOrNull() is ZigErrorSetValue }) {
                                val names = switchItemValues.map { (it.single() as ZigErrorSetValue).name }
                                return sequenceOf(ZigUnknown.of(ZigErrorSetType(names.toSortedSet())))
                            }
                        }
                        val switchElement = switchProngElement.containingSwitch
                        val conditionElement = switchElement.conditionElement.innerElement
                            ?: return sequenceOf(ZigUnknown.ofUnknownType())
                        val conditionValues = resolveValues(conditionElement)
                        if (conditionValues.none { isTaggedUnionType(it.typeOf) }) {
                            return conditionValues.asSequence().map { ZigUnknown.of(it.typeOf) }
                        }
                        // TODO: peer type resolution
                        switchItemElements.asSequence().flatMap { itemElement ->
                            resolveReferences(itemElement)
                                .filterIsInstance<ZigElement>()
                                .flatMap { resolveValuesFromReferenceTo(it).asSequence() }
                        }.ifEmpty { sequenceOf(ZigUnknown.ofUnknownType()) }
                    }
                    1 -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
                    else -> sequenceOf(ZigUnknown.ofUnknownType())
                }

                is ZigForPayload -> {
                    val payloadIndex = payloadElement.items.indexOf(target)
                    val forInputs = payloadElement.containingFor.inputs
                    val inputElement = forInputs.elementAtOrNull(payloadIndex)?.innerElement
                        ?: return sequenceOf(ZigUnknown.ofUnknownType())
                    if (inputElement is ZigForRange) {
                        return sequenceOf(ZigUnknown.of(ZigUsizeType))
                    }
                    resolveValues(inputElement).asSequence().map { input ->
                        when (val typeOfInput = input.typeOf) {
                            is ZigArrayType -> ZigUnknown.of(typeOfInput.child)
                            is ZigPointerType -> when (typeOfInput.size) {
                                ZigPointerSize.SLICE -> ZigUnknown.of(typeOfInput.child)
                                ZigPointerSize.ONE -> when (typeOfInput.child) {
                                    is ZigArrayType -> ZigUnknown.of(typeOfInput.child)
                                    else -> ZigUnknown.ofUnknownType()
                                }
                                else -> ZigUnknown.ofUnknownType()
                            }
                            else -> ZigUnknown.ofUnknownType()
                        }
                    }
                }
            }
        }

        else -> sequenceOf(ZigUnknown.ofUnknownType())
    }
}

private fun isTaggedUnionType(type: ZigValue): Boolean {
    if (type !is ZigContainerType) {
        return false
    }
    return type.element.isTaggedUnion
}

private fun computeFunctionReturnValues(
    type: ZigFunctionType,
    arguments: Sequence<Set<ZigValue>>,
): Sequence<ZigValue> {
    val returnTypeElement = type.element.returnTypeElement?.innerElement
        ?: return sequenceOf(ZigUnknown.ofUnknownType())
    val returnTypes = resolveValues(returnTypeElement)
    if (returnTypes.contains(ZigTypeType)) {
        val functionBodyElement = (type.element as? ZigFnDecl)?.body
        if (functionBodyElement != null) {
            val typeParams = computeTypeParams(type, arguments)
            val traverser = SyntaxTraverser.psiTraverser(functionBodyElement).expand { it !is ZigContainerDecl }
            return traverser.asSequence().flatMap { descendant ->
                if (descendant is ZigReturn) {
                    when (val returnValue = descendant.returnValue) {
                        null -> sequenceOf(ZigVoidValue)
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
            }.ifEmpty { sequenceOf(ZigUnknown.of(ZigTypeType)) }
        }
    }
    var values = returnTypes.asSequence()
    val typeParams = computeTypeParams(type, arguments)
    if (typeParams.isNotEmpty()) {
        values = values.flatMap { substituteTypeParams(it, typeParams) }
    }
    return values.map { ZigUnknown.of(it) }
}

private fun computeCalledFuncAndArgs(callElement: ZigCall): Sequence<Triple<ZigValue, Boolean, Sequence<Set<ZigValue>>>> {
    val initialArgs = callElement.arguments.map { resolveValues(it) }.toList()
    return computeCalledFuncAndSelfArg(callElement).map { (function, selfArg) ->
        if (selfArg != null) {
            val actualArgs = sequenceOf(setOf(selfArg)) + initialArgs.asSequence()
            Triple(function, true, actualArgs)
        } else {
            Triple(function, false, initialArgs.asSequence())
        }
    }
}

private fun computeCalledFuncAndSelfArg(callElement: ZigCall): Sequence<Pair<ZigValue, ZigValue?>> {
    return when (val functionElement = callElement.functionElement) {
        is ZigFieldAccess -> {
            val name = functionElement.identifierElement?.text
                ?: return sequenceOf(ZigUnknown.ofUnknownType() to null)
            resolveValues(functionElement.leftOperand).asSequence().flatMap { left ->
                when (left) {
                    is ZigContainerType -> {
                        computeContainerDeclValues(left, name).map { it to null }
                    }
                    else -> when (val typeOfLeft = left.typeOf) {
                        is ZigContainerType -> {
                            computeContainerDeclValues(typeOfLeft, name).map { it to left }
                        }
                        is ZigPointerType -> when (typeOfLeft.size) {
                            ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                                is ZigContainerType -> {
                                    computeContainerDeclValues(pointerChild, name).map { it to left }
                                }
                                else -> sequenceOf(ZigUnknown.ofUnknownType() to null)
                            }
                            else -> sequenceOf(ZigUnknown.ofUnknownType() to null)
                        }
                        else -> sequenceOf(ZigUnknown.ofUnknownType() to null)
                    }
                }
            }
        }

        is ZigEnumLiteral -> sequenceOf(ZigUnknown.ofUnknownType() to null) // TODO

        else -> resolveValues(functionElement).asSequence().map { it to null }
    }
}

private fun computeValuesFromLabeledBreaks(element: ZigLabelable): Sequence<ZigValue> {
    val labelDecl = element.labelDecl ?: return emptySequence()
    val labelName = labelDecl.identifierElement.text
    val traverser = SyntaxTraverser.psiTraverser(element).expand { it !is ZigContainerDecl }
    // TODO: peer type resolution
    return traverser.asSequence().flatMap { descendant ->
        if (descendant is ZigBreak && labelName == descendant.labelRef?.identifierElement?.text) {
            when (val breakValue = descendant.breakValue) {
                null -> sequenceOf(ZigVoidValue)
                else -> resolveValues(breakValue).asSequence()
            }
        } else {
            emptySequence()
        }
    }
}

private fun inferResultTypes(childElement: ZigElement): Sequence<ZigValue> {
    return when (val parentElement = childElement.parent) {
        is ZigVarDecl -> {
            if (parentElement.initializer !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val typeElement = parentElement.typeElement?.innerElement
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            resolveValues(typeElement).asSequence()
        }

        is ZigContainerField -> {
            if (parentElement.initializer !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val typeElement = parentElement.typeElement?.innerElement
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            resolveValues(typeElement).asSequence()
        }

        is ZigAssign -> {
            if (parentElement.rightElement !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            resolveValues(parentElement.leftElement).asSequence().map { it.typeOf }
        }

        is ZigAssignDestructure -> sequenceOf(ZigUnknown.of(ZigTypeType)) // TODO

        is ZigListInitItem -> {
            if (parentElement.expression !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            if (parentElement.isStructField) {
                resolveReferences(parentElement)
                    .filterIsInstance<ZigElement>()
                    .flatMap { resolveValuesFromReferenceTo(it).asSequence() }
                    .map { it.typeOf }
                    .ifEmpty { sequenceOf(ZigUnknown.of(ZigTypeType)) }
            } else {
                val initElement = parentElement.containingListInit
                val initTypes = when (val typeElement = initElement.typeExpression) {
                    null -> inferResultTypes(initElement)
                    else -> resolveValues(typeElement).asSequence()
                }
                initTypes.map { type ->
                    when (type) {
                        is ZigArrayType -> type.child
                        is ZigTupleType -> {
                            val initIndex = initElement.initItems.indexOf(parentElement)
                            assert(initIndex >= 0)
                            type.fields.getOrNull(initIndex) ?: ZigUnknown.of(ZigTypeType)
                        }
                        else -> ZigUnknown.of(ZigTypeType)
                    }
                }
            }
        }

        is ZigEqualEqual, is ZigBangEqual -> {
            val otherElement = when (childElement) {
                parentElement.leftOperand -> parentElement.rightOperand
                parentElement.rightOperand -> parentElement.leftOperand
                else -> null
            }
            if (otherElement == null) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            resolveValues(otherElement).asSequence().map { it.typeOf }
        }

        is ZigSwitchItem -> {
            val switchElement = parentElement.containingSwitchProng.containingSwitch
            val conditionElement = switchElement.conditionElement.innerElement
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            resolveValues(conditionElement).asSequence().map { it.typeOf }
        }

        is ZigSwitchProng -> {
            if (parentElement.expression !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            inferResultTypes(parentElement.containingSwitch)
        }

        is ZigThenElement -> {
            if (parentElement.innerElement !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            inferResultTypes(parentElement.parent as ZigElement)
        }

        is ZigElseElement -> {
            if (parentElement.innerElement !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            inferResultTypes(parentElement.parent as ZigElement)
        }

        is ZigOrelse -> sequenceOf(ZigUnknown.of(ZigTypeType)) // TODO

        is ZigCatch -> sequenceOf(ZigUnknown.of(ZigTypeType)) // TODO

        is ZigAddressOf -> sequenceOf(ZigUnknown.of(ZigTypeType)) // TODO

        is ZigReturn -> {
            val typeElement =
                childElement.findParentOfType<ZigFnDecl>()
                    ?.let { it.returnTypeElement?.innerElement }
                    ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            resolveValues(typeElement).asSequence()
        }

        is ZigBreak -> sequenceOf(ZigUnknown.of(ZigTypeType)) // TODO

        is ZigCall -> {
            if (parentElement.functionElement !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            inferResultTypes(parentElement)
        }

        is ZigCallArg -> {
            val callElement = parentElement.containingCall
            if (callElement != null) {
                val argIndex = callElement.argumentElements.indexOf(parentElement)
                assert(argIndex >= 0)
                return computeCalledFuncAndArgs(callElement).flatMap { (function, isMethod, arguments) ->
                    val functionType = function.typeOf
                    if (functionType !is ZigFunctionType) {
                        return@flatMap sequenceOf(ZigUnknown.of(ZigTypeType))
                    }
                    val paramIndex = if (isMethod) argIndex + 1 else argIndex
                    val paramElement = functionType.element.parameters.elementAtOrNull(paramIndex)
                        ?: return@flatMap sequenceOf(ZigUnknown.of(ZigTypeType))
                    val typeElement = paramElement.typeElement?.innerElement
                        ?: return@flatMap sequenceOf(ZigUnknown.of(ZigTypeType))
                    var values = resolveValues(typeElement).asSequence()
                    val typeParams = computeTypeParams(functionType, arguments)
                    if (typeParams.isNotEmpty()) {
                        values = values.flatMap { substituteTypeParams(it, typeParams) }
                    }
                    values
                }
            }
            val builtinCallElement = parentElement.containingBuiltinCall
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            when (builtinCallElement.builtinElement.text) {
                // TODO
                else -> sequenceOf(ZigUnknown.of(ZigTypeType))
            }
        }

        // TODO
        else -> sequenceOf(ZigUnknown.of(ZigTypeType))
    }
}

private fun getFullNameStartingFrom(element: PsiElement, typeParams: Map<ZigParamDecl, Set<ZigValue>>): String {
    if (element is ZigContainer) {
        return when (element) {
            is ZigFile -> {
                val name = element.name
                assert(name.endsWith(".zig"))
                name.substringBeforeLast('.')
            }

            is ZigContainerDecl -> when (val parent = element.parent!!) {
                is ZigVarDecl if parent.initializer == element -> {
                    val grandparent = parent.parent!!
                    val grandparentName = getFullNameStartingFrom(grandparent, typeParams)
                    "${grandparentName}.${parent.name ?: "_"}"
                }

                is ZigReturn if parent.returnValue == element -> {
                    val fnDecl = parent.findParentOfType<ZigFnDecl>() ?: return "_"
                    val fnName = getFullNameStartingFrom(fnDecl, typeParams)
                    buildString {
                        append(fnName)
                        append('(')
                        for ((i, paramDecl) in fnDecl.parameters.withIndex()) {
                            if (i > 0) {
                                append(", ")
                            }
                            val typeParam = ZigTypeParamType(paramDecl)
                            val typeArg = ZigAnalyzer.substituteTypeParams(typeParam, typeParams).singleOrNull()
                            if (typeArg != null && typeArg != typeParam) {
                                append(typeArg.toText())
                            } else {
                                append('_')
                            }
                        }
                        append(')')
                    }
                }

                else -> {
                    val parentName = getFullNameStartingFrom(parent, typeParams)
                    when (element.containerKind) {
                        ZigContainerKind.STRUCT -> "${parentName}__struct"
                        ZigContainerKind.OPAQUE -> "${parentName}__opaque"
                        ZigContainerKind.ENUM -> "${parentName}__enum"
                        ZigContainerKind.UNION -> "${parentName}__union"
                    }
                }
            }
        }
    }
    val parent = element.parent!!
    val parentName = getFullNameStartingFrom(parent, typeParams)
    return when (element) {
        is ZigTestDecl -> "${parentName}.test" // TODO: `test` -> `test_{0,1,etc.}`, `test "foo"` -> `test.foo`, `test foo` -> `decltest.foo`
        is ZigComptime if parent is ZigContainer -> "${parentName}.comptime"
        is ZigVarDecl if parent is ZigContainer -> "${parentName}.${element.name ?: "_"}"
        is ZigFnDecl -> "${parentName}.${element.name ?: "_"}"
        else -> parentName
    }
}

private val PRIMITIVE_VALUES = mapOf(
    "anyerror" to ZigAnyerrorType,
    //"anyframe",
    "anyopaque" to ZigAnyopaqueType,
    "bool" to ZigBoolType,
    "c_int" to ZigCIntType(ZigSignedness.SIGNED),
    "c_long" to ZigCLongType(ZigSignedness.SIGNED),
    "c_longdouble" to ZigCLongDoubleType,
    "c_longlong" to ZigCLongLongType(ZigSignedness.SIGNED),
    "c_char" to ZigCCharType,
    "c_short" to ZigCShortType(ZigSignedness.SIGNED),
    "c_uint" to ZigCIntType(ZigSignedness.UNSIGNED),
    "c_ulong" to ZigCLongType(ZigSignedness.UNSIGNED),
    "c_ulonglong" to ZigCLongLongType(ZigSignedness.UNSIGNED),
    "c_ushort" to ZigCShortType(ZigSignedness.UNSIGNED),
    "comptime_float" to ZigComptimeFloatType,
    "comptime_int" to ZigComptimeIntType,
    "f128" to ZigFixedFloatType(128),
    "f16" to ZigFixedFloatType(16),
    "f32" to ZigFixedFloatType(32),
    "f64" to ZigFixedFloatType(64),
    "f80" to ZigFixedFloatType(80),
    "false" to ZigFalseValue,
    "isize" to ZigIsizeType,
    "noreturn" to ZigNoreturnType,
    "null" to ZigNullValue,
    "true" to ZigTrueValue,
    "type" to ZigTypeType,
    "undefined" to ZigUndefinedValue,
    "usize" to ZigUsizeType,
    "void" to ZigVoidType,
)
