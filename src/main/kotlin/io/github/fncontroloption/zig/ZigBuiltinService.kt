package io.github.fncontroloption.zig

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

@Service(Service.Level.PROJECT)
class ZigBuiltinService(private val project: Project) {
    private var cachedSdk: Sdk? = null
    private var cachedBuiltinFile: VirtualFile? = null

    val builtinFile: VirtualFile?
        get() {
            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication().runWriteAction {
                    updateCachedBuiltinFile()
                }
            }
            return cachedBuiltinFile
        }

    private fun updateCachedBuiltinFile() {
        val currentSdk = ProjectRootManager.getInstance(project).projectSdk
        if (cachedSdk == null || cachedSdk != currentSdk) {
            cachedSdk = null
            cachedBuiltinFile = null
            if (currentSdk?.sdkType is ZigSdkType) {
                cachedSdk = currentSdk
                val builtinText = currentSdk.homePath?.let {
                    getZigExeOutput(it, "build-exe", "--show-builtin")
                }
                if (builtinText != null) {
                    cachedBuiltinFile = LightVirtualFile("builtin.zig", ZigLanguage, builtinText)
                }
            }
        }
    }
}
