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
