package io.github.fncontroloption.zig.psi;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;

public interface ZigElementTypes {
    IElementType CONTAINER_DECL = new ZigElementType("CONTAINER_DECL");
    IElementType CONTAINER_TAG = new ZigElementType("CONTAINER_TAG");
    IElementType CONTAINER_TAG_ENUM = new ZigElementType("CONTAINER_TAG_ENUM");
    IElementType CONTAINER_DECL_BODY = new ZigElementType("CONTAINER_DECL_BODY");
    IElementType BLOCK = new ZigElementType("BLOCK");
    IElementType BLOCK_BODY = new ZigElementType("BLOCK_BODY");
    IElementType BUILTIN_CALL = new ZigElementType("BUILTIN_CALL");
    IElementType CALL = new ZigElementType("CALL");
    IElementType CALL_ARGS = new ZigElementType("CALL_ARGS");
    IElementType VAR_DECL = new ZigElementType("VAR_DECL");
    IElementType VAR_DECL_PROTO = new ZigElementType("VAR_DECL_PROTO");
    IElementType TYPE_EXPR = new ZigElementType("TYPE_EXPR");
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
    IElementType ERROR_SET_DECL = new ZigElementType("ERROR_SET_DECL");
    IElementType ERROR_SET_MEMBER = new ZigElementType("ERROR_SET_MEMBER");
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
    IElementType POINTER_TYPE = new ZigElementType("POINTER_TYPE");
    IElementType POINTER_MODIFIERS = new ZigElementType("POINTER_MODIFIERS");
    IElementType ARRAY_TYPE = new ZigElementType("ARRAY_TYPE");
    IElementType TYPE_LENGTH = new ZigElementType("TYPE_LENGTH");
    IElementType TYPE_SENTINEL = new ZigElementType("TYPE_SENTINEL");
    IElementType ERROR_UNION = new ZigElementType("ERROR_UNION");
    IElementType ASSIGN_MUL = new ZigElementType("ASSIGN_MUL");
    IElementType ASSIGN_DIV = new ZigElementType("ASSIGN_DIV");
    IElementType ASSIGN_MOD = new ZigElementType("ASSIGN_MOD");
    IElementType ASSIGN_ADD = new ZigElementType("ASSIGN_ADD");
    IElementType ASSIGN_SUB = new ZigElementType("ASSIGN_SUB");
    IElementType ASSIGN_SHL = new ZigElementType("ASSIGN_SHL");
    IElementType ASSIGN_SHL_SAT = new ZigElementType("ASSIGN_SHL_SAT");
    IElementType ASSIGN_SHR = new ZigElementType("ASSIGN_SHR");
    IElementType ASSIGN_BIT_AND = new ZigElementType("ASSIGN_BIT_AND");
    IElementType ASSIGN_BIT_XOR = new ZigElementType("ASSIGN_BIT_XOR");
    IElementType ASSIGN_BIT_OR = new ZigElementType("ASSIGN_BIT_OR");
    IElementType ASSIGN_MUL_WRAP = new ZigElementType("ASSIGN_MUL_WRAP");
    IElementType ASSIGN_ADD_WRAP = new ZigElementType("ASSIGN_ADD_WRAP");
    IElementType ASSIGN_SUB_WRAP = new ZigElementType("ASSIGN_SUB_WRAP");
    IElementType ASSIGN_MUL_SAT = new ZigElementType("ASSIGN_MUL_SAT");
    IElementType ASSIGN_ADD_SAT = new ZigElementType("ASSIGN_ADD_SAT");
    IElementType ASSIGN_SUB_SAT = new ZigElementType("ASSIGN_SUB_SAT");
    IElementType ASSIGN = new ZigElementType("ASSIGN");
    IElementType BREAK = new ZigElementType("BREAK");
    IElementType CONTINUE = new ZigElementType("CONTINUE");
    IElementType COMPTIME = new ZigElementType("COMPTIME");
    IElementType NOSUSPEND = new ZigElementType("NOSUSPEND");
    IElementType SUSPEND = new ZigElementType("SUSPEND");
    IElementType RESUME = new ZigElementType("RESUME");
    IElementType RETURN = new ZigElementType("RETURN");
    IElementType GROUPED_EXPRESSION = new ZigElementType("GROUPED_EXPRESSION");
    IElementType IF = new ZigElementType("IF");
    IElementType CONDITION = new ZigElementType("CONDITION");
    IElementType PAYLOAD = new ZigElementType("PAYLOAD");
    IElementType PAYLOAD_ENTRY = new ZigElementType("PAYLOAD_ENTRY");
    IElementType THEN = new ZigElementType("THEN");
    IElementType ELSE = new ZigElementType("ELSE");
    IElementType EQUAL_EQUAL = new ZigElementType("EQUAL_EQUAL");
    IElementType BANG_EQUAL = new ZigElementType("BANG_EQUAL");
    IElementType LESS_THAN = new ZigElementType("LESS_THAN");
    IElementType GREATER_THAN = new ZigElementType("GREATER_THAN");
    IElementType LESS_OR_EQUAL = new ZigElementType("LESS_OR_EQUAL");
    IElementType GREATER_OR_EQUAL = new ZigElementType("GREATER_OR_EQUAL");
    IElementType MERGE_ERROR_SETS = new ZigElementType("MERGE_ERROR_SETS");
    IElementType MUL = new ZigElementType("MUL");
    IElementType DIV = new ZigElementType("DIV");
    IElementType MOD = new ZigElementType("MOD");
    IElementType MUL_WRAP = new ZigElementType("MUL_WRAP");
    IElementType MUL_SAT = new ZigElementType("MUL_SAT");
    IElementType ADD = new ZigElementType("ADD");
    IElementType SUB = new ZigElementType("SUB");
    IElementType ARRAY_CAT = new ZigElementType("ARRAY_CAT");
    IElementType ADD_WRAP = new ZigElementType("ADD_WRAP");
    IElementType SUB_WRAP = new ZigElementType("SUB_WRAP");
    IElementType ADD_SAT = new ZigElementType("ADD_SAT");
    IElementType SUB_SAT = new ZigElementType("SUB_SAT");
    IElementType SHL = new ZigElementType("SHL");
    IElementType SHL_SAT = new ZigElementType("SHL_SAT");
    IElementType SHR = new ZigElementType("SHR");
    IElementType BIT_AND = new ZigElementType("BIT_AND");
    IElementType BIT_XOR = new ZigElementType("BIT_XOR");
    IElementType BIT_OR = new ZigElementType("BIT_OR");
    IElementType ORELSE = new ZigElementType("ORELSE");
    IElementType CATCH = new ZigElementType("CATCH");
    IElementType CATCH_RESULT = new ZigElementType("CATCH_RESULT");
    IElementType BOOL_AND = new ZigElementType("BOOL_AND");
    IElementType BOOL_OR = new ZigElementType("BOOL_OR");
    IElementType BOOL_NOT = new ZigElementType("BOOL_NOT");
    IElementType NEGATION = new ZigElementType("NEGATION");
    IElementType BIT_NOT = new ZigElementType("BIT_NOT");
    IElementType NEGATION_WRAP = new ZigElementType("NEGATION_WRAP");
    IElementType ADDRESS_OF = new ZigElementType("ADDRESS_OF");
    IElementType TRY = new ZigElementType("TRY");
    IElementType CONTAINER_FIELD = new ZigElementType("CONTAINER_FIELD");
    IElementType EXPR_STATEMENT = new ZigElementType("EXPR_STATEMENT");
    IElementType DEFER = new ZigElementType("DEFER");
    IElementType ERRDEFER = new ZigElementType("ERRDEFER");
    IElementType ENUM_LITERAL = new ZigElementType("ENUM_LITERAL");
    IElementType ARRAY_INIT = new ZigElementType("ARRAY_INIT");
    IElementType INIT_LIST = new ZigElementType("INIT_LIST");
    IElementType INIT_LIST_ENTRY = new ZigElementType("INIT_LIST_ENTRY");
    IElementType SLICE = new ZigElementType("SLICE");
    IElementType INDEXES = new ZigElementType("INDEXES");
    IElementType SLICE_END = new ZigElementType("SLICE_END");
    IElementType SLICE_SENTINEL = new ZigElementType("SLICE_SENTINEL");
    IElementType ARRAY_ACCESS = new ZigElementType("ARRAY_ACCESS");
    IElementType DEREF = new ZigElementType("DEREF");
    IElementType SWITCH = new ZigElementType("SWITCH");
    IElementType SWITCH_PRONG_LIST = new ZigElementType("SWITCH_PRONG_LIST");
    IElementType SWITCH_PRONG = new ZigElementType("SWITCH_PRONG");
    IElementType SWITCH_ITEM = new ZigElementType("SWITCH_ITEM");
    IElementType SWITCH_RESULT = new ZigElementType("SWITCH_RESULT");
    IElementType WHILE = new ZigElementType("WHILE");
    IElementType WHILE_CONTINUE_EXPR = new ZigElementType("WHILE_CONTINUE_EXPR");
    IElementType FOR = new ZigElementType("FOR");
    IElementType FOR_INPUTS = new ZigElementType("FOR_INPUTS");
    IElementType FOR_RANGE = new ZigElementType("FOR_RANGE");
    IElementType MULTILINE_STRING_LITERAL = new ZigElementType("MULTILINE_STRING_LITERAL");

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
            if (type == BLOCK_BODY) {
                return new ZigBlockBody(node);
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
            if (type == FIELD_ACCESS) {
                return new ZigFieldAccess(node);
            }
            if (type == TYPE_EXPR) {
                return new ZigTypeExpr(node);
            }
            if (type == INIT_EXPR) {
                return new ZigInitExpr(node);
            }
            if (type == CONTAINER_FIELD) {
                return new ZigContainerField(node);
            }
            if (type == OPTIONAL_TYPE) {
                return new ZigOptionalType(node);
            }
            if (type == POINTER_TYPE) {
                return new ZigPointerType(node);
            }
            if (type == ARRAY_TYPE) {
                return new ZigArrayType(node);
            }
            if (type == ERROR_UNION) {
                return new ZigErrorUnion(node);
            }
            if (type == ENUM_LITERAL) {
                return new ZigEnumLiteral(node);
            }
            if (type == CALL) {
                return new ZigCall(node);
            }
            if (type == STRING_LITERAL) {
                return new ZigStringLiteral(node);
            }
            if (type == RETURN) {
                return new ZigReturn(node);
            }
            if (type == ARRAY_INIT) {
                return new ZigArrayInit(node);
            }
            if (type == INIT_LIST) {
                return new ZigInitList(node);
            }
            if (type == INIT_LIST_ENTRY) {
                return new ZigInitListEntry(node);
            }
            if (type == DEREF) {
                return new ZigDeref(node);
            }
            if (type == ARRAY_ACCESS) {
                return new ZigArrayAccess(node);
            }
            if (type == SLICE) {
                return new ZigSlice(node);
            }
            if (type == UNWRAP_OPTIONAL) {
                return new ZigUnwrapOptional(node);
            }
            if (type == BOOL_NOT) {
                return new ZigBoolNot(node);
            }
            if (type == NEGATION) {
                return new ZigNegation(node);
            }
            if (type == BIT_NOT) {
                return new ZigBitNot(node);
            }
            if (type == NEGATION_WRAP) {
                return new ZigNegationWrap(node);
            }
            if (type == ADDRESS_OF) {
                return new ZigAddressOf(node);
            }
            if (type == TRY) {
                return new ZigTry(node);
            }
            if (type == IF) {
                return new ZigIf(node);
            }
            if (type == THEN) {
                return new ZigThen(node);
            }
            if (type == ELSE) {
                return new ZigElse(node);
            }
            if (type == CONDITION) {
                return new ZigCondition(node);
            }
            if (type == SWITCH) {
                return new ZigSwitch(node);
            }
            if (type == SWITCH_PRONG_LIST) {
                return new ZigSwitchProngList(node);
            }
            if (type == SWITCH_PRONG) {
                return new ZigSwitchProng(node);
            }
            if (type == SWITCH_ITEM) {
                return new ZigSwitchItem(node);
            }
            if (type == SWITCH_RESULT) {
                return new ZigSwitchResult(node);
            }
            if (type == CATCH) {
                return new ZigCatch(node);
            }
            if (type == CATCH_RESULT) {
                return new ZigCatchResult(node);
            }
            if (type == WHILE) {
                return new ZigWhile(node);
            }
            if (type == FOR) {
                return new ZigFor(node);
            }
            if (type == FOR_INPUTS) {
                return new ZigForInputs(node);
            }
            if (type == PAYLOAD) {
                return new ZigPayload(node);
            }
            if (type == PAYLOAD_ENTRY) {
                return new ZigPayloadEntry(node);
            }
            if (type == BREAK) {
                return new ZigBreak(node);
            }
            if (type == CONTINUE) {
                return new ZigContinue(node);
            }
            return new ZigPsiElement(node); // TODO
        }
    }
}
