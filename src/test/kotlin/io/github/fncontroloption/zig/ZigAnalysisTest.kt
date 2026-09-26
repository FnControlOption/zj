package io.github.fncontroloption.zig

import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.requireIs
import io.github.fncontroloption.zig.psi2.ZigContainerFieldImpl
import io.github.fncontroloption.zig.psi2.ZigContainerKind
import io.github.fncontroloption.zig.psi2.ZigElement
import io.github.fncontroloption.zig.psi2.ZigFile
import io.github.fncontroloption.zig.psi2.ZigPointerSize
import io.github.fncontroloption.zig.psi2.ZigVarDecl

class ZigAnalysisTest : BasePlatformTestCase() {
    fun createFile(text: String): ZigFile {
        val fileFactory = PsiFileFactory.getInstance(project)
        val file = fileFactory.createFileFromText("dummy.zig", ZigLanguage, text)
        return file as ZigFile
    }

    fun createExpression(text: String): ZigElement {
        val file = createFile("const dummy = $text;")
        val varDecl = file.members.first() as ZigVarDecl
        return varDecl.initializer!!
    }

    fun resolveValues(text: String): Set<ZigValue> {
        val expr = createExpression(text)
        return ZigAnalyzer.resolveValues(expr)
    }

    fun resolveValue(text: String): ZigValue {
        val values = resolveValues(text)
        assertEquals(1, values.size)
        return values.first()
    }

    fun testContainer() {
        val type = resolveValue("struct { foo: u42 }").requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.STRUCT, type.element.containerKind)
        val members = type.element.members.toList()
        assertEquals(1, members.size)
        val field = members[0].requireIs<ZigContainerFieldImpl>()
        assertEquals("foo", field.name)
    }

    fun testFunctionType() {
        val type = resolveValue("fn (u42) bool").requireIs<ZigFunctionType>()
        assertEquals("bool", type.element.returnTypeElement?.text)
    }

    fun testOptionalType() {
        val type = resolveValue("?struct { foo: u42 }").requireIs<ZigOptionalType>()
        val child = type.child.requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.STRUCT, child.element.containerKind)
    }

    fun testErrorSet() {
        val text = "error{Foo,Bar}"
        val type = resolveValue(text).requireIs<ZigErrorSetType>()
        assertEquals(setOf("Foo", "Bar"), type.names)
    }

    fun testErrorSetAccess() {
        val text = "error{Foo,Bar}.Foo"
        val value = resolveValue(text).requireIs<ZigErrorSetValue>()
        assertEquals("Foo", value.name)
        assertEquals(ZigErrorSetType(setOf("Foo", "Bar")), value.typeOf)
    }

    fun testErrorValue() {
        val text = "error.Foo"
        val value = resolveValue(text).requireIs<ZigErrorSetValue>()
        assertEquals("Foo", value.name)
        assertEquals(ZigErrorSetType(setOf("Foo")), value.typeOf)
    }

    fun testErrorUnion() {
        val type = resolveValue("error{Foo}!bool").requireIs<ZigErrorUnionType>()
        assertEquals(ZigErrorSetType(setOf("Foo")), type.errorSet)
        assertEquals(ZigBoolType, type.payload)
    }

    fun testPointerType() {
        val type = resolveValue("*u42").requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.ONE, type.size)
        assertEquals(null, type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 42), type.child)
    }

    fun testSliceType() {
        val type = resolveValue("[:7]u42").requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.SLICE, type.size)
        assertEquals(ZigIntValue(ZigComptimeIntType, 7), type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 42), type.child)
    }

    fun testManyItemPointerType() {
        val type = resolveValue("[*:7]u42").requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.MANY, type.size)
        assertEquals(ZigIntValue(ZigComptimeIntType, 7), type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 42), type.child)
    }

    fun testCPointerType() {
        val type = resolveValue("[*c]u42").requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.C, type.size)
        assertEquals(null, type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 42), type.child)
    }

    fun testArrayType() {
        val type = resolveValue("[6:7]u42").requireIs<ZigArrayType>()
        assertEquals(ZigIntValue(ZigComptimeIntType, 6), type.length)
        assertEquals(ZigIntValue(ZigComptimeIntType, 7), type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 42), type.child)
    }

    fun testTupleType() {
        val type = resolveValue("struct { bool, usize }").requireIs<ZigTupleType>()
        assertEquals(listOf(ZigBoolType, ZigUsizeType), type.fields)
    }

    fun testFixedFloatType() {
        assertEquals(ZigFixedFloatType(32), resolveValue("f32"))
        assertEquals(ZigFixedFloatType(80), resolveValue("f80"))
    }

    fun testFixedIntType() {
        assertEquals(ZigFixedIntType(ZigSignedness.SIGNED, 42), resolveValue("i42"))
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 67), resolveValue("u67"))
    }

    fun testComptime() {
        assertEquals(ZigUndefinedValue, resolveValue("comptime undefined"))
    }

    fun testEnumFieldAccess() {
        val value = resolveValue("enum { foo, bar }.foo").requireIs<ZigEnumLiteralValue>()
        assertEquals("foo", value.name)
        val type = value.typeOf.requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.element.containerKind)
    }

    fun testDeclAccessConstWithType() {
        val text = """
            struct {
                const foo: enum { bar, baz } = .bar;
            }.foo
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.element.containerKind)
    }

    fun testDeclAccessVarWithType() {
        val text = """
            struct {
                var foo: enum { bar, baz } = .bar;
            }.foo
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.element.containerKind)
    }

    fun testDeclAccessConstOfType() {
        val text = """
            struct {
                const Foo = enum { bar, baz };
            }.Foo
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.element.containerKind)
    }

    fun testDeclAccessVarOfType() {
        val text = """
            struct {
                var Foo = enum { bar, baz };
            }.Foo
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigTypeType, value.typeOf)
    }

    fun testDeclAccessConstWithoutType() {
        val text = """
            struct {
                const foo: enum { bar, baz } = .bar;
                const qux = foo;
            }.qux
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.element.containerKind)
    }

    fun testDeclAccessVarWithoutType() {
        val text = """
            struct {
                const foo: enum { bar, baz } = .bar;
                var qux = foo;
            }.qux
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.element.containerKind)
    }

    fun testFieldAccessTuple() {
        val text = "@as(struct { bool, usize }, .{ true, 42 }).@\"1\""
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigUsizeType, value.typeOf)
    }

    fun testFunctionValue() {
        val text = """
            struct {
                fn foo(bar: u42) bool {}
            }.foo
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigFunctionType>()
        assertEquals("bool", type.element.returnTypeElement?.text)
    }

    fun testFunctionCall() {
        val text = """
            struct {
                fn foo(bar: u42) bool {}
            }.foo(42)
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigBoolType, value.typeOf)
    }

    fun testMethodCall() {
        val text = """
            struct {
                const Foo = struct {
                    bar: u42 = 42,
                    fn baz(self: @This()) bool {}
                };
                const qux: Foo = .{};
            }.qux.baz()
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigBoolType, value.typeOf)
    }

    fun testUnwrapOptional() {
        val text = "@as(?bool, true).?"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigBoolType, value.typeOf)
    }

    fun testTry() {
        val text = "try @as(error{Foo}!bool, true)"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigBoolType, value.typeOf)
    }

    fun testDeref() {
        val text = "@as(*const bool, &true).*"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigBoolType, value.typeOf)
    }

    fun testArrayAccessArray() {
        val text = "@as([2]bool, .{ true, false })[1]"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigBoolType, value.typeOf)
    }

    fun testArrayAccessArrayPointer() {
        val text = "@as(*const [2]bool, &.{ true, false })[1]"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigBoolType, value.typeOf)
    }

    fun testArrayAccessSlice() {
        val text = "@as([]const bool, &.{ true, false })[1]"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigBoolType, value.typeOf)
    }

    fun testArrayAccessManyItemPointer() {
        val text = "@as([*]const bool, &.{ true, false })[1]"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigBoolType, value.typeOf)
    }

    fun testArrayAccessTuple() {
        val text = "@as(struct { bool, usize }, .{ true, 42 })[1]"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigUsizeType, value.typeOf)
    }

    fun testArrayInit() {
        val text = "[2]bool{ true, false }"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigArrayType>()
        assertEquals(ZigIntValue(ZigComptimeIntType, 2), type.length)
        assertEquals(null, type.sentinel)
        assertEquals(ZigBoolType, type.child)
    }

    fun testTupleInit() {
        val text = ".{ true, false }"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigTupleType>()
        assertEquals(listOf(ZigBoolType, ZigBoolType), type.fields)
    }

    fun testCatch() {
        val text = "@as(error{Foo}!usize, undefined) catch undefined"
        val values = resolveValues(text)
        assertEquals(2, values.size)
        assertTrue(values.any { it is ZigUnknown && it.typeOf == ZigUsizeType })
        assertTrue(values.contains(ZigUndefinedValue))
    }

    fun testOrelse() {
        val text = "@as(?usize, undefined) orelse undefined"
        val values = resolveValues(text)
        assertEquals(2, values.size)
        assertTrue(values.any { it is ZigUnknown && it.typeOf == ZigUsizeType })
        assertTrue(values.contains(ZigUndefinedValue))
    }

    fun testMergeErrorSets() {
        val text = "error{Foo,Bar} || error{Baz,Qux}"
        val type = resolveValue(text).requireIs<ZigErrorSetType>()
        assertEquals(setOf("Foo", "Bar", "Baz", "Qux"), type.names)
    }

    fun testIntLiteral() {
        val text = "42"
        val value = resolveValue(text).requireIs<ZigIntValue>()
        assertEquals(ZigComptimeIntType, value.typeOf)
        assertEquals(42, value.value)
    }

    fun testCharLiteral() {
        val text = "'e'"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigComptimeIntType, value.typeOf)
    }

    fun testGenericTupleType() {
        val text = """
            struct {
                fn Foo(T: type) type {
                    return struct { T, usize };
                }
            }.Foo(bool)
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigTupleType>()
        assertEquals(listOf(ZigBoolType, ZigUsizeType), type.fields)
    }

    fun testEnumLiteral() {
        val text = ".foo"
        val value = resolveValue(text).requireIs<ZigEnumLiteralValue>()
        assertEquals("foo", value.name)
        assertEquals(ZigEnumLiteralType, value.typeOf)
    }

    fun testEnumLiteralType() {
        assertEquals(ZigEnumLiteralType, resolveValue("@EnumLiteral()"))
    }

    fun testStringLiteral() {
        val text = """
            "foo"
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val pointer = value.typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.ONE, pointer.size)
        val array = pointer.child.requireIs<ZigArrayType>()
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), array.child)
        array.length.requireIs<ZigUnknown>()
        val sentinel = array.sentinel.requireIs<ZigIntValue>()
        assertEquals(ZigComptimeIntType, sentinel.typeOf)
        assertEquals(0, sentinel.value)
    }

    fun testMultilineStringLiteral() {
        val text = """
            \\foo
            \\bar
        """
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val pointer = value.typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.ONE, pointer.size)
        val array = pointer.child.requireIs<ZigArrayType>()
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), array.child)
        array.length.requireIs<ZigUnknown>()
        val sentinel = array.sentinel.requireIs<ZigIntValue>()
        assertEquals(ZigComptimeIntType, sentinel.typeOf)
        assertEquals(0, sentinel.value)
    }
}
