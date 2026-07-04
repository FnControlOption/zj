package io.github.fncontroloption.zig

import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.assertInstanceOf
import com.intellij.testFramework.requireIs
import io.github.fncontroloption.zig.psi2.ZigContainerField
import io.github.fncontroloption.zig.psi2.ZigContainerKind
import io.github.fncontroloption.zig.psi2.ZigElement
import io.github.fncontroloption.zig.psi2.ZigFile
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
        val field = members[0].requireIs<ZigContainerField>()
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

    fun testErrorUnion() {
        val type = resolveValue("error{Foo}!bool").requireIs<ZigErrorUnionType>()
        assertEquals(ZigBoolType, type.payload)
    }

    fun testEnumFieldAccess() {
        val value = resolveValue("enum { foo, bar }.foo").requireIs<ZigOpaqueValue>()
        val type = value.type.requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.element.containerKind)
    }

    fun testDeclAccessConstWithType() {
        val text = """
            struct {
                const foo: enum { bar, baz } = .bar;
            }.foo
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigOpaqueValue>()
        val type = value.type.requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.element.containerKind)
    }

    fun testDeclAccessVarWithType() {
        val text = """
            struct {
                var foo: enum { bar, baz } = .bar;
            }.foo
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigOpaqueValue>()
        val type = value.type.requireIs<ZigContainerType>()
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
        assertInstanceOf<ZigUnknownType>(resolveValue(text))
    }

    fun testDeclAccessConstWithoutType() {
        val text = """
            struct {
                const foo: enum { bar, baz } = .bar;
                const qux = foo;
            }.qux
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigOpaqueValue>()
        val type = value.type.requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.element.containerKind)
    }

    fun testDeclAccessVarWithoutType() {
        val text = """
            struct {
                const foo: enum { bar, baz } = .bar;
                var qux = foo;
            }.qux
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigOpaqueValue>()
        val type = value.type.requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.element.containerKind)
    }

    fun testFunctionValue() {
        val text = """
            struct {
                fn foo(bar: u42) bool {}
            }.foo
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigOpaqueValue>()
        val type = value.type.requireIs<ZigFunctionType>()
        assertEquals("bool", type.element.returnTypeElement?.text)
    }

    fun testFunctionCall() {
        val text = """
            struct {
                fn foo(bar: u42) bool {}
            }.foo(42)
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigOpaqueValue>()
        assertEquals(ZigBoolType, value.type)
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
        val value = resolveValue(text).requireIs<ZigOpaqueValue>()
        assertEquals(ZigBoolType, value.type)
    }

    fun testTry() {
        val text = "try @as(error{Foo}!bool, true)"
        val value = resolveValue(text).requireIs<ZigOpaqueValue>()
        assertEquals(ZigBoolType, value.type)
    }
}
