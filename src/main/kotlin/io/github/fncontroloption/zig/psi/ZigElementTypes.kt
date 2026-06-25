package io.github.fncontroloption.zig.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement

object ZigElementTypes {
    val CONTAINER_DECL = ZigElementType("CONTAINER_DECL")
    val CONTAINER_TAG = ZigElementType("CONTAINER_TAG")
    val CONTAINER_TAG_ENUM = ZigElementType("CONTAINER_TAG_ENUM")
    val CONTAINER_DECL_BODY = ZigElementType("CONTAINER_DECL_BODY")
    val BLOCK = ZigElementType("BLOCK")
    val BLOCK_BODY = ZigElementType("BLOCK_BODY")
    val BUILTIN_CALL = ZigElementType("BUILTIN_CALL")
    val CALL = ZigElementType("CALL")
    val CALL_ARGS = ZigElementType("CALL_ARGS")
    val VAR_DECL = ZigElementType("VAR_DECL")
    val VAR_DECL_PROTO = ZigElementType("VAR_DECL_PROTO")
    val TYPE_EXPR = ZigElementType("TYPE_EXPR")
    val ALIGN = ZigElementType("ALIGN")
    val ADDRSPACE = ZigElementType("ADDRSPACE")
    val LINKSECTION = ZigElementType("LINKSECTION")
    val CALLCONV = ZigElementType("CALLCONV")
    val INIT_EXPR = ZigElementType("INIT_EXPR")
    val FN_DECL = ZigElementType("FN_DECL")
    val FN_PROTO = ZigElementType("FN_PROTO")
    val FN_PARAMS = ZigElementType("FN_PARAMS")
    val FN_PARAM_DECL = ZigElementType("FN_PARAM_DECL")
    val FN_VARARGS = ZigElementType("FN_VARARGS")
    val ERROR_SET_DECL = ZigElementType("ERROR_SET_DECL")
    val ERROR_SET_MEMBER = ZigElementType("ERROR_SET_MEMBER")
    val ERROR_VALUE = ZigElementType("ERROR_VALUE")
    val TEST_DECL = ZigElementType("TEST_DECL")
    val CHAR_LITERAL = ZigElementType("CHAR_LITERAL")
    val NUMBER_LITERAL = ZigElementType("NUMBER_LITERAL")
    val UNREACHABLE_LITERAL = ZigElementType("UNREACHABLE_LITERAL")
    val ANYFRAME_LITERAL = ZigElementType("ANYFRAME_LITERAL")
    val STRING_LITERAL = ZigElementType("STRING_LITERAL")
    val IDENTIFIER = ZigElementType("IDENTIFIER")
    val FIELD_ACCESS = ZigElementType("FIELD_ACCESS")
    val UNWRAP_OPTIONAL = ZigElementType("UNWRAP_OPTIONAL")
    val OPTIONAL_TYPE = ZigElementType("OPTIONAL_TYPE")
    val ANYFRAME_TYPE = ZigElementType("ANYFRAME_TYPE")
    val POINTER_TYPE = ZigElementType("POINTER_TYPE")
    val POINTER_MODIFIERS = ZigElementType("POINTER_MODIFIERS")
    val ARRAY_TYPE = ZigElementType("ARRAY_TYPE")
    val TYPE_LENGTH = ZigElementType("TYPE_LENGTH")
    val TYPE_SENTINEL = ZigElementType("TYPE_SENTINEL")
    val ERROR_UNION = ZigElementType("ERROR_UNION")
    val ASSIGN_MUL = ZigElementType("ASSIGN_MUL")
    val ASSIGN_DIV = ZigElementType("ASSIGN_DIV")
    val ASSIGN_MOD = ZigElementType("ASSIGN_MOD")
    val ASSIGN_ADD = ZigElementType("ASSIGN_ADD")
    val ASSIGN_SUB = ZigElementType("ASSIGN_SUB")
    val ASSIGN_SHL = ZigElementType("ASSIGN_SHL")
    val ASSIGN_SHL_SAT = ZigElementType("ASSIGN_SHL_SAT")
    val ASSIGN_SHR = ZigElementType("ASSIGN_SHR")
    val ASSIGN_BIT_AND = ZigElementType("ASSIGN_BIT_AND")
    val ASSIGN_BIT_XOR = ZigElementType("ASSIGN_BIT_XOR")
    val ASSIGN_BIT_OR = ZigElementType("ASSIGN_BIT_OR")
    val ASSIGN_MUL_WRAP = ZigElementType("ASSIGN_MUL_WRAP")
    val ASSIGN_ADD_WRAP = ZigElementType("ASSIGN_ADD_WRAP")
    val ASSIGN_SUB_WRAP = ZigElementType("ASSIGN_SUB_WRAP")
    val ASSIGN_MUL_SAT = ZigElementType("ASSIGN_MUL_SAT")
    val ASSIGN_ADD_SAT = ZigElementType("ASSIGN_ADD_SAT")
    val ASSIGN_SUB_SAT = ZigElementType("ASSIGN_SUB_SAT")
    val ASSIGN = ZigElementType("ASSIGN")
    val ASSIGN_DESTRUCTURE = ZigElementType("ASSIGN_DESTRUCTURE")
    val BREAK = ZigElementType("BREAK")
    val CONTINUE = ZigElementType("CONTINUE")
    val COMPTIME = ZigElementType("COMPTIME")
    val NOSUSPEND = ZigElementType("NOSUSPEND")
    val SUSPEND = ZigElementType("SUSPEND")
    val RESUME = ZigElementType("RESUME")
    val RETURN = ZigElementType("RETURN")
    val GROUPED_EXPRESSION = ZigElementType("GROUPED_EXPRESSION")
    val IF = ZigElementType("IF")
    val CONDITION = ZigElementType("CONDITION")
    val PAYLOAD = ZigElementType("PAYLOAD")
    val PAYLOAD_ENTRY = ZigElementType("PAYLOAD_ENTRY")
    val THEN = ZigElementType("THEN")
    val ELSE = ZigElementType("ELSE")
    val EQUAL_EQUAL = ZigElementType("EQUAL_EQUAL")
    val BANG_EQUAL = ZigElementType("BANG_EQUAL")
    val LESS_THAN = ZigElementType("LESS_THAN")
    val GREATER_THAN = ZigElementType("GREATER_THAN")
    val LESS_OR_EQUAL = ZigElementType("LESS_OR_EQUAL")
    val GREATER_OR_EQUAL = ZigElementType("GREATER_OR_EQUAL")
    val MERGE_ERROR_SETS = ZigElementType("MERGE_ERROR_SETS")
    val MUL = ZigElementType("MUL")
    val DIV = ZigElementType("DIV")
    val MOD = ZigElementType("MOD")
    val MUL_WRAP = ZigElementType("MUL_WRAP")
    val MUL_SAT = ZigElementType("MUL_SAT")
    val ADD = ZigElementType("ADD")
    val SUB = ZigElementType("SUB")
    val ARRAY_CAT = ZigElementType("ARRAY_CAT")
    val ADD_WRAP = ZigElementType("ADD_WRAP")
    val SUB_WRAP = ZigElementType("SUB_WRAP")
    val ADD_SAT = ZigElementType("ADD_SAT")
    val SUB_SAT = ZigElementType("SUB_SAT")
    val SHL = ZigElementType("SHL")
    val SHL_SAT = ZigElementType("SHL_SAT")
    val SHR = ZigElementType("SHR")
    val BIT_AND = ZigElementType("BIT_AND")
    val BIT_XOR = ZigElementType("BIT_XOR")
    val BIT_OR = ZigElementType("BIT_OR")
    val ORELSE = ZigElementType("ORELSE")
    val CATCH = ZigElementType("CATCH")
    val BOOL_AND = ZigElementType("BOOL_AND")
    val BOOL_OR = ZigElementType("BOOL_OR")
    val BOOL_NOT = ZigElementType("BOOL_NOT")
    val NEGATION = ZigElementType("NEGATION")
    val BIT_NOT = ZigElementType("BIT_NOT")
    val NEGATION_WRAP = ZigElementType("NEGATION_WRAP")
    val ADDRESS_OF = ZigElementType("ADDRESS_OF")
    val TRY = ZigElementType("TRY")
    val CONTAINER_FIELD = ZigElementType("CONTAINER_FIELD")
    val EXPR_STATEMENT = ZigElementType("EXPR_STATEMENT")
    val DEFER = ZigElementType("DEFER")
    val ERRDEFER = ZigElementType("ERRDEFER")
    val ENUM_LITERAL = ZigElementType("ENUM_LITERAL")
    val ARRAY_INIT = ZigElementType("ARRAY_INIT")
    val INIT_LIST = ZigElementType("INIT_LIST")
    val INIT_LIST_ENTRY = ZigElementType("INIT_LIST_ENTRY")
    val SLICE = ZigElementType("SLICE")
    val INDEXES = ZigElementType("INDEXES")
    val SLICE_END = ZigElementType("SLICE_END")
    val SLICE_SENTINEL = ZigElementType("SLICE_SENTINEL")
    val ARRAY_ACCESS = ZigElementType("ARRAY_ACCESS")
    val DEREF = ZigElementType("DEREF")
    val SWITCH = ZigElementType("SWITCH")
    val SWITCH_PRONG_LIST = ZigElementType("SWITCH_PRONG_LIST")
    val SWITCH_PRONG = ZigElementType("SWITCH_PRONG")
    val SWITCH_ITEM = ZigElementType("SWITCH_ITEM")
    val SWITCH_RESULT = ZigElementType("SWITCH_RESULT")
    val WHILE = ZigElementType("WHILE")
    val WHILE_CONTINUE_EXPR = ZigElementType("WHILE_CONTINUE_EXPR")
    val FOR = ZigElementType("FOR")
    val FOR_INPUTS = ZigElementType("FOR_INPUTS")
    val FOR_RANGE = ZigElementType("FOR_RANGE")
    val MULTILINE_STRING_LITERAL = ZigElementType("MULTILINE_STRING_LITERAL")
    val ASM = ZigElementType("ASM")
    val ASM_OUTPUT_LIST = ZigElementType("ASM_OUTPUT_LIST")
    val ASM_OUTPUT = ZigElementType("ASM_OUTPUT")
    val ASM_INPUT_LIST = ZigElementType("ASM_INPUT_LIST")
    val ASM_INPUT = ZigElementType("ASM_INPUT")
    val ASM_CLOBBERS = ZigElementType("ASM_CLOBBERS")

    object Factory {
        fun createElement(node: ASTNode): PsiElement {
            val type = node.elementType
            if (type === CONTAINER_DECL) {
                return ZigContainerDecl(node)
            }
            if (type === CONTAINER_DECL_BODY) {
                return ZigContainerDeclBody(node)
            }
            if (type === BLOCK) {
                return ZigBlock(node)
            }
            if (type === BLOCK_BODY) {
                return ZigBlockBody(node)
            }
            if (type === BUILTIN_CALL) {
                return ZigBuiltinCall(node)
            }
            if (type === CALL_ARGS) {
                return ZigCallArgs(node)
            }
            if (type === VAR_DECL) {
                return ZigVarDecl(node)
            }
            if (type === VAR_DECL_PROTO) {
                return ZigVarDeclProto(node)
            }
            if (type === FN_DECL) {
                return ZigFnDecl(node)
            }
            if (type === FN_PROTO) {
                return ZigFnProto(node)
            }
            if (type === FN_PARAMS) {
                return ZigFnParams(node)
            }
            if (type === FN_PARAM_DECL) {
                return ZigFnParamDecl(node)
            }
            if (type === IDENTIFIER) {
                return ZigIdentifier(node)
            }
            if (type === TEST_DECL) {
                return ZigTestDecl(node)
            }
            if (type === FIELD_ACCESS) {
                return ZigFieldAccess(node)
            }
            if (type === TYPE_EXPR) {
                return ZigTypeExpr(node)
            }
            if (type === INIT_EXPR) {
                return ZigInitExpr(node)
            }
            if (type === CONTAINER_FIELD) {
                return ZigContainerField(node)
            }
            if (type === OPTIONAL_TYPE) {
                return ZigOptionalType(node)
            }
            if (type === POINTER_TYPE) {
                return ZigPointerType(node)
            }
            if (type === ARRAY_TYPE) {
                return ZigArrayType(node)
            }
            if (type === ERROR_UNION) {
                return ZigErrorUnion(node)
            }
            if (type === ENUM_LITERAL) {
                return ZigEnumLiteral(node)
            }
            if (type === CALL) {
                return ZigCall(node)
            }
            if (type === STRING_LITERAL) {
                return ZigStringLiteral(node)
            }
            if (type === RETURN) {
                return ZigReturn(node)
            }
            if (type === ARRAY_INIT) {
                return ZigArrayInit(node)
            }
            if (type === INIT_LIST) {
                return ZigInitList(node)
            }
            if (type === INIT_LIST_ENTRY) {
                return ZigInitListEntry(node)
            }
            if (type === DEREF) {
                return ZigDeref(node)
            }
            if (type === ARRAY_ACCESS) {
                return ZigArrayAccess(node)
            }
            if (type === SLICE) {
                return ZigSlice(node)
            }
            if (type === UNWRAP_OPTIONAL) {
                return ZigUnwrapOptional(node)
            }
            if (type === BOOL_NOT) {
                return ZigBoolNot(node)
            }
            if (type === NEGATION) {
                return ZigNegation(node)
            }
            if (type === BIT_NOT) {
                return ZigBitNot(node)
            }
            if (type === NEGATION_WRAP) {
                return ZigNegationWrap(node)
            }
            if (type === ADDRESS_OF) {
                return ZigAddressOf(node)
            }
            if (type === TRY) {
                return ZigTry(node)
            }
            if (type === IF) {
                return ZigIf(node)
            }
            if (type === THEN) {
                return ZigThen(node)
            }
            if (type === ELSE) {
                return ZigElse(node)
            }
            if (type === CONDITION) {
                return ZigCondition(node)
            }
            if (type === SWITCH) {
                return ZigSwitch(node)
            }
            if (type === SWITCH_PRONG_LIST) {
                return ZigSwitchProngList(node)
            }
            if (type === SWITCH_PRONG) {
                return ZigSwitchProng(node)
            }
            if (type === SWITCH_ITEM) {
                return ZigSwitchItem(node)
            }
            if (type === SWITCH_RESULT) {
                return ZigSwitchResult(node)
            }
            if (type === CATCH) {
                return ZigCatch(node)
            }
            if (type === WHILE) {
                return ZigWhile(node)
            }
            if (type === FOR) {
                return ZigFor(node)
            }
            if (type === FOR_INPUTS) {
                return ZigForInputs(node)
            }
            if (type === PAYLOAD) {
                return ZigPayload(node)
            }
            if (type === PAYLOAD_ENTRY) {
                return ZigPayloadEntry(node)
            }
            if (type === BREAK) {
                return ZigBreak(node)
            }
            if (type === CONTINUE) {
                return ZigContinue(node)
            }
            if (type === ASSIGN) {
                return ZigAssign(node)
            }
            if (type === ORELSE) {
                return ZigOrelse(node)
            }
            if (type === GROUPED_EXPRESSION) {
                return ZigGroupedExpression(node)
            }
            return ZigPsiElement(node) // TODO
        }
    }
}
