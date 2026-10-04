# 函数对象与 lambda

> 返回 [文档索引](README.md)

## 要点
- Function<R, ...> 类型、函数作参数
- lambda 按值捕获外部变量与 this
- this:: / obj:: / Class:: 方法引用与类型检查


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

### 9.3 lambda 捕获外部变量

```cang
class Main()

func void call(Function<Void, int> f) {
    f(123)
}

int x = 41
call((i) -> void {
    Stdout.println(i + x)     # 按值捕获 x
})
```

- 一个参数；
- **返回类型可写任意类型，也可省略**：`(i) -> { ... }` 从期望的 `Function<...>` 推导；`(i) -> int { ... }` 显式，须与期望一致；
- **需要推导时必须写返回类型**：lambda 不在已知 `Function<...>` 上下文（如 `var f = (x) -> ...`）时编译报错并提示显式写出——参数类型同样来自期望类型；
- 非 `void` 返回的 lambda，每个路径都必须 `return`（否则 `must return a value of type ... on every path`）；
- **可以捕获外部变量与 `this`，按值捕获**（创建时快照，与 Java 一致）。

捕获语义：

- 快照发生在 lambda **创建时**，之后对外部变量的修改不影响已创建的实例：

```cang
int x = 41
Function<Void, int> f = (i) -> void {
    Stdout.println(i + x)
}
f(1)        # 42
x = 100
f(2)        # 43（仍是创建时的 41）
```

- lambda 内对被捕获变量赋值只修改**副本**，不影响外部变量；
- lambda 内声明的同名局部变量会遮蔽捕获（同名局部优先）；
- 捕获数组、List 或类实例只快照引用本身，对象内容仍共享（对 List 的 add/remove 外部可见）；
- 线程块 `thread { }` 仍然**禁止捕获**（见文档开头 Thread 章节）。

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
- 静态方法/实例方法使用方式。

`Class::instanceMethod`、`obj::staticMethod` 等错误引用会被拒绝。

---

