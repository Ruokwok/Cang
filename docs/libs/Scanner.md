# Scanner 标准输入

> 返回 [文档索引](../README.md)

## 要点

- `cang/io/Scanner`：键盘/标准输入读取，**单例**（`class _ Scanner` 私有构造 + 静态工厂）
- 读取方法**阻塞当前线程**等待输入：`readLine()` 整行、`readKey()` 单键（含 esc/enter/方向键）
- `KEY_*` 静态常量用于比较键码；显式 `import cang/io/Scanner`（cang/io 不自动加载）

## 用法

```cang
import cang/io/Scanner

class Main()
var sc = Scanner.get()                 # 静态工厂取单例（不可 new）
String line = sc.readLine()            # 阻塞读一行（回显，enter 结束，去末尾换行）
Stdout.println(line)

int k = sc.readKey()                   # 阻塞读一个键（无回显）
if (k == Scanner.KEY_ENTER) {
    Stdout.println("enter")
}
if (k == Scanner.KEY_ESC) {
    Stdout.println("esc")
}
if (k == Scanner.KEY_UP) {
    Stdout.println("up")
}
```

## API

| 方法 / 常量 | 说明 |
|---|---|
| `static Scanner get()` | 获取单例实例（外部 `new Scanner()` 编译报错——私有构造） |
| `int readKey()` | **阻塞**读一键，返回键码（无回显）。普通字符=ASCII 0..127；`enter=13` `esc=27` `tab=9` `backspace=8`；方向键/功能键 = **256+扫描码**（up=328 down=332 left=331 right=333） |
| `String readLine()` | **阻塞**读一行（回显，enter 结束），去掉末尾换行；**EOF 返回 null**（管道/文件重定向到尾时） |
| `KEY_ENTER/KEY_ESC/KEY_TAB/KEY_BACKSPACE` | 13 / 27 / 9 / 8 |
| `KEY_UP/KEY_DOWN/KEY_LEFT/KEY_RIGHT` | 328 / 332 / 331 / 333（Windows `_getch` 扫描码+256） |

## 键码读取循环示例

```cang
import cang/io/Scanner

class Main()
var sc = Scanner.get()
Stdout.println("press q to quit")
while (true) {
    int k = sc.readKey()
    if (k == 113) {           # 'q'
        break
    }
    if (k == Scanner.KEY_ENTER) {
        Stdout.println("<CR>")
    } else if (k == Scanner.KEY_ESC) {
        Stdout.println("<ESC>")
    } else {
        Stdout.println(k)
    }
}
```

## 平台差异（必读）

- **Windows（主实现）**：`readKey` 走 conio `_getch()`——无回显、**特殊键完整**（0/0xE0 前缀 + 扫描码在库内合并为 256+code）；`readLine` 走 `fgets(stdin)`；
- **POSIX v1 降级**：`readKey` 走 `getchar()`（**行缓冲**——需 enter 才返回，方向键等特殊键需终端 raw 模式，暂不支持）；`readLine` 同 `fgets`；
- **交互验证**：`readKey` 依赖真实终端（conio 不读管道），自动化测试覆盖 `readLine`/单例/KEY 常量，`readKey` 请在真实控制台手测（`test/t_scanner_key.cang`）；
- 实现为编译器 Design B wrapper（`emitScannerRuntime`），符号 `_getch`/`fgets`/`__acrt_iob_func` 已链接实证。