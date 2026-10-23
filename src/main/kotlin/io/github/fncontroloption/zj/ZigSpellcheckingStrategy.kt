package io.github.fncontroloption.zj

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.spellchecker.inspections.CommentSplitter
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.TokenConsumer
import com.intellij.spellchecker.tokenizer.Tokenizer

class ZigSpellcheckingStrategy : SpellcheckingStrategy() {
    override fun getTokenizer(element: PsiElement?): Tokenizer<*> {
        if (element is PsiComment) {
            return ZigCommentTokenizer()
        }

        return EMPTY_TOKENIZER
    }

    private class ZigCommentTokenizer : Tokenizer<PsiComment?>() {
        override fun tokenize(element: PsiComment, consumer: TokenConsumer) {
            // Exclude the "//" at the start of the comment from spell checking
            var startIndex = 2
            if (ZigTokenSets.DOC_COMMENTS.contains(element.tokenType)) {
                startIndex = 3
            }
            consumer.consumeToken(
                element, element.text, false, 0,
                TextRange.create(startIndex, element.textLength),
                CommentSplitter.getInstance()
            )
        }
    }
}
