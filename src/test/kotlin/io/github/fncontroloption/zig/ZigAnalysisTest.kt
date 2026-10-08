package io.github.fncontroloption.zig

import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.requireIs
import io.github.fncontroloption.zig.psi2.ZigContainerFieldImpl
import io.github.fncontroloption.zig.psi2.ZigContainerKind
import io.github.fncontroloption.zig.psi2.ZigExpression
import io.github.fncontroloption.zig.psi2.ZigFile
import io.github.fncontroloption.zig.psi2.ZigMutability
import io.github.fncontroloption.zig.psi2.ZigPointerSize
import io.github.fncontroloption.zig.psi2.ZigVarDecl

class ZigAnalysisTest : BasePlatformTestCase() {
    fun createFile(text: String): ZigFile {
        val fileFactory = PsiFileFactory.getInstance(project)
        val file = fileFactory.createFileFromText("dummy.zig", ZigLanguage, text)
        return file as ZigFile
    }

    fun createExpression(text: String): ZigExpression {
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
        assertSize(1, values)
        return values.first()
    }

    fun testContainer() {
        val type = resolveValue("struct { foo: u42 }").requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.STRUCT, type.element.containerKind)
        val members = type.element.members.toList()
        assertSize(1, members)
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
        assertNull(type.sentinel)
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
        assertNull(type.sentinel)
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
        assertNull(type.sentinel)
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
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigUsizeType, value.typeOf)
    }

    fun testOrelse() {
        val text = "@as(?usize, undefined) orelse undefined"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        assertEquals(ZigUsizeType, value.typeOf)
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
        val value = resolveValue(text).requireIs<ZigStringLiteralValue>()
        assertEquals("foo", value.value)
        val pointer = value.typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.ONE, pointer.size)
        val array = pointer.child.requireIs<ZigArrayType>()
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), array.child)
        val length = array.length.requireIs<ZigIntValue>()
        assertEquals(ZigUsizeType, length.typeOf)
        assertEquals(3, length.value)
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

    fun testPeerTypesIf() {
        val text = "if (undefined) error.Foo else error.Bar"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigErrorSetType>()
        assertEquals(setOf("Foo", "Bar"), type.names)
    }

    fun testPeerTypesCatch() {
        val text = "@as(error{Foo}!error{Bar}, undefined) catch error.Baz"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigErrorSetType>()
        assertEquals(setOf("Bar", "Baz"), type.names)
    }

    fun testPeerTypesOrelse() {
        val text = "@as(?error{Foo}, undefined) orelse error.Bar"
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigErrorSetType>()
        assertEquals(setOf("Foo", "Bar"), type.names)
    }

    fun testPeerTypesTypeOf() {
        val text = "@TypeOf(error.Foo, error.Bar, error.Baz)"
        val type = resolveValue(text).requireIs<ZigErrorSetType>()
        assertEquals(setOf("Foo", "Bar", "Baz"), type.names)
    }

    fun testPeerTypesBlock() {
        val text = """
            blk: {
                if (undefined) break :blk error.Foo;
                if (undefined) break :blk error.Bar;
                break :blk error.Baz;
            }
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigErrorSetType>()
        assertEquals(setOf("Foo", "Bar", "Baz"), type.names)
    }

    fun testPeerTypesSwitch() {
        val text = """
            blk: switch (undefined) {
                undefined => error.Foo,
                undefined => error.Bar,
                undefined => break :blk error.Baz,
            }
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigErrorSetType>()
        assertEquals(setOf("Foo", "Bar", "Baz"), type.names)
    }

    fun testPeerTypesFor() {
        val text = """
            blk: for (undefined) |_| {
                if (undefined) break error.Foo;
                if (undefined) break :blk error.Bar;
            } else error.Baz
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigErrorSetType>()
        assertEquals(setOf("Foo", "Bar", "Baz"), type.names)
    }

    fun testPeerTypesTaggedUnionFields() {
        val text = """
            switch (@as(union(enum) {
                foo: error{Foo},
                bar: error{Bar},
                baz: error{Baz},
            }, undefined)) {
                .foo, .bar, .baz => |e| e,
            }
        """.trimIndent()
        val value = resolveValue(text).requireIs<ZigUnknown>()
        val type = value.typeOf.requireIs<ZigErrorSetType>()
        assertEquals(setOf("Foo", "Bar", "Baz"), type.names)
    }

    fun testPeerTypesBool() {
        val text = "@TypeOf(true, undefined)"
        assertEquals(ZigBoolType, resolveValue(text))
    }

    fun testPeerTypesNull() {
        val text = "@TypeOf(null, undefined)"
        assertEquals(ZigNullType, resolveValue(text))
    }

    fun testPeerTypesOptional() {
        val text = "@TypeOf(null, true)"
        val type = resolveValue(text).requireIs<ZigOptionalType>()
        assertEquals(ZigBoolType, type.child)
    }

    fun testPeerTypesComptimeInt() {
        val text = "@TypeOf(42, undefined)"
        assertEquals(ZigComptimeIntType, resolveValue(text))
    }

    fun testPeerTypesComptimeFloat() {
        val text = "@TypeOf(42, 3.14)"
        assertEquals(ZigComptimeFloatType, resolveValue(text))
    }

    fun testPeerTypesErrorUnion() {
        val text = "@TypeOf(error.Foo, @as(error{Bar}!bool, true))"
        val type = resolveValue(text).requireIs<ZigErrorUnionType>()
        assertEquals(ZigBoolType, type.payload)
        val errorSet = type.errorSet.requireIs<ZigErrorSetType>()
        assertEquals(setOf("Foo", "Bar"), errorSet.names)
    }

    fun testPeerTypesEnumLiteral() {
        val text = "@TypeOf(.foo, undefined)"
        assertEquals(ZigEnumLiteralType, resolveValue(text))
    }

    fun testPeerTypesEnum() {
        val text = """
            blk: {
                const E = enum { foo, bar };
                break :blk @TypeOf(E.foo, undefined);
            }
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.containerKind)
    }

    fun testPeerTypesUnion() {
        val text = """
            blk: {
                const U = union(enum) { foo, bar: bool };
                break :blk @TypeOf(@as(U, undefined), undefined);
            }
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.UNION, type.containerKind)
    }

    fun testPeerTypesEnumLiteralAndLiteral() {
        val text = "@TypeOf(.foo, .bar, undefined)"
        assertEquals(ZigEnumLiteralType, resolveValue(text))
    }

    fun testPeerTypesEnumLiteralAndEnum() {
        val text = """
            blk: {
                const E = enum { foo, bar };
                break :blk @TypeOf(.foo, E.bar);
            }
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.containerKind)
    }

    fun testPeerTypesEnumLiteralAndUnion() {
        val text = """
            blk: {
                const U = union(enum) { foo, bar: bool };
                break :blk @TypeOf(.foo, @as(U, undefined));
            }
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.UNION, type.containerKind)
    }

    fun testPeerTypesEnumAndLiteral() {
        val text = """
            blk: {
                const E = enum { foo, bar };
                break :blk @TypeOf(E.foo, .bar);
            }
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.containerKind)
    }

    fun testPeerTypesEnumAndEnum() {
        val text = """
            blk: {
                const E = enum { foo, bar };
                break :blk @TypeOf(E.foo, E.bar, undefined);
            }
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.ENUM, type.containerKind)
    }

    fun testPeerTypesEnumAndUnion() {
        val text = """
            blk: {
                const E = enum { foo, bar };
                const U = union(E) { foo, bar: bool };
                break :blk @TypeOf(E.foo, @as(U, undefined));
            }
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.UNION, type.containerKind)
    }

    fun testPeerTypesUnionAndLiteral() {
        val text = """
            blk: {
                const U = union(enum) { foo, bar: bool };
                break :blk @TypeOf(@as(U, undefined), .foo);
            }
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.UNION, type.containerKind)
    }

    fun testPeerTypesUnionAndEnum() {
        val text = """
            blk: {
                const E = enum { foo, bar };
                const U = union(E) { foo, bar: bool };
                break :blk @TypeOf(@as(U, undefined), E.foo);
            }
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.UNION, type.containerKind)
    }

    fun testPeerTypesUnionAndUnion() {
        val text = """
            blk: {
                const U = union(enum) { foo, bar: bool };
                break :blk @TypeOf(@as(U, undefined), @as(U, undefined), undefined);
            }
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigContainerType>()
        assertEquals(ZigContainerKind.UNION, type.containerKind)
    }

    fun testPeerTypesUnsignedFixedInt() {
        val text = "@TypeOf(1, @as(u8, 2), @as(u42, 3))"
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 42), resolveValue(text))
    }

    fun testPeerTypesSignedFixedInt() {
        val text = "@TypeOf(1, @as(i8, 2), @as(i42, 3))"
        assertEquals(ZigFixedIntType(ZigSignedness.SIGNED, 42), resolveValue(text))
    }

    fun testPeerTypesUnsignedAndSignedFixedInt() {
        val text = "@TypeOf(1, @as(u8, 2), @as(i42, 3))"
        assertEquals(ZigFixedIntType(ZigSignedness.SIGNED, 42), resolveValue(text))
    }

    fun testPeerTypesFixedFloat() {
        val text = "@TypeOf(1, @as(u8, 2), @as(f16, 3), @as(f80, 4), 5.0)"
        assertEquals(ZigFixedFloatType(80), resolveValue(text))
    }

    fun testPeerTypesTuple() {
        val text = """
            @TypeOf(
                @as(struct { u8, f80 }, undefined),
                @as(struct { i42, f16 }, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigTupleType>()
        assertSize(2, type.fields)
        assertEquals(ZigFixedIntType(ZigSignedness.SIGNED, 42), type.fields[0])
        assertEquals(ZigFixedFloatType(80), type.fields[1])
    }

    fun testPeerTypesCPointer() {
        val text = """
            @TypeOf(
                @as(*u8, undefined),
                @as([*:0]u8, undefined),
                @as([*c]u8, undefined),
                @as(?[*:0]u8, undefined),
                @as(?*u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.C, type.size)
        assertNull(type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.ONE
    // peerSize = ZigPointerSize.ONE
    fun testPeerTypesArrayAndArrayPointers() {
        val text = """
            @TypeOf(
                @as(*[2:0]u8, undefined),
                @as(*[3:0]u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.SLICE, type.size)
        assertEquals(ZigIntValue(ZigComptimeIntType, 0), type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.ONE
    // peerSize = ZigPointerSize.ONE
    fun testPeerTypesArrayAndTuplePointers() {
        val text = """
            @TypeOf(
                @as(*[2:0]u8, undefined),
                @as(*struct { u8, u8 }, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.ONE, type.size)
        assertNull(type.sentinel)
        val array = type.child.requireIs<ZigArrayType>()
        assertEquals(ZigIntValue(ZigUsizeType, 2), array.length)
        assertNull(array.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), array.child)
    }

    // ptrSize = ZigPointerSize.ONE
    // peerSize = ZigPointerSize.ONE
    fun testPeerTypesArrayAndEmptyTuplePointers() {
        val text = """
            @TypeOf(
                @as(*[2:0]u8, undefined),
                &.{},
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.SLICE, type.size)
        assertNull(type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.ONE
    // peerSize = ZigPointerSize.ONE
    fun testPeerTypesEmptyTupleAndArrayPointers() {
        val text = """
            @TypeOf(
                &.{},
                @as(*[2:0]u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.SLICE, type.size)
        assertNull(type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.MANY
    // peerSize = ZigPointerSize.ONE
    fun testPeerTypesManyItemAndArrayPointers() {
        val text = """
            @TypeOf(
                @as([*:0]u8, undefined),
                @as(*[2:0]u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.MANY, type.size)
        assertEquals(ZigIntValue(ZigComptimeIntType, 0), type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.MANY
    // peerSize = ZigPointerSize.ONE
    fun testPeerTypesManyItemAndEmptyTuplePointers() {
        val text = """
            @TypeOf(
                @as([*:0]u8, undefined),
                &.{},
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.MANY, type.size)
        assertNull(type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.SLICE
    // peerSize = ZigPointerSize.ONE
    fun testPeerTypesSliceAndArrayPointer() {
        val text = """
            @TypeOf(
                @as([:0]u8, undefined),
                @as(*[2:0]u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.SLICE, type.size)
        assertEquals(ZigIntValue(ZigComptimeIntType, 0), type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.SLICE
    // peerSize = ZigPointerSize.ONE
    fun testPeerTypesSliceAndEmptyTuplePointer() {
        val text = """
            @TypeOf(
                @as([:0]u8, undefined),
                &.{},
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.SLICE, type.size)
        assertNull(type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.ONE
    // peerSize = ZigPointerSize.MANY
    fun testPeerTypesArrayAndManyItemPointers() {
        val text = """
            @TypeOf(
                @as(*[2:0]u8, undefined),
                @as([*:0]u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.MANY, type.size)
        assertEquals(ZigIntValue(ZigComptimeIntType, 0), type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.ONE
    // peerSize = ZigPointerSize.MANY
    fun testPeerTypesEmptyTupleAndManyItemPointers() {
        val text = """
            @TypeOf(
                &.{},
                @as([*:0]u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.MANY, type.size)
        assertNull(type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.MANY
    // peerSize = ZigPointerSize.MANY
    fun testPeerTypesManyItemAndManyItemPointers() {
        val text = """
            @TypeOf(
                @as([*:0]u8, undefined),
                @as([*:1]u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.MANY, type.size)
        assertNull(type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.SLICE
    // peerSize = ZigPointerSize.MANY
    fun testPeerTypesSliceAndManyItemPointer() {
        val text = """
            @TypeOf(
                @as([:0]u8, undefined),
                @as([*:0]u8, undefined),
            )
        """.trimIndent()
        assertSize(2, resolveValues(text))
    }

    // ptrSize = ZigPointerSize.SLICE
    // peerSize = ZigPointerSize.MANY
    fun testPeerTypesArrayAndArrayAndManyItemPointers() {
        val text = """
            @TypeOf(
                @as(*[2:0]u8, undefined),
                @as(*[3:0]u8, undefined),
                @as([*:0]u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.MANY, type.size)
        assertEquals(ZigIntValue(ZigComptimeIntType, 0), type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.ONE
    // peerSize = ZigPointerSize.SLICE
    fun testPeerTypesArrayPointerAndSlice() {
        val text = """
            @TypeOf(
                @as(*[2:0]u8, undefined),
                @as([:0]u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.SLICE, type.size)
        assertEquals(ZigIntValue(ZigComptimeIntType, 0), type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.ONE
    // peerSize = ZigPointerSize.SLICE
    fun testPeerTypesEmptyTuplePointerAndSlice() {
        val text = """
            @TypeOf(
                &.{},
                @as([:0]u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.SLICE, type.size)
        assertNull(type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    // ptrSize = ZigPointerSize.MANY
    // peerSize = ZigPointerSize.SLICE
    fun testPeerTypesManyItemPointerAndSlice() {
        val text = """
            @TypeOf(
                @as([*:0]u8, undefined),
                @as([:0]u8, undefined),
            )
        """.trimIndent()
        assertSize(2, resolveValues(text))
    }

    // ptrSize = ZigPointerSize.SLICE
    // peerSize = ZigPointerSize.SLICE
    fun testPeerTypesSliceAndSlice() {
        val text = """
            @TypeOf(
                @as([:0]u8, undefined),
                @as([:1]u8, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.SLICE, type.size)
        assertNull(type.sentinel)
        assertEquals(ZigFixedIntType(ZigSignedness.UNSIGNED, 8), type.child)
    }

    fun testPeerTypesEmptyTupleAndNoreturnArrayPointers() {
        val text = "@TypeOf(&.{}, @as(*[0]noreturn, undefined))"
        assertSize(2, resolveValues(text))
    }

    fun testPeerTypesConstPointer() {
        val text = """
            @TypeOf(
                @as(*usize, undefined),
                @as(*const usize, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.ONE, type.size)
        assertNull(type.sentinel)
        assertEquals(ZigMutability.CONST, type.mutability)
        assertEquals(ZigUsizeType, type.child)
    }

    fun testPeerTypesConstCPointer() {
        val text = """
            @TypeOf(
                @as([*c]usize, undefined),
                @as([*c]const usize, undefined),
            )
        """.trimIndent()
        val type = resolveValue(text).requireIs<ZigPointerType>()
        assertEquals(ZigPointerSize.C, type.size)
        assertNull(type.sentinel)
        assertEquals(ZigMutability.CONST, type.mutability)
        assertEquals(ZigUsizeType, type.child)
    }

    fun testMutabilityOfIdentifierVarReference() {
        val text = """
            blk: {
                var foo: usize = 42;
                break :blk &foo;
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfIdentifierConstReference() {
        val text = """
            blk: {
                const foo: usize = 42;
                break :blk &foo;
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfIdentifierFnReference() {
        val text = """
            struct {
                fn foo() void {}
                const bar = &foo;
            }.bar;
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfGroupedExpressionVar() {
        val text = """
            blk: {
                var foo: usize = 42;
                break :blk &(foo);
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfGroupedExpressionConst() {
        val text = """
            blk: {
                const foo: usize = 42;
                break :blk &(foo);
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfVarDeclAccess() {
        val text = """
            blk: {
                const foo = struct {
                    var bar: usize = 42;
                };
                break :blk &foo.bar;
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfConstDeclAccess() {
        val text = """
            blk: {
                const foo = struct {
                    const bar: usize = 42;
                };
                break :blk &foo.bar;
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfFnDeclAccess() {
        val text = """
            blk: {
                const foo = struct {
                    fn bar() void {}
                };
                break :blk &foo.bar;
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfVarFieldAccess() {
        val text = """
            blk: {
                const Foo = struct {
                    bar: usize = 42,
                };
                var foo: Foo = .{};
                break :blk &foo.bar;
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfConstFieldAccess() {
        val text = """
            blk: {
                const Foo = struct {
                    bar: usize = 42,
                };
                const foo: Foo = .{};
                break :blk &foo.bar;
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfVarFieldViaBuiltin() {
        val text = """
            blk: {
                const Foo = struct {
                    bar: usize = 42,
                };
                var foo: Foo = .{};
                break :blk &@field(foo, "bar");
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfConstFieldViaBuiltin() {
        val text = """
            blk: {
                const Foo = struct {
                    bar: usize = 42,
                };
                const foo: Foo = .{};
                break :blk &@field(foo, "bar");
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfVarArrayAccess() {
        val text = """
            blk: {
                var foo: [2]usize = .{ 6, 7 };
                break :blk &foo[1];
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfConstArrayAccess() {
        val text = """
            blk: {
                const foo: [2]usize = .{ 6, 7 };
                break :blk &foo[1];
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfVarTupleAccess() {
        val text = """
            blk: {
                var foo: struct { isize, usize } = .{ 6, 7 };
                break :blk &foo[1];
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfConstTupleAccess() {
        val text = """
            blk: {
                const foo: struct { isize, usize } = .{ 6, 7 };
                break :blk &foo[1];
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfVarPointerArrayAccess() {
        val text = """
            blk: {
                const foo: *[2]usize = undefined;
                break :blk &foo[1];
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfConstPointerArrayAccess() {
        val text = """
            blk: {
                const foo: *const [2]usize = undefined;
                break :blk &foo[1];
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfVarPointerTupleAccess() {
        val text = """
            blk: {
                const foo: *struct { isize, usize } = undefined;
                break :blk &foo[1];
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfConstPointerTupleAccess() {
        val text = """
            blk: {
                const foo: *const struct { isize, usize } = undefined;
                break :blk &foo[1];
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfVarSliceAccess() {
        val text = """
            blk: {
                const foo: []usize = undefined;
                break :blk &foo[1];
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfConstSliceAccess() {
        val text = """
            blk: {
                const foo: []const usize = undefined;
                break :blk &foo[1];
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfUnwrapOptionalVar() {
        val text = """
            blk: {
                var foo: ?usize = 42;
                break :blk &foo.?;
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfUnwrapOptionalConst() {
        val text = """
            blk: {
                const foo: ?usize = 42;
                break :blk &foo.?;
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfTryVar() {
        val text = """
            blk: {
                var foo: error{Foo}!usize = 42;
                break :blk &(try foo);
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfTryConst() {
        val text = """
            blk: {
                const foo: error{Foo}!usize = 42;
                break :blk &(try foo);
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }

    fun testMutabilityOfVarPointerDeref() {
        val text = """
            blk: {
                const foo: *usize = undefined;
                break :blk &foo.*;
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.VAR, type.mutability)
    }

    fun testMutabilityOfConstPointerDeref() {
        val text = """
            blk: {
                const foo: *const usize = undefined;
                break :blk &foo.*;
            }
        """.trimIndent()
        val type = resolveValue(text).typeOf.requireIs<ZigPointerType>()
        assertEquals(ZigMutability.CONST, type.mutability)
    }
}
