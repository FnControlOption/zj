# ZJ

Adds Zig language support to IntelliJ Platform-based IDEs. Written from scratch.

<a href="https://plugins.jetbrains.com/plugin/35763-zj"><img width="184" height="36" alt="Get from Marketplace" src="https://github.com/user-attachments/assets/440e21b3-ac82-4eb8-9076-b1e2f3e56ab2" /></a>

### Features

-   Block folding, with function bodies and test declarations collapsed by default
-   Semantic highlighting, including names of functions, instance methods, comptime parameters, global variables, local variables, and fields
-   Completions for field names, enum literals, decl literals, error names, and builtin function names
-   Support for generics, including MultiArrayList
-   Exit point highlighting (return, break, continue)
-   Find Usages, with support for [string identifiers](https://ziglang.org/documentation/0.17.0/#String-Identifier-Syntax)
-   Go to Declaration
-   Go to Symbol, implemented with [stub indexes](https://plugins.jetbrains.com/docs/intellij/stub-indexes.html) to make it fast

### Partially implemented

-   Module imports, including `build.zig` imports declared in `build.zig.zon`
-   [Peer type resolution](https://ziglang.org/documentation/master/#Peer-Type-Resolution)
-   Error annotations, such as redeclared variables

### Not (yet) implemented

-   Inlay hints
-   Debugging
-   Comptime interpreter
-   …and much, much more

### AI disclosure

I don't vibecode, but I do use AI to ask questions about the IntelliJ Platform API.

### License

Unless otherwise specified, everything in this repository is licensed under Apache 2.0.

- Zig logo: CC BY-SA 4.0
- Zig language reference: MIT

### Alternatives

- [ZigBrains plugin for IntelliJ Platform](https://plugins.jetbrains.com/plugin/22456-zigbrains): requires ZLS
- [Zig Language plugin for Visual Studio Code](https://marketplace.visualstudio.com/items?itemName=ziglang.vscode-zig): also requires ZLS
