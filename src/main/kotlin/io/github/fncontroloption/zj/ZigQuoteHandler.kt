package io.github.fncontroloption.zj

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler

class ZigQuoteHandler : SimpleTokenSetQuoteHandler(ZigTokenType.CHAR_LITERAL, ZigTokenType.STRING_LITERAL)
