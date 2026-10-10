# IDE 接口（cc.ruok.cang.api）

> 返回 [文档索引](README.md)

## 要点

- 面向外部 Cang IDE / 编辑器插件的**只读语言服务**（方法列表、签名提示、属性、代码补全、语法诊断）
- 复用编译器自身的 Lexer/Parser 建索引——与真实语言行为一致
- 容忍未写完的代码：补全与参数提示按行扫描作用域，不要求整缓冲可解析

> **Cang 是玩具/教学级语言，本接口同样定位为实验性，不保证跨版本兼容。**

## 模型类

| 类 | 说明 |
|---|---|
| `Element` | 基类：`getName()`、`getLine()`（转到定义用） |
| `Class` | 类条目：父类、命名空间、是否抽象；`getStaticFunctions()` / `getMemberFunctions()` / `getVariables()`（实例字段）/ `getStaticVariables()` / `getConstructors()`（init/_init 重载） |
| `Function` | 方法 / 全局函数 / 构造：`getReturnType()`、`getParams()`、`getSignature()`（如 `int add(int a, int b = 1)`）、`isStatic/isMember/isFinal/isNative/isAbstract/isConstructor` |
| `Variable` | 字段 / 参数 / 局部变量：`getType()`、`isStatic/isFinal`、`getDefaultValue()`、`getDetail()` |
| `Completion` | 补全候选项：`getKind()`（KEYWORD/CLASS/METHOD/FIELD/VARIABLE/FUNCTION）、`getDetail()`、`getInsertText()`、`isStatic()` |
| `Diagnostic` | 语法诊断：`getMessage()/getLine()/getColumn()` |
| `SignatureHelp` | 调用处参数提示：`getOverloads()`、`getActive()`、`getActiveParameter()` |

## CangApi 方法总览

### 索引与源码根

```java
CangApi.addSourceRoot("/path/to/project/src");  // 目录（递归 *.cang）或单文件，可多次
CangApi.reset();                                 // 换工程 / 热重载：清空根与索引
CangApi.isDirty();                               // 索引是否待重建
```

- **自动加载 stdlib**：每次构建索引时尝试工作目录下的 `stdlib/` 与 `../stdlib/`（与编译器前两级一致），无需手动添加。
- 索引惰性构建、线程安全（首次查询时建立，`addSourceRoot/reset` 后自动失效）。
- 解析失败的文件不入索引（坏文件的诊断由 `check()` 负责）。

### 类与成员查询（含继承链）

```java
List<Class> all = CangApi.getClasses();          // 全部类，按名排序
Class c = CangApi.getClass("List");              // 简单名 / 全限定名（a_util_Logger）/ 路径均可
List<String> kw = CangApi.getKeywords();         // 语言关键字（补全）
List<Function> globals = CangApi.getGlobalFunctions();

// 成员查询自动沿父类链收集（父类在前）；静态字段不继承
CangApi.getFunctions("List");          // 全部方法 + init 重载
CangApi.getMemberFunctions("List");    // 成员（实例）方法
CangApi.getStaticFunctions("Math");    // 静态方法
CangApi.getFields("File");             // 实例字段（含继承）
CangApi.getStaticFields("Scanner");    // 本类静态字段
CangApi.findFunctions("area");         // 按名找全工程重载（悬浮 / 参数提示）
CangApi.findFunctions("area", "Circle"); // 限定在 Circle 继承链内
```

### 代码补全

```java
List<Completion> list = CangApi.complete(buffer, caretOffset);
```

- **点前**（`list.` / `Math.` / `f.`）：按接收者类型给成员——
  接收者可为**局部变量/参数**（`var x = new Foo()`、`String s`、参数表）、**类名**（给静态成员）、`this`（当前类）、**字段**；数组给 `length()`。
- **非点位**：局部变量 → 当前类成员 → 全局函数 → 类名 → 关键字（按序、前缀过滤）。
- 作用域扫描是**行级**的：缓冲没写完也能补全。

### 方法提示（悬浮 / 参数提示）

```java
// 悬浮：给名字取候选与签名
for (Function f : CangApi.findFunctions("add")) {
    hover.setSignature(f.getSignature());      // "int add(T value)"
}

// 调用处参数提示（自动识别重载、接收者、new 构造、当前参数下标）
SignatureHelp h = CangApi.getSignatureHelp(buffer, caretOffset);
h.getOverloads();        // 候选重载
h.getActive();           // 激活的重载（按参数个数推断，多义时 -1）
h.getActiveParameter();  // 光标在第几个参数（0 起）
```

### 语法诊断（波浪线）

```java
List<Diagnostic> ds = CangApi.check(buffer);   // 空 = 语法正确
// 消息为编译器原文（短错误风格），如 "statements are not allowed in the class body"
```

## 编译 API（in-process）

IDE 内直接调用编译器——**不 spawn 子进程、不 System.exit**，结果全部结构化返回：

```java
CompileResult r = CangApi.compile("src/Main.cang",
        new CompileOptions()
            .outputName("myapp")     // 可执行文件名（.exe 可省）；null = 与源文件同名
            .noLink(false)           // true = 只生成 .ll
            .target("windows")       // windows | linux | macos
            .gc(true)                // Boehm GC（默认开）
            .quiet(true));           // 默认静默：过程输出不进 stdout

if (r.isSuccess()) {
    r.getExeFile();                  // "target/myapp.exe"
    r.getLlFile();                   // "target/myapp.ll"
    r.getIrText();                   // LLVM IR 文本（编辑器预览）
} else {
    for (Diagnostic d : r.getDiagnostics()) {
        // 源码错误带行列（如 7:1）；构建/链接错误为 0:0
        d.getLine(); d.getColumn(); d.getMessage();
    }
    r.getBuildOutput();              // 链接失败时的 clang/gcc 原文（可放 IDE 输出面板）
}
```

- **单实例串行**：编译器内部是进程级状态，调用在内部锁上排队（不要指望并行两路编译提速）；
- **GC 库定位与 cwd 无关**：`.gcLibDir(目录)` 会在 `<目录>/lib/` 下按平台名查找
  （如 `window_amd64_libgc.a`，另认 `<目录>/lib/libgc.a`、`<目录>/libgc.a` 与文件直传）——
  IDE 进程的 cwd 不是编译器安装目录时**必须传**，否则报
  `Boehm GC static library not found for windows target`；CLI 的 `--gc-lib <目录>` 同语义；
- **诊断**：词法/语法/语义错误 = `CompileError` 原文 + 真实行列；`LinkFailed`（工具链失败、目标未实现等）= 短消息 + `buildOutput`；
- **静默默认**：`quiet(true)` 时进度（[1/3]…、[OK] Compiled）走 null sink；`quiet(false)` 与 CLI 完全一致；
- **未保存缓冲**：编译以磁盘文件为单位（import 按路径解析）——先写临时文件再调 `compile(tempPath)`。
## 编辑器集成流程（伪代码）

```text
onDidOpen / onDidChange(root):
    CangApi.addSourceRoot(root)        // 首次；之后源文件改动索引可 reset() 重建

onDidChangeDocument(buf):
    diagnostics = CangApi.check(buf.text)          -> 波浪线
onComplete(buf, offset):
    return CangApi.complete(buf.text, offset)      -> 补全列表（kind 决定图标）
onHover(buf, offset):
    name = 词法取 offset 处标识符
    fs = CangApi.findFunctions(name)
    return fs.map(getSignature)                    -> 悬浮卡片
onSignatureHelp(buf, offset):
    return CangApi.getSignatureHelp(buf.text, offset) -> 参数提示
on_goto_definition(elem):
    elem.getLine()                                -> 打开源文件对应行
```

## 限制（第一版）

- 类型解析只做**一级**：接收者需是标识符 / `this` / 类名；`foo().bar` 链式调用不推导；
- 补全作用域 = 行扫描（参数 + `var`/类型声明 + `for` 循环变量），不等价编译器的完整语义；
- 无文档注释提取（悬浮只有签名）；只读——不做重命名 / 格式化；
- 泛型按基名索引（`List<int>` 归约为 `List`），不展开单态化实例。

---
