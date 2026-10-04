# Function 函数对象

> 返回 [文档索引](../README.md)

## 要点

- `Function<R, P1, P2, ...>`：把函数/lambda/方法引用当作值传递的类型，`R` 为返回类型，其后为参数类型
- 运行时表示为 `{ code, receiver }` 结构（ABI 固定：receiver 恒为首参）
- lambda 按值捕获外部变量与 `this`；`this::m` / `obj::m` / `Class::m` 三种方法引用
- 完整教程见 [函数对象与 lambda](../10-函数对象与lambda.md)

## 类型写法

```cang
Function<int, int, int> add = (a, b) -> a + b     # R=int，参数 int, int
Function<void> task = () -> { ... }               # 无参无返回
Function<int, int> twice = (x) -> x * 2
```

- `Function.cang` 为类型声明面（`cang/lang` 自动可用）；
- 调用就是普通函数调用：`int r = add(1, 2)`。

## 作参数传递

```cang
func int applyTwice(int x, Function<int, int> f) {
    return f(f(x))
}

class Main()
Stdout.println(applyTwice(3, (x) -> x + 1))   # 5
```

## lambda 捕获（按值快照）

```cang
class Main()
int base = 10
Function<int> getBase = () -> base     # 创建时快照 base 的值
base = 99
Stdout.println(getBase())              # 10（不是 99）
```

- 捕获对象/数组/List 时快照**引用本身**（内容共享、外部可见）；
- 捕获 `this` 时保持原始对象引用；
- lambda 局部写捕获名只改副本；内层声明可遮蔽。

## 方法引用

```cang
class Greeter(String name = "world")
func void hello() {
    Stdout.println("hi " + this.name)
}

func int inc(int x) { return x + 1 }

class Main()
# this:: —— 绑定当前实例（顶层语句中 this = 入口类实例）
Function<void> f = this::someMethod

# 对象方法引用
var g = new Greeter("cang")
Function<void> h = g.hello               # 或 g::hello 形式按语言支持书写

# 静态/顶层函数引用
Function<int, int> p = inc
Stdout.println(p(41))                    # 42
```

## 类型检查

- 赋值/传参时校验 `Function<返回, 参数...>` 签名匹配（返回协变不放宽到无关类型）；
- 方法引用解析不到、签名不符在编译期报错（如 `Unknown method in reference`、参数个数/类型不匹配）。

## 限制（第一版）

- 不可对 `Function` 做 `==` 比较（无函数相等语义）；
- `thread {}` 块不接受捕获 lambda / Function 作目标（对象式 `task()` 接受无参无返回函数——方法引用/顶层函数/无参 lambda）；
- 详见 [编译与当前限制](../17-编译与当前限制.md)。
