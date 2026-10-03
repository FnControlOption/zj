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

object ZigAnalyzer {
    fun resolveValues(element: ZigExpression): Set<ZigValue> {
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

    fun inferResultTypes(childElement: ZigExpression): Sequence<ZigValue> {
        return io.github.fncontroloption.zig.inferResultTypes(childElement)
    }

    fun resolveOrInferStructInitTypes(element: ZigListInit): Sequence<IZigContainerType> {
        return resolveOrInferListInitTypes(element).filterIsInstance<IZigContainerType>()
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
        val value = ZigUnknown.of(type)
        val place = ZigPlace(null, value)
        return getBuiltinFieldPlace(place, name)?.value?.typeOf
    }

    fun getBuiltinFieldPlace(place: ZigPlace, name: String): ZigPlace? {
        var mutability = place.mutability
        var type = place.value.typeOf
        if (type is ZigPointerType && type.size == ZigPointerSize.ONE) {
            mutability = type.mutability
            type = type.child
        }
        return when (type) {
            is ZigPointerType -> when (type.size) {
                ZigPointerSize.ONE,
                ZigPointerSize.MANY,
                ZigPointerSize.C -> null

                ZigPointerSize.SLICE -> when (name) {
                    "len" -> ZigPlace(mutability, ZigUnknown.of(ZigUsizeType))
                    "ptr" -> ZigPlace(mutability, ZigUnknown.of(ZigPointerType(ZigPointerSize.MANY, type.sentinel, type.mutability, type.child)))
                    else -> null
                }
            }

            is ZigArrayType -> when (name) {
                "len" -> ZigPlace.constant(type.length)
                else -> null
            }

            is ZigTupleType -> when (name) {
                "len" -> ZigPlace.constant(ZigIntValue(ZigUsizeType, type.fields.size))
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
            is ZigContainerType -> {
                val tagElement = type.element.containerArgElement ?: return emptySequence()
                val tagExpression = tagElement.expression ?: return emptySequence()
                resolveValues(tagExpression).asSequence()
            }
            is ZigInferredUnionTagType -> {
                val tagElement = type.union.element.containerArgElement ?: return emptySequence()
                if (tagElement.isInferredEnum) {
                    val tagExpression = tagElement.expression ?: return emptySequence()
                    return resolveValues(tagExpression).asSequence()
                }
                emptySequence()
            }
            is ZigFieldEnumType -> emptySequence()
            is ZigTupleType -> emptySequence()
        }
    }

    fun resolveUnionTagTypes(type: IZigContainerType): Sequence<ZigValue> {
        if (type !is ZigContainerType) {
            return emptySequence()
        }
        if (type.element.containerKind != ZigContainerKind.UNION) {
            return emptySequence()
        }
        val tagElement = type.element.containerArgElement ?: return emptySequence()
        if (tagElement.isInferredEnum) {
            return sequenceOf(ZigInferredUnionTagType(type))
        }
        val tagExpression = tagElement.expression ?: return emptySequence()
        return resolveValues(tagExpression).asSequence()
    }

    fun createGenericParameterValues(param: ZigFnParam): Sequence<ZigGenericParameterValue> {
        if (param.isAnytype) {
            return sequenceOf(ZigAnytypeParameterValue(param))
        }
        val typeElement = param.typeElement?.expression ?: return emptySequence()
        val types = resolveValues(typeElement)
        val isComptime = param.isComptime || types.singleOrNull() == ZigTypeType
        if (isComptime) {
            return types.asSequence().map { ZigComptimeParameterValue(it, param) }
        }
        return emptySequence()
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

    fun findBuiltinFile(originalElement: PsiElement): ZigFile? {
        val project = originalElement.project
        val virtualFile = ZigBuiltinService.getBuiltinFile(project) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
        return psiFile as? ZigFile
    }
}

data class ZigPlace(val mutability: ZigMutability?, val value: ZigValue) {
    companion object {
        fun constant(value: ZigValue) = ZigPlace(ZigMutability.CONST, value)
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
                is ZigErrorSetType if type.names.size == 1 -> ZigErrorSetValue(type, type.names.first())
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
            HtmlChunk.text(renderZigIdentifier(name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
        )
        else -> HtmlChunk.fragment(
            createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@as"),
            HtmlChunk.text("("),
            typeOf.toHtmlChunk(truncate),
            HtmlChunk.text(", "),
            HtmlChunk.text("."),
            createStyledHtmlChunk(
                ZigHighlightingColors.INSTANCE_FIELD,
                renderZigIdentifier(name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE),
            ),
            HtmlChunk.text(")")
        )
    }
}

sealed interface IZigContainerType : ZigValue {
    override val typeOf: ZigTypeType
    val typeParams: Map<ZigFnParam, Set<ZigValue>>
    val containerKind: ZigContainerKind
    val isTaggedUnion: Boolean
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

    override val isTaggedUnion: Boolean
        get() = element.isTaggedUnion

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
            if (param.isAnytype) {
                builder.append(createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "anytype"))
            } else {
                builder.append(createTypeElementHtmlChunk(param.typeElement, typeParams, truncate))
            }
        }
        builder.append(") ")
        if (element.bangTokenElement != null) {
            builder.append("!")
        }
        builder.append(createTypeElementHtmlChunk(element.returnTypeElement, typeParams, truncate))
        return builder.toFragment()
    }
}

sealed interface ZigGenericParameterValue : ZigValue {
    val element: ZigFnParam
}

data class ZigComptimeParameterValue(
    override val typeOf: ZigValue,
    override val element: ZigFnParam,
) : ZigGenericParameterValue {
    override fun toHtmlChunk(truncate: Boolean) =
        createNamedHtmlChunk(
            element,
            ZigHighlightingColors.COMPTIME_PARAMETER,
            ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING,
        )
}

data class ZigAnytypeParameterValue(override val element: ZigFnParam) : ZigGenericParameterValue {
    override val typeOf = ZigAnytypeParameterType(this)

    override fun toHtmlChunk(truncate: Boolean) =
        createNamedHtmlChunk(
            element,
            ZigHighlightingColors.PARAMETER,
            ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING,
        )
}

data class ZigAnytypeParameterType(val value: ZigAnytypeParameterValue) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        builder.append(createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@TypeOf"))
        builder.append("(")
        builder.append(value.toHtmlChunk(truncate))
        builder.append(")")
        return builder.toFragment()
    }
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
                builder.append(renderZigIdentifier(name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
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
            HtmlChunk.text(renderZigIdentifier(name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
        )
        else -> HtmlChunk.fragment(
            createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@as"),
            HtmlChunk.text("("),
            typeOf.toHtmlChunk(truncate),
            HtmlChunk.text(", "),
            createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "error"),
            HtmlChunk.text("."),
            HtmlChunk.text(renderZigIdentifier(name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)),
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
    // TODO: include other modifiers (volatile, allowzero, addrspace, align)
    val mutability: ZigMutability?,
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
        if (mutability == ZigMutability.CONST) {
            builder.append(createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "const"))
            builder.append(" ")
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

data class ZigVectorType(val length: ZigValue, val child: ZigValue) : ZigValue {
    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        builder.append(createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@Vector"))
        builder.append("(")
        builder.append(length.toHtmlChunk(truncate))
        builder.append(", ")
        builder.append(child.toHtmlChunk(truncate))
        builder.append(")")
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

    override val isTaggedUnion: Boolean
        get() = false

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

    override val isTaggedUnion: Boolean
        get() = false

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

    override val isTaggedUnion: Boolean
        get() = false

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

data class ZigLazyFieldValue(val left: ZigPlace, val fieldName: ZigValue) : ZigValue {
    override val typeOf = ZigUnknown.of(ZigTypeType)

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        if (fieldName is ZigStringLiteralValue) {
            builder.append(left.value.toHtmlChunk(truncate))
            builder.append(".")
            builder.append(renderZigIdentifier(fieldName.value, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
        } else {
            builder.append(createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@field"))
            builder.append("(")
            builder.append(left.value.toHtmlChunk(truncate))
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
        ZigMutability.CONST,
        ZigArrayType(
            ZigUnknown.of(ZigUsizeType),
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

data class ZigStringLiteralValue(val value: String) : ZigValue {
    override val typeOf = ZigPointerType(
        ZigPointerSize.ONE,
        null,
        ZigMutability.CONST,
        ZigArrayType(
            ZigIntValue(ZigUsizeType, value.length),
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

private fun resolvePlaces(element: ZigExpression): Set<ZigPlace> {
    return CachedValuesManager.getCachedValue(element) {
        RecursionManager.doPreventingRecursion(element, true) {
            val places = computePlacesUncached(element).toSet()
            CachedValueProvider.Result.create(
                places,
                PsiModificationTracker.MODIFICATION_COUNT,
                ZigBuiltinService.getModificationTracker(element.project),
                ZigBuiltinFunctionsService.getModificationTracker(element.project),
            )
        }
    } ?: emptySet()
}

private fun computePlacesUncached(element: ZigExpression): Sequence<ZigPlace> {
    return when (element) {
        is ZigIdentifier -> {
            val text = element.text
            val primitiveValue = toPrimitive(text)
            if (primitiveValue != null) {
                return sequenceOf(ZigPlace.constant(primitiveValue))
            }
            resolveReferences(element).flatMap { target ->
                val values = when (target) {
                    is ZigReferenceable -> resolveValuesFromReferenceTo(target).asSequence()
                    else -> emptySequence()
                }
                when (target) {
                    is ZigVarDeclOrProto -> values.map { ZigPlace(target.mutability, it) }
                    else -> values.map { ZigPlace.constant(it) }
                }
            }
        }

        is ZigFieldAccess -> {
            val name = parseZigIdentifier(element.identifierElement) ?: return emptySequence()
            val fieldName = ZigStringLiteralValue(name)
            resolvePlaces(element.leftOperand).asSequence().flatMap { computeFieldAccess(it, fieldName) }
        }

        is ZigArrayAccess -> {
            val indexElement = element.indexElement?.expression
            resolvePlaces(element.leftOperand).asSequence().flatMap { left ->
                when (val typeOfLeft = left.value.typeOf) {
                    is ZigArrayType -> sequenceOf(ZigPlace(left.mutability, ZigUnknown.of(typeOfLeft.child)))
                    is ZigVectorType -> sequenceOf(ZigPlace(left.mutability, ZigUnknown.of(typeOfLeft.child)))
                    is ZigTupleType -> computeArrayAccessOnTuple(typeOfLeft, indexElement).map { ZigPlace(left.mutability, it) }
                    is ZigPointerType -> when (typeOfLeft.size) {
                        ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                            is ZigArrayType -> sequenceOf(ZigPlace(typeOfLeft.mutability, ZigUnknown.of(pointerChild.child)))
                            is ZigVectorType -> sequenceOf(ZigPlace(typeOfLeft.mutability, ZigUnknown.of(pointerChild.child)))
                            is ZigTupleType -> computeArrayAccessOnTuple(pointerChild, indexElement).map { ZigPlace(typeOfLeft.mutability, it) }
                            else -> emptySequence()
                        }
                        else -> sequenceOf(ZigPlace(typeOfLeft.mutability, ZigUnknown.of(typeOfLeft.child)))
                    }
                    else -> emptySequence()
                }
            }
        }

        is ZigGroupedExpression -> {
            val innerExpression = element.innerExpression ?: return emptySequence()
            resolvePlaces(innerExpression).asSequence()
        }

        is ZigUnwrapOptional -> {
            val unionElement = getUnwrapUnionTagTypeArgument(element)
            if (unionElement != null) {
                return resolveValues(unionElement)
                    .asSequence()
                    .filterIsInstance<ZigContainerType>()
                    .flatMap { ZigAnalyzer.resolveUnionTagTypes(it) }
                    .map { ZigPlace.constant(it) }
            }
            val operandElement = element.operand ?: return emptySequence()
            resolvePlaces(operandElement).asSequence().mapNotNull { operand ->
                when (val typeOfOperand = operand.value.typeOf) {
                    is ZigOptionalType -> ZigPlace(operand.mutability, ZigUnknown.of(typeOfOperand.child))
                    else -> null
                }
            }
        }

        is ZigTry -> {
            val operandElement = element.operand ?: return emptySequence()
            resolvePlaces(operandElement).asSequence().mapNotNull { operand ->
                when (val typeOfOperand = operand.value.typeOf) {
                    is ZigErrorUnionType -> ZigPlace(operand.mutability, ZigUnknown.of(typeOfOperand.payload))
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
                        ZigPointerSize.C -> ZigPlace(typeOfOperand.mutability, ZigUnknown.of(typeOfOperand.child))
                        ZigPointerSize.SLICE,
                        ZigPointerSize.MANY -> null
                    }
                    else -> null
                }
            }
        }

        is ZigBuiltinCall if element.builtinElement.text == "@field" -> {
            val arguments = element.arguments.toList()
            if (arguments.size < 2) {
                return emptySequence()
            }
            val (leftElement, fieldNameElement) = arguments
            val leftPlaces = resolvePlaces(leftElement).asSequence()
            val fieldNameValues = resolveValues(fieldNameElement).asSequence()
            computeFieldPlaces(leftPlaces, fieldNameValues)
        }

        else -> computeValuesUncached(element).map { ZigPlace.constant(it) }
    }
}

private fun resolveValues(element: ZigExpression): Set<ZigValue> {
    return CachedValuesManager.getCachedValue(element) {
        RecursionManager.doPreventingRecursion(element, true) {
            val values = computeValuesUncached(element).toSet()
            CachedValueProvider.Result.create(
                values,
                PsiModificationTracker.MODIFICATION_COUNT,
                ZigBuiltinService.getModificationTracker(element.project),
                ZigBuiltinFunctionsService.getModificationTracker(element.project),
            )
        }
    } ?: emptySet()
}

private fun computeValuesUncached(element: ZigExpression): Sequence<ZigValue> {
    return when (element) {
        is ZigIdentifier,
        is ZigFieldAccess,
        is ZigArrayAccess,
        is ZigGroupedExpression,
        is ZigUnwrapOptional,
        is ZigTry,
        is ZigDeref,
            -> resolvePlaces(element).asSequence().map { it.value }

        is ZigBuiltinCall if element.builtinElement.text == "@field"
            -> resolvePlaces(element).asSequence().map { it.value }

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

        is ZigOptionalTypeExpression -> {
            val childElement = element.childExpression
                ?: return sequenceOf(ZigOptionalType(ZigUnknown.of(ZigTypeType)))
            resolveValues(childElement).asSequence().map { ZigOptionalType(it) }
        }

        is ZigErrorSet -> {
            val names = sortedSetOf<String>()
            for (item in element.setItems) {
                val name = parseZigIdentifier(item.identifierElement)
                    ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
                names.add(name)
            }
            sequenceOf(ZigErrorSetType(names))
        }

        is ZigErrorValueExpression -> {
            val name = parseZigIdentifier(element.identifierElement)
                ?: return sequenceOf(ZigUnknown.ofUnknownType())
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

        is ZigArrayTypeExpression -> {
            val length = resolveValues(element.lengthElement.expression).singleOrNull() ?: ZigUnknown.of(ZigUsizeType)
            val sentinel = element.sentinelElement?.let { outerElement ->
                outerElement.expression?.let { resolveValues(it) }?.singleOrNull() ?: ZigUnknown.ofUnknownType()
            }
            val childElement = element.childExpression
                ?: return sequenceOf(ZigArrayType(length, sentinel, ZigUnknown.of(ZigTypeType)))
            resolveValues(childElement).asSequence().map { ZigArrayType(length, sentinel, it) }
        }

        is ZigPointerTypeExpression -> {
            val size = element.pointerSize
            val sentinel = element.sentinelElement?.let { outerElement ->
                outerElement.expression?.let { resolveValues(it) }?.singleOrNull() ?: ZigUnknown.ofUnknownType()
            }
            val mutability = element.mutability
            val childElement = element.childExpression
                ?: return sequenceOf(ZigPointerType(size, sentinel, mutability, ZigUnknown.of(ZigTypeType)))
            resolveValues(childElement).asSequence().map { ZigPointerType(size, sentinel, mutability, it) }
        }

        is ZigComptime -> {
            val innerExpression = element.innerExpression ?: return sequenceOf(ZigUnknown.ofUnknownType())
            resolveValues(innerExpression).asSequence()
        }

        is ZigAddressOf -> {
            val operandElement = element.operand ?: return sequenceOf(ZigUnknown.ofUnknownType())
            resolvePlaces(operandElement).asSequence().map {
                ZigUnknown.of(ZigPointerType(ZigPointerSize.ONE, null, it.mutability, it.value.typeOf))
            }
        }

        is ZigIf -> {
            val thenValues = when (val thenElement = element.thenElement.expression) {
                null -> sequenceOf(ZigUnknown.ofUnknownType())
                else -> resolveValues(thenElement).asSequence()
            }
            val elseValues = when (val outerElement = element.elseElement) {
                null -> sequenceOf(ZigVoidValue)
                else -> when (val expression = outerElement.expression) {
                    null -> sequenceOf(ZigUnknown.ofUnknownType())
                    else -> resolveValues(expression).asSequence()
                }
            }
            val values = thenValues + elseValues
            resolvePeerValues(values.toList()).asSequence()
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
            val values = leftValues + rightValues
            resolvePeerValues(values.toList()).asSequence()
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
            val values = leftValues + rightValues
            resolvePeerValues(values.toList()).asSequence()
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

        is ZigSlice -> {
            // TODO: if the indexes are non-comptime, return a slice instead of an array of unknown length
            val length = ZigUnknown.of(ZigUsizeType)
            var sentinel: ZigValue? = null
            val sentinelElement = element.sentinelElement?.expression
            if (sentinelElement != null) {
                sentinel = resolveValues(sentinelElement).singleOrNull() ?: ZigUnknown.ofUnknownType()
            }
            resolvePlaces(element.sliced).asSequence().mapNotNull { sliced ->
                val (mutability, childType) = when (val typeOfSliced = sliced.value.typeOf) {
                    is ZigArrayType -> Pair(sliced.mutability, typeOfSliced.child)

                    is ZigPointerType -> when (typeOfSliced.size) {
                        ZigPointerSize.ONE -> when (val pointerChild = typeOfSliced.child) {
                            is ZigArrayType -> Pair(typeOfSliced.mutability, pointerChild.child)
                            else -> return@mapNotNull null // TODO
                        }

                        ZigPointerSize.MANY,
                        ZigPointerSize.SLICE,
                        ZigPointerSize.C -> Pair(typeOfSliced.mutability, typeOfSliced.child)
                    }

                    else -> return@mapNotNull null
                }
                val arrayType = ZigArrayType(length, sentinel, childType)
                val pointerType = ZigPointerType(ZigPointerSize.ONE, null, mutability, arrayType)
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

        is ZigBuiltinCall -> when (val builtinName = element.builtinElement.text) {
            "@constCast" -> {
                val argElement = element.argumentElements.firstOrNull()?.expression
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                resolveValues(argElement).asSequence().map { value ->
                    when (val type = value.typeOf) {
                        is ZigPointerType -> {
                            val type = ZigPointerType(type.size, type.sentinel, ZigMutability.VAR, type.child)
                            ZigUnknown.of(type)
                        }
                        else -> ZigUnknown.ofUnknownType()
                    }
                }
            }

            "@EnumLiteral" -> sequenceOf(ZigEnumLiteralType)

            "@FieldType" -> {
                val arguments = element.arguments.toList()
                if (arguments.size < 2) {
                    return sequenceOf(ZigUnknown.ofUnknownType())
                }
                val (containerElement, fieldNameElement) = arguments
                val containerTypes = resolveValues(containerElement).asSequence()
                val fieldNameValues = resolveValues(fieldNameElement).asSequence()
                computeFieldTypeValues(containerTypes, fieldNameValues)
            }

            "@import" -> {
                val argElement = element.argumentElements.firstOrNull()?.expression
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
                val argElement = element.argumentElements.firstOrNull()?.expression
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                computeTagNameValues(resolveValues(argElement).asSequence())
            }

            "@This" -> {
                val containerElement = element.findParentOfType<ZigContainer>()
                    ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
                val typeParams = findUnboundTypeParams(containerElement)
                sequenceOf(ZigContainerType(containerElement, typeParams))
            }

            "@TypeOf" -> {
                val argElements = element.argumentElements.map { it.expression }.toList()
                if (argElements.size == 1) {
                    resolveValues(argElements[0]).asSequence().map { it.typeOf }
                } else {
                    val values = argElements.flatMap { resolveValues(it) }
                    resolvePeerValues(values.toList()).asSequence().map { it.typeOf }
                }
            }

            "@Vector" -> {
                val arguments = element.arguments.toList()
                if (arguments.size < 2) {
                    return sequenceOf(ZigUnknown.ofUnknownType())
                }
                val (lengthExpression, childExpression) = arguments
                val length = resolveValues(lengthExpression).singleOrNull()
                    ?: ZigUnknown.of(ZigComptimeIntType)
                resolveValues(childExpression).asSequence().map { ZigVectorType(length, it) }
            }

            else -> {
                val builtin = ZigBuiltinFunctionsService.getBuiltinFnProto(element.project, builtinName)
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                val typeElement = builtin.returnTypeElement?.expression
                    ?: return sequenceOf(ZigUnknown.ofUnknownType())
                val arguments = element.argumentElements.map { resolveValues(it.expression) }
                val typeParams = computeBuiltinFnGenericParams(builtin, arguments)
                resolveValues(typeElement)
                    .asSequence()
                    .flatMap { substituteTypeParams(it, typeParams) }
                    .map { ZigUnknown.of(it) }
            }
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
            val values = computeValuesFromLabeledBreaks(element).toList()
            resolvePeerValues(values).asSequence().ifEmpty { sequenceOf(ZigVoidValue) }
        }

        is ZigSwitch -> {
            var values = computeValuesFromLabeledBreaks(element)
            values += element.prongs.flatMap { prongElement ->
                prongElement.expression?.let { resolveValues(it).asSequence() }
                    ?: sequenceOf(ZigUnknown.ofUnknownType())
            }
            resolvePeerValues(values.toList()).asSequence().ifEmpty { sequenceOf(ZigUnknown.ofUnknownType()) }
        }

        is ZigFor,
        is ZigWhile -> {
            var values = computeValuesFromLabeledBreaks(element)
            values += computeValuesFromUnlabeledBreaks(element)
            val elseElement = element.elseElement?.expression
            if (elseElement != null) {
                values += resolveValues(elseElement).asSequence()
            } else {
                values += sequenceOf(ZigVoidValue)
            }
            resolvePeerValues(values.toList()).asSequence()
        }

        is ZigStringLiteral, is ZigMultilineStringLiteral -> {
            val value = when (element) {
                is ZigStringLiteral -> ZigStringLiteralParser.parse(element.text)
                is ZigMultilineStringLiteral -> null
            }
            if (value != null) {
                return sequenceOf(ZigStringLiteralValue(value))
            }
            val length = ZigUnknown.of(ZigUsizeType)
            val u8 = ZigFixedIntType(ZigSignedness.UNSIGNED, 8)
            val array = ZigArrayType(length, ZigIntValue(ZigComptimeIntType, 0), u8)
            val pointer = ZigPointerType(ZigPointerSize.ONE, null, ZigMutability.CONST, array)
            sequenceOf(ZigUnknown.of(pointer))
        }

        is ZigNumberLiteral -> {
            when (val result = ZigNumberLiteralParser.parse(element.text)) {
                is ZigNumberLiteralParser.IntResult -> sequenceOf(ZigIntValue(ZigComptimeIntType, result.value))
                is ZigNumberLiteralParser.BigIntResult -> sequenceOf(ZigUnknown.of(ZigComptimeIntType))
                is ZigNumberLiteralParser.FloatResult -> sequenceOf(ZigUnknown.of(ZigComptimeFloatType))
                ZigNumberLiteralParser.FailureResult -> sequenceOf(ZigUnknown.ofUnknownType())
            }
        }

        is ZigCharLiteral -> sequenceOf(ZigUnknown.of(ZigComptimeIntType))

        is ZigEnumLiteral -> {
            val name = parseZigIdentifier(element.identifierElement)
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
    }
}

private fun toPrimitive(text: String): ZigValue? {
    val primitiveValue = PRIMITIVE_VALUES[text]
    if (primitiveValue != null) {
        return primitiveValue
    }
    val intType = toFixedIntType(text)
    if (intType != null) {
        return intType
    }
    return null
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
    var bits = 0
    for (c in text.substring(1)) {
        bits *= 10
        when (c) {
            in '0'..'9' -> bits += c - '0'
            else -> return null
        }
    }
    return ZigFixedIntType(signedness, bits)
}

private fun computeFieldAccess(
    left: ZigPlace,
    fieldName: ZigStringLiteralValue,
): Sequence<ZigPlace> {
    val name = fieldName.value
    val builtinPlace = ZigAnalyzer.getBuiltinFieldPlace(left, name)
    if (builtinPlace != null) {
        return sequenceOf(builtinPlace)
    }
    return when (val leftValue = left.value) {
        is ZigErrorSetType -> {
            if (name in leftValue.names) {
                sequenceOf(ZigPlace.constant(ZigErrorSetValue(leftValue, name)))
            } else {
                emptySequence()
            }
        }
        is IZigContainerType -> computeContainerTypeDeclAccess(leftValue, fieldName)
        else -> when (val typeOfLeft = leftValue.typeOf) {
            is IZigContainerType ->  computeContainerInstanceFieldAccess(left.mutability, typeOfLeft, fieldName)
            is ZigPointerType -> when (typeOfLeft.size) {
                ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                    is IZigContainerType ->  computeContainerInstanceFieldAccess(typeOfLeft.mutability, pointerChild, fieldName)
                    else ->  sequenceOf(ZigPlace(null, ZigLazyFieldValue(left, fieldName)))
                }
                else ->  emptySequence()
            }
            else ->  sequenceOf(ZigPlace(null, ZigLazyFieldValue(left, fieldName)))
        }
    }
}

private fun computeArrayAccessOnTuple(type: ZigTupleType, indexElement: ZigExpression?): Sequence<ZigValue> {
    if (indexElement == null) {
        return emptySequence()
    }
    return resolveValues(indexElement)
        .asSequence()
        .filterIsInstance<ZigIntValue>()
        .mapNotNull { type.fields.getOrNull(it.value) }
        .map { ZigUnknown.of(it) }
}

private fun findUnboundTypeParams(element: ZigElement): Map<ZigFnParam, Set<ZigValue>> {
    return buildMap {
        var element: PsiElement? = element
        while (element != null) {
            if (element is ZigFnDeclOrProto) {
                for (paramDecl in element.parameters) {
                    if (ZigAnalyzer.createGenericParameterValues(paramDecl).any()) {
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
            if (ZigAnalyzer.createGenericParameterValues(paramDecl).any()) {
                this[paramDecl] = argValues
            }
        }
    }
}

private fun computeBuiltinFnGenericParams(
    element: ZigBuiltinFnProto,
    arguments: Sequence<Set<ZigValue>>,
): Map<ZigFnParam, Set<ZigValue>> {
    return buildMap {
        for ((paramDecl, argValues) in element.parameters.zip(arguments)) {
            if (ZigAnalyzer.createGenericParameterValues(paramDecl).any()) {
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
            is ZigGenericParameterValue -> {
                val values = typeParams[value.element]
                if (values.isNullOrEmpty()) {
                    sequenceOf(value)
                } else {
                    values.asSequence().flatMap { substituteTypeParams(it, typeParams) }
                }
            }
            is ZigAnytypeParameterType -> {
                substituteTypeParams(value.value, typeParams).map { it.typeOf }
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
                val length = lengthValues.singleOrNull() ?: ZigUnknown.of(ZigUsizeType)
                val sentinelValues = value.sentinel?.let { substituteTypeParams(it, typeParams) }
                val sentinel = sentinelValues?.singleOrNull()
                val childTypes = substituteTypeParams(value.child, typeParams)
                childTypes.map { ZigArrayType(length, sentinel, it) }
            }
            is ZigVectorType -> {
                val lengthValues = substituteTypeParams(value.length, typeParams)
                val length = lengthValues.singleOrNull() ?: ZigUnknown.of(ZigComptimeIntType)
                val childTypes = substituteTypeParams(value.child, typeParams)
                childTypes.map { ZigVectorType(length, it) }
            }
            is ZigPointerType -> {
                val sentinelValues = value.sentinel?.let { substituteTypeParams(it, typeParams) }
                val sentinel = sentinelValues?.singleOrNull()
                val childTypes = substituteTypeParams(value.child, typeParams)
                childTypes.map { ZigPointerType(value.size, sentinel, value.mutability, it) }
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
                val leftValues = substituteTypeParams(value.left.value, typeParams)
                val leftPlaces = leftValues.map { ZigPlace(value.left.mutability, it) }
                val fieldNameValues = substituteTypeParams(value.fieldName, typeParams)
                computeFieldPlaces(leftPlaces, fieldNameValues).map { it.value }
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
            is ZigStringLiteralValue,
                -> sequenceOf(value)
        }
    } ?: sequenceOf(value)
}

private fun computeContainerInstanceFieldAccess(
    mutability: ZigMutability?,
    type: IZigContainerType,
    fieldName: ZigStringLiteralValue,
): Sequence<ZigPlace> {
    val name = fieldName.value
    if (type.containerKind == ZigContainerKind.ENUM) {
        return emptySequence()
    }
    val member = type.members.firstNotNullOfOrNull {
        if (it is ZigContainerField && it.name == name) it else null
    }
    if (member == null) {
        return emptySequence()
    }
    var values = resolveValuesFromReferenceTo(member).asSequence()
    if (type.typeParams.isNotEmpty()) {
        values = values.flatMap { substituteTypeParams(it, type.typeParams) }
    }
    return values.map { ZigPlace(mutability, it) }
}

private fun computeContainerTypeDeclAccess(
    type: IZigContainerType,
    fieldName: ZigStringLiteralValue,
): Sequence<ZigPlace> {
    val name = fieldName.value
    val member = type.members.firstNotNullOfOrNull {
        if (it is ZigNamedContainerMember && it.name == name) it else null
    }
    if (member == null) {
        return emptySequence()
    }
    if (member is ZigContainerField) {
        return if (type.containerKind == ZigContainerKind.ENUM) {
            sequenceOf(ZigPlace.constant(ZigEnumLiteralValue(type, name)))
        } else if (type.isTaggedUnion) {
            ZigAnalyzer.resolveUnionTagTypes(type).map { ZigPlace.constant(ZigEnumLiteralValue(it, name)) }
        } else {
            emptySequence()
        }
    }
    var values = resolveValuesFromReferenceTo(member).asSequence()
    if (type.typeParams.isNotEmpty()) {
        values = values.flatMap { substituteTypeParams(it, type.typeParams) }
    }
    return when (member) {
        is ZigVarDecl -> values.map { ZigPlace(member.mutability, it) }
        else -> values.map { ZigPlace.constant(it) }
    }
}

private fun resolveValuesFromReferenceTo(target: ZigReferenceable): Set<ZigValue> {
    return CachedValuesManager.getCachedValue(target) {
        RecursionManager.doPreventingRecursion(target, true) {
            val values = computeValuesFromReferenceUncached(target).toSet()
            CachedValueProvider.Result.create(
                values,
                PsiModificationTracker.MODIFICATION_COUNT,
                ZigBuiltinService.getModificationTracker(target.project),
                ZigBuiltinFunctionsService.getModificationTracker(target.project),
            )
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
            val typeElement = target.typeElement?.expression
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
            val typeElement = target.typeElement?.expression
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

                    is ZigVectorType -> ZigUnknown.of(typeOfInit.child)

                    else -> ZigUnknown.ofUnknownType()
                }
            }
        }

        is ZigFnParam -> {
            ZigAnalyzer.createGenericParameterValues(target).ifEmpty {
                val typeElement = target.typeElement?.expression
                    ?: return@ifEmpty sequenceOf(ZigUnknown.ofUnknownType())
                val types = resolveValues(typeElement)
                types.asSequence().map { ZigUnknown.of(it) }
            }
        }

        is ZigContainerFieldImpl -> {
            when (val typeElement = target.typeElement?.expression) {
                null -> when (target.container.containerKind) {
                    ZigContainerKind.STRUCT,
                    ZigContainerKind.OPAQUE -> sequenceOf(ZigUnknown.ofUnknownType())
                    ZigContainerKind.ENUM -> {
                        val typeParams = findUnboundTypeParams(target.container)
                        sequenceOf(ZigUnknown.of(ZigContainerType(target.container, typeParams)))
                    }
                    ZigContainerKind.UNION -> when (target.container.containerArgElement) {
                        null -> sequenceOf(ZigUnknown.ofUnknownType())
                        else -> sequenceOf(ZigVoidValue)
                    }
                }
                else -> resolveValues(typeElement).asSequence().map { ZigUnknown.of(it) }
            }
        }

        is ZigPayloadItem -> {
            val places = computePayloadItemPlaces(target)
            if (target.isPointer) {
                places.map { ZigUnknown.of(ZigPointerType(ZigPointerSize.ONE, null, it.mutability, it.value.typeOf)) }
            } else {
                places.map { it.value }
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
    val returnTypeElement = functionElement.returnTypeElement?.expression
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
                    when (val returnValue = descendant.returnOperand) {
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
            } else if (type.isTaggedUnion) {
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
    return when (val functionElement = callElement.functionExpression) {
        is ZigFieldAccess -> {
            val name = parseZigIdentifier(functionElement.identifierElement)
                ?: return sequenceOf(ZigUnknown.ofUnknownType() to null)
            val fieldName = ZigStringLiteralValue(name)
            resolveValues(functionElement.leftOperand).asSequence().flatMap { left ->
                when (left) {
                    is IZigContainerType -> {
                        computeContainerTypeDeclAccess(left, fieldName).map { it.value to null }
                    }
                    else -> when (val typeOfLeft = left.typeOf) {
                        is IZigContainerType -> {
                            computeContainerTypeDeclAccess(typeOfLeft, fieldName).map { it.value to left }
                        }
                        is ZigPointerType -> when (typeOfLeft.size) {
                            ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                                is IZigContainerType -> {
                                    computeContainerTypeDeclAccess(pointerChild, fieldName).map { it.value to left }
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
            val name = parseZigIdentifier(functionElement.identifierElement)
                ?: return sequenceOf(ZigUnknown.ofUnknownType() to null)
            ZigAnalyzer.inferEnumLiteralTypes(functionElement).flatMap { (_, type) ->
                val member = type.members.firstNotNullOfOrNull {
                    if (it is ZigNamedContainerDecl && it.name == name) it else null
                }
                if (member != null) {
                    resolveValuesFromReferenceTo(member)
                        .asSequence()
                        .flatMap { substituteTypeParams(it, type.typeParams) }
                        .map { it to null }
                } else {
                    sequenceOf(ZigUnknown.ofUnknownType() to null)
                }
            }
        }

        else -> resolveValues(functionElement).asSequence().map { it to null }
    }
}

private fun computeValuesFromLabeledBreaks(element: ZigLabelable): Sequence<ZigValue> {
    return getLabeledBreaksFor(element).flatMap { resolveBreakValue(it) }
}

private fun getLabeledBreaksFor(element: ZigLabelable): Sequence<ZigBreak> {
    val labelDecl = element.labelDecl ?: return emptySequence()
    val labelName = parseZigIdentifier(labelDecl.identifierElement) ?: return emptySequence()
    val traverser = SyntaxTraverser.psiTraverser(element).expand { it !is ZigContainerExpression }
    return traverser.asSequence().mapNotNull { descendant ->
        if (descendant is ZigBreak && labelName == parseZigIdentifier(descendant.labelRef?.identifierElement)) {
            descendant
        } else {
            null
        }
    }
}

private fun computeValuesFromUnlabeledBreaks(loop: ZigLoop): Sequence<ZigValue> {
    return getUnlabeledBreaksFor(loop).flatMap { resolveBreakValue(it) }
}

private fun getUnlabeledBreaksFor(loop: ZigLoop): Sequence<ZigBreak> {
    val traverser = SyntaxTraverser.psiTraverser(loop.thenElement).expand { descendant ->
        when (descendant) {
            is ZigContainerExpression -> false
            is ZigThenElement -> descendant.parent == loop || descendant.parent !is ZigLoop
            else -> true
        }
    }
    return traverser.asSequence().mapNotNull { descendant ->
        if (descendant is ZigBreak && descendant.labelRef == null) {
            descendant
        } else {
            null
        }
    }
}

private fun resolveBreakValue(element: ZigBreak): Set<ZigValue> {
    return when (val breakValue = element.breakOperand) {
        null -> setOf(ZigVoidValue)
        else -> resolveValues(breakValue)
    }
}

private fun inferResultTypes(childElement: ZigExpression): Sequence<ZigValue> {
    return when (val parentElement = childElement.parent) {
        is ZigVarDecl -> {
            if (parentElement.initializer !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val typeElement = parentElement.typeElement?.expression
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            resolveValues(typeElement).asSequence()
        }

        is ZigContainerFieldImpl -> {
            if (parentElement.initializer !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val typeElement = parentElement.typeElement?.expression
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            resolveValues(typeElement).asSequence()
        }

        is ZigAssign -> {
            if (parentElement.rightExpression !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            resolveValues(parentElement.leftExpression).asSequence().map { it.typeOf }
        }

        is ZigAssignDestructure -> {
            if (parentElement.initializer !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val fields = parentElement.variables.map { variable ->
                val types = when (variable) {
                    is ZigVarProto -> {
                        val typeElement = variable.typeElement?.expression
                            ?: return@map ZigUnknown.of(ZigTypeType)
                        resolveValues(typeElement)
                    }
                    is ZigExpression -> resolveValues(variable).asSequence().map { it.typeOf }.toSet()
                    else -> emptySet()
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
            val initElement = parentElement.containingListInit
            if (parentElement.isStructField) {
                val name = parseZigIdentifier(parentElement.identifierElement)
                    ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
                ZigAnalyzer.resolveOrInferStructInitTypes(initElement).flatMap { type ->
                    val member = type.members.firstNotNullOfOrNull {
                        if (it is ZigContainerField && it.name == name) it else null
                    }
                    if (member != null) {
                        resolveValuesFromReferenceTo(member)
                            .asSequence()
                            .flatMap { substituteTypeParams(it.typeOf, type.typeParams) }
                    } else {
                        sequenceOf(ZigUnknown.of(ZigTypeType))
                    }
                }
            } else {
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
            if (parentElement.expression !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val switchElement = parentElement.containingSwitchProng.containingSwitch
            val conditionElement = switchElement.conditionElement.expression
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            resolveValues(conditionElement).asSequence().flatMap {
                val type = it.typeOf
                if (type is IZigContainerType && type.isTaggedUnion) {
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
            val parentTypes = inferResultTypes(parentElement.containingSwitch)
            val otherTypes =
                parentElement.containingSwitch.prongs
                    .filter { it !== parentElement }
                    .mapNotNull { it.expression }
                    .flatMap { resolveValues(it) }
                    .map { it.typeOf }
            parentTypes + otherTypes
        }

        is ZigThenElement -> {
            if (parentElement.expression !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val parentTypes = inferResultTypes(parentElement.parent as ZigExpression)
            val grandparentElement = parentElement.parent
            if (grandparentElement is ZigIf) {
                val elseElement = grandparentElement.elseElement?.expression
                if (elseElement != null) {
                    val elseTypes = resolveValues(elseElement).asSequence().map { it.typeOf }
                    return parentTypes + elseTypes
                }
            }
            parentTypes
        }

        is ZigElseElement -> {
            if (parentElement.expression !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val parentTypes = inferResultTypes(parentElement.parent as ZigExpression)
            val grandparentElement = parentElement.parent
            if (grandparentElement is ZigIf) {
                val thenElement = grandparentElement.thenElement.expression
                if (thenElement != null) {
                    val thenTypes = resolveValues(thenElement).asSequence().map { it.typeOf }
                    return parentTypes + thenTypes
                }
            }
            parentTypes
        }

        is ZigOrelse -> {
            if (parentElement.rightOperand !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val parentTypes = inferResultTypes(parentElement)
            val leftTypes = resolveValues(parentElement.leftOperand).asSequence().map { left ->
                when (val typeOfLeft = left.typeOf) {
                    is ZigOptionalType -> typeOfLeft.child
                    else -> ZigUnknown.ofUnknownType()
                }
            }
            parentTypes + leftTypes
        }

        is ZigCatch -> {
            if (parentElement.rightOperand !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val parentTypes = inferResultTypes(parentElement)
            val leftTypes = resolveValues(parentElement.leftOperand).asSequence().map { left ->
                when (val typeOfLeft = left.typeOf) {
                    is ZigErrorUnionType -> typeOfLeft.payload
                    else -> ZigUnknown.ofUnknownType()
                }
            }
            parentTypes + leftTypes
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
                            val length = ZigUnknown.of(ZigUsizeType)
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
            val typeElement = fnDecl.returnTypeElement?.expression
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            if (fnDecl.bangTokenElement != null) {
                val errorSet = ZigUnknown.ofUnknownType()
                resolveValues(typeElement).asSequence().map { ZigErrorUnionType(errorSet, it) }
            } else {
                resolveValues(typeElement).asSequence()
            }
        }

        is ZigBreak -> {
            if (parentElement.breakOperand !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val labelElement = parentElement.labelRef?.identifierElement
            val breakable = if (labelElement != null) {
                val label = parseZigIdentifier(labelElement)
                    ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
                parentElement.parents(withSelf = false).firstNotNullOfOrNull {
                    when (it) {
                        is ZigLabelable if label == parseZigIdentifier(it.labelDecl?.identifierElement) -> it
                        else -> null
                    }
                }
            } else {
                var foundThenElemAsChild = false
                parentElement.parents(withSelf = false).firstNotNullOfOrNull { current ->
                    if (foundThenElemAsChild && current is ZigLoop) {
                        current
                    } else {
                        foundThenElemAsChild = current is ZigThenElement
                        null
                    }
                }
            }
            if (breakable == null) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val parentTypes = inferResultTypes(breakable)
            var breakElements = getLabeledBreaksFor(breakable)
            if (breakable is ZigLoop) {
                breakElements += getUnlabeledBreaksFor(breakable)
            }
            val otherTypes =
                breakElements
                    .filter { it !== parentElement }
                    .flatMap { resolveBreakValue(it) }
                    .map { it.typeOf }
            parentTypes + otherTypes
        }

        is ZigContinue -> {
            if (parentElement.continueOperand !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val label = parseZigIdentifier(parentElement.labelRef?.identifierElement)
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            val labelable = parentElement.findParentInFile {
                it is ZigLabelable && label == parseZigIdentifier(it.labelDecl?.identifierElement)
            }
            if (labelable !is ZigSwitch) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            val conditionElement = labelable.conditionElement.expression
                ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
            resolveValues(conditionElement).asSequence().map { it.typeOf }
        }

        is ZigComptime -> {
            if (parentElement.innerExpression !== childElement) {
                return sequenceOf(ZigUnknown.of(ZigTypeType))
            }
            inferResultTypes(parentElement)
        }

        is ZigCall -> {
            if (parentElement.functionExpression !== childElement) {
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
                    val typeElement = paramElement.typeElement?.expression
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
            val builtinName = builtinCallElement.builtinElement.text
            val argIndex = builtinCallElement.argumentElements.indexOf(parentElement)
            assert(argIndex >= 0)
            when (builtinName) {
                "@as" -> {
                    if (argIndex != 1) {
                        return sequenceOf(ZigUnknown.of(ZigTypeType))
                    }
                    val firstArgElement = builtinCallElement.argumentElements.firstOrNull()?.expression
                        ?: return sequenceOf(ZigUnknown.ofUnknownType())
                    resolveValues(firstArgElement).asSequence()
                }

                "@memset" -> {
                    if (argIndex != 1) {
                        return sequenceOf(ZigUnknown.of(ZigTypeType))
                    }
                    val firstArgElement = builtinCallElement.argumentElements.firstOrNull()?.expression
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

                else -> {
                    val builtin = ZigBuiltinFunctionsService.getBuiltinFnProto(childElement.project, builtinName)
                        ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
                    val typeElement = builtin.parameters.elementAtOrNull(argIndex)?.typeElement?.expression
                        ?: return sequenceOf(ZigUnknown.of(ZigTypeType))
                    val arguments = builtinCallElement.argumentElements.map { resolveValues(it.expression) }
                    val typeParams = computeBuiltinFnGenericParams(builtin, arguments)
                    resolveValues(typeElement)
                        .asSequence()
                        .flatMap { substituteTypeParams(it, typeParams) }
                }
            }
        }

        // TODO
        else -> sequenceOf(ZigUnknown.of(ZigTypeType))
    }
}

internal fun createStyledHtmlChunk(attributesKey: TextAttributesKey, value: String): HtmlChunk {
    val sb = StringBuilder()
    HtmlSyntaxInfoUtil.appendStyledSpan(sb, attributesKey, value, 1f)
    return HtmlChunk.raw(sb.toString())
}

private fun createNamedHtmlChunk(
    element: PsiNamedElement,
    attributesKey: TextAttributesKey,
    quote: ZigIdQuoteBehavior,
): HtmlChunk {
    val name = element.name
    if (name != null) {
        val target = buildString {
            append(DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL)
            append(element.containingFile.viewProvider.virtualFile.url)
            append(ZIG_DOCUMENTATION_OFFSET_SEPARATOR)
            append(element.textOffset)
        }
        return HtmlChunk.link(target, createStyledHtmlChunk(attributesKey, renderZigIdentifier(name, quote)))
    } else {
        return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
    }
}

private fun createTypeElementHtmlChunk(
    typeElement: ZigTypeElement?,
    typeParams: Map<ZigFnParam, Set<ZigValue>>,
    truncate: Boolean,
): HtmlChunk {
    val value = typeElement?.expression?.let {
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
    val quote = when (truncate) {
        true -> ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING
        false -> ZigIdQuoteBehavior.EAGERLY_UNQUOTE
    }
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
                    val parentChunk = createNamedHtmlChunk(parent, ZigHighlightingColors.CONSTANT, quote)
                    if (truncate) {
                        return parentChunk
                    }
                    val grandparent = parent.parent!!
                    val grandparentChunk = createContainerHtmlChunkStartingFrom(grandparent, typeParams, false)
                    HtmlChunk.fragment(grandparentChunk, HtmlChunk.text("."), parentChunk)
                }

                is ZigReturn if parent.returnOperand == element -> {
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
            createNamedHtmlChunk(element, attributesKey, quote)
        }
        is ZigFnDecl -> {
            createNamedHtmlChunk(element, ZigHighlightingColors.STATIC_METHOD, quote)
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
    for (comptimeParam in ZigAnalyzer.createGenericParameterValues(param)) {
        val comptimeArg = substituteTypeParams(comptimeParam, typeParams).singleOrNull()
        if (comptimeArg != null) {
            return comptimeArg.toHtmlChunk(truncate)
        }
    }
    return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
}

private fun computePayloadItemPlaces(payloadItem: ZigPayloadItem): Sequence<ZigPlace> {
    return when (val payloadElement = payloadItem.containingPayload) {
        is ZigThenPayload -> {
            val conditionElement =
                payloadElement.containingIf?.conditionElement?.expression
                    ?: payloadElement.containingWhile?.conditionElement?.expression
                    ?: return emptySequence()
            resolvePlaces(conditionElement).asSequence().mapNotNull { condition ->
                when (val typeOfCondition = condition.value.typeOf) {
                    is ZigOptionalType -> ZigPlace(condition.mutability, ZigUnknown.of(typeOfCondition.child))
                    is ZigErrorUnionType -> ZigPlace(condition.mutability, ZigUnknown.of(typeOfCondition.payload))
                    else -> null
                }
            }
        }

        is ZigElsePayload -> {
            val conditionElement =
                payloadElement.containingIf?.conditionElement?.expression
                    ?: payloadElement.containingWhile?.conditionElement?.expression
                    ?: return emptySequence()
            resolvePlaces(conditionElement).asSequence().mapNotNull { condition ->
                when (val typeOfCondition = condition.value.typeOf) {
                    is ZigErrorUnionType -> ZigPlace(condition.mutability, ZigUnknown.of(typeOfCondition.errorSet))
                    else -> null
                }
            }
        }

        is ZigCatchPayload -> {
            val leftElement = payloadElement.containingCatch.leftOperand
            resolvePlaces(leftElement).asSequence().mapNotNull { left ->
                when (val typeOfLeft = left.value.typeOf) {
                    is ZigErrorUnionType -> ZigPlace(left.mutability, ZigUnknown.of(typeOfLeft.errorSet))
                    else -> null
                }
            }
        }

        is ZigSwitchPayload -> when (payloadElement.items.indexOf(payloadItem)) {
            0 -> {
                val switchProng = payloadElement.containingSwitchProng
                val switchElement = switchProng.containingSwitch
                val conditionElement = switchElement.conditionElement.expression
                    ?: return emptySequence()

                val isElseProng = switchProng.isElse
                val isInlineProng = switchProng.isInline
                val otherSwitchItems =
                    switchElement.prongs
                        .filter { it !== switchProng }
                        .flatMap { it.items }
                        .flatMap { resolveValues(it.expression) }
                        .toSet()
                val prongSwitchItems =
                    switchProng.items
                        .flatMap { resolveValues(it.expression) }
                        .toSet()

                // Helper variables for error sets:
                val otherErrorNames =
                    otherSwitchItems
                        .asSequence()
                        .filterIsInstance<ZigErrorSetValue>()
                        .map { it.name }
                        .toSet()
                val prongErrorValues =
                    prongSwitchItems
                        .asSequence()
                        .filterIsInstance<ZigErrorSetValue>()
                        .toList()
                val prongErrorNames =
                    prongErrorValues
                        .asSequence()
                        .map { it.name }
                        .toSortedSet()
                val prongErrorSet = ZigErrorSetType(prongErrorNames)

                // Helper variables for tagged unions:
                val otherEnumLiterals =
                    otherSwitchItems
                        .asSequence()
                        .filterIsInstance<ZigEnumLiteralValue>()
                        .map { it.name }
                        .toSet()
                val prongEnumLiterals =
                    prongSwitchItems
                        .asSequence()
                        .filterIsInstance<ZigEnumLiteralValue>()
                        .map { it.name }
                        .toSet()

                resolvePlaces(conditionElement).asSequence().flatMap { condition ->
                    val values = when (val conditionType = condition.value.typeOf) {
                        is ZigErrorSetType -> {
                            if (isElseProng) {
                                val names = conditionType.names.toSortedSet()
                                names.removeAll(otherErrorNames)
                                if (isInlineProng) {
                                    names.asSequence().map { ZigErrorSetValue(ZigErrorSetType(setOf(it)), it) }
                                } else {
                                    sequenceOf(ZigUnknown.of(ZigErrorSetType(names)))
                                }
                            } else {
                                if (isInlineProng) {
                                    prongErrorValues.asSequence()
                                } else {
                                    sequenceOf(ZigUnknown.of(prongErrorSet))
                                }
                            }
                        }
                        is IZigContainerType if conditionType.isTaggedUnion -> {
                            if (isElseProng) {
                                if (isInlineProng) {
                                    conditionType.members
                                        .filterIsInstance<ZigContainerField>()
                                        .filter { it.name !in otherEnumLiterals }
                                        .flatMap { resolveValuesFromReferenceTo(it) }
                                        .flatMap { substituteTypeParams(it, conditionType.typeParams) }
                                } else {
                                    sequenceOf(ZigUnknown.of(conditionType))
                                }
                            } else {
                                val fieldValues =
                                    conditionType.members
                                        .filterIsInstance<ZigContainerField>()
                                        .filter { it.name in prongEnumLiterals }
                                        .sortedBy { prongEnumLiterals.indexOf(it.name) }
                                        .flatMap { resolveValuesFromReferenceTo(it) }
                                        .flatMap { substituteTypeParams(it, conditionType.typeParams) }
                                if (isInlineProng) {
                                    fieldValues
                                } else {
                                    // TODO: handle pointer capture of tagged union variants with incompatible types
                                    resolvePeerValues(fieldValues.toList()).asSequence()
                                }
                            }
                        }
                        is IZigContainerType if conditionType.containerKind == ZigContainerKind.ENUM -> {
                            if (isElseProng) {
                                if (isInlineProng) {
                                    val fields = conditionType.members.filterIsInstance<ZigContainerField>().toList()
                                    if (fields.any { it.nameIdentifier?.textMatches("_") == true }) {
                                        sequenceOf(ZigUnknown.of(conditionType))
                                    } else {
                                        fields
                                            .asSequence()
                                            .mapNotNull { it.name }
                                            .filter { it !in otherEnumLiterals }
                                            .map { ZigEnumLiteralValue(conditionType, it) }
                                    }
                                } else {
                                    sequenceOf(ZigUnknown.of(conditionType))
                                }
                            } else {
                                if (isInlineProng) {
                                    prongEnumLiterals.asSequence().map { ZigEnumLiteralValue(conditionType, it) }
                                } else {
                                    sequenceOf(ZigUnknown.of(conditionType))
                                }
                            }
                        }
                        else -> sequenceOf(ZigUnknown.of(conditionType)) // TODO
                    }
                    values.map { ZigPlace(condition.mutability, it) }
                }
            }
            1 -> {
                val switchProng = payloadElement.containingSwitchProng
                val switchElement = switchProng.containingSwitch
                val conditionElement = switchElement.conditionElement.expression
                    ?: return emptySequence()
                val isElseProng = switchProng.isElse
                val isInlineProng = switchProng.isInline
                val otherEnumLiterals =
                    switchElement.prongs
                        .filter { it !== switchProng }
                        .flatMap { it.items }
                        .flatMap { resolveValues(it.expression) }
                        .filterIsInstance<ZigEnumLiteralValue>()
                        .map { it.name }
                        .toSet()
                val prongEnumLiterals =
                    switchProng.items
                        .flatMap { resolveValues(it.expression) }
                        .filterIsInstance<ZigEnumLiteralValue>()
                        .map { it.name }
                        .toSet()
                resolveValues(conditionElement).asSequence().flatMap { condition ->
                    val conditionType = condition.typeOf
                    val values = if (conditionType is IZigContainerType && conditionType.isTaggedUnion) {
                        val tagTypes = ZigAnalyzer.resolveUnionTagTypes(conditionType)
                        if (isInlineProng) {
                            if (isElseProng) {
                                tagTypes
                                    .filterIsInstance<IZigContainerType>()
                                    .filter { it.containerKind == ZigContainerKind.ENUM }
                                    .flatMap { type ->
                                        type.members
                                            .filterIsInstance<ZigContainerField>()
                                            .mapNotNull { it.name }
                                            .filter { it !in otherEnumLiterals }
                                            .map { ZigEnumLiteralValue(type, it) }
                                    }
                            } else {
                                tagTypes.flatMap { type ->
                                    prongEnumLiterals.map { ZigEnumLiteralValue(type, it) }
                                }
                            }
                        } else {
                            tagTypes.map { ZigUnknown.of(it) }
                        }
                    } else {
                        emptySequence()
                    }
                    values.map { ZigPlace.constant(it) }
                }
            }
            else -> emptySequence()
        }

        is ZigForPayload -> {
            val payloadIndex = payloadElement.items.indexOf(payloadItem)
            val forInputs = payloadElement.containingFor.inputs
            val inputElement = forInputs.elementAtOrNull(payloadIndex)?.innerElement
                ?: return emptySequence()
            if (inputElement is ZigForRange) {
                return if (payloadItem.isPointer) {
                    emptySequence()
                } else {
                    sequenceOf(ZigPlace(null, ZigUnknown.of(ZigUsizeType)))
                }
            }
            if (inputElement !is ZigExpression) {
                return emptySequence()
            }
            resolveValues(inputElement).asSequence().mapNotNull { input ->
                when (val typeOfInput = input.typeOf) {
                    is ZigArrayType -> {
                        if (payloadItem.isPointer) {
                            null
                        } else {
                            ZigPlace(null, ZigUnknown.of(typeOfInput.child))
                        }
                    }
                    is ZigPointerType -> when (typeOfInput.size) {
                        ZigPointerSize.SLICE -> ZigPlace(typeOfInput.mutability, ZigUnknown.of(typeOfInput.child))
                        ZigPointerSize.ONE -> when (val pointerChild = typeOfInput.child) {
                            is ZigArrayType -> ZigPlace(typeOfInput.mutability, ZigUnknown.of(pointerChild.child))
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

private fun computeFieldTypeValues(
    containerTypes: Sequence<ZigValue>,
    fieldNameValues: Sequence<ZigValue>,
): Sequence<ZigValue> {
    return containerTypes.flatMap { container -> fieldNameValues.flatMap { fieldName ->
        if (container is IZigContainerType
            && (container.containerKind == ZigContainerKind.STRUCT
                    || container.containerKind == ZigContainerKind.UNION)
            && fieldName is ZigStringLiteralValue
        ) {
            val field = container.members.firstNotNullOfOrNull {
                if (it is ZigContainerField && it.name == fieldName.value) it else null
            }
            if (field != null) {
                resolveValuesFromReferenceTo(field).asSequence().map { it.typeOf }
            } else {
                sequenceOf(ZigLazyFieldType(container, fieldName))
            }
        } else {
            sequenceOf(ZigLazyFieldType(container, fieldName))
        }
    } }
}

private fun computeFieldPlaces(
    leftPlaces: Sequence<ZigPlace>,
    fieldNameValues: Sequence<ZigValue>,
): Sequence<ZigPlace> {
    return leftPlaces.flatMap { left -> fieldNameValues.flatMap { fieldName ->
        if (fieldName is ZigStringLiteralValue) {
            computeFieldAccess(left, fieldName)
        } else {
            sequenceOf(ZigPlace(null, ZigLazyFieldValue(left, fieldName)))
        }
    } }
}

fun computeTagNameValues(values: Sequence<ZigValue>): Sequence<ZigValue> {
    return values.map {
        when (it) {
            is ZigEnumLiteralValue -> ZigStringLiteralValue(it.name)
            else -> ZigLazyTagNameValue(it)
        }
    }
}

/** Gets `Foo` in `@typeInfo(Foo).@"union".tag_type.?` */
private fun getUnwrapUnionTagTypeArgument(element: ZigUnwrapOptional): ZigExpression? {
    var child = element.operand
    if (child !is ZigFieldAccess) return null
    if (child.identifierElement?.textMatches("tag_type") != true) return null
    child = child.leftOperand
    if (child !is ZigFieldAccess) return null
    if (child.identifierElement?.textMatches("@\"union\"") != true) return null
    child = child.leftOperand
    if (child !is ZigBuiltinCall) return null
    if (!child.builtinElement.textMatches("@typeInfo")) return null
    return child.argumentElements.firstOrNull()?.expression
}

// Based on Sema.resolvePeerTypes
// https://codeberg.org/ziglang/zig/src/branch/master/src/Sema.zig#L32687
private fun resolvePeerValues(values: List<ZigValue>): List<ZigValue> {
    when (values.size) {
        0 -> return emptyList()
        1 -> return values
        else -> {}
    }

    var sameType = true
    val firstType = values[0].typeOf
    for (i in 1 until values.size) {
        if (values[i].typeOf != firstType) {
            sameType = false
            break
        }
    }
    if (sameType) {
        if (firstType == ZigTypeType) {
            return values
        }
        return listOf(ZigUnknown.of(firstType))
    }

    val peerTys: MutableList<ZigValue?> = values.map { it.typeOf }.toMutableList()
    val peerVals: MutableList<ZigValue?> = values.toMutableList()
    val type = resolvePeerTypesInner(peerTys, peerVals)
    return if (type != null) listOf(ZigUnknown.of(type)) else values
}

private enum class PeerResolveStrategy {
    UNKNOWN,
    ERROR_SET,
    ERROR_UNION,
    NULLABLE,
    OPTIONAL,
    ARRAY,
    VECTOR,
    C_PTR,
    PTR,
    FUNC,
    ENUM_OR_UNION,
    COMPTIME_INT,
    COMPTIME_FLOAT,
    FIXED_INT,
    FIXED_FLOAT,
    TUPLE,
    EXACT;

    fun merge(other: PeerResolveStrategy): PeerResolveStrategy {
        val s0IsThis = ordinal <= other.ordinal
        val s0 = if (s0IsThis) this else other
        val s1 = if (s0IsThis) other else this
        return when (s0) {
            UNKNOWN -> s1
            ERROR_SET -> when (s1) {
                ERROR_SET -> ERROR_SET
                else -> ERROR_UNION
            }
            ERROR_UNION -> when (s1) {
                ERROR_UNION -> ERROR_UNION
                else -> ERROR_UNION
            }
            NULLABLE -> when (s1) {
                NULLABLE -> NULLABLE
                C_PTR -> C_PTR
                else -> OPTIONAL
            }
            OPTIONAL -> when (s1) {
                OPTIONAL -> OPTIONAL
                C_PTR -> C_PTR
                else -> OPTIONAL
            }
            ARRAY -> when (s1) {
                ARRAY -> ARRAY
                VECTOR -> VECTOR
                else -> ARRAY
            }
            VECTOR -> when (s1) {
                VECTOR -> VECTOR
                else -> VECTOR
            }
            C_PTR -> when (s1) {
                C_PTR -> C_PTR
                else -> C_PTR
            }
            PTR -> when (s1) {
                PTR -> PTR
                else -> PTR
            }
            FUNC -> when (s1) {
                FUNC -> FUNC
                else -> s1
            }
            ENUM_OR_UNION -> when (s1) {
                ENUM_OR_UNION -> ENUM_OR_UNION
                else -> ENUM_OR_UNION
            }
            COMPTIME_INT -> when (s1) {
                COMPTIME_INT -> COMPTIME_INT
                else -> s1
            }
            COMPTIME_FLOAT -> when (s1) {
                COMPTIME_FLOAT -> COMPTIME_FLOAT
                else -> s1
            }
            FIXED_INT -> when (s1) {
                FIXED_INT -> FIXED_INT
                else -> s1
            }
            FIXED_FLOAT -> when (s1) {
                FIXED_FLOAT -> FIXED_FLOAT
                else -> s1
            }
            TUPLE -> when (s1) {
                EXACT -> EXACT
                else -> TUPLE
            }
            EXACT -> EXACT
        }
    }

    companion object {
        fun select(ty: ZigValue): PeerResolveStrategy {
            return when (ty) {
                ZigTypeType, ZigVoidType, ZigBoolType -> EXACT
                ZigNoreturnType, ZigUndefinedType -> UNKNOWN
                ZigNullType -> NULLABLE
                ZigComptimeIntType -> COMPTIME_INT
                is ZigFixedIntType -> FIXED_INT
                ZigComptimeFloatType -> COMPTIME_FLOAT
                is ZigFixedFloatType -> FIXED_FLOAT
                is ZigPointerType if ty.size == ZigPointerSize.C -> C_PTR
                is ZigPointerType -> PTR
                is ZigArrayType -> ARRAY
                is ZigVectorType -> VECTOR
                is ZigOptionalType -> OPTIONAL
                is ZigErrorSetType -> ERROR_SET
                is ZigErrorUnionType -> ERROR_UNION
                is ZigEnumLiteralType -> ENUM_OR_UNION
                is ZigFunctionType -> FUNC
                is ZigTupleType -> TUPLE
                is IZigContainerType -> when (ty.containerKind) {
                    ZigContainerKind.OPAQUE -> EXACT
                    ZigContainerKind.ENUM -> ENUM_OR_UNION
                    ZigContainerKind.UNION -> ENUM_OR_UNION
                    ZigContainerKind.STRUCT -> EXACT
                }
                else -> EXACT
            }
        }
    }
}

private fun resolvePeerTypesInner(
    peerTys: MutableList<ZigValue?>,
    peerVals: MutableList<ZigValue?>,
): ZigValue? {
    var s = PeerResolveStrategy.UNKNOWN
    for (ty in peerTys) {
        if (ty == null) continue
        s = s.merge(PeerResolveStrategy.select(ty))
    }

    if (s == PeerResolveStrategy.UNKNOWN) {
        s = PeerResolveStrategy.EXACT
    } else {
        for (idx in peerTys.indices) {
            val ty = peerTys[idx] ?: continue
            when (ty) {
                ZigNoreturnType, ZigUndefinedType -> peerTys[idx] = null
                else -> {}
            }
        }
    }

    when (s) {
        PeerResolveStrategy.UNKNOWN -> throw IllegalStateException()

        PeerResolveStrategy.ERROR_SET -> {
            var finalSet: ZigErrorSetType? = null
            for (ty in peerTys) {
                if (ty == null) continue
                if (ty !is ZigErrorSetType) return null
                if (finalSet != null) {
                    val names = finalSet.names + ty.names
                    finalSet = ZigErrorSetType(names.toSortedSet())
                } else {
                    finalSet = ty
                }
            }
            return finalSet!!
        }

        PeerResolveStrategy.ERROR_UNION -> {
            var finalSet: ZigErrorSetType? = null
            for (idx in peerTys.indices) {
                val ty = peerTys[idx] ?: continue
                val setTy = when (ty) {
                    is ZigErrorSetType -> {
                        peerTys[idx] = null
                        peerVals[idx] = null
                        ty
                    }
                    is ZigErrorUnionType -> {
                        peerTys[idx] = ty.payload
                        peerVals[idx] = null // TODO
                        ty.errorSet as? ZigErrorSetType ?: return null
                    }
                    else -> continue
                }
                if (finalSet != null) {
                    val names = finalSet.names + setTy.names
                    finalSet = ZigErrorSetType(names.toSortedSet())
                } else {
                    finalSet = setTy
                }
            }
            val finalPayload = resolvePeerTypesInner(peerTys, peerVals) ?: return null
            return ZigErrorUnionType(finalSet!!, finalPayload)
        }

        PeerResolveStrategy.NULLABLE -> {
            for (ty in peerTys) {
                if (ty == null) continue
                if (ty != ZigNullType) return null
            }
            return ZigNullType
        }

        PeerResolveStrategy.OPTIONAL -> {
            for (idx in peerTys.indices) {
                val ty = peerTys[idx] ?: continue
                when (ty) {
                    ZigNullType -> {
                        peerTys[idx] = null
                        peerVals[idx] = null
                    }
                    is ZigOptionalType -> {
                        peerTys[idx] = ty.child
                        peerVals[idx] = null // TODO
                    }
                    else -> {}
                }
            }
            val childTy = resolvePeerTypesInner(peerTys, peerVals) ?: return null
            return ZigOptionalType(childTy)
        }

        PeerResolveStrategy.ARRAY -> {
            return null // TODO
        }

        PeerResolveStrategy.VECTOR -> {
            return null // TODO
        }

        PeerResolveStrategy.C_PTR -> {
            var ptrTy: ZigPointerType? = null
            for (ty in peerTys) {
                if (ty == null) continue
                val peerTy = when (ty) {
                    ZigComptimeIntType, ZigNullType -> continue
                    is ZigPointerType -> when (ty.size) {
                        ZigPointerSize.SLICE -> return null
                        ZigPointerSize.ONE, ZigPointerSize.MANY, ZigPointerSize.C -> ty
                    }
                    is ZigOptionalType -> when (val child = ty.child) {
                        is ZigPointerType -> when (child.size) {
                            ZigPointerSize.SLICE, ZigPointerSize.C -> return null
                            ZigPointerSize.MANY, ZigPointerSize.ONE -> {
                                // TODO: check if allowzero
                                child
                            }
                        }
                        else -> return null
                    }
                    else -> return null
                }
                if (ptrTy == null) {
                    ptrTy = ZigPointerType(ZigPointerSize.C, null, peerTy.mutability, peerTy.child)
                    continue
                }
                val ptrChild = ptrTy.child
                if (ptrChild != peerTy.child) {
                    // TODO: resolve pair in memory coercible
                    return null
                }
                // TODO: pointer modifiers
                val ptrMutability = when (ptrTy.mutability) {
                    ZigMutability.CONST -> ZigMutability.CONST
                    ZigMutability.VAR -> peerTy.mutability
                    null -> null
                }
                ptrTy = ZigPointerType(ZigPointerSize.C, null, ptrMutability, ptrChild)
            }
            return ptrTy!!
        }

        PeerResolveStrategy.PTR -> {
            var sliceIdx: Int? = null
            var ptrTy: ZigPointerType? = null
            for (idx in peerTys.indices) {
                val ty = peerTys[idx] ?: continue
                val peerTy = when (ty) {
                    is ZigPointerType -> ty
                    is ZigFunctionType -> ZigPointerType(ZigPointerSize.ONE, null, ZigMutability.CONST, ty)
                    else -> return null
                }
                when (peerTy.size) {
                    ZigPointerSize.ONE, ZigPointerSize.MANY -> {}
                    ZigPointerSize.SLICE -> sliceIdx = idx
                    ZigPointerSize.C -> return null
                }
                if (ptrTy == null) {
                    ptrTy = peerTy
                    continue
                }
                var ptrSize = ptrTy.size
                var ptrSentinel = ptrTy.sentinel
                var ptrChild = ptrTy.child
                check(ptrSize != ZigPointerSize.C)
                // TODO: pointer modifiers
                val ptrMutability = when (ptrTy.mutability) {
                    ZigMutability.CONST -> ZigMutability.CONST
                    ZigMutability.VAR -> peerTy.mutability
                    null -> null
                }
                val peerSentinel = when (peerTy.size) {
                    ZigPointerSize.ONE -> when (val pointee = peerTy.child) {
                        is ZigArrayType -> pointee.sentinel
                        else -> null
                    }
                    ZigPointerSize.MANY, ZigPointerSize.SLICE -> peerTy.sentinel
                }
                val curSentinel = when (ptrSize) {
                    ZigPointerSize.ONE -> when (val pointee = ptrChild) {
                        is ZigArrayType -> pointee.sentinel
                        else -> null
                    }
                    ZigPointerSize.MANY, ZigPointerSize.SLICE -> ptrSentinel
                }
                val peerArr = typeIsArrayLike(peerTy.child)
                val curArr = typeIsArrayLike(ptrChild)
                run good@{
                    when (peerTy.size) {
                        ZigPointerSize.ONE -> when (ptrSize) {
                            ZigPointerSize.ONE -> {
                                // TODO: resolve pair in memory coercible
                                if (ptrChild == peerTy.child) {
                                    return@good
                                }
                                if (curArr == null) return null
                                if (peerArr == null) return null
                                // TODO: resolve pair in memory coercible
                                if (curArr.child == peerArr.child) {
                                    if (curArr.length == peerArr.length) {
                                        ptrChild = ZigArrayType(
                                            length = ZigIntValue(ZigUsizeType, curArr.length),
                                            child = curArr.child,
                                            sentinel = null,
                                        )
                                        return@good
                                    }
                                    ptrSize = ZigPointerSize.SLICE
                                    ptrChild = curArr.child
                                    return@good
                                }
                                if (peerArr.child == ZigNoreturnType) {
                                    ptrSize = ZigPointerSize.SLICE
                                    ptrChild = curArr.child
                                    return@good
                                }
                                if (curArr.child == ZigNoreturnType) {
                                    ptrSize = ZigPointerSize.SLICE
                                    ptrChild = peerArr.child
                                    return@good
                                }
                                return null
                            }
                            ZigPointerSize.MANY -> {
                                if (peerArr == null) return null
                                // TODO: resolve pair in memory coercible
                                if (ptrChild == peerArr.child) {
                                    return@good
                                }
                                if (peerArr.child == ZigNoreturnType) {
                                    return@good
                                }
                                return null
                            }
                            ZigPointerSize.SLICE -> {
                                if (peerArr == null) return null
                                // TODO: resolve pair in memory coercible
                                if (ptrChild == peerArr.child) {
                                    return@good
                                }
                                if (peerArr.child == ZigNoreturnType) {
                                    return@good
                                }
                                return null
                            }
                        }
                        ZigPointerSize.MANY -> when (ptrSize) {
                            ZigPointerSize.ONE -> {
                                if (curArr == null) return null
                                // TODO: resolve pair in memory coercible
                                if (curArr.child == peerTy.child) {
                                    ptrSize = ZigPointerSize.MANY
                                    ptrChild = curArr.child
                                    return@good
                                }
                                if (curArr.child == ZigNoreturnType) {
                                    ptrSize = ZigPointerSize.MANY
                                    ptrChild = peerTy.child
                                    return@good
                                }
                                return null
                            }
                            ZigPointerSize.MANY -> {
                                // TODO: resolve pair in memory coercible
                                if (ptrChild == peerTy.child) {
                                    return@good
                                }
                                return null
                            }
                            ZigPointerSize.SLICE -> {
                                if (sliceIdx != null) return null
                                // TODO: resolve pair in memory coercible
                                if (ptrChild == peerTy.child) {
                                    ptrSize = ZigPointerSize.MANY
                                    return@good
                                }
                                return null
                            }
                        }
                        ZigPointerSize.SLICE -> when (ptrSize) {
                            ZigPointerSize.ONE -> {
                                if (curArr == null) return null
                                // TODO: resolve pair in memory coercible
                                if (curArr.child == peerTy.child) {
                                    ptrSize = ZigPointerSize.SLICE
                                    ptrChild = curArr.child
                                    return@good
                                }
                                if (curArr.child == ZigNoreturnType) {
                                    ptrSize = ZigPointerSize.SLICE
                                    ptrChild = peerTy.child
                                    return@good
                                }
                                return null
                            }
                            ZigPointerSize.MANY -> {
                                return null
                            }
                            ZigPointerSize.SLICE -> {
                                // TODO: resolve pair in memory coercible
                                if (ptrChild == peerTy.child) {
                                    return@good
                                }
                                return null
                            }
                        }
                    }
                }
                run sentinel@{
                    run noSentinel@{
                        if (peerSentinel == null) return@noSentinel
                        if (curSentinel == null) return@noSentinel
                        //  TODO: get coerced sentinels
                        if (peerSentinel != curSentinel) return@noSentinel
                        if (ptrSize == ZigPointerSize.ONE) when (val pointee = ptrChild) {
                            is ZigArrayType -> ptrChild = ZigArrayType(
                                length = pointee.length,
                                child = pointee.child,
                                sentinel = curSentinel,
                            )
                            else -> throw IllegalStateException()
                        } else {
                            ptrSentinel = curSentinel
                        }
                        return@sentinel
                    }
                    ptrSentinel = null
                    if (ptrSize == ZigPointerSize.ONE) when (val pointee = ptrChild) {
                        is ZigArrayType -> ptrChild = ZigArrayType(
                            length = pointee.length,
                            child = pointee.child,
                            sentinel = null,
                        )
                        else -> {}
                    }
                }
                ptrTy = ZigPointerType(ptrSize, ptrSentinel, ptrMutability, ptrChild)
            }
            check(ptrTy != null)
            when (val pointee = ptrTy.child) {
                ZigNoreturnType -> return null
                is ZigArrayType if pointee.child == ZigNoreturnType -> return null
                else -> {}
            }
            return ptrTy
        }

        PeerResolveStrategy.FUNC -> {
            return null // TODO
        }

        PeerResolveStrategy.ENUM_OR_UNION -> {
            var curTy: ZigValue? = null
            for (ty in peerTys) {
                if (ty == null) continue
                when (ty) {
                    ZigEnumLiteralType -> {}
                    is IZigContainerType -> when (ty.containerKind) {
                        ZigContainerKind.ENUM -> {}
                        ZigContainerKind.UNION -> {}
                        else -> return null
                    }
                    else -> return null
                }
                if (curTy == null) {
                    curTy = ty
                    continue
                }
                when (curTy) {
                    ZigEnumLiteralType -> {
                        curTy = ty
                    }
                    is IZigContainerType -> when (curTy.containerKind) {
                        ZigContainerKind.ENUM -> when (ty) {
                            ZigEnumLiteralType -> {}
                            is IZigContainerType -> when (ty.containerKind) {
                                ZigContainerKind.ENUM -> {
                                    if (ty != curTy) return null
                                }
                                ZigContainerKind.UNION -> {
                                    val tagTys = ZigAnalyzer.resolveUnionTagTypes(ty)
                                    if (!tagTys.contains(curTy)) return null
                                    curTy = ty
                                }
                                else -> throw IllegalStateException()
                            }
                        }
                        ZigContainerKind.UNION -> when (ty) {
                            ZigEnumLiteralType -> {}
                            is IZigContainerType -> when (ty.containerKind) {
                                ZigContainerKind.ENUM -> {
                                    val curTagTys = ZigAnalyzer.resolveUnionTagTypes(curTy)
                                    if (!curTagTys.contains(ty)) return null
                                }
                                ZigContainerKind.UNION -> {
                                    if (ty != curTy) return null
                                }
                                else -> throw IllegalStateException()
                            }
                        }
                        else -> throw IllegalStateException()
                    }
                    else -> throw IllegalStateException()
                }
            }
            return curTy!!
        }

        PeerResolveStrategy.COMPTIME_INT -> {
            for (ty in peerTys) {
                if (ty == null) continue
                when (ty) {
                    ZigComptimeIntType -> {}
                    else -> return null
                }
            }
            return ZigComptimeIntType
        }

        PeerResolveStrategy.COMPTIME_FLOAT -> {
            for (ty in peerTys) {
                if (ty == null) continue
                when (ty) {
                    ZigComptimeIntType, ZigComptimeFloatType -> {}
                    else -> return null
                }
            }
            return ZigComptimeFloatType
        }

        PeerResolveStrategy.FIXED_INT -> {
            var idxUnsigned: Int? = null
            var idxSigned: Int? = null
            for (idx in peerTys.indices) {
                val ty = peerTys[idx] ?: continue
                when (ty) {
                    ZigComptimeIntType -> continue
                    is ZigFixedIntType -> {}
                    else -> return null
                }
                val largestIdx = when (ty.signedness) {
                    ZigSignedness.UNSIGNED -> {
                        if (idxUnsigned == null) {
                            idxUnsigned = idx
                            continue
                        }
                        idxUnsigned
                    }
                    ZigSignedness.SIGNED -> {
                        if (idxSigned == null) {
                            idxSigned = idx
                            continue
                        }
                        idxSigned
                    }
                }
                val curTy = peerTys[largestIdx] as ZigFixedIntType
                if (ty.bits > curTy.bits) {
                    when (ty.signedness) {
                        ZigSignedness.UNSIGNED -> idxUnsigned = idx
                        ZigSignedness.SIGNED -> idxSigned = idx
                    }
                }
            }
            if (idxSigned == null) {
                return peerTys[idxUnsigned!!]
            }
            if (idxUnsigned == null) {
                return peerTys[idxSigned]
            }
            val unsignedTy = peerTys[idxUnsigned] as ZigFixedIntType
            val signedTy = peerTys[idxSigned] as ZigFixedIntType
            if (signedTy.bits > unsignedTy.bits) {
                return signedTy
            }
            return null
        }

        PeerResolveStrategy.FIXED_FLOAT -> {
            var curTy: ZigFixedFloatType? = null
            for (ty in peerTys) {
                if (ty == null) continue
                when (ty) {
                    ZigComptimeFloatType, ZigComptimeIntType, is ZigFixedIntType -> {}
                    is ZigFixedFloatType -> {
                        if (curTy != null) {
                            if (curTy == ty) continue
                            val bits = maxOf(curTy.bits, ty.bits)
                            curTy = ZigFixedFloatType(bits)
                        } else {
                            curTy = ty
                        }
                    }
                    else -> return null
                }
            }
            check(curTy != null)
            for (ty in peerTys) {
                if (ty == null) continue
                when (ty) {
                    ZigComptimeFloatType, ZigComptimeIntType, is ZigFixedFloatType -> {}
                    is ZigFixedIntType -> {
                        val intPrecision = when (ty.signedness) {
                            ZigSignedness.SIGNED -> ty.bits - 1
                            ZigSignedness.UNSIGNED -> ty.bits
                        }
                        if (intPrecision > floatSignificandBits(curTy))
                            return null
                    }
                    else -> throw IllegalStateException()
                }
            }
            return curTy
        }

        PeerResolveStrategy.TUPLE -> {
            var manager: PsiManager? = null
            var fieldCount = -1
            for (idx in peerTys.indices) {
                val ty = peerTys[idx] ?: continue
                if (ty !is ZigTupleType) {
                    return null
                }
                if (manager == null) {
                    manager = ty.element.manager
                    fieldCount = ty.fields.size
                    continue
                }
                if (ty.fields.size != fieldCount) {
                    return null
                }
            }
            check(manager != null)
            val fieldTypes = mutableListOf<ZigValue>()
            val subPeerTys = MutableList<ZigValue?>(peerTys.size) { null }
            val subPeerVals = MutableList<ZigValue?>(peerVals.size) { null }
            for (fieldIndex in 0 until fieldCount) {
                for (idx in peerTys.indices) {
                    val ty = peerTys[idx]
                    if (ty == null) {
                        subPeerTys[idx] = null
                        subPeerVals[idx] = null
                        continue
                    }
                    check(ty is ZigTupleType)
                    subPeerTys[idx] = ty.element.fieldTypes[fieldIndex]
                    subPeerVals[idx] = null // TODO
                }
                val fieldTy = resolvePeerTypesInner(subPeerTys, subPeerVals) ?: return null
                fieldTypes.add(fieldTy)
            }
            return ZigTupleType(LightZigTuple(manager, fieldTypes))
        }

        PeerResolveStrategy.EXACT -> {
            var expectTy: ZigValue? = null
            for (ty in peerTys) {
                if (ty == null) continue
                if (expectTy != null) {
                    if (ty != expectTy) return null
                } else {
                    expectTy = ty
                }
            }
            return expectTy!!
        }
    }
}

private class ArrayLike(val length: Int, val child: ZigValue)

private fun typeIsArrayLike(ty: ZigValue): ArrayLike? {
    return when (ty) {
        is ZigArrayType -> when (val length = ty.length) {
            is ZigIntValue -> ArrayLike(length.value, ty.child)
            else -> null
        }
        is ZigTupleType -> {
            val fieldCount = ty.fields.size
            if (fieldCount == 0) {
                return ArrayLike(0, ZigNoreturnType)
            }
            val child = ty.fields[0]
            for (i in 1 until fieldCount) {
                if (ty.fields[i] != child) {
                    return null
                }
            }
            ArrayLike(fieldCount, child)
        }
        else -> null
    }
}

// Based on Type.floatSignificandBits
// https://codeberg.org/ziglang/zig/src/commit/ebb8683/src/Type.zig#L1952-L1962
private fun floatSignificandBits(ty: ZigFixedFloatType): Int {
    return when (ty.bits) {
        16 -> 11
        32 -> 24
        64 -> 53
        80 -> 64
        128 -> 113
        else -> throw IllegalStateException()
    }
}
