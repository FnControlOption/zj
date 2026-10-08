package io.github.fncontroloption.zig

import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.findDirectory
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

    fun resolveValuesFromReferenceTo(target: ZigNamedElement): Set<ZigValue> {
        return io.github.fncontroloption.zig.resolveValuesFromReferenceTo(target)
    }

    fun substituteTypeParams(
        value: ZigValue,
        typeParams: Map<ZigFnParam, ZigValue?>,
    ): Sequence<ZigValue> {
        return io.github.fncontroloption.zig.substituteTypeParams(value, typeParams)
    }

    fun substituteTypeParams(
        values: Sequence<ZigValue>,
        typeParams: Map<ZigFnParam, ZigValue?>,
    ): Sequence<ZigValue> {
        return values.flatMap { substituteTypeParams(it, typeParams) }
    }

    fun substituteTypeParams(
        values: Iterable<ZigValue>,
        typeParams: Map<ZigFnParam, ZigValue?>,
    ): Sequence<ZigValue> {
        return substituteTypeParams(values.asSequence(), typeParams)
    }

    fun inferResultType(childElement: ZigExpression): ZigValue {
        return io.github.fncontroloption.zig.inferResultType(childElement)
    }

    /* TODO: delete
    fun resolveOrInferStructInitTypes(element: ZigListInit): Sequence<IZigContainerType> {
        return resolveOrInferListInitTypes(element).filterIsInstance<IZigContainerType>()
    }
     */

    fun resolveOrInferListInitType(element: ZigListInit): ZigValue {
        val typeExpression = element.typeExpression
        if (typeExpression != null) {
            return resolveValue(typeExpression, ZigTypeType)
        }
        return inferResultType(element).map { findListInitType(it) }
    }

    fun findListInitType(originalType: ZigValue): ZigValue {
        var type = originalType
        while (true) {
            type = when (type) {
                is ZigErrorUnionType -> type.payload
                is ZigOptionalType -> type.child
                else -> break
            }
        }
        return type
    }

    fun findEnumLiteralContainerType(originalType: ZigValue): IZigContainerType? {
        var type = originalType
        while (true) {
            type = when (type) {
                is ZigErrorUnionType -> type.payload
                is ZigOptionalType -> type.child
                is ZigPointerType if type.size == ZigPointerSize.ONE -> type.child
                else -> break
            }
        }
        return type as? IZigContainerType
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
        val place = ZigPlace.Single(null, value)
        return getBuiltinFieldPlace(place, name)?.value?.typeOf
    }

    fun resolveEnumTagType(type: IZigContainerType): ZigValue? {
        if (type.containerKind != ZigContainerKind.ENUM) {
            return null
        }
        return when (type) {
            is ZigContainerType -> {
                resolveValue(type.element.containerArgElement?.expression, ZigTypeType)
            }
            is ZigInferredUnionTagType -> {
                val tagElement = type.union.element.containerArgElement ?: return null
                if (tagElement.isInferredEnum) {
                    return resolveValue(tagElement.expression, ZigTypeType)
                }
                ZigUnknown.of(ZigTypeType)
            }
            is ZigFieldEnumType -> ZigUnknown.of(ZigTypeType)
            is ZigTupleType -> throw IllegalStateException()
        }
    }

    fun resolveUnionTagType(type: IZigContainerType): ZigValue? {
        if (type !is ZigContainerType) {
            return null
        }
        if (type.element.containerKind != ZigContainerKind.UNION) {
            return null
        }
        val tagElement = type.element.containerArgElement ?: return null
        if (tagElement.isInferredEnum) {
            return ZigInferredUnionTagType(type)
        }
        return resolveValue(tagElement.expression, ZigTypeType)
    }

    fun createGenericParameterValue(param: ZigFnParam): ZigGenericParameterValue? {
        if (param.isAnytype) {
            return ZigAnytypeParameterValue(param)
        }
        val typeElement = param.typeElement?.expression ?: return null
        val type = resolveValue(typeElement, ZigTypeType)
        val isComptime = param.isComptime || type == ZigTypeType
        if (isComptime) {
            return ZigComptimeParameterValue(type, param)
        }
        return null
    }

    fun findStdFile(originalElement: PsiElement, path: String): ZigFile? {
        val project = originalElement.project
        val projectRoots = ProjectRootManager.getInstance(project).contentRoots.asSequence()
        val projectLibStds = projectRoots.mapNotNull { it.findDirectory("lib/std") }

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
        val sdkRoots = enumerator.sdkOnly().sourceRoots.asSequence()

        val psiManager = PsiManager.getInstance(project)
        for (root in projectLibStds + sdkRoots) {
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

sealed interface ZigPlace {
    val mutability: ZigMutability?
    val value: ZigValue

    fun map(transform: (ZigPlace) -> ZigPlace): ZigPlace

    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int
    override fun toString(): String

    data class Single(
        override val mutability: ZigMutability?,
        override val value: ZigValue,
    ) : ZigPlace {
        override fun map(transform: (ZigPlace) -> ZigPlace): ZigPlace = transform(this)
    }

    @ConsistentCopyVisibility
    data class Multi private constructor(val places: Set<ZigPlace>) : ZigPlace {
        override val mutability by lazy {
            places.map { it.mutability }.reduce { a, b -> if (a == b) a else null }
        }

        override val value by lazy {
            ZigMultiValue.from(places.map { it.value }.toSet())
        }

        override fun map(transform: (ZigPlace) -> ZigPlace): ZigPlace {
            return from(places.map(transform).toSet())
        }

        companion object {
            fun from(places: Set<ZigPlace>): ZigPlace {
                if (places.isEmpty()) return unknown()
                return places.singleOrNull() ?: Multi(places)
            }
        }
    }

    companion object {
        fun constant(value: ZigValue) = Single(ZigMutability.CONST, value)
        fun unknown() = Single(null, ZigUnknown.ofUnknownType())
    }
}

sealed interface ZigValue {
    val typeOf: ZigValue

    fun toHtmlChunk(truncate: Boolean = false): HtmlChunk
    fun toPlainText() = Jsoup.parse(toHtmlChunk(truncate = true).toString()).text()
    fun map(transform: (ZigValue) -> ZigValue): ZigValue = transform(this)
    fun forEach(action: (ZigValue) -> Unit): Unit = action(this)

    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int
    override fun toString(): String
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

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
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

@ConsistentCopyVisibility
data class ZigMultiValue private constructor(val values: Set<ZigValue>) : ZigValue {
    init {
        check(values.size > 1)
    }

    override val typeOf by lazy { map { it.typeOf } }

    override fun toHtmlChunk(truncate: Boolean) =
        createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "<multi value>")

    override fun map(transform: (ZigValue) -> ZigValue): ZigValue {
        return from(values.map(transform).toSet())
    }

    override fun forEach(action: (ZigValue) -> Unit) {
        values.forEach(action)
    }

    companion object {
        fun from(values: Set<ZigValue>, expectedType: ZigValue? = null): ZigValue {
            if (values.isNotEmpty()) {
                return values.singleOrNull() ?: ZigMultiValue(values)
            }
            if (expectedType != null) {
                return ZigUnknown.of(expectedType)
            }
            return ZigUnknown.ofUnknownType()
        }
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
    val typeParams: Map<ZigFnParam, ZigValue?>
    val containerKind: ZigContainerKind
    val isTaggedUnion: Boolean
    val members: Sequence<ZigContainerMember>
    fun getNamedMembersVisibleFrom(originalFile: PsiFile): Sequence<ZigNamedContainerMember>
}

data class ZigContainerType(
    val element: ZigContainer,
    override val typeParams: Map<ZigFnParam, ZigValue?>,
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
    val typeParams: Map<ZigFnParam, ZigValue?>,
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
    fun with(
        size: ZigPointerSize = this.size,
        sentinel: ZigValue? = this.sentinel,
        mutability: ZigMutability? = this.mutability,
        child: ZigValue = this.child,
    ) = ZigPointerType(size, sentinel, mutability, child)

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

    override val typeParams: Map<ZigFnParam, ZigValue?>
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

    override val typeParams: Map<ZigFnParam, ZigValue?>
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

    override val typeParams: Map<ZigFnParam, ZigValue?>
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

private fun resolvePlace(element: ZigExpression): ZigPlace {
    return CachedValuesManager.getCachedValue(element) {
        RecursionManager.doPreventingRecursion(element, true) {
            CachedValueProvider.Result.create(
                computePlaceUncached(element),
                PsiModificationTracker.MODIFICATION_COUNT,
                ZigBuiltinService.getModificationTracker(element.project),
                ZigBuiltinFunctionsService.getModificationTracker(element.project),
                ZigModulesProjectService.getModificationTracker(element.project),
            )
        }
    } ?: ZigPlace.unknown()
}

private fun computePlaceUncached(element: ZigExpression): ZigPlace {
    return when (element) {
        is ZigIdentifier -> computeIdentifier(element)
        is ZigFieldAccess -> computeFieldAccess(element)
        is ZigArrayAccess -> computeArrayAccess(element)
        is ZigGroupedExpression -> computeGroupedExpression(element)
        is ZigUnwrapOptional -> computeUnwrapOptional(element)
        is ZigTry -> computeTry(element)
        is ZigDeref -> computeDeref(element)
        is ZigAddressOf -> computeAddressOf(element)
        is ZigSlice -> computeSlice(element)
        is ZigBuiltinCall if element.builtinElement.text == "@field" -> computeFieldBuiltinCall(element)
        else -> ZigPlace.constant(resolveValue(element))
    }
}

private fun resolveValue(element: ZigExpression?, expectedType: ZigValue? = null): ZigValue {
    if (element != null) {
        return ZigMultiValue.from(resolveValues(element), expectedType)
    }
    if (expectedType != null) {
        return ZigUnknown.of(expectedType)
    }
    return ZigUnknown.ofUnknownType()
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
                ZigModulesProjectService.getModificationTracker(element.project),
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
        is ZigAddressOf,
        is ZigSlice,
            -> sequenceOf(resolvePlace(element).value)

        is ZigBuiltinCall if element.builtinElement.text == "@field"
            -> sequenceOf(resolvePlace(element).value)

        is ZigContainerExpression -> sequenceOf(computeContainerExpression(element))
        is ZigFnProto -> sequenceOf(ZigFunctionType(element, findUnboundTypeParams(element)))
        is ZigOptionalTypeExpression -> sequenceOf(computeOptionalTypeExpression(element))
        is ZigErrorSet -> sequenceOf(computeErrorSet(element))
        is ZigErrorValueExpression -> sequenceOf(computeErrorValueExpression(element))
        is ZigErrorUnion -> sequenceOf(computeErrorUnion(element))
        is ZigArrayTypeExpression -> sequenceOf(computeArrayTypeExpression(element))
        is ZigPointerTypeExpression -> sequenceOf(computePointerTypeExpression(element))
        is ZigComptime -> sequenceOf(computeComptime(element))
        is ZigIf -> sequenceOf(computeIf(element))
        is ZigCatch -> sequenceOf(computeCatch(element))
        is ZigOrelse -> sequenceOf(computeOrelse(element))
        is ZigMergeErrorSets -> sequenceOf(computeMergeErrorSets(element))
        is ZigListInit -> sequenceOf(computeListInit(element))

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

        is ZigBuiltinCall -> sequenceOf(computeBuiltinCall(element))
        is ZigCall -> sequenceOf(computeCall(element))
        is ZigBlock -> sequenceOf(computeBlock(element))
        is ZigSwitch -> sequenceOf(computeSwitch(element))
        is ZigLoop -> sequenceOf(computeLoop(element))
        is ZigStringExpression -> sequenceOf(computeStringExpression(element))
        is ZigNumberLiteral -> sequenceOf(computeNumberLiteral(element))
        is ZigCharLiteral -> sequenceOf(ZigUnknown.of(ZigComptimeIntType))
        is ZigEnumLiteral -> sequenceOf(computeEnumLiteral(element))

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

private fun getBuiltinFieldPlace(place: ZigPlace, name: String): ZigPlace? {
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
                "len" -> ZigPlace.Single(mutability, ZigUnknown.of(ZigUsizeType))
                "ptr" -> ZigPlace.Single(mutability, ZigUnknown.of(type.with(size = ZigPointerSize.MANY)))
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

private fun computeFieldAccess(
    left: ZigPlace,
    fieldName: ZigValue,
): ZigPlace {
    if (fieldName !is ZigStringLiteralValue) {
        return ZigPlace.Single(null, ZigLazyFieldValue(left, fieldName))
    }
    val name = fieldName.value
    val builtinPlace = getBuiltinFieldPlace(left, name)
    if (builtinPlace != null) {
        return builtinPlace
    }
    return when (val leftValue = left.value) {
        is ZigErrorSetType -> {
            if (name in leftValue.names) {
                ZigPlace.constant(ZigErrorSetValue(leftValue, name))
            } else {
                ZigPlace.unknown()
            }
        }
        is IZigContainerType -> computeContainerTypeDeclAccess(leftValue, fieldName)
        else -> when (val typeOfLeft = leftValue.typeOf) {
            is IZigContainerType -> computeContainerInstanceFieldAccess(left.mutability, typeOfLeft, fieldName)
            is ZigPointerType -> when (typeOfLeft.size) {
                ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                    is IZigContainerType ->
                        computeContainerInstanceFieldAccess(typeOfLeft.mutability, pointerChild, fieldName)
                    else -> ZigPlace.Single(null, ZigLazyFieldValue(left, fieldName))
                }
                else -> ZigPlace.unknown()
            }
            else -> ZigPlace.Single(null, ZigLazyFieldValue(left, fieldName))
        }
    }
}

private fun computeArrayAccessOnTuple(
    mutability: ZigMutability?,
    type: ZigTupleType,
    indexExpression: ZigExpression?,
): ZigPlace {
    if (indexExpression == null) {
        return ZigPlace.unknown()
    }
    val fieldType = resolveValue(indexExpression).map {
        when (it) {
            is ZigIntValue -> type.fields.getOrNull(it.value)
            else -> null
        } ?: ZigUnknown.of(ZigTypeType)
    }
    return ZigPlace.Single(mutability, ZigUnknown.of(fieldType))
}

private fun findUnboundTypeParams(element: ZigElement): Map<ZigFnParam, ZigValue?> {
    return buildMap {
        var element: PsiElement? = element
        while (element != null) {
            if (element is ZigFnDeclOrProto) {
                for (paramDecl in element.parameters) {
                    if (ZigAnalyzer.createGenericParameterValue(paramDecl) != null) {
                        this[paramDecl] = null
                    }
                }
            }
            element = element.parent
        }
    }
}

private fun computeTypeParams(
    function: ZigFunctionType,
    arguments: Sequence<ZigValue?>,
): Map<ZigFnParam, ZigValue?> {
    if (function.typeParams.isEmpty()) {
        return emptyMap()
    }
    return function.typeParams.toMutableMap().apply {
        for ((paramDecl, argValues) in function.element.parameters.zip(arguments)) {
            if (ZigAnalyzer.createGenericParameterValue(paramDecl) != null) {
                this[paramDecl] = argValues
            }
        }
    }
}

private fun computeBuiltinFnGenericParams(
    element: ZigBuiltinFnProto,
    arguments: Sequence<ZigValue>,
): Map<ZigFnParam, ZigValue?> {
    return buildMap {
        for ((paramDecl, argValue) in element.parameters.zip(arguments)) {
            if (ZigAnalyzer.createGenericParameterValue(paramDecl) != null) {
                this[paramDecl] = argValue
            }
        }
    }
}

private fun substituteContainerTypeParams(
    type: ZigContainerType,
    typeParams: Map<ZigFnParam, ZigValue?>,
): ZigContainerType {
    return if (type.typeParams.isEmpty()) {
        type
    } else {
        // NOTE:
        //
        // We must also include generic parameters from earlier function calls.
        //
        // For example, suppose `Foo(comptime X: type)` calls `Bar(comptime Y: type)`
        // like so: `Bar(X)`.
        //
        // Given `Foo(u8)`, the process of resolving `Y` is as follows:
        //
        // 1. Since `Y` is a comptime param, we use `ZigContainerType.typeParams`
        //    to get its value, which is `X`.
        // 2. Since `X` is a comptime param, we use `ZigContainerType.typeParams`
        //    to get its value, which is `u8`.
        // 3. Since `u8` is a primitive type, we are done.
        //
        ZigContainerType(type.element, type.typeParams + typeParams)
    }
}

private fun substituteFunctionTypeParams(
    type: ZigFunctionType,
    typeParams: Map<ZigFnParam, ZigValue?>,
): ZigFunctionType {
    return if (type.typeParams.isEmpty()) {
        type
    } else {
        ZigFunctionType(type.element, type.typeParams + typeParams)
    }
}

private fun substituteTypeParams1(
    value: ZigValue,
    typeParams: Map<ZigFnParam, ZigValue?>,
): ZigValue {
    return ZigMultiValue.from(substituteTypeParams(value, typeParams).toSet())
}

private fun substituteTypeParams(
    value: ZigValue,
    typeParams: Map<ZigFnParam, ZigValue?>,
): Sequence<ZigValue> {
    return RecursionManager.doPreventingRecursion(value, true) {
        when (value) {
            is ZigUnknown -> {
                substituteTypeParams(value.typeOf, typeParams).map { ZigUnknown.of(it) }
            }
            is ZigMultiValue -> {
                sequenceOf(value.map { substituteTypeParams1(it, typeParams) })
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
                val arg = typeParams[value.element]
                if (arg == null) {
                    sequenceOf(value)
                } else {
                    sequenceOf(substituteTypeParams1(arg, typeParams))
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
                childTypes.map { value.with(sentinel = sentinel, child = it) }
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
                val containerType = substituteTypeParams1(value.container, typeParams)
                val fieldNameValue = substituteTypeParams1(value.fieldName, typeParams)
                sequenceOf(computeFieldType(containerType, fieldNameValue))
            }
            is ZigLazyFieldValue -> {
                val leftValue = substituteTypeParams1(value.left.value, typeParams)
                val leftPlace = ZigPlace.Single(value.left.mutability, leftValue)
                val fieldNameValue = substituteTypeParams1(value.fieldName, typeParams)
                sequenceOf(computeFieldPlace(leftPlace, fieldNameValue).value)
            }
            is ZigLazyTagNameValue -> {
                sequenceOf(computeTagNameValue(substituteTypeParams1(value.value, typeParams)))
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
): ZigPlace {
    val name = fieldName.value
    if (type.containerKind == ZigContainerKind.ENUM) {
        return ZigPlace.unknown()
    }
    val member = type.members.firstNotNullOfOrNull {
        if (it is ZigContainerField && it.name == name) it else null
    }
    if (member == null) {
        return ZigPlace.unknown()
    }
    var value = resolveValueFromReferenceTo(member)
    if (type.typeParams.isNotEmpty()) {
        value = substituteTypeParams1(value, type.typeParams)
    }
    return ZigPlace.Single(mutability, value)
}

private fun computeContainerTypeDeclAccess(
    type: IZigContainerType,
    fieldName: ZigStringLiteralValue,
): ZigPlace {
    val name = fieldName.value
    val member = type.members.firstNotNullOfOrNull {
        if (it is ZigNamedContainerMember && it.name == name) it else null
    }
    if (member == null) {
        return ZigPlace.unknown()
    }
    if (member is ZigContainerField) {
        return if (type.containerKind == ZigContainerKind.ENUM) {
            ZigPlace.constant(ZigEnumLiteralValue(type, name))
        } else if (type.isTaggedUnion) {
            val tagType = ZigAnalyzer.resolveUnionTagType(type) ?: return ZigPlace.unknown()
            ZigPlace.constant(ZigEnumLiteralValue(tagType, name))
        } else {
            ZigPlace.unknown()
        }
    }
    var value = resolveValueFromReferenceTo(member)
    if (type.typeParams.isNotEmpty()) {
        value = substituteTypeParams1(value, type.typeParams)
    }
    return when (member) {
        is ZigVarDecl -> ZigPlace.Single(member.mutability, value)
        else -> ZigPlace.constant(value)
    }
}

private fun resolveValueFromReferenceTo(target: ZigNamedElement): ZigValue {
    return ZigMultiValue.from(resolveValuesFromReferenceTo(target))
}

private fun resolveValuesFromReferenceTo(target: ZigNamedElement): Set<ZigValue> {
    return CachedValuesManager.getCachedValue(target) {
        RecursionManager.doPreventingRecursion(target, true) {
            val values = computeValuesFromReferenceUncached(target).toSet()
            CachedValueProvider.Result.create(
                values,
                PsiModificationTracker.MODIFICATION_COUNT,
                ZigBuiltinService.getModificationTracker(target.project),
                ZigBuiltinFunctionsService.getModificationTracker(target.project),
                ZigModulesProjectService.getModificationTracker(target.project),
            )
        }
    } ?: emptySet()
}

private fun computeValuesFromReferenceUncached(target: ZigNamedElement): Sequence<ZigValue> {
    return when (target) {
        is ZigFile -> sequenceOf(ZigContainerType(target, emptyMap()))
        is ZigFnDecl -> computeFnDecl(target)
        is ZigVarDecl -> computeVarDecl(target)
        is ZigVarProto -> computeVarProto(target)
        is ZigFnParam -> sequenceOf(computeFnParam(target))
        is ZigContainerFieldImpl -> computeContainerField(target)
        is ZigPayloadItem -> sequenceOf(computePayloadItem(target).value)
        is LightZigTupleField -> sequenceOf(ZigUnknown.of(target.container.fieldTypes[target.fieldIndex]))
        is ZigLabelDecl -> emptySequence()
    }
}

private fun computeFunctionReturnValue(
    type: ZigFunctionType,
    arguments: Sequence<ZigValue?>,
): ZigValue {
    val functionElement = type.element
    if (functionElement is ZigFnDecl
        && functionElement.containingFile.name == "meta.zig"
        && functionElement.containingFile.containingDirectory?.name == "std"
    ) {
        when (functionElement.name) {
            "Tag" -> {
                val value = arguments.firstOrNull()
                    ?: return ZigUnknown.of(ZigTypeType)
                return computeTagType(value) ?: ZigUnknown.of(ZigTypeType)
            }
            "FieldEnum" -> {
                val value = arguments.firstOrNull()
                    ?: return ZigUnknown.of(ZigTypeType)
                return ZigFieldEnumType(value)
            }
            else -> {}
        }
    }
    val returnTypeElement = functionElement.returnTypeElement?.expression
        ?: return ZigUnknown.ofUnknownType()
    val returnType = if (functionElement.bangTokenElement != null) {
        val errorSet = ZigUnknown.ofUnknownType() // TODO: infer error set
        resolveValue(returnTypeElement).map { ZigErrorUnionType(errorSet, it) }
    } else {
        resolveValue(returnTypeElement)
    }
    if (returnType == ZigTypeType) {
        val functionBodyElement = (functionElement as? ZigFnDecl)?.body
        if (functionBodyElement != null) {
            val typeParams = computeTypeParams(type, arguments)
            val traverser = SyntaxTraverser.psiTraverser(functionBodyElement).expand { it !is ZigContainerExpression }
            val values = traverser
                .asSequence()
                .filterIsInstance<ZigReturn>()
                .map { descendant ->
                    when (val returnValue = descendant.returnOperand) {
                        null -> ZigVoidValue
                        else -> {
                            var value = resolveValue(returnValue)
                            if (typeParams.isNotEmpty()) {
                                value = substituteTypeParams1(value, typeParams)
                            }
                            value
                        }
                    }
                }
            return ZigMultiValue.from(values.toSet())
        }
    }
    var value = returnType
    val typeParams = computeTypeParams(type, arguments)
    if (typeParams.isNotEmpty()) {
        value = substituteTypeParams1(value, typeParams)
    }
    return ZigUnknown.of(value)
}

private fun computeTagType(type: ZigValue): ZigValue? {
    return when (type) {
        is IZigContainerType -> {
            if (type.containerKind == ZigContainerKind.ENUM) {
                ZigAnalyzer.resolveEnumTagType(type)
            } else if (type.isTaggedUnion) {
                ZigAnalyzer.resolveUnionTagType(type)
            } else {
                null
            }
        }
        else -> null
    }
}

private fun mapCalledFuncAndArgs(
    callElement: ZigCall,
    transform: (ZigValue, Boolean, Sequence<ZigValue?>) -> ZigValue,
): ZigValue {
    val initialArgs = callElement.arguments.map { resolveValue(it) }.toList()
    return mapCalledFuncAndSelfArg(callElement) { function, selfArg ->
        if (selfArg != null) {
            val actualArgs = sequenceOf(selfArg) + initialArgs.asSequence()
            transform(function, true, actualArgs)
        } else {
            transform(function, false, initialArgs.asSequence())
        }
    }
}

internal fun mapCalledFuncAndSelfArg(
    callElement: ZigCall,
    transform: (ZigValue, ZigValue?) -> ZigValue,
): ZigValue {
    return when (val functionElement = callElement.functionExpression) {
        is ZigFieldAccess -> {
            val name = parseZigIdentifier(functionElement.identifierElement)
                ?: return ZigUnknown.ofUnknownType()
            val fieldName = ZigStringLiteralValue(name)
            resolveValue(functionElement.leftOperand).map { left ->
                when (left) {
                    is IZigContainerType -> {
                        val place = computeContainerTypeDeclAccess(left, fieldName)
                        transform(place.value, null)
                    }
                    else -> when (val typeOfLeft = left.typeOf) {
                        is IZigContainerType -> {
                            val place = computeContainerTypeDeclAccess(typeOfLeft, fieldName)
                            transform(place.value, left)
                        }
                        is ZigPointerType -> when (typeOfLeft.size) {
                            ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                                is IZigContainerType -> {
                                    val place = computeContainerTypeDeclAccess(pointerChild, fieldName)
                                    transform(place.value, left)
                                }
                                else -> ZigUnknown.ofUnknownType()
                            }
                            else -> ZigUnknown.ofUnknownType()
                        }
                        else -> ZigUnknown.ofUnknownType()
                    }
                }
            }
        }

        is ZigEnumLiteral -> {
            val name = parseZigIdentifier(functionElement.identifierElement)
                ?: return ZigUnknown.ofUnknownType()
            inferResultType(functionElement).map { originalType ->
                val type = ZigAnalyzer.findEnumLiteralContainerType(originalType)
                    ?: return@map ZigUnknown.ofUnknownType()
                val member = type.members.firstNotNullOfOrNull {
                    if (it is ZigNamedContainerDecl && it.name == name) it else null
                }
                if (member != null) {
                    var value = resolveValueFromReferenceTo(member)
                    value = substituteTypeParams1(value, type.typeParams)
                    transform(value, null)
                } else {
                    ZigUnknown.ofUnknownType()
                }
            }
        }

        else -> resolveValue(functionElement).map { transform(it, null) }
    }
}

private fun computeValuesFromLabeledBreaks(element: ZigLabelable): Sequence<ZigValue> {
    return getLabeledBreaksFor(element).map { resolveBreakValue(it) }
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
    return getUnlabeledBreaksFor(loop).map { resolveBreakValue(it) }
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

private fun resolveBreakValue(element: ZigBreak): ZigValue {
    return when (val breakValue = element.breakOperand) {
        null -> ZigVoidValue
        else -> resolveValue(breakValue)
    }
}

private fun inferResultType(childElement: ZigExpression): ZigValue {
    return when (val parentElement = childElement.parent) {
        is ZigVarDecl -> inferVarDecl(parentElement, childElement)
        is ZigContainerFieldImpl -> inferContainerField(parentElement, childElement)
        is ZigAssign -> inferAssign(parentElement, childElement)
        is ZigAssignDestructure -> inferAssignDestructure(parentElement, childElement)
        is ZigListInitItem -> inferListInitItem(parentElement, childElement)
        is ZigEqualEqual, is ZigBangEqual -> inferEqual(parentElement, childElement)
        is ZigSwitchItem -> inferSwitchItem(parentElement, childElement)
        is ZigSwitchProng -> inferSwitchProng(parentElement, childElement)
        is ZigThenElement -> inferThen(parentElement, childElement)
        is ZigElseElement -> inferElse(parentElement, childElement)
        is ZigOrelse -> inferOrelse(parentElement, childElement)
        is ZigCatch -> inferCatch(parentElement, childElement)
        is ZigAddressOf -> inferAddressOf(parentElement, childElement)
        is ZigReturn -> inferReturn(parentElement, childElement)
        is ZigBreak -> inferBreak(parentElement, childElement)
        is ZigContinue -> inferContinue(parentElement, childElement)
        is ZigComptime -> inferComptime(parentElement, childElement)
        is ZigCall -> inferCall(parentElement, childElement)
        is ZigCallArg -> inferCallArg(parentElement, childElement)
        else -> ZigUnknown.of(ZigTypeType)
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
    typeParams: Map<ZigFnParam, ZigValue?>,
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
    typeParams: Map<ZigFnParam, ZigValue?>,
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
    typeParams: Map<ZigFnParam, ZigValue?>,
    truncate: Boolean,
): HtmlChunk {
    val genericParam = ZigAnalyzer.createGenericParameterValue(param)
    if (genericParam != null) {
        val genericArg = substituteTypeParams1(genericParam, typeParams)
        return genericArg.toHtmlChunk(truncate)
    }
    return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
}

private fun computePayloadItemPlace(payloadItem: ZigPayloadItem): ZigPlace {
    return when (val payloadElement = payloadItem.containingPayload) {
        is ZigThenPayload -> computeThenPayload(payloadElement)
        is ZigElsePayload -> computeElsePayload(payloadElement)
        is ZigCatchPayload -> computeCatchPayload(payloadElement)
        is ZigSwitchPayload -> computeSwitchPayload(payloadElement, payloadItem)
        is ZigForPayload -> computeForPayload(payloadElement, payloadItem)
    }
}

private fun computeFieldType(
    containerType: ZigValue,
    fieldNameValue: ZigValue,
): ZigValue = containerType.map { container ->
    fieldNameValue.map { fieldName ->
        if (container is IZigContainerType
            && (container.containerKind == ZigContainerKind.STRUCT
                    || container.containerKind == ZigContainerKind.UNION)
            && fieldName is ZigStringLiteralValue
        ) {
            val field = container.members.firstNotNullOfOrNull {
                if (it is ZigContainerField && it.name == fieldName.value) it else null
            }
            if (field != null) {
                resolveValueFromReferenceTo(field).typeOf
            } else {
                ZigLazyFieldType(container, fieldName)
            }
        } else {
            ZigLazyFieldType(container, fieldName)
        }
    }
}

private fun computeFieldPlace(
    leftPlace: ZigPlace,
    fieldName: ZigValue,
): ZigPlace =
    leftPlace.map { computeFieldAccess(it, fieldName) }

fun computeTagNameValue(value: ZigValue): ZigValue {
    return value.map {
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

private fun computeArrayAccess(left: ZigPlace, indexExpression: ZigExpression?): ZigPlace {
    return when (val typeOfLeft = left.value.typeOf) {
        is ZigArrayType -> ZigPlace.Single(left.mutability, ZigUnknown.of(typeOfLeft.child))
        is ZigVectorType -> ZigPlace.Single(left.mutability, ZigUnknown.of(typeOfLeft.child))
        is ZigTupleType -> computeArrayAccessOnTuple(left.mutability, typeOfLeft, indexExpression)
        is ZigPointerType -> when (typeOfLeft.size) {
            ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                is ZigArrayType -> ZigPlace.Single(typeOfLeft.mutability, ZigUnknown.of(pointerChild.child))
                is ZigVectorType -> ZigPlace.Single(typeOfLeft.mutability, ZigUnknown.of(pointerChild.child))
                is ZigTupleType -> computeArrayAccessOnTuple(typeOfLeft.mutability, pointerChild, indexExpression)
                else -> ZigPlace.unknown()
            }
            else -> ZigPlace.Single(typeOfLeft.mutability, ZigUnknown.of(typeOfLeft.child))
        }
        else -> ZigPlace.unknown()
    }
}

private fun computeIdentifier(element: ZigIdentifier): ZigPlace {
    val text = element.text
    val primitiveValue = toPrimitive(text)
    if (primitiveValue != null) {
        return ZigPlace.constant(primitiveValue)
    }
    val places = resolveReferences(element).map { target ->
        val value = when (target) {
            is ZigNamedElement -> resolveValueFromReferenceTo(target)
            else -> ZigUnknown.ofUnknownType()
        }
        when (target) {
            is ZigVarDeclOrProto -> ZigPlace.Single(target.mutability, value)
            else -> ZigPlace.constant(value)
        }
    }
    return ZigPlace.Multi.from(places.toSet())
}

private fun computeFieldAccess(element: ZigFieldAccess): ZigPlace {
    val name = parseZigIdentifier(element.identifierElement) ?: return ZigPlace.unknown()
    val left = resolvePlace(element.leftOperand)
    val fieldName = ZigStringLiteralValue(name)
    return computeFieldAccess(left, fieldName)
}

private fun computeArrayAccess(element: ZigArrayAccess): ZigPlace {
    val left = resolvePlace(element.leftOperand)
    val indexExpression = element.indexElement?.expression
    return computeArrayAccess(left, indexExpression)
}

private fun computeGroupedExpression(element: ZigGroupedExpression): ZigPlace {
    val innerExpression = element.innerExpression ?: return ZigPlace.unknown()
    return resolvePlace(innerExpression)
}

private fun computeUnwrapOptional(element: ZigUnwrapOptional): ZigPlace {
    val unionElement = getUnwrapUnionTagTypeArgument(element)
    if (unionElement != null) {
        val tagType = resolveValue(unionElement).map {
            when (it) {
                is ZigContainerType -> ZigAnalyzer.resolveUnionTagType(it)
                else -> null
            } ?: ZigUnknown.of(ZigTypeType)
        }
        return ZigPlace.constant(tagType)
    }
    val operandElement = element.operand ?: return ZigPlace.unknown()
    return resolvePlace(operandElement).map { operand ->
        when (val typeOfOperand = operand.value.typeOf) {
            is ZigOptionalType -> ZigPlace.Single(operand.mutability, ZigUnknown.of(typeOfOperand.child))
            else -> ZigPlace.unknown()
        }
    }
}

private fun computeTry(element: ZigTry): ZigPlace {
    val operandElement = element.operand ?: return ZigPlace.unknown()
    return resolvePlace(operandElement).map { operand ->
        when (val typeOfOperand = operand.value.typeOf) {
            is ZigErrorUnionType -> ZigPlace.Single(operand.mutability, ZigUnknown.of(typeOfOperand.payload))
            else -> ZigPlace.unknown()
        }
    }
}

private fun computeDeref(element: ZigDeref): ZigPlace {
    val operandElement = element.operand ?: return ZigPlace.unknown()
    return resolvePlace(operandElement).map { operand ->
        when (val typeOfOperand = operand.value.typeOf) {
            is ZigPointerType -> when (typeOfOperand.size) {
                ZigPointerSize.ONE,
                ZigPointerSize.C -> ZigPlace.Single(typeOfOperand.mutability, ZigUnknown.of(typeOfOperand.child))
                ZigPointerSize.SLICE,
                ZigPointerSize.MANY -> ZigPlace.unknown()
            }
            else -> ZigPlace.unknown()
        }
    }
}

private fun computeFieldBuiltinCall(element: ZigBuiltinCall): ZigPlace {
    val arguments = element.arguments.toList()
    if (arguments.size < 2) {
        return ZigPlace.unknown()
    }
    val (leftElement, fieldNameElement) = arguments
    val leftPlace = resolvePlace(leftElement)
    val fieldNameValue = resolveValue(fieldNameElement)
    return computeFieldPlace(leftPlace, fieldNameValue)
}

private fun computeContainerExpression(element: ZigContainerExpression): ZigValue {
    run tuple@{
        if (element.containerKind != ZigContainerKind.STRUCT) {
            return@tuple
        }
        val fields = mutableListOf<ZigValue>()
        for (member in element.members) {
            if (member !is ZigContainerField) {
                return@tuple
            }
            if (member.nameIdentifier != null) {
                return@tuple
            }
            val types = resolveValuesFromReferenceTo(member).asSequence().map { it.typeOf }.toSet()
            // For efficiency, limit each field to a single possible type.
            fields.add(types.singleOrNull() ?: ZigUnknown.of(ZigTypeType))
        }
        if (fields.isEmpty()) {
            return@tuple
        }
        val lightTuple = LightZigTuple(element.manager, fields)
        lightTuple.navigationElement = element
        for ((i, member) in element.members.withIndex()) {
            lightTuple.fields[i].navigationElement = member
        }
        return ZigTupleType(lightTuple)
    }
    return ZigContainerType(element, findUnboundTypeParams(element))
}

private fun computeOptionalTypeExpression(element: ZigOptionalTypeExpression): ZigValue {
    val child = resolveValue(element.childExpression, ZigTypeType)
    return ZigOptionalType(child)
}

private fun computeErrorSet(element: ZigErrorSet): ZigValue {
    val names = sortedSetOf<String>()
    for (item in element.setItems) {
        val name = parseZigIdentifier(item.identifierElement) ?: return ZigUnknown.of(ZigTypeType)
        names.add(name)
    }
    return ZigErrorSetType(names)
}

private fun computeErrorValueExpression(element: ZigErrorValueExpression): ZigValue {
    val name = parseZigIdentifier(element.identifierElement) ?: return ZigUnknown.ofUnknownType()
    val type = ZigErrorSetType(setOf(name))
    return ZigErrorSetValue(type, name)
}

private fun computeErrorUnion(element: ZigErrorUnion): ZigValue {
    val errorSet = resolveValue(element.errorSet, ZigTypeType)
    val payload = resolveValue(element.payload, ZigTypeType)
    return ZigErrorUnionType(errorSet, payload)
}

private fun computeArrayTypeExpression(element: ZigArrayTypeExpression): ZigValue {
    val length = resolveValue(element.lengthElement.expression, ZigUsizeType)
    val sentinel = element.sentinelElement?.let { resolveValue(it.expression) }
    val child = resolveValue(element.childExpression, ZigTypeType)
    return ZigArrayType(length, sentinel, child)
}

private fun computePointerTypeExpression(element: ZigPointerTypeExpression): ZigValue {
    val size = element.pointerSize
    val sentinel = element.sentinelElement?.let { resolveValue(it.expression) }
    val mutability = element.mutability
    val child = resolveValue(element.childExpression, ZigTypeType)
    return ZigPointerType(size, sentinel, mutability, child)
}

private fun computeComptime(element: ZigComptime): ZigValue {
    return resolveValue(element.innerExpression)
}

private fun computeAddressOf(element: ZigAddressOf): ZigPlace {
    val operandElement = element.operand ?: return ZigPlace.constant(ZigUnknown.ofUnknownType())
    return resolvePlace(operandElement).map {
        ZigPlace.constant(ZigUnknown.of(ZigPointerType(ZigPointerSize.ONE, null, it.mutability, it.value.typeOf)))
    }
}

private fun computeIf(element: ZigIf): ZigValue {
    val thenValue = resolveValue(element.thenElement.expression)
    val elseValue = when (val outerElement = element.elseElement) {
        null -> ZigVoidValue
        else -> resolveValue(outerElement.expression)
    }
    return ZigMultiValue.from(resolvePeerValues(setOf(thenValue, elseValue)))
}

private fun computeCatch(element: ZigCatch): ZigValue {
    val leftValue = resolveValue(element.leftOperand).map { left ->
        when (val typeOfLeft = left.typeOf) {
            is ZigErrorUnionType -> ZigUnknown.of(typeOfLeft.payload)
            else -> ZigUnknown.ofUnknownType()
        }
    }
    val rightValue = resolveValue(element.rightOperand)
    return ZigMultiValue.from(resolvePeerValues(setOf(leftValue, rightValue)))
}

private fun computeOrelse(element: ZigOrelse): ZigValue {
    val leftValue = resolveValue(element.leftOperand).map { left ->
        when (val typeOfLeft = left.typeOf) {
            is ZigOptionalType -> ZigUnknown.of(typeOfLeft.child)
            else -> ZigUnknown.ofUnknownType()
        }
    }
    val rightValue = resolveValue(element.rightOperand)
    return ZigMultiValue.from(resolvePeerValues(setOf(leftValue, rightValue)))
}

private fun computeMergeErrorSets(element: ZigMergeErrorSets): ZigValue {
    val leftType = resolveValue(element.leftOperand, ZigTypeType)
    val rightType = resolveValue(element.rightOperand, ZigTypeType)
    return leftType.map { left ->
        when (left) {
            is ZigErrorSetType -> rightType.map { right ->
                when (right) {
                    is ZigErrorSetType -> {
                        val names = left.names + right.names
                        ZigErrorSetType(names.toSortedSet())
                    }
                    else -> ZigUnknown.of(ZigTypeType)
                }
            }
            else -> ZigUnknown.of(ZigTypeType)
        }
    }
}

private fun computeListInit(element: ZigListInit): ZigValue {
    val typeElement = element.typeExpression
    if (typeElement != null) {
        return ZigUnknown.of(resolveValue(typeElement, ZigTypeType))
    }
    val fieldTypes = mutableListOf<ZigValue>()
    for (item in element.initItems) {
        if (item.isStructField) {
            return ZigUnknown.ofUnknownType() // TODO
        }
        fieldTypes.add(resolveValue(item.expression).typeOf)
    }
    return ZigUnknown.of(ZigTupleType(LightZigTuple(element.manager, fieldTypes)))
}

private fun computeSlice(element: ZigSlice): ZigPlace {
    // TODO: if the indexes are non-comptime, return a slice instead of an array of unknown length
    val length = ZigUnknown.of(ZigUsizeType)
    val sentinel = element.sentinelElement?.let { resolveValue(it.expression) }
    return resolvePlace(element.sliced).map { sliced ->
        val (mutability, childType) = when (val typeOfSliced = sliced.value.typeOf) {
            is ZigArrayType -> Pair(sliced.mutability, typeOfSliced.child)

            is ZigPointerType -> when (typeOfSliced.size) {
                ZigPointerSize.ONE -> when (val pointerChild = typeOfSliced.child) {
                    is ZigArrayType -> Pair(typeOfSliced.mutability, pointerChild.child)
                    else -> return@map ZigPlace.constant(ZigUnknown.ofUnknownType()) // TODO
                }

                ZigPointerSize.MANY,
                ZigPointerSize.SLICE,
                ZigPointerSize.C -> Pair(typeOfSliced.mutability, typeOfSliced.child)
            }

            else -> return@map ZigPlace.constant(ZigUnknown.ofUnknownType())
        }
        val arrayType = ZigArrayType(length, sentinel, childType)
        val pointerType = ZigPointerType(ZigPointerSize.ONE, null, mutability, arrayType)
        ZigPlace.constant(ZigUnknown.of(pointerType))
    }
}

private fun computeBuiltinCall(element: ZigBuiltinCall): ZigValue {
    return when (val builtinName = element.builtinElement.text) {
        "@constCast" -> computeConstCastBuiltinCall(element)
        "@EnumLiteral" -> ZigEnumLiteralType
        "@FieldType" -> computeFieldTypeBuiltinCall(element)
        "@import" -> computeImportBuiltinCall(element)
        "@tagName" -> computeTagNameBuiltinCall(element)
        "@This" -> computeThisBuiltinCall(element)
        "@TypeOf" -> computeTypeOfBuiltinCall(element)
        "@Vector" -> computeVectorBuiltinCall(element)
        else -> {
            val builtin = ZigBuiltinFunctionsService.getBuiltinFnProto(element.project, builtinName)
                ?: return ZigUnknown.ofUnknownType()
            val type = resolveValue(builtin.returnTypeElement?.expression, ZigTypeType)
            val arguments = element.arguments.map { resolveValue(it) }
            val typeParams = computeBuiltinFnGenericParams(builtin, arguments)
            ZigUnknown.of(substituteTypeParams1(type, typeParams))
        }
    }
}

private fun computeConstCastBuiltinCall(element: ZigBuiltinCall): ZigValue {
    return resolveValue(element.arguments.firstOrNull()).map { value ->
        when (val type = value.typeOf) {
            is ZigPointerType -> ZigUnknown.of(type.with(mutability = ZigMutability.VAR))
            else -> ZigUnknown.ofUnknownType()
        }
    }
}

private fun computeFieldTypeBuiltinCall(element: ZigBuiltinCall): ZigValue {
    val arguments = element.arguments.toList()
    if (arguments.size < 2) {
        return ZigUnknown.ofUnknownType()
    }
    val (containerElement, fieldNameElement) = arguments
    val containerType = resolveValue(containerElement)
    val fieldNameValue = resolveValue(fieldNameElement)
    return computeFieldType(containerType, fieldNameValue)
}

private fun computeImportBuiltinCall(element: ZigBuiltinCall): ZigValue {
    val argument = element.arguments.firstOrNull()
    if (argument !is ZigStringLiteral) {
        return ZigUnknown.ofUnknownType()
    }
    val values = resolveReferences(argument).map { target ->
        when (target) {
            is ZigFile -> ZigContainerType(target, emptyMap())
            else -> ZigUnknown.ofUnknownType()
        }
    }
    return ZigMultiValue.from(values.toSet())
}

private fun computeTagNameBuiltinCall(element: ZigBuiltinCall): ZigValue {
    return computeTagNameValue(resolveValue(element.arguments.firstOrNull()))
}

private fun computeThisBuiltinCall(element: ZigBuiltinCall): ZigValue {
    val container = element.findParentOfType<ZigContainer>() ?: return ZigUnknown.of(ZigTypeType)
    return ZigContainerType(container, findUnboundTypeParams(container))
}

private fun computeTypeOfBuiltinCall(element: ZigBuiltinCall): ZigValue {
    val arguments = element.arguments.toList()
    return if (arguments.size == 1) {
        resolveValue(arguments[0]).typeOf
    } else {
        val values = arguments.map { resolveValue(it) }.toSet()
        ZigMultiValue.from(resolvePeerValues(values)).typeOf
    }
}

private fun computeVectorBuiltinCall(element: ZigBuiltinCall): ZigValue {
    val arguments = element.arguments.toList()
    if (arguments.size < 2) {
        return ZigUnknown.of(ZigTypeType)
    }
    val (lengthExpression, childExpression) = arguments
    val length = resolveValue(lengthExpression, ZigComptimeIntType)
    val child = resolveValue(childExpression)
    return ZigVectorType(length, child)
}

private fun computeCall(element: ZigCall): ZigValue {
    return mapCalledFuncAndArgs(element) { function, _, arguments ->
        when (val typeOfFunction = function.typeOf) {
            is ZigFunctionType -> computeFunctionReturnValue(typeOfFunction, arguments)
            else -> ZigUnknown.ofUnknownType()
        }
    }
}

private fun computeBlock(element: ZigBlock): ZigValue {
    val values = computeValuesFromLabeledBreaks(element).toSet()
    if (values.isEmpty()) return ZigVoidValue
    return ZigMultiValue.from(resolvePeerValues(values))
}

private fun computeSwitch(element: ZigSwitch): ZigValue {
    val values = buildSet {
        addAll(computeValuesFromLabeledBreaks(element))
        addAll(element.prongs.map { resolveValue(it.expression) })
    }
    return ZigMultiValue.from(resolvePeerValues(values))
}

private fun computeLoop(element: ZigLoop): ZigValue {
    val values = buildSet {
        addAll(computeValuesFromLabeledBreaks(element))
        addAll(computeValuesFromUnlabeledBreaks(element))
        add(element.elseElement?.let { resolveValue(it.expression) } ?: ZigVoidValue)
    }
    return ZigMultiValue.from(resolvePeerValues(values))
}

private fun computeStringExpression(element: ZigStringExpression): ZigValue {
    val value = element.value
    if (value != null) {
        return ZigStringLiteralValue(value)
    }
    val length = ZigUnknown.of(ZigUsizeType)
    val u8 = ZigFixedIntType(ZigSignedness.UNSIGNED, 8)
    val array = ZigArrayType(length, ZigIntValue(ZigComptimeIntType, 0), u8)
    val pointer = ZigPointerType(ZigPointerSize.ONE, null, ZigMutability.CONST, array)
    return ZigUnknown.of(pointer)
}

private fun computeNumberLiteral(element: ZigNumberLiteral): ZigValue {
    return when (val result = ZigNumberLiteralParser.parse(element.text)) {
        is ZigNumberLiteralParser.IntResult -> ZigIntValue(ZigComptimeIntType, result.value)
        is ZigNumberLiteralParser.BigIntResult -> ZigUnknown.of(ZigComptimeIntType)
        is ZigNumberLiteralParser.FloatResult -> ZigUnknown.of(ZigComptimeFloatType)
        ZigNumberLiteralParser.FailureResult -> ZigUnknown.ofUnknownType()
    }
}

private fun computeEnumLiteral(element: ZigEnumLiteral): ZigValue {
    val name = parseZigIdentifier(element.identifierElement)
    return if (name != null) {
        ZigEnumLiteralValue(ZigEnumLiteralType, name)
    } else {
        ZigUnknown.of(ZigEnumLiteralType)
    }
}

private fun computeFnDecl(target: ZigFnDecl): Sequence<ZigValue> {
    val typeParams = findUnboundTypeParams(target)
    return sequenceOf(ZigUnknown.of(ZigFunctionType(target, typeParams)))
}

private fun computeVarDecl(target: ZigVarDecl): Sequence<ZigValue> {
    val typeElement = target.typeElement?.expression
    if (typeElement != null) {
        return resolveValues(typeElement).asSequence().map { ZigUnknown.of(it) }
    }
    val initElement = target.initializer ?: return sequenceOf(ZigUnknown.ofUnknownType())
    return when (target.mutability) {
        ZigMutability.CONST -> resolveValues(initElement).asSequence()
        ZigMutability.VAR -> resolveValues(initElement).asSequence().map { ZigUnknown.of(it.typeOf) }
    }
}

private fun computeVarProto(target: ZigVarProto): Sequence<ZigValue> {
    val typeElement = target.typeElement?.expression
    if (typeElement != null) {
        return resolveValues(typeElement).asSequence().map { ZigUnknown.of(it) }
    }
    val destructure = target.containingAssignDestructure
    val initElement = destructure.initializer ?: return sequenceOf(ZigUnknown.ofUnknownType())
    val destructureIndex = destructure.variables.indexOf(target)
    assert(destructureIndex >= 0)
    return resolveValues(initElement).asSequence().map { init ->
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

private fun computeFnParam(target: ZigFnParam): ZigValue {
    val genericParam = ZigAnalyzer.createGenericParameterValue(target)
    return genericParam ?: ZigUnknown.of(resolveValue(target.typeElement?.expression, ZigTypeType))
}

private fun computeContainerField(target: ZigContainerFieldImpl): Sequence<ZigValue> {
    return when (val typeElement = target.typeElement?.expression) {
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

private fun computePayloadItem(target: ZigPayloadItem): ZigPlace {
    return if (target.isPointer) {
        computePayloadItemPlace(target).map {
            ZigPlace.constant(ZigUnknown.of(ZigPointerType(ZigPointerSize.ONE, null, it.mutability, it.value.typeOf)))
        }
    } else {
        computePayloadItemPlace(target).map {
            ZigPlace.constant(it.value)
        }
    }
}

private fun computeThenPayload(payloadElement: ZigThenPayload): ZigPlace {
    val conditionElement =
        payloadElement.containingIf?.conditionElement?.expression
            ?: payloadElement.containingWhile?.conditionElement?.expression
            ?: return ZigPlace.unknown()
    return resolvePlace(conditionElement).map { condition ->
        when (val typeOfCondition = condition.value.typeOf) {
            is ZigOptionalType -> ZigPlace.Single(condition.mutability, ZigUnknown.of(typeOfCondition.child))
            is ZigErrorUnionType -> ZigPlace.Single(condition.mutability, ZigUnknown.of(typeOfCondition.payload))
            else -> ZigPlace.unknown()
        }
    }
}

private fun computeElsePayload(payloadElement: ZigElsePayload): ZigPlace {
    val conditionElement =
        payloadElement.containingIf?.conditionElement?.expression
            ?: payloadElement.containingWhile?.conditionElement?.expression
            ?: return ZigPlace.unknown()
    return resolvePlace(conditionElement).map { condition ->
        when (val typeOfCondition = condition.value.typeOf) {
            is ZigErrorUnionType -> ZigPlace.Single(condition.mutability, ZigUnknown.of(typeOfCondition.errorSet))
            else -> ZigPlace.unknown()
        }
    }
}

private fun computeCatchPayload(payloadElement: ZigCatchPayload): ZigPlace {
    val leftElement = payloadElement.containingCatch.leftOperand
    return resolvePlace(leftElement).map { left ->
        when (val typeOfLeft = left.value.typeOf) {
            is ZigErrorUnionType -> ZigPlace.Single(left.mutability, ZigUnknown.of(typeOfLeft.errorSet))
            else -> ZigPlace.unknown()
        }
    }
}

private fun computeSwitchPayload(payloadElement: ZigSwitchPayload, payloadItem: ZigPayloadItem): ZigPlace {
    return when (payloadElement.items.indexOf(payloadItem)) {
        0 -> {
            val switchProng = payloadElement.containingSwitchProng
            val switchElement = switchProng.containingSwitch
            val conditionElement = switchElement.conditionElement.expression
                ?: return ZigPlace.unknown()

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

            resolvePlace(conditionElement).map { condition ->
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
                                resolvePeerValues(fieldValues.toSet()).asSequence()
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
                ZigPlace.Single(condition.mutability, ZigMultiValue.from(values.toSet()))
            }
        }
        1 -> {
            val switchProng = payloadElement.containingSwitchProng
            val switchElement = switchProng.containingSwitch
            val conditionElement = switchElement.conditionElement.expression
                ?: return ZigPlace.unknown()
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
            resolvePlace(conditionElement).map { condition ->
                val conditionType = condition.value.typeOf
                val values = if (conditionType is IZigContainerType && conditionType.isTaggedUnion) {
                    val tagType = ZigAnalyzer.resolveUnionTagType(conditionType) ?: return@map ZigPlace.unknown()
                    if (isInlineProng) {
                        if (isElseProng) {
                            sequenceOf(tagType.map { type ->
                                when (type) {
                                    is IZigContainerType if type.containerKind == ZigContainerKind.ENUM -> {
                                        val values =
                                            type.members
                                                .filterIsInstance<ZigContainerField>()
                                                .mapNotNull { it.name }
                                                .filter { it !in otherEnumLiterals }
                                                .map { ZigEnumLiteralValue(type, it) }
                                        ZigMultiValue.from(values.toSet(), type)
                                    }
                                    else -> ZigUnknown.of(type)
                                }
                            })
                        } else {
                            sequenceOf(tagType.map { type ->
                                val values = prongEnumLiterals.map { ZigEnumLiteralValue(type, it) }
                                ZigMultiValue.from(values.toSet(), type)
                            })
                        }
                    } else {
                        sequenceOf(tagType.map { ZigUnknown.of(it) })
                    }
                } else {
                    emptySequence()
                }
                ZigPlace.constant(ZigMultiValue.from(values.toSet()))
            }
        }
        else -> ZigPlace.unknown()
    }
}

private fun computeForPayload(payloadElement: ZigForPayload, payloadItem: ZigPayloadItem): ZigPlace {
    val payloadIndex = payloadElement.items.indexOf(payloadItem)
    val forInputs = payloadElement.containingFor.inputs
    val inputElement = forInputs.elementAtOrNull(payloadIndex)?.innerElement
        ?: return ZigPlace.unknown()
    if (inputElement is ZigForRange) {
        return if (payloadItem.isPointer) {
            ZigPlace.unknown()
        } else {
            ZigPlace.Single(null, ZigUnknown.of(ZigUsizeType))
        }
    }
    if (inputElement !is ZigExpression) {
        return ZigPlace.unknown()
    }
    return resolvePlace(inputElement).map { input ->
        when (val typeOfInput = input.value.typeOf) {
            is ZigArrayType -> {
                if (payloadItem.isPointer) {
                    ZigPlace.unknown()
                } else {
                    ZigPlace.Single(null, ZigUnknown.of(typeOfInput.child))
                }
            }
            is ZigPointerType -> when (typeOfInput.size) {
                ZigPointerSize.SLICE -> ZigPlace.Single(typeOfInput.mutability, ZigUnknown.of(typeOfInput.child))
                ZigPointerSize.ONE -> when (val pointerChild = typeOfInput.child) {
                    is ZigArrayType -> ZigPlace.Single(typeOfInput.mutability, ZigUnknown.of(pointerChild.child))
                    else -> ZigPlace.unknown()
                }
                else -> ZigPlace.unknown()
            }
            else -> ZigPlace.unknown()
        }
    }
}

private fun inferVarDecl(
    parentElement: ZigVarDecl,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.initializer !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    return resolveValue(parentElement.typeElement?.expression, ZigTypeType)
}

private fun inferContainerField(
    parentElement: ZigContainerFieldImpl,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.initializer !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    return resolveValue(parentElement.typeElement?.expression, ZigTypeType)
}

private fun inferAssign(
    parentElement: ZigAssign,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.rightExpression !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    return resolveValue(parentElement.leftExpression).typeOf
}

private fun inferAssignDestructure(
    parentElement: ZigAssignDestructure,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.initializer !== childElement) {
        return ZigUnknown.of(ZigTypeType)
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
    return ZigTupleType(LightZigTuple(childElement.manager, fields.toList()))
}

private fun inferListInitItem(
    parentElement: ZigListInitItem,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.expression !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val initElement = parentElement.containingListInit
    return if (parentElement.isStructField) {
        val name = parseZigIdentifier(parentElement.identifierElement)
            ?: return ZigUnknown.of(ZigTypeType)
        ZigAnalyzer.resolveOrInferListInitType(initElement).map { type ->
            if (type !is IZigContainerType) {
                return@map ZigUnknown.of(ZigTypeType)
            }
            val member = type.members.firstNotNullOfOrNull {
                if (it is ZigContainerField && it.name == name) it else null
            }
            if (member != null) {
                var value = resolveValueFromReferenceTo(member).typeOf
                value = substituteTypeParams1(value, type.typeParams)
                value
            } else {
                ZigUnknown.of(ZigTypeType)
            }
        }
    } else {
        ZigAnalyzer.resolveOrInferListInitType(initElement).map { type ->
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

private fun inferEqual(
    parentElement: ZigBinaryExpression,
    childElement: ZigExpression,
): ZigValue {
    val otherElement = when (childElement) {
        parentElement.leftOperand -> parentElement.rightOperand
        parentElement.rightOperand -> parentElement.leftOperand
        else -> null
    }
    if (otherElement == null) {
        return ZigUnknown.of(ZigTypeType)
    }
    return resolveValue(otherElement).typeOf
}

private fun inferSwitchItem(
    parentElement: ZigSwitchItem,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.expression !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val switchElement = parentElement.containingSwitchProng.containingSwitch
    val conditionElement = switchElement.conditionElement.expression
        ?: return ZigUnknown.of(ZigTypeType)
    return resolveValue(conditionElement).map {
        val type = it.typeOf
        if (type is IZigContainerType && type.isTaggedUnion) {
            val tagType = ZigAnalyzer.resolveUnionTagType(type) ?: return@map ZigUnknown.of(ZigTypeType)
            tagType
        } else {
            type
        }
    }
}

private fun inferSwitchProng(
    parentElement: ZigSwitchProng,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.expression !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val types = mutableSetOf<ZigValue>()
    types += inferResultType(parentElement.containingSwitch)
    for (prong in parentElement.containingSwitch.prongs) {
        if (prong === parentElement) continue
        types += resolveValue(prong.expression).typeOf
    }
    return ZigMultiValue.from(types, ZigTypeType)
}

private fun inferThen(
    parentElement: ZigThenElement,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.expression !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val grandparentElement = parentElement.parent
    if (grandparentElement !is ZigExpression) {
        return ZigUnknown.of(ZigTypeType)
    }
    val types = mutableSetOf<ZigValue>()
    types += inferResultType(grandparentElement)
    if (grandparentElement is ZigIf) {
        types += resolveValue(grandparentElement.elseElement?.expression).typeOf
    }
    return ZigMultiValue.from(types, ZigTypeType)
}

private fun inferElse(
    parentElement: ZigElseElement,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.expression !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val grandparentElement = parentElement.parent
    if (grandparentElement !is ZigExpression) {
        return ZigUnknown.of(ZigTypeType)
    }
    val types = mutableSetOf<ZigValue>()
    types += inferResultType(grandparentElement)
    if (grandparentElement is ZigIf) {
        types += resolveValue(grandparentElement.thenElement.expression).typeOf
    }
    return ZigMultiValue.from(types, ZigTypeType)
}

private fun inferOrelse(
    parentElement: ZigOrelse,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.rightOperand !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val types = mutableSetOf<ZigValue>()
    types += inferResultType(parentElement)
    types += resolveValue(parentElement.leftOperand).map { left ->
        when (val typeOfLeft = left.typeOf) {
            is ZigOptionalType -> typeOfLeft.child
            else -> ZigUnknown.of(ZigTypeType)
        }
    }
    return ZigMultiValue.from(types, ZigTypeType)
}

private fun inferCatch(
    parentElement: ZigCatch,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.rightOperand !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val types = mutableSetOf<ZigValue>()
    types += inferResultType(parentElement)
    types += resolveValue(parentElement.leftOperand).map { left ->
        when (val typeOfLeft = left.typeOf) {
            is ZigErrorUnionType -> typeOfLeft.payload
            else -> ZigUnknown.of(ZigTypeType)
        }
    }
    return ZigMultiValue.from(types, ZigTypeType)
}

private fun inferAddressOf(
    parentElement: ZigAddressOf,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.operand !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    return inferResultType(parentElement).map {
        when (it) {
            is ZigPointerType -> when (it.size) {
                ZigPointerSize.ONE -> it.child

                ZigPointerSize.SLICE -> {
                    val length = ZigUnknown.of(ZigUsizeType)
                    ZigArrayType(length, null, it.child)
                }

                ZigPointerSize.MANY,
                ZigPointerSize.C -> ZigUnknown.of(ZigTypeType)
            }
            else -> ZigUnknown.of(ZigTypeType)
        }
    }
}

private fun inferReturn(
    parentElement: ZigReturn,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.returnOperand !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val fnDecl = childElement.findParentOfType<ZigFnDecl>()
        ?: return ZigUnknown.of(ZigTypeType)
    val typeElement = fnDecl.returnTypeElement?.expression
        ?: return ZigUnknown.of(ZigTypeType)
    return if (fnDecl.bangTokenElement != null) {
        val errorSet = ZigUnknown.of(ZigTypeType)
        resolveValue(typeElement).map { ZigErrorUnionType(errorSet, it) }
    } else {
        resolveValue(typeElement)
    }
}

private fun inferBreak(
    parentElement: ZigBreak,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.breakOperand !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val labelElement = parentElement.labelRef?.identifierElement
    val breakable = if (labelElement != null) {
        val label = parseZigIdentifier(labelElement)
            ?: return ZigUnknown.of(ZigTypeType)
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
        return ZigUnknown.of(ZigTypeType)
    }
    val types = mutableSetOf<ZigValue>()
    types += inferResultType(breakable)
    var breakElements = getLabeledBreaksFor(breakable)
    if (breakable is ZigLoop) {
        breakElements += getUnlabeledBreaksFor(breakable)
    }
    for (b in breakElements) {
        if (b === parentElement) continue
        types += resolveBreakValue(b).typeOf
    }
    return ZigMultiValue.from(types, ZigTypeType)
}

private fun inferContinue(
    parentElement: ZigContinue,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.continueOperand !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val label = parseZigIdentifier(parentElement.labelRef?.identifierElement)
        ?: return ZigUnknown.of(ZigTypeType)
    val labelable = parentElement.findParentInFile {
        it is ZigLabelable && label == parseZigIdentifier(it.labelDecl?.identifierElement)
    }
    if (labelable !is ZigSwitch) {
        return ZigUnknown.of(ZigTypeType)
    }
    val conditionElement = labelable.conditionElement.expression
        ?: return ZigUnknown.of(ZigTypeType)
    return resolveValue(conditionElement).typeOf
}

private fun inferComptime(
    parentElement: ZigComptime,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.innerExpression !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    return inferResultType(parentElement)
}

private fun inferCall(
    parentElement: ZigCall,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.functionExpression !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val grandparentElement = parentElement.parent
    if (grandparentElement is ZigTry) {
        val errorSet = ZigUnknown.of(ZigTypeType)
        return inferResultType(grandparentElement).map { ZigErrorUnionType(errorSet, it) }
    }
    return inferResultType(parentElement)
}

private fun inferCallArg(
    parentElement: ZigCallArg,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.expression !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val callElement = parentElement.containingCall
    if (callElement != null) {
        val argIndex = callElement.argumentElements.indexOf(parentElement)
        assert(argIndex >= 0)
        return mapCalledFuncAndArgs(callElement) blk@{ function, isMethod, arguments ->
            val functionType = function.typeOf
            if (functionType !is ZigFunctionType) {
                return@blk ZigUnknown.of(ZigTypeType)
            }
            val paramIndex = if (isMethod) argIndex + 1 else argIndex
            val paramElement = functionType.element.parameters.elementAtOrNull(paramIndex)
                ?: return@blk ZigUnknown.of(ZigTypeType)
            var value = resolveValue(paramElement.typeElement?.expression, ZigTypeType)
            val typeParams = computeTypeParams(functionType, arguments)
            if (typeParams.isNotEmpty()) {
                value = substituteTypeParams1(value, typeParams)
            }
            value
        }
    }
    val builtinCallElement = parentElement.containingBuiltinCall
        ?: return ZigUnknown.of(ZigTypeType)
    val builtinName = builtinCallElement.builtinElement.text
    val argIndex = builtinCallElement.argumentElements.indexOf(parentElement)
    assert(argIndex >= 0)
    return when (builtinName) {
        "@as" -> inferAsBuiltinCallArg(builtinCallElement, argIndex)
        "@memset" -> inferMemsetBuiltinCallArg(builtinCallElement, argIndex)
        else -> {
            val builtin = ZigBuiltinFunctionsService.getBuiltinFnProto(parentElement.project, builtinName)
                ?: return ZigUnknown.of(ZigTypeType)
            val typeElement = builtin.parameters.elementAtOrNull(argIndex)?.typeElement?.expression
                ?: return ZigUnknown.of(ZigTypeType)
            val arguments = builtinCallElement.arguments.map { resolveValue(it) }
            val typeParams = computeBuiltinFnGenericParams(builtin, arguments)
            substituteTypeParams1(resolveValue(typeElement), typeParams)
        }
    }
}

private fun inferAsBuiltinCallArg(
    builtinCallElement: ZigBuiltinCall,
    argIndex: Int,
): ZigValue {
    if (argIndex != 1) {
        return ZigUnknown.of(ZigTypeType)
    }
    val firstArgElement = builtinCallElement.argumentElements.firstOrNull()?.expression
        ?: return ZigUnknown.of(ZigTypeType)
    return resolveValue(firstArgElement)
}

private fun inferMemsetBuiltinCallArg(
    builtinCallElement: ZigBuiltinCall,
    argIndex: Int,
): ZigValue {
    if (argIndex != 1) {
        return ZigUnknown.of(ZigTypeType)
    }
    val firstArgElement = builtinCallElement.argumentElements.firstOrNull()?.expression
        ?: return ZigUnknown.of(ZigTypeType)
    return resolveValue(firstArgElement).map {
        when (val type = it.typeOf) {
            is ZigPointerType -> when (type.size) {
                ZigPointerSize.SLICE -> type.child
                ZigPointerSize.ONE -> when (val child = type.child) {
                    is ZigArrayType -> child.child
                    else -> ZigUnknown.of(ZigTypeType)
                }
                ZigPointerSize.MANY,
                ZigPointerSize.C -> ZigUnknown.of(ZigTypeType)
            }
            else -> ZigUnknown.of(ZigTypeType)
        }
    }
}

// Based on Sema.resolvePeerTypes
// https://codeberg.org/ziglang/zig/src/commit/00b59eb/src/Sema.zig#L32687
private fun resolvePeerValues(values: Set<ZigValue>): Set<ZigValue> {
    when (values.size) {
        0 -> return emptySet()
        1 -> return values
        else -> {}
    }

    val vals = values.toList()
    val tys = vals.map { it.typeOf }

    var sameType = true
    val firstType = tys[0]
    for (i in 1 until vals.size) {
        if (tys[i] != firstType) {
            sameType = false
            break
        }
    }
    if (sameType) {
        if (firstType == ZigTypeType) {
            return values
        }
        return setOf(ZigUnknown.of(firstType))
    }

    val type = resolvePeerTypesInner(tys.toMutableList(), vals.toMutableList())
    return if (type != null) setOf(ZigUnknown.of(type)) else values
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
                    ptrTy = peerTy.with(size = ZigPointerSize.C, sentinel = null)
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
                                    val tagTy = ZigAnalyzer.resolveUnionTagType(ty)
                                    if (tagTy != curTy) return null
                                    curTy = ty
                                }
                                else -> throw IllegalStateException()
                            }
                        }
                        ZigContainerKind.UNION -> when (ty) {
                            ZigEnumLiteralType -> {}
                            is IZigContainerType -> when (ty.containerKind) {
                                ZigContainerKind.ENUM -> {
                                    val curTagTy = ZigAnalyzer.resolveUnionTagType(curTy)
                                    if (ty != curTagTy) return null
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
