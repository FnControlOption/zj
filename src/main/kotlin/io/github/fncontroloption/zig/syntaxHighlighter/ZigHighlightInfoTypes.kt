package io.github.fncontroloption.zig.syntaxHighlighter

import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightInfoType.HighlightInfoTypeImpl
import com.intellij.openapi.editor.colors.TextAttributesKey

private fun createSymbolTypeInfo(attributesKey: TextAttributesKey): HighlightInfoType {
    return HighlightInfoTypeImpl(HighlightInfoType.SYMBOL_TYPE_SEVERITY, attributesKey, false)
}

object ZigHighlightInfoTypes {
    val KEYWORD = createSymbolTypeInfo(ZigHighlightingColors.KEYWORD)
    val INSTANCE_METHOD = createSymbolTypeInfo(ZigHighlightingColors.INSTANCE_METHOD)
    val INSTANCE_FIELD = createSymbolTypeInfo(ZigHighlightingColors.INSTANCE_FIELD)
    val STATIC_METHOD = createSymbolTypeInfo(ZigHighlightingColors.STATIC_METHOD)
    val STATIC_FIELD = createSymbolTypeInfo(ZigHighlightingColors.STATIC_FIELD)
    val CONSTANT = createSymbolTypeInfo(ZigHighlightingColors.CONSTANT)
    val LOCAL_VARIABLE = createSymbolTypeInfo(ZigHighlightingColors.LOCAL_VARIABLE)
    val REASSIGNED_LOCAL_VARIABLE = createSymbolTypeInfo(ZigHighlightingColors.REASSIGNED_LOCAL_VARIABLE)
    val PARAMETER = createSymbolTypeInfo(ZigHighlightingColors.PARAMETER)
    val COMPTIME_PARAMETER = createSymbolTypeInfo(ZigHighlightingColors.COMPTIME_PARAMETER)
}
