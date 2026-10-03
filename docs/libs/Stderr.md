# Stderr 标准错误输出

> 返回 [文档索引](../README.md)

## 要点
- 成员与 Stdout 完全一致，输出到标准错误流
- Windows 用 __acrt_iob_func(2)+fprintf，POSIX 用 stderr
- 适合错误日志：与 stdout 分流可分别重定向

### 14.3 Stderr（标准错误输出）

成员方法与 `Stdout` 完全一致（`print` / `println`，支持同样的参数类型），区别仅是输出到**标准错误**（stderr）：

```cang
Stdout.println("normal output")   # stdout
Stderr.println("error message")   # stderr
Stderr.print("warning: ")
Stderr.println("details")
```

无需 import（编译器内置，按名字分派）；显式 `import cang/lang/Stderr` 也可（供 IDE/类型提示）。

---

