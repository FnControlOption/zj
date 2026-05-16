package io.github.fncontroloption.zig;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.spellchecker.inspections.CommentSplitter;
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy;
import com.intellij.spellchecker.tokenizer.TokenConsumer;
import com.intellij.spellchecker.tokenizer.Tokenizer;
import io.github.fncontroloption.zig.psi.ZigTokenSets;
import org.jetbrains.annotations.NotNull;

final class ZigSpellcheckingStrategy extends SpellcheckingStrategy {

  @Override
  public @NotNull Tokenizer<?> getTokenizer(PsiElement element) {
    if (element instanceof PsiComment) {
      return new ZigCommentTokenizer();
    }

    return EMPTY_TOKENIZER;
  }

  private static class ZigCommentTokenizer extends Tokenizer<PsiComment> {

    @Override
    public void tokenize(@NotNull PsiComment element, @NotNull TokenConsumer consumer) {
      // Exclude the "//" at the start of the comment from spell checking
      int startIndex = 2;
      var tokenType = element.getTokenType();
      if (ZigTokenSets.DOC_COMMENTS.contains(tokenType)) {
        startIndex = 3;
      }
      consumer.consumeToken(element, element.getText(), false, 0,
          TextRange.create(startIndex, element.getTextLength()),
          CommentSplitter.getInstance());
    }

  }

}
