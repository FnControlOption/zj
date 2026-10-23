package io.github.fncontroloption.zj

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy

class ZigDirectoryIndexExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
    override fun getExcludeUrlsForProject(): Array<out String?> {
        return ProjectRootManager.getInstance(project).contentRoots.asSequence().flatMap {
            sequenceOf("${it.url}/.zig-cache", "${it.url}/zig-out", "${it.url}/zig-pkg")
        }.toList().toTypedArray()
    }
}
