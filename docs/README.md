# Cang 文档索引

> Cang 是一门实验性 / 教学向语言，**非生产就绪**。本目录由原 `doc.md` 按学习路线拆分而成：语言主题一章一文件，标准库每个类一个文件，全部带要点与示例代码。

## 学习路线（语言）

按顺序阅读：

| # | 文档 | 覆盖内容 |
|---|------|----------|
| 01 | [快速开始](01-快速开始.md) | 最小程序、构建编译器、编译与运行、完整示例 |
| 02 | [项目结构与入口](02-项目结构与入口.md) | class 前后布局、import/as 别名、分号、注释、目录结构 |
| 03 | [基本类型](03-基本类型.md) | byte/int/long/float/double/bool、字面量增强、null |
| 04 | [字符串 string 与 String](04-字符串.md) | str 与 String 分层、装箱、内容比较、常量池 |
| 05 | [变量与类型推导](05-变量与类型推导.md) | 显式声明、var、作用域 |
| 06 | [运算符](06-运算符.md) | 算术/比较/逻辑、三目、短路求值 |
| 07 | [条件语句](07-条件语句.md) | if/else、switch/case/default |
| 08 | [循环](08-循环.md) | while、for、break/continue、for-each |
| 09 | [函数与方法](09-函数与方法.md) | 定义、默认参数、return 检查、方法重载 |
| 10 | [函数对象与 lambda](10-函数对象与lambda.md) | Function 类型、lambda 捕获、方法引用 |
| 11 | [类与对象](11-类与对象.md) | 构造即参数、实例/静态方法、继承声明 |
| 12 | [泛型](12-泛型.md) | 单态化、多类型参数 Pair<K, V>、diamond |
| 13 | [数组](13-数组.md) | T[]/T[N]、字面量、运行时长度、越界检查 |
| 14 | [继承与多态](14-继承与多态.md) | Object 根类、动态分派、like、final |
| 15 | [异常处理](15-异常处理.md) | try/catch/finally、throw、运行时错误 |
| 16 | [内存管理](16-内存管理.md) | Boehm GC 默认、free、--no-gc |
| 17 | [编译与当前限制](17-编译与当前限制.md) | native、已知限制、编译流程 |

## 标准库（libs/，每个类一个文件）

| 文档 | 类 / 模块 | 说明 |
|------|-----------|------|
| [Stdout.md](libs/Stdout.md) | `Stdout` | 标准输出 print/println（内建） |
| [Stderr.md](libs/Stderr.md) | `Stderr` | 标准错误输出（与 Stdout 同签名） |
| [Math.md](libs/Math.md) | `Math` | 数学常量与函数、随机数 |
| [System.md](libs/System.md) | `System` | ARGS、平台常量、exit、环境变量、时间 |
| [String.md](libs/String.md) | `String` | 字符串类 API（length/substring/查找/大小写） |
| [List.md](libs/List.md) | `List<T>` | 泛型动态数组（参考 ArrayList） |
| [Dict.md](libs/Dict.md) | `Dict<K, V>` | 泛型 KV 映射（参考 HashMap） |
| [Thread.md](libs/Thread.md) | `Thread<T>` | 对象式 task/start/join（spawn 已移除）、thread{} |
| [File.md](libs/File.md) | `cang/io/File` | 文件路径、状态、目录、文本读写 |
| [Function.md](libs/Function.md) | `Function<R,...>` | 函数对象类型声明 |
| [Error.md](libs/Error.md) | `Error` | 异常基类、message/stack |
| [Object.md](libs/Object.md) | `Object` | 所有类的隐式根类 |

## 标准库源码位置

```text
stdlib/cang/lang/    # 自动查找，也可显式 import（支持 as 别名）
stdlib/cang/io/      # 需显式 import cang/io/File
```

当前清单：`Object`、`Stdout`、`Stderr`、`String`、`Math`、`System`、`Function`、`Void`、`Error`、`Thread`、`List`、`Dict`、`cang/io/File`。
