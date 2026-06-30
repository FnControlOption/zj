package io.github.fncontroloption.zig

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import io.github.fncontroloption.zig.psi2.ZigContainerDecl
import io.github.fncontroloption.zig.psi2.ZigContainerField
import io.github.fncontroloption.zig.psi2.ZigFnDecl
import io.github.fncontroloption.zig.psi2.ZigTestDecl
import io.github.fncontroloption.zig.psi2.ZigVarDecl

private fun shouldIncludeInStructureView(element: NavigatablePsiElement): Boolean {
    return when (element) {
        is ZigContainerDecl,
        is ZigContainerField,
        is ZigFnDecl,
        is ZigTestDecl -> true
        is ZigVarDecl -> !element.isLocal
        else -> false
    }
}

class ZigStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile) = ZigStructureViewBuilder(psiFile)
}

class ZigStructureViewBuilder(val psiFile: PsiFile) : TreeBasedStructureViewBuilder() {
    override fun createStructureViewModel(editor: Editor?) = ZigStructureViewModel(editor, psiFile)
}

/** @see com.intellij.ide.structureView.impl.java.JavaFileTreeModel */
class ZigStructureViewModel(editor: Editor?, psiFile: PsiFile) :
    StructureViewModelBase(psiFile, editor, ZigStructureViewElement(psiFile)),
    StructureViewModel.ElementInfoProvider {

    init {
        withSorters(Sorter.ALPHA_SORTER)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement?) = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement?) = false
}

class ZigStructureViewElement(val element: NavigatablePsiElement) : StructureViewTreeElement, SortableTreeElement {
    override fun getValue() = element

    override fun navigate(requestFocus: Boolean) = element.navigate(requestFocus)

    override fun canNavigate() = element.canNavigate()

    override fun canNavigateToSource() = element.canNavigateToSource()

    override fun getAlphaSortKey() = element.name ?: ""

    override fun getPresentation() = element.presentation ?: PresentationData(element.toString(), null, null, null)

    override fun getChildren(): Array<out TreeElement> {
        val treeElements = mutableListOf<TreeElement>()
        val psiTraverser = SyntaxTraverser.psiTraverser(element).expand {
            it === element || it !is NavigatablePsiElement || !shouldIncludeInStructureView(it)
        }
        for (elem in psiTraverser) {
            if (elem === element) {
                continue
            }
            if (elem is NavigatablePsiElement && shouldIncludeInStructureView(elem)) {
                treeElements.add(ZigStructureViewElement(elem))
            }
        }
        return treeElements.toTypedArray()
    }
}
