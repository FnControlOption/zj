package io.github.fncontroloption.zig;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import io.github.fncontroloption.zig.parser.ZigParser;
import io.github.fncontroloption.zig.psi.ZigFile;
import io.github.fncontroloption.zig.psi.ZigTokenSets;
import org.jetbrains.annotations.NotNull;

final class ZigParserDefinition implements ParserDefinition {

  public static final IFileElementType FILE = new IFileElementType(ZigLanguage.INSTANCE);

  @NotNull
  @Override
  public Lexer createLexer(Project project) {
    return new ZigLexerAdapter();
  }

  @NotNull
  @Override
  public TokenSet getCommentTokens() {
    return ZigTokenSets.COMMENTS;
  }

  @NotNull
  @Override
  public TokenSet getStringLiteralElements() {
    return ZigTokenSets.STRINGS;
  }

  @NotNull
  @Override
  public PsiParser createParser(final Project project) {
    return new ZigParser();
  }

  @NotNull
  @Override
  public IFileElementType getFileNodeType() {
    return FILE;
  }

  @NotNull
  @Override
  public PsiFile createFile(@NotNull FileViewProvider viewProvider) {
    return new ZigFile(viewProvider);
  }

  @NotNull
  @Override
  public PsiElement createElement(ASTNode node) {
    return new com.intellij.extapi.psi.ASTWrapperPsiElement(node); // TODO
  }

}
