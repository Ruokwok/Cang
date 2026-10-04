# Scanner 标准输入

> 返回 [文档索引](../README.md)

## 要点

- `cang/io/Scanner`：键盘/标准输入读取，**单例**（`class _ Scanner` 私有构造 + 静态工厂）
- 读取方法**阻塞当前线程**等待输入：`readLine(ms?)` 整行（超时/EOF → null）、`readKey(ms?)` 单键（超时/EOF → -1；含 esc/enter/方向键）
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
| `int readKey()` / `int readKey(int timeoutMs)` | **阻塞**读一键（无回显）。`readKey()` 永久等待；`readKey(ms)` 最多等 ms 毫秒，**超时或 EOF 返回 -1**（`-1` 参数 = 永久）。键码：普通字符=ASCII 0..127；`enter=13` `esc=27` `tab=9` `backspace=8`；方向键/功能键 = **256+扫描码**（up=328 down=332 left=331 right=333） |
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
- **POSIX raw 模式（Linux/macOS）**：`readKey` 每次调用自治切换——`tcgetattr` 保存原 termios → 拷贝 → `cfmakeraw` → `tcsetattr` 进入 raw（**无行缓冲、无回显，单字符即时返回**）→ `read(0,1)` 阻塞读键 → **返回前恢复原 termios**（所以 `readLine` 仍走 canonical 行模式，互不干扰）；
  - **ESC 序列**：读到 27 后用 `poll(100ms)` 探测后续字节；`ESC [ A/B/C/D` 映射为 **328/332/333/331**——与 Windows 的 `KEY_UP/DOWN/RIGHT/LEFT` **同一组值**；单按 ESC（100ms 内无后续）返回 27；不完整序列降级为 27；
  - 边界：F 键等长序列（`ESC [ 1 1 ~`）v1 不解析（仅方向键 A-D），残余字节会被下次读取消费；
  - 进程若被强杀（如调试器中断）终端可能残留 raw 模式——执行 `stty sane` 恢复；
- **验证状态**：POSIX 分支已通过 `--target linux` IR 编译 + `clang -c` 目标文件（符号 `tcgetattr/tcsetattr/cfmakeraw/read/poll` 均为 libc 标准）；实际按键运行待 Linux/macOS 环境复测。Windows `_getch`/`fgets` 链接与运行已实证；
- `readKey` 依赖真实终端（管道/重定向下 `_getch` 阻塞、raw read 也无键可读），自动化测试覆盖 `readLine`/单例/KEY 常量，`readKey` 请在真实控制台手测（`test/t_scanner_key.cang`）。


## 中文与多字节输入（readKey 是字节接口）

`readKey()` 每次返回**一个原始字节**，不是字符/码点。中文经输入法上屏后是**多字节序列**，
需要**连续多次调用**才收齐：

| 环境 | "中" 的返回序列 |
|---|---|
| UTF-8 控制台（Win10+ / POSIX） | 3 次：`228, 184, 173` |
| GBK 控制台（代码页 936） | 2 次：`214, 208` |

- 这些值 **>127、不等于任何 `KEY_*`**，监听循环里会落进"普通键"分支——做键码监听时
  **忽略 ≥128 的字节**即可（或先 `> 127` 的连续字节聚合成字符）；
- **想要中文内容请用 `readLine()`**（整行文本语义，编码随控制台，中文原样返回）——
  `readKey` 是给按键/快捷键监听用的；
- **0xE0 前缀判定已做编码保护**：Windows `_getch` 把 `0/0xE0` 当特殊键前缀，而 UTF-8 首字节
  E0-EF、GBK 部分汉字首字节恰为 `0xE0`——现在若前缀后的第二字节 **≥128** 则判定为编码数据
  （扫描码恒 <128）：返回前缀字节、第二字节存入 pending 供下次 `readKey` 取出，**不丢字节**；
- POSIX 分支无此问题（`0x1B` 不可能出现在 UTF-8 序列中，ESC 判定安全）。