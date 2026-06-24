package io.github.fncontroloption.zig

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class ZigImportService(private val project: Project) {
    private var cachedLibPath: String? = null
    private var cachedStdFile: PsiFile? = null
    private var notifiedUnsetLibPath = false
    private var notifiedInvalidLibPath = false

    val stdFile: PsiFile?
        get() {
            val libPath = project.service<ZigSettings>().zigLibPath
            if (cachedLibPath == libPath && cachedStdFile != null) {
                return cachedStdFile
            }
            if (libPath.isBlank()) {
                if (!notifiedUnsetLibPath) {
                    notifiedUnsetLibPath = true
                    notifyLibPathConfigure("The Zig lib directory path is unset.")
                }
                return null
            }
            notifiedUnsetLibPath = false
            val stdPath = Path.of(libPath, "std", "std.zig")
            val virtualFile = VirtualFileManager.getInstance().findFileByNioPath(stdPath)
            if (virtualFile == null) {
                if (!notifiedInvalidLibPath) {
                    notifiedInvalidLibPath = true
                    notifyLibPathConfigure("The Zig lib directory path is invalid.")
                }
                return null
            }
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            if (psiFile == null) {
                if (!notifiedInvalidLibPath) {
                    notifiedInvalidLibPath = true
                    notifyLibPathConfigure("The Zig lib directory path is invalid.")
                }
                return null
            }
            notifiedInvalidLibPath = false
            cachedLibPath = libPath
            cachedStdFile = psiFile
            return psiFile
        }

    private fun notifyLibPathConfigure(reason: String) {
        val notifGroup = NotificationGroupManager.getInstance().getNotificationGroup("Zig")
        val notif = notifGroup.createNotification("Cannot resolve std", reason, NotificationType.WARNING)
        notif.addAction(NotificationAction.create("Configure Zig settings") { e, notification ->
            ShowSettingsUtil.getInstance().showSettingsDialog(e.project, ZigSettingsConfigurable::class.java)
            notification.expire()
        })
        Notifications.Bus.notify(notif, project)
    }

    /*
    val builtinFile by lazy {
        val fileFactory = PsiFileFactory.getInstance(project)
        fileFactory.createFileFromText("builtin.zig", ZigFileType.INSTANCE, builtinText)
    }

    // TODO: get std path from `zig build --print-configuration-path`
    private val stdPath = Path.of(System.getProperty("user.home"), "Code", "zig", "lib", "std", "std.zig")

    // TODO: get builtin text from `zig build-exe --show-builtin`
    private val builtinText = """const std = @import("std");
/// Zig version. When writing code that supports multiple versions of Zig, prefer
/// feature detection (i.e. with `@hasDecl` or `@hasField`) over version checks.
pub const zig_version = std.SemanticVersion.parse(zig_version_string) catch unreachable;
pub const zig_version_string = "0.17.0-dev.877+a3ae499dc";"""
    */
}
