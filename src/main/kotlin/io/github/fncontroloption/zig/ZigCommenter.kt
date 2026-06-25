package io.github.fncontroloption.zig

import com.intellij.lang.Commenter

class ZigCommenter : Commenter {
    override fun getLineCommentPrefix() = "//"
    override fun getBlockCommentPrefix() = null
    override fun getBlockCommentSuffix() = null
    override fun getCommentedBlockCommentPrefix() = null
    override fun getCommentedBlockCommentSuffix() = null
}
