package io.github.fncontroloption.zj

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter

class ZigChooseByNameContributor : ChooseByNameContributorEx {
    override fun processNames(
        processor: Processor<in String>,
        scope: GlobalSearchScope,
        filter: IdFilter?
    ) {
        val stubIndex = StubIndex.getInstance()
        stubIndex.processAllKeys(ZigStubIndexKeys.CONTAINER_FIELDS, processor, scope, filter)
        stubIndex.processAllKeys(ZigStubIndexKeys.FN_DECLS, processor, scope, filter)
        stubIndex.processAllKeys(ZigStubIndexKeys.VAR_DECLS, processor, scope, filter)
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters
    ) {
        val scope = parameters.searchScope
        val project = scope.project ?: return
        val filter = parameters.idFilter
        val stubIndex = StubIndex.getInstance()

        fun <Psi : PsiElement> processStubIndexed(
            indexKey: StubIndexKey<String, Psi>,
            requiredClass: Class<Psi>,
            processor: Processor<in Psi>
        ) = stubIndex.processElements(indexKey, name, project, scope, filter, requiredClass, processor)

        processStubIndexed(ZigStubIndexKeys.CONTAINER_FIELDS, ZigContainerFieldImpl::class.java, processor)
        processStubIndexed(ZigStubIndexKeys.FN_DECLS, ZigFnDecl::class.java, processor)
        processStubIndexed(ZigStubIndexKeys.VAR_DECLS, ZigVarDecl::class.java, processor)
    }
}
