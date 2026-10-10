package io.github.fncontroloption.zig

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.icons.AllIcons
import com.intellij.psi.util.findParentOfType
import io.github.fncontroloption.zig.psi2.*

class ZigCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val element = position.findParentOfType<ZigElement>() ?: return
        when (element) {
            is ZigFieldAccess if element.identifierElement === position -> {
                fillFieldAccessVariants(parameters, element, result)
            }
            is ZigEnumLiteral if element.identifierElement === position -> {
                val parentElement = element.parent
                if (parentElement is ZigListInitItem && !parentElement.isFieldInit) {
                    fillFieldInitVariants(parentElement, result)
                }
            }
            is ZigListInitItem if element.fieldNameIdentifier === position -> {
                fillFieldInitVariants(element, result)
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
    ZigAnalyzer.forEachFieldAccessContainer(leftValue) { containerType, isInstance ->
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
                    val value = ZigAnalyzer.substituteTypeParams(initialValue, containerType.typeParams)
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
            val value = ZigAnalyzer.substituteTypeParams(initialValue, containerType.typeParams)
            when (val type = value.typeOf) {
                is ZigFunctionType -> {
                    var (params, paramTypes) = getFunctionParamTypes(type, containerType.typeParams)
                    val hasVarargs = type.element.hasVarargs
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
    result.withRelevanceSorter(sorter).addAllElements(variants)
}

/** @see ZigListInitItemReference.multiResolve */
private fun fillFieldInitVariants(element: ZigListInitItem, result: CompletionResultSet) {
    val listInit = element.containingListInit
    val fieldsToExclude = listInit.initItems.mapNotNull { it.fieldName }.toSet()
    val listType = ZigAnalyzer.resolveOrInferListInitType(listInit)
    val variants = mutableSetOf<LookupElement>()
    listType.forEachValue { type ->
        val containerType = type as? IZigContainerType ?: return@forEachValue
        variants.addAll(getFieldInitVariants(containerType, fieldsToExclude))
    }
    val sorter =
        CompletionSorter
            .emptySorter()
            .weigh(ZigFieldWeigherByDefaultValue)
            .weighAfter(ZigFieldWeigherByDefaultValue.toString(), ZigMemberWeigherByDeclOrder)
    result.withRelevanceSorter(sorter).addAllElements(variants)
}
