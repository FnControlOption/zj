package io.github.fncontroloption.zig.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.TokenType;
import io.github.fncontroloption.zig.psi.ZigTypes;

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
  "!"    { return ZigTypes.BANG; }
  "|"    { return ZigTypes.PIPE; }
  "||"   { return ZigTypes.PIPE_PIPE; }
  "|="   { return ZigTypes.PIPE_EQUAL; }
  "="    { return ZigTypes.EQUAL; }
  "=="   { return ZigTypes.EQUAL_EQUAL; }
  "=>"   { return ZigTypes.EQUAL_ANGLE_BRACKET_RIGHT; }
  "!="   { return ZigTypes.BANG_EQUAL; }
  "("    { return ZigTypes.L_PAREN; }
  ")"    { return ZigTypes.R_PAREN; }
  ";"    { return ZigTypes.SEMICOLON; }
  "%"    { return ZigTypes.PERCENT; }
  "%="   { return ZigTypes.PERCENT_EQUAL; }
  "{"    { return ZigTypes.L_BRACE; }
  "}"    { return ZigTypes.R_BRACE; }
  "["    { return ZigTypes.L_BRACKET; }
  "]"    { return ZigTypes.R_BRACKET; }
  "."    { return ZigTypes.PERIOD; }
  ".*"   { return ZigTypes.PERIOD_ASTERISK; }
  ".."   { return ZigTypes.ELLIPSIS2; }
  "..."  { return ZigTypes.ELLIPSIS3; }
  "^"    { return ZigTypes.CARET; }
  "^="   { return ZigTypes.CARET_EQUAL; }
  "+"    { return ZigTypes.PLUS; }
  "++"   { return ZigTypes.PLUS_PLUS; }
  "+="   { return ZigTypes.PLUS_EQUAL; }
  "+%"   { return ZigTypes.PLUS_PERCENT; }
  "+%="  { return ZigTypes.PLUS_PERCENT_EQUAL; }
  "+|"   { return ZigTypes.PLUS_PIPE; }
  "+|="  { return ZigTypes.PLUS_PIPE_EQUAL; }
  "-"    { return ZigTypes.MINUS; }
  "-="   { return ZigTypes.MINUS_EQUAL; }
  "-%"   { return ZigTypes.MINUS_PERCENT; }
  "-%="  { return ZigTypes.MINUS_PERCENT_EQUAL; }
  "-|"   { return ZigTypes.MINUS_PIPE; }
  "-|="  { return ZigTypes.MINUS_PIPE_EQUAL; }
  "*"    { return ZigTypes.ASTERISK; }
  "*="   { return ZigTypes.ASTERISK_EQUAL; }
  "*%"   { return ZigTypes.ASTERISK_PERCENT; }
  "*%="  { return ZigTypes.ASTERISK_PERCENT_EQUAL; }
  "*|"   { return ZigTypes.ASTERISK_PIPE; }
  "*|="  { return ZigTypes.ASTERISK_PIPE_EQUAL; }
  "->"   { return ZigTypes.ARROW; }
  ":"    { return ZigTypes.COLON; }
  "/"    { return ZigTypes.SLASH; }
  "/="   { return ZigTypes.SLASH_EQUAL; }
  ","    { return ZigTypes.COMMA; }
  "&"    { return ZigTypes.AMPERSAND; }
  "&="   { return ZigTypes.AMPERSAND_EQUAL; }
  "?"    { return ZigTypes.QUESTION_MARK; }
  "<"    { return ZigTypes.ANGLE_BRACKET_LEFT; }
  "<="   { return ZigTypes.ANGLE_BRACKET_LEFT_EQUAL; }
  "<<"   { return ZigTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT; }
  "<<="  { return ZigTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_EQUAL; }
  "<<|"  { return ZigTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE; }
  "<<|=" { return ZigTypes.ANGLE_BRACKET_ANGLE_BRACKET_LEFT_PIPE_EQUAL; }
  ">"    { return ZigTypes.ANGLE_BRACKET_RIGHT; }
  ">="   { return ZigTypes.ANGLE_BRACKET_RIGHT_EQUAL; }
  ">>"   { return ZigTypes.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT; }
  ">>="  { return ZigTypes.ANGLE_BRACKET_ANGLE_BRACKET_RIGHT_EQUAL; }
  "~"    { return ZigTypes.TILDE; }

  "addrspace"   { return ZigTypes.KEYWORD_ADDRSPACE; }
  "align"       { return ZigTypes.KEYWORD_ALIGN; }
  "allowzero"   { return ZigTypes.KEYWORD_ALLOWZERO; }
  "and"         { return ZigTypes.KEYWORD_AND; }
  "anyframe"    { return ZigTypes.KEYWORD_ANYFRAME; }
  "anytype"     { return ZigTypes.KEYWORD_ANYTYPE; }
  "asm"         { return ZigTypes.KEYWORD_ASM; }
  "break"       { return ZigTypes.KEYWORD_BREAK; }
  "callconv"    { return ZigTypes.KEYWORD_CALLCONV; }
  "catch"       { return ZigTypes.KEYWORD_CATCH; }
  "comptime"    { return ZigTypes.KEYWORD_COMPTIME; }
  "const"       { return ZigTypes.KEYWORD_CONST; }
  "continue"    { return ZigTypes.KEYWORD_CONTINUE; }
  "defer"       { return ZigTypes.KEYWORD_DEFER; }
  "else"        { return ZigTypes.KEYWORD_ELSE; }
  "enum"        { return ZigTypes.KEYWORD_ENUM; }
  "errdefer"    { return ZigTypes.KEYWORD_ERRDEFER; }
  "error"       { return ZigTypes.KEYWORD_ERROR; }
  "export"      { return ZigTypes.KEYWORD_EXPORT; }
  "extern"      { return ZigTypes.KEYWORD_EXTERN; }
  "fn"          { return ZigTypes.KEYWORD_FN; }
  "for"         { return ZigTypes.KEYWORD_FOR; }
  "if"          { return ZigTypes.KEYWORD_IF; }
  "inline"      { return ZigTypes.KEYWORD_INLINE; }
  "noalias"     { return ZigTypes.KEYWORD_NOALIAS; }
  "noinline"    { return ZigTypes.KEYWORD_NOINLINE; }
  "nosuspend"   { return ZigTypes.KEYWORD_NOSUSPEND; }
  "opaque"      { return ZigTypes.KEYWORD_OPAQUE; }
  "or"          { return ZigTypes.KEYWORD_OR; }
  "orelse"      { return ZigTypes.KEYWORD_ORELSE; }
  "packed"      { return ZigTypes.KEYWORD_PACKED; }
  "pub"         { return ZigTypes.KEYWORD_PUB; }
  "resume"      { return ZigTypes.KEYWORD_RESUME; }
  "return"      { return ZigTypes.KEYWORD_RETURN; }
  "linksection" { return ZigTypes.KEYWORD_LINKSECTION; }
  "struct"      { return ZigTypes.KEYWORD_STRUCT; }
  "suspend"     { return ZigTypes.KEYWORD_SUSPEND; }
  "switch"      { return ZigTypes.KEYWORD_SWITCH; }
  "test"        { return ZigTypes.KEYWORD_TEST; }
  "threadlocal" { return ZigTypes.KEYWORD_THREADLOCAL; }
  "try"         { return ZigTypes.KEYWORD_TRY; }
  "union"       { return ZigTypes.KEYWORD_UNION; }
  "unreachable" { return ZigTypes.KEYWORD_UNREACHABLE; }
  "var"         { return ZigTypes.KEYWORD_VAR; }
  "volatile"    { return ZigTypes.KEYWORD_VOLATILE; }
  "while"       { return ZigTypes.KEYWORD_WHILE; }

  [a-zA-Z_][a-zA-Z_0-9]* { return ZigTypes.IDENTIFIER; }

  [0-9][_a-zA-Z0-9]*("."[_a-zA-Z0-9]*[eEpP][-+]?[_a-zA-Z0-9]*)?
  { return ZigTypes.NUMBER_LITERAL; }

  ' { yybegin(CHAR); }

  \" { type = ZigTypes.STRING_LITERAL; yybegin(STRING); }
  @\" { type = ZigTypes.IDENTIFIER; yybegin(STRING); }

  @[a-zA-Z_][a-zA-Z_0-9]* { return ZigTypes.BUILTIN; }

  \\\\ { yybegin(MULTILINE_STRING); }

  "//" { type = ZigTypes.LINE_COMMENT; yybegin(COMMENT); }
  "///" { type = ZigTypes.DOC_COMMENT; yybegin(COMMENT); }
  "//!" { type = ZigTypes.CONTAINER_DOC_COMMENT; yybegin(COMMENT); }
  "////" { type = ZigTypes.LINE_COMMENT; yybegin(COMMENT); }

  [\ \n\t\r]+ { return TokenType.WHITE_SPACE; }

  [^] { return TokenType.BAD_CHARACTER; }
}

<CHAR> {
  [\n\x01-\x09\x0b-\x1f\x7f] { yybegin(YYINITIAL); return TokenType.BAD_CHARACTER; }
  <<EOF>> { yybegin(YYINITIAL); return TokenType.BAD_CHARACTER; }
  ' { yybegin(YYINITIAL); return ZigTypes.CHAR_LITERAL; }
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
  <<EOF>> { yybegin(YYINITIAL); return ZigTypes.MULTILINE_STRING_LITERAL_LINE; }
  \r?\n { yybegin(YYINITIAL); return ZigTypes.MULTILINE_STRING_LITERAL_LINE; }
  [^] {}
}

<COMMENT> {
  [\r\x01-\x09\x0b-\x0c\x0e-\x1f\x7f] { yybegin(YYINITIAL); return TokenType.BAD_CHARACTER; }
  <<EOF>> { yybegin(YYINITIAL); return type; }
  \r?\n { yybegin(YYINITIAL); return type; }
  [^] {}
}
