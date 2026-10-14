package io.github.fncontroloption.zig

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.icons.AllIcons
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.elementType
import com.intellij.psi.util.findParentOfType
import com.intellij.psi.util.parents

class ZigCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val element = position.findParentOfType<ZigElement>() ?: return
        when (element) {
            is ZigIdentifier if element.identifierElement === position -> {
                fillIdentifierVariants(element, result)
                fillErrorValueVariants(element, "error.", result)
                // TODO: add insert handler that also replaces identifier after `error.` (if any)
            }
            is ZigTestDecl if element.nameElement === position && position.elementType === ZigTokenType.IDENTIFIER -> {
                fillTestVariants(element, result)
            }
            is ZigLabelRef if element.identifierElement === position -> {
                fillLabelVariants(element, result)
            }
            is ZigFieldAccess if element.identifierElement === position -> {
                fillFieldAccessVariants(parameters, element, result)
            }
            is ZigErrorValueExpression if element.identifierElement === position -> {
                fillErrorValueVariants(element, "", result)
            }
            is ZigEnumLiteral if element.identifierElement === position -> {
                val parent = element.parent
                if (parent is ZigListInitItem && !parent.isFieldInit) {
                    fillFieldInitVariants(parent, result)
                }
                fillEnumLiteralVariants(parameters, element, result)
            }
            is ZigListInitItem if element.fieldNameIdentifier === position -> {
                fillFieldInitVariants(element, result)
            }
            is ZigBuiltinCall if element.builtinElement === position -> {
                fillBuiltinCallVariants(element, result)
            }
            else -> {}
        }
    }
}

private object ZigFieldWeigherByDefaultValue : LookupElementWeigher("zigFieldDefaultValue") {
    enum class Weight { NO_DEFAULT, HAS_DEFAULT }

    override fun weigh(element: LookupElement): Weight? {
        val field = element.`object` as? ZigContainerField ?: return null
        return if (field.initializer == null) Weight.NO_DEFAULT else Weight.HAS_DEFAULT
    }
}

private object ZigMemberWeigherByDeclOrder : LookupElementWeigher("zigMemberDeclOrder") {
    override fun weigh(element: LookupElement): Int? {
        val member = element.`object` as? ZigContainerMember ?: return null
        val container = member.container ?: return null
        val index = container.members.indexOf(member)
        return if (index >= 0) index else null
    }
}

private class FunctionInsertHandler(val hasParams: Boolean) : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val project = context.project
        val editor = context.editor
        val document = editor.document
        val tailOffset = context.tailOffset
        val text = document.charsSequence
        when (context.completionChar) {
            Lookup.NORMAL_SELECT_CHAR -> {
                document.insertString(tailOffset, "()")
                if (hasParams) {
                    editor.caretModel.moveToOffset(tailOffset + 1)
                } else {
                    editor.caretModel.moveToOffset(tailOffset + 2)
                }
                AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
            }

            Lookup.REPLACE_SELECT_CHAR,
            Lookup.AUTO_INSERT_SELECT_CHAR -> {
                val tail = text.subSequence(tailOffset, text.length)
                var foundCallArgs = false
                if (tail.startsWith("()")) {
                    // no-op
                } else if (tail.startsWith('(')) {
                    foundCallArgs = true
                } else {
                    document.insertString(tailOffset, "()")
                }
                if (hasParams || foundCallArgs) {
                    editor.caretModel.moveToOffset(tailOffset + 1)
                } else {
                    editor.caretModel.moveToOffset(tailOffset + 2)
                }
                AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null)
            }

            // TODO
            else -> {}
        }
    }
}

private object FieldInitInsertHandler : InsertHandler<LookupElement> {
    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val editor = context.editor
        val document = editor.document
        val tailOffset = context.tailOffset
        val text = document.charsSequence
        when (context.completionChar) {
            Lookup.NORMAL_SELECT_CHAR -> {
                document.insertString(tailOffset, " = ")
                editor.caretModel.moveToOffset(tailOffset + 3)
            }

            Lookup.REPLACE_SELECT_CHAR,
            Lookup.AUTO_INSERT_SELECT_CHAR -> {
                val tail = text.subSequence(tailOffset, text.length)
                if (tail.startsWith(" = ")) {
                    // no-op
                } else if (tail.startsWith(" =")) {
                    document.insertString(tailOffset + 2, " ")
                } else if (tail.startsWith(' ')) {
                    document.insertString(tailOffset + 1, "= ")
                } else {
                    document.insertString(tailOffset, " = ")
                }
                editor.caretModel.moveToOffset(tailOffset + 3)
            }

            // TODO
            else -> {}
        }
    }
}

internal fun getFunctionParamTypes(
    functionElement: ZigFnOrBuiltinFnDeclOrProto,
    genericParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
): Triple<List<ZigFnParam>, List<ZigValue?>, Boolean> {
    val (params, paramTypes) = functionElement.parameters.map { param ->
        param to param.typeElement?.expression?.let {
            val initialValue = ZigAnalyzer.resolveValue(it)
            ZigAnalyzer.substituteGenericParams(initialValue, genericParams)
        }
    }.unzip()
    return Triple(params, paramTypes, functionElement.hasVarargs)
}

private fun getFunctionReceiverType(paramTypes: List<ZigValue?>): ZigValue? {
    val firstParamType = paramTypes.firstOrNull() ?: return null
    if (firstParamType is ZigPointerType && firstParamType.size == ZigPointerSize.ONE) {
        return firstParamType.child
    }
    return firstParamType
}

private fun getFunctionTailText(
    params: List<ZigFnParam>,
    paramTypes: List<ZigValue?>,
    hasVarargs: Boolean,
): String {
    return buildString {
        append('(')
        for (i in params.indices) {
            if (i > 0) {
                append(", ")
            }
            append(getParameterText(params[i], paramTypes[i]))
        }
        if (hasVarargs) {
            if (params.isNotEmpty()) {
                append(", ")
            }
            append("...")
        }
        append(')')
    }
}

internal fun getParameterText(param: ZigFnParam, type: ZigValue?): String {
    return buildString {
        val name = param.name
        if (name != null) {
            append(name)
            append(": ")
        }
        if (param.isAnytype) {
            assert(type == null)
            append("anytype")
        } else if (type != null) {
            append(type.toPlainText())
        } else {
            append('_')
        }
    }
}

private fun getFunctionTypeText(functionType: ZigFunctionType): String {
    val element = functionType.function.dereference() ?: return "_"
    return getFunctionTypeText(element, functionType.genericParams)
}

private fun getFunctionTypeText(
    functionElement: ZigFnOrBuiltinFnDeclOrProto,
    genericParams: Map<SmartPsiElementPointer<ZigFnParam>, ZigValue?>,
): String {
    if (functionElement is ZigBuiltinFnProto && functionElement.returnsAnytype) {
        return "anytype"
    }
    val returnType = functionElement.returnTypeElement?.expression?.let {
        val initialValue = ZigAnalyzer.resolveValue(it)
        ZigAnalyzer.substituteGenericParams(initialValue, genericParams)
    }
    if (returnType == null) {
        return "_"
    }
    val text = returnType.toPlainText()
    return if (functionElement.bangTokenElement != null) "!$text" else text
}

private fun getFieldInitVariants(
    containerType: ZigContainerType,
    fieldsToExclude: Set<String>,
): Sequence<LookupElement> {
    if (containerType.containerKind == ZigContainerKind.ENUM) {
        return emptySequence()
    }
    return containerType.members.filterIsInstance<ZigContainerField>().mapNotNull { member ->
        val name = member.name ?: return@mapNotNull null
        if (name in fieldsToExclude) return@mapNotNull null
        val initialValue = ZigAnalyzer.resolveValueFromReferenceTo(member)
        val value = ZigAnalyzer.substituteGenericParams(initialValue, containerType.genericParams)
        val initializer = member.initializer
        val tailText = buildString {
            append(" =")
            if (initializer != null) {
                append(' ')
                val initializerText = initializer.text
                val newline = initializerText.indexOf('\n')
                if (newline < 0) {
                    append(initializerText)
                } else {
                    append(initializerText.substring(0, newline))
                    append("...")
                }
            }
        }
        buildLookupElement(member, name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)
            .withIcon(AllIcons.Nodes.Field)
            .withTailText(tailText)
            .withTypeText(value.typeOf.toPlainText())
            .withInsertHandler(FieldInitInsertHandler)
    }
}

private fun createLookupElementWithType(
    element: ZigNamedElement,
    name: String,
    type: ZigValue?,
    quote: ZigIdQuoteBehavior,
): LookupElement {
    return if (type != null) {
        buildLookupElement(element, name, quote)
            .withIcon(element.getIcon(0))
            .withTypeText(type.toPlainText())
    } else {
        buildLookupElement(element, name, quote)
    }
}

private fun buildLookupElement(
    element: ZigNamedElement,
    name: String,
    quote: ZigIdQuoteBehavior,
): LookupElementBuilder =
    LookupElementBuilder.create(element, renderZigIdentifier(name, quote))
        .withLookupString(name)
        .withLookupString(escapeZigIdentifier(name))

private fun squashVariants(set: Set<LookupElement>): Collection<LookupElement> {
    // Limitation: We can only provide one lookup object per lookup element.
    return set.groupBy { it.lookupString }.map { (_, list) -> list.first() }
}

/** @see ZigIdentifierReference.multiResolve */
private fun fillIdentifierVariants(element: ZigIdentifier, result: CompletionResultSet) {
    val variants = mutableSetOf<LookupElement>()
    for (candidate in identifierReferenceCandidates(element)) {
        val name = candidate.name ?: continue
        when (val type = ZigAnalyzer.resolveValueFromReferenceTo(candidate).typeOf) {
            is ZigFunctionType -> {
                val (params, paramTypes, hasVarargs) = when (val element = type.function.dereference()) {
                    null -> Triple(emptyList(), emptyList(), true)
                    else -> getFunctionParamTypes(element, type.genericParams)
                }
                val isMethod = when (val receiverType = getFunctionReceiverType(paramTypes)) {
                    is ZigPsiContainerType -> receiverType.container.dereference() == candidate.parent
                    else -> false
                }
                variants.add(
                    buildLookupElement(candidate, name, ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING)
                        .withIcon(if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function)
                        .withTailText(getFunctionTailText(params, paramTypes, hasVarargs))
                        .withTypeText(getFunctionTypeText(type))
                        .withInsertHandler(FunctionInsertHandler(params.isNotEmpty() || hasVarargs))
                )
            }

            else -> {
                variants.add(
                    createLookupElementWithType(
                        candidate,
                        name,
                        type,
                        ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING,
                    )
                )
            }
        }
    }
    result.addAllElements(squashVariants(variants))
}

/** @see ZigTestReference.multiResolve */
private fun fillTestVariants(element: ZigTestDecl, result: CompletionResultSet) {
    val variants = mutableSetOf<LookupElement>()
    for (candidate in identifierReferenceCandidates(element)) {
        val name = candidate.name ?: continue
        when (candidate) {
            is ZigFnDecl -> {}
            is ZigVarDecl -> if (candidate.isLocal) continue
            else -> continue
        }
        val icon = when (val type = ZigAnalyzer.resolveValueFromReferenceTo(candidate).typeOf) {
            is ZigFunctionType -> {
                val paramTypes = when (val element = type.function.dereference()) {
                    null -> emptyList()
                    else -> getFunctionParamTypes(element, type.genericParams).component2()
                }
                val isMethod = when (val receiverType = getFunctionReceiverType(paramTypes)) {
                    is ZigPsiContainerType -> receiverType.container.dereference() == candidate.parent
                    else -> false
                }
                if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function
            }
            else -> candidate.getIcon(0)
        }
        variants.add(buildLookupElement(candidate, name, ZigIdQuoteBehavior.PRESERVE_WHEN_SHADOWING).withIcon(icon))
    }
    result.addAllElements(squashVariants(variants))
}

/** @see ZigLabelReference.resolve */
private fun fillLabelVariants(element: ZigLabelRef, result: CompletionResultSet) {
    val variants = mutableSetOf<LookupElement>()
    for (parent in element.parents(withSelf = false)) {
        if (parent is ZigContainer) {
            break
        }
        if (parent is ZigLabelable) {
            val labelDecl = parent.labelDecl
            val label = parseZigIdentifier(labelDecl?.identifierElement)
            if (labelDecl != null && label != null) {
                variants.add(buildLookupElement(labelDecl, label, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
            }
        }
    }
    result.addAllElements(squashVariants(variants))
}

/** @see ZigFieldAccessReference.multiResolve */
private fun fillFieldAccessVariants(
    parameters: CompletionParameters,
    element: ZigFieldAccess,
    result: CompletionResultSet,
) {
    val leftValue = ZigAnalyzer.resolveValue(element.leftOperand)
    val originalFile = element.containingFile
    val variants = mutableSetOf<LookupElement>()
    leftValue.forEachValue { left ->
        val typeOfLeft = left.typeOf
        when (typeOfLeft) {
            is ZigOptionalType -> {
                variants.add(LookupElementBuilder.create("?").withTypeText(typeOfLeft.child.toPlainText()))
            }
            is ZigPointerType if typeOfLeft.size == ZigPointerSize.ONE -> {
                variants.add(LookupElementBuilder.create("*").withTypeText(typeOfLeft.child.toPlainText()))
            }
            else -> {}
        }
        for (fieldName in ZigAnalyzer.getBuiltinFields(typeOfLeft)) {
            val fieldType = ZigAnalyzer.getBuiltinFieldType(typeOfLeft, fieldName)
            variants.add(
                LookupElementBuilder.create(renderZigIdentifier(fieldName, ZigIdQuoteBehavior.EAGERLY_UNQUOTE))
                    .withLookupString(fieldName)
                    .withLookupString(escapeZigIdentifier(fieldName))
                    .withIcon(AllIcons.Nodes.Field)
                    .withTypeText(fieldType?.toPlainText())
            )
        }
    }
    for ((containerType, isInstance) in ZigAnalyzer.fieldAccessContainers(leftValue)) {
        for (member in containerType.getNamedMembersVisibleFrom(originalFile)) {
            val name = member.name ?: continue
            if (member is ZigContainerField) {
                if (containerType.containerKind == ZigContainerKind.ENUM) {
                    if (isInstance || member.nameIdentifier?.textMatches("_") == true) {
                        continue
                    }
                    variants.add(
                        createLookupElementWithType(
                            member,
                            name,
                            containerType,
                            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                        )
                    )
                } else if (isInstance) {
                    val initialValue = ZigAnalyzer.resolveValueFromReferenceTo(member)
                    val value = ZigAnalyzer.substituteGenericParams(initialValue, containerType.genericParams)
                    variants.add(
                        createLookupElementWithType(
                            member,
                            name,
                            value.typeOf,
                            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                        )
                    )
                } else if (containerType.isTaggedUnion) {
                    val type = ZigAnalyzer.resolveUnionTagType(containerType)
                    variants.add(
                        createLookupElementWithType(
                            member,
                            name,
                            type,
                            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                        )
                    )
                }
                continue
            }
            val initialValue = ZigAnalyzer.resolveValueFromReferenceTo(member)
            val value = ZigAnalyzer.substituteGenericParams(initialValue, containerType.genericParams)
            when (val type = value.typeOf) {
                is ZigFunctionType -> {
                    var (params, paramTypes, hasVarargs) = when (val element = type.function.dereference()) {
                        null -> Triple(emptyList(), emptyList(), true)
                        else -> getFunctionParamTypes(element, containerType.genericParams)
                    }
                    val isMethod = getFunctionReceiverType(paramTypes) == containerType
                    if (isInstance) {
                        if (!isMethod) {
                            continue
                        }
                        params = params.subList(1, params.size)
                        paramTypes = paramTypes.subList(1, paramTypes.size)
                    }
                    variants.add(
                        buildLookupElement(member, name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)
                            .withIcon(if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function)
                            .withTailText(getFunctionTailText(params, paramTypes, hasVarargs))
                            .withTypeText(getFunctionTypeText(type))
                            .withInsertHandler(FunctionInsertHandler(params.isNotEmpty() || hasVarargs))
                    )
                }

                else -> {
                    if (isInstance) {
                        continue
                    }
                    variants.add(
                        createLookupElementWithType(
                            member,
                            name,
                            type,
                            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                        )
                    )
                }
            }
        }
    }
    val sorter =
        CompletionSorter
            .defaultSorter(parameters, result.prefixMatcher)
            .weigh(ZigMemberWeigherByDeclOrder)
    result.withRelevanceSorter(sorter).addAllElements(squashVariants(variants))
}

private fun fillErrorValueVariants(element: ZigExpression, prefix: String, result: CompletionResultSet) {
    val errorValuesToExclude = when (val parent = element.parent) {
        is ZigSwitchItem -> {
            parent.containingSwitchProng.containingSwitch.prongs
                .flatMap { it.items }
                .map { ZigAnalyzer.resolveValue(it.expression) }
                .filterIsInstance<ZigErrorValue>()
                .map { it.name }
                .toSet()
        }
        else -> emptySet()
    }
    val variants = mutableSetOf<LookupElement>()
    ZigAnalyzer.inferResultType(element).forEachValue { originalType ->
        var type = originalType
        while (type is ZigOptionalType) type = type.child
        if (type is ZigErrorUnionType) type = type.errorSet
        if (type !is ZigErrorSetType) return@forEachValue
        for (name in type.names) {
            if (name in errorValuesToExclude) continue
            variants.add(
                LookupElementBuilder.create("${prefix}${renderZigIdentifier(name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)}")
                    .withLookupString("${prefix}${name}")
                    .withLookupString("${prefix}${escapeZigIdentifier(name)}")
            )
        }
    }
    result.addAllElements(squashVariants(variants))
}

/** @see ZigEnumLiteralReference.multiResolve */
private fun fillEnumLiteralVariants(
    parameters: CompletionParameters,
    element: ZigEnumLiteral,
    result: CompletionResultSet,
) {
    val originalFile = element.containingFile
    val enumFieldsToExclude = when (val parent = element.parent) {
        is ZigSwitchItem -> {
            parent.containingSwitchProng.containingSwitch.prongs
                .flatMap { it.items }
                .map { ZigAnalyzer.resolveValue(it.expression) }
                .filterIsInstance<ZigEnumLiteralValue>()
                .map { it.name }
                .toSet()
        }
        else -> emptySet()
    }
    val variants = mutableSetOf<LookupElement>()
    ZigAnalyzer.inferResultType(element).forEachValue { originalType ->
        val containerType = ZigAnalyzer.findEnumLiteralContainerType(originalType) ?: return@forEachValue
        for (member in containerType.getNamedMembersVisibleFrom(originalFile)) {
            val name = member.name ?: continue
            if (member is ZigContainerField) {
                if (containerType.containerKind == ZigContainerKind.ENUM) {
                    if (member.nameIdentifier?.textMatches("_") == true) {
                        continue
                    }
                    if (name in enumFieldsToExclude) {
                        continue
                    }
                    variants.add(
                        createLookupElementWithType(
                            member,
                            name,
                            containerType,
                            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                        )
                    )
                } else if (containerType.isTaggedUnion) {
                    val initialValue = ZigAnalyzer.resolveValueFromReferenceTo(member)
                    val value = ZigAnalyzer.substituteGenericParams(initialValue, containerType.genericParams)
                    val type = value.typeOf
                    if (type == ZigVoidType) {
                        variants.add(
                            createLookupElementWithType(
                                member,
                                name,
                                containerType,
                                ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                            )
                        )
                    } else {
                        // TODO: add specialized completion item for non-void tagged union field
                    }
                }
                continue
            }
            val initialValue = ZigAnalyzer.resolveValueFromReferenceTo(member)
            val value = ZigAnalyzer.substituteGenericParams(initialValue, containerType.genericParams)
            when (val type = value.typeOf) {
                is ZigFunctionType -> {
                    // TODO: check if return type can coerce to original type
                    val (params, paramTypes, hasVarargs) = when (val element = type.function.dereference()) {
                        null -> Triple(emptyList(), emptyList(), true)
                        else -> getFunctionParamTypes(element, containerType.genericParams)
                    }
                    val isMethod = getFunctionReceiverType(paramTypes) == containerType
                    variants.add(
                        buildLookupElement(member, name, ZigIdQuoteBehavior.EAGERLY_UNQUOTE)
                            .withIcon(if (isMethod) AllIcons.Nodes.Method else AllIcons.Nodes.Function)
                            .withTailText(getFunctionTailText(params, paramTypes, hasVarargs))
                            .withTypeText(getFunctionTypeText(type))
                            .withInsertHandler(FunctionInsertHandler(params.isNotEmpty() || hasVarargs))
                    )
                }

                else -> {
                    // TODO: check if decl type can coerce to original type
                    variants.add(
                        createLookupElementWithType(
                            member,
                            name,
                            type,
                            ZigIdQuoteBehavior.EAGERLY_UNQUOTE,
                        )
                    )
                }
            }
        }
    }
    val sorter =
        CompletionSorter
            .defaultSorter(parameters, result.prefixMatcher)
            .weigh(ZigMemberWeigherByDeclOrder)
    result.withRelevanceSorter(sorter).addAllElements(squashVariants(variants))
}

/** @see ZigListInitItemReference.multiResolve */
private fun fillFieldInitVariants(element: ZigListInitItem, result: CompletionResultSet) {
    val listInit = element.containingListInit
    val fieldsToExclude = listInit.initItems.mapNotNull { it.fieldName }.toSet()
    val listType = ZigAnalyzer.resolveOrInferListInitType(listInit)
    val variants = mutableSetOf<LookupElement>()
    listType.forEachValue { type ->
        val containerType = type as? ZigContainerType ?: return@forEachValue
        variants.addAll(getFieldInitVariants(containerType, fieldsToExclude))
    }
    val sorter =
        CompletionSorter
            .emptySorter()
            .weigh(ZigFieldWeigherByDefaultValue)
            .weighAfter(ZigFieldWeigherByDefaultValue.toString(), ZigMemberWeigherByDeclOrder)
    result.withRelevanceSorter(sorter).addAllElements(squashVariants(variants))
}

/** @see ZigBuiltinCallReference.resolve */
private fun fillBuiltinCallVariants(element: ZigBuiltinCall, result: CompletionResultSet) {
    val variants = mutableSetOf<LookupElement>()
    for (builtin in ZigBuiltinFunctionsService.getAll(element.project)) {
        val name = builtin.builtinElement.text
        val (params, paramTypes) = getFunctionParamTypes(builtin, emptyMap())
        val hasVarargs = builtin.hasVarargs
        variants.add(
            LookupElementBuilder.create(builtin, name)
                .withIcon(AllIcons.Nodes.Function)
                .withTailText(getFunctionTailText(params, paramTypes, hasVarargs))
                .withTypeText(getFunctionTypeText(builtin, emptyMap()))
                .withInsertHandler(FunctionInsertHandler(params.isNotEmpty() || hasVarargs))
        )
    }
    result.addAllElements(squashVariants(variants))
}
