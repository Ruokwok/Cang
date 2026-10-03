# Dict KV 映射

> 返回 [文档索引](../README.md)

## 要点
- Dict<K, V> 纯 Cang 实现，API 参考 java.util.HashMap
- put/get/getOrDefault/containsKey/remove/keys/values
- get 未找到抛 Error；keys()/values() 快照按插入序对应

### 13.1 cang/lang/Dict（KV 映射）

`Dict<K, V>` 是键值映射（纯 Cang 实现，API 参考 `java.util.HashMap`），多类型参数泛型的第一个标准库用户：

```cang
import cang/lang/Dict

class Main()
var d = new Dict<String, int>()
d.put("a", 1)
d.put("b", 2)
d.put("a", 10)                            # 同键覆盖
Stdout.println(d.get("a"))                # 10
Stdout.println(d.getOrDefault("zz", -1))  # -1（键不存在）
Stdout.println(d.size())                  # 2
for (String k : d.keys()) {
    Stdout.println(k + " = " + d.get(k))
}
```

- API：`put` / `get` / `getOrDefault` / `containsKey` / `remove` / `size` / `isEmpty` / `clear` / `keys` / `values`；
- `get` 未找到抛 `Error("Dict key not found")`；不想抛错用 `getOrDefault(key, fallback)`；
- `keys()` / `values()` 返回快照数组，顺序按插入序一一对应（可配对遍历）；
- 键判等用 `==`：`String` 按**内容**比较，值类型按数值；`K` 支持值类型与 `String`，`V` 不限；
- 底层为并行数组线性查找、容量不足自动翻倍（2x），适合中小规模；`_indexOf` / `_grow` 为类私有。

---

