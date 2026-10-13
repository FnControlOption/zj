package io.github.fncontroloption.zig

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class ZigAutoPopupHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is ZigFile) {
            return Result.CONTINUE
        }
        val popupController = AutoPopupController.getInstance(project)
        val offset = editor.caretModel.offset
        val text = editor.document.immutableCharSequence
        when (charTyped) {
            '@' -> {
                popupController.scheduleAutoPopup(editor)
                return Result.STOP
            }
            '"' -> {
                if (text.getOrNull(offset - 1) == '@') {
                    popupController.scheduleAutoPopup(editor)
                    return Result.STOP
                }
            }
            ':' -> {
                val charSeq = text.subSequence(0, offset).trimEnd()
                if (charSeq.endsWith("break") || charSeq.endsWith("continue")) {
                    popupController.scheduleAutoPopup(editor)
                    return Result.STOP
                }
            }
        }
        return Result.CONTINUE
    }
}
