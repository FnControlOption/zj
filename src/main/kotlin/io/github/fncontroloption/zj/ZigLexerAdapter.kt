package io.github.fncontroloption.zj

import com.intellij.lexer.FlexAdapter

class ZigLexerAdapter : FlexAdapter(ZigLexer(null))
