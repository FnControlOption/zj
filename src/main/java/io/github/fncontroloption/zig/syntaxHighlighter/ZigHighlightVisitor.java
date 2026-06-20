package io.github.fncontroloption.zig.syntaxHighlighter;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.HighlightVisitor;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import io.github.fncontroloption.zig.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.regex.Pattern;

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
            "isize",
            "noreturn",
            "null",
            "true",
            "type",
            "undefined",
            "usize",
            "void"
    );

    private static final Pattern INT_PATTERN = Pattern.compile("\\A[iu]\\d+\\Z");

    private HighlightInfoHolder holder;

    @Override
    public boolean suitableForFile(@NotNull PsiFile psiFile) {
        return psiFile instanceof ZigFile;
    }

    @Override
    public void visit(@NotNull PsiElement element) {
        var info = createHighlightInfo(element);
        if (info != null) {
            holder.add(info);
        }
    }

    private @Nullable HighlightInfo createHighlightInfo(@NotNull PsiElement element) {
        return switch (element) {
            case ZigFnProto proto -> {
                var identifier = proto.getNameIdentifier();
                if (identifier == null) {
                    yield null;
                }

                yield createHighlightInfo(identifier, ZigHighlightInfoTypes.STATIC_METHOD);
            }

            case ZigFnParamDecl decl -> {
                var identifier = decl.getNameIdentifier();
                if (identifier == null) {
                    yield null;
                }

                var paramTypeExpr = decl.getFnParamTypeExpr();
                if (paramTypeExpr == null) {
                    yield null;
                }

                var paramType = paramTypeExpr.getExpr();
                if (paramType instanceof ZigIdentifier && paramType.textMatches("type")) {
                    yield createHighlightInfo(identifier, ZigHighlightInfoTypes.TYPE_PARAMETER);
                }

                yield createHighlightInfo(identifier, ZigHighlightInfoTypes.PARAMETER);
            }

            case ZigVarDeclProto proto -> {
                var identifier = proto.getNameIdentifier();
                if (identifier == null) {
                    yield null;
                }

                if (proto.isConst()) {
                    var parent = proto.getParent();
                    if (parent instanceof ZigVarDecl decl) {
                        var initExpr = decl.getVarDeclInitExpr();
                        if (initExpr != null) {
                            var expr = initExpr.getExpr();
                            if (expr != null) {
                                var info = createHighlightInfo(expr);
                                if (info != null && info.type == ZigHighlightInfoTypes.STATIC_METHOD) {
                                    yield createHighlightInfo(identifier, ZigHighlightInfoTypes.STATIC_METHOD);
                                }
                            }
                        }
                    }
                }

                var type = switch (proto.getDeclType()) {
                    case LOCAL_CONST -> ZigHighlightInfoTypes.LOCAL_VARIABLE;
                    case LOCAL_VAR -> ZigHighlightInfoTypes.REASSIGNED_LOCAL_VARIABLE;
                    case GLOBAL_CONST -> ZigHighlightInfoTypes.CONSTANT;
                    case GLOBAL_VAR -> ZigHighlightInfoTypes.STATIC_FIELD;
                };
                yield createHighlightInfo(identifier, type);
            }

            case ZigContainerField field -> {
                var identifier = field.getNameIdentifier();
                if (identifier == null) {
                    yield null;
                }

                yield createHighlightInfo(identifier, ZigHighlightInfoTypes.INSTANCE_FIELD);
            }

            case ZigIdentifier identifier -> {
                var text = identifier.getText();

                if (PRIMITIVES.contains(text) || INT_PATTERN.matcher(text).matches()) {
                    yield createHighlightInfo(element, ZigHighlightInfoTypes.KEYWORD);
                }

                yield createHighlightInfoFromReference(identifier, identifier);
            }

            case ZigFieldAccess access -> {
                var name = access.getFieldName();
                if (name == null) {
                    yield null;
                }

                yield createHighlightInfoFromReference(access, name);
            }

            case ZigEnumLiteral literal -> {
                var name = literal.getFieldName();
                if (name == null) {
                    yield null;
                }

                yield createHighlightInfoFromReference(literal, name);
            }

            case ZigInitListEntry entry -> {
                var name = entry.getFieldName();
                if (name == null) {
                    yield null;
                }

                yield createHighlightInfoFromReference(entry, name);
            }

            case ZigTestDecl decl -> {
                var name = decl.getTestName();
                if (name == null) {
                    yield null;
                }

                yield createHighlightInfoFromReference(decl, name);
            }

            default -> null;
        };
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

    private HighlightInfo createHighlightInfo(PsiElement element, HighlightInfoType type) {
        return HighlightInfo.newHighlightInfo(type).range(element).create();
    }

    private @Nullable HighlightInfo createHighlightInfoFromReference(PsiElement element, PsiElement range) {
        for (var reference : element.getReferences()) {
            var resolved = reference.resolve();
            if (resolved != null) {
                var info = createHighlightInfo(resolved);
                if (info != null) {
                    return createHighlightInfo(range, info.type);
                }
            }
        }
        return null;
    }
}
