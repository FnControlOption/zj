package io.github.fncontroloption.zig

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class ZigProjectService(private val project: Project) {
    val stdFile by lazy {
        VirtualFileManager.getInstance().findFileByNioPath(stdPath)?.let {
            PsiManager.getInstance(project).findFile(it)
        }
    }

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
}
