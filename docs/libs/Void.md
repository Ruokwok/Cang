# Void 无返回值包装

> 返回 [文档索引](../README.md)

## 要点

- `cang/lang/Void`：函数类型中的“无返回值”占位类型
- 写法为 `Function<Void, 参数...>`，例如 `Function<Void, int>` 表示接收 `int`、不返回值的函数
- `Function<void>` / `Function<void, int>` 也可被解析为同样的无返回值签名
- `Void` 是类型声明，不是可用的返回值；普通函数无返回值仍写 `func void name(...)`

## 用法

```cang
class Main
    func void call(Function<Void, int> f) {
        f(123)
    }

    static func void main(String[] args) {
        var m = new Main()
        m.call((i) -> {
            Stdout.println(i)
        })
    }
```

无参无返回的函数类型：

```cang
Function<Void> task = () -> {
    Stdout.println("run")
}
```

`Void` 会在引用 `Function<Void, ...>` 时自动加载，显式 `import cang/lang/Void` 亦可。

---
