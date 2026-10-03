package io.github.fncontroloption.zig

import com.intellij.openapi.fileTypes.LanguageFileType

object ZigFileType : LanguageFileType(ZigLanguage) {
    override fun getName() = "ZIG"
    override fun getDescription() = "Zig"
    override fun getDefaultExtension() = "zig"
    override fun getIcon() = ZigIcons.ZIG
}
