package io.github.fncontroloption.zig.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;

public interface ZigTypes {

  // *** Tokens ***

  IElementType AMPERSAND = new ZigElementType("&");
  IElementType AMPERSAND_EQUAL = new ZigElementType("&=");
  IElementType ANGLE_BRACKET_ANGLE_BRACKET_LEFT = new ZigElementType("<<");
  IElementType ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL = new ZigElementType("<<=");
  IElementType ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE = new ZigElementType("<<|");
  IElementType ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL = new ZigElementType("<<|=");
  IElementType ANGLE_BRACKET_ANGLE_BRACKET_RIGHT = new ZigElementType(">>");
  IElementType ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL = new ZigElementType(">>=");
  IElementType ANGLE_BRACKET_LEFT = new ZigElementType("<");
  IElementType ANGLE_BRACKET_LEFT_EQUAL = new ZigElementType("<=");
  IElementType ANGLE_BRACKET_RIGHT = new ZigElementType(">");
  IElementType ANGLE_BRACKET_RIGHT_EQUAL = new ZigElementType(">=");
  IElementType ARROW = new ZigElementType("->");
  IElementType ASTERISK = new ZigElementType("*");
  IElementType ASTERISK_EQUAL = new ZigElementType("*=");
  IElementType ASTERISK_PERCENT = new ZigElementType("*%");
  IElementType ASTERISK_PERCENT_EQUAL = new ZigElementType("*%=");
  IElementType ASTERISK_PIPE = new ZigElementType("*|");
  IElementType ASTERISK_PIPE_EQUAL = new ZigElementType("*|=");
  IElementType BANG = new ZigElementType("!");
  IElementType BANG_EQUAL = new ZigElementType("!=");
  IElementType BUILTIN = new ZigElementType("a builtin function");
  IElementType CARET = new ZigElementType("^");
  IElementType CARET_EQUAL = new ZigElementType("^=");
  IElementType CHAR_LITERAL = new ZigElementType("a character literal");
  IElementType COLON = new ZigElementType(":");
  IElementType COMMA = new ZigElementType(",");
  IElementType CONTAINER_DOC_COMMENT = new ZigElementType("a container document comment");
  IElementType DOC_COMMENT = new ZigElementType("a document comment");
  IElementType ELLIPSIS2 = new ZigElementType("..");
  IElementType ELLIPSIS3 = new ZigElementType("...");
  IElementType EQUAL = new ZigElementType("=");
  IElementType EQUAL_ANGLE_BRACKET_RIGHT = new ZigElementType("=>");
  IElementType EQUAL_EQUAL = new ZigElementType("==");
  IElementType IDENTIFIER = new ZigElementType("an identifier");
  IElementType KEYWORD_ADDRSPACE = new ZigElementType("addrspace");
  IElementType KEYWORD_ALIGN = new ZigElementType("align");
  IElementType KEYWORD_ALLOWZERO = new ZigElementType("allowzero");
  IElementType KEYWORD_AND = new ZigElementType("and");
  IElementType KEYWORD_ANYFRAME = new ZigElementType("anyframe");
  IElementType KEYWORD_ANYTYPE = new ZigElementType("anytype");
  IElementType KEYWORD_ASM = new ZigElementType("asm");
  IElementType KEYWORD_BREAK = new ZigElementType("break");
  IElementType KEYWORD_CALLCONV = new ZigElementType("callconv");
  IElementType KEYWORD_CATCH = new ZigElementType("catch");
  IElementType KEYWORD_COMPTIME = new ZigElementType("comptime");
  IElementType KEYWORD_CONST = new ZigElementType("const");
  IElementType KEYWORD_CONTINUE = new ZigElementType("continue");
  IElementType KEYWORD_DEFER = new ZigElementType("defer");
  IElementType KEYWORD_ELSE = new ZigElementType("else");
  IElementType KEYWORD_ENUM = new ZigElementType("enum");
  IElementType KEYWORD_ERRDEFER = new ZigElementType("errdefer");
  IElementType KEYWORD_ERROR = new ZigElementType("error");
  IElementType KEYWORD_EXPORT = new ZigElementType("export");
  IElementType KEYWORD_EXTERN = new ZigElementType("extern");
  IElementType KEYWORD_FN = new ZigElementType("fn");
  IElementType KEYWORD_FOR = new ZigElementType("for");
  IElementType KEYWORD_IF = new ZigElementType("if");
  IElementType KEYWORD_INLINE = new ZigElementType("inline");
  IElementType KEYWORD_LINKSECTION = new ZigElementType("linksection");
  IElementType KEYWORD_NOALIAS = new ZigElementType("noalias");
  IElementType KEYWORD_NOINLINE = new ZigElementType("noinline");
  IElementType KEYWORD_NOSUSPEND = new ZigElementType("nosuspend");
  IElementType KEYWORD_OPAQUE = new ZigElementType("opaque");
  IElementType KEYWORD_OR = new ZigElementType("or");
  IElementType KEYWORD_ORELSE = new ZigElementType("orelse");
  IElementType KEYWORD_PACKED = new ZigElementType("packed");
  IElementType KEYWORD_PUB = new ZigElementType("pub");
  IElementType KEYWORD_RESUME = new ZigElementType("resume");
  IElementType KEYWORD_RETURN = new ZigElementType("return");
  IElementType KEYWORD_STRUCT = new ZigElementType("struct");
  IElementType KEYWORD_SUSPEND = new ZigElementType("suspend");
  IElementType KEYWORD_SWITCH = new ZigElementType("switch");
  IElementType KEYWORD_TEST = new ZigElementType("test");
  IElementType KEYWORD_THREADLOCAL = new ZigElementType("threadlocal");
  IElementType KEYWORD_TRY = new ZigElementType("try");
  IElementType KEYWORD_UNION = new ZigElementType("union");
  IElementType KEYWORD_UNREACHABLE = new ZigElementType("unreachable");
  IElementType KEYWORD_VAR = new ZigElementType("var");
  IElementType KEYWORD_VOLATILE = new ZigElementType("volatile");
  IElementType KEYWORD_WHILE = new ZigElementType("while");
  IElementType LINE_COMMENT = new ZigElementType("a line comment");
  IElementType L_BRACE = new ZigElementType("{");
  IElementType L_BRACKET = new ZigElementType("[");
  IElementType L_PAREN = new ZigElementType("(");
  IElementType MINUS = new ZigElementType("-");
  IElementType MINUS_EQUAL = new ZigElementType("-=");
  IElementType MINUS_PERCENT = new ZigElementType("-%");
  IElementType MINUS_PERCENT_EQUAL = new ZigElementType("-%=");
  IElementType MINUS_PIPE = new ZigElementType("-|");
  IElementType MINUS_PIPE_EQUAL = new ZigElementType("-|=");
  IElementType MULTILINE_STRING_LITERAL_LINE = new ZigElementType("a multiline string literal");
  IElementType NUMBER_LITERAL = new ZigElementType("a number literal");
  IElementType PERCENT = new ZigElementType("%");
  IElementType PERCENT_EQUAL = new ZigElementType("%=");
  IElementType PERIOD = new ZigElementType(".");
  IElementType PERIOD_ASTERISK = new ZigElementType(".*");
  IElementType PIPE = new ZigElementType("|");
  IElementType PIPE_EQUAL = new ZigElementType("|=");
  IElementType PIPE_PIPE = new ZigElementType("||");
  IElementType PLUS = new ZigElementType("+");
  IElementType PLUS_EQUAL = new ZigElementType("+=");
  IElementType PLUS_PERCENT = new ZigElementType("+%");
  IElementType PLUS_PERCENT_EQUAL = new ZigElementType("+%=");
  IElementType PLUS_PIPE = new ZigElementType("+|");
  IElementType PLUS_PIPE_EQUAL = new ZigElementType("+|=");
  IElementType PLUS_PLUS = new ZigElementType("++");
  IElementType QUESTION_MARK = new ZigElementType("?");
  IElementType R_BRACE = new ZigElementType("}");
  IElementType R_BRACKET = new ZigElementType("]");
  IElementType R_PAREN = new ZigElementType(")");
  IElementType SEMICOLON = new ZigElementType(";");
  IElementType SLASH = new ZigElementType("/");
  IElementType SLASH_EQUAL = new ZigElementType("/=");
  IElementType STRING_LITERAL = new ZigElementType("a string literal");
  IElementType TILDE = new ZigElementType("~");

  // *** AST ***

  IElementType FN_PROTO = new ZigElementType("function prototype");

  class Factory {
    public static PsiElement createElement(ASTNode node) {
      IElementType type = node.getElementType();
      if (type == FN_PROTO) {
        return new ZigFnProto(node);
      }
      throw new AssertionError("Unknown element type: " + type);
    }
  }
}
