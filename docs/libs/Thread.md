# Thread 线程

> 返回 [文档索引](../README.md)

## 要点
- Thread.spawn(fn, args...) + join 取值；thread { } 语法糖
- 对象式 new Thread().task(this::run).start()
- 仅值类型跨线程；与 Boehm GC 兼容（GC_CreateThread）

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

验证状态：`test/thread_demo.cang`、`thread_types.cang`、`thread_gc.cang`、`thread_block.cang`、`thread_block2.cang`、`thread_block3.cang` 在 Windows/MinGW + GC 下端到端通过（含 `Thread<Void>/Thread<String>/Thread<double>`、String 参数、线程内 GC 分配与 `System.gc()`、`thread { }` 顶层/方法体）；Linux/macOS 已验证 LLVM IR 与目标文件编译，实际链接运行需对应平台环境。

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

- 块被编译成一个独立的无参 `void` 函数，并通过 `Thread.spawn` 启动（继承 `Thread` 的 GC 语义与跨平台路径）；
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

- `new Thread()` 创建线程对象（任务与句柄字段初始为空）；
- `.task(fn)` 绑定任务并返回线程对象本身（支持链式）：参数须是**无参无返回**的 `Function<void>`——方法引用（`this::run`、`obj::m`、`Class::staticM`）、顶层函数名或无参 lambda 均可；带参数或有返回值的函数在编译期被拒绝（`Thread.task requires a no-argument no-return function`）；
- `.start()` 在新线程执行任务，返回 `Thread<void>` 句柄，可继续 `.join()` 等待完成；未 `task` 就 `start`、或对同一对象重复 `start`，运行时报错（`Thread has no task` / `Thread already started`）；
- `.join()` 等待任务结束（`void` 无返回值）；对未启动的对象调用 `join` 运行时报错；
- 与 `thread { }` 的无捕获限制不同：任务可以携带 `this`/对象 receiver（`this::run` 即绑定当前实例），跨线程共享状态的数据竞争由程序自己保证（与 Java 相同）；
- `Thread.spawn` 与 `thread { }` 写法不受影响，继续支持值类型返回值与参数。

> 文档基于当前编译器实现。部分高级功能仍在开发中，文末列出了已知限制。

---

