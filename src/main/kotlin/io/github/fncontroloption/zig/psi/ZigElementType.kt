package io.github.fncontroloption.zig.psi

import com.intellij.psi.tree.IElementType
import io.github.fncontroloption.zig.ZigLanguage
import org.jetbrains.annotations.NonNls

class ZigElementType(@NonNls debugName: String) : IElementType(debugName, ZigLanguage)
