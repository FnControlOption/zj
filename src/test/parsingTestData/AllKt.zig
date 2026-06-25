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
const foo = break;
const foo = break bar;
const foo = break :bar baz;
const foo = continue;
const foo = continue bar;
const foo = continue :bar baz;
const foo = comptime bar;
const foo = nosuspend bar;
const foo = resume bar;
const foo = return;
const foo = return bar;
const foo = {};
test {
    defer foo;
    defer {}
    defer foo: {}
    errdefer foo;
    errdefer {}
    errdefer foo: {}
}
comptime {}
const foo = bar: {};
test {
    {}
    foo: {}
}
const foo = .bar;
const Foo3 = struct {
    const a = b;
    fn c() d {}
};
const foo: Foo3 = .a;
const foo: Foo3 = .c();
const Foo4 = struct {
    foo: Foo3 = .a,
    foo: Foo3 = .c(),
};
const foo5 = bar;
const foo6 = foo5;
fn foo7() Foo {}
test foo7 {}
const foo = Foo{ bar, baz };
const foo = Foo{ .bar = baz };
const foo = .{ bar, baz };
const foo = .{ .bar = baz };
const foo = '\\';
const foo = "\\";
const Foo8 = struct {
    foo: Foo,
};
fn foo8() Foo8 {}
const foo = foo8().foo;
fn Foo9() type {
    return struct {
        const foo = bar;
    };
}
const foo = Foo9().foo;
const foo = Foo8{ .foo = bar };
const foo: Foo8 = .{ .foo = bar };
const foo = bar[ ];
const foo = bar[a];
const foo = bar[a..];
const foo = bar[a..b];
const foo = bar[a..b:c];
const foo = bar.*;
const foo = bar.?;
const Foo = enum {
    foo = bar,
    baz,
};
const Foo = struct(Bar) {};
const Foo = enum(Bar) {};
const Foo = union(enum) {};
const Foo = union(enum(Bar)) {};
const Foo = []Bar;
const Foo = []const Bar;
const Foo = [*c]Bar;
const Foo = [*c]const Bar;
const Foo = [*]Bar;
const Foo = [*:sentinel]const Bar;
const Foo = [length]Bar;
const Foo = [length:sentinel]const Bar;
const Foo = *const volatile allowzero Bar;
const foo = bar catch |baz| qux;
const foo = if (a) |*b| c else |d| e;
const foo = if (bar) break else continue;
test {
    if (a) b;
    if (a) b else c;
    if (a) |*b| {} else |c| {}
}
const foo = switch (bar) { baz => qux };
const foo = bar: switch (a) {
    inline b,
    c,
    => |d, e| f,
    inline else => |g, h| i,
};
const Foo10 = if (foo) struct {
    const bar = baz;
} else struct {
    const bar = baz;
};
const foo = Foo10.bar;
fn foo11() Foo {}
fn foo12() Foo {}
const foo = if (bar) foo11 else foo12;
const Foo13 = struct {
    fn foo(self: @This(), bar: Foo3) {}
};
const foo = Foo13.foo(bar, .a);
const foo13: Foo13 = bar;
const foo = foo13.foo(.a);
fn Foo14() type {
    return struct {
        fn foo(self: @This(), bar: Foo3) {}
    };
}
const foo = Foo14().foo(bar, .a);
const foo14: Foo14() = bar;
const foo = foo14.foo(.a);
const foo15: [bar]Foo8 = baz;
const foo = foo15[bar].foo;
const foo = foo15[bar..baz][qux].foo;
const foo16: [*]Foo8 = bar;
const foo = foo16[bar].foo;
const foo17: *[len]Foo8 = bar;
const foo = foo17[bar].foo;
const foo: *const Foo8 = &.{ .foo = bar };
const foo = [_]Foo8{ .{ .foo = bar } };
const foo: [len]Foo8 = .{ .{ .foo = bar } };
test {
    while (foo) {}
    foo: inline while (a) |*b| : (c) d else e;
}
test {
    for (foo) {}
    foo: inline for (a, b..c,) |d, *e,| f else g;
}
test {
    switch (foo) {
        .a => b = c,
        .d => e += f,
    }
    a = b;
    c += d;
}
test {
    comptime {}
    comptime foo;
    if (foo) bar else comptime baz;
}
test {
    foo: {
        break :foo;
        continue :foo;
    }
    foo: while (bar) {
        break :foo;
        continue :foo;
    }
    foo: for (bar) |baz| {
        break :foo;
        continue :foo;
    }
    foo: switch (bar) {
        else => {
            break :foo;
            continue :foo;
        }
    }
}
fn foo() align(a) addrspace(b) linksection(c) callconv(d) Foo {}
const foo = for (a) |*b| c else d;
const foo = inline for (a) |*b| c else d;
const foo = bar: for (a) |*b| c else d;
const foo = bar: inline for (a) |*b| c else d;
const foo = while (a) |*b| : (c) d else |e| f;
const foo = inline while (a) |*b| : (c) d else |e| f;
const foo = bar: while (a) |*b| : (c) d else |e| f;
const foo = bar: inline while (a) |*b| : (c) d else |e| f;
const foo: for (a) |*b| c else d = bar;
const foo: inline for (a) |*b| c else d = bar;
const foo: bar: for (a) |*b| c else d = baz;
const foo: bar: inline for (a) |*b| c else d = baz;
const foo: while (a) |*b| : (c) d else |e| f = bar;
const foo: inline while (a) |*b| : (c) d else |e| f = bar;
const foo: bar: while (a) |*b| : (c) d else |e| f = baz;
const foo: bar: inline while (a) |*b| : (c) d else |e| f = baz;
const foo =
    \\bar
    \\baz
;
test {
    nosuspend {}
    nosuspend foo;
    suspend {}
    suspend foo;
}
test {
    if (foo) bar, baz = qux;
}
const foo = asm volatile (bar)
const foo = asm volatile (bar
    : [a] "b" (c),
      [d] "e" (-> f)
    : [g] "h" (i),
      [j] "k" (l)
    : m);
fn foo18(T: type) T {}
fn foo19(T: type) ?T {}
const foo = foo18(Foo8).foo;
const foo = foo18(?Foo8).?.foo;
const foo = foo19(Foo8).?.foo;
const Foo20 = struct {
    fn fizz(self: @This(), T: type) T {}
    fn buzz(self: @This(), T: type) ?T {}
};
const foo20: Foo20 = bar;
const foo = foo20.fizz(Foo8).foo;
const foo = foo20.fizz(?Foo8).?.foo;
const foo = foo20.buzz(Foo8).?.foo;
