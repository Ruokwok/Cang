# Process 本地命令执行

> 返回 [文档索引](../README.md)

## 要点

- `Process.exec(cmd, call[, timeout])` 执行本地命令，按行回调输出，返回退出码；`timeout` 可省略（默认 -1 = 永久等待）
- argv 数组直接执行（**不经过 shell**），天然免疫命令注入
- 超时返回 `-1`（会终止子进程，已回调的输出保留）；**无法启动进程抛出 `Error`**（消息 `cannot start '程序名'`，可 catch，未捕获则打印后 exit 1）
- stderr 继承当前终端（不进回调）

## 用法

```cang
import cang/io/Process

# 命令 + 参数数组；cmd[0] 是可执行文件（PATH 搜索 / 绝对路径均可）
String[] cmd = ["git", "log", "--oneline", "-3"]

Function<Void, String> cb = (line) -> {
    Stdout.println(">> " + line)
}

# timeout 毫秒，可省略；缺省 -1 = 永久等待
try {
    int code = Process.exec(cmd, cb)
    if (code == -1) {
        Stdout.println("超时")
    }
} catch (Error e) {
    Stdout.println(e.message)     # cannot start ''git''
}
```

## 语义

| 情况 | 行为 |
|---|---|
| 正常结束 | 返回子进程退出码；stdout 每行回调一次（含末行无换行内容） |
| 命令不存在 / 无法启动 | 抛出 `Error`：`cannot start '<cmd[0]>'` |
| 超时 | 终止子进程，返回 `-1`；超时前已读到的行仍会回调 |
| 空行 / 行尾 `\r` | 空行正常回调；`\r\n` 自动剥离为行内容 |

## 平台说明

- **Windows**：`CreateProcessW`（UTF-8 参数转 UTF-16），PATH 搜索经 `SearchPathW`，管道读取用 `PeekNamedPipe` 轮询 + `GetTickCount64` 超时
- **POSIX**：`fork + execvp`，`poll(20ms)` 轮询 + `waitpid` 收尸，超时 `SIGKILL`
- 回调运行在 exec 的调用线程内（同步执行）；回调内可正常 `Stdout` 打印与赋值捕获变量
- 输出编码跟随子进程原样字节（Windows 中文控制台子进程若输出 GBK 则回调收到 GBK 字节——期望子进程输出 UTF-8）

## 限制（v1）

- 不提供向子进程 stdin 写数据（管道只接 stdout）
- stderr 不进回调（继承终端）；需要合并两流请用 shell 技巧自行处理
- POSIX 分支通过交叉编译语法验证，运行验证待 Linux 环境补做
