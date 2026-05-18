const std = @import("std");
const parseInt = std.fmt.parseInt;

fn parseIntegers(
    input: []const u8,
    gpa: std.mem.Allocator,
    list: *std.ArrayList(u32),
) !void {
    var it = std.mem.tokenizeAny(u8, input, " ,");
    while (it.next()) |num| {
        const n = try parseInt(u32, num, 10);
        try list.append(gpa, n);
    }
}

test parseIntegers {
    const input = "123 67 89,99";
    const gpa = std.testing.allocator;

    var list: std.ArrayList(u32) = .empty;
    // Ensure the list is freed at scope exit.
    // Try commenting out this line!
    defer list.deinit(gpa);

    try parseIntegers(input, gpa, &list);

    const expected = [_]u32{ 123, 67, 89, 99 };

    for (expected, list.items) |exp, actual| {
        try std.testing.expectEqual(exp, actual);
    }
}
