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
