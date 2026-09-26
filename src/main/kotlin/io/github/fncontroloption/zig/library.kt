package io.github.fncontroloption.zig

import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile

class ZigLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val libraries = mutableListOf<SyntheticLibrary>()
        val builtinFile = project.service<ZigBuiltinService>().builtinFile
        if (builtinFile != null) {
            libraries.add(ZigSyntheticLibrary("builtin", listOf(builtinFile), emptyList(), emptySet()))
        }
        return libraries
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
