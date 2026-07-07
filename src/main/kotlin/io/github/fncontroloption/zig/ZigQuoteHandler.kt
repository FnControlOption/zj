package io.github.fncontroloption.zig

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import io.github.fncontroloption.zig.psi2.ZigTokenType

class ZigQuoteHandler : SimpleTokenSetQuoteHandler(ZigTokenType.CHAR_LITERAL, ZigTokenType.STRING_LITERAL)
