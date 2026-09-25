package io.github.fncontroloption.zig

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.findFile
import com.intellij.psi.*
import com.intellij.psi.util.*
import io.github.fncontroloption.zig.psi2.*
import io.github.fncontroloption.zig.syntaxHighlighter.ZigHighlightingColors
import org.jsoup.Jsoup

object ZigAnalyzer {
    fun resolveValues(element: ZigElement): Set<ZigValue> {
        return io.github.fncontroloption.zig.resolveValues(element)
    }

    fun resolveValuesFromReferenceTo(target: ZigReferenceable): Set<ZigValue> {
        return io.github.fncontroloption.zig.resolveValuesFromReferenceTo(target)
    }

    fun substituteTypeParams(
        value: ZigValue,
        typeParams: Map<ZigFnParam, Set<ZigValue>>,
    ): Sequence<ZigValue> {
        return io.github.fncontroloption.zig.substituteTypeParams(value, typeParams)
    }

    fun substituteTypeParams(
        values: Sequence<ZigValue>,
        typeParams: Map<ZigFnParam, Set<ZigValue>>,
    ): Sequence<ZigValue> {
        return values.flatMap { substituteTypeParams(it, typeParams) }
    }

    fun substituteTypeParams(
        values: Iterable<ZigValue>,
        typeParams: Map<ZigFnParam, Set<ZigValue>>,
    ): Sequence<ZigValue> {
        return substituteTypeParams(values.asSequence(), typeParams)
    }

    fun inferResultTypes(childElement: ZigElement): Sequence<ZigValue> {
        return io.github.fncontroloption.zig.inferResultTypes(childElement)
    }

    fun resolveOrInferStructInitTypes(element: ZigListInit): Sequence<ZigContainerType> {
        return resolveOrInferListInitTypes(element).filterIsInstance<ZigContainerType>()
    }

    fun resolveOrInferListInitTypes(element: ZigListInit): Sequence<ZigValue> {
        val typeExpression = element.typeExpression
        if (typeExpression != null) {
            return resolveValues(typeExpression).asSequence()
        }
        return inferResultTypes(element).map { originalType ->
            var type = originalType
            while (true) {
                type = when (type) {
                    is ZigErrorUnionType -> type.payload
                    is ZigOptionalType -> type.child
                    else -> break
                }
            }
            type
        }
    }

    fun inferEnumLiteralTypes(element: ZigEnumLiteral): Sequence<Pair<ZigValue, IZigContainerType>> {
        return inferResultTypes(element).mapNotNull { originalType ->
            var type = originalType
            while (true) {
                type = when (type) {
                    is ZigErrorUnionType -> type.payload
                    is ZigOptionalType -> type.child
                    is ZigPointerType if type.size == ZigPointerSize.ONE -> type.child
                    else -> break
                }
            }
            if (type is IZigContainerType) {
                originalType to type
            } else {
                null
            }
        }
    }

    fun getBuiltinFields(type: ZigValue): Array<String> {
        var type = type
        if (type is ZigPointerType && type.size == ZigPointerSize.ONE) {
            type = type.child
        }
        return when (type) {
            is ZigPointerType -> when (type.size) {
                ZigPointerSize.ONE,
                ZigPointerSize.MANY,
                ZigPointerSize.C -> emptyArray()

                ZigPointerSize.SLICE -> arrayOf("len", "ptr")
            }

            is ZigArrayType -> arrayOf("len")

            is ZigTupleType -> arrayOf("len")

            else -> emptyArray()
        }
    }

    fun getBuiltinFieldType(type: ZigValue, name: String): ZigValue? {
        var type = type
        if (type is ZigPointerType && type.size == ZigPointerSize.ONE) {
            type = type.child
        }
        return when (type) {
            is ZigPointerType -> when (type.size) {
                ZigPointerSize.ONE,
                ZigPointerSize.MANY,
                ZigPointerSize.C -> null

                ZigPointerSize.SLICE -> when (name) {
                    "len" -> ZigUsizeType
                    "ptr" -> ZigPointerType(ZigPointerSize.MANY, type.sentinel, type.child)
                    else -> null
                }
            }

            is ZigArrayType -> when (name) {
                "len" -> ZigUsizeType
                else -> null
            }

            is ZigTupleType -> when (name) {
                "len" -> ZigUsizeType
                else -> null
            }

            else -> null
        }
    }

    fun resolveEnumTagTypes(type: IZigContainerType): Sequence<ZigValue> {
        if (type.containerKind != ZigContainerKind.ENUM) {
            return emptySequence()
        }
        return when (type) {
            is ZigContainerType -> when (val tagElement = type.element.containerTagElement?.innerElement) {
                null -> emptySequence()
                is ZigContainerTagEnum -> emptySequence()
                else -> resolveValues(tagElement).asSequence()
            }
            is ZigInferredUnionTagType -> when (val tagElement = type.union.element.containerTagElement?.innerElement) {
                null -> emptySequence()
                is ZigContainerTagEnum -> when (val enumTagElement = tagElement.innerElement) {
                    null -> emptySequence()
                    else -> resolveValues(enumTagElement).asSequence()
                }
                else -> emptySequence()
            }
            is ZigFieldEnumType -> emptySequence()
            is ZigTupleType -> emptySequence()
        }
    }

    fun resolveUnionTagTypes(type: ZigContainerType): Sequence<ZigValue> {
        if (type.element.containerKind != ZigContainerKind.UNION) {
            return emptySequence()
        }
        return when (val tagElement = type.element.containerTagElement?.innerElement) {
            null -> emptySequence()
            is ZigContainerTagEnum -> sequenceOf(ZigInferredUnionTagType(type))
            else -> resolveValues(tagElement).asSequence()
        }
    }

    fun resolveComptimeParameterTypes(param: ZigFnParam): Set<ZigValue> {
        val typeElement = param.typeElement?.innerElement ?: return emptySet()
        val types = resolveValues(typeElement)
        if (param.comptimeTokenElement != null) {
            return types
        }
        if (types.singleOrNull() == ZigTypeType) {
            return types
        }
        return emptySet()
    }

    fun findStdFile(originalElement: PsiElement, path: String): ZigFile? {
        val project = originalElement.project
        val originalFile = originalElement.containingFile?.originalFile?.virtualFile
        val enumerator = run {
            if (originalFile != null) {
                val module = ModuleUtilCore.findModuleForFile(originalFile, project)
                if (module != null) {
                    return@run OrderEnumerator.orderEntries(module)
                }
            }
            OrderEnumerator.orderEntries(project)
        }
        val psiManager = PsiManager.getInstance(project)
        for (root in enumerator.sdkOnly().sourceRoots) {
            val virtualFile = root.findFile(path)
            if (virtualFile != null) {
                val psiFile = psiManager.findFile(virtualFile)
                if (psiFile is ZigFile) {
                    return psiFile
                }
            }
        }
        return null
    }
}

sealed interface ZigValue {
    val typeOf: ZigValue
    fun toHtmlChunk(truncate: Boolean = false): HtmlChunk
    fun toPlainText() = Jsoup.parse(toHtmlChunk(truncate = true).toString()).text()
}

class ZigUnknown private constructor(override val typeOf: ZigValue) : ZigValue {
    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        if (DEBUG) {
            return HtmlChunk.fragment(
                createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@as"),
                HtmlChunk.text("("),
                typeOf.toHtmlChunk(truncate),
                HtmlChunk.text(", "),
                createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_"),
                HtmlChunk.text(")")
            )
        }
        return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
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

        private const val DEBUG = false
    }
}

data object ZigTypeType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "type")
}

data object ZigAnyerrorType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "anyerror")
}

data object ZigAnyopaqueType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "anyopaque")
}

data object ZigBoolType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "bool")
}

data object ZigFalseValue : ZigValue {
    override val typeOf: ZigBoolType
        get() = ZigBoolType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "false")
}

data object ZigTrueValue : ZigValue {
    override val typeOf: ZigBoolType
        get() = ZigBoolType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "true")
}

data object ZigNullType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = HtmlChunk.fragment(
        createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@TypeOf"),
        HtmlChunk.text("("),
        createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "null"),
        HtmlChunk.text(")")
    )
}

data object ZigNullValue : ZigValue {
    override val typeOf: ZigNullType
        get() = ZigNullType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "null")
}

data object ZigNoreturnType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "noreturn")
}

data object ZigUndefinedType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = HtmlChunk.fragment(
        createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@TypeOf"),
        HtmlChunk.text("("),
        createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "undefined"),
        HtmlChunk.text(")")
    )
}

data object ZigUndefinedValue : ZigValue {
    override val typeOf: ZigUndefinedType
        get() = ZigUndefinedType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "undefined")
}

data object ZigVoidType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "void")
}

data object ZigVoidValue : ZigValue {
    override val typeOf: ZigVoidType
        get() = ZigVoidType

    override fun toHtmlChunk(truncate: Boolean) = HtmlChunk.text("{}")
}

data object ZigEnumLiteralType : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = HtmlChunk.fragment(
        createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@EnumLiteral"),
        HtmlChunk.text("()")
    )
}

data class ZigEnumLiteralValue(override val typeOf: ZigValue, val name: String) : ZigValue {
    override fun toHtmlChunk(truncate: Boolean) = when (typeOf) {
        ZigEnumLiteralType -> HtmlChunk.fragment(
            HtmlChunk.text("."),
            HtmlChunk.text(name)
        )
        else -> HtmlChunk.fragment(
            createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@as"),
            HtmlChunk.text("("),
            typeOf.toHtmlChunk(truncate),
            HtmlChunk.text(", "),
            HtmlChunk.text("."),
            createStyledHtmlChunk(ZigHighlightingColors.INSTANCE_FIELD, name),
            HtmlChunk.text(")")
        )
    }
}

sealed interface IZigContainerType : ZigValue {
    override val typeOf: ZigTypeType
    val typeParams: Map<ZigFnParam, Set<ZigValue>>
    val containerKind: ZigContainerKind
    val members: Sequence<ZigContainerMember>
    fun getNamedMembersVisibleFrom(originalFile: PsiFile): Sequence<ZigNamedContainerMember>
}

data class ZigContainerType(
    val element: ZigContainer,
    override val typeParams: Map<ZigFnParam, Set<ZigValue>>,
) : IZigContainerType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createContainerHtmlChunkStartingFrom(element, typeParams, truncate)

    override val containerKind: ZigContainerKind
        get() = element.containerKind

    override val members: Sequence<ZigContainerMember>
        get() = element.members

    override fun getNamedMembersVisibleFrom(originalFile: PsiFile): Sequence<ZigNamedContainerMember> {
        val inSameFile = originalFile == element.containingFile
        return element.members.filterIsInstance<ZigNamedContainerMember>().filter {
            when (it) {
                is ZigVarDecl -> inSameFile || it.isPub
                is ZigFnDecl -> inSameFile || it.isPub
                else -> true
            }
        }
    }
}

data class ZigFunctionType(
    val element: ZigFnDeclOrProto,
    val typeParams: Map<ZigFnParam, Set<ZigValue>>,
) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        builder.append(createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "fn"))
        builder.append(" (")
        for ((i, param) in element.parameters.withIndex()) {
            if (i > 0) {
                builder.append(", ")
            }
            builder.append(createTypeElementHtmlChunk(param.typeElement, typeParams, truncate))
        }
        builder.append(") ")
        if (element.bangTokenElement != null) {
            builder.append("!")
        }
        builder.append(createTypeElementHtmlChunk(element.returnTypeElement, typeParams, truncate))
        return builder.toFragment()
    }
}

data class ZigComptimeParameterValue(override val typeOf: ZigValue, val element: ZigFnParam) : ZigValue {
    override fun toHtmlChunk(truncate: Boolean) = createNamedHtmlChunk(element, ZigHighlightingColors.COMPTIME_PARAMETER)
}

data class ZigOptionalType(val child: ZigValue) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = HtmlChunk.fragment(HtmlChunk.text("?"), child.toHtmlChunk(truncate))
}

data class ZigErrorSetType(val names: Set<String>) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        builder.append(createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "error"))
        if (names.isEmpty()) {
            builder.append("{}")
        } else if (truncate) {
            builder.append("{...}")
        } else {
            builder.append("{")
            for ((i, name) in names.withIndex()) {
                if (i > 0) {
                    builder.append(",")
                }
                builder.append(name)
            }
            builder.append("}")
        }
        return builder.toFragment()
    }
}

data class ZigErrorSetValue(override val typeOf: ZigErrorSetType, val name: String) : ZigValue {
    override fun toHtmlChunk(truncate: Boolean) = when (typeOf.names.singleOrNull()) {
        name -> HtmlChunk.fragment(
            createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "error"),
            HtmlChunk.text("."),
            HtmlChunk.text(name)
        )
        else -> HtmlChunk.fragment(
            createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@as"),
            HtmlChunk.text("("),
            typeOf.toHtmlChunk(truncate),
            HtmlChunk.text(", "),
            createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "error"),
            HtmlChunk.text("."),
            HtmlChunk.text(name),
            HtmlChunk.text(")")
        )
    }
}

data class ZigErrorUnionType(val errorSet: ZigValue, val payload: ZigValue) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = HtmlChunk.fragment(
        errorSet.toHtmlChunk(truncate),
        HtmlChunk.text("!"),
        payload.toHtmlChunk(truncate)
    )
}

data class ZigPointerType(
    val size: ZigPointerSize,
    val sentinel: ZigValue?,
    // TODO: include modifiers (const, volatile, allowzero, addrspace, align)
    val child: ZigValue,
) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        when (size) {
            ZigPointerSize.ONE -> builder.append("*")
            ZigPointerSize.C -> builder.append("[*c]")
            ZigPointerSize.MANY, ZigPointerSize.SLICE -> {
                builder.append("[")
                when (size) {
                    ZigPointerSize.MANY -> builder.append("*")
                    ZigPointerSize.SLICE -> {}
                }
                if (sentinel != null) {
                    builder.append(":")
                    builder.append(sentinel.toHtmlChunk(truncate))
                }
                builder.append("]")
            }
        }
        builder.append(child.toHtmlChunk(truncate))
        return builder.toFragment()
    }
}

data class ZigArrayType(val length: ZigValue, val sentinel: ZigValue?, val child: ZigValue) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        builder.append("[")
        builder.append(length.toHtmlChunk(truncate))
        if (sentinel != null) {
            builder.append(":")
            builder.append(sentinel.toHtmlChunk(truncate))
        }
        builder.append("]")
        builder.append(child.toHtmlChunk(truncate))
        return builder.toFragment()
    }
}

data class ZigTupleType(val element: LightZigTuple) : IZigContainerType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    val fields: List<ZigValue>
        get() = element.fieldTypes

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        if (fields.isEmpty()) {
            builder.append(createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@TypeOf"))
            builder.append("(.{})")
        } else if (truncate) {
            builder.append(createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "struct"))
            builder.append(" {...}")
        } else {
            builder.append(createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "struct"))
            builder.append(" { ")
            for ((i, field) in fields.withIndex()) {
                if (i > 0) {
                    builder.append(", ")
                }
                builder.append(field.toHtmlChunk(truncate))
            }
            builder.append(" }")
        }
        return builder.toFragment()
    }

    override val typeParams: Map<ZigFnParam, Set<ZigValue>>
        get() = emptyMap()

    override val containerKind: ZigContainerKind
        get() = ZigContainerKind.STRUCT

    override val members: Sequence<LightZigTupleField>
        get() = element.fields.asSequence()

    override fun getNamedMembersVisibleFrom(originalFile: PsiFile) = members
}

sealed interface ZigFloatType : ZigValue {
    override val typeOf: ZigTypeType
}

data object ZigComptimeFloatType : ZigFloatType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "comptime_float")
}

data class ZigFixedFloatType(val bits: Int) : ZigFloatType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "f${bits}")
}

data object ZigCLongDoubleType : ZigFloatType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "c_longdouble")
}

sealed interface ZigIntType : ZigValue {
    override val typeOf: ZigTypeType
}

data class ZigIntValue(override val typeOf: ZigIntType, val value: Int) : ZigValue {
    override fun toHtmlChunk(truncate: Boolean) = when (typeOf) {
        ZigComptimeIntType -> createStyledHtmlChunk(ZigHighlightingColors.NUMBER, value.toString())
        else -> HtmlChunk.fragment(
            createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@as"),
            HtmlChunk.text("("),
            typeOf.toHtmlChunk(truncate),
            HtmlChunk.text(", "),
            createStyledHtmlChunk(ZigHighlightingColors.NUMBER, value.toString()),
            HtmlChunk.text(")")
        )
    }
}

data object ZigComptimeIntType : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "comptime_int")
}

enum class ZigSignedness { SIGNED, UNSIGNED }

data class ZigFixedIntType(val signedness: ZigSignedness, val bits: Int) : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = when (signedness) {
        ZigSignedness.SIGNED -> createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "i${bits}")
        ZigSignedness.UNSIGNED -> createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "u${bits}")
    }
}

data object ZigIsizeType : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "isize")
}

data object ZigUsizeType : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "usize")
}

data object ZigCCharType : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "c_char")
}

data class ZigCShortType(val signedness: ZigSignedness) : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "c_short")
}

data class ZigCIntType(val signedness: ZigSignedness) : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "c_int")
}

data class ZigCLongType(val signedness: ZigSignedness) : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "c_long")
}

data class ZigCLongLongType(val signedness: ZigSignedness) : ZigIntType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "c_longlong")
}

data class ZigInferredUnionTagType(val union: ZigContainerType) : IZigContainerType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        builder.append(createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@typeInfo"))
        builder.append("(")
        builder.append(union.toHtmlChunk(truncate))
        builder.append(").")
        builder.append(createStyledHtmlChunk(ZigHighlightingColors.INSTANCE_FIELD, "@\"union\""))
        builder.append(".")
        builder.append(createStyledHtmlChunk(ZigHighlightingColors.INSTANCE_FIELD, "tag_type"))
        builder.append(".?")
        return builder.toFragment()
    }

    override val typeParams: Map<ZigFnParam, Set<ZigValue>>
        get() = emptyMap()

    override val containerKind: ZigContainerKind
        get() = ZigContainerKind.ENUM

    override val members: Sequence<ZigContainerField>
        get() = union.element.members.filterIsInstance<ZigContainerField>()

    override fun getNamedMembersVisibleFrom(originalFile: PsiFile) = members
}

data class ZigFieldEnumType(val container: ZigValue) : IZigContainerType {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        builder.append(createStyledHtmlChunk(ZigHighlightingColors.STATIC_FIELD, "meta"))
        builder.append(".")
        builder.append(createStyledHtmlChunk(ZigHighlightingColors.STATIC_METHOD, "FieldEnum"))
        builder.append("(")
        builder.append(container.toHtmlChunk(truncate))
        builder.append(")")
        return builder.toFragment()
    }

    override val typeParams: Map<ZigFnParam, Set<ZigValue>>
        get() = emptyMap()

    override val containerKind: ZigContainerKind
        get() = ZigContainerKind.ENUM

    override val members: Sequence<ZigContainerField>
        get() = when (container) {
            is IZigContainerType -> container.members.filterIsInstance<ZigContainerField>()
            else -> emptySequence()
        }

    override fun getNamedMembersVisibleFrom(originalFile: PsiFile) = members
}

data class ZigLazyFieldType(val container: ZigValue, val fieldName: ZigValue) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        builder.append(createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@FieldType"))
        builder.append("(")
        builder.append(container.toHtmlChunk(truncate))
        builder.append(", ")
        builder.append(fieldName.toHtmlChunk(truncate))
        builder.append(")")
        return builder.toFragment()
    }
}

data class ZigLazyFieldValue(val left: ZigValue, val fieldName: ZigValue) : ZigValue {
    override val typeOf = ZigUnknown.of(ZigTypeType)

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        if (fieldName is ZigNullTerminatedStringValue) {
            builder.append(left.toHtmlChunk(truncate))
            builder.append(".")
            builder.append(fieldName.value)
        } else {
            builder.append(createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@field"))
            builder.append("(")
            builder.append(left.toHtmlChunk(truncate))
            builder.append(", ")
            builder.append(fieldName.toHtmlChunk(truncate))
            builder.append(")")
        }
        return builder.toFragment()
    }
}

data class ZigLazyTagNameValue(val value: ZigValue) : ZigValue {
    override val typeOf = ZigPointerType(
        ZigPointerSize.ONE,
        null,
        ZigArrayType(
            ZigUnknown.ofUnknownType(),
            ZigIntValue(ZigComptimeIntType, 0),
            ZigFixedIntType(ZigSignedness.UNSIGNED, 8)
        )
    )

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        builder.append(createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@tagName"))
        builder.append("(")
        builder.append(value.toHtmlChunk(truncate))
        builder.append(")")
        return builder.toFragment()
    }
}

data class ZigNullTerminatedStringValue(val value: String) : ZigValue {
    override val typeOf = ZigPointerType(
        ZigPointerSize.ONE,
        null,
        ZigArrayType(
            ZigIntValue(ZigComptimeIntType, value.length),
            ZigIntValue(ZigComptimeIntType, 0),
            ZigFixedIntType(ZigSignedness.UNSIGNED, 8)
        )
    )

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val text = buildString {
            escapeZigString(value, this)
        }
        return createStyledHtmlChunk(ZigHighlightingColors.STRING, text)
    }
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
        is ZigContainerExpression -> {
            val tupleType = run {
                if (element.containerKind != ZigContainerKind.STRUCT) {
                    return@run null
                }
                val fields = mutableListOf<ZigValue>()
                for (member in element.members) {
                    if (member !is ZigContainerField) {
                        return@run null
                    }
                    if (member.nameIdentifier != null) {
                        return@run null
                    }
                    val types = resolveValuesFromReferenceTo(member).asSequence().map { it.typeOf }.toSet()
                    // For efficiency, limit each field to a single possible type.
                    fields.add(types.singleOrNull() ?: ZigUnknown.of(ZigTypeType))
                }
                if (fields.isEmpty()) {
                    return@run null
                }
                val lightTuple = LightZigTuple(element.manager, fields)
                lightTuple.navigationElement = element
                for ((i, member) in element.members.withIndex()) {
                    lightTuple.fields[i].navigationElement = member
                }
                ZigTupleType(lightTuple)
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

        is ZigErrorSet -> {
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
            val unionElement = getUnwrapUnionTagTypeArgument(element)
            if (unionElement != null) {
                return resolveValues(unionElement)
                    .asSequence()
                    .filterIsInstance<ZigContainerType>()
                    .flatMap { ZigAnalyzer.resolveUnionTagTypes(it) }
            }
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
                val types = when (val expressionElement = item.expression) {
                    null -> emptySet()
                    else -> resolveValues(expressionElement).asSequence().map { it.typeOf }.toSet()
                }
                // For efficiency, limit each field to a single possible type.
                fieldTypes.add(types.singleOrNull() ?: ZigUnknown.ofUnknownType())
            }
            sequenceOf(ZigUnknown.of(ZigTupleType(LightZigTuple(element.manager, fieldTypes))))
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
                    is ZigReferenceable -> resolveValuesFromReferenceTo(target).asSequence()
                    else -> sequenceOf(ZigUnknown.ofUnknownType())
                }
            }
        }

        is ZigFieldAccess -> {
            val name = element.identifierElement?.text ?: return sequenceOf(ZigUnknown.ofUnknownType())
            val fieldName = ZigNullTerminatedStringValue(name)
            resolveValues(element.leftOperand).asSequence().flatMap { computeFieldAccess(it, fieldName) }
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

        is ZigSlice -> {
            // TODO: if the indexes are non-comptime, return a slice instead of an array of unknown length
            val length = ZigUnknown.ofUnknownType()
            var sentinel: ZigValue? = null
            val sentinelElement = element.sentinelElement?.innerElement
            if (sentinelElement != null) {
                sentinel = resolveValues(sentinelElement).singleOrNull() ?: ZigUnknown.ofUnknownType()
            }
            resolveValues(element.sliced).asSequence().map { sliced ->
                val childType = when (val typeOfSliced = sliced.typeOf) {
                    is ZigArrayType -> typeOfSliced.child

                    is ZigPointerType -> when (typeOfSliced.size) {
                        ZigPointerSize.ONE -> when (val pointerChild = typeOfSliced.child) {
                            is ZigArrayType -> pointerChild.child
                            else -> ZigUnknown.ofUnknownType() // TODO
                        }

                        ZigPointerSize.MANY,
                        ZigPointerSize.SLICE,
                        ZigPointerSize.C -> typeOfSliced.child
                    }

                    else -> ZigUnknown.ofUnknownType()
                }
                val arrayType = ZigArrayType(length, sentinel, childType)
                val pointerType = ZigPointerType(ZigPointerSize.ONE, null, arrayType)
                ZigUnknown.of(pointerType)
            }
        }

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

            "@field" -> {
                val argElements = element.argumentElements.toList()
                val leftElement = argElements.getOrNull(0)?.innerElement
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                val fieldNameElement = argElements.getOrNull(1)?.innerElement
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                val leftValues = resolveValues(leftElement).asSequence()
                val fieldNameValues = resolveValues(fieldNameElement).asSequence()
                computeFieldValues(leftValues, fieldNameValues)
            }

            "@FieldType" -> {
                val argElements = element.argumentElements.toList()
                val containerElement = argElements.getOrNull(0)?.innerElement
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                val fieldNameElement = argElements.getOrNull(1)?.innerElement
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                val containerTypes = resolveValues(containerElement).asSequence()
                val fieldNameValues = resolveValues(fieldNameElement).asSequence()
                computeFieldTypeValues(containerTypes, fieldNameValues)
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

            "@tagName" -> {
                val argElement = element.argumentElements.firstOrNull()?.innerElement
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                computeTagNameValues(resolveValues(argElement).asSequence())
            }

            "@This" -> {
                val containerElement = element.findParentOfType<ZigContainer>()
                    ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
                val typeParams = findUnboundTypeParams(containerElement)
                sequenceOf(ZigContainerType(containerElement, typeParams))
            }

            "@typeInfo" -> {
                val langFile = ZigAnalyzer.findStdFile(element, "lang.zig")
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                val member = langFile.members.filterIsInstance<ZigNamedContainerMember>().firstOrNull { it.name == "Type" }
                if (member != null) {
                    resolveValuesFromReferenceTo(member).asSequence().map { ZigUnknown.of(it) }
                } else {
                    sequenceOf(ZigUnknown.ofUnknownType())
                }
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
            // TODO: peer type resolution
            values.ifEmpty { sequenceOf(ZigVoidValue) }
        }

        is ZigSwitch -> {
            var values = computeValuesFromLabeledBreaks(element)
            values += element.prongs.flatMap { prongElement ->
                prongElement.expression?.let { resolveValues(it).asSequence() }
                    ?: sequenceOf(ZigUnknown.ofUnknownType())
            }
            // TODO: peer type resolution
            values.ifEmpty { sequenceOf(ZigUnknown.ofUnknownType()) }
        }

        is ZigFor,
        is ZigWhile -> {
            var values = computeValuesFromLabeledBreaks(element)
            values += computeValuesFromUnlabeledBreaks(element)
            val elseElement = element.elseElement?.innerElement
            if (elseElement != null) {
                values += resolveValues(elseElement).asSequence()
            } else {
                values += sequenceOf(ZigVoidValue)
            }
            // TODO: peer type resolution
            values
        }

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

        is ZigEnumLiteral -> {
            val name = element.identifierElement?.text
            if (name != null) {
                sequenceOf(ZigEnumLiteralValue(ZigEnumLiteralType, name))
            } else {
                sequenceOf(ZigUnknown.of(ZigEnumLiteralType))
            }
        }

        is ZigBreak,
        is ZigContinue,
        is ZigReturn,
        is ZigUnreachableLiteral -> sequenceOf(ZigUnknown.of(ZigNoreturnType))

        is ZigAnyframeLiteral,
        is ZigAnyframeType,
        is ZigAsm,
        is ZigNosuspend,
        is ZigResume -> sequenceOf(ZigUnknown.ofUnknownType())

        else -> throw IllegalArgumentException("${element.javaClass.simpleName}")
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

private fun computeFieldAccess(
    left: ZigValue,
    fieldName: ZigNullTerminatedStringValue,
): Sequence<ZigValue> {
    val name = fieldName.value
    val builtinType = ZigAnalyzer.getBuiltinFieldType(left.typeOf, name)
    if (builtinType != null) {
        return sequenceOf(ZigUnknown.of(builtinType))
    }
    return when (left) {
        is ZigErrorSetType -> {
            if (name in left.names) {
                sequenceOf(ZigErrorSetValue(left, name))
            } else {
                sequenceOf(ZigUnknown.of(left))
            }
        }
        is IZigContainerType -> computeContainerTypeDeclValues(left, fieldName)
        else -> when (val typeOfLeft = left.typeOf) {
            is IZigContainerType -> computeContainerInstanceFieldValues(left, typeOfLeft, fieldName)
            is ZigPointerType -> when (typeOfLeft.size) {
                ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                    is IZigContainerType -> computeContainerInstanceFieldValues(left, pointerChild, fieldName)
                    else -> sequenceOf(ZigLazyFieldValue(left, fieldName))
                }
                else -> sequenceOf(ZigLazyFieldValue(left, fieldName))
            }
            else -> sequenceOf(ZigLazyFieldValue(left, fieldName))
        }
    }
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

private fun findUnboundTypeParams(element: ZigElement): Map<ZigFnParam, Set<ZigValue>> {
    return buildMap {
        var element: PsiElement? = element
        while (element != null) {
            if (element is ZigFnDecl) {
                for (paramDecl in element.parameters) {
                    if (ZigAnalyzer.resolveComptimeParameterTypes(paramDecl).isNotEmpty()) {
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
): Map<ZigFnParam, Set<ZigValue>> {
    if (function.typeParams.isEmpty()) {
        return emptyMap()
    }
    return function.typeParams.toMutableMap().apply {
        for ((paramDecl, argValues) in function.element.parameters.zip(arguments)) {
            if (ZigAnalyzer.resolveComptimeParameterTypes(paramDecl).isNotEmpty()) {
                this[paramDecl] = argValues
            }
        }
    }
}

private fun substituteContainerTypeParams(
    type: ZigContainerType,
    typeParams: Map<ZigFnParam, Set<ZigValue>>,
): ZigContainerType {
    return if (type.typeParams.isEmpty()) {
        type
    } else {
        // TODO: should we only update already-existing parameters?
        ZigContainerType(type.element, type.typeParams + typeParams)
    }
}

private fun substituteFunctionTypeParams(
    type: ZigFunctionType,
    typeParams: Map<ZigFnParam, Set<ZigValue>>,
): ZigFunctionType {
    return if (type.typeParams.isEmpty()) {
        type
    } else {
        // TODO: should we only update already-existing parameters?
        ZigFunctionType(type.element, type.typeParams + typeParams)
    }
}

private fun substituteTypeParams(
    value: ZigValue,
    typeParams: Map<ZigFnParam, Set<ZigValue>>,
): Sequence<ZigValue> {
    return RecursionManager.doPreventingRecursion(value, true) {
        when (value) {
            is ZigUnknown -> {
                substituteTypeParams(value.typeOf, typeParams).map { ZigUnknown.of(it) }
            }
            is ZigEnumLiteralValue -> {
                substituteTypeParams(value.typeOf, typeParams).map { ZigEnumLiteralValue(it, value.name) }
            }
            is ZigContainerType -> {
                sequenceOf(substituteContainerTypeParams(value, typeParams))
            }
            is ZigFunctionType -> {
                sequenceOf(substituteFunctionTypeParams(value, typeParams))
            }
            is ZigComptimeParameterValue -> {
                val values = typeParams[value.element]
                if (values.isNullOrEmpty()) {
                    sequenceOf(value)
                } else {
                    values.asSequence().flatMap { substituteTypeParams(it, typeParams) }
                }
            }
            is ZigOptionalType -> {
                substituteTypeParams(value.child, typeParams).map { ZigOptionalType(it) }
            }
            is ZigErrorUnionType -> {
                val errorSetTypes = substituteTypeParams(value.errorSet, typeParams)
                val errorSet = errorSetTypes.singleOrNull() ?: ZigUnknown.of(ZigTypeType)
                val payloadTypes = substituteTypeParams(value.payload, typeParams)
                payloadTypes.map { ZigErrorUnionType(errorSet, it) }
            }
            is ZigArrayType -> {
                val lengthValues = substituteTypeParams(value.length, typeParams)
                val length = lengthValues.singleOrNull() ?: ZigUnknown.ofUnknownType()
                val sentinelValues = value.sentinel?.let { substituteTypeParams(it, typeParams) }
                val sentinel = sentinelValues?.singleOrNull()
                val childTypes = substituteTypeParams(value.child, typeParams)
                childTypes.map { ZigArrayType(length, sentinel, it) }
            }
            is ZigPointerType -> {
                val sentinelValues = value.sentinel?.let { substituteTypeParams(it, typeParams) }
                val sentinel = sentinelValues?.singleOrNull()
                val childTypes = substituteTypeParams(value.child, typeParams)
                childTypes.map { ZigPointerType(value.size, sentinel, it) }
            }
            is ZigTupleType -> {
                val fields = value.fields.map { field ->
                    val substitutions = substituteTypeParams(field, typeParams).toSet()
                    // For efficiency, limit each field to a single possible type.
                    substitutions.singleOrNull() ?: ZigUnknown.of(ZigTypeType)
                }
                sequenceOf(ZigTupleType(LightZigTuple(value.element.manager, fields)))
            }
            is ZigInferredUnionTagType -> {
                sequenceOf(ZigInferredUnionTagType(substituteContainerTypeParams(value.union, typeParams)))
            }
            is ZigFieldEnumType -> {
                substituteTypeParams(value.container, typeParams).map { ZigFieldEnumType(it) }
            }
            is ZigLazyFieldType -> {
                val containerTypes = substituteTypeParams(value.container, typeParams)
                val fieldNameValues = substituteTypeParams(value.fieldName, typeParams)
                computeFieldTypeValues(containerTypes, fieldNameValues)
            }
            is ZigLazyFieldValue -> {
                val leftValues = substituteTypeParams(value.left, typeParams)
                val fieldNameValues = substituteTypeParams(value.fieldName, typeParams)
                computeFieldValues(leftValues, fieldNameValues)
            }
            is ZigLazyTagNameValue -> {
                computeTagNameValues(substituteTypeParams(value.value, typeParams))
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
            is ZigIntValue,
            is ZigNullTerminatedStringValue,
                -> sequenceOf(value)
        }
    } ?: sequenceOf(value)
}

private fun computeContainerInstanceFieldValues(
    left: ZigValue,
    type: IZigContainerType,
    fieldName: ZigNullTerminatedStringValue,
): Sequence<ZigValue> {
    val name = fieldName.value
    if (type.containerKind == ZigContainerKind.ENUM) {
        return sequenceOf(ZigLazyFieldValue(left, fieldName))
    }
    val member = type.members.firstOrNull {
        it is PsiNameIdentifierOwner && it.name == name
    }
    if (member == null || member !is ZigContainerField) {
        return sequenceOf(ZigLazyFieldValue(left, fieldName))
    }
    var values = resolveValuesFromReferenceTo(member).asSequence()
    if (type.typeParams.isNotEmpty()) {
        values = values.flatMap { substituteTypeParams(it, type.typeParams) }
    }
    return values
}

private fun computeContainerTypeDeclValues(
    type: IZigContainerType,
    fieldName: ZigNullTerminatedStringValue,
): Sequence<ZigValue> {
    val name = fieldName.value
    val member = type.members.filterIsInstance<ZigNamedContainerMember>().firstOrNull { it.name == name }
    if (member == null) {
        return sequenceOf(ZigLazyFieldValue(type, fieldName))
    }
    if (member is ZigContainerField) {
        return if (type.containerKind == ZigContainerKind.ENUM) {
            sequenceOf(ZigEnumLiteralValue(type, name))
        } else if (type is ZigContainerType && type.element.isTaggedUnion) {
            ZigAnalyzer.resolveUnionTagTypes(type).map { ZigEnumLiteralValue(it, name) }
        } else {
            sequenceOf(ZigLazyFieldValue(type, fieldName))
        }
    }
    var values = resolveValuesFromReferenceTo(member).asSequence()
    if (type.typeParams.isNotEmpty()) {
        values = values.flatMap { substituteTypeParams(it, type.typeParams) }
    }
    return values
}

private fun resolveValuesFromReferenceTo(target: ZigReferenceable): Set<ZigValue> {
    return CachedValuesManager.getCachedValue(target) {
        RecursionManager.doPreventingRecursion(target, true) {
            val values = computeValuesFromReferenceUncached(target).toSet()
            CachedValueProvider.Result.create(values, PsiModificationTracker.MODIFICATION_COUNT)
        }
    } ?: emptySet()
}

private fun computeValuesFromReferenceUncached(target: ZigReferenceable): Sequence<ZigValue> {
    return when (target) {
        is ZigFile -> sequenceOf(ZigContainerType(target, emptyMap()))

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

                    is ZigArrayType -> ZigUnknown.of(typeOfInit.child)

                    // TODO: destructure vector

                    else -> ZigUnknown.ofUnknownType()
                }
            }
        }

        is ZigFnParam -> {
            val comptimeTypes = ZigAnalyzer.resolveComptimeParameterTypes(target)
            if (comptimeTypes.isNotEmpty()) {
                return comptimeTypes.asSequence().map { ZigComptimeParameterValue(it, target) }
            }
            val typeElement = target.typeElement?.innerElement ?: return sequenceOf(ZigUnknown.ofUnknownType())
            val types = resolveValues(typeElement)
            types.asSequence().map { ZigUnknown.of(it) }
        }

        is ZigContainerFieldImpl -> {
            when (val typeElement = target.typeElement?.innerElement) {
                null -> when (target.container.containerKind) {
                    ZigContainerKind.STRUCT,
                    ZigContainerKind.OPAQUE -> sequenceOf(ZigUnknown.ofUnknownType())
                    ZigContainerKind.ENUM -> {
                        val typeParams = findUnboundTypeParams(target.container)
                        sequenceOf(ZigUnknown.of(ZigContainerType(target.container, typeParams)))
                    }
                    ZigContainerKind.UNION -> when ((target.container as ZigContainerExpression).containerTagElement) {
                        null -> sequenceOf(ZigUnknown.ofUnknownType())
                        else -> sequenceOf(ZigVoidValue)
                    }
                }
                else -> resolveValues(typeElement).asSequence().map { ZigUnknown.of(it) }
            }
        }

        is ZigPayloadItem -> {
            val values = computePayloadItemValues(target)
            if (target.isPointer) {
                values.map { ZigUnknown.of(ZigPointerType(ZigPointerSize.ONE, null, it.typeOf)) }
            } else {
                values
            }
        }

        is LightZigTupleField -> sequenceOf(ZigUnknown.of(target.container.fieldTypes[target.fieldIndex]))
    }
}

private fun computeFunctionReturnValues(
    type: ZigFunctionType,
    arguments: Sequence<Set<ZigValue>>,
): Sequence<ZigValue> {
    val functionElement = type.element
    if (functionElement is ZigFnDecl
        && functionElement.containingFile.name == "meta.zig"
        && functionElement.containingFile.containingDirectory?.name == "std"
    ) {
        when (functionElement.name) {
            "Tag" -> {
                val values = arguments.firstOrNull()
                    ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
                return values.asSequence().flatMap { computeTagType(it) }
            }
            "FieldEnum" -> {
                val values = arguments.firstOrNull()
                    ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
                return values.asSequence().map { ZigFieldEnumType(it) }
            }
            else -> {}
        }
    }
    val returnTypeElement = functionElement.returnTypeElement?.innerElement
        ?: return sequenceOf(ZigUnknown.ofUnknownType())
    val returnTypes = if (functionElement.bangTokenElement != null) {
        val errorSet = ZigUnknown.ofUnknownType() // TODO: infer error set
        resolveValues(returnTypeElement).map { ZigErrorUnionType(errorSet, it) }
    } else {
        resolveValues(returnTypeElement)
    }
    if (returnTypes.singleOrNull() == ZigTypeType) {
        val functionBodyElement = (functionElement as? ZigFnDecl)?.body
        if (functionBodyElement != null) {
            val typeParams = computeTypeParams(type, arguments)
            val traverser = SyntaxTraverser.psiTraverser(functionBodyElement).expand { it !is ZigContainerExpression }
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

private fun computeTagType(type: ZigValue): Sequence<ZigValue> {
    return when (type) {
        is IZigContainerType -> {
            if (type.containerKind == ZigContainerKind.ENUM) {
                ZigAnalyzer.resolveEnumTagTypes(type)
            } else if (type is ZigContainerType && type.element.isTaggedUnion) {
                ZigAnalyzer.resolveUnionTagTypes(type)
            } else {
                emptySequence()
            }
        }
        else -> emptySequence()
    }
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

// TODO: can this return `emptySequence()` instead of `sequenceOf(ZigUnknown.ofUnknownType() to null)`?
internal fun computeCalledFuncAndSelfArg(callElement: ZigCall): Sequence<Pair<ZigValue, ZigValue?>> {
    return when (val functionElement = callElement.functionElement) {
        is ZigFieldAccess -> {
            val name = functionElement.identifierElement?.text
                ?: return sequenceOf(ZigUnknown.ofUnknownType() to null)
            val fieldName = ZigNullTerminatedStringValue(name)
            resolveValues(functionElement.leftOperand).asSequence().flatMap { left ->
                when (left) {
                    is IZigContainerType -> {
                        computeContainerTypeDeclValues(left, fieldName).map { it to null }
                    }
                    else -> when (val typeOfLeft = left.typeOf) {
                        is IZigContainerType -> {
                            computeContainerTypeDeclValues(typeOfLeft, fieldName).map { it to left }
                        }
                        is ZigPointerType -> when (typeOfLeft.size) {
                            ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                                is IZigContainerType -> {
                                    computeContainerTypeDeclValues(pointerChild, fieldName).map { it to left }
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

        is ZigEnumLiteral -> {
            resolveReferences(functionElement)
                .filterIsInstance<ZigReferenceable>()
                .flatMap { resolveValuesFromReferenceTo(it).asSequence() }
                .map { it to null }
        }

        else -> resolveValues(functionElement).asSequence().map { it to null }
    }
}

private fun computeValuesFromLabeledBreaks(element: ZigLabelable): Sequence<ZigValue> {
    val labelDecl = element.labelDecl ?: return emptySequence()
    val labelName = labelDecl.identifierElement.text
    val traverser = SyntaxTraverser.psiTraverser(element).expand { it !is ZigContainerExpression }
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

private fun computeValuesFromUnlabeledBreaks(loop: ZigLoop): Sequence<ZigValue> {
    val traverser = SyntaxTraverser.psiTraverser(loop.thenElement).expand { descendant ->
        when (descendant) {
            is ZigContainerExpression -> false
            is ZigThenElement -> descendant.parent == loop || descendant.parent !is ZigLoop
            else -> true
        }
    }
    return traverser.asSequence().flatMap { descendant ->
        if (descendant is ZigBreak && descendant.labelRef == null) {
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

        is ZigContainerFieldImpl -> {
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

        is ZigAssignDestructure -> {
            if (parentElement.initializer !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val fields = parentElement.variables.map { variable ->
                val types = when (variable) {
                    is ZigVarProto -> {
                        val typeElement = variable.typeElement?.innerElement
                            ?: return@map ZigUnknown.of(ZigTypeType)
                        resolveValues(typeElement)
                    }
                    else -> resolveValues(variable).asSequence().map { it.typeOf }.toSet()
                }
                // For efficiency, limit each field to a single possible type.
                types.singleOrNull() ?: ZigUnknown.of(ZigTypeType)
            }
            sequenceOf(ZigTupleType(LightZigTuple(childElement.manager, fields.toList())))
        }

        is ZigListInitItem -> {
            if (parentElement.expression !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            if (parentElement.isStructField) {
                resolveReferences(parentElement)
                    .filterIsInstance<ZigReferenceable>()
                    .flatMap { resolveValuesFromReferenceTo(it).asSequence() }
                    .map { it.typeOf }
                    .ifEmpty { sequenceOf(ZigUnknown.of(ZigTypeType)) }
            } else {
                val initElement = parentElement.containingListInit
                ZigAnalyzer.resolveOrInferListInitTypes(initElement).map { type ->
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
            if (parentElement.innerElement !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val switchElement = parentElement.containingSwitchProng.containingSwitch
            val conditionElement = switchElement.conditionElement.innerElement
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            resolveValues(conditionElement).asSequence().flatMap {
                val type = it.typeOf
                if (type is ZigContainerType && type.element.isTaggedUnion) {
                    ZigAnalyzer.resolveUnionTagTypes(type)
                } else {
                    sequenceOf(type)
                }
            }
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
            val parentTypes = inferResultTypes(parentElement.parent as ZigElement)
            val grandparentElement = parentElement.parent
            if (grandparentElement is ZigIf) {
                val thenElement = grandparentElement.thenElement.innerElement
                if (thenElement != null) {
                    val thenTypes = resolveValues(thenElement).asSequence().map { it.typeOf }
                    return parentTypes + thenTypes
                }
            }
            parentTypes
        }

        is ZigOrelse,
        is ZigCatch -> {
            if (parentElement.rightOperand !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            inferResultTypes(parentElement)
        }

        is ZigAddressOf -> {
            if (parentElement.operand !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            inferResultTypes(parentElement)
                .filterIsInstance<ZigPointerType>()
                .map {
                    when (it.size) {
                        ZigPointerSize.ONE -> it.child

                        ZigPointerSize.SLICE -> {
                            val length = ZigUnknown.ofUnknownType()
                            ZigArrayType(length, null, it.child)
                        }

                        ZigPointerSize.MANY,
                        ZigPointerSize.C -> ZigUnknown.of(ZigTypeType)
                    }
                }
                .ifEmpty { sequenceOf(ZigUnknown.of(ZigTypeType)) }
        }

        is ZigReturn -> {
            val fnDecl = childElement.findParentOfType<ZigFnDecl>()
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            val typeElement = fnDecl.returnTypeElement?.innerElement
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            if (fnDecl.bangTokenElement != null) {
                val errorSet = ZigUnknown.ofUnknownType()
                resolveValues(typeElement).asSequence().map { ZigErrorUnionType(errorSet, it) }
            } else {
                resolveValues(typeElement).asSequence()
            }
        }

        is ZigBreak -> {
            if (parentElement.breakValue !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val label = parentElement.labelRef?.identifierElement?.text
            val breakable = if (label != null) {
                parentElement.findParentInFile {
                    it is ZigLabelable && label == it.labelDecl?.identifierElement?.text
                }
            } else {
                var foundThenElemAsChild = false
                parentElement.findParentInFile { current ->
                    if (foundThenElemAsChild && current is ZigLoop) {
                        true
                    } else {
                        foundThenElemAsChild = current is ZigThenElement
                        false
                    }
                }
            }
            if (breakable !is ZigElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            inferResultTypes(breakable)
        }

        is ZigContinue -> {
            if (parentElement.continueOperand !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val label = parentElement.labelRef?.identifierElement?.text
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            val labelable = parentElement.findParentInFile {
                it is ZigLabelable && label == it.labelDecl?.identifierElement?.text
            }
            if (labelable !is ZigSwitch) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val conditionElement = labelable.conditionElement.innerElement
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            resolveValues(conditionElement).asSequence().map { it.typeOf }
        }

        is ZigComptime -> {
            if (parentElement.innerElement !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            inferResultTypes(parentElement)
        }

        is ZigCall -> {
            if (parentElement.functionElement !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val grandparentElement = parentElement.parent
            if (grandparentElement is ZigTry) {
                val errorSet = ZigUnknown.ofUnknownType()
                return inferResultTypes(grandparentElement).map { ZigErrorUnionType(errorSet, it) }
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
            val argIndex = builtinCallElement.argumentElements.indexOf(parentElement)
            assert(argIndex >= 0)
            when (builtinCallElement.builtinElement.text) {
                "@as" -> {
                    if (argIndex != 1) {
                        return sequenceOf(ZigUnknown.of(ZigTypeType))
                    }
                    val firstArgElement = builtinCallElement.argumentElements.firstOrNull()?.innerElement
                        ?: return sequenceOf(ZigUnknown.ofUnknownType())
                    resolveValues(firstArgElement).asSequence()
                }

                "@memset" -> {
                    if (argIndex != 1) {
                        return sequenceOf(ZigUnknown.of(ZigTypeType))
                    }
                    val firstArgElement = builtinCallElement.argumentElements.firstOrNull()?.innerElement
                        ?: return sequenceOf(ZigUnknown.ofUnknownType())
                    resolveValues(firstArgElement).asSequence().mapNotNull {
                        when (val type = it.typeOf) {
                            is ZigPointerType -> when (type.size) {
                                ZigPointerSize.SLICE -> type.child
                                ZigPointerSize.ONE -> when (val child = type.child) {
                                    is ZigArrayType -> child.child
                                    else -> null
                                }
                                ZigPointerSize.MANY,
                                ZigPointerSize.C -> null
                            }
                            else -> null
                        }
                    }
                }

                // TODO
                else -> sequenceOf(ZigUnknown.of(ZigTypeType))
            }
        }

        // TODO
        else -> sequenceOf(ZigUnknown.of(ZigTypeType))
    }
}

private fun createStyledHtmlChunk(attributesKey: TextAttributesKey, value: String): HtmlChunk {
    val sb = StringBuilder()
    HtmlSyntaxInfoUtil.appendStyledSpan(sb, attributesKey, value, 1f)
    return HtmlChunk.raw(sb.toString())
}

private fun createNamedHtmlChunk(element: PsiNamedElement, attributesKey: TextAttributesKey): HtmlChunk {
    val name = element.name
    if (name != null) {
        val target = buildString {
            append(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)
            append(element.containingFile.viewProvider.virtualFile.url)
            append(ZIG_DOCUMENTATION_OFFSET_SEPARATOR)
            append(element.textOffset)
        }
        return HtmlChunk.link(target, createStyledHtmlChunk(attributesKey, name))
    } else {
        return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
    }
}

private fun createTypeElementHtmlChunk(
    typeElement: ZigTypeElement?,
    typeParams: Map<ZigFnParam, Set<ZigValue>>,
    truncate: Boolean,
): HtmlChunk {
    val value = typeElement?.innerElement?.let {
        val initialValues = resolveValues(it)
        val values = ZigAnalyzer.substituteTypeParams(initialValues, typeParams)
        values.singleOrNull()
    }
    return value?.toHtmlChunk(truncate) ?: createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
}

private fun createContainerHtmlChunkStartingFrom(
    element: PsiElement,
    typeParams: Map<ZigFnParam, Set<ZigValue>>,
    truncate: Boolean,
): HtmlChunk {
    if (element is ZigContainer) {
        return when (element) {
            is ZigFile -> {
                val name = element.name
                assert(name.endsWith(".zig"))
                val text = name.substringBeforeLast('.')
                val target = buildString {
                    append(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)
                    append(element.viewProvider.virtualFile.url)
                }
                HtmlChunk.link(target, createStyledHtmlChunk(ZigHighlightingColors.CONSTANT, text))
            }

            is ZigContainerExpression -> when (val parent = element.parent!!) {
                is ZigVarDecl if parent.initializer == element -> {
                    val parentChunk = createNamedHtmlChunk(parent, ZigHighlightingColors.CONSTANT)
                    if (truncate) {
                        return parentChunk
                    }
                    val grandparent = parent.parent!!
                    val grandparentChunk = createContainerHtmlChunkStartingFrom(grandparent, typeParams, false)
                    HtmlChunk.fragment(grandparentChunk, HtmlChunk.text("."), parentChunk)
                }

                is ZigReturn if parent.returnValue == element -> {
                    val fnDecl = parent.findParentOfType<ZigFnDecl>()
                        ?: return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
                    val fnChunk = createContainerHtmlChunkStartingFrom(fnDecl, typeParams, truncate)
                    val builder = HtmlBuilder()
                    builder.append(fnChunk)
                    builder.append("(")
                    for ((i, paramDecl) in fnDecl.parameters.withIndex()) {
                        if (i > 0) {
                            builder.append(", ")
                        }
                        builder.append(createParameterHtmlChunk(paramDecl, typeParams, truncate))
                    }
                    builder.append(")")
                    builder.toFragment()
                }

                else -> {
                    val parentChunk = createContainerHtmlChunkStartingFrom(parent, typeParams, truncate)
                    val suffix = when (element.containerKind) {
                        ZigContainerKind.STRUCT -> "__struct"
                        ZigContainerKind.OPAQUE -> "__opaque"
                        ZigContainerKind.ENUM -> "__enum"
                        ZigContainerKind.UNION -> "__union"
                    }
                    HtmlChunk.fragment(parentChunk, HtmlChunk.text(suffix))
                }
            }
        }
    }

    val parent = element.parent!!
    val elementChunk = when (element) {
        is ZigTestDecl -> {
            // TODO: `test` -> `test_{0,1,etc.}`, `test "foo"` -> `test.foo`, `test foo` -> `decltest.foo`
            createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "test")
        }
        is ZigComptime if parent is ZigContainer -> {
            createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "comptime")
        }
        is ZigVarDecl if parent is ZigContainer -> {
            val attributesKey = when (element.mutability) {
                ZigMutability.CONST -> ZigHighlightingColors.CONSTANT
                ZigMutability.VAR -> ZigHighlightingColors.STATIC_FIELD
            }
            createNamedHtmlChunk(element, attributesKey)
        }
        is ZigFnDecl -> {
            createNamedHtmlChunk(element, ZigHighlightingColors.STATIC_METHOD)
        }
        else -> return createContainerHtmlChunkStartingFrom(parent, typeParams, truncate)
    }
    if (truncate) {
        return elementChunk
    }
    val parentChunk = createContainerHtmlChunkStartingFrom(parent, typeParams, false)
    return HtmlChunk.fragment(parentChunk, HtmlChunk.text("."), elementChunk)
}

private fun createParameterHtmlChunk(
    param: ZigFnParam,
    typeParams: Map<ZigFnParam, Set<ZigValue>>,
    truncate: Boolean,
): HtmlChunk {
    for (type in ZigAnalyzer.resolveComptimeParameterTypes(param)) {
        val comptimeParam = ZigComptimeParameterValue(type, param)
        val comptimeArg = substituteTypeParams(comptimeParam, typeParams).singleOrNull()
        if (comptimeArg != null) {
            return comptimeArg.toHtmlChunk(truncate)
        }
    }
    return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
}

private fun computePayloadItemValues(payloadItem: ZigPayloadItem): Sequence<ZigValue> {
    return when (val payloadElement = payloadItem.containingPayload) {
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

        is ZigSwitchPayload -> when (payloadElement.items.indexOf(payloadItem)) {
            0 -> {
                // TODO: shrink error set in `else` prong
                val switchProngElement = payloadElement.containingSwitchProng
                if (switchProngElement.isInline) {
                    return sequenceOf(ZigUnknown.ofUnknownType()) // TODO
                }
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
                val conditionValues = resolveValues(conditionElement).asSequence()
                if (switchItemElements.isEmpty()) {
                    return conditionValues.map { ZigUnknown.of(it.typeOf) }
                }
                conditionValues.map { it.typeOf }.flatMap { type ->
                    if (type is ZigContainerType && type.element.isTaggedUnion) {
                        val fields = type.members.filterIsInstance<ZigContainerField>()
                        switchItemElements
                            .asSequence()
                            .flatMap { resolveValues(it) }
                            .filterIsInstance<ZigEnumLiteralValue>()
                            .mapNotNull { value -> fields.firstOrNull { it.name == value.name } }
                            .flatMap { resolveValuesFromReferenceTo(it) }
                    } else {
                        sequenceOf(ZigUnknown.of(type))
                    }
                }
            }
            1 -> sequenceOf(ZigUnknown.ofUnknownType()) // TODO
            else -> sequenceOf(ZigUnknown.ofUnknownType())
        }

        is ZigForPayload -> {
            val payloadIndex = payloadElement.items.indexOf(payloadItem)
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
                        ZigPointerSize.ONE -> when (val pointerChild = typeOfInput.child) {
                            is ZigArrayType -> ZigUnknown.of(pointerChild.child)
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

private fun computeFieldTypeValues(
    containerTypes: Sequence<ZigValue>,
    fieldNameValues: Sequence<ZigValue>,
): Sequence<ZigValue> {
    return containerTypes.zip(fieldNameValues).flatMap { (container, fieldName) ->
        if (container is IZigContainerType
            && (container.containerKind == ZigContainerKind.STRUCT
                    || container.containerKind == ZigContainerKind.UNION)
            && fieldName is ZigNullTerminatedStringValue
        ) {
            val fields = container.members.filterIsInstance<ZigContainerField>()
            val field = fields.firstOrNull { it.name == fieldName.value }
            if (field != null) {
                resolveValuesFromReferenceTo(field).asSequence().map { it.typeOf }
            } else {
                sequenceOf(ZigLazyFieldType(container, fieldName))
            }
        } else {
            sequenceOf(ZigLazyFieldType(container, fieldName))
        }
    }
}

private fun computeFieldValues(
    leftValues: Sequence<ZigValue>,
    fieldNameValues: Sequence<ZigValue>,
): Sequence<ZigValue> {
    return leftValues.zip(fieldNameValues).flatMap { (left, fieldName) ->
        if (fieldName is ZigNullTerminatedStringValue) {
            computeFieldAccess(left, fieldName)
        } else {
            sequenceOf(ZigLazyFieldValue(left, fieldName))
        }
    }
}

fun computeTagNameValues(values: Sequence<ZigValue>): Sequence<ZigValue> {
    return values.map {
        when (it) {
            is ZigEnumLiteralValue -> ZigNullTerminatedStringValue(it.name)
            else -> ZigLazyTagNameValue(it)
        }
    }
}

/** Gets `Foo` in `@typeInfo(Foo).@"union".tag_type.?` */
private fun getUnwrapUnionTagTypeArgument(element: ZigUnwrapOptional): ZigElement? {
    var child = element.operand
    if (child !is ZigFieldAccess) return null
    if (child.identifierElement?.text != "tag_type") return null
    child = child.leftOperand
    if (child !is ZigFieldAccess) return null
    if (child.identifierElement?.text != "@\"union\"") return null
    child = child.leftOperand
    if (child !is ZigBuiltinCall) return null
    if (child.builtinElement.text != "@typeInfo") return null
    return child.argumentElements.firstOrNull()?.innerElement
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
