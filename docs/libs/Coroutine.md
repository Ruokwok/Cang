# Coroutine 协程

> 返回 [文档索引](../README.md)

## 要点

- `cang/lang/Coroutine`：基于 libuv 的**单线程事件循环**协程，不是并行线程
- 对象式：`new Coroutine([daemon|fn]).task(fn).setDaemon(bool).start().join()`
- `coroutine { ... }` 是语法糖：等价于 `new Coroutine(false).task(() -> { ... }).start()`
- 任务必须是**无参无返回**的 `Function<void>`；`start()` 返回协程自身，便于链式 `join()`
- 非 daemon 协程在 `main` 退出前自动泵完；daemon 协程不等待
- 引用 `Coroutine` 时会自动加载标准库，无需显式 `import`

## 用法

```cang
class Main
    static func void main(String[] args) {
        var c = new Coroutine(() -> {
            Stdout.println("hello from coroutine")
        }).start()

        c.join()
        Stdout.println("done")
    }
```

`coroutine { ... }` 更简洁：

```cang
class Main
    static func void main(String[] args) {
        coroutine {
            Stdout.println("fire and forget")
        }

        var c = coroutine {
            Stdout.println("with handle")
        }
        c.join()
    }
```

## API

| 写法 | 说明 |
|---|---|
| `new Coroutine()` | 空任务；`start()` 前必须 `task(...)` |
| `new Coroutine(bool daemon)` | 设置是否 daemon |
| `new Coroutine(Function<void> fn)` | 直接绑定任务 |
| `task(Function<void> fn)` | 绑定/覆盖任务，返回自身；只能在 `start()` 前调用 |
| `setDaemon(bool b)` | 设置 daemon，返回自身；只能在 `start()` 前调用 |
| `start()` | 把任务调度到事件循环，返回自身；只能调用一次 |
| `join()` | 泵事件循环直到该协程完成；未 `start()` 或 join 自己会报运行时错误 |
| `getId()` | 协程逻辑 id（int，独立于线程 id） |
| `isAlive()` | 已 start 且尚未完成时为 true |

## 语义与限制

- **单线程**：所有协程在同一个事件循环线程上顺序执行；阻塞调用（如 `Thread.sleep`）会卡住整个循环和全部协程；
- **没有并行**：不要用协程替代需要 CPU 并行的 `Thread`；
- `join` 其他协程 = 嵌套泵循环，是真正的协作交错点；`join` 自己 = 运行时错误；
- 非 daemon 协程在 `main` 返回前自动泵完，输出不会丢；daemon 协程不等；
- 任务体与 lambda 一样**按值捕获**外部变量与 `this`；对捕获变量赋值是编译错误（见 [函数对象与 lambda](../10-函数对象与lambda.md)）；
- 跨线程触碰同一个事件循环未定义；协程 API 通常只在同一线程（一般是 main）使用；
- 使用协程需要链接 libuv 静态库；库缺失时链接阶段报错（布局与 GC 库相同，见 [快速开始](../01-快速开始.md)）。

---
