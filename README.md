# Cang

Cang 是一个使用 Java 编写前端、输出 LLVM IR 的实验性编程语言。它采用接近 C/Java 的语法，提供静态类型、类与继承、数组、字符串、函数对象和基础标准库。

> **重要声明：Cang 是玩具级/教学级语言，不适合用于生产环境。**
>
> 当前实现仍处于实验阶段，可能存在内存安全、异常处理、跨平台、标准库和编译器稳定性问题。请勿将 Cang 用于生产服务、关键业务、金融系统、嵌入式设备或任何安全敏感场景。

## 特性概览

当前已实现或部分实现：

- Java 17 编写的 Lexer、Parser、AST 和 LLVM IR 后端；
- 输出 `.ll` LLVM IR，并通过 clang/gcc 生成本地可执行文件；
- 基本类型：`byte`、`int`、`long`、`float`、`double`、`bool`、`string`；
- 引用字符串类型 `String`，支持双引号和反引号多行字符串；
- 类、单继承、构造参数、实例方法、静态方法和类型 ID 动态分派；
- 泛型类：编译期单态化，支持多类型参数（`Pair<K, V>`）与多实参特化；
- 数组、定长数组、多维数组和 `length()`；
- 数组边界检查；
- `Function<R, ...>` 函数对象；
- 函数与方法重载（按参数个数/类型解析；带默认参数的方法禁止同名重载）；
- lambda 闭包（按值捕获外部变量与 `this`）；
- `this::method`、`object::method`、`Class::staticMethod` 方法引用；
- `Math`、`System`、`Stdout`、`Stderr`、`String`、`List`、`Dict` 等基础标准库；
- `try/catch/finally` 与 `Error` 的第一版实现；
- 线程：`Thread.spawn(fn, ...)`、`thread { }` 语法糖、`new Thread().task(this::run).start()` 对象式写法；
- Boehm GC 自动内存管理（默认）+ `free` 提前释放，`--no-gc` 可切回手动模式；
- Windows、Linux、macOS 目标平台常量和基础交叉编译参数。

## 快速示例

```cang
class Main()

func int add(int a, int b) {
    return a + b
}

int[] values = [1, 2, 3]
Stdout.println(values.length())
Stdout.println(add(values[0], values[1]))
```

函数定义和逻辑代码位于入口 `class` 之后。`class` 之前只允许 `namespace`、`import` 和注释。

## 函数对象示例

```cang
class Main()

func void call(Function<Void, int> f) {
    f(123)
}

call((i) -> void {
    Stdout.println(i)
})
```

lambda 按值捕获外部变量（创建时快照，与 Java 一致）：

```cang
class Main()

int x = 41
Function<Void, int> f = (i) -> void {
    Stdout.println(i + x)   # 按值捕获 x
}
f(1)                       # 42
x = 100
f(2)                       # 43（仍是创建时的 41）
```

## 数组示例

```cang
class Main()

int[10] fixed = []
int[] values = [1, 2, 3]
int[][] matrix = [[1, 2], [3, 4]]

Stdout.println(values.length())
Stdout.println(matrix[0][1])
```

## 异常示例

```cang
import cang/lang/Error

class Main()

try {
    throw new Error("something failed")
} catch (Error e) {
    Stdout.println(e.message)
} finally {
    Stdout.println("finished")
}
```

当前异常系统仍是第一版实现，存在跨函数传播和运行时错误覆盖范围有限等限制。

## 编译环境

建议环境：

- Java 17；
- LLVM clang；
- Windows 下使用 MinGW gcc 进行链接；
- Maven 可选，直接使用 `javac` 也可以编译项目。

## 编译编译器

使用 Maven：

```powershell
mvn compile
```

如果 Maven 不在 PATH 中，可以直接使用 Java 17：

```powershell
javac -encoding UTF-8 -d target/classes (Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object FullName)
```

## 编译 Cang 程序

源码放在 `src/` 下，用命名空间路径引用（自动补 `.cang` 并在 `src/` 下查找）：

```powershell
cang cc/ruok/Main
cang cc/ruok/Main.cang
```

产物统一输出到 `target/`：

```text
src/cc/ruok/Main.cang → target/Main.exe
```

也支持直接传文件路径（兼容旧用法）：

```powershell
cang test/example.cang
```

只生成 LLVM IR：

```powershell
cang test/example.cang --no-link
```

指定目标平台和架构：

```powershell
cang test/example.cang --target windows --arch amd64
cang test/example.cang --target linux --arch amd64
cang test/example.cang --target linux --arch aarch64
cang test/example.cang --target macos --arch amd64
```

`cang` 是仓库根目录的启动脚本（`cang.cmd` / `cang`）；没有该命令时等价于：

```powershell
java -cp target\classes Cang <参数>
```

`--target` 和 `--arch` 会影响 `System.OS_TYPE` 与 `System.ARCH_TYPE`。真正的跨架构链接还需要对应的 clang、gcc 和系统库。

## 内存管理

Cang 默认使用 Boehm GC 自动回收，`free` 可选用于提前释放：

```cang
class Main()

int[] values = [1, 2, 3]
System.gc()        # 手动触发一次回收（可选）

# 也可以提前释放
free values
```

编译开关：

```powershell
Cang app.cang              # 默认 Boehm GC（GC_malloc 静态链接）
Cang app.cang --no-gc      # 切回纯手动 free 模式
```

重要限制：

- GC 模式下 `free` 映射为 `GC_free`，对象别名下提前 `free` 仍可能 use-after-free；
- 字符串字面量来自常量池，不能 `free`；
- 线程与 GC 兼容：Windows 经 `GC_CreateThread` 附着，posix 在线程入口注册线程栈，无需降级；
- Linux GC 库已就位但端到端链接依赖本机 WSL；macOS 目标编译尚未实现。

## 标准库

标准库源码位于：

```text
stdlib/cang/lang/
```

主要文件：

- `Object.cang`：基础对象；
- `Stdout.cang`：输出；
- `String.cang`：字符串对象接口；
- `Math.cang`：数学常量和函数；
- `System.cang`：参数、平台、环境变量、时间和退出；
- `Function.cang`：函数对象类型声明；
- `Void.cang`：无返回值包装类型；
- `Error.cang`：异常对象基础类型；
- `List.cang`：泛型动态数组（参考 ArrayList）；
- `Dict.cang`：泛型 KV 映射（参考 HashMap）；
- `Thread.cang`：线程句柄类型声明。

## 项目结构

```text
Cang/
├── src/main/java/
│   ├── lexer/       # 词法分析
│   ├── parser/      # Parser 和 AST
│   ├── codegen/     # LLVM IR 生成
│   └── util/        # 编译错误和工具类
├── stdlib/
│   └── cang/lang/   # Cang 标准库源码
├── docs/             # 文档（按学习路线拆分：语言一章一文件 + 标准库每类一文件）
├── test/             # 测试和示例（仅本地，不入库）
├── working/          # 多文件工作示例（仅本地，不入库）
└── README.md
```

文档见 **[docs/](docs/README.md)**——语言部分按学习路线（快速开始 → 类型 → 控制流 → 函数 → 类 → 异常/内存/线程），标准库每个类单独一篇，均含要点与示例代码。

## 当前限制

Cang 目前不应被视为稳定语言或生产级编译器，主要限制包括：

- 编译器缺少完整的自动化测试、持续集成和模糊测试体系；
- 错误恢复和诊断能力仍有限；
- 标准库规模较小，缺少成熟的集合、文件、网络、JSON 等 API；
- lambda 仅支持单参数 `Function<Void, T>` 形式（可按值捕获）；
- 异常系统目前只覆盖第一版同函数控制流场景；
- 数组越界和空指针等部分运行时错误仍可能直接终止程序；
- 在别名场景下提前 `free` 存在 use-after-free 风险；
- Unicode、非 ASCII 字符串和跨平台细节仍需持续完善；
- 编译器当前主要通过字符串拼接生成 LLVM IR，鲁棒性和优化能力有限；
- 包管理、LSP、格式化工具、调试器和成熟 IDE 支持尚未完善。

## 适合的用途

Cang 适合用于：

- 学习编译器前端和 LLVM 后端；
- 学习静态类型、类、继承和函数对象；
- 研究 LLVM IR 生成；
- 编写实验性小程序和教学示例；
- 验证编程语言设计想法。

## 不适合的用途

Cang 不适合用于：

- 生产环境服务；
- 关键业务或金融系统；
- 安全敏感程序；
- 长时间运行的高可靠服务；
- 需要稳定 ABI 或完整跨平台保证的项目；
- 依赖成熟生态和长期兼容性的商业项目。

## 许可证

当前项目许可证和贡献规则请以仓库后续发布的正式说明为准。
