# cang/io/File 文件

> 返回 [文档索引](../README.md)

## 要点
- import cang/io/File；构造即路径，toSystemPath 转分隔符
- 路径/状态/目录操作/文本读写 20 个方法
- 失败语义 Java 风格：readText->null、list->null、mkdir->false


文件读写标准库，对象模型参考 Java 的 `java.io.File`。相对路径的根目录是**程序运行目录**（进程当前工作目录）。`cang/io` 下的模块需要显式导入：

```cang
import cang/io/File

class Main()
File f = new File("data/abc.txt")
Stdout.println(f.exists())
```

### 18.1 系统路径分隔符

`File.separator` 是平台常量：Windows 为 `\`，Linux/macOS 为 `/`。

```cang
Stdout.println(File.separator)
```

`getPath()` 原样返回构造时传入的字符串，不做任何转换。需要把路径统一成当前系统风格时显式调用 `toSystemPath()`：

```cang
File f = new File("src/util/helper.cang")   # 源串原样保留
Stdout.println(f.toSystemPath())            # windows: src\util\helper.cang
                                            # linux:   src/util/helper.cang
```

注意：Linux 上反斜杠是合法的文件名字符，`toSystemPath()` 会把它们全部替换为 `/`，只应在处理跨平台路径字面量时调用，不要对已存在的 Linux 文件名使用。

### 18.2 路径信息

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `getPath()` | String | 构造时的原始路径 |
| `getName()` | String | 最后一段名称（文件名/目录名） |
| `getParent()` | String | 父目录路径；没有父目录时返回 `null` |
| `getAbsolutePath()` | String | 相对路径基于运行目录拼接 |
| `isAbsolute()` | bool | 是否绝对路径 |
| `toSystemPath()` | String | 分隔符转换为当前系统风格 |

```cang
File f = new File("src/abc.txt")
Stdout.println(f.getPath())          # src/abc.txt
Stdout.println(f.getName())          # abc.txt
Stdout.println(f.getParent())        # src
Stdout.println(f.isAbsolute())       # false

File bare = new File("abc.txt")
Stdout.println(bare.getParent() == null)   # true（null 比较安全）
```

### 18.3 状态查询

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `exists()` | bool | 路径是否存在（文件或目录） |
| `isFile()` | bool | 是否普通文件 |
| `isDirectory()` | bool | 是否目录 |
| `length()` | long | 文件字节数；目录或不存在时为 0 |

### 18.4 文件与目录操作

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `delete()` | bool | 删除文件或空目录 |
| `mkdir()` | bool | 创建单级目录（已存在返回 false） |
| `mkdirs()` | bool | 递归创建目录（已存在返回 false，与 Java 一致） |
| `createNewFile()` | bool | 创建空文件（已存在返回 false） |
| `renameTo(File dest)` | bool | 重命名/移动到目标路径 |

```cang
File dir = new File("out/reports")
dir.mkdirs()
File report = new File("out/reports/r1.txt")
report.writeText("hello")

File moved = new File("out/r1.txt")
report.renameTo(moved)
moved.delete()
dir.delete()          # 目录非空时失败（只能删空目录）
```

### 18.5 文本读写

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `readText()` | String | 读取全部文本；无法打开返回 `null`，空文件返回 `""` |
| `writeText(String)` | bool | 覆盖写入；可打开返回 true |
| `appendText(String)` | bool | 追加写入；可打开返回 true |
| `readLines()` | String[] | 按行读取（自动去掉 `\r\n` 的 `\r`）；无法打开返回**空数组** |
| `list()` | String[] | 列出目录内容（不含 `.` 与 `..`）；不是目录返回 `null` |

```cang
File f = new File("data.txt")
f.writeText("line1\nline2\n")
f.appendText("line3\n")

String all = f.readText()
String[] lines = f.readLines()
Stdout.println(lines.length())        # 3
for (String s : lines) {
    Stdout.println(s)
}

File dir = new File("data")
String[] names = dir.list()           # 非目录时为 null，先判 isDirectory()
```

### 18.6 失败语义与内存

- 打开失败不抛异常（语言没有异常到 stdlib 的通路），按上表返回 `null` / 空数组 / `false`。
  与 Java 的差异：Java `readText` 失败抛 `IOException`，这里返回 `null`，请用 `== null` 判断。
- 读取返回的字符串与数组由 Boehm GC 自动回收（默认模式）；`--no-gc` 模式下这些缓冲无法显式释放（`free` 不接受字符串），会泄漏到进程结束——建议文件程序使用默认 GC 模式。
- `readLines()` / `list()` 返回的数组可直接链式取长度：`f.readLines().length()`。
- 平台实现差异：Windows 使用 `_mkdir`/`_rmdir`/`_fseeki64`/`_ftelli64` 等下划线符号，Linux/macOS 使用 `mkdir(path,0777)`/`fseeko` 等 POSIX 形式；全程不用 `stat`，状态判断由 `access()`/`opendir()` 组合完成。Windows 端到端已验证；Linux/macOS 目标当前只能生成 IR（无 WSL/macOS 主机，`--target linux --no-link` 可检查 IR）。

---

