package io.github.fncontroloption.zig

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.richcopy.HtmlSyntaxInfoUtil
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.RecursionManager
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.Strings
import com.intellij.openapi.vfs.findDirectory
import com.intellij.openapi.vfs.findFile
import com.intellij.psi.*
import com.intellij.psi.util.*
import com.intellij.util.containers.Interner
import org.jsoup.Jsoup

private val PRIMITIVE_VALUES = mapOf(
    "anyerror" to ZigAnyerrorType,
    //"anyframe",
    "anyopaque" to ZigAnyopaqueType,
    "bool" to ZigBoolType,
    "c_int" to ZigCIntType.of(ZigSignedness.SIGNED),
    "c_long" to ZigCLongType.of(ZigSignedness.SIGNED),
    "c_longdouble" to ZigCLongDoubleType,
    "c_longlong" to ZigCLongLongType.of(ZigSignedness.SIGNED),
    "c_char" to ZigCCharType,
    "c_short" to ZigCShortType.of(ZigSignedness.SIGNED),
    "c_uint" to ZigCIntType.of(ZigSignedness.UNSIGNED),
    "c_ulong" to ZigCLongType.of(ZigSignedness.UNSIGNED),
    "c_ulonglong" to ZigCLongLongType.of(ZigSignedness.UNSIGNED),
    "c_ushort" to ZigCShortType.of(ZigSignedness.UNSIGNED),
    "comptime_float" to ZigComptimeFloatType,
    "comptime_int" to ZigComptimeIntType,
    "f128" to ZigFixedFloatType.of(128),
    "f16" to ZigFixedFloatType.of(16),
    "f32" to ZigFixedFloatType.of(32),
    "f64" to ZigFixedFloatType.of(64),
    "f80" to ZigFixedFloatType.of(80),
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
    fun resolveValue(element: ZigExpression): ZigValue {
        return io.github.fncontroloption.zig.resolveValue(element)
    }

    fun resolveValueFromReferenceTo(target: ZigNamedElement): ZigValue {
        return io.github.fncontroloption.zig.resolveValueFromReferenceTo(target)
    }

    fun substituteTypeParams(
        value: ZigValue,
        typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
    ): ZigValue {
        return io.github.fncontroloption.zig.substituteTypeParams(value, typeParams)
    }

    fun inferResultType(childElement: ZigExpression): ZigValue {
        return io.github.fncontroloption.zig.inferResultType(childElement)
    }

    fun resolveOrInferListInitType(element: ZigListInit): ZigValue {
        val typeExpression = element.typeExpression
        if (typeExpression != null) {
            return resolveValue(typeExpression, ZigTypeType)
        }
        return inferResultType(element).mapValue { findInitializerType(it) }
    }

    fun findInitializerType(originalType: ZigValue): ZigValue {
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
        return getBuiltinFieldPlace(null, value, name)?.value?.typeOf
    }

    fun resolveEnumTagType(type: IZigContainerType): ZigValue? {
        if (type.containerKind != ZigContainerKind.ENUM) {
            return null
        }
        return when (type) {
            is ZigContainerType -> {
                val element = type.container.dereference()
                resolveValue(element?.containerArgElement?.expression, ZigTypeType)
            }
            is ZigInferredUnionTagType -> {
                val element = type.union.container.dereference() ?: return null
                val tagElement = element.containerArgElement ?: return null
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
        val element = type.container.dereference() ?: return null
        if (element.containerKind != ZigContainerKind.UNION) {
            return null
        }
        val tagElement = element.containerArgElement ?: return null
        if (tagElement.isInferredEnum) {
            return ZigInferredUnionTagType.of(type)
        }
        return resolveValue(tagElement.expression, ZigTypeType)
    }

    fun createGenericParameterValue(param: ZigFnParam): ZigGenericParameterValue? {
        if (param.isAnytype) {
            return ZigAnytypeParameterValue.of(param.createSmartPointer())
        }
        val typeElement = param.typeElement?.expression ?: return null
        val type = resolveValue(typeElement, ZigTypeType)
        val isComptime = param.isComptime || type == ZigTypeType
        if (isComptime) {
            return ZigComptimeParameterValue.of(type, param.createSmartPointer())
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

    fun forEachFieldAccessContainer(
        leftValue: ZigValue,
        action: (IZigContainerType, isInstance: Boolean) -> Unit,
    ) {
        leftValue.forEachValue { left ->
            when (left) {
                is IZigContainerType -> action(left, false)
                else -> when (val typeOfLeft = left.typeOf) {
                    is IZigContainerType -> action(typeOfLeft, true)
                    is ZigPointerType -> when (typeOfLeft.size) {
                        ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                            is IZigContainerType -> action(pointerChild, true)
                            else -> {}
                        }
                        else -> {}
                    }
                    else -> {}
                }
            }
        }
    }
}

sealed interface ZigPlace {
    val mutability: ZigMutability?
    val value: ZigValue

    fun mapPlace(transform: (ZigPlace) -> ZigPlace): ZigPlace

    fun mapType(transform: (ZigMutability?, type: ZigValue) -> ZigPlace): ZigPlace {
        return mapPlace { place ->
            when (val type = place.value.typeOf) {
                is ZigMultiValue -> {
                    val places = type.values.map { transform(place.mutability, it) }
                    Multi.from(places.toSet())
                }
                else -> transform(place.mutability, type)
            }
        }
    }

    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int
    override fun toString(): String

    @ConsistentCopyVisibility
    data class Single private constructor(
        override val mutability: ZigMutability?,
        override val value: ZigValue,
    ) : ZigPlace {
        companion object {
            fun of(
                mutability: ZigMutability?,
                value: ZigValue,
            ) = intern(Single(mutability, value))
        }

        override fun mapPlace(transform: (ZigPlace) -> ZigPlace): ZigPlace = transform(this)
    }

    @ConsistentCopyVisibility
    data class Multi private constructor(val places: Set<ZigPlace>) : ZigPlace {
        override val mutability by lazy {
            places.map { it.mutability }.reduce { a, b -> if (a == b) a else null }
        }

        override val value by lazy {
            ZigMultiValue.from(places.map { it.value }.toSet())
        }

        override fun mapPlace(transform: (ZigPlace) -> ZigPlace): ZigPlace {
            return from(places.map { it.mapPlace(transform) }.toSet())
        }

        companion object {
            fun from(places: Set<ZigPlace>): ZigPlace {
                if (places.isEmpty()) return unknown()
                return places.singleOrNull() ?: intern(Multi(places))
            }
        }
    }

    companion object {
        private val interner = Interner.createWeakInterner<ZigPlace>()

        @Suppress("UNCHECKED_CAST")
        fun <T : ZigPlace> intern(place: T) = interner.intern(place) as T

        fun constant(value: ZigValue) = Single.of(ZigMutability.CONST, value)

        fun unknown() = UNKNOWN
        val UNKNOWN by lazy { Single.of(null, ZigUnknown.ofUnknownType()) }
    }
}

sealed interface ZigValue {
    val typeOf: ZigValue

    fun toHtmlChunk(truncate: Boolean = false): HtmlChunk
    fun toPlainText() = Jsoup.parse(toHtmlChunk(truncate = true).toString()).text()
    fun mapValue(transform: (ZigValue) -> ZigValue): ZigValue = transform(this)
    fun forEachValue(action: (ZigValue) -> Unit): Unit = action(this)
    fun anyValue(predicate: (ZigValue) -> Boolean): Boolean = predicate(this)

    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int
    override fun toString(): String

    companion object {
        private val interner = Interner.createWeakInterner<ZigValue>()

        @Suppress("UNCHECKED_CAST")
        fun <T : ZigValue> intern(value: T) = interner.intern(value) as T
    }
}

@ConsistentCopyVisibility
data class ZigUnknown private constructor(override val typeOf: ZigValue) : ZigValue {
    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        if (DEBUG) {
            return HtmlChunk.fragment(
                createStyledHtmlChunk(ZigHighlightingColors.BUILTIN, "@as"),
                HtmlChunk.text("("),
                typeOf.toHtmlChunk(truncate),
                HtmlChunk.text(", "),
                createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "<unknown>"),
                HtmlChunk.text(")")
            )
        }
        return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "<unknown>")
    }

    companion object {
        fun of(type: ZigValue): ZigValue {
            return when (type) {
                TYPE -> VALUE
                ZigTypeType -> TYPE
                ZigNullType -> ZigNullValue
                ZigUndefinedType -> ZigUndefinedValue
                ZigVoidType -> ZigVoidValue
                is ZigFixedIntType if type.bits == 0 -> ZigIntValue.of(type, 0)
                is ZigErrorSetType if type.names.size == 1 -> ZigErrorSetValue.of(type, type.names.first())
                is ZigMultiValue -> type.mapValue { of(it) }
                else -> ZigValue.intern(ZigUnknown(type))
            }
        }

        val TYPE = ZigValue.intern(ZigUnknown(ZigTypeType))
        val VALUE = ZigValue.intern(ZigUnknown(TYPE))
        fun ofUnknownType() = VALUE

        private const val DEBUG = false
    }
}

@ConsistentCopyVisibility
data class ZigMultiValue private constructor(val values: Set<ZigValue>) : ZigValue {
    init {
        check(values.size > 1)
    }

    override val typeOf by lazy { mapValue { it.typeOf } }

    override fun toHtmlChunk(truncate: Boolean) =
        createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "<multi value>")

    override fun mapValue(transform: (ZigValue) -> ZigValue): ZigValue {
        return from(values.map { it.mapValue(transform) }.toSet())
    }

    override fun forEachValue(action: (ZigValue) -> Unit) {
        values.forEach { it.forEachValue(action) }
    }

    override fun anyValue(predicate: (ZigValue) -> Boolean): Boolean {
        return values.any { it.anyValue(predicate) }
    }

    companion object {
        fun from(values: Set<ZigValue>, expectedType: ZigValue? = null): ZigValue {
            if (values.isNotEmpty()) {
                return values.singleOrNull() ?: ZigValue.intern(ZigMultiValue(values))
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

@ConsistentCopyVisibility
data class ZigEnumLiteralValue private constructor(override val typeOf: ZigValue, val name: String) : ZigValue {
    companion object {
        fun of(typeOf: ZigValue, name: String) =
            ZigValue.intern(ZigEnumLiteralValue(typeOf, name))
    }

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
    val typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>
    val containerKind: ZigContainerKind?
    val isTaggedUnion: Boolean
    val members: Sequence<ZigContainerMember>
    fun getNamedMembersVisibleFrom(originalFile: PsiFile): Sequence<ZigNamedContainerMember>
}

@ConsistentCopyVisibility
data class ZigContainerType private constructor(
    val container: SmartPsiElementPointer<ZigContainer>,
    override val typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
) : IZigContainerType {
    companion object {
        fun of(
            container: SmartPsiElementPointer<ZigContainer>,
            typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
        ) = ZigValue.intern(ZigContainerType(container, typeParams))
    }

    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val element = container.dereference()
            ?: return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
        return createContainerHtmlChunkStartingFrom(element, typeParams, truncate)
    }

    override val containerKind: ZigContainerKind?
        get() {
            val element = container.dereference() ?: return null
            return element.containerKind
        }

    override val isTaggedUnion: Boolean
        get() {
            val element = container.dereference() ?: return false
            return element.isTaggedUnion
        }

    override val members: Sequence<ZigContainerMember>
        get() {
            val element = container.dereference() ?: return emptySequence()
            return element.members
        }

    override fun getNamedMembersVisibleFrom(originalFile: PsiFile): Sequence<ZigNamedContainerMember> {
        val element = container.dereference() ?: return emptySequence()
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

@ConsistentCopyVisibility
data class ZigFunctionType private constructor(
    val function: SmartPsiElementPointer<ZigFnDeclOrProto>,
    val typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
) : ZigValue {
    companion object {
        fun of(
            function: SmartPsiElementPointer<ZigFnDeclOrProto>,
            typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
        ) = ZigValue.intern(ZigFunctionType(function, typeParams))
    }

    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val element = function.dereference()
            ?: return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
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
    val param: SmartPsiElementPointer<ZigFnParam>
}

@ConsistentCopyVisibility
data class ZigComptimeParameterValue private constructor(
    override val typeOf: ZigValue,
    override val param: SmartPsiElementPointer<ZigFnParam>,
) : ZigGenericParameterValue {
    companion object {
        fun of(
            typeOf: ZigValue,
            param: SmartPsiElementPointer<ZigFnParam>,
        ) = ZigValue.intern(ZigComptimeParameterValue(typeOf, param))
    }

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val element = param.dereference()
            ?: return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
        return createNamedHtmlChunk(
            element,
            ZigHighlightingColors.COMPTIME_PARAMETER,
            ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING,
        )
    }
}

@ConsistentCopyVisibility
data class ZigAnytypeParameterValue private constructor(
    override val param: SmartPsiElementPointer<ZigFnParam>,
) : ZigGenericParameterValue {
    companion object {
        fun of(param: SmartPsiElementPointer<ZigFnParam>) =
            ZigValue.intern(ZigAnytypeParameterValue(param))
    }

    override val typeOf = ZigAnytypeParameterType.of(this)

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val element = param.dereference()
            ?: return createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
        return createNamedHtmlChunk(
            element,
            ZigHighlightingColors.PARAMETER,
            ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING,
        )
    }
}

@ConsistentCopyVisibility
data class ZigAnytypeParameterType private constructor(val value: ZigAnytypeParameterValue) : ZigValue {
    companion object {
        fun of(value: ZigAnytypeParameterValue) =
            ZigValue.intern(ZigAnytypeParameterType(value))
    }

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

@ConsistentCopyVisibility
data class ZigOptionalType private constructor(val child: ZigValue) : ZigValue {
    companion object {
        fun of(child: ZigValue) =
            ZigValue.intern(ZigOptionalType(child))
    }

    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = HtmlChunk.fragment(HtmlChunk.text("?"), child.toHtmlChunk(truncate))
}

@ConsistentCopyVisibility
data class ZigErrorSetType private constructor(val names: Set<String>) : ZigValue {
    companion object {
        fun of(names: Set<String>) =
            ZigValue.intern(ZigErrorSetType(names))
    }

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

@ConsistentCopyVisibility
data class ZigErrorSetValue private constructor(override val typeOf: ZigErrorSetType, val name: String) : ZigValue {
    companion object {
        fun of(typeOf: ZigErrorSetType, name: String) =
            ZigValue.intern(ZigErrorSetValue(typeOf, name))
    }

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

@ConsistentCopyVisibility
data class ZigErrorUnionType private constructor(val errorSet: ZigValue, val payload: ZigValue) : ZigValue {
    companion object {
        fun of(errorSet: ZigValue, payload: ZigValue) =
            ZigValue.intern(ZigErrorUnionType(errorSet, payload))
    }

    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = HtmlChunk.fragment(
        errorSet.toHtmlChunk(truncate),
        HtmlChunk.text("!"),
        payload.toHtmlChunk(truncate)
    )
}

@ConsistentCopyVisibility
data class ZigPointerType private constructor(
    val size: ZigPointerSize,
    val sentinel: ZigValue?,
    // TODO: include other modifiers (volatile, allowzero, addrspace, align)
    val mutability: ZigMutability?,
    val child: ZigValue,
) : ZigValue {
    companion object {
        fun of(
            size: ZigPointerSize,
            sentinel: ZigValue?,
            mutability: ZigMutability?,
            child: ZigValue,
        ) = ZigValue.intern(ZigPointerType(size, sentinel, mutability, child))
    }

    fun with(
        size: ZigPointerSize = this.size,
        sentinel: ZigValue? = this.sentinel,
        mutability: ZigMutability? = this.mutability,
        child: ZigValue = this.child,
    ) = of(size, sentinel, mutability, child)

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

@ConsistentCopyVisibility
data class ZigArrayType private constructor(
    val length: ZigValue,
    val sentinel: ZigValue?,
    val child: ZigValue,
) : ZigValue {
    companion object {
        fun of(
            length: ZigValue,
            sentinel: ZigValue?,
            child: ZigValue,
        ) = ZigValue.intern(ZigArrayType(length, sentinel, child))
    }

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

@ConsistentCopyVisibility
data class ZigVectorType private constructor(val length: ZigValue, val child: ZigValue) : ZigValue {
    companion object {
        fun of(length: ZigValue, child: ZigValue) =
            ZigValue.intern(ZigVectorType(length, child))
    }

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

@ConsistentCopyVisibility
data class ZigTupleType private constructor(val element: LightZigTuple) : IZigContainerType {
    companion object {
        fun of(element: LightZigTuple) =
            ZigValue.intern(ZigTupleType(element))
    }

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

    override val typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>
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

@ConsistentCopyVisibility
data class ZigFixedFloatType private constructor(val bits: Int) : ZigFloatType {
    companion object {
        fun of(bits: Int) =
            ZigValue.intern(ZigFixedFloatType(bits))
    }

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

@ConsistentCopyVisibility
data class ZigIntValue private constructor(override val typeOf: ZigIntType, val value: Int) : ZigValue {
    companion object {
        fun of(typeOf: ZigIntType, value: Int) =
            ZigValue.intern(ZigIntValue(typeOf, value))
    }

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

@ConsistentCopyVisibility
data class ZigFixedIntType private constructor(val signedness: ZigSignedness, val bits: Int) : ZigIntType {
    companion object {
        fun of(signedness: ZigSignedness, bits: Int) =
            ZigValue.intern(ZigFixedIntType(signedness, bits))
    }

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

@ConsistentCopyVisibility
data class ZigCShortType private constructor(val signedness: ZigSignedness) : ZigIntType {
    companion object {
        fun of(signedness: ZigSignedness) =
            ZigValue.intern(ZigCShortType(signedness))
    }

    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "c_short")
}

@ConsistentCopyVisibility
data class ZigCIntType private constructor(val signedness: ZigSignedness) : ZigIntType {
    companion object {
        fun of(signedness: ZigSignedness) =
            ZigValue.intern(ZigCIntType(signedness))
    }

    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "c_int")
}

@ConsistentCopyVisibility
data class ZigCLongType private constructor(val signedness: ZigSignedness) : ZigIntType {
    companion object {
        fun of(signedness: ZigSignedness) =
            ZigValue.intern(ZigCLongType(signedness))
    }

    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "c_long")
}

@ConsistentCopyVisibility
data class ZigCLongLongType private constructor(val signedness: ZigSignedness) : ZigIntType {
    companion object {
        fun of(signedness: ZigSignedness) =
            ZigValue.intern(ZigCLongLongType(signedness))
    }

    override val typeOf: ZigTypeType
        get() = ZigTypeType

    override fun toHtmlChunk(truncate: Boolean) = createStyledHtmlChunk(ZigHighlightingColors.KEYWORD, "c_longlong")
}

@ConsistentCopyVisibility
data class ZigInferredUnionTagType private constructor(val union: ZigContainerType) : IZigContainerType {
    companion object {
        fun of(union: ZigContainerType) =
            ZigValue.intern(ZigInferredUnionTagType(union))
    }

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

    override val typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>
        get() = emptyMap()

    override val containerKind: ZigContainerKind
        get() = ZigContainerKind.ENUM

    override val isTaggedUnion: Boolean
        get() = false

    override val members: Sequence<ZigContainerField>
        get() = union.members.filterIsInstance<ZigContainerField>()

    override fun getNamedMembersVisibleFrom(originalFile: PsiFile) = members
}

@ConsistentCopyVisibility
data class ZigFieldEnumType private constructor(val container: ZigValue) : IZigContainerType {
    companion object {
        fun of(container: ZigValue) =
            ZigValue.intern(ZigFieldEnumType(container))
    }

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

    override val typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>
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

@ConsistentCopyVisibility
data class ZigLazyFieldType private constructor(val container: ZigValue, val fieldName: ZigValue) : ZigValue {
    companion object {
        fun of(container: ZigValue, fieldName: ZigValue) =
            ZigValue.intern(ZigLazyFieldType(container, fieldName))
    }

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

@ConsistentCopyVisibility
data class ZigLazyFieldValue private constructor(val left: ZigValue, val fieldName: ZigValue) : ZigValue {
    companion object {
        fun of(left: ZigValue, fieldName: ZigValue) =
            ZigValue.intern(ZigLazyFieldValue(left, fieldName))
    }

    override val typeOf = ZigUnknown.of(ZigTypeType)

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val builder = HtmlBuilder()
        if (fieldName is ZigStringLiteralValue) {
            builder.append(left.toHtmlChunk(truncate))
            builder.append(".")
            builder.append(renderZigIdentifier(fieldName.value, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
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

@ConsistentCopyVisibility
data class ZigLazyTagNameValue private constructor(val value: ZigValue) : ZigValue {
    companion object {
        fun of(value: ZigValue) =
            ZigValue.intern(ZigLazyTagNameValue(value))
    }

    override val typeOf = ZigPointerType.of(
        ZigPointerSize.ONE,
        null,
        ZigMutability.CONST,
        ZigArrayType.of(
            ZigUnknown.of(ZigUsizeType),
            ZigIntValue.of(ZigComptimeIntType, 0),
            ZigFixedIntType.of(ZigSignedness.UNSIGNED, 8)
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

@ConsistentCopyVisibility
data class ZigStringLiteralValue private constructor(val value: String) : ZigValue {
    companion object {
        fun of(value: String) =
            ZigValue.intern(ZigStringLiteralValue(value))
    }

    override val typeOf = ZigPointerType.of(
        ZigPointerSize.ONE,
        null,
        ZigMutability.CONST,
        ZigArrayType.of(
            ZigIntValue.of(ZigUsizeType, value.length),
            ZigIntValue.of(ZigComptimeIntType, 0),
            ZigFixedIntType.of(ZigSignedness.UNSIGNED, 8)
        )
    )

    override fun toHtmlChunk(truncate: Boolean): HtmlChunk {
        val text = buildString {
            append('"')
            escapeZigString(value, this)
            append('"')
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
    if (element == null) {
        return if (expectedType != null) ZigUnknown.of(expectedType) else ZigUnknown.ofUnknownType()
    }
    return RecursionManager.doPreventingRecursion(element, true) {
        computeValueUncached(element)
    } ?: if (expectedType != null) ZigUnknown.of(expectedType) else ZigUnknown.ofUnknownType()
}

private fun computeValueUncached(element: ZigExpression): ZigValue {
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
            -> resolvePlace(element).value

        is ZigBuiltinCall if element.builtinElement.text == "@field"
            -> resolvePlace(element).value

        is ZigContainerExpression -> computeContainerExpression(element)
        is ZigFnProto -> ZigFunctionType.of(element.createSmartPointer(), findUnboundTypeParams(element))
        is ZigOptionalTypeExpression -> computeOptionalTypeExpression(element)
        is ZigErrorSet -> computeErrorSet(element)
        is ZigErrorValueExpression -> computeErrorValueExpression(element)
        is ZigErrorUnion -> computeErrorUnion(element)
        is ZigArrayTypeExpression -> computeArrayTypeExpression(element)
        is ZigPointerTypeExpression -> computePointerTypeExpression(element)
        is ZigComptime -> computeComptime(element)
        is ZigIf -> computeIf(element)
        is ZigCatch -> computeCatch(element)
        is ZigOrelse -> computeOrelse(element)
        is ZigMergeErrorSets -> computeMergeErrorSets(element)
        is ZigListInit -> computeListInit(element)

        is ZigAdd -> ZigUnknown.ofUnknownType() // TODO
        is ZigAddSat -> ZigUnknown.ofUnknownType() // TODO
        is ZigAddWrap -> ZigUnknown.ofUnknownType() // TODO
        is ZigArrayCat -> ZigUnknown.ofUnknownType() // TODO
        is ZigBangEqual -> ZigUnknown.ofUnknownType() // TODO
        is ZigBitAnd -> ZigUnknown.ofUnknownType() // TODO
        is ZigBitOr -> ZigUnknown.ofUnknownType() // TODO
        is ZigBitXor -> ZigUnknown.ofUnknownType() // TODO
        is ZigBoolAnd -> ZigUnknown.ofUnknownType() // TODO
        is ZigBoolOr -> ZigUnknown.ofUnknownType() // TODO
        is ZigDiv -> ZigUnknown.ofUnknownType() // TODO
        is ZigEqualEqual -> ZigUnknown.ofUnknownType() // TODO
        is ZigGreaterOrEqual -> ZigUnknown.ofUnknownType() // TODO
        is ZigGreaterThan -> ZigUnknown.ofUnknownType() // TODO
        is ZigLessOrEqual -> ZigUnknown.ofUnknownType() // TODO
        is ZigLessThan -> ZigUnknown.ofUnknownType() // TODO
        is ZigMod -> ZigUnknown.ofUnknownType() // TODO
        is ZigMul -> ZigUnknown.ofUnknownType() // TODO
        is ZigMulSat -> ZigUnknown.ofUnknownType() // TODO
        is ZigMulWrap -> ZigUnknown.ofUnknownType() // TODO
        is ZigShl -> ZigUnknown.ofUnknownType() // TODO
        is ZigShlSat -> ZigUnknown.ofUnknownType() // TODO
        is ZigShr -> ZigUnknown.ofUnknownType() // TODO
        is ZigSub -> ZigUnknown.ofUnknownType() // TODO
        is ZigSubSat -> ZigUnknown.ofUnknownType() // TODO
        is ZigSubWrap -> ZigUnknown.ofUnknownType() // TODO

        is ZigBitNot -> ZigUnknown.ofUnknownType() // TODO
        is ZigBoolNot -> ZigUnknown.ofUnknownType() // TODO
        is ZigNegation -> ZigUnknown.ofUnknownType() // TODO
        is ZigNegationWrap -> ZigUnknown.ofUnknownType() // TODO

        is ZigBuiltinCall -> computeBuiltinCall(element)
        is ZigCall -> computeCall(element)
        is ZigBlock -> computeBlock(element)
        is ZigSwitch -> computeSwitch(element)
        is ZigLoop -> computeLoop(element)
        is ZigStringExpression -> computeStringExpression(element)
        is ZigNumberLiteral -> computeNumberLiteral(element)
        is ZigCharLiteral -> ZigUnknown.of(ZigComptimeIntType)
        is ZigEnumLiteral -> computeEnumLiteral(element)

        is ZigBreak,
        is ZigContinue,
        is ZigReturn,
        is ZigUnreachableLiteral -> ZigUnknown.of(ZigNoreturnType)

        is ZigAnyframeLiteral,
        is ZigAnyframeType,
        is ZigAsm,
        is ZigNosuspend,
        is ZigResume -> ZigUnknown.ofUnknownType()
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
    return ZigFixedIntType.of(signedness, bits)
}

private fun getBuiltinFieldPlace(mutability: ZigMutability?, value: ZigValue, name: String): ZigPlace? {
    var mutability = mutability
    var type = value.typeOf
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
                "len" -> ZigPlace.Single.of(mutability, ZigUnknown.of(ZigUsizeType))
                "ptr" -> ZigPlace.Single.of(mutability, ZigUnknown.of(type.with(size = ZigPointerSize.MANY)))
                else -> null
            }
        }

        is ZigArrayType -> when (name) {
            "len" -> ZigPlace.constant(type.length)
            else -> null
        }

        is ZigTupleType -> when (name) {
            "len" -> ZigPlace.constant(ZigIntValue.of(ZigUsizeType, type.fields.size))
            else -> null
        }

        else -> null
    }
}

private fun computeFieldAccess(
    mutability: ZigMutability?,
    leftValue: ZigValue,
    fieldName: ZigValue,
): ZigPlace {
    if (fieldName !is ZigStringLiteralValue) {
        return ZigPlace.Single.of(null, ZigLazyFieldValue.of(leftValue, fieldName))
    }
    val name = fieldName.value
    val builtinPlace = getBuiltinFieldPlace(mutability, leftValue, name)
    if (builtinPlace != null) {
        return builtinPlace
    }
    return when (leftValue) {
        is ZigErrorSetType -> {
            if (name in leftValue.names) {
                ZigPlace.constant(ZigErrorSetValue.of(leftValue, name))
            } else {
                ZigPlace.unknown()
            }
        }
        is IZigContainerType -> computeContainerTypeDeclAccess(leftValue, fieldName)
        is ZigMultiValue -> {
            val places = leftValue.values.map { computeFieldAccess(mutability, it, fieldName) }
            ZigPlace.Multi.from(places.toSet())
        }
        else -> when (val typeOfLeft = leftValue.typeOf) {
            is IZigContainerType -> computeContainerInstanceFieldAccess(mutability, typeOfLeft, fieldName)
            is ZigPointerType -> when (typeOfLeft.size) {
                ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                    is IZigContainerType ->
                        computeContainerInstanceFieldAccess(typeOfLeft.mutability, pointerChild, fieldName)
                    else -> ZigPlace.Single.of(null, ZigLazyFieldValue.of(leftValue, fieldName))
                }
                else -> ZigPlace.unknown()
            }
            else -> ZigPlace.Single.of(null, ZigLazyFieldValue.of(leftValue, fieldName))
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
    val fieldType = resolveValue(indexExpression).mapValue {
        when (it) {
            is ZigIntValue -> type.fields.getOrNull(it.value)
            else -> null
        } ?: ZigUnknown.of(ZigTypeType)
    }
    return ZigPlace.Single.of(mutability, ZigUnknown.of(fieldType))
}

private fun findUnboundTypeParams(element: ZigElement): Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?> {
    return buildMap {
        var element: PsiElement? = element
        while (element != null) {
            if (element is ZigFnDeclOrProto) {
                for (paramDecl in element.parameters) {
                    val genericValue = ZigAnalyzer.createGenericParameterValue(paramDecl)
                    if (genericValue != null) {
                        this[genericValue.param] = null
                    }
                }
            }
            element = element.parent
        }
    }
}

private fun computeTypeParams(
    type: ZigFunctionType,
    arguments: Sequence<ZigValue?>,
): Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?> {
    val element = type.function.dereference() ?: return emptyMap()
    if (type.typeParams.isEmpty()) {
        return emptyMap()
    }
    return type.typeParams.toMutableMap().apply {
        for ((paramDecl, argValue) in element.parameters.zip(arguments)) {
            val genericValue = ZigAnalyzer.createGenericParameterValue(paramDecl)
            if (genericValue != null) {
                this[genericValue.param] = argValue
            }
        }
    }
}

private fun computeBuiltinFnGenericParams(
    element: ZigBuiltinFnProto,
    arguments: Sequence<ZigValue>,
): Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?> {
    return buildMap {
        for ((paramDecl, argValue) in element.parameters.zip(arguments)) {
            val genericValue = ZigAnalyzer.createGenericParameterValue(paramDecl)
            if (genericValue != null) {
                this[genericValue.param] = argValue
            }
        }
    }
}

private fun substituteContainerTypeParams(
    type: ZigContainerType,
    typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
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
        ZigContainerType.of(type.container, type.typeParams + typeParams)
    }
}

private fun substituteFunctionTypeParams(
    type: ZigFunctionType,
    typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
): ZigFunctionType {
    return if (type.typeParams.isEmpty()) {
        type
    } else {
        ZigFunctionType.of(type.function, type.typeParams + typeParams)
    }
}

private fun substituteTypeParams(
    value: ZigValue,
    typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
): ZigValue {
    return RecursionManager.doPreventingRecursion(value, true) {
        when (value) {
            is ZigUnknown -> {
                ZigUnknown.of(substituteTypeParams(value.typeOf, typeParams))
            }
            is ZigMultiValue -> {
                value.mapValue { substituteTypeParams(it, typeParams) }
            }
            is ZigEnumLiteralValue -> {
                substituteTypeParams(value.typeOf, typeParams).mapValue { ZigEnumLiteralValue.of(it, value.name) }
            }
            is ZigContainerType -> {
                substituteContainerTypeParams(value, typeParams)
            }
            is ZigFunctionType -> {
                substituteFunctionTypeParams(value, typeParams)
            }
            is ZigGenericParameterValue -> {
                val arg = typeParams[value.param]
                if (arg == null) {
                    value
                } else {
                    substituteTypeParams(arg, typeParams)
                }
            }
            is ZigAnytypeParameterType -> {
                substituteTypeParams(value.value, typeParams).typeOf
            }
            is ZigOptionalType -> {
                substituteTypeParams(value.child, typeParams).mapValue { ZigOptionalType.of(it) }
            }
            is ZigErrorUnionType -> {
                val errorSet = substituteTypeParams(value.errorSet, typeParams)
                val payload = substituteTypeParams(value.payload, typeParams)
                payload.mapValue { ZigErrorUnionType.of(errorSet, it) }
            }
            is ZigArrayType -> {
                val length = substituteTypeParams(value.length, typeParams)
                val sentinel = value.sentinel?.let { substituteTypeParams(it, typeParams) }
                val child = substituteTypeParams(value.child, typeParams)
                child.mapValue { ZigArrayType.of(length, sentinel, it) }
            }
            is ZigVectorType -> {
                val length = substituteTypeParams(value.length, typeParams)
                val child = substituteTypeParams(value.child, typeParams)
                child.mapValue { ZigVectorType.of(length, it) }
            }
            is ZigPointerType -> {
                val sentinel = value.sentinel?.let { substituteTypeParams(it, typeParams) }
                val child = substituteTypeParams(value.child, typeParams)
                child.mapValue { value.with(sentinel = sentinel, child = it) }
            }
            is ZigTupleType -> {
                val fields = value.fields.map { substituteTypeParams(it, typeParams) }
                ZigTupleType.of(LightZigTuple(value.element.manager, fields))
            }
            is ZigInferredUnionTagType -> {
                ZigInferredUnionTagType.of(substituteContainerTypeParams(value.union, typeParams))
            }
            is ZigFieldEnumType -> {
                substituteTypeParams(value.container, typeParams).mapValue { ZigFieldEnumType.of(it) }
            }
            is ZigLazyFieldType -> {
                val containerType = substituteTypeParams(value.container, typeParams)
                val fieldNameValue = substituteTypeParams(value.fieldName, typeParams)
                computeFieldType(containerType, fieldNameValue)
            }
            is ZigLazyFieldValue -> {
                val leftValue = substituteTypeParams(value.left, typeParams)
                val fieldName = substituteTypeParams(value.fieldName, typeParams)
                computeFieldAccess(null, leftValue, fieldName).value
            }
            is ZigLazyTagNameValue -> {
                computeTagNameValue(substituteTypeParams(value.value, typeParams))
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
                -> value
        }
    } ?: value
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
        value = substituteTypeParams(value, type.typeParams)
    }
    return ZigPlace.Single.of(mutability, value)
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
            ZigPlace.constant(ZigEnumLiteralValue.of(type, name))
        } else if (type.isTaggedUnion) {
            val tagType = ZigAnalyzer.resolveUnionTagType(type) ?: return ZigPlace.unknown()
            ZigPlace.constant(tagType.mapValue { ZigEnumLiteralValue.of(it, name) })
        } else {
            ZigPlace.unknown()
        }
    }
    var value = resolveValueFromReferenceTo(member)
    if (type.typeParams.isNotEmpty()) {
        value = substituteTypeParams(value, type.typeParams)
    }
    return when (member) {
        is ZigVarDecl -> ZigPlace.Single.of(member.mutability, value)
        else -> ZigPlace.constant(value)
    }
}

private fun resolveValueFromReferenceTo(target: ZigNamedElement): ZigValue {
    return CachedValuesManager.getCachedValue(target) {
        RecursionManager.doPreventingRecursion(target, true) {
            CachedValueProvider.Result.create(
                computeValueFromReferenceUncached(target),
                PsiModificationTracker.MODIFICATION_COUNT,
                ZigBuiltinService.getModificationTracker(target.project),
                ZigBuiltinFunctionsService.getModificationTracker(target.project),
                ZigModulesProjectService.getModificationTracker(target.project),
            )
        }
    } ?: ZigUnknown.ofUnknownType()
}

private fun computeValueFromReferenceUncached(target: ZigNamedElement): ZigValue {
    return when (target) {
        is ZigFile -> ZigContainerType.of(target.createSmartPointer(), emptyMap())
        is ZigFnDecl -> ZigUnknown.of(ZigFunctionType.of(target.createSmartPointer(), findUnboundTypeParams(target)))
        is ZigVarDecl -> computeVarDecl(target)
        is ZigVarProto -> computeVarProto(target)
        is ZigFnParam -> computeFnParam(target)
        is ZigContainerFieldImpl -> computeContainerField(target)
        is ZigPayloadItem -> computePayloadItem(target).value
        is LightZigTupleField -> ZigUnknown.of(target.container.fieldTypes[target.fieldIndex])
        is ZigLabelDecl -> ZigUnknown.ofUnknownType()
    }
}

private fun computeFunctionReturnValue(
    type: ZigFunctionType,
    arguments: Sequence<ZigValue?>,
): ZigValue {
    val functionElement = type.function.dereference()
        ?: return ZigUnknown.ofUnknownType()
    if (functionElement is ZigFnDecl
        && functionElement.containingFile.name == "meta.zig"
        && functionElement.containingFile.containingDirectory?.name == "std"
    ) {
        when (functionElement.name) {
            "Tag" -> {
                val value = arguments.firstOrNull()
                    ?: return ZigUnknown.of(ZigTypeType)
                return value.mapValue { computeTagType(it) ?: ZigUnknown.of(ZigTypeType) }
            }
            "FieldEnum" -> {
                val value = arguments.firstOrNull()
                    ?: return ZigUnknown.of(ZigTypeType)
                return value.mapValue { ZigFieldEnumType.of(it) }
            }
            else -> {}
        }
    }
    val returnTypeElement = functionElement.returnTypeElement?.expression
        ?: return ZigUnknown.ofUnknownType()
    val returnType = if (functionElement.bangTokenElement != null) {
        val errorSet = ZigUnknown.ofUnknownType() // TODO: infer error set
        resolveValue(returnTypeElement).mapValue { ZigErrorUnionType.of(errorSet, it) }
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
                                value = substituteTypeParams(value, typeParams)
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
        value = substituteTypeParams(value, typeParams)
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
            val fieldName = ZigStringLiteralValue.of(name)
            resolveValue(functionElement.leftOperand).mapValue { left ->
                when (left) {
                    is IZigContainerType -> {
                        val place = computeContainerTypeDeclAccess(left, fieldName)
                        place.value.mapValue { transform(it, null) }
                    }
                    else -> when (val typeOfLeft = left.typeOf) {
                        is IZigContainerType -> {
                            val place = computeContainerTypeDeclAccess(typeOfLeft, fieldName)
                            place.value.mapValue { transform(it, left) }
                        }
                        is ZigPointerType -> when (typeOfLeft.size) {
                            ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                                is IZigContainerType -> {
                                    val place = computeContainerTypeDeclAccess(pointerChild, fieldName)
                                    place.value.mapValue { transform(it, left) }
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
            inferResultType(functionElement).mapValue { originalType ->
                val type = ZigAnalyzer.findEnumLiteralContainerType(originalType)
                    ?: return@mapValue ZigUnknown.ofUnknownType()
                val member = type.members.firstNotNullOfOrNull {
                    if (it is ZigNamedContainerDecl && it.name == name) it else null
                }
                if (member != null) {
                    var value = resolveValueFromReferenceTo(member)
                    value = substituteTypeParams(value, type.typeParams)
                    value.mapValue { transform(it, null) }
                } else {
                    ZigUnknown.ofUnknownType()
                }
            }
        }

        else -> resolveValue(functionElement).mapValue { transform(it, null) }
    }
}

private fun computeValuesFromLabeledBreaks(element: ZigLabelable): Sequence<ZigValue> {
    return getLabeledBreaksFor(element).map { resolveBreakValue(it) }
}

private fun getLabeledBreaksFor(element: ZigLabelable): Sequence<ZigBreak> {
    val labelDecl = element.labelDecl ?: return emptySequence()
    val labelName = parseZigIdentifier(labelDecl.identifierElement) ?: return emptySequence()
    val traverser = SyntaxTraverser.psiTraverser(element).expand { it !is ZigContainerExpression }
    return traverser.asSequence().filterIsInstance<ZigBreak>().filter {
        labelName == parseZigIdentifier(it.labelRef?.identifierElement)
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
    return traverser.asSequence().filterIsInstance<ZigBreak>().filter { it.labelRef == null }
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

internal fun createStyledHtmlChunk(attributesKey: TextAttributesKey, text: String): HtmlChunk {
    val value = Strings.escapeXmlEntities(text)
    val sb = StringBuilder()
    HtmlSyntaxInfoUtil.appendStyledSpan(sb, attributesKey, value, 1f)
    return HtmlChunk.raw(sb.toString())
}

private fun createLinkedHtmlChunk(
    element: ZigNavigatableElement,
    attributesKey: TextAttributesKey,
    text: String,
): HtmlChunk {
    val target = buildString {
        append(ZIG_DOCUMENTATION_PROTOCOL)
        append(element.containingFile.viewProvider.virtualFile.url)
        append(ZIG_DOCUMENTATION_OFFSET_SEPARATOR)
        append(element.textOffset)
    }
    return HtmlChunk.link(target, createStyledHtmlChunk(attributesKey, text))
}

private fun createNamedHtmlChunk(
    element: ZigNamedElement,
    attributesKey: TextAttributesKey,
    quote: ZigIdQuoteBehavior,
): HtmlChunk {
    val name = element.name
    return if (name != null) {
        createLinkedHtmlChunk(element, attributesKey, renderZigIdentifier(name, quote))
    } else {
        createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
    }
}

private fun createTypeElementHtmlChunk(
    typeElement: ZigTypeElement?,
    typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
    truncate: Boolean,
): HtmlChunk {
    val value = typeElement?.expression?.let {
        val initialValue = resolveValue(it)
        substituteTypeParams(initialValue, typeParams)
    }
    return value?.toHtmlChunk(truncate) ?: createStyledHtmlChunk(ZigHighlightingColors.BAD_CHARACTER, "_")
}

private fun createContainerHtmlChunkStartingFrom(
    element: PsiElement,
    typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
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
                    append(ZIG_DOCUMENTATION_PROTOCOL)
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
                    val kind = when (element.containerKind) {
                        ZigContainerKind.STRUCT -> "struct"
                        ZigContainerKind.OPAQUE -> "opaque"
                        ZigContainerKind.ENUM -> "enum"
                        ZigContainerKind.UNION -> "union"
                    }
                    HtmlChunk.fragment(
                        parentChunk,
                        HtmlChunk.text("__"),
                        createLinkedHtmlChunk(element, ZigHighlightingColors.KEYWORD, kind)
                    )
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
    typeParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
    truncate: Boolean,
): HtmlChunk {
    val genericParam = ZigAnalyzer.createGenericParameterValue(param)
    if (genericParam != null) {
        val genericArg = substituteTypeParams(genericParam, typeParams)
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
): ZigValue = containerType.mapValue { container ->
    fieldNameValue.mapValue { fieldName ->
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
                ZigLazyFieldType.of(container, fieldName)
            }
        } else {
            ZigLazyFieldType.of(container, fieldName)
        }
    }
}

fun computeTagNameValue(value: ZigValue): ZigValue {
    return value.mapValue {
        when (it) {
            is ZigEnumLiteralValue -> ZigStringLiteralValue.of(it.name)
            else -> ZigLazyTagNameValue.of(it)
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

private fun computeArrayAccess(
    mutability: ZigMutability?,
    typeOfLeft: ZigValue,
    indexExpression: ZigExpression?,
): ZigPlace {
    return when (typeOfLeft) {
        is ZigArrayType -> ZigPlace.Single.of(mutability, ZigUnknown.of(typeOfLeft.child))
        is ZigVectorType -> ZigPlace.Single.of(mutability, ZigUnknown.of(typeOfLeft.child))
        is ZigTupleType -> computeArrayAccessOnTuple(mutability, typeOfLeft, indexExpression)
        is ZigPointerType -> when (typeOfLeft.size) {
            ZigPointerSize.ONE -> when (val pointerChild = typeOfLeft.child) {
                is ZigArrayType -> ZigPlace.Single.of(typeOfLeft.mutability, ZigUnknown.of(pointerChild.child))
                is ZigVectorType -> ZigPlace.Single.of(typeOfLeft.mutability, ZigUnknown.of(pointerChild.child))
                is ZigTupleType -> computeArrayAccessOnTuple(typeOfLeft.mutability, pointerChild, indexExpression)
                else -> ZigPlace.unknown()
            }
            else -> ZigPlace.Single.of(typeOfLeft.mutability, ZigUnknown.of(typeOfLeft.child))
        }
        is ZigMultiValue -> {
            val places = typeOfLeft.values.map { computeArrayAccess(mutability, it, indexExpression) }
            ZigPlace.Multi.from(places.toSet())
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
            is ZigVarDeclOrProto -> ZigPlace.Single.of(target.mutability, value)
            else -> ZigPlace.constant(value)
        }
    }
    return ZigPlace.Multi.from(places.toSet())
}

private fun computeFieldAccess(element: ZigFieldAccess): ZigPlace {
    val name = parseZigIdentifier(element.identifierElement) ?: return ZigPlace.unknown()
    val left = resolvePlace(element.leftOperand)
    val fieldName = ZigStringLiteralValue.of(name)
    return left.mapPlace { computeFieldAccess(it.mutability, it.value, fieldName) }
}

private fun computeArrayAccess(element: ZigArrayAccess): ZigPlace {
    val left = resolvePlace(element.leftOperand)
    val indexExpression = element.indexElement?.expression
    return left.mapPlace { computeArrayAccess(it.mutability, it.value.typeOf, indexExpression) }
}

private fun computeGroupedExpression(element: ZigGroupedExpression): ZigPlace {
    val innerExpression = element.innerExpression ?: return ZigPlace.unknown()
    return resolvePlace(innerExpression)
}

private fun computeUnwrapOptional(element: ZigUnwrapOptional): ZigPlace {
    val unionElement = getUnwrapUnionTagTypeArgument(element)
    if (unionElement != null) {
        val tagType = resolveValue(unionElement).mapValue {
            when (it) {
                is ZigContainerType -> ZigAnalyzer.resolveUnionTagType(it)
                else -> null
            } ?: ZigUnknown.of(ZigTypeType)
        }
        return ZigPlace.constant(tagType)
    }
    val operandElement = element.operand ?: return ZigPlace.unknown()
    return resolvePlace(operandElement).mapType { mutability, typeOfOperand ->
        when (typeOfOperand) {
            is ZigOptionalType -> ZigPlace.Single.of(mutability, ZigUnknown.of(typeOfOperand.child))
            else -> ZigPlace.unknown()
        }
    }
}

private fun computeTry(element: ZigTry): ZigPlace {
    val operandElement = element.operand ?: return ZigPlace.unknown()
    return resolvePlace(operandElement).mapType { mutability, typeOfOperand ->
        when (typeOfOperand) {
            is ZigErrorUnionType -> ZigPlace.Single.of(mutability, ZigUnknown.of(typeOfOperand.payload))
            else -> ZigPlace.unknown()
        }
    }
}

private fun computeDeref(element: ZigDeref): ZigPlace {
    val operandElement = element.operand ?: return ZigPlace.unknown()
    return resolvePlace(operandElement).mapType { _, typeOfOperand ->
        when (typeOfOperand) {
            is ZigPointerType -> when (typeOfOperand.size) {
                ZigPointerSize.ONE,
                ZigPointerSize.C -> ZigPlace.Single.of(typeOfOperand.mutability, ZigUnknown.of(typeOfOperand.child))
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
    val left = resolvePlace(leftElement)
    val fieldName = resolveValue(fieldNameElement)
    return left.mapPlace { computeFieldAccess(it.mutability, it.value, fieldName) }
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
            fields.add(resolveValueFromReferenceTo(member).typeOf)
        }
        if (fields.isEmpty()) {
            return@tuple
        }
        val lightTuple = LightZigTuple(element.manager, fields)
        lightTuple.navigationElement = element
        for ((i, member) in element.members.withIndex()) {
            lightTuple.fields[i].navigationElement = member
        }
        return ZigTupleType.of(lightTuple)
    }
    return ZigContainerType.of(element.createSmartPointer(), findUnboundTypeParams(element))
}

private fun computeOptionalTypeExpression(element: ZigOptionalTypeExpression): ZigValue {
    val child = resolveValue(element.childExpression, ZigTypeType)
    return child.mapValue { ZigOptionalType.of(it) }
}

private fun computeErrorSet(element: ZigErrorSet): ZigValue {
    val names = sortedSetOf<String>()
    for (item in element.setItems) {
        val name = parseZigIdentifier(item.identifierElement) ?: return ZigUnknown.of(ZigTypeType)
        names.add(name)
    }
    return ZigErrorSetType.of(names)
}

private fun computeErrorValueExpression(element: ZigErrorValueExpression): ZigValue {
    val name = parseZigIdentifier(element.identifierElement) ?: return ZigUnknown.ofUnknownType()
    val type = ZigErrorSetType.of(setOf(name))
    return ZigErrorSetValue.of(type, name)
}

private fun computeErrorUnion(element: ZigErrorUnion): ZigValue {
    val errorSet = resolveValue(element.errorSet, ZigTypeType)
    val payload = resolveValue(element.payload, ZigTypeType)
    return payload.mapValue { ZigErrorUnionType.of(errorSet, it) }
}

private fun computeArrayTypeExpression(element: ZigArrayTypeExpression): ZigValue {
    val length = resolveValue(element.lengthElement.expression, ZigUsizeType)
    val sentinel = element.sentinelElement?.let { resolveValue(it.expression) }
    val child = resolveValue(element.childExpression, ZigTypeType)
    return child.mapValue { ZigArrayType.of(length, sentinel, it) }
}

private fun computePointerTypeExpression(element: ZigPointerTypeExpression): ZigValue {
    val size = element.pointerSize
    val sentinel = element.sentinelElement?.let { resolveValue(it.expression) }
    val mutability = element.mutability
    val child = resolveValue(element.childExpression, ZigTypeType)
    return child.mapValue { ZigPointerType.of(size, sentinel, mutability, it) }
}

private fun computeComptime(element: ZigComptime): ZigValue {
    return resolveValue(element.innerElement as? ZigExpression)
}

private fun computeAddressOf(element: ZigAddressOf): ZigPlace {
    val operandElement = element.operand ?: return ZigPlace.constant(ZigUnknown.ofUnknownType())
    return resolvePlace(operandElement).mapType { mutability, type ->
        ZigPlace.constant(ZigUnknown.of(ZigPointerType.of(ZigPointerSize.ONE, null, mutability, type)))
    }
}

private fun computeIf(element: ZigIf): ZigValue {
    val thenValue = resolveValue(element.thenElement.expression)
    val elseValue = when (val outerElement = element.elseElement) {
        null -> ZigVoidValue
        else -> resolveValue(outerElement.expression)
    }
    return resolvePeerValues(setOf(thenValue, elseValue))
}

private fun computeCatch(element: ZigCatch): ZigValue {
    val leftValue = resolveValue(element.leftOperand).mapValue { left ->
        when (val typeOfLeft = left.typeOf) {
            is ZigErrorUnionType -> ZigUnknown.of(typeOfLeft.payload)
            else -> ZigUnknown.ofUnknownType()
        }
    }
    val rightValue = resolveValue(element.rightOperand)
    return resolvePeerValues(setOf(leftValue, rightValue))
}

private fun computeOrelse(element: ZigOrelse): ZigValue {
    val leftValue = resolveValue(element.leftOperand).mapValue { left ->
        when (val typeOfLeft = left.typeOf) {
            is ZigOptionalType -> ZigUnknown.of(typeOfLeft.child)
            else -> ZigUnknown.ofUnknownType()
        }
    }
    val rightValue = resolveValue(element.rightOperand)
    return resolvePeerValues(setOf(leftValue, rightValue))
}

private fun computeMergeErrorSets(element: ZigMergeErrorSets): ZigValue {
    val leftType = resolveValue(element.leftOperand, ZigTypeType)
    val rightType = resolveValue(element.rightOperand, ZigTypeType)
    return leftType.mapValue { left ->
        when (left) {
            is ZigErrorSetType -> rightType.mapValue { right ->
                when (right) {
                    is ZigErrorSetType -> {
                        val names = left.names + right.names
                        ZigErrorSetType.of(names.toSortedSet())
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
        if (item.isFieldInit) {
            return ZigUnknown.ofUnknownType() // TODO
        }
        fieldTypes.add(resolveValue(item.expression).typeOf)
    }
    return ZigUnknown.of(ZigTupleType.of(LightZigTuple(element.manager, fieldTypes)))
}

private fun computeSlice(element: ZigSlice): ZigPlace {
    // TODO: if the indexes are non-comptime, return a slice instead of an array of unknown length
    val length = ZigUnknown.of(ZigUsizeType)
    val sentinel = element.sentinelElement?.let { resolveValue(it.expression) }
    return resolvePlace(element.sliced).mapType { mutabilityOfSliced, typeOfSliced ->
        val (mutability, childType) = when (typeOfSliced) {
            is ZigArrayType -> Pair(mutabilityOfSliced, typeOfSliced.child)

            is ZigPointerType -> when (typeOfSliced.size) {
                ZigPointerSize.ONE -> when (val pointerChild = typeOfSliced.child) {
                    is ZigArrayType -> Pair(typeOfSliced.mutability, pointerChild.child)
                    else -> return@mapType ZigPlace.constant(ZigUnknown.ofUnknownType()) // TODO
                }

                ZigPointerSize.MANY,
                ZigPointerSize.SLICE,
                ZigPointerSize.C -> Pair(typeOfSliced.mutability, typeOfSliced.child)
            }

            else -> return@mapType ZigPlace.constant(ZigUnknown.ofUnknownType())
        }
        val arrayType = ZigArrayType.of(length, sentinel, childType)
        val pointerType = ZigPointerType.of(ZigPointerSize.ONE, null, mutability, arrayType)
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
            ZigUnknown.of(substituteTypeParams(type, typeParams))
        }
    }
}

private fun computeConstCastBuiltinCall(element: ZigBuiltinCall): ZigValue {
    return resolveValue(element.arguments.firstOrNull()).mapValue { value ->
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
            is ZigFile -> ZigContainerType.of(target.createSmartPointer(), emptyMap())
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
    return ZigContainerType.of(container.createSmartPointer(), findUnboundTypeParams(container))
}

private fun computeTypeOfBuiltinCall(element: ZigBuiltinCall): ZigValue {
    val arguments = element.arguments.toList()
    if (arguments.isEmpty()) return ZigUnknown.of(ZigTypeType)
    return if (arguments.size == 1) {
        resolveValue(arguments[0]).typeOf
    } else {
        val values = arguments.map { resolveValue(it) }.toSet()
        resolvePeerValues(values).typeOf
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
    return child.mapValue { ZigVectorType.of(length, it) }
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
    return resolvePeerValues(values)
}

private fun computeSwitch(element: ZigSwitch): ZigValue {
    val values = buildSet {
        addAll(computeValuesFromLabeledBreaks(element))
        addAll(element.prongs.map { resolveValue(it.expression) })
    }
    return resolvePeerValues(values)
}

private fun computeLoop(element: ZigLoop): ZigValue {
    val values = buildSet {
        addAll(computeValuesFromLabeledBreaks(element))
        addAll(computeValuesFromUnlabeledBreaks(element))
        add(element.elseElement?.let { resolveValue(it.expression) } ?: ZigVoidValue)
    }
    return resolvePeerValues(values)
}

private fun computeStringExpression(element: ZigStringExpression): ZigValue {
    val value = element.value
    if (value != null) {
        return ZigStringLiteralValue.of(value)
    }
    val length = ZigUnknown.of(ZigUsizeType)
    val u8 = ZigFixedIntType.of(ZigSignedness.UNSIGNED, 8)
    val array = ZigArrayType.of(length, ZigIntValue.of(ZigComptimeIntType, 0), u8)
    val pointer = ZigPointerType.of(ZigPointerSize.ONE, null, ZigMutability.CONST, array)
    return ZigUnknown.of(pointer)
}

private fun computeNumberLiteral(element: ZigNumberLiteral): ZigValue {
    return when (val result = ZigNumberLiteralParser.parse(element.text)) {
        is ZigNumberLiteralParser.IntResult -> ZigIntValue.of(ZigComptimeIntType, result.value)
        is ZigNumberLiteralParser.BigIntResult -> ZigUnknown.of(ZigComptimeIntType)
        is ZigNumberLiteralParser.FloatResult -> ZigUnknown.of(ZigComptimeFloatType)
        ZigNumberLiteralParser.FailureResult -> ZigUnknown.ofUnknownType()
    }
}

private fun computeEnumLiteral(element: ZigEnumLiteral): ZigValue {
    val name = parseZigIdentifier(element.identifierElement)
    return if (name != null) {
        ZigEnumLiteralValue.of(ZigEnumLiteralType, name)
    } else {
        ZigUnknown.of(ZigEnumLiteralType)
    }
}

private fun computeVarDecl(target: ZigVarDecl): ZigValue {
    val typeElement = target.typeElement?.expression
    if (typeElement != null) {
        return ZigUnknown.of(resolveValue(typeElement, ZigTypeType))
    }
    val initElement = target.initializer ?: return ZigUnknown.ofUnknownType()
    return when (target.mutability) {
        ZigMutability.CONST -> resolveValue(initElement)
        ZigMutability.VAR -> ZigUnknown.of(resolveValue(initElement).typeOf)
    }
}

private fun computeVarProto(target: ZigVarProto): ZigValue {
    val typeElement = target.typeElement?.expression
    if (typeElement != null) {
        return ZigUnknown.of(resolveValue(typeElement, ZigTypeType))
    }
    val destructure = target.containingAssignDestructure
    val initElement = destructure.initializer ?: return ZigUnknown.ofUnknownType()
    val destructureIndex = destructure.variables.indexOf(target)
    assert(destructureIndex >= 0)
    return resolveValue(initElement).mapValue { init ->
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

private fun computeContainerField(target: ZigContainerFieldImpl): ZigValue {
    return when (val typeElement = target.typeElement?.expression) {
        null -> when (target.container.containerKind) {
            ZigContainerKind.STRUCT,
            ZigContainerKind.OPAQUE -> ZigUnknown.ofUnknownType()
            ZigContainerKind.ENUM -> {
                val typeParams = findUnboundTypeParams(target.container)
                ZigUnknown.of(ZigContainerType.of(target.container.createSmartPointer(), typeParams))
            }
            ZigContainerKind.UNION -> when (target.container.containerArgElement) {
                null -> ZigUnknown.ofUnknownType()
                else -> ZigVoidValue
            }
        }
        else -> ZigUnknown.of(resolveValue(typeElement, ZigTypeType))
    }
}

private fun computePayloadItem(target: ZigPayloadItem): ZigPlace {
    return if (target.isPointer) {
        computePayloadItemPlace(target).mapType { mutability, type ->
            ZigPlace.constant(ZigUnknown.of(ZigPointerType.of(ZigPointerSize.ONE, null, mutability, type)))
        }
    } else {
        computePayloadItemPlace(target).mapPlace {
            ZigPlace.constant(it.value)
        }
    }
}

private fun computeThenPayload(payloadElement: ZigThenPayload): ZigPlace {
    val conditionElement =
        payloadElement.containingIf?.conditionElement?.expression
            ?: payloadElement.containingWhile?.conditionElement?.expression
            ?: return ZigPlace.unknown()
    return resolvePlace(conditionElement).mapType { mutability, typeOfCondition ->
        when (typeOfCondition) {
            is ZigOptionalType -> ZigPlace.Single.of(mutability, ZigUnknown.of(typeOfCondition.child))
            is ZigErrorUnionType -> ZigPlace.Single.of(mutability, ZigUnknown.of(typeOfCondition.payload))
            else -> ZigPlace.unknown()
        }
    }
}

private fun computeElsePayload(payloadElement: ZigElsePayload): ZigPlace {
    val conditionElement =
        payloadElement.containingIf?.conditionElement?.expression
            ?: payloadElement.containingWhile?.conditionElement?.expression
            ?: return ZigPlace.unknown()
    return resolvePlace(conditionElement).mapType { mutability, typeOfCondition ->
        when (typeOfCondition) {
            is ZigErrorUnionType -> ZigPlace.Single.of(mutability, ZigUnknown.of(typeOfCondition.errorSet))
            else -> ZigPlace.unknown()
        }
    }
}

private fun computeCatchPayload(payloadElement: ZigCatchPayload): ZigPlace {
    val leftElement = payloadElement.containingCatch.leftOperand
    return resolvePlace(leftElement).mapType { mutability, typeOfLeft ->
        when (typeOfLeft) {
            is ZigErrorUnionType -> ZigPlace.Single.of(mutability, ZigUnknown.of(typeOfLeft.errorSet))
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
                    .map { resolveValue(it.expression) }
                    .toSet()
            val prongSwitchItems =
                switchProng.items
                    .map { resolveValue(it.expression) }
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
            val prongErrorSet = ZigErrorSetType.of(prongErrorNames)

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

            resolvePlace(conditionElement).mapType { mutability, conditionType ->
                val values = when (conditionType) {
                    is ZigErrorSetType -> {
                        if (isElseProng) {
                            val names = conditionType.names.toSortedSet()
                            names.removeAll(otherErrorNames)
                            if (isInlineProng) {
                                names.asSequence().map { ZigErrorSetValue.of(ZigErrorSetType.of(setOf(it)), it) }
                            } else {
                                sequenceOf(ZigUnknown.of(ZigErrorSetType.of(names)))
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
                                    .map { resolveValueFromReferenceTo(it) }
                                    .map { substituteTypeParams(it, conditionType.typeParams) }
                            } else {
                                sequenceOf(ZigUnknown.of(conditionType))
                            }
                        } else {
                            val fieldValues =
                                conditionType.members
                                    .filterIsInstance<ZigContainerField>()
                                    .filter { it.name in prongEnumLiterals }
                                    .sortedBy { prongEnumLiterals.indexOf(it.name) }
                                    .map { resolveValueFromReferenceTo(it) }
                                    .map { substituteTypeParams(it, conditionType.typeParams) }
                            if (isInlineProng) {
                                fieldValues
                            } else {
                                // TODO: handle pointer capture of tagged union variants with incompatible types
                                sequenceOf(resolvePeerValues(fieldValues.toSet()))
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
                                        .map { ZigEnumLiteralValue.of(conditionType, it) }
                                }
                            } else {
                                sequenceOf(ZigUnknown.of(conditionType))
                            }
                        } else {
                            if (isInlineProng) {
                                prongEnumLiterals.asSequence().map { ZigEnumLiteralValue.of(conditionType, it) }
                            } else {
                                sequenceOf(ZigUnknown.of(conditionType))
                            }
                        }
                    }
                    else -> sequenceOf(ZigUnknown.of(conditionType)) // TODO
                }
                ZigPlace.Single.of(mutability, ZigMultiValue.from(values.toSet()))
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
                    .map { resolveValue(it.expression) }
                    .filterIsInstance<ZigEnumLiteralValue>()
                    .map { it.name }
                    .toSet()
            val prongEnumLiterals =
                switchProng.items
                    .map { resolveValue(it.expression) }
                    .filterIsInstance<ZigEnumLiteralValue>()
                    .map { it.name }
                    .toSet()
            resolvePlace(conditionElement).mapType { _, conditionType ->
                val values = if (conditionType is IZigContainerType && conditionType.isTaggedUnion) {
                    val tagType = ZigAnalyzer.resolveUnionTagType(conditionType) ?: return@mapType ZigPlace.unknown()
                    if (isInlineProng) {
                        if (isElseProng) {
                            sequenceOf(tagType.mapValue { type ->
                                when (type) {
                                    is IZigContainerType if type.containerKind == ZigContainerKind.ENUM -> {
                                        val values =
                                            type.members
                                                .filterIsInstance<ZigContainerField>()
                                                .mapNotNull { it.name }
                                                .filter { it !in otherEnumLiterals }
                                                .map { ZigEnumLiteralValue.of(type, it) }
                                        ZigMultiValue.from(values.toSet(), type)
                                    }
                                    else -> ZigUnknown.of(type)
                                }
                            })
                        } else {
                            sequenceOf(tagType.mapValue { type ->
                                val values = prongEnumLiterals.map { ZigEnumLiteralValue.of(type, it) }
                                ZigMultiValue.from(values.toSet(), type)
                            })
                        }
                    } else {
                        sequenceOf(ZigUnknown.of(tagType))
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
            ZigPlace.Single.of(null, ZigUnknown.of(ZigUsizeType))
        }
    }
    if (inputElement !is ZigExpression) {
        return ZigPlace.unknown()
    }
    return resolvePlace(inputElement).mapType { _, typeOfInput ->
        when (typeOfInput) {
            is ZigArrayType -> {
                if (payloadItem.isPointer) {
                    ZigPlace.unknown()
                } else {
                    ZigPlace.Single.of(null, ZigUnknown.of(typeOfInput.child))
                }
            }
            is ZigPointerType -> when (typeOfInput.size) {
                ZigPointerSize.SLICE -> ZigPlace.Single.of(typeOfInput.mutability, ZigUnknown.of(typeOfInput.child))
                ZigPointerSize.ONE -> when (val pointerChild = typeOfInput.child) {
                    is ZigArrayType -> ZigPlace.Single.of(typeOfInput.mutability, ZigUnknown.of(pointerChild.child))
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
        when (variable) {
            is ZigVarProto -> resolveValue(variable.typeElement?.expression, ZigTypeType)
            is ZigExpression -> resolveValue(variable).typeOf
            else -> ZigUnknown.of(ZigTypeType)
        }
    }
    return ZigTupleType.of(LightZigTuple(childElement.manager, fields.toList()))
}

private fun inferListInitItem(
    parentElement: ZigListInitItem,
    childElement: ZigExpression,
): ZigValue {
    if (parentElement.expression !== childElement) {
        return ZigUnknown.of(ZigTypeType)
    }
    val initElement = parentElement.containingListInit
    return if (parentElement.isFieldInit) {
        val name = parentElement.fieldName ?: return ZigUnknown.of(ZigTypeType)
        ZigAnalyzer.resolveOrInferListInitType(initElement).mapValue { type ->
            if (type !is IZigContainerType) {
                return@mapValue ZigUnknown.of(ZigTypeType)
            }
            val member = type.members.firstNotNullOfOrNull {
                if (it is ZigContainerField && it.name == name) it else null
            }
            if (member != null) {
                var value = resolveValueFromReferenceTo(member).typeOf
                value = substituteTypeParams(value, type.typeParams)
                value
            } else {
                ZigUnknown.of(ZigTypeType)
            }
        }
    } else {
        ZigAnalyzer.resolveOrInferListInitType(initElement).mapValue { type ->
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
    return resolveValue(conditionElement).mapValue {
        val type = it.typeOf
        if (type is IZigContainerType && type.isTaggedUnion) {
            val tagType = ZigAnalyzer.resolveUnionTagType(type) ?: return@mapValue ZigUnknown.of(ZigTypeType)
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
    types +=
        parentElement.containingSwitch.prongs
            .filter { it !== parentElement }
            .map { resolveValue(it.expression).typeOf }
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
    types += resolveValue(parentElement.leftOperand).mapValue { left ->
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
    types += resolveValue(parentElement.leftOperand).mapValue { left ->
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
    return inferResultType(parentElement).mapValue {
        when (val type = ZigAnalyzer.findInitializerType(it)) {
            is ZigPointerType -> when (type.size) {
                ZigPointerSize.ONE -> type.child

                ZigPointerSize.SLICE -> {
                    val length = ZigUnknown.of(ZigUsizeType)
                    ZigArrayType.of(length, null, type.child)
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
        resolveValue(typeElement).mapValue { ZigErrorUnionType.of(errorSet, it) }
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
    types +=
        breakElements
            .filter { it !== parentElement }
            .map { resolveBreakValue(it).typeOf }
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
    if (parentElement.innerElement !== childElement) {
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
        return inferResultType(grandparentElement).mapValue { ZigErrorUnionType.of(errorSet, it) }
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
            val functionType = function.typeOf as? ZigFunctionType
                ?: return@blk ZigUnknown.of(ZigTypeType)
            val functionElement = functionType.function.dereference()
                ?: return@blk ZigUnknown.of(ZigTypeType)
            val paramIndex = if (isMethod) argIndex + 1 else argIndex
            val paramElement = functionElement.parameters.elementAtOrNull(paramIndex)
                ?: return@blk ZigUnknown.of(ZigTypeType)
            var value = resolveValue(paramElement.typeElement?.expression, ZigTypeType)
            val typeParams = computeTypeParams(functionType, arguments)
            if (typeParams.isNotEmpty()) {
                value = substituteTypeParams(value, typeParams)
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
            substituteTypeParams(resolveValue(typeElement), typeParams)
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
    return resolveValue(firstArgElement).mapValue {
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
// https://codeberg.org/ziglang/zig/src/tag/0.17.0/src/Sema.zig#L32697
private fun resolvePeerValues(values: Set<ZigValue>): ZigValue {
    when (values.size) {
        0 -> return ZigUnknown.ofUnknownType()
        1 -> return values.single()
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
            return ZigMultiValue.from(values)
        }
        return ZigUnknown.of(firstType)
    }

    return when (val type = resolvePeerTypesInner(tys.toMutableList(), vals.toMutableList())) {
        null -> ZigMultiValue.from(values)
        ZigTypeType -> ZigMultiValue.from(values.filter { it.typeOf == ZigTypeType }.toSet())
        else -> ZigUnknown.of(type)
    }
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
                    null -> EXACT
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
                    finalSet = ZigErrorSetType.of(names.toSortedSet())
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
                    finalSet = ZigErrorSetType.of(names.toSortedSet())
                } else {
                    finalSet = setTy
                }
            }
            val finalPayload = resolvePeerTypesInner(peerTys, peerVals) ?: return null
            return ZigErrorUnionType.of(finalSet!!, finalPayload)
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
            return ZigOptionalType.of(childTy)
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
                ptrTy = ZigPointerType.of(ZigPointerSize.C, null, ptrMutability, ptrChild)
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
                    is ZigFunctionType -> ZigPointerType.of(ZigPointerSize.ONE, null, ZigMutability.CONST, ty)
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
                                        ptrChild = ZigArrayType.of(
                                            length = ZigIntValue.of(ZigUsizeType, curArr.length),
                                            sentinel = null,
                                            child = curArr.child
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
                            is ZigArrayType -> ptrChild =
                                ZigArrayType.of(
                                    length = pointee.length,
                                    sentinel = curSentinel,
                                    child = pointee.child
                                )
                            else -> throw IllegalStateException()
                        } else {
                            ptrSentinel = curSentinel
                        }
                        return@sentinel
                    }
                    ptrSentinel = null
                    if (ptrSize == ZigPointerSize.ONE) when (val pointee = ptrChild) {
                        is ZigArrayType -> ptrChild = ZigArrayType.of(
                            length = pointee.length,
                            child = pointee.child,
                            sentinel = null,
                        )
                        else -> {}
                    }
                }
                ptrTy = ZigPointerType.of(ptrSize, ptrSentinel, ptrMutability, ptrChild)
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
                            curTy = ZigFixedFloatType.of(bits)
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
            return ZigTupleType.of(LightZigTuple(manager, fieldTypes))
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
// https://codeberg.org/ziglang/zig/src/tag/0.17.0/src/Type.zig#L1952-L1962
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
