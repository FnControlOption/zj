package io.github.fncontroloption.zig

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler

class ZigQuoteHandler : SimpleTokenSetQuoteHandler(ZigTokenType.CHAR_LITERAL, ZigTokenType.STRING_LITERAL)
