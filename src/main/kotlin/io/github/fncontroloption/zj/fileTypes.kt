package io.github.fncontroloption.zj

import com.intellij.openapi.fileTypes.LanguageFileType

object ZigFileType : LanguageFileType(ZigLanguage) {
    override fun getName() = "ZIG"
    override fun getDescription() = "Zig"
    override fun getDefaultExtension() = "zig"
    override fun getIcon() = ZigIcons.ZIG
}

object ZonFileType : LanguageFileType(ZonLanguage) {
    override fun getName() = "ZON"
    override fun getDescription() = "ZON"
    override fun getDefaultExtension() = "zon"
    override fun getIcon() = ZigIcons.ZIG
}
