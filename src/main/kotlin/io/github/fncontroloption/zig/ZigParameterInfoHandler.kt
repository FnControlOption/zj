package io.github.fncontroloption.zig

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.util.elementType
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.siblings
import com.intellij.psi.util.startOffset

private fun isInsideCallArgs(call: ZigCallOrBuiltinCall, offset: Int): Boolean {
    return call.leftParenElement.startOffset < offset && offset < call.endOffset
}

class ZigParameterInfoHandler : ParameterInfoHandler<ZigCallOrBuiltinCall, ZigParameterInfoHandler.Item> {
    data class Item(
        val text: String,
        val paramRanges: List<Pair<Int, Int>>,
        val varargsRange: Pair<Int, Int>?,
    )

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): ZigCallOrBuiltinCall? {
        var element = context.file.findElementAt(context.offset)
        var call: ZigCallOrBuiltinCall? = null
        while (element != null) {
            if (element is ZigCallOrBuiltinCall && isInsideCallArgs(element, context.offset)) {
                call = element
                break
            }
            element = element.parent
        }
        if (call == null) {
            return null
        }
        val candidates = mutableSetOf<Triple<List<ZigFnParam>, List<ZigValue?>, Boolean>>()
        when (call) {
            is ZigCall -> mapCalledFuncAndSelfArg(call) blk@{ function, selfArg ->
                val functionType = function.typeOf as? ZigFunctionType ?: return@blk ZigVoidValue
                val functionInfo = ZigFunctionInfo.of(null, functionType.function, functionType.genericParams)
                var params = functionInfo.params
                var paramTypes = functionInfo.paramTypes
                val hasVarargs = functionInfo.hasVarargs
                if (selfArg != null) {
                    params = params.subList(1, params.size)
                    paramTypes = paramTypes.subList(1, paramTypes.size)
                }
                candidates.add(Triple(params, paramTypes, hasVarargs))
                ZigVoidValue
            }
            is ZigBuiltinCall -> {
                val name = call.builtinElement.text
                val builtin = ZigBuiltinFunctionsService.getBuiltinFnProto(call.project, name) ?: return null
                val functionInfo = ZigFunctionInfo.of(null, builtin, emptyMap())
                val params = functionInfo.params
                val paramTypes = functionInfo.paramTypes
                val hasVarargs = functionInfo.hasVarargs
                candidates.add(Triple(params, paramTypes, hasVarargs))
            }
        }
        val infoItems = candidates.map { (params, paramTypes, hasVarargs) ->
            if (params.isEmpty() && !hasVarargs) {
                return@map Item("<no parameters>", emptyList(), null)
            }
            val paramRanges = mutableListOf<Pair<Int, Int>>()
            var varargsRange: Pair<Int, Int>? = null
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
                if (hasVarargs) {
                    if (params.isNotEmpty()) {
                        append(", ")
                    }
                    val start = length
                    append("...")
                    val end = length
                    varargsRange = start to end
                }
            }
            Item(text, paramRanges, varargsRange)
        }
        context.itemsToShow = infoItems.toSet().toTypedArray()
        return call
    }

    override fun showParameterInfo(element: ZigCallOrBuiltinCall, context: CreateParameterInfoContext) {
        context.showHint(element, element.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): ZigCallOrBuiltinCall? {
        val call = context.parameterOwner as? ZigCallOrBuiltinCall ?: return null
        return if (isInsideCallArgs(call, context.offset)) call else null
    }

    override fun updateParameterInfo(parameterOwner: ZigCallOrBuiltinCall, context: UpdateParameterInfoContext) {
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

    override fun updateUI(p: Item?, context: ParameterInfoUIContext) {
        if (p == null) {
            return
        }
        val paramIndex = context.currentParameterIndex
        val (highlightStart, highlightEnd) = when {
            paramIndex in p.paramRanges.indices -> p.paramRanges[paramIndex]
            paramIndex >= p.paramRanges.size -> p.varargsRange ?: (-1 to -1)
            else -> -1 to -1
        }
        context.setupUIComponentPresentation(
            p.text, highlightStart, highlightEnd, false,
            false, false, context.defaultParameterColor
        )
    }
}
