package io.github.fncontroloption.zig

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.elementType
import com.intellij.util.Processor
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import io.github.fncontroloption.zig.psi2.ZigNamedElement
import io.github.fncontroloption.zig.psi2.ZigTokenType

val ZIG_ESCAPED_IDENTIFIER_INDEX_NAME =
    ID.create<String, Void?>("io.github.fncontroloption.zig.ZigEscapedIdentifierIndex")

class ZigEscapedIdentifierIndex : ScalarIndexExtension<String>() {
    override fun getName() = ZIG_ESCAPED_IDENTIFIER_INDEX_NAME
    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(ZigFileType)
    override fun dependsOnFileContent() = true
    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE!!
    override fun getVersion() = 5
    override fun getIndexer() = DataIndexer<String, Void?, FileContent> { content ->
        val result = mutableMapOf<String, Void?>()
        val lexer = ZigLexerAdapter()
        lexer.start(content.contentAsText)
        while (lexer.tokenType != null) {
            if (lexer.tokenType === ZigTokenType.IDENTIFIER) {
                val sequence = lexer.tokenSequence
                if (sequence.startsWith('@') && sequence.contains('\\')) {
                    val subsequence = sequence.subSequence(1, sequence.length)
                    val name = ZigStringLiteralParser.parse(subsequence)
                    if (name != null) {
                        result[name] = null
                    }
                }
            }
            lexer.advance()
        }
        result
    }
}

class ZigEscapedIdentifierReferencesSearch : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ) {
        val target = queryParameters.elementToSearch as? ZigNamedElement ?: return
        val name = target.name ?: return
        val rootElements = when (val scope = queryParameters.effectiveSearchScope) {
            is GlobalSearchScope -> {
                val psiManager = PsiManager.getInstance(target.project)
                FileBasedIndex.getInstance()
                    .getContainingFiles(ZIG_ESCAPED_IDENTIFIER_INDEX_NAME, name, scope)
                    .asSequence()
                    .mapNotNull { psiManager.findFile(it) }
            }
            is LocalSearchScope -> scope.scope.asSequence()
            else -> return
        }
        for (rootElement in rootElements) {
            for (childElement in SyntaxTraverser.psiTraverser(rootElement)) {
                if (childElement.elementType !== ZigTokenType.IDENTIFIER) continue
                if (!childElement.textContains('@')) continue
                if (!childElement.textContains('\\')) continue
                for (reference in childElement.parent.references) {
                    if (!reference.isReferenceTo(target)) continue
                    if (consumer.process(reference)) continue
                    return
                }
            }
        }
    }
}
