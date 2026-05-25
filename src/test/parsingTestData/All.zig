test {}
test "foo" {}
test foo {}
var foo = bar;
const foo = bar;
pub threadlocal var foo: Foo = bar;
pub threadlocal const foo: Foo = bar;
foo: Foo,
comptime foo: Foo = bar,
comptime {}
const foo = a or b or c;
const foo = a or b and c == d & e << f + g * h;
const foo = !~--%&try bar;
const foo = 'a';
const foo = 42;
const foo = unreachable;
const foo = anyframe;
const foo = "foo";
const foo = (bar);
const foo = @foo(bar, baz);
const foo = @foo(bar, baz, );
const foo = comptime bar;
const foo =
    \\Foo
    \\Bar
;
const foo = Foo!Bar;
const foo: Foo align(a) addrspace(b) linksection(c) = d;
