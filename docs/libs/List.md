# List 动态数组

> 返回 [文档索引](../README.md)

## 要点

- `cang/lang/List<T>`：纯 Cang 实现的泛型动态数组，存储结构与扩容策略参考 `java.util.ArrayList`
- 底层 `T[] data + int size`，容量不足时按 1.5 倍扩容（`oldCap + oldCap / 2`）
- `add` 返回新元素下标；`remove(int)` 返回被删元素；越界访问抛 `Error`
- 无需 import 也可用（`cang/lang` 自动查找），显式 `import cang/lang/List` 亦可

## 构造与导入

```cang
import cang/lang/List

class Main()
var list = new List<String>()          # 默认容量 10（对应 Java DEFAULT_CAPACITY）
list.add("a")
list.add("b")

List<int> nums = new List<>()          # diamond 从声明推断元素类型
nums.add(1)
```

- 泛型单态化：每个元素类型组合（`List<String>`、`List<int>`…）各生成一份具体类型；
- 元素类型支持值类型与引用类型（`int`/`String`/对象等），元素比较用 `==`（`String` 按内容）。

## API 详解

### size() / isEmpty()

```cang
Stdout.println(list.size())      # 元素个数：2
Stdout.println(list.isEmpty())   # 是否为空：false
```

### add(T value) — 末尾追加

```cang
int idx = list.add("c")          # 返回新元素下标（本例为 2）
nums.add(42)
```

容量不足时自动调用私有 `_grow()` 扩容为 1.5 倍（最小增长兜底 +10，避免 0/1 容量死循环）。

### get(int index) / set(int index, T value) — 下标读写

```cang
String s = list.get(0)           # 越界抛 Error: List index out of bounds: 99
list.set(0, "z")                 # 同样做越界检查
```

### insertAt(int index, T value) — 指定下标插入

Java `add(int index, E element)` 的无重载命名：

```cang
var xs = new List<int>()
xs.add(1)
xs.add(3)
xs.insertAt(1, 2)                # [1, 2, 3]，后续元素整体后移
```

### remove(int index) — 删除并返回

```cang
String gone = list.remove(0)     # 返回被删元素，后续元素前移
```

与 Java `remove(int)` 语义一致（按值删除请先 `indexOf`）。

### contains(T value) / indexOf(T value) — 查找

```cang
if (list.contains("b")) { ... }
int i = list.indexOf("b")        # 首次出现下标；未找到返回 -1
```

`String` 元素按**内容**比较（与 Java `equals` 语义一致）。

### clear() / toArray()

```cang
list.clear()                     # 清空（容量保留，与 Java 一致）
String[] arr = list.toArray()    # 拷贝为长度等于 size 的新数组
```

## 遍历

```cang
for (String s : list) {
    Stdout.println(s)
}
int sum = 0
for (int n : nums) {
    sum = sum + n
}
```

## 私有方法

- `_grow()`：容量翻倍/1.5 倍扩容（`_` 前缀 = 类私有，外部调用报错）。

## 完整示例

```cang
import cang/lang/List

class Main()
var list = new List<String>()
list.add("apple")
list.add("banana")
list.add(1, "apricot")           # 错误写法示意：List 用 insertAt(1, "apricot")
```

正确写法：

```cang
import cang/lang/List

class Main()
var list = new List<String>()
list.add("apple")
list.add("banana")
list.insertAt(1, "apricot")      # [apple, apricot, banana]
Stdout.println(list.size())      # 3
Stdout.println(list.indexOf("banana"))   # 2
Stdout.println(list.remove(0))   # apple
for (String s : list) {
    Stdout.println(s)            # apricot / banana
}
```
