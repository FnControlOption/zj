package io.github.fncontroloption.zig.psi;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;

public interface ZigElementTypes {
    IElementType CONTAINER_DECL = new ZigElementType("CONTAINER_DECL");
    IElementType CONTAINER_DECL_ARG = new ZigElementType("CONTAINER_DECL_ARG");
    IElementType CONTAINER_DECL_BODY = new ZigElementType("CONTAINER_DECL_BODY");
    IElementType BLOCK = new ZigElementType("BLOCK");
    IElementType BUILTIN_CALL = new ZigElementType("BUILTIN_CALL");
    IElementType CALL = new ZigElementType("CALL");
    IElementType CALL_ARGS = new ZigElementType("CALL_ARGS");
    IElementType VAR_DECL = new ZigElementType("VAR_DECL");
    IElementType VAR_DECL_PROTO = new ZigElementType("VAR_DECL_PROTO");
    IElementType VAR_DECL_TYPE = new ZigElementType("VAR_DECL_TYPE");
    IElementType ALIGN = new ZigElementType("ALIGN");
    IElementType ADDRSPACE = new ZigElementType("ADDRSPACE");
    IElementType LINKSECTION = new ZigElementType("LINKSECTION");
    IElementType CALLCONV = new ZigElementType("CALLCONV");
    IElementType INIT_EXPR = new ZigElementType("INIT_EXPR");
    IElementType FN_DECL = new ZigElementType("FN_DECL");
    IElementType FN_PROTO = new ZigElementType("FN_PROTO");
    IElementType FN_PARAMS = new ZigElementType("FN_PARAMS");
    IElementType FN_PARAM_DECL = new ZigElementType("FN_PARAM_DECL");
    IElementType FN_VARARGS = new ZigElementType("FN_VARARGS");
    IElementType FN_RETURN_TYPE = new ZigElementType("FN_RETURN_TYPE");
    IElementType ERROR_SET_DECL = new ZigElementType("ERROR_SET_DECL");
    IElementType ERROR_VALUE = new ZigElementType("ERROR_VALUE");
    IElementType TEST_DECL = new ZigElementType("TEST_DECL");
    IElementType CHAR_LITERAL = new ZigElementType("CHAR_LITERAL");
    IElementType NUMBER_LITERAL = new ZigElementType("NUMBER_LITERAL");
    IElementType UNREACHABLE_LITERAL = new ZigElementType("UNREACHABLE_LITERAL");
    IElementType ANYFRAME_LITERAL = new ZigElementType("ANYFRAME_LITERAL");
    IElementType STRING_LITERAL = new ZigElementType("STRING_LITERAL");
    IElementType IDENTIFIER = new ZigElementType("IDENTIFIER");
    IElementType FIELD_ACCESS = new ZigElementType("FIELD_ACCESS");
    IElementType UNWRAP_OPTIONAL = new ZigElementType("UNWRAP_OPTIONAL");
    IElementType OPTIONAL_TYPE = new ZigElementType("OPTIONAL_TYPE");
    IElementType ANYFRAME_TYPE = new ZigElementType("ANYFRAME_TYPE");
    IElementType PTR_TYPE = new ZigElementType("PTR_TYPE");
    IElementType SLICE_TYPE = new ZigElementType("SLICE_TYPE");
    IElementType ERROR_UNION = new ZigElementType("ERROR_UNION");
    IElementType ASSIGN = new ZigElementType("ASSIGN");
    IElementType COMPTIME = new ZigElementType("COMPTIME");
    IElementType GROUPED_EXPRESSION = new ZigElementType("GROUPED_EXPRESSION");

    class Factory {
        public static PsiElement createElement(ASTNode node) {
            IElementType type = node.getElementType();
            if (type == CONTAINER_DECL) {
                return new ZigContainerDecl(node);
            }
            if (type == CONTAINER_DECL_BODY) {
                return new ZigContainerDeclBody(node);
            }
            if (type == BLOCK) {
                return new ZigBlock(node);
            }
            if (type == BUILTIN_CALL) {
                return new ZigBuiltinCall(node);
            }
            if (type == CALL_ARGS) {
                return new ZigCallArgs(node);
            }
            if (type == VAR_DECL) {
                return new ZigVarDecl(node);
            }
            if (type == VAR_DECL_PROTO) {
                return new ZigVarDeclProto(node);
            }
            if (type == FN_DECL) {
                return new ZigFnDecl(node);
            }
            if (type == FN_PROTO) {
                return new ZigFnProto(node);
            }
            if (type == FN_PARAMS) {
                return new ZigFnParams(node);
            }
            if (type == FN_PARAM_DECL) {
                return new ZigFnParamDecl(node);
            }
            if (type == IDENTIFIER) {
                return new ZigIdentifier(node);
            }
            if (type == TEST_DECL) {
                return new ZigTestDecl(node);
            }
            return new ZigPsiElement(node); // TODO
        }
    }
}
