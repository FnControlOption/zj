package io.github.fncontroloption.zig

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.fncontroloption.zig.psi2.ZigFile

class ZigAutoPopupHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is ZigFile) {
            return Result.CONTINUE
        }
        if (charTyped == ':') {
            val offset = editor.caretModel.offset
            val charSeq = editor.document.immutableCharSequence.subSequence(0, offset).trimEnd()
            if (charSeq.endsWith("break") || charSeq.endsWith("continue")) {
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
                return Result.STOP
            }
        }
        return Result.CONTINUE
    }
}
