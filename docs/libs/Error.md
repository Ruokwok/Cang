# Error 异常基类

> 返回 [文档索引](../README.md)

## 要点

- `cang/lang/Error`：所有可抛出/可捕获异常的基类，字段为构造参数模型
- `throw new Error(message)` 抛出；`catch (Error e)` 捕获（含自定义子类）
- `e.message` 取错误消息；未捕获异常打印消息与位置并以退出码 1 结束
- 完整控制流语法见 [异常处理](../15-异常处理.md)

## 类声明

```cang
class Error(String message = "", String[] stack = [])
```

- `message`：错误描述文本，默认空串；
- `stack`：运行时填充的调用栈文本数组（简化版运行时堆栈跟踪）；
- 采用**参数即构造器**模型（无花括号字段体）。

## 抛出与捕获

```cang
import cang/lang/Error

class Main()
try {
    throw new Error("boom")
} catch (Error e) {
    Stdout.println(e.message)     # boom
} finally {
    Stdout.println("done")        # 无论是否异常都会执行
}
```

## 自定义错误类型

捕获类型必须是 `Error` 或其子类——用继承定义领域错误：

```cang
import cang/lang/Error

class ValidationError(String message = "") : Error(message)

func void check(int age) {
    if (age < 0) {
        throw new ValidationError("age must be >= 0")
    }
}

class Main()
try {
    check(-1)
} catch (Error e) {
    Stdout.println(e.message)     # age must be >= 0
}
```

## 运行时错误也会走异常

下列运行时错误会抛 `Error`，被 `try/catch` 捕获（未捕获则打印并退出 1）：

- 空指针解引用：`Null pointer dereference`
- 数组越界：`Array index out of bounds`
- 整数除零：`Integer division by zero`
- 列表/字典越界或缺键：`List index out of bounds: ...` / `Dict key not found`

```cang
import cang/lang/Error

class Main()
int[] a = null
try {
    Stdout.println(a[0])
} catch (Error e) {
    Stdout.println("caught: " + e.message)
}
```

## 注意

- 线程内异常**不跨线程传播**：线程里未捕获异常终止整个进程；线程函数内部的 `try/catch` 正常工作；
- 第一版为同函数控制流 handler，跨函数 `invoke/landingpad` 传播不在范围内（见 [编译与当前限制](../17-编译与当前限制.md)）。
