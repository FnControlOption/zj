const foo =
bar;

const foo =
bar +
baz;

const foo = bar +
baz;

const foo = bar
// Foo
+
baz;

test {
foo +=
bar;

foo, bar =
baz;
}

const foo =
bar ==
baz;

const Foo = struct {
bar: Bar =
baz,
};

const foo = .{
.bar = baz,
};

test {
break :foo bar;
foo catch |bar|
baz;
}

const foo =
// Foo
if (bar) baz
// Bar
;

const foo =
// Foo
if (bar)
// Bar
|baz|
// Baz
qux
// Qux
else
// Quux
|fizz|
// Fizz
buzz
// Buzz
;

const foo = if (bar) {
baz;
} else {
qux;
};

const foo = bar catch
baz;

const foo =
bar catch
baz;

const foo =
bar catch
// Foo
|baz|
qux;

const foo = bar orelse
baz;

const foo =
bar orelse
baz;

const foo =
if (bar)
baz
else
qux;

test {
foo += bar;
}

const foo =
bar and
baz;

const foo = bar and
baz;

const foo =
if (bar and
baz)
qux;

test {
if (foo and
bar)
baz;

if (
// Foo
foo
// Bar
and
// Baz
bar
// Qux
)
baz;
}

const foo = [
bar
]baz;

const foo = bar[
baz
];

const foo = bar[start..end];

const foo = bar[start..end :sentinel];

const foo = bar(
\\Foo
\\Bar
, baz);

const foo = bar(
\\Foo
\\Bar
//
,
baz
//
,
);

const foo = bar(
baz,
qux,
);

const foo =
bar(
baz,
qux,
);

comptime
// Foo
{
comptime
// Bar
{
\\Foo
++
\\Bar
;
}
}

var foo align(
\\Foo
) =
\\Foo
\\Bar
++
\\Baz
;

test {
var foo =
\\Foo
\\Bar
++
\\Baz
;
}

const foo =
bar(
\\Foo
\\Bar
);

const Foo = struct {
bar: Bar =
\\Foo
\\Bar
,
};

const Foo = struct {
bar: Bar =
baz
//
,
};

const foo = bar(baz and
qux);

const foo = bar(
baz and
qux,
);

const foo = bar and
baz;

const foo =
bar and
baz;

const foo =
bar
.baz;

test {
if (foo and
bar)
{}
}

const foo = bar(
baz +
qux,
);

const foo = bar(
\\Foo
,
\\Bar
);
