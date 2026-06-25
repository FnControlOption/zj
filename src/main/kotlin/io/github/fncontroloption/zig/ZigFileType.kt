package io.github.fncontroloption.zig

import com.intellij.openapi.fileTypes.LanguageFileType

object ZigFileType : LanguageFileType(ZigLanguage) {
    override fun getName() = "Zig"
    override fun getDescription() = "Zig file"
    override fun getDefaultExtension() = "zig"
    override fun getIcon() = ZigIcons.ZIG
}
