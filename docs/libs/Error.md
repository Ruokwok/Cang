# Error 异常基类

> 返回 [文档索引](../README.md)

## 要点

- `cang/lang/Error`：所有可抛出/可捕获异常的基类，字段在类体声明、构造为 `init`
- `throw new Error(message)` 抛出；`catch (Error e)` 捕获（含自定义子类）
- `e.message` 取错误消息；未捕获异常打印消息与位置并以退出码 1 结束
- 完整控制流语法见 [异常处理](../15-异常处理.md)

## 类声明

```cang
class Error
    String message = "";
    String[] stack = [];

    init() {
    }

    init(String message) {
        this.message = message;
    }
```

- `message`：错误描述文本，默认空串；
- `stack`：预留的调用栈文本数组；当前运行时尚未自动填充；
- 字段在类体声明（含默认值），`init` 构造方法赋值。

## 抛出与捕获

```cang
import cang/lang/Error

class Main
    static func void main(String[] args) {
        try {
            throw new Error("boom")
        } catch (Error e) {
            Stdout.println(e.message)     # boom
        } finally {
            Stdout.println("done")        # 无论是否异常都会执行
        }
    }
```

## 自定义错误类型

捕获类型必须是 `Error` 或其子类——用继承定义领域错误：

```cang
# ValidationError.cang
import cang/lang/Error

class ValidationError : Error
    init(String message) {
        super(message);
    }
```

```cang
# Main.cang
import cang/lang/Error
import ValidationError

func void check(int age) {
    if (age < 0) {
        throw new ValidationError("age must be >= 0")
    }
}

class Main
    static func void main(String[] args) {
        try {
            check(-1)
        } catch (Error e) {
            Stdout.println(e.message)     # age must be >= 0
        }
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

class Main
    static func void main(String[] args) {
        int[] a = null
        try {
            Stdout.println(a[0])
        } catch (Error e) {
            Stdout.println("caught: " + e.message)
        }
    }
```

## 注意

- 线程内异常**不跨线程传播**：线程里未捕获异常终止整个进程；线程函数内部的 `try/catch` 正常工作；
- 异常支持**跨函数传播**：被调用函数抛出（含可捕获的运行时错误）时，会回退到调用方最近的 `try`；
- 当前同一 `try` 只支持一个 `catch` 子句；`finally` 支持；线程边界仍隔离异常。
