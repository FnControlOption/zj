package io.github.fncontroloption.zig.syntaxHighlighter;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import io.github.fncontroloption.zig.ZigIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Map;

final class ZigColorSettingsPage implements ColorSettingsPage {

  private static final AttributesDescriptor[] DESCRIPTORS = new AttributesDescriptor[]{
          new AttributesDescriptor("Identifier", ZigHighlightingColors.IDENTIFIER),
          new AttributesDescriptor("Number", ZigHighlightingColors.NUMBER),
          new AttributesDescriptor("Keyword", ZigHighlightingColors.KEYWORD),
          new AttributesDescriptor("String", ZigHighlightingColors.STRING),
          new AttributesDescriptor("Line comment", ZigHighlightingColors.LINE_COMMENT),
          new AttributesDescriptor("Doc comment", ZigHighlightingColors.DOC_COMMENT),
          new AttributesDescriptor("Operation sign", ZigHighlightingColors.OPERATION_SIGN),
          new AttributesDescriptor("Braces", ZigHighlightingColors.BRACES),
          new AttributesDescriptor("Dot", ZigHighlightingColors.DOT),
          new AttributesDescriptor("Semicolon", ZigHighlightingColors.SEMICOLON),
          new AttributesDescriptor("Comma", ZigHighlightingColors.COMMA),
          new AttributesDescriptor("Parentheses", ZigHighlightingColors.PARENTHESES),
          new AttributesDescriptor("Brackets", ZigHighlightingColors.BRACKETS),
          new AttributesDescriptor("Builtin function", ZigHighlightingColors.BUILTIN),
          new AttributesDescriptor("Bad character", ZigHighlightingColors.BAD_CHARACTER)
  };

  @Override
  public Icon getIcon() {
    return ZigIcons.ZIG;
  }

  @NotNull
  @Override
  public SyntaxHighlighter getHighlighter() {
    return new ZigSyntaxHighlighter();
  }

  @NotNull
  @Override
  public String getDemoText() {
    return """
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
        const bad = $$$;""";
  }

  @Nullable
  @Override
  public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
    return null;
  }

  @Override
  public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
    return DESCRIPTORS;
  }

  @Override
  public ColorDescriptor @NotNull [] getColorDescriptors() {
    return ColorDescriptor.EMPTY_ARRAY;
  }

  @NotNull
  @Override
  public String getDisplayName() {
    return "Zig";
  }

}
