# Thread 线程

> 返回 [文档索引](../README.md)

## 要点
- 对象式 new Thread([daemon]).task(fn).start() + join；非守护默认退出前等待，new Thread(true) 为守护不等；thread { } 同非守护语义
- **Thread.spawn 已移除**（编译期报错并提示迁移）；对象式任务为 void 无参，fn 收方法引用/顶层函数/无参 lambda
- 仅值类型跨线程（对象式任务的 receiver 除外）；与 Boehm GC 兼容（GC_CreateThread）

## Thread<T> 与 spawn 的移除

`Thread<T>` 是编译器内建的线程句柄类型。**显式创建线程只有一种写法**：

```cang
var t = new Thread().task(fn).start()
t.join()
```

- **`Thread.spawn(fn, args...)` 已移除**——现在调用会在编译期报错：
  `Thread.spawn has been removed; use new Thread().task(fn).start()`；
- 原 spawn 支持的**值类型返回 join**（`Thread<int> t = Thread.spawn(...); t.join()` 取值）
  随移除一并消失，当前对象式与 `thread { }` 均为 **void 任务**（`start()` 返回的句柄可
  `join()` 等待完成，但不携带返回值）；需要输出结果请在任务内部打印或写入共享变量；
- `Thread<T>` 类型仍用于 join 句柄（`start()` 返回 `Thread<void>`）；
- 平台与 GC：Windows 经 `GC_CreateThread` 附着（`--no-gc` 用 `CreateThread`），join 用
  `WaitForSingleObject`；Linux/macOS `pthread_create/pthread_join` 且线程入口注册线程栈；
- `join()` 每个句柄只能调用一次（重复报 `Thread already joined`）；创建失败报错退出；
- 线程内异常不跨线程传播：未捕获异常终止整个进程；线程函数内部的 `try/catch` 正常工作。

验证状态：`thread_demo`（双任务+join）、`thread_types`（顶层函数/方法引用/lambda 三种任务源）、
`thread_gc`（线程内 GC 分配）、`t_thread_obj`（对象式）在 Windows/MinGW + GC 下端到端通过；
`thread_reject` 断言 spawn 移除报错；`thread_double_join` 断言重复 join 运行时错误。
Linux/macOS 已验证 LLVM IR 与目标文件编译，实际链接运行需对应平台环境。

### thread { } 语法糖

用于创建无需返回值的线程，可写在方法体或顶层代码中：

```cang
class Main()

Stdout.println("before")

thread {
    Stdout.println("in thread")
}

Stdout.println("main continues")
```

语义：

- 块被编译成一个独立的无参 `void` 函数并作为线程启动（继承对象式的 GC 语义与跨平台路径）；
- 当前线程不等待，继续执行后面的语句；
- 程序返回前会自动 join 所有 `thread { }` 创建的线程，保证块内输出不会因进程退出而丢失；
- **不允许捕获外部变量或 `this`**（报 `Thread block cannot capture ...`；与 lambda 不同，线程块保持无捕获）；
- 暂不支持写在循环体内（编译期报错）。

### new Thread().task(fn).start() 对象式写法

参考 Java 的线程写法：先创建线程对象，绑定任务，再启动：

```cang
class Main()
func void run() {
    Stdout.println("in thread")
}

var t = new Thread().task(this::run).start()
t.join()
Stdout.println("main done")
```

无参 lambda 也可以直接作为任务（返回类型可省略）：

```cang
class Main()
var t = new Thread().task(() -> {
    Stdout.println("lambda task")
}).start()
t.join()
```

- `new Thread()` / `new Thread(false)` = **非守护**（默认）；`new Thread(true)` = **守护**（参数必须 bool，其他类型编译报错）；任务与句柄字段初始为空；
- `.task(fn)` 绑定任务并返回线程对象本身（支持链式）：参数须是**无参无返回**的 `Function<void>`——方法引用（`this::run`、`obj::m`、`Class::staticM`）、顶层函数名或**无参 lambda**（`() -> { ... }`）均可；带参数或有返回值的函数在编译期被拒绝（`Thread.task requires a no-argument no-return function`）；
- `.start()` 在新线程执行任务，返回 `Thread<void>` 句柄，可继续 `.join()` 等待完成；未 `task` 就 `start`、或对同一对象重复 `start`，运行时报错（`Thread has no task` / `Thread already started`）；
- `.join()` 等待任务结束（`void` 无返回值）；对未启动的对象调用 `join` 运行时报错；
- **守护线程决定退出行为**：非守护（默认）的句柄会登记，**程序退出前统一 join 等待完成**（与 `thread { }` 一致）；`new Thread(true)` 声明守护线程，退出**不等待**（可能被截断，输出不保证）；手动 `t.join()` 过的句柄收尾自动跳过（不会重复 join）；
- 与 `thread { }` 的无捕获限制不同：任务可以携带 `this`/对象 receiver（`this::run` 即绑定当前实例），跨线程共享状态的数据竞争由程序自己保证（与 Java 相同）；
- `thread { }` 写法不受影响，继续作为 void 任务的语法糖（程序退出前自动 join）。

> 文档基于当前编译器实现。高级功能仍在开发中，已知限制见 [编译与当前限制](../17-编译与当前限制.md)。

---

