package io.github.fncontroloption.zig

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.text.Strings
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiFile
import java.nio.file.Path

private val MESSAGE_REGEX = """\s*(.*):(\d+):(\d+): (error|note): (.*)\s*""".toRegex()

class ZigAstCheckAnnotator :
    ExternalAnnotator<ZigAstCheckAnnotator.Info, List<ZigAstCheckAnnotator.Result>>(),
    DumbAware {

    class Info(val sdk: Sdk, val path: Path, val contents: CharSequence)

    class Result(val index: Int, val severity: HighlightSeverity, val message: String)

    override fun collectInformation(psiFile: PsiFile): Info? {
        val sdk = ProjectRootManager.getInstance(psiFile.project).projectSdk ?: return null
        val path = psiFile.viewProvider.virtualFile.toNioPathOrNull() ?: return null
        val contents = psiFile.viewProvider.contents
        return Info(sdk, path, contents)
    }

    override fun doAnnotate(collectedInfo: Info): List<Result>? {
        val sdk = collectedInfo.sdk
        val exe = ZigExe.find(sdk) ?: return null
        val path = collectedInfo.path.toString()
        val contents = collectedInfo.contents
        val result = exe.run("ast-check", "--color", "off", path) ?: return null
        if (result.success) return null
        val annotateResults = mutableListOf<Result>()
        result.stderr.forEachLine { line ->
            // TODO: handle multiline message, f.e. duplicate struct member @"foo\nbar"
            val match = MESSAGE_REGEX.matchEntire(line) ?: return@forEachLine
            val line = match.groupValues[2].toInt() - 1
            val column = match.groupValues[3].toInt() - 1
            val severity = when (match.groupValues[4]) {
                "error" -> HighlightSeverity.ERROR
                "note" -> HighlightSeverity.WEAK_WARNING
                else -> return@forEachLine
            }
            val message = Strings.capitalize(match.groupValues[5])
            val index = lineAndColumnToIndex(contents, line, column) ?: return@forEachLine
            annotateResults.add(Result(index, severity, message))
        }
        return annotateResults
    }

    override fun apply(psiFile: PsiFile, annotationResult: List<Result>, holder: AnnotationHolder) {
        for (result in annotationResult) {
            val element = psiFile.findElementAt(result.index) ?: continue
            holder
                .newAnnotation(result.severity, result.message)
                .range(element)
                .create()
        }
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
