package io.github.fncontroloption.zig;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.TokenType;
import io.github.fncontroloption.zig.psi2.ZigTokenType;

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
  "!"    { return ZigTokenType.BANG; }
  "|"    { return ZigTokenType.PIPE; }
  "||"   { return ZigTokenType.PIPE_PIPE; }
  "|="   { return ZigTokenType.PIPE_EQUAL; }
  "="    { return ZigTokenType.EQUAL; }
  "=="   { return ZigTokenType.EQUAL_EQUAL; }
  "=>"   { return ZigTokenType.EQUAL_ANGLE_BRACKET_RIGHT; }
  "!="   { return ZigTokenType.BANG_EQUAL; }
  "("    { return ZigTokenType.L_PAREN; }
  ")"    { return ZigTokenType.R_PAREN; }
  ";"    { return ZigTokenType.SEMICOLON; }
  "%"    { return ZigTokenType.PERCENT; }
  "%="   { return ZigTokenType.PERCENT_EQUAL; }
  "{"    { return ZigTokenType.L_BRACE; }
  "}"    { return ZigTokenType.R_BRACE; }
  "["    { return ZigTokenType.L_BRACKET; }
  "]"    { return ZigTokenType.R_BRACKET; }
  "."    { return ZigTokenType.PERIOD; }
  ".*"   { return ZigTokenType.PERIOD_ASTERISK; }
  ".."   { return ZigTokenType.ELLIPSIS2; }
  "..."  { return ZigTokenType.ELLIPSIS3; }
  "^"    { return ZigTokenType.CARET; }
  "^="   { return ZigTokenType.CARET_EQUAL; }
  "+"    { return ZigTokenType.PLUS; }
  "++"   { return ZigTokenType.PLUS_PLUS; }
  "+="   { return ZigTokenType.PLUS_EQUAL; }
  "+%"   { return ZigTokenType.PLUS_PERCENT; }
  "+%="  { return ZigTokenType.PLUS_PERCENT_EQUAL; }
  "+|"   { return ZigTokenType.PLUS_PIPE; }
  "+|="  { return ZigTokenType.PLUS_PIPE_EQUAL; }
  "-"    { return ZigTokenType.MINUS; }
  "-="   { return ZigTokenType.MINUS_EQUAL; }
  "-%"   { return ZigTokenType.MINUS_PERCENT; }
  "-%="  { return ZigTokenType.MINUS_PERCENT_EQUAL; }
  "-|"   { return ZigTokenType.MINUS_PIPE; }
  "-|="  { return ZigTokenType.MINUS_PIPE_EQUAL; }
  "*"    { return ZigTokenType.ASTERISK; }
  "*="   { return ZigTokenType.ASTERISK_EQUAL; }
  "*%"   { return ZigTokenType.ASTERISK_PERCENT; }
  "*%="  { return ZigTokenType.ASTERISK_PERCENT_EQUAL; }
  "*|"   { return ZigTokenType.ASTERISK_PIPE; }
  "*|="  { return ZigTokenType.ASTERISK_PIPE_EQUAL; }
  "->"   { return ZigTokenType.ARROW; }
  ":"    { return ZigTokenType.COLON; }
  "/"    { return ZigTokenType.SLASH; }
  "/="   { return ZigTokenType.SLASH_EQUAL; }
  ","    { return ZigTokenType.COMMA; }
  "&"    { return ZigTokenType.AMPERSAND; }
  "&="   { return ZigTokenType.AMPERSAND_EQUAL; }
  "?"    { return ZigTokenType.QUESTION_MARK; }
  "<"    { return ZigTokenType.ANGLE_BRACKET_LEFT; }
  "<="   { return ZigTokenType.ANGLE_BRACKET_LEFT_EQUAL; }
  "<<"   { return ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT; }
  "<<="  { return ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL; }
  "<<|"  { return ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE; }
  "<<|=" { return ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL; }
  ">"    { return ZigTokenType.ANGLE_BRACKET_RIGHT; }
  ">="   { return ZigTokenType.ANGLE_BRACKET_RIGHT_EQUAL; }
  ">>"   { return ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT; }
  ">>="  { return ZigTokenType.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL; }
  "~"    { return ZigTokenType.TILDE; }

  "addrspace"   { return ZigTokenType.KEYWORD_ADDRSPACE; }
  "align"       { return ZigTokenType.KEYWORD_ALIGN; }
  "allowzero"   { return ZigTokenType.KEYWORD_ALLOWZERO; }
  "and"         { return ZigTokenType.KEYWORD_AND; }
  "anyframe"    { return ZigTokenType.KEYWORD_ANYFRAME; }
  "anytype"     { return ZigTokenType.KEYWORD_ANYTYPE; }
  "asm"         { return ZigTokenType.KEYWORD_ASM; }
  "break"       { return ZigTokenType.KEYWORD_BREAK; }
  "callconv"    { return ZigTokenType.KEYWORD_CALLCONV; }
  "catch"       { return ZigTokenType.KEYWORD_CATCH; }
  "comptime"    { return ZigTokenType.KEYWORD_COMPTIME; }
  "const"       { return ZigTokenType.KEYWORD_CONST; }
  "continue"    { return ZigTokenType.KEYWORD_CONTINUE; }
  "defer"       { return ZigTokenType.KEYWORD_DEFER; }
  "else"        { return ZigTokenType.KEYWORD_ELSE; }
  "enum"        { return ZigTokenType.KEYWORD_ENUM; }
  "errdefer"    { return ZigTokenType.KEYWORD_ERRDEFER; }
  "error"       { return ZigTokenType.KEYWORD_ERROR; }
  "export"      { return ZigTokenType.KEYWORD_EXPORT; }
  "extern"      { return ZigTokenType.KEYWORD_EXTERN; }
  "fn"          { return ZigTokenType.KEYWORD_FN; }
  "for"         { return ZigTokenType.KEYWORD_FOR; }
  "if"          { return ZigTokenType.KEYWORD_IF; }
  "inline"      { return ZigTokenType.KEYWORD_INLINE; }
  "noalias"     { return ZigTokenType.KEYWORD_NOALIAS; }
  "noinline"    { return ZigTokenType.KEYWORD_NOINLINE; }
  "nosuspend"   { return ZigTokenType.KEYWORD_NOSUSPEND; }
  "opaque"      { return ZigTokenType.KEYWORD_OPAQUE; }
  "or"          { return ZigTokenType.KEYWORD_OR; }
  "orelse"      { return ZigTokenType.KEYWORD_ORELSE; }
  "packed"      { return ZigTokenType.KEYWORD_PACKED; }
  "pub"         { return ZigTokenType.KEYWORD_PUB; }
  "resume"      { return ZigTokenType.KEYWORD_RESUME; }
  "return"      { return ZigTokenType.KEYWORD_RETURN; }
  "linksection" { return ZigTokenType.KEYWORD_LINKSECTION; }
  "struct"      { return ZigTokenType.KEYWORD_STRUCT; }
  "suspend"     { return ZigTokenType.KEYWORD_SUSPEND; }
  "switch"      { return ZigTokenType.KEYWORD_SWITCH; }
  "test"        { return ZigTokenType.KEYWORD_TEST; }
  "threadlocal" { return ZigTokenType.KEYWORD_THREADLOCAL; }
  "try"         { return ZigTokenType.KEYWORD_TRY; }
  "union"       { return ZigTokenType.KEYWORD_UNION; }
  "unreachable" { return ZigTokenType.KEYWORD_UNREACHABLE; }
  "var"         { return ZigTokenType.KEYWORD_VAR; }
  "volatile"    { return ZigTokenType.KEYWORD_VOLATILE; }
  "while"       { return ZigTokenType.KEYWORD_WHILE; }

  [a-zA-Z_][a-zA-Z_0-9]* { return ZigTokenType.IDENTIFIER; }

  [0-9][_a-zA-Z0-9]* ("."[_a-zA-Z0-9]+)? ([eEpP][-+]?[_a-zA-Z0-9]*)?
  { return ZigTokenType.NUMBER_LITERAL; }

  ' { yybegin(CHAR); }

  \" { type = ZigTokenType.STRING_LITERAL; yybegin(STRING); }
  @\" { type = ZigTokenType.IDENTIFIER; yybegin(STRING); }

  @[a-zA-Z_][a-zA-Z_0-9]* { return ZigTokenType.BUILTIN; }

  \\\\ { yybegin(MULTILINE_STRING); }

  "//" { type = ZigTokenType.LINE_COMMENT; yybegin(COMMENT); }
  "///" { type = ZigTokenType.DOC_COMMENT; yybegin(COMMENT); }
  "//!" { type = ZigTokenType.CONTAINER_DOC_COMMENT; yybegin(COMMENT); }
  "////" { type = ZigTokenType.LINE_COMMENT; yybegin(COMMENT); }

  [\ \n\t\r]+ { return TokenType.WHITE_SPACE; }

  [^] { return TokenType.BAD_CHARACTER; }
}

<CHAR> {
  [\n\x01-\x09\x0b-\x1f\x7f] { yypushback(1); yybegin(YYINITIAL); return ZigTokenType.CHAR_LITERAL; }
  <<EOF>> { yybegin(YYINITIAL); return ZigTokenType.CHAR_LITERAL; }
  ' { yybegin(YYINITIAL); return ZigTokenType.CHAR_LITERAL; }
  [^] {}
  \\[^] {}
}

<STRING> {
  [\n\x01-\x09\x0b-\x1f\x7f] { yypushback(1); yybegin(YYINITIAL); return type; }
  <<EOF>> { yybegin(YYINITIAL); return type; }
  \" { yybegin(YYINITIAL); return type; }
  [^] {}
  \\[^] {}
}

<MULTILINE_STRING> {
  [\r\x01-\x09\x0b-\x0c\x0e-\x1f\x7f] { yypushback(1); yybegin(YYINITIAL); return ZigTokenType.MULTILINE_STRING_LITERAL_LINE; }
  <<EOF>> { yybegin(YYINITIAL); return ZigTokenType.MULTILINE_STRING_LITERAL_LINE; }
  \n { yypushback(1); yybegin(YYINITIAL); return ZigTokenType.MULTILINE_STRING_LITERAL_LINE; }
  \r\n { yypushback(2); yybegin(YYINITIAL); return ZigTokenType.MULTILINE_STRING_LITERAL_LINE; }
  [^] {}
}

<COMMENT> {
  [\r\x01-\x09\x0b-\x0c\x0e-\x1f\x7f] { yypushback(1); yybegin(YYINITIAL); return type; }
  <<EOF>> { yybegin(YYINITIAL); return type; }
  \n { yypushback(1); yybegin(YYINITIAL); return type; }
  \r\n { yypushback(2); yybegin(YYINITIAL); return type; }
  [^] {}
}
