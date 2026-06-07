package io.github.fncontroloption.zig.syntaxHighlighter;

import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.annotations.NotNull;

public interface ZigHighlightInfoTypes {

    HighlightInfoType KEYWORD = createSymbolTypeInfo(ZigHighlightingColors.KEYWORD);
    HighlightInfoType INSTANCE_METHOD = createSymbolTypeInfo(ZigHighlightingColors.INSTANCE_METHOD);
    HighlightInfoType INSTANCE_FIELD = createSymbolTypeInfo(ZigHighlightingColors.INSTANCE_FIELD);
    HighlightInfoType STATIC_METHOD = createSymbolTypeInfo(ZigHighlightingColors.STATIC_METHOD);
    HighlightInfoType STATIC_FIELD = createSymbolTypeInfo(ZigHighlightingColors.STATIC_FIELD);
    HighlightInfoType CONSTANT = createSymbolTypeInfo(ZigHighlightingColors.CONSTANT);
    HighlightInfoType LOCAL_VARIABLE = createSymbolTypeInfo(ZigHighlightingColors.LOCAL_VARIABLE);
    HighlightInfoType REASSIGNED_LOCAL_VARIABLE = createSymbolTypeInfo(ZigHighlightingColors.REASSIGNED_LOCAL_VARIABLE);
    HighlightInfoType PARAMETER = createSymbolTypeInfo(ZigHighlightingColors.PARAMETER);
    HighlightInfoType TYPE_PARAMETER = createSymbolTypeInfo(ZigHighlightingColors.TYPE_PARAMETER);

    private static @NotNull HighlightInfoType createSymbolTypeInfo(@NotNull TextAttributesKey attributesKey) {
        return new HighlightInfoType.HighlightInfoTypeImpl(HighlightInfoType.SYMBOL_TYPE_SEVERITY, attributesKey, false);
    }
}
