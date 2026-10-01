package io.github.fncontroloption.zig

import com.intellij.lang.Language

object ZigLanguage : Language("Zig") {
    @Suppress("unused")
    private fun readResolve(): Any = ZigLanguage
}

object ZigBuiltinFnProtoLanguage : Language(ZigLanguage, "ZigBuiltinFnProto") {
    @Suppress("unused")
    private fun readResolve(): Any = ZigBuiltinFnProtoLanguage
}
