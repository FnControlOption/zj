package io.github.fncontroloption.zig

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiFile
import java.nio.file.Path

abstract class ZigExternalAnnotator :
    ExternalAnnotator<ZigExternalAnnotator.Info, List<ZigExe.Message>>(),
    DumbAware {

    class Info(val project: Project, val path: Path)

    override fun collectInformation(psiFile: PsiFile, editor: Editor, hasErrors: Boolean): Info? {
        val virtualFile = psiFile.virtualFile ?: return null
        if (FileDocumentManager.getInstance().isFileModified(virtualFile)) return null
        val path = virtualFile.toNioPathOrNull() ?: return null
        return Info(psiFile.project, path)
    }

    abstract override fun doAnnotate(collectedInfo: Info): List<ZigExe.Message>?

    override fun apply(psiFile: PsiFile, annotationResult: List<ZigExe.Message>, holder: AnnotationHolder) {
        val contents = psiFile.viewProvider.contents
        for (result in annotationResult) {
            val index = lineAndColumnToIndex(contents, result.line, result.column) ?: continue
            val element = psiFile.findElementAt(index) ?: continue
            val severity = when (result.kind) {
                ZigExe.Message.Kind.ERROR -> HighlightSeverity.ERROR
                ZigExe.Message.Kind.NOTE -> HighlightSeverity.WEAK_WARNING
            }
            holder
                .newAnnotation(severity, result.text)
                .range(element)
                .create()
        }
    }
}

class ZigAstCheckAnnotator : ZigExternalAnnotator() {
    override fun doAnnotate(collectedInfo: Info): List<ZigExe.Message>? {
        val sdk = ProjectRootManager.getInstance(collectedInfo.project).projectSdk ?: return null
        val exe = ZigExe.find(sdk) ?: return null
        val path = collectedInfo.path.toString()
        val result = exe.run("ast-check", "--color", "off", path)
        if (result.success) return null
        return result.errorMessages.values.singleOrNull()
    }
}

private fun lineAndColumnToIndex(contents: CharSequence, line: Int, column: Int): Int? {
    var index = 0
    repeat(line) {
        val newline = contents.indexOf('\n', index)
        if (newline < 0) return null
        index = newline + 1
    }
    var bytes = 0
    while (bytes < column && index < contents.length) {
        val code = Character.codePointAt(contents, index)
        index += Character.charCount(code)
        bytes += when (code) {
            in 0..0x7f -> 1
            in 0x80..0x7ff -> 2
            in 0x800..0xffff -> 3
            in 0x10000..0x10ffff -> 4
            else -> throw IllegalStateException()
        }
    }
    if (index > contents.length) return null
    return index
}
