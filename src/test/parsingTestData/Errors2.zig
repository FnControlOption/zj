test {
    defer;
    errdefer;
    suspend;
    nosuspend;
}
fn foo() {}
extern fn foo(..., bar: Bar) Foo;
fn foo(bar: Bar baz: Baz) Foo {}
const foo: = bar;
fn foo(bar: ) Foo {}
const Foo = struct { bar: };
const foo: ? = bar;
const foo: anyframe-> = bar;
const foo: * = bar;
const foo: [] = bar;
const foo: Foo! = bar;
const foo: comptime = bar;
const foo = asm volatile (bar : [baz] "qux" (-> ));
const foo: if (bar) else = baz;
const foo: while (bar) else = baz;
const foo: for (bar) |baz| else = qux;
const foo = bar(baz qux);
