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
