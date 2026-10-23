package io.github.fncontroloption.zj

import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage

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
    AttributesDescriptor("Bad character", ZigHighlightingColors.BAD_CHARACTER),

    AttributesDescriptor("Instance method", ZigHighlightingColors.INSTANCE_METHOD),
    AttributesDescriptor("Instance field", ZigHighlightingColors.INSTANCE_FIELD),
    AttributesDescriptor("Static method", ZigHighlightingColors.STATIC_METHOD),
    AttributesDescriptor("Static field", ZigHighlightingColors.STATIC_FIELD),
    AttributesDescriptor("Constant", ZigHighlightingColors.CONSTANT),
    AttributesDescriptor("Local variable", ZigHighlightingColors.LOCAL_VARIABLE),
    AttributesDescriptor("Reassigned local variable", ZigHighlightingColors.REASSIGNED_LOCAL_VARIABLE),
    AttributesDescriptor("Parameter", ZigHighlightingColors.PARAMETER),
    AttributesDescriptor("Comptime parameter", ZigHighlightingColors.COMPTIME_PARAMETER),
    AttributesDescriptor("Error name", ZigHighlightingColors.ERROR_NAME),
)

private val TAG_TO_DESCRIPTOR = mapOf(
    "primitive" to ZigHighlightingColors.KEYWORD,

    "instanceMethod" to ZigHighlightingColors.INSTANCE_METHOD,
    "instanceField" to ZigHighlightingColors.INSTANCE_FIELD,
    "staticMethod" to ZigHighlightingColors.STATIC_METHOD,
    "staticField" to ZigHighlightingColors.STATIC_FIELD,
    "constant" to ZigHighlightingColors.CONSTANT,
    "localVariable" to ZigHighlightingColors.LOCAL_VARIABLE,
    "reassignedLocalVariable" to ZigHighlightingColors.REASSIGNED_LOCAL_VARIABLE,
    "parameter" to ZigHighlightingColors.PARAMETER,
    "comptimeParameter" to ZigHighlightingColors.COMPTIME_PARAMETER,
    "errorName" to ZigHighlightingColors.ERROR_NAME,
)

private const val DEMO_TEXT = """
//! Container doc comment
const <constant>number</constant> = 42;
const <constant>string</constant> = "foo";
const <constant>sum</constant> = 12 + 34;
const <constant>array</constant>: [2]<primitive>i32</primitive> = .{ 12, 34 };
const <constant>fn_call</constant> = <staticMethod>func</staticMethod>(12, 34);
const <constant>builtin</constant> = @foo();
const <constant>bad</constant> = $$$;

/// Doc comment
fn <staticMethod>func</staticMethod>(<parameter>foo</parameter>: <primitive>i32</primitive>, <parameter>bar</parameter>: <primitive>i32</primitive>) <primitive>i32</primitive> {
    // Line comment
    const <localVariable>baz</localVariable> = <parameter>foo</parameter>;
    var <reassignedLocalVariable>qux</reassignedLocalVariable> = <parameter>bar</parameter>;
    <reassignedLocalVariable>qux</reassignedLocalVariable> += <localVariable>baz</localVariable>;
    return <reassignedLocalVariable>qux</reassignedLocalVariable>;
}

const <constant>MyStruct</constant> = struct {
    <instanceField>instance_field</instanceField>: <primitive>i32</primitive>,
    var <staticField>static_field</staticField> = <primitive>true</primitive>;
    const <constant>Self</constant> = @This();
    fn <instanceMethod>instanceMethod</instanceMethod>(<parameter>self</parameter>: <constant>Self</constant>) <primitive>i32</primitive> {
        return <parameter>self</parameter>.<instanceField>instance_field</instanceField>;
    }
    fn <staticMethod>staticMethod</staticMethod>(<parameter>i</parameter>: <primitive>i32</primitive>) <primitive>i32</primitive> {
        if (<staticField>static_field</staticField>) {
            return <parameter>i</parameter> + <parameter>i</parameter>;
        } else {
            return <parameter>i</parameter> * <parameter>i</parameter>;
        }
    }
};
fn <staticMethod>genericFunc</staticMethod>(<comptimeParameter>Value</comptimeParameter>: <primitive>type</primitive>, <parameter>value</parameter>: <comptimeParameter>Value</comptimeParameter>) [2]<comptimeParameter>Value</comptimeParameter> {
    return .{ <parameter>value</parameter>, <parameter>value</parameter> };
}
fn <staticMethod>comptimeFunc</staticMethod>(comptime <comptimeParameter>parameter</comptimeParameter>: <primitive>i32</primitive>) <primitive>i32</primitive> {
    return <comptimeParameter>parameter</comptimeParameter> * 2;
}
fn <staticMethod>fallibleFunc</staticMethod>() error{<errorName>MyError</errorName>}!<primitive>void</primitive> {
    if (1 + 1 == 2) {
        return error.<errorName>MyError</errorName>;
    }
}
"""

class ZigColorSettingsPage : ColorSettingsPage {
    override fun getIcon() = ZigIcons.ZIG
    override fun getHighlighter() = ZigSyntaxHighlighter()
    override fun getDemoText() = DEMO_TEXT
    override fun getAdditionalHighlightingTagToDescriptorMap() = TAG_TO_DESCRIPTOR
    override fun getAttributeDescriptors() = DESCRIPTORS
    override fun getColorDescriptors() = emptyArray<ColorDescriptor>()
    override fun getDisplayName() = "Zig"
}
