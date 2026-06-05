const Foo = struct {
    bar: Bar,
};
const Foo = error{
    Foo,
    Bar,
};
fn foo(bar: Bar, baz: Baz) Foo {}
fn foo(bar: Bar, baz: Baz) !Foo {}
