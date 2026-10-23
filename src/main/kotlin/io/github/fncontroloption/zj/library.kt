package io.github.fncontroloption.zj

import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile

class ZigLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val libraries = mutableListOf<SyntheticLibrary>()
        val builtinFile = ZigBuiltinService.getBuiltinFile(project)
        if (builtinFile != null) {
            libraries.add(ZigSyntheticLibrary("builtin", listOf(builtinFile), emptyList(), emptySet()))
        }
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            val pkg = root.findChild("zig-pkg") ?: continue
            if (!pkg.isValid || !pkg.isDirectory) continue
            for (child in pkg.children) {
                libraries.add(ZigSyntheticLibrary(child.name, listOf(child), emptyList(), emptySet()))
            }
        }
        return libraries
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> {
        return ProjectRootManager.getInstance(project).contentRoots.mapNotNull { it.findChild("zig-pkg") }
    }
}

/** @see com.intellij.openapi.roots.JavaSyntheticLibrary */
private data class ZigSyntheticLibrary(
    private val comparisonId: String,
    private val sourceRoots: List<VirtualFile>,
    private val binaryRoots: List<VirtualFile>,
    private val excludedRoots: Set<VirtualFile>,
) : SyntheticLibrary(comparisonId, null), ItemPresentation {
    override fun getSourceRoots() = sourceRoots
    override fun getBinaryRoots() = binaryRoots
    override fun getExcludedRoots() = excludedRoots
    override fun getPresentableText() = comparisonId
    override fun getIcon(unused: Boolean) = AllIcons.Nodes.PpLibFolder
}
