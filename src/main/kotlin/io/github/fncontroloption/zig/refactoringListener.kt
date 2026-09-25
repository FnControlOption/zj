package io.github.fncontroloption.zig

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.listeners.RefactoringElementListenerProvider
import io.github.fncontroloption.zig.psi2.ZigBuiltinCall
import io.github.fncontroloption.zig.psi2.ZigFile
import io.github.fncontroloption.zig.psi2.ZigStringLiteral
import io.github.fncontroloption.zig.psi2.createZigStringLiteral
import java.nio.file.Path

fun nioPathToUnixString(path: Path): String {
    return buildString {
        append(path.getName(0))
        for (i in 1..<path.nameCount) {
            append('/')
            append(path.getName(i))
        }
    }
}

class ZigRefactoringListenerProvider : RefactoringElementListenerProvider {
    override fun getListener(element: PsiElement): RefactoringElementListener? {
        if (element is ZigFile) {
            val oldDir = element.containingDirectory ?: return null
            val oldDirPath = oldDir.virtualFile.toNioPath()
            return ZigRefactoringFileListener(oldDirPath)
        }
        return null
    }
}

private class ZigRefactoringFileListener(val oldDirPath: Path) : RefactoringElementListener {
    override fun elementMoved(newElement: PsiElement) {
        if (newElement is ZigFile) {
            val newDir = newElement.containingDirectory ?: return
            val newDirPath = newDir.virtualFile.toNioPath()
            val processor = BuiltinCallProcessor(newDirPath)
            PsiTreeUtil.processElements(newElement, ZigBuiltinCall::class.java, processor)
        }
    }

    override fun elementRenamed(newElement: PsiElement) {}

    inner class BuiltinCallProcessor(val newDirPath: Path) : PsiElementProcessor<ZigBuiltinCall> {
        override fun execute(element: ZigBuiltinCall): Boolean {
            process(element)
            return true
        }

        fun process(element: ZigBuiltinCall) {
            when (element.builtinElement.text) {
                "@embedFile" -> {} // TODO

                "@import" -> {
                    val oldElement = element.argumentElements.firstOrNull()?.innerElement
                    if (oldElement !is ZigStringLiteral) {
                        return
                    }
                    val oldValue = oldElement.value ?: return
                    if (!oldValue.endsWith(".zig") && !oldValue.endsWith(".zon")) {
                        return
                    }
                    val importedPath = oldDirPath.resolve(oldValue)
                    val relativePath = newDirPath.relativize(importedPath)
                    val newValue = nioPathToUnixString(relativePath)
                    val newElement = createZigStringLiteral(oldElement.project, newValue)
                    ApplicationManager.getApplication().invokeAndWait {
                        ApplicationManager.getApplication().runWriteAction {
                            oldElement.replace(newElement)
                        }
                    }
                }

                else -> {}
            }
        }
    }
}
