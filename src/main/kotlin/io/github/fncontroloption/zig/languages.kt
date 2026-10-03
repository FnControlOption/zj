package io.github.fncontroloption.zig

import com.intellij.lang.Language

object ZigLanguage : Language("ZIG") {
    override fun getDisplayName() = "Zig"
    override fun isCaseSensitive() = true

    @Suppress("unused")
    private fun readResolve(): Any = this
}

object ZonLanguage : Language(ZigLanguage, "ZON") {
    @Suppress("unused")
    private fun readResolve(): Any = this
}

object ZigBuiltinFnProtoLanguage : Language(ZigLanguage, "ZIG_BUILTIN_FN_PROTO") {
    override fun getDisplayName() = "Zig builtin function prototype"

    @Suppress("unused")
    private fun readResolve(): Any = this
}
