# Math 数学

> 返回 [文档索引](../README.md)

## 要点
- abs/max/min、pow/sqrt、floor/ceil/round
- sin/cos/tan/asin/acos/atan、log/exp/log10
- random() 伪随机（LCG，跨平台可复现序列）


使用方式：

```cang
Math.PI
Math.E
```

### 15.1 绝对值

```cang
Math.abs(-10)
Math.abs(-1.5)
```

支持 `int`、`long`、`float`、`double`。

### 15.2 最大值和最小值

```cang
Math.max(3, 8)
Math.min(3, 8)
Math.max(1.5, 2.5)
Math.min(1.5, 2.5)
```

### 15.3 幂和根

```cang
Math.sqrt(9.0)
Math.pow(2.0, 3.0)
```

### 15.4 舍入

```cang
Math.floor(2.9)
Math.ceil(2.1)
Math.round(2.6)
```

### 15.5 三角函数

```cang
Math.sin(x)
Math.cos(x)
Math.tan(x)
Math.asin(x)
Math.acos(x)
Math.atan(x)
Math.atan2(y, x)
```

### 15.6 对数和指数

```cang
Math.log(x)
Math.log10(x)
Math.exp(x)
```

### 15.7 随机数

```cang
double value = Math.random()
```

返回 `[0, 1)` 范围的 `double`。

Cang 使用自包含的 64 位 LCG，不依赖平台 libc 的 `rand()`，因此相同初始种子下 Windows/Linux/macOS 生成序列一致。

---

