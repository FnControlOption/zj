const foo0 = 42;
const foo1 = foo0;
const Foo2 = struct {
    const foo3 = foo1;
    const foo4 = foo3;
};
fn foo5(foo6: u42) void {
    _ = foo6;
    const foo7 = 42;
    _ = foo7;
    const foo8, var foo9 = .{ 42, 42 };
    _ = .{ foo8, foo9 };
}
const foo10 = foo5;
const foo11 = if (undefined) |*foo12| foo12 else |foo13| foo13;
const foo14 = while (undefined) |*foo15| foo15 else |foo16| foo16;
const foo17 = for (undefined, undefined) |*foo18, foo19| .{ foo18, foo19 };
const foo20 = switch (undefined) {
    undefined => |*foo21, foo22| .{ foo21, foo22 },
};
const foo23 = undefined catch |foo24| foo24;
test foo5 {}
test {
    foo25: {
        break :foo25;
        continue :foo25;
    }
    foo26: while (undefined) {
        break :foo26;
        continue :foo26;
    }
    foo27: for (undefined) |_| {
        break :foo27;
        continue :foo27;
    }
    foo28: switch (undefined) {
        undefined => {
            break :foo28;
            continue :foo28;
        }
    }
}
