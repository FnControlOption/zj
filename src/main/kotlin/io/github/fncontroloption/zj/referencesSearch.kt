package io.github.fncontroloption.zj

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.elementType
import com.intellij.util.Processor
import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor

val ZIG_ESCAPED_IDENTIFIER_INDEX_NAME =
    ID.create<String, Void?>("io.github.fncontroloption.zj.ZigEscapedIdentifierIndex")

const val ZIG_ESCAPED_IDENTIFIER_INDEX_VERSION = 5

class ZigEscapedIdentifierIndex : ScalarIndexExtension<String>() {
    override fun getName() = ZIG_ESCAPED_IDENTIFIER_INDEX_NAME
    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(ZigFileType)
    override fun dependsOnFileContent() = true
    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE!!
    override fun getVersion() = ZIG_ESCAPED_IDENTIFIER_INDEX_VERSION
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
        val target = queryParameters.elementToSearch as? ZigNameIdentifierOwner ?: return
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

val ZIG_STRING_LITERAL_INDEX_NAME =
    ID.create<String, Void?>("io.github.fncontroloption.zj.ZigStringLiteralIndex")

const val ZIG_STRING_LITERAL_INDEX_VERSION = 0

class ZigStringLiteralIndex : ScalarIndexExtension<String>() {
    override fun getName() = ZIG_STRING_LITERAL_INDEX_NAME
    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(ZigFileType)
    override fun dependsOnFileContent() = true
    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE!!
    override fun getVersion() = ZIG_STRING_LITERAL_INDEX_VERSION
    override fun getIndexer() = DataIndexer<String, Void?, FileContent> { content ->
        val result = mutableMapOf<String, Void?>()
        val lexer = ZigLexerAdapter()
        lexer.start(content.contentAsText)
        while (lexer.tokenType != null) {
            if (lexer.tokenType === ZigTokenType.STRING_LITERAL) {
                val value = ZigStringLiteralParser.parse(lexer.tokenSequence)
                if (value != null) {
                    result[value] = null
                }
            }
            // TODO: handle multiline string literal?
            lexer.advance()
        }
        result
    }
}

class ZigModuleReferencesSearch : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ) {
        val project = queryParameters.project
        val target = queryParameters.elementToSearch as? ZigFile ?: return
        val targetPath = target.virtualFile?.toNioPathOrNull() ?: return

        val modulesService = project.service<ZigModulesProjectService>()
        val modulesCache = modulesService.cache.value
        val moduleId = modulesCache.rootFileToId[targetPath] ?: return
        val module = modulesCache.idToModule[moduleId] ?: return

        val virtualFiles = when (val scope = queryParameters.effectiveSearchScope) {
            is GlobalSearchScope -> {
                val fileBasedIndex = FileBasedIndex.getInstance()
                modulesCache.idToModule.values
                    .asSequence()
                    .flatMap { it.importTable.asSequence() }
                    .filter { (_, importId) -> importId == module.id }
                    .map { (importName, _) -> importName }
                    .distinct()
                    .flatMap { fileBasedIndex.getContainingFiles(ZIG_STRING_LITERAL_INDEX_NAME, it, scope) }
                    .distinct()
            }
            is LocalSearchScope -> scope.virtualFiles.asSequence()
            else -> return
        }

        val psiManager = PsiManager.getInstance(project)
        virtualFiles
            .mapNotNull { psiManager.findFile(it) }
            .filterIsInstance<ZigFile>()
            .flatMap { SyntaxTraverser.psiTraverser(it) }
            .filterIsInstance<ZigStringLiteral>()
            .flatMap { it.references.asSequence() }
            .filter { it.isReferenceTo(target) }
            .forEach(consumer::process)
    }
}
