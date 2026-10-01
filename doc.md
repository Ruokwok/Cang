# Cang 语言开发文档

Cang 是一门使用 Java 编写前端、输出 LLVM IR 的编译型语言。它采用接近 Java/C 的语法，同时提供顶层逻辑代码、类、继承、静态方法、数组、函数对象和 Boehm GC 自动内存管理（可选 `--no-gc` 手动模式）。

本文档面向 Cang 初学者，也可作为当前编译器实现的语法和标准库参考。

## Thread<T>

`Thread<T>` 是编译器内建的线程句柄，使用 `Thread.spawn(function, args...)` 创建，并通过 `thread.join()` 等待并取得 `T`。

- 入口必须是顶层、无捕获的普通函数标识符；
- 结果类型 `T`：`void`、`int`、`long`、`float`、`double`、`bool`、`byte`、`String`、`str`；
- 参数为同范围的值类型，个数任意；对象、数组、`Function`、lambda、方法引用会被明确拒绝（禁止跨线程共享可变状态）；
- Windows：GC 模式经 `GC_CreateThread` 创建（线程由 Boehm 附着），`--no-gc` 模式用原生 `CreateThread`；`join` 使用 `WaitForSingleObject`；
- Linux/macOS：`pthread_create`/`pthread_join`，GC 模式在线程入口调用 `GC_register_my_thread` 注册线程栈；
- 与默认 Boehm GC 兼容，无需降级；
- `join()` 只能调用一次，重复调用立即报错退出；线程创建失败同样报错退出；
- 线程内异常不跨线程传播：未捕获异常会终止整个进程（与主流程一致）；线程函数内部的 `try/catch` 正常工作。

验证状态：`test/thread_demo.cang`、`thread_types.cang`、`thread_gc.cang` 在 Windows/MinGW + GC 下端到端通过（含 `Thread<Void>/Thread<String>/Thread<double>`、String 参数、线程内 GC 分配与 `System.gc()`）；Linux/macOS 已验证 LLVM IR 与目标文件编译，实际链接运行需对应平台环境。

> 文档基于当前编译器实现。部分高级功能仍在开发中，文末列出了已知限制。

---

## 1. 快速开始

### 1.1 一个最小程序

```cang
class Main()

Stdout.println("Hello, Cang!")
```

Cang 文件通常使用 `.cang` 扩展名。

Cang 的入口模型是：文件中的第一个 `class` 作为入口类，类声明之后的普通语句作为程序逻辑，编译器会将它们包装到生成的 `main` 函数中。

### 1.2 编译器构建

项目需要 Java 17。使用 Maven 时：

```powershell
mvn compile
```

如果本机没有 Maven，也可以直接使用 `javac` 编译全部源码：

```powershell
javac -encoding UTF-8 -d target/classes (Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object FullName)
```

### 1.3 编译 Cang 程序

源码按命名空间放在 `src/` 下，直接用命名空间路径引用（自动补 `.cang` 并在 `src/` 下查找）：

```powershell
cang cc/ruok/Main
cang cc/ruok/Main.cang
```

等价于：

```powershell
java -cp target\classes Cang cc/ruok/Main
```

产物统一输出到 `target/`：

```text
src/cc/ruok/Main.cang → target/Main.ll → target/Main.o → target/Main.exe
```

也支持直接传文件路径（兼容旧用法）：

```powershell
cang test/example.cang
```

找不到源码时会打印搜索过的候选路径。

只生成 LLVM IR、不进行本地链接：

```powershell
cang test/example.cang --no-link
```

指定目标平台：

```powershell
java -cp target/classes Cang test/hello.cang --target windows
java -cp target/classes Cang test/hello.cang --target linux
java -cp target/classes Cang test/hello.cang --target macos
```

指定目标架构：

```powershell
java -cp target/classes Cang test/hello.cang --target linux --arch amd64
java -cp target/classes Cang test/hello.cang --target linux --arch aarch64
```

`--target` 和 `--arch` 会影响 `System.OS_TYPE` 与 `System.ARCH_TYPE` 的编译期值。真正的跨架构链接还需要对应的 clang/gcc 和系统库。

内存管理默认使用 Boehm GC 自动回收（静态链接，堆分配走 `GC_malloc`）：

```powershell
java -cp target/classes Cang test/hello.cang                # 默认开启 GC
java -cp target/classes Cang test/hello.cang --no-gc        # 切回手动 free 模式
java -cp target/classes Cang test/hello.cang --gc --gc-lib "D:\path\to\gc\lib"   # 显式指定库目录
```

手动控制回收时机：

```cang
System.gc()    # 立即触发一次垃圾回收（--no-gc 模式下为空操作）
```

GC 静态库按目标平台查找：

```text
runtime/boehm/<target>-<arch>/lib/libgc.a
```

已就位：`windows-amd64`（本机构建）与 `linux-amd64`（Ubuntu libgc-dev 取回）。macOS 目标编译尚未实现。

注意：`Thread.spawn` 与默认 Boehm GC 兼容（Windows 经 `GC_CreateThread` 附着线程，posix 在线程入口注册线程栈），线程程序不需要特殊开关；对象和数组不允许作为线程参数。

---

## 2. 文件结构与执行模型

Cang 使用“入口 class 在前，函数和逻辑代码在后”的文件结构约定：

```cang
# class 之前只允许 namespace、import 和注释
class Main()

# class 之后定义函数/方法
func int add(int a, int b) {
    return a + b
}

# class 之后编写逻辑代码
Stdout.println(add(1, 2))
```

### 2.1 class 之前的内容

`class` 之前只能出现：

- `namespace` 声明；
- `import` 声明；
- 单行或多行注释；
- 空白行。

函数定义、变量声明和执行逻辑都应放在入口 `class` 之后。

### 2.2 语句分号

普通语句末尾分号可省略：

```cang
int x = 1
Stdout.println(x)
```

也可以使用分号：

```cang
int x = 1;
Stdout.println(x);
```

`for` 循环的三个组成部分之间必须使用分号：

```cang
for (int i = 0; i < 10; i++) {
    Stdout.println(i)
}
```

### 2.3 注释

单行注释：

```cang
# 这是单行注释
```

多行注释：

```cang
#*
这是多行注释。
可以包含多行内容。
*#
```

---

## 3. 基本类型

当前支持的基本类型：

| 类型 | 说明 |
|---|---|
| `byte` | 8 位整数 |
| `int` | 32 位整数 |
| `long` | 64 位整数 |
| `float` | 单精度浮点数 |
| `double` | 双精度浮点数 |
| `bool` | 布尔值 |
| `string` | Cang 的字符串基本类型，内部语义为 `str` |
| `String` | 引用类型字符串对象 |
| `var` | 根据初始化表达式推导类型 |
| `Void` | 函数类型中的无返回值包装名称 |

### 3.1 整数

```cang
byte b = 127
int i = 123
long l = 123456789L
```

数字可以使用下划线分隔：

```cang
int million = 1_000_000
```

支持十六进制和二进制：

```cang
int hex = 0xFF
int binary = 0b1010
```

### 3.2 浮点数

```cang
float f = 1.5f
double d = 3.1415926
```

常用后缀：

- `f/F`：float
- `d/D`：double
- `l/L`：long
- `i/I`：int
- `b/B`：byte

### 3.3 布尔值

```cang
bool enabled = true
bool finished = false
```

### 3.4 null

引用类型可以使用 `null`：

```cang
String text = null
Point point = null
```

`var` 无法从 `null` 推导类型：

```cang
var value = null       # 编译错误
String value = null    # 合法
```

---

## 4. string 与 String

Cang 区分基本字符串类型和引用字符串类型。

### 4.1 string

`string` 是基本字符串类型，使用单引号：

```cang
string name = 'Cang'
var text = 'hello'
```

特点：

- 单引号字面量类型为 `string`/`str`；
- 没有对象方法；
- 适合短文本和基础字符串值；
- 不允许直接调用 `length()`、`substring()` 等对象方法。

### 4.2 String

`String` 是 `cang/lang/String` 引用类型对象，双引号和反引号字面量属于 `String`：

```cang
String name = "Cang"
String paragraph = `第一行
第二行`
```

反引号适合多行字符串。

### 4.3 显式转换和装箱

```cang
string raw = 'hello'
String boxed = new String(raw)

string numberText = string(123)
String numberObject = String(123)
```

`string` 与 `String` 不进行隐式互转。

### 4.4 字符串比较

两种字符串都支持 `==` 和 `!=`：

```cang
string a = 'hello'
String b = "hello"

bool same = a == b
bool different = a != "world"
```

底层比较使用字符串内容比较。

### 4.5 字符串常量池

字符串字面量由编译器放入 LLVM 全局常量区，相同文本可以复用常量。字符串是不可变的，拼接会生成新的字符串缓冲区。

---

## 5. 变量与类型推导

显式类型声明：

```cang
int count = 10
String message = "ok"
```

使用 `var`：

```cang
var count = 10
var message = "hello"
var raw = 'hello'
```

`var` 只能根据初始化表达式推导：

```cang
var x = 1              # int
var y = 1.5            # double
var z = true           # bool
var s = "text"        # String
var r = 'text'         # str
```

---

## 6. 运算符

### 6.1 算术运算

```cang
int a = 10 + 3
int b = 10 - 3
int c = 10 * 3
int d = 10 / 3
int e = 10 % 3
double f = 10 // 3       # 浮点除法
```

### 6.2 比较运算

```cang
x == y
x != y
x < y
x <= y
x > y
x >= y
```

字符串只支持可靠的 `==` 和 `!=` 内容比较。

### 6.3 逻辑运算

```cang
bool result = a && b
bool other = a || b
bool inverse = !result
```

### 6.4 三目表达式

```cang
int max = a > b ? a : b
```

---

## 7. 控制流

### 7.1 if / else

```cang
if (score >= 60) {
    Stdout.println("pass")
} else {
    Stdout.println("fail")
}
```

### 7.2 while

```cang
int i = 0
while (i < 5) {
    Stdout.println(i)
    i++
}
```

### 7.3 for

```cang
for (int i = 0; i < 5; i++) {
    Stdout.println(i)
}
```

### 7.4 break / continue

```cang
for (int i = 0; i < 10; i++) {
    if (i == 3) continue
    if (i == 8) break
    Stdout.println(i)
}
```

### 7.5 switch

```cang
switch (value) {
    case 1:
        Stdout.println("one")
    case 2:
        Stdout.println("two")
    default:
        Stdout.println("other")
}
```

---

## 8. 函数

### 8.1 定义函数

函数定义放在入口 `class` 之后：

```cang
class Main()

func int add(int a, int b) {
    return a + b
}

Stdout.println(add(2, 3))
```

### 8.2 void 函数

```cang
func void greet(String name) {
    Stdout.println(name)
}
```

### 8.3 默认参数

```cang
func int add(int a, int b = 1) {
    return a + b
}

add(3)
add(3, 4)
```

### 8.4 return

非 `void` 函数必须返回正确类型的值：

```cang
func int square(int x) {
    return x * x
}
```

编译器会检查返回值类型和缺失 return。

---

## 9. Function 函数对象

Cang 支持函数作为参数传递。

### 9.1 Function 类型

```cang
Function<返回类型, 参数类型1, 参数类型2>
```

例如：

```cang
Function<int, int, int>
```

表示接收两个 `int`，返回 `int` 的函数。

无返回值使用 `Void`：

```cang
Function<Void, int>
```

### 9.2 方法作为参数

函数定义放在 `class` 之后，因此它们属于当前类的方法。可以使用对象方法引用传递：

```cang
class Main()

func int inc(int x) {
    return x + 1
}

func int apply(Function<int, int> f, int value) {
    return f(value)
}

var main = new Main()
Stdout.println(main.apply(main::inc, 41))
```

### 9.3 无捕获 lambda

```cang
class Main()

func void call(Function<Void, int> f) {
    f(123)
}

call((i) -> void {
    Stdout.println(i)
})
```

当前 lambda 语法要求：

- 一个参数；
- 显式写返回类型 `void`；
- 必须出现在已知 `Function<...>` 类型上下文中；
- 不允许捕获外部变量。

以下写法会报错：

```cang
func void bad() {
    int x = 10
    call((i) -> void {
        Stdout.println(x)       # 不允许捕获 x
    })
}
```

### 9.4 this 方法引用

```cang
class Main()

func void add() {
    Stdout.println(7)
}

func void run(Function<Void> f) {
    f()
}

func void test() {
    this.run(this::add)
}

var obj = new Main()
obj.test()
```

`this::add` 会绑定当前对象的 `this`。

### 9.5 对象方法引用

```cang
class Main()

func void add() {
    Stdout.println(7)
}

func void run(Function<Void> f) {
    f()
}

func void test(Main obj) {
    this.run(obj::add)
}
```

`obj::add` 会绑定对象 `obj`。

### 9.6 静态方法引用

```cang
class Main()

static func int twice(int x) {
    return x * 2
}

func int apply(Function<int, int> f, int x) {
    return f(x)
}

class Entry()
Stdout.println(apply(Main::twice, 21))
```

静态方法不需要 receiver，因此使用：

```cang
ClassName::staticMethod
```

### 9.7 Function 类型检查

编译器会检查：

- 返回类型；
- 参数数量；
- 参数类型；
- 静态方法/实例方法使用方式；
- lambda 是否捕获外部变量。

`Class::instanceMethod`、`obj::staticMethod` 等错误引用会被拒绝。

---

## 10. 类与对象

### 10.1 定义类

```cang
class Point(int x = 0, int y = 0)
```

创建对象：

```cang
Point p = new Point(10, 20)
```

### 10.2 实例方法

```cang
class Counter(int value = 0)

func int get() {
    return this.value
}
```

调用：

```cang
Counter c = new Counter(10)
Stdout.println(c.get())
```

### 10.3 静态方法

```cang
class MathUtil()

static func int doubleValue(int x) {
    return x * 2
}
```

调用：

```cang
Stdout.println(MathUtil.doubleValue(21))
```

静态方法必须使用：

```cang
static func ReturnType method(Args...) { ... }
```

### 10.4 继承

```cang
class Animal(String name = "animal")
class Dog(String name = "dog") : Animal(name)
```

Cang 当前只支持单继承，不支持多继承。

---

## 11. 数组

### 11.1 数组声明

```cang
int[10] fixed = []          # 固定长度 10，零填充
int[] values = [1, 2, 3]    # 长度从字面量推导
var numbers = [1, 2, 3]     # 类型和长度都推导
```

多维数组：

```cang
int[][] matrix = [[1, 2], [3, 4]]
int[2][] rows = [[1, 2], [3, 4]]
```


### 11.2 访问和赋值

```cang
int[] values = [1, 2, 3]
Stdout.println(values[0])
values[1] = 20
```

嵌套数组：

```cang
int[][] matrix = [[1, 2], [3, 4]]
Stdout.println(matrix[0][1])
matrix[1][0] = 30
```

编译器会检查元素类型并在运行时检查下标边界。

### 11.3 length()

数组通过 `.length()` 获取长度：

```cang
int[] values = [1, 2, 3]
Stdout.println(values.length())       # 3

int[][] matrix = [[1, 2], [3]]
Stdout.println(matrix.length())       # 2
Stdout.println(matrix[0].length())    # 2
```

数组长度存储在数组内存头部，`length()` 返回 `int`。

### 11.4 for-each

```cang
int[] values = [1, 2, 3]
for (int value : values) {
    Stdout.println(value)
}
```

嵌套数组：

```cang
int[][] matrix = [[1, 2], [3, 4]]
for (int[] row : matrix) {
    Stdout.println(row[0])
}
```

---

## 12. 内存管理

Cang 默认使用 Boehm GC 自动回收，同时保留 `free` 作为提前释放手段。

### 12.0 默认：Boehm GC 自动回收

- 堆分配：`GC_malloc/GC_realloc`，main 入口自动调用 `GC_init`；
- 不可达的数组/对象在分配压力下**自动回收**，无需业务代码干预；
- `System.gc()` 可在任意时机手动触发一次回收；
- 字符串常量在只读全局区，不参与 GC；
- 加 `--no-gc` 切回纯手动模式（`malloc/free`，无人回收）；
- 显式 `free` 在 GC 模式下映射为 `GC_free`（可提前释放，语义不变）；
- 与 `Thread.spawn` 兼容：Windows 经 `GC_CreateThread` 附着线程，posix 在线程入口注册线程栈，无需降级。

### 12.1 free

```cang
int[] values = [1, 2, 3]
Point p = new Point()

free values, p
```

也可以单独释放：

```cang
free values
```

### 12.2 不能释放的类型

基本类型没有堆内存：

```cang
int i = 1
free i
```

当前不会调用 libc `free`，但变量会被标记为不可再使用。更推荐不要对基本类型使用 `free`。

字符串字面量来自常量池，不能释放：

```cang
string a = 'hello'
String b = "hello"

free a       # 编译错误
free b       # 编译错误
```

### 12.3 重复释放和释放后使用

```cang
int[] a = [1]
free a
free a              # 编译错误
Stdout.println(a[0]) # 编译错误
```

当前释放记录按变量名维护。别名场景需要谨慎：

```cang
int[] a = [1]
int[] b = a
free a
# b 仍可能指向已经释放的内存
```

---

## 13. 标准库

标准库源码位于：

```text
stdlib/cang/lang/
```

当前主要标准库：

- `Object.cang`
- `Stdout.cang`
- `String.cang`
- `Math.cang`
- `System.cang`
- `Function.cang`
- `Void.cang`
- `Error.cang`

`cang/lang` 下的标准库由编译器自动查找，也可以显式 import。

---

## 14. Stdout

标准输出由编译器内建处理。

### 14.1 print

不换行：

```cang
Stdout.print("hello")
Stdout.print(123)
Stdout.print(1.5)
```

### 14.2 println

换行：

```cang
Stdout.println("hello")
Stdout.println(123)
Stdout.println(true)
```

支持的常用参数类型：

- `byte`
- `int`
- `long`
- `float`
- `double`
- `bool`
- `String`
- `str`

---

## 15. Math

使用方式：

```cang
Math.PI
Math.E
```

### 15.1 绝对值

```cang
Math.abs(-10)
Math.abs(-1.5)
```

支持 `int`、`long`、`float`、`double`。

### 15.2 最大值和最小值

```cang
Math.max(3, 8)
Math.min(3, 8)
Math.max(1.5, 2.5)
Math.min(1.5, 2.5)
```

### 15.3 幂和根

```cang
Math.sqrt(9.0)
Math.pow(2.0, 3.0)
```

### 15.4 舍入

```cang
Math.floor(2.9)
Math.ceil(2.1)
Math.round(2.6)
```

### 15.5 三角函数

```cang
Math.sin(x)
Math.cos(x)
Math.tan(x)
Math.asin(x)
Math.acos(x)
Math.atan(x)
Math.atan2(y, x)
```

### 15.6 对数和指数

```cang
Math.log(x)
Math.log10(x)
Math.exp(x)
```

### 15.7 随机数

```cang
double value = Math.random()
```

返回 `[0, 1)` 范围的 `double`。

Cang 使用自包含的 64 位 LCG，不依赖平台 libc 的 `rand()`，因此相同初始种子下 Windows/Linux/macOS 生成序列一致。

---

## 16. System

### 16.1 启动参数

```cang
String program = System.ARGS[0]
for (String arg : System.ARGS) {
    Stdout.println(arg)
}
```

`System.ARGS[0]` 通常是程序路径，后面是用户参数。

### 16.2 目标平台信息

```cang
Stdout.println(System.OS_TYPE)
Stdout.println(System.ARCH_TYPE)
```

这两个值根据编译目标确定，而不是根据编译器宿主机确定：

```powershell
Cang program.cang --target linux --arch aarch64
```

### 16.3 退出程序

```cang
System.exit(0)
System.exit(1)
```

### 16.4 环境变量

```cang
String path = System.getenv("PATH")
```

找不到环境变量时返回 `null`。

### 16.5 当前时间

```cang
long now = System.currentTimeMillis()
```

返回 Unix epoch 毫秒时间戳。当前后端使用跨平台 C 运行时时间函数，精度和平台实现有关。

---

## 17. String

`String` 是不可变引用类型，标准库声明位于 `stdlib/cang/lang/String.cang`。

常用方法：

```cang
String s = " Hello Cang "

s.length()
s.equals("Hello Cang")
s.startsWith(" Hello")
s.endsWith("Cang ")
s.indexOf("Cang")
s.substring(1, 6)
s.toUpper()
s.toLower()
s.trim()
s.indexOfIgnoreCase("cang")
```

字符串对象是不可变的，方法不会修改原字符串，而是返回新值或新字符串。

`string` 基本类型不能直接调用这些方法，需要先转换为 `String`：

```cang
string raw = 'hello'
String boxed = new String(raw)
```

---

## 18. Object 与继承

所有普通类默认继承 `Object`。

```cang
class User(String name = "guest")
```

可以显式继承：

```cang
class Animal(String name = "animal")
class Dog(String name = "dog") : Animal(name)
```

当前支持：

- 单继承；
- 父类构造参数；
- `like` 类型判断；
- 基于类型 ID 的动态方法分派。

不支持多继承。

---

## 19. final

变量常量：

```cang
final int value = 10
value = 20       # 编译错误
```

函数不可重写：

```cang
final func void run() {
    Stdout.println("run")
}
```

子类不能覆盖 final 方法。

---

## 20. native

`native` 表示只有签名、没有 Cang 函数体的声明，由编译器或运行时实现：

```cang
static native func int nativeFunction(int x)
```

标准库中的 `Math`、`System`、`Stdout` 等 API 主要使用这种方式声明，再由 LLVMGen 内建实现。

---

## 21. 错误与当前限制

### 21.1 try/catch（第一版）

`try/catch/finally` 与 `throw` 已可用，捕获的对象必须是 `cang/lang/Error` 或其子类。使用前需要导入标准库：

```cang
import cang/lang/Error

class Main()

try {
    throw new Error("boom")
} catch (Error e) {
    Stdout.println(e.message)
} finally {
    Stdout.println("done")
}
```

未捕获异常会打印错误消息和位置并以退出码 1 结束：

```cang
import cang/lang/Error

class Main()
throw new Error("loud failure")
```

输出：

```text
error: loud failure
  at program.cang:4
```

自定义异常继承 `Error`：

```cang
import cang/lang/Error

class Main()
try {
    throw new MyError("sub")
} catch (MyError e) {
    Stdout.println(e.message)
}

class MyError(String message = "", String[] stack = []) : Error(message)
```

`Error` 的字段：

- `message`：`String`，错误消息；
- `stack`：`String[]`，堆栈信息数组（第一版运行时尚未自动填充）。

当前限制：

- 同一 `try` 只支持一个 `catch`；
- `catch` 类型必须是 `Error` 或其子类，否则编译错误；
- `finally` 会在异常传播前执行；
- 异常只在同一函数内传播，暂不支持跨函数 `invoke`/`landingpad`；
- 数组越界和空指针错误仍直接终止程序，尚未转为可捕获异常；
- `throw` 一个函数内、`try` 在另一个函数内时，异常不会被那个 `try` 捕获。

当前许多运行时错误仍会直接终止程序，例如：

- 数组越界；
- 空指针访问；
- 未知方法；
- LLVM 运行时错误。

### 21.2 函数定义位置

`class` 之前只允许 `namespace`、`import` 和注释。函数定义应放在入口 `class` 之后，并作为当前类的方法：

```cang
class Main()

func int add(int a, int b) {
    return a + b
}

var main = new Main()
Stdout.println(main.add(1, 2))
```

### 21.3 函数对象限制

当前支持：

- 普通函数引用；
- 无捕获 lambda；
- `this::method`；
- `object::method`；
- `Class::staticMethod`。

当前不支持普通 lambda 捕获外部变量：

```cang
int x = 1
var f = (i) -> void {
    Stdout.println(x)       # 不支持捕获 x
}
```

### 21.4 内存管理

Cang 默认采用 Boehm GC 自动回收：

- 数组和对象由 GC 自动回收，`System.gc()` 可手动触发；
- `free` 仍可用于提前释放（GC 模式下映射为 `GC_free`）；
- `--no-gc` 可切回纯手动模式；
- 基本类型使用栈槽位；
- 字符串字面量由常量池管理；
- 线程与 GC 已兼容，但对象/数组不允许跨线程传递，线程间异常不传播。

---

## 22. 推荐项目结构

```text
CangProject/
├── src/
│   └── main/
│       └── java/
├── stdlib/
│   └── cang/
│       └── lang/
│           ├── Object.cang
│           ├── String.cang
│           ├── Stdout.cang
│           ├── Math.cang
│           ├── System.cang
│           ├── Function.cang
│           ├── Void.cang
│           └── Error.cang
├── test/
│   ├── hello.cang
│   ├── array.cang
│   └── function_full.cang
└── working/
    └── cc/
        └── ruok/
            └── Main.cang
```

建议：

- `test/` 放单文件测试和回归测试；
- `working/` 放多文件示例；
- 标准库统一放在 `stdlib/cang/lang/`；
- Cang 源码使用 UTF-8 保存；
- Java 编译时使用 `-encoding UTF-8`。

---

## 23. 当前编译流程总结

Cang 的编译流程大致为：

```text
.cang 源码
    ↓
Lexer 词法分析
    ↓
Parser + AST 语法分析
    ↓
标准库/import 解析
    ↓
LLVMGen 生成 LLVM IR
    ↓
.clang/.ll
    ↓
clang 生成目标文件
    ↓
gcc/链接器生成可执行文件
```

生成的 LLVM 文件可以使用：

```powershell
Cang program.cang --no-link
```

查看：

```text
program.ll
```

这对于排查类型、函数调用、数组布局和平台目标问题很有帮助。

---

## 24. 完整示例

```cang
class Main()

func int inc(int x) {
    return x + 1
}

func int apply(Function<int, int> f, int x) {
    return f(x)
}

int[] values = [1, 2, 3]
Stdout.println(values.length())

for (int value : values) {
    Stdout.println(value)
}

Stdout.println(apply(inc, 41))

call((i) -> void {
    Stdout.println(i)
})
```

> 说明：当前 lambda 仅支持 `Function<Void, T>` 形式的无捕获 void lambda，因此上面的 lambda 需要配合 `func void call(Function<Void, int> f)` 使用，不能作为 `Function<int, int>` 的参数。

---

## 25. 总结

Cang 当前适合用于：

- 学习编译器前端和 LLVM 后端；
- 学习静态类型、函数、类、继承和数组；
- 编写小型命令行程序；
- 实验函数对象、方法引用和跨平台 LLVM 目标。

推荐的学习顺序：

1. 变量和基本类型；
2. `if`、`while`、`for`；
3. 函数和返回值；
4. 类和对象；
5. 数组与 `length()`；
6. `String` 与 `Math`/`System` 标准库；
7. `Function`、lambda 和方法引用；
8. 手动内存管理；
9. LLVM IR 和目标平台编译。
