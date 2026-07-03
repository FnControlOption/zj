package io.github.fncontroloption.zig

import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.fncontroloption.zig.psi2.ZigAddressOf
import io.github.fncontroloption.zig.psi2.ZigBuiltinCall
import io.github.fncontroloption.zig.psi2.ZigContainer
import io.github.fncontroloption.zig.psi2.ZigContainerDecl
import io.github.fncontroloption.zig.psi2.ZigContainerField
import io.github.fncontroloption.zig.psi2.ZigContainerKind
import io.github.fncontroloption.zig.psi2.ZigElement
import io.github.fncontroloption.zig.psi2.ZigFieldAccess
import io.github.fncontroloption.zig.psi2.ZigFile
import io.github.fncontroloption.zig.psi2.ZigFnDecl
import io.github.fncontroloption.zig.psi2.ZigIdentifier
import io.github.fncontroloption.zig.psi2.ZigMutability
import io.github.fncontroloption.zig.psi2.ZigOptionalTypeElement
import io.github.fncontroloption.zig.psi2.ZigPointerSize
import io.github.fncontroloption.zig.psi2.ZigPointerTypeElement
import io.github.fncontroloption.zig.psi2.ZigStringLiteral
import io.github.fncontroloption.zig.psi2.ZigVarDecl

object ZigAnalyzer {
    fun resolveValues(element: ZigElement): Set<ZigValue> {
        return io.github.fncontroloption.zig.resolveValues(element)
    }
}

sealed interface ZigValue

class ZigOpaqueValue(val type: ZigType) : ZigValue

sealed interface ZigType : ZigValue

class ZigUnknownType : ZigType

data class ZigContainerType(val element: ZigContainer) : ZigType

data class ZigOptionalType(val child: ZigType) : ZigType

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

        is ZigOptionalTypeElement -> {
            val childElement = element.optionalChildElement ?: return emptyList()
            resolveValues(childElement).filterIsInstance<ZigType>().map { ZigOptionalType(it) }
        }

        is ZigPointerTypeElement -> {
            val childElement = element.pointerChildElement ?: return emptyList()
            val size = element.pointerSize
            resolveValues(childElement).filterIsInstance<ZigType>().map { ZigPointerType(size, it) }
        }

        is ZigFieldAccess -> {
            val name = element.identifierElement?.text ?: return emptyList()
            resolveValues(element.leftOperand).flatMap { left ->
                when (left) {
                    is ZigContainerType -> computeContainerDeclValues(left, name)
                    is ZigType -> emptyList()
                    else -> emptyList() // TODO
                }
            }
        }

        is ZigIdentifier -> {
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
            else -> emptyList() // TODO
        }

        is ZigAddressOf -> {
            val operandElement = element.operand ?: return emptyList()
            resolveValues(operandElement)
                .filterIsInstance<ZigOpaqueValue>()
                .map { ZigOpaqueValue(ZigPointerType(ZigPointerSize.ONE, it.type)) }
        }

        else -> emptyList()
    }
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
    return computeValuesFromReferenceTo(member)
}

private fun computeValuesFromReferenceTo(target: ZigElement): Collection<ZigValue> {
    return when (target) {
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

        is ZigFnDecl -> emptyList() // TODO
        else -> emptyList() // TODO
    }
}
