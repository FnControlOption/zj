package io.github.fncontroloption.zig

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

@Service(Service.Level.PROJECT)
class ZigBuiltinService(private val project: Project) {
    private var cachedSdk: Sdk? = null
    private var cachedBuiltinFile: VirtualFile? = null

    val modificationTracker = SimpleModificationTracker()

    val builtinFile: VirtualFile?
        get() = cachedBuiltinFile

    fun updateCachedBuiltinFile() {
        val currentSdk = ProjectRootManager.getInstance(project).projectSdk
        if (cachedSdk == null || cachedSdk != currentSdk) {
            cachedSdk = null
            cachedBuiltinFile = null
            if (currentSdk != null) {
                cachedSdk = currentSdk
                val builtinText = getZigExeOutput(currentSdk, "build-exe", "--show-builtin")
                if (builtinText != null) {
                    val virtualFile = LightVirtualFile("builtin.zig", ZigLanguage, builtinText)
                    virtualFile.isWritable = false
                    cachedBuiltinFile = virtualFile
                }
                modificationTracker.incModificationCount()
            }
        }
    }

    companion object {
        fun getModificationTracker(project: Project): ModificationTracker {
            return project.service<ZigBuiltinService>().modificationTracker
        }

        fun getBuiltinFile(project: Project): VirtualFile? {
            return project.service<ZigBuiltinService>().builtinFile
        }
    }
}
