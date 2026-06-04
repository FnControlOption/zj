package io.github.fncontroloption.zig.syntaxHighlighter;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.HighlightVisitor;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import io.github.fncontroloption.zig.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/** @see com.intellij.codeInsight.daemon.impl.analysis.JavaNamesHighlightVisitor */
public class ZigHighlightVisitor implements HighlightVisitor {
    private static final Set<String> PRIMITIVES = Set.of(
            "anyerror",
            "anyframe",
            "anyopaque",
            "bool",
            "c_int",
            "c_long",
            "c_longdouble",
            "c_longlong",
            "c_char",
            "c_short",
            "c_uint",
            "c_ulong",
            "c_ulonglong",
            "c_ushort",
            "comptime_float",
            "comptime_int",
            "f128",
            "f16",
            "f32",
            "f64",
            "f80",
            "false",
            "i16",
            "i32",
            "i64",
            "i128",
            "i8",
            "isize",
            "noreturn",
            "null",
            "true",
            "type",
            "u16",
            "u29",
            "u32",
            "u64",
            "u128",
            "u1",
            "u8",
            "undefined",
            "usize",
            "void"
    );

    private HighlightInfoHolder holder;

    @Override
    public boolean suitableForFile(@NotNull PsiFile psiFile) {
        return psiFile instanceof ZigFile;
    }

    @Override
    public void visit(@NotNull PsiElement element) {
        switch (element) {
            case ZigFnDecl decl -> {
                var identifier = decl.getNameIdentifier();
                if (identifier == null) {
                    return;
                }

                addHighlight(identifier, ZigHighlightInfoTypes.STATIC_METHOD);
            }

            case ZigVarDecl decl -> {
                var identifier = decl.getNameIdentifier();
                if (identifier == null) {
                    return;
                }

                var type = switch (decl.getDeclType()) {
                    case LOCAL_CONST -> ZigHighlightInfoTypes.LOCAL_VARIABLE;
                    case LOCAL_VAR ->  ZigHighlightInfoTypes.REASSIGNED_LOCAL_VARIABLE;
                    case GLOBAL_CONST -> ZigHighlightInfoTypes.CONSTANT;
                    case GLOBAL_VAR -> ZigHighlightInfoTypes.STATIC_FIELD;
                };
                addHighlight(identifier, type);
            }

            default -> {
                var node = element.getNode();
                if (node == null) {
                    return;
                }

                var nodeType = node.getElementType();
                if (nodeType == ZigTokenTypes.IDENTIFIER && PRIMITIVES.contains(node.getText())) {
                    addHighlight(element, ZigHighlightInfoTypes.KEYWORD);
                }
            }
        }
    }

    @Override
    public boolean analyze(@NotNull PsiFile psiFile, boolean updateWholeFile, @NotNull HighlightInfoHolder holder, @NotNull Runnable action) {
        this.holder = holder;
        try {
            action.run();
        } finally {
            this.holder = null;
        }
        return true;
    }

    @Override
    @SuppressWarnings("MethodDoesntCallSuperMethod")
    public @NotNull HighlightVisitor clone() {
        return new ZigHighlightVisitor();
    }

    private void addHighlight(PsiElement element, HighlightInfoType type) {
        holder.add(HighlightInfo.newHighlightInfo(type).range(element).create());
    }
}
