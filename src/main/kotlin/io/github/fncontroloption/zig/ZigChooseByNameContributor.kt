package io.github.fncontroloption.zig

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.descendants
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import io.github.fncontroloption.zig.psi2.ZigBuiltinCall
import io.github.fncontroloption.zig.psi2.ZigContainerField
import io.github.fncontroloption.zig.psi2.ZigFieldAccess
import io.github.fncontroloption.zig.psi2.ZigFnDecl
import io.github.fncontroloption.zig.psi2.ZigLabelDecl
import io.github.fncontroloption.zig.psi2.ZigMutability
import io.github.fncontroloption.zig.psi2.ZigNamedElement
import io.github.fncontroloption.zig.psi2.ZigParamDecl
import io.github.fncontroloption.zig.psi2.ZigPayloadItem
import io.github.fncontroloption.zig.psi2.ZigVarDecl
import io.github.fncontroloption.zig.psi2.ZigVarProto

private fun canChooseByName(element: ZigNamedElement): Boolean {
    val name = element.name ?: return false
    return when (element) {
        is ZigContainerField,
        is ZigFnDecl -> true

        is ZigVarDecl -> {
            val initializer = element.initializer
            if (element.mutability == ZigMutability.CONST
                && initializer is ZigFieldAccess
                && initializer.identifierElement?.text == name
            ) {
                return false
            }
            if (element.isPub) {
                return true
            }
            if (initializer is ZigBuiltinCall && initializer.builtinElement.text == "@import") {
                return false
            }
            !element.isLocal
        }

        is ZigLabelDecl,
        is ZigParamDecl,
        is ZigPayloadItem,
        is ZigVarProto -> false
    }
}

class ZigChooseByNameContributor : ChooseByNameContributorEx {
    override fun processNames(
        processor: Processor<in String>,
        scope: GlobalSearchScope,
        filter: IdFilter?
    ) {
        val project = scope.project ?: return
        FileTypeIndex.processFiles(ZigFileType, { virtualFile ->
            val file = PsiManager.getInstance(project).findFile(virtualFile)
            if (file != null) {
                for (descendant in file.descendants()) {
                    if (descendant is ZigNamedElement && canChooseByName(descendant)) {
                        val name = descendant.name
                        if (name != null) {
                            processor.process(name)
                        }
                    }
                }
            }
            true
        }, scope)
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters
    ) {
        val scope = parameters.searchScope
        val project = scope.project ?: return
        FileTypeIndex.processFiles(ZigFileType, { virtualFile ->
            val file = PsiManager.getInstance(project).findFile(virtualFile)
            if (file != null) {
                for (descendant in file.descendants()) {
                    if (descendant is ZigNamedElement && canChooseByName(descendant)) {
                        if (descendant.name == name) {
                            processor.process(descendant)
                        }
                    }
                }
            }
            true
        }, scope)
    }
}
