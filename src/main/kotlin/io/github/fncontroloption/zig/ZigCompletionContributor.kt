package io.github.fncontroloption.zig

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionSorter
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.psi.util.findParentOfType
import io.github.fncontroloption.zig.psi2.*

class ZigCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val element = position.findParentOfType<ZigElement>() ?: return
        when (element) {
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
