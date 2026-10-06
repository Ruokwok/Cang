# StringBuilder 字符串构建器

> 返回 [文档索引](../README.md)

## 要点

- 高效拼接：`append` 摊还 O(len(s))，容量不足自动翻倍（最低 16 字节）
- `toString()` 返回**不可变快照**——之后继续 append 不影响已取的字符串
- 编译器内建（Design B wrapper），需 `import cang/lang/StringBuilder`

## 用法

```cang
import cang/lang/StringBuilder

StringBuilder sb = new StringBuilder()
sb.append("hello")
sb.append(", ")
sb.append("world")
Stdout.println(sb.toString())   // hello, world
Stdout.println(sb.length())     // 12（字节数）

# 预留容量（可选，第一个参数是 cap）
StringBuilder buf = new StringBuilder(256)

# 快照隔离
String snap = sb.toString()
sb.append("!")
Stdout.println(snap)            // 不含后面的 "!"

# 复用：clear 保留已分配容量
buf.clear()
```

## 行为细节

| 情况 | 行为 |
|---|---|
| `append(null)` | 空操作（安全） |
| 首次 append | 分配缓冲（`max(need, cap*2, 16)`，自定义 cap 也从堆分配） |
| 增长 | 新缓冲 + 拷贝 + 释放旧缓冲（GC 模式由 GC 回收） |
| `length()` | 返回 `int`（缓冲超过 2^31 字节会截断，实际不会遇到） |
| `cap` / `data` / `size` | 内部状态字段——**只读，勿直接修改** |

对比裸 `+` 拼接：循环里 `s = s + x` 每次全量拷贝（O(n²)）；StringBuilder 把代价推迟到一次性的 `toString`，是 Lexer/CodeGen 这类热路径的正确选择。
