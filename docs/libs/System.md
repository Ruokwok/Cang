# System 系统

> 返回 [文档索引](../README.md)

## 要点
- ARGS 启动参数数组、OS_TYPE/ARCH_TYPE 平台常量
- exit 退出、环境变量读写、当前时间毫秒
- 目标平台常量按编译目标确定而非宿主机


### 16.1 启动参数

```cang
String program = System.ARGS[0]
for (String arg : System.ARGS) {
    Stdout.println(arg)
}
```

`System.ARGS[0]` 通常是程序路径，后面是用户参数。

### 16.2 目标平台信息

```cang
Stdout.println(System.OS_TYPE)
Stdout.println(System.ARCH_TYPE)
```

这两个值根据编译目标确定，而不是根据编译器宿主机确定：

```powershell
Cang program.cang --target linux --arch aarch64
```

### 16.3 退出程序

```cang
System.exit(0)
System.exit(1)
```

### 16.4 环境变量

```cang
String path = System.getenv("PATH")
```

找不到环境变量时返回 `null`。

### 16.5 当前时间

```cang
long now = System.currentTimeMillis()
```

返回 Unix epoch 毫秒时间戳。当前后端使用跨平台 C 运行时时间函数，精度和平台实现有关。

---

