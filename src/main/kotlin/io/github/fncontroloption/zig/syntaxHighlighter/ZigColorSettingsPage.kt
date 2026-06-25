package io.github.fncontroloption.zig.syntaxHighlighter

import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import io.github.fncontroloption.zig.ZigIcons

private val DESCRIPTORS = arrayOf(
    AttributesDescriptor("Identifier", ZigHighlightingColors.IDENTIFIER),
    AttributesDescriptor("Number", ZigHighlightingColors.NUMBER),
    AttributesDescriptor("Keyword", ZigHighlightingColors.KEYWORD),
    AttributesDescriptor("String", ZigHighlightingColors.STRING),
    AttributesDescriptor("Line comment", ZigHighlightingColors.LINE_COMMENT),
    AttributesDescriptor("Doc comment", ZigHighlightingColors.DOC_COMMENT),
    AttributesDescriptor("Operation sign", ZigHighlightingColors.OPERATION_SIGN),
    AttributesDescriptor("Braces", ZigHighlightingColors.BRACES),
    AttributesDescriptor("Dot", ZigHighlightingColors.DOT),
    AttributesDescriptor("Semicolon", ZigHighlightingColors.SEMICOLON),
    AttributesDescriptor("Comma", ZigHighlightingColors.COMMA),
    AttributesDescriptor("Parentheses", ZigHighlightingColors.PARENTHESES),
    AttributesDescriptor("Brackets", ZigHighlightingColors.BRACKETS),
    AttributesDescriptor("Builtin function", ZigHighlightingColors.BUILTIN),
    AttributesDescriptor("Bad character", ZigHighlightingColors.BAD_CHARACTER)
)

private const val DEMO_TEXT = """
//! Container doc comment

/// Doc comment
fn func(a: i32, b: i32) i32 {
    // Line comment
    return a + b;
}

const number = 42;
const string = "foo";
const sum = 12 + 34;
const array: [2]i32 = .{ 12, 34 };
const fn_call = func(12, 34);
const builtin = @foo();
const bad = $$$;
"""

class ZigColorSettingsPage : ColorSettingsPage {
    override fun getIcon() = ZigIcons.ZIG
    override fun getHighlighter() = ZigSyntaxHighlighter()
    override fun getDemoText() = DEMO_TEXT
    override fun getAdditionalHighlightingTagToDescriptorMap() = null
    override fun getAttributeDescriptors() = DESCRIPTORS
    override fun getColorDescriptors() = emptyArray<ColorDescriptor>()
    override fun getDisplayName() = "Zig"
}
