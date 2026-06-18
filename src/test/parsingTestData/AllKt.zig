const Foo = struct {
    bar: Bar,
};
const Foo = error{
    Foo,
    Bar,
};
fn foo(bar: Bar, baz: Baz) Foo {}
fn foo(bar: Bar, baz: Baz) !Foo {}
const foo: *Foo = bar;
const foo: *const Foo = bar;
const foo: ?Foo = bar;
const foo: anyframe->Foo = bar;
const foo: []Foo = bar;
const foo: []const Foo = bar;
const foo: fn () Foo = bar;
const foo: fn (Bar, Baz) Foo = bar;
const foo: Foo!Bar = bar
const foo = 'f';
const foo = 42;
const foo = unreachable;
const foo = anyframe;
const foo = "foo";
const foo = bar.baz;
const foo = bar. ;
const foo = bar.?;

/// Foo
///
/// Bar
const foo = bar;

/// Foo
///
/// Bar
fn foo(
    /// Foo
    ///
    /// Bar
    bar: Bar,
) void {}

fn foo() void {
    var bar = baz;
    const qux = bar;
}

test {
    var foo = bar;
    foo = baz;
}

fn foo(comptime T: type) Foo {
    _ = T;
}

fn foo(bar: anytype) Foo {}
extern fn foo(...) Foo;

const foo: comptime Foo = bar;
const foo: (Foo) = bar;
const foo = extern struct {
    bar: Bar,
};
const foo = packed struct {
    bar: Bar,
};

const foo = error.Foo;
const foo = error. ;
const foo: if (a) Foo else Bar = bar;
const foo = a or b or c;
const foo = a or b and c == d & e << f + g * h;
pub const foo = bar;
pub fn foo() Foo {}
foo: Foo,
const Foo2 = struct {
    const foo2 = "foo2";
    fn foo2Fn() void {}
};
const foo = Foo2.foo2;
const foo = Foo2.foo2Fn;
const foo = @import("builtin").zig_version;
const foo = @import("std").ArrayList;
const foo = @This();
const Foo3 = struct {
    const Self = @This();
};
const foo = bar + ;
const foo = !~--%&try bar;
const foo = try ;
test {
    foo();
}
