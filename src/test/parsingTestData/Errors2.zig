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
const Foo = union() {};
const Foo = union(enum()) {};
const foo = bar[];
const foo = if () bar;
const foo = while () bar;
const foo = switch () {};
const foo = ();
const foo = asm ();
fn foo() align() addrspace() linksection() callconv() Foo {}
const foo = ;
const Foo = struct { bar: Bar = };
const foo: [:]Foo = bar;
const foo: [*:]Foo = bar;
const foo: [len:]Foo = bar;
const foo = bar[baz.. :];
const foo = bar[baz..qux :];
const foo = if (bar);
const foo = comptime;
const foo = nosuspend;
const foo = resume;
const foo = if (bar) else;
const foo = while (bar) else;
const foo = for (bar) |baz| else;
const foo = .{ .bar = };
test {
    foo = ;
    foo, bar = ;
    foo, var bar = ;
    const foo = ;
}
test {
    foo += ;
}
const foo = switch (bar) {
    baz => qux = ,
};
const foo = switch (bar) {
    baz... => qux,
};
const foo = for (bar..) |baz| qux;
const foo: inline = bar;
const foo: bar: inline = baz;
const foo = inline;
const foo = bar: inline;
test {
    inline;
    foo: inline;
}
const Foo = fn (#) Foo;
const Foo = fn (noalias) Foo;
const foo = .{#};
const foo = .{ bar baz };
const Foo = struct {
    bar: Bar
    const baz = qux;
};
const foo = bar.;
const foo = bar + ;
const foo = try;
test {
    if {}
}
const foo = if bar;
const foo = while bar;
const foo = switch {};
test {
    if (foo);
    while (foo);
    for (foo) |bar|;
}
test {
    if (foo) bar
    while (foo) bar
    for (foo) |bar| baz
}
test {
    foo: Foo align(bar)
    foo: Foo addrspace(bar)
    foo: Foo linksection(bar)
    foo: Foo = bar;
    foo: Foo align(a) addrspace(b) linksection(c) = d;
    foo:
}
const foo = switch (bar) {
    baz => qux
    fizz => buzz
};
const foo = for (a b) |c, d| bar;
const foo = for (bar) baz;
const foo = for (bar) | | baz;
const foo = for (bar) |* | baz;
const foo = for (bar) |*a b| baz;
const foo = for () |bar| baz;
const foo = while (bar) : baz;
const foo = while (bar) : () baz;
const foo: [len]align(bar) const volatile allowzero addrspace(baz) Foo = qux;
const foo = asm (bar : [] "baz" () : [] "qux" () : );
const foo = asm (bar : [a] "b" (c) [d] "e" (f));
const foo = asm (bar : : [a] "b" (c) [d] "e" (f));
