package io.github.fncontroloption.zj

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.projectImport.ProjectOpenProcessor

class ZigProjectOpenProcessor : ProjectOpenProcessor() {
    override val name: String
        get() = "Zig"

    override fun getIcon(file: VirtualFile) = ZigIcons.ZIG

    override fun canOpenProject(file: VirtualFile): Boolean {
        return !file.isDirectory && (file.name == "build.zig" || file.name == "build.zig.zon")
    }

    override suspend fun openProjectAsync(
        virtualFile: VirtualFile,
        projectToClose: Project?,
        forceOpenInNewFrame: Boolean,
    ): Project? {
        val path = virtualFile.parent.toNioPathOrNull() ?: return null
        val options =
            OpenProjectTask
                .build()
                .withProjectToClose(projectToClose)
                .withForceOpenInNewFrame(forceOpenInNewFrame)
        return ProjectUtil.openOrImportAsync(path, options)
    }
}
