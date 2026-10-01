package io.github.fncontroloption.zig

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.util.elementType
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.siblings
import com.intellij.psi.util.startOffset
import io.github.fncontroloption.zig.psi2.ZigCall
import io.github.fncontroloption.zig.psi2.ZigTokenType

private fun isInsideCallArgs(call: ZigCall, offset: Int): Boolean {
    return call.leftParenElement.startOffset < offset && offset < call.endOffset
}

data class ZigParameterInfoItem(val text: String, val paramRanges: List<Pair<Int, Int>>)

class ZigParameterInfoHandler : ParameterInfoHandler<ZigCall, ZigParameterInfoItem> {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): ZigCall? {
        var element = context.file.findElementAt(context.offset)
        var call: ZigCall? = null
        while (element != null) {
            if (element is ZigCall && isInsideCallArgs(element, context.offset)) {
                call = element
                break
            }
            element = element.parent
        }
        if (call == null) {
            return null
        }
        val infoItems = computeCalledFuncAndSelfArg(call).mapNotNull { (function, selfArg) ->
            val functionType = function.typeOf as? ZigFunctionType ?: return@mapNotNull null
            var (params, paramTypes) = getFunctionParamTypes(functionType)
            if (selfArg != null) {
                params = params.subList(1, params.size)
                paramTypes = paramTypes.subList(1, paramTypes.size)
            }
            if (params.isEmpty()) {
                return@mapNotNull ZigParameterInfoItem("<no parameters>", emptyList())
            }
            val paramRanges = mutableListOf<Pair<Int, Int>>()
            val text = buildString {
                for (i in params.indices) {
                    if (i > 0) {
                        append(", ")
                    }
                    val start = length
                    append(getParameterText(params[i], paramTypes[i]))
                    val end = length
                    paramRanges.add(start to end)
                }
            }
            ZigParameterInfoItem(text, paramRanges)
        }
        context.itemsToShow = infoItems.toSet().toTypedArray()
        return call
    }

    override fun showParameterInfo(element: ZigCall, context: CreateParameterInfoContext) {
        context.showHint(element, element.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): ZigCall? {
        val call = context.parameterOwner as? ZigCall ?: return null
        return if (isInsideCallArgs(call, context.offset)) call else null
    }

    override fun updateParameterInfo(parameterOwner: ZigCall, context: UpdateParameterInfoContext) {
        var currentParam = -1
        for (child in parameterOwner.firstChild.siblings()) {
            if (context.offset <= child.startOffset) {
                break
            }
            when (child.elementType) {
                ZigTokenType.L_PAREN -> currentParam = 0
                ZigTokenType.COMMA -> currentParam += 1
                else -> {}
            }
        }
        context.setCurrentParameter(currentParam)
    }

    override fun updateUI(p: ZigParameterInfoItem?, context: ParameterInfoUIContext) {
        if (p == null) {
            return
        }
        val (highlightStart, highlightEnd) = p.paramRanges.getOrNull(context.currentParameterIndex) ?: (-1 to -1)
        context.setupUIComponentPresentation(
            p.text, highlightStart, highlightEnd, false,
            false, false, context.defaultParameterColor
        )
    }
}
