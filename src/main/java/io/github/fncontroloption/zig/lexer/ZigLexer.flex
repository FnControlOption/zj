package io.github.fncontroloption.zig.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.TokenType;
import io.github.fncontroloption.zig.psi.ZigTokenTypes;

// Based on https://codeberg.org/ziglang/zig/src/commit/72d954e/lib/std/zig/tokenizer.zig
%%

%class ZigLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType

%{
  IElementType type;
%}

%state CHAR
%state STRING
%state MULTILINE_STRING
%state COMMENT

%%

<YYINITIAL> {
  "!"    { return ZigTokenTypes.BANG; }
  "|"    { return ZigTokenTypes.PIPE; }
  "||"   { return ZigTokenTypes.PIPE_PIPE; }
  "|="   { return ZigTokenTypes.PIPE_EQUAL; }
  "="    { return ZigTokenTypes.EQUAL; }
  "=="   { return ZigTokenTypes.EQUAL_EQUAL; }
  "=>"   { return ZigTokenTypes.EQUAL_ANGLE_BRACKET_RIGHT; }
  "!="   { return ZigTokenTypes.BANG_EQUAL; }
  "("    { return ZigTokenTypes.L_PAREN; }
  ")"    { return ZigTokenTypes.R_PAREN; }
  ";"    { return ZigTokenTypes.SEMICOLON; }
  "%"    { return ZigTokenTypes.PERCENT; }
  "%="   { return ZigTokenTypes.PERCENT_EQUAL; }
  "{"    { return ZigTokenTypes.L_BRACE; }
  "}"    { return ZigTokenTypes.R_BRACE; }
  "["    { return ZigTokenTypes.L_BRACKET; }
  "]"    { return ZigTokenTypes.R_BRACKET; }
  "."    { return ZigTokenTypes.PERIOD; }
  ".*"   { return ZigTokenTypes.PERIOD_ASTERISK; }
  ".."   { return ZigTokenTypes.ELLIPSIS2; }
  "..."  { return ZigTokenTypes.ELLIPSIS3; }
  "^"    { return ZigTokenTypes.CARET; }
  "^="   { return ZigTokenTypes.CARET_EQUAL; }
  "+"    { return ZigTokenTypes.PLUS; }
  "++"   { return ZigTokenTypes.PLUS_PLUS; }
  "+="   { return ZigTokenTypes.PLUS_EQUAL; }
  "+%"   { return ZigTokenTypes.PLUS_PERCENT; }
  "+%="  { return ZigTokenTypes.PLUS_PERCENT_EQUAL; }
  "+|"   { return ZigTokenTypes.PLUS_PIPE; }
  "+|="  { return ZigTokenTypes.PLUS_PIPE_EQUAL; }
  "-"    { return ZigTokenTypes.MINUS; }
  "-="   { return ZigTokenTypes.MINUS_EQUAL; }
  "-%"   { return ZigTokenTypes.MINUS_PERCENT; }
  "-%="  { return ZigTokenTypes.MINUS_PERCENT_EQUAL; }
  "-|"   { return ZigTokenTypes.MINUS_PIPE; }
  "-|="  { return ZigTokenTypes.MINUS_PIPE_EQUAL; }
  "*"    { return ZigTokenTypes.ASTERISK; }
  "*="   { return ZigTokenTypes.ASTERISK_EQUAL; }
  "*%"   { return ZigTokenTypes.ASTERISK_PERCENT; }
  "*%="  { return ZigTokenTypes.ASTERISK_PERCENT_EQUAL; }
  "*|"   { return ZigTokenTypes.ASTERISK_PIPE; }
  "*|="  { return ZigTokenTypes.ASTERISK_PIPE_EQUAL; }
  "->"   { return ZigTokenTypes.ARROW; }
  ":"    { return ZigTokenTypes.COLON; }
  "/"    { return ZigTokenTypes.SLASH; }
  "/="   { return ZigTokenTypes.SLASH_EQUAL; }
  ","    { return ZigTokenTypes.COMMA; }
  "&"    { return ZigTokenTypes.AMPERSAND; }
  "&="   { return ZigTokenTypes.AMPERSAND_EQUAL; }
  "?"    { return ZigTokenTypes.QUESTION_MARK; }
  "<"    { return ZigTokenTypes.ANGLE_BRACKET_LEFT; }
  "<="   { return ZigTokenTypes.ANGLE_BRACKET_LEFT_EQUAL; }
  "<<"   { return ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT; }
  "<<="  { return ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL; }
  "<<|"  { return ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE; }
  "<<|=" { return ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL; }
  ">"    { return ZigTokenTypes.ANGLE_BRACKET_RIGHT; }
  ">="   { return ZigTokenTypes.ANGLE_BRACKET_RIGHT_EQUAL; }
  ">>"   { return ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT; }
  ">>="  { return ZigTokenTypes.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL; }
  "~"    { return ZigTokenTypes.TILDE; }

  "addrspace"   { return ZigTokenTypes.KEYWORD_ADDRSPACE; }
  "align"       { return ZigTokenTypes.KEYWORD_ALIGN; }
  "allowzero"   { return ZigTokenTypes.KEYWORD_ALLOWZERO; }
  "and"         { return ZigTokenTypes.KEYWORD_AND; }
  "anyframe"    { return ZigTokenTypes.KEYWORD_ANYFRAME; }
  "anytype"     { return ZigTokenTypes.KEYWORD_ANYTYPE; }
  "asm"         { return ZigTokenTypes.KEYWORD_ASM; }
  "break"       { return ZigTokenTypes.KEYWORD_BREAK; }
  "callconv"    { return ZigTokenTypes.KEYWORD_CALLCONV; }
  "catch"       { return ZigTokenTypes.KEYWORD_CATCH; }
  "comptime"    { return ZigTokenTypes.KEYWORD_COMPTIME; }
  "const"       { return ZigTokenTypes.KEYWORD_CONST; }
  "continue"    { return ZigTokenTypes.KEYWORD_CONTINUE; }
  "defer"       { return ZigTokenTypes.KEYWORD_DEFER; }
  "else"        { return ZigTokenTypes.KEYWORD_ELSE; }
  "enum"        { return ZigTokenTypes.KEYWORD_ENUM; }
  "errdefer"    { return ZigTokenTypes.KEYWORD_ERRDEFER; }
  "error"       { return ZigTokenTypes.KEYWORD_ERROR; }
  "export"      { return ZigTokenTypes.KEYWORD_EXPORT; }
  "extern"      { return ZigTokenTypes.KEYWORD_EXTERN; }
  "fn"          { return ZigTokenTypes.KEYWORD_FN; }
  "for"         { return ZigTokenTypes.KEYWORD_FOR; }
  "if"          { return ZigTokenTypes.KEYWORD_IF; }
  "inline"      { return ZigTokenTypes.KEYWORD_INLINE; }
  "noalias"     { return ZigTokenTypes.KEYWORD_NOALIAS; }
  "noinline"    { return ZigTokenTypes.KEYWORD_NOINLINE; }
  "nosuspend"   { return ZigTokenTypes.KEYWORD_NOSUSPEND; }
  "opaque"      { return ZigTokenTypes.KEYWORD_OPAQUE; }
  "or"          { return ZigTokenTypes.KEYWORD_OR; }
  "orelse"      { return ZigTokenTypes.KEYWORD_ORELSE; }
  "packed"      { return ZigTokenTypes.KEYWORD_PACKED; }
  "pub"         { return ZigTokenTypes.KEYWORD_PUB; }
  "resume"      { return ZigTokenTypes.KEYWORD_RESUME; }
  "return"      { return ZigTokenTypes.KEYWORD_RETURN; }
  "linksection" { return ZigTokenTypes.KEYWORD_LINKSECTION; }
  "struct"      { return ZigTokenTypes.KEYWORD_STRUCT; }
  "suspend"     { return ZigTokenTypes.KEYWORD_SUSPEND; }
  "switch"      { return ZigTokenTypes.KEYWORD_SWITCH; }
  "test"        { return ZigTokenTypes.KEYWORD_TEST; }
  "threadlocal" { return ZigTokenTypes.KEYWORD_THREADLOCAL; }
  "try"         { return ZigTokenTypes.KEYWORD_TRY; }
  "union"       { return ZigTokenTypes.KEYWORD_UNION; }
  "unreachable" { return ZigTokenTypes.KEYWORD_UNREACHABLE; }
  "var"         { return ZigTokenTypes.KEYWORD_VAR; }
  "volatile"    { return ZigTokenTypes.KEYWORD_VOLATILE; }
  "while"       { return ZigTokenTypes.KEYWORD_WHILE; }

  [a-zA-Z_][a-zA-Z_0-9]* { return ZigTokenTypes.IDENTIFIER; }

  [0-9][_a-zA-Z0-9]*("."[_a-zA-Z0-9]*[eEpP][-+]?[_a-zA-Z0-9]*)?
  { return ZigTokenTypes.NUMBER_LITERAL; }

  ' { yybegin(CHAR); }

  \" { type = ZigTokenTypes.STRING_LITERAL; yybegin(STRING); }
  @\" { type = ZigTokenTypes.IDENTIFIER; yybegin(STRING); }

  @[a-zA-Z_][a-zA-Z_0-9]* { return ZigTokenTypes.BUILTIN; }

  \\\\ { yybegin(MULTILINE_STRING); }

  "//" { type = ZigTokenTypes.LINE_COMMENT; yybegin(COMMENT); }
  "///" { type = ZigTokenTypes.DOC_COMMENT; yybegin(COMMENT); }
  "//!" { type = ZigTokenTypes.CONTAINER_DOC_COMMENT; yybegin(COMMENT); }
  "////" { type = ZigTokenTypes.LINE_COMMENT; yybegin(COMMENT); }

  [\ \n\t\r]+ { return TokenType.WHITE_SPACE; }

  [^] { return TokenType.BAD_CHARACTER; }
}

<CHAR> {
  [\n\x01-\x09\x0b-\x1f\x7f] { yybegin(YYINITIAL); return TokenType.BAD_CHARACTER; }
  <<EOF>> { yybegin(YYINITIAL); return TokenType.BAD_CHARACTER; }
  ' { yybegin(YYINITIAL); return ZigTokenTypes.CHAR_LITERAL; }
  [^] {}
  \\' {}
}

<STRING> {
  [\n\x01-\x09\x0b-\x1f\x7f] { yybegin(YYINITIAL); return TokenType.BAD_CHARACTER; }
  <<EOF>> { yybegin(YYINITIAL); return TokenType.BAD_CHARACTER; }
  \" { yybegin(YYINITIAL); return type; }
  [^] {}
  \\\" {}
}

<MULTILINE_STRING> {
  [\r\x01-\x09\x0b-\x0c\x0e-\x1f\x7f] { yybegin(YYINITIAL); return TokenType.BAD_CHARACTER; }
  <<EOF>> { yybegin(YYINITIAL); return ZigTokenTypes.MULTILINE_STRING_LITERAL_LINE; }
  \r?\n { yybegin(YYINITIAL); return ZigTokenTypes.MULTILINE_STRING_LITERAL_LINE; }
  [^] {}
}

<COMMENT> {
  [\r\x01-\x09\x0b-\x0c\x0e-\x1f\x7f] { yybegin(YYINITIAL); return TokenType.BAD_CHARACTER; }
  <<EOF>> { yybegin(YYINITIAL); return type; }
  \r?\n { yybegin(YYINITIAL); return type; }
  [^] {}
}
