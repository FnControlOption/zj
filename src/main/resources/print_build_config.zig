const std = @import("std");
const json = std.json;
const Allocator = std.mem.Allocator;
const Configuration = std.Build.Configuration;
const Self = @This();

arena: Allocator,
configuration: *const Configuration,
module_set: std.AutoArrayHashMapUnmanaged(Configuration.Module.Index, bool) = .empty,

fn loadStep(self: *Self, step: *const Configuration.Step) Allocator.Error!void {
    if (step.extended.cast(self.configuration, Configuration.Step.Compile)) |compile| {
        try self.loadModule(compile.root_module, !compile.flags3.kind.isTest());
    }
    for (step.deps.slice(self.configuration)) |dep| {
        try self.loadStep(dep.ptr(self.configuration));
    }
}

fn loadModule(self: *Self, module_index: Configuration.Module.Index, is_root_module: bool) Allocator.Error!void {
    const gop = try self.module_set.getOrPut(self.arena, module_index);
    if (gop.found_existing) {
        if (is_root_module) gop.value_ptr.* = true;
        return;
    }
    gop.value_ptr.* = is_root_module;
    const module = module_index.get(self.configuration);
    const import_table = module.import_table.get(self.configuration);
    for (0..import_table.imports.mal.len) |i| {
        const sub_import = import_table.imports.mal.get(i);
        try self.loadModule(sub_import.module, false);
    }
}

fn sortModules(self: *Self) !void {
    const Ctx = struct {
        keys: []Configuration.Module.Index,
        pub fn lessThan(ctx: @This(), a_index: usize, b_index: usize) bool {
            return @backingInt(ctx.keys[a_index]) < @backingInt(ctx.keys[b_index]);
        }
    };
    self.module_set.sort(Ctx{ .keys = self.module_set.keys() });
}

fn modulesAsJsonValue(self: *const Self) !json.Value {
    var root: json.Array = .init(self.arena);
    for (self.module_set.keys(), self.module_set.values()) |module_index, is_root_module| {
        const module = module_index.get(self.configuration);

        const root_source_file = blk: {
            const root_source_index = module.root_source_file.unwrap() orelse break :blk null;
            const root_source_path = root_source_index.get(self.configuration);
            switch (root_source_path) {
                .source_path => |source_path| {
                    var path: []const u8 = source_path.sub_path.slice(self.configuration);
                    if (source_path.owner.get(self.configuration)) |owner| {
                        const root_path = owner.root_path.slice(self.configuration);
                        path = try std.fmt.allocPrint(self.arena, "{s}{c}{s}", .{ root_path, std.fs.path.sep, path });
                    }
                    break :blk path;
                },
                .relative => break :blk null,
                .generated => break :blk null,
            }
        };

        var import_table_object: json.ObjectMap = .empty;
        const import_table = module.import_table.get(self.configuration).imports.mal;
        if (import_table.len > 0) {
            const import_names: []Configuration.String = import_table.items(.name);
            const import_modules: []Configuration.Module.Index = import_table.items(.module);
            for (import_names, import_modules) |name, import_module| {
                const import_key = name.slice(self.configuration);
                const import_value = @backingInt(import_module);
                try import_table_object.put(self.arena, import_key, .{ .integer = import_value });
            }
        }

        var module_object: json.ObjectMap = .empty;
        try module_object.put(self.arena, "index", .{ .integer = @backingInt(module_index) });
        try module_object.put(self.arena, "is_root_module", .{ .bool = is_root_module });
        if (root_source_file) |path| {
            try module_object.put(self.arena, "root_source_file", .{ .string = path });
        } else {
            try module_object.put(self.arena, "root_source_file", .null);
        }
        try module_object.put(self.arena, "import_table", .{ .object = import_table_object });
        try root.append(.{ .object = module_object });
    }
    return .{ .array = root };
}

fn configAsJsonValue(self: *const Self) !json.Value {
    var steps: json.Array = .init(self.arena);
    for (self.configuration.steps) |*step| {
        const name = step.name.slice(self.configuration);
        try steps.append(.{ .string = name });
    }

    var root: json.ObjectMap = .empty;
    try root.put(self.arena, "steps", .{ .array = steps });
    try root.put(self.arena, "modules", try self.modulesAsJsonValue());
    return .{ .object = root };
}

pub fn main(init: std.process.Init) !void {
    const io = init.io;
    const arena = init.arena.allocator();

    const args = try init.minimal.args.toSlice(arena);
    std.debug.assert(args.len == 2);
    const configuration_path = args[1]; // zig build --print-configuration-path

    const configuration = blk: {
        var file = try std.Io.Dir.cwd().openFile(io, configuration_path, .{});
        defer file.close(io);
        break :blk try Configuration.loadFile(arena, io, file);
    };

    var self: Self = .{
        .arena = arena,
        .configuration = &configuration,
    };
    for (configuration.steps) |*step| {
        try self.loadStep(step);
    }
    try self.sortModules();
    const config = try self.configAsJsonValue();

    var stdout_buffer: [1024]u8 = undefined;
    var stdout_writer = std.Io.File.stdout().writer(io, &stdout_buffer);
    const stdout = &stdout_writer.interface;
    try stdout.print("{f}", .{json.fmt(config, .{ .whitespace = .minified })});
    try stdout.flush();
}
