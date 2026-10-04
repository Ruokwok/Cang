# Cang 已知问题与修复指引（debug.md）

> 来源：全量代码审查（LLVMGen 崩溃向量 / 类型与求值一致性 / Parser 与入口 / 内存与线程，四路并行实测取证）。
> 状态图例：🔴 崩溃或错误程序被静默接受 ｜ 🟡 行为不一致 / 体验差 ｜ ⚪ 已知设计取舍 ｜ ✅ 已修复
> 注意：文中行号为审计时快照，后续编辑可能造成偏移，请以符号名/代码片段定位。

---

## 一、运行时裸崩溃（无友好报错，SEGV / SIGFPE）

### 1. ✅ null 字段读 SEGV —— 已修复
- 现象：`Person p = null; Stdout.println(p.age)` 对 null 直接 GEP+load → 写零页崩溃；方法调用有 null 检查，字段读没有。
- 修复：`generateFieldAccess` 在 GEP 前插入与方法调用同款检查（icmp + `emitRuntimeError("Null pointer dereference")`，可被 catch 捕获）。
- 验证：`test/t_nullfield.cang` → exit 1 + `error: Null pointer dereference at :3`。

### 2. ✅ null 字段写 SEGV —— 已修复
- 现象：`p.age = 1`（p 为 null）→ store 到零页。
- 修复：`generateAssign` 字段分支同样插入 null 检查。
- 验证：`test/t_nullfield_w.cang`。

### 3. ✅ 字符串拼接 null SEGV —— 已修复
- 现象：`"a" + s`（s 为 null，如 `f.readText()` 打不开返回 null）→ `strlen(NULL)` 崩溃。
- 修复：`generateStringConcat` 对两侧做 `select` 守卫，null 操作数替换为 `"null"` 字面量，**对齐 Java 语义**（`"a"+null` → `anull`）。
- 验证：`test/t_strnull.cang` → `anull / nullb / nullnull / [null]`。

### 4. ✅ 深递归裸栈溢出 —— 已修复
- 现象：100 万层递归 → `0xC00000FD` 原生栈溢出，进程无任何提示。
- 修复（SP 探测方案，非计数器）：
  - 新增 `@.stack.base = internal thread_local global i64 0`（每线程栈基址）；main 两个入口与 `emitThreadEntry` 开头各写入一次本线程栈顶地址（`emitStackBaseSet`）。
  - `generateFunction` 用户函数入口发射探测（`emitStackOverflowProbe`）：`alloca i8` 取本帧地址 → 与 `base - STACK_BUDGET(960KB)` 比较 → 触线走 `emitFatalError("Stack overflow: recursion too deep")`。
  - 选 SP 探测而非深度计数器的原因：帧大小因函数而异，常数帧数上限无法同时避免"误报"和"先于计数器裸崩"；SP 方案按真实剩余字节判定、无需在每个 `ret` 减计数、天然按线程隔离（thread_local）。
  - 预算标定（Windows 实测）：主线程栈 ~1MB；瘦帧（~50B）原生 20000 层 ✓ / 30000 层裸崩；960KB 预算 → 触发点 ≈ 93.75% 栈容量，任何帧尺寸都先于裸崩触发，误差路径留 64KB。
- 验证：`test/t_stackoverflow.cang`（1 亿层）→ `error: Stack overflow: recursion too deep at :1` 退出 1；`test/t_rec_ok.cang`（10000 层）→ 正常输出退出 0；回归 31/31。
- 教训（复发！）：初版值名 `%stk.ovf.N` 与标签 `stk.ovf.N` 同名撞上 #34 同款 LLVM 命名空间冲突，`function_full`（多函数才触发计数器巧合）才暴露——**值/标签成对生成处前缀必须不同，且要用多函数用例回归**。

### 5. ✅ 循环内局部变量反复 alloca → 栈溢出 —— 已修复
- 现象：循环体内局部变量的 `alloca` 指令位于循环块内，每次迭代重新执行且从不回收；300 万次 `int j = i` → 栈溢出。List for-each 的循环变量 alloca 同样在体内（无需用户声明即增长）。
- 修复（`llvm.stacksave/stackrestore` 方案）：
  - 四个循环发射器（while / for / 数组 for-each / List for-each）统一：**入口 save**（for 在 init 之后，保证 `int i` 初始化变量存活）、**cleanup 标签**（restore + 跳 update/cond）、**出口 restore**（覆盖 break 直跳）；`LoopContext.continueLabel` 改指 cleanup → break/continue 发射逻辑零改动。
  - List for-each 原先**没有 push loopStack** → 循环内 break/continue 是静默空操作，本次顺带补上（t_feloop 断言 break=3）。
- 附带修复 1（同批暴露）：**同函数同名局部变量 IR 冲突**（两个循环各声明 `int j` → 两个 `%v.j` → clang `multiple definition`，既有问题首次被测试命中）→ 四处 VarDecl alloca 名统一加 `tmpCount` 后缀（`%v.j.N`），顺带使作用域遮蔽合法化。
- 附带修复 2：**`emitBranchIfNeeded` 终结符检测过窄**（只认 ret 和"恰好跳向自身 target 的 br"，`while(...){ break }` 这种以 br 结尾的体追加第二终结符）→ 改为检查**最后一行**是否为任意终结符（ret/br/switch/unreachable/indirectbr）。
- 验证：`t_loopalloc`（300 万 while + 200 万 for 循环体声明 + break/continue=9）✓、`t_feloop`（数组/List for-each 各 10 万 + list 内 break=3）✓；回归 33/33。
- 已知残留（可接受）：循环体内 throw 跳出时未 restore（同函数 handler 场景栈帧泄漏一次，有界）；循环内 `return` 无需 restore（函数帧整体释放）。

### 6. 🔴 编译器自身深嵌套 StackOverflowError —— 待修
- 现象：2 万层括号 → 未捕获 `java.lang.StackOverflowError` 裸栈回溯。
- 位置：`Parser.java` 递归下降（约 833 行，表达式递归）。
- 修复指引：顶层 parse 入口 `catch (StackOverflowError e)` → 转成 `CompileError("expression nesting too deep")`（带文件行列）；可选：给递归深度计数、超过阈值主动抛 CompileError（更可控）。

---

## 二、错误程序被静默接受（编译器该拦没拦）

### 7. ✅ 赋值完全不查类型 —— 已修复
- 修复：新增 `assignableTo(target, val)`（类型相等 / 数值拓宽 / 指针互认 / null 字面量仅对指针与 `%CangFunction` 槽）→ 三处入库前检查：`generateAssign` 变量路径、字段路径、`generateVarDecl` 通用路径。实测 `q = "s"` → `Cannot assign String to int`（编译期拦截，不再落到 clang）；回归 40/40。
- 原现象：
- 现象：`int q; q = "s"` 前端 EXIT 0，直到 clang 才报 `'%str.0' but expected 'i32'`（报错无源码定位）。
- 位置：`generateAssign` 变量分支（约 3189）只 `castValue` 不校验。
- 修复指引：仿照 `generateVarDecl`（约 1801）的检查：声明类型（scope 里存的 llvmType/cangType）与右值 `semanticType`/llvmType 比对，不兼容抛 `Cannot assign <X> to <Y> (at line N)`。注意 `castValue` 可能隐式拓宽——只拦"收窄/不相关"（i32→i8*、i8*→i32、double→int 等）。

### 8. 🔴 字面量/收窄无范围检查 —— 待修
- 现象：`int y = 9999999999` 编译链接成功，运行得 `1410065407`（静默环绕）；`byte b = 300` → 44（Java 拒）；`int m = doubleVar` 静默 `fptosi`。
- 位置：`IntLit` codegen（约 2272）无范围检查；`castValue` 浮→整无告警。
- 修复指引：
  - 字面量：按声明目标类型检查（byte: -128..127、int: ±2^31、long: ±2^63），超范围编译错误（Java 语义）；无目标类型时按默认（超 int 用 long，再超报错）。
  - `byte b = 300`：在 var-decl/assign 的类型检查里做常量范围校验。
  - double→int：至少警告或报错（Java 是编译错误，`int m = 3.5` 拒；`int m = (int)3.5` 才行——若 Cang 暂无强转语法，可先只拦字面量、放行变量）。

### 9. ✅ str/String 检查绕过 —— 已修复
- 修复：① 三处调用返回点统一经 `callResultCarriesSemantic` 附 semanticType（String/str/Array/Function——String 方法返回终于带上类型，`str s = String方法` 与 `String s = str方法` 报错，与字面量标准对齐）；② `assignableTo` 兜住 LLVM 类型级错配（`str t = 5` → `Cannot assign int to str`，此前直出非法 IR）。存量测试无 `str` 变量声明，零回归。
- 原现象：
- 现象：方法返回值 `semanticType` 多为 null → `str s = 返回String的方法()`、`String s = 返回str的方法()`、甚至 `str t = 5` 全静默通过（后者直出非法 IR）；而字面量 `str s = "x"` 报错——标准倒挂。
- 位置：`generateVarDecl`（约 1801）条件依赖 `val.semanticType != null`；`generateMethodCall` 返回值（约 3904）只对 Array 附 semanticType。
- 修复指引：
  - 短期（最小）：方法调用返回值按 `fi.returnType` 附 semanticType（String/str/bool/int... 都附）；同步检查既有测试是否依赖"方法返回可赋给 str"的宽松行为。
  - 数值兜底：`str t = 5` 这类 llvmType 不匹配（i32 vs i8*）应硬报错，不依赖 semanticType。

### 10. ✅ 数组写无边界检查（读有写无）—— 已修复
- 修复：写路径补 null 检查 + `idx<0 || idx>=len` 检查（与读路径同款 `emitRuntimeError("Array index out of bounds")`，可 catch）；读路径补 null 检查（原实现先 load 长度头后判界，null 数组直接 SEGV）。实测 `a[99] = 5` → `error: Array index out of bounds` exit 1；`f.list()` 对文件返回 null 后 `names[0]` → `error: Null pointer dereference`；回归 40/40。
- 原现象：
- 现象：`a[99] = 5; a[-1] = 7` 编译通过 → 堆越界写/内存破坏；读路径有 `Array index out of bounds` 检查。
- 位置：`generateArrayAssign`（约 3235-3292）直接 store；读检查模板在约 4856-4870（`icmp slt/ge + emitRuntimeError`）。
- 修复指引：写路径照抄读路径：负数 + `≥ length` 双检 → `emitRuntimeError("Array index out of bounds", line, ok)`。注意先 load 长度头再比较（读路径现有实现里数组为 null 时先 load 崩——顺手把 null 数组检查也补上，读写都要）。

### 11. 🔴 重复定义三套标准不一致 —— 待修
- 现象：重复函数报错（`Duplicate function`）；重复 class `classes.put` 静默覆盖（同文件两个 `class Main` EXIT 0，跨文件同名类字段不同 → 下游误导性 `Unknown field` 且定位错）；重复变量生成两个 `%v.x=alloca`，clang 才报 multiple definition。
- 位置：`LLVMGen` `classes.put`（约 467）、`collectFunction`（约 743，有查重）、var-decl 无查重。
- 修复指引：统一策略——`collectClass` 对已存在的同 fullName 抛 `Duplicate class`；`generateVarDecl` 在 scope.define 前查 `scope.lookup(name) != null`（限同层作用域，考虑 shadowing 规则是否允许内层遮蔽——若允许，只查同层）。

### 12. ✅ 必返分析是 IR 文本匹配 —— 已修复（AST 化 + 终结符保证）
- 修复三件套：① **AST 级 `alwaysReturns` 分析**（Return/Block 任一句/双分支 If/无 break 的 `while(true)` 与 `for(;;)`/全 return 的 switch（保守：每 case+default）/try+全部 catch）替换 IR 文本搜索——`if(c){return 1}` 缺 else 现在友好报 `Function 'bad' must return...`，`while(true){}` 按 Java 语义放行；② **尾部终结符保证**：非 void 且全路径已 return 但文本落在 join 标签上 → 追加 `unreachable`，void → `ret void`；termLast 判定须排除以 `:` 结尾的标签行（`switch.end.3:` 以 switch 开头但不是指令——首版踩坑，clang `expected instruction opcode`）；③ 返回类型：`return true` 进 int/long/byte 现在报 `found 'bool'`（与 Java 对齐，仅返回路径收紧、调用实参仍允许 bool→int），`return null` 报错文案改为 `found 'null'`。
- 验证：`t_retpath`（双分支 return / 全 return switch / while(true) 编译 / bool 返回）`1|2|10|20|0|1|done`；负例 `t_retpath_missing`、`t_retpath_bool` 友好报错；回归 44/44。
- 原现象（保留）：
- 现象：对整个函数 IR 搜 `"\n  ret "`。漏报：`func int f(bool c){ if(c){ return 1 } }`、switch 全 return → 编译过但产无终止符 endLabel → clang `expected instruction opcode`；误报：`func int w(){ while(true){} }` 被拒（Java 合法）。
- 位置：必返检查（约 1236-1259）；无终止符 label 发射点约 1893/1965。
- 修复指引：
  - 把"文本搜 ret"换成 AST 级控制流分析：`alwaysReturns(block)` 递归——Return 恒真；Block 看最后非空语句；If 两支都真且有 else；Switch 全 case+default 都真；While/For 循环体真且条件非常量 true 时假（`while(true)` 无 break → 真）；Try 看 try 块。
  - 发射侧兜底：函数结束时若当前块还没有 terminator，按返回类型补默认 ret 或报"missing return"，保证 IR 永远有终结符（治标先于治本）。

### 13. ✅ return 在 try 内 → 跳过 finally + 无效 IR —— 已修复（finally 五出口统一，见第 20 条机制）
- 修复后：`try { return } finally { ... }` 先跑 finally 再 ret（值表达式先于 finally 求值）；跳转后开 `jexit.dead.N` 死块承接后续语句，消灭空块无终结符。t_fin_return 实测 `fin|1` 顺序正确。

---

## 三、求值语义与 Java 相悖

### 14. ✅ `&&` / `||` 非短路 —— 已修复（短路 phi；t_shortcircuit + 回归 37/37）
- 现象：两侧全部生成后再 `and i1`：`f && side()` 中 side 无条件执行；**`x != null && x.m()` 会崩**（Java 保护惯用法完全失效）。
- 位置：`generateBinary`（约 2774-2791）。
- 修复指引：标准短路 IR——左值算完 → `br` 到 rhs 块 / 短路块 → 两块汇合到 `phi i1`：
  ```
  lhs → br i1 %l, label %rhs, label %short   (&&; || 反向)
  rhs: %r = <生成右操作数> → br label %join
  short: br label %join
  join: %res = phi i1 [ false/true, %short ], [ %r, %rhs ]
  ```
  注意：右操作数生成可能发射自己的 label（如嵌套短路、方法调用 null 检查），phi 前驱必须用生成后实际的"当前块"——用 `body` 追加位置构造标签名即可。三元 `?:`（约 3294）同样处理。

### 15. ✅ 接收者/迭代对象重复求值 —— 已修复（generateMethodCall objCache 单次生成 + for-each iterable 复用）
- 现象：`generateMethodCall` 为 List 探测、length 探测、正式分派对 `node.object` 各 `generateExpr` 一次（约 3731/3739/3818）→ `new File("x").getName()` 构造两次、`c.inc().show()` 中 inc 执行两次（副作用翻倍）；`generateForEach` 对 iterable 生成两次（约 2111+2127）。
- 修复指引：
  - 方法调用：先 `LLVMValue objVal = generateExpr(node.object)` **一次**，List/length 探测改用缓存值判断（`listValue = objVal`），再进入正式路径。注意探测失败路径不能把已生成的 IR 作废（生成是追加式的，值缓存即可）。
  - for-each：同样先算 iterable 缓存复用。
  - 三元两支都生成（约 3294）：同 14 的分支方案。

### 16. ✅ 三元两支求值、foreach iterable 双生成 —— 已修复（三元改分支+phi，仅命中支执行；见 14/15）

---

## 四、内存（`--no-gc` 必现泄漏 + UAF）

### 17. ✅ readLines 行副本不可 free —— 已修复（临时缓冲即释放 + 放开 String free）
- 修复：①`cang.f.readlines` 的整文件 `%text` 缓冲在 `fin:` 返回前 `free`（行副本已 strndup，缓冲是临时物非交付物）；②**放开 `free` String/str**（原编译期拒绝删除）——用户可逐个释放堆串（readLines 行）；文档同步：只 free 堆串，`--no-gc` 下字面量 free 会崩（GC 模式 GC_free 对常量为 no-op）。
- 实测 t_free_objs：`24`（readLines 行数正常）+ `string and lines freed`；回归 67/67（file_std 覆盖 readLines 改动）。

### 18. ✅ List free 漏内部 `_data` 缓冲 —— 已修复（对象 free 连带 Array 字段，一层）
- 修复：`generateFree` 对类对象按 `ClassInfo.fieldTypes` 遍历，**Array 字段**逐个 null 检查后 free，再 free 对象本体。覆盖纯 Cang List 的 `T[] data` 与 Dict 的 `keys[]`/`vals[]`（字段类型 `Array<...>` 单态后仍匹配）。v1 一层：String 字段（常量池风险）与嵌套对象字段不碰，文档注明。
- 实测 t_free_objs：`list freed` / `dict freed`；回归 67/67。

### 19. 🟡 freedVars 按名全局污染 + 别名 UAF —— 待修
- 现象：`g(){ free x }` 后 `h()` 同名 `x` 编译报 `Use of freed variable`（freedVars 是函数间共享的 Set）；`b = a; free a; print(b[0])` 编译通过（别名不查，UAF）。
- 位置：`freedVars`（约 106 声明、1663 加入、2725 检查）。
- 修复指引：
  - freedVars 改为**按函数作用域**：`generateFunction` 入口快照/出口恢复（或直接 clear——函数间本就不应共享）。
  - 别名追踪成本高（v1）：至少文档声明"free 后不得使用任何别名"；可选做保守版本——free 时把"同 llvmType 的指针变量"标记可疑（过度保守会误报，需权衡）。首版建议只修作用域污染。

---

## 五、try / catch / finally 语义缺口

### 20. ✅ try 内 break/continue 跳过 finally + 双终结符 IR —— 已修复（finally 五出口统一）
- **统一机制（13/20/21 同批落地）**：`finallyStack`（FinallyCtx：finallyBlock + inTryBody）在 generateTry 进入时 push、共享 finally 生成前 remove；出口点（return / break / continue / catch 内 throw）调 `inlineFinallyLayers()` 按**内→外**逐层内联生成 finally 体（每层只激活外层栈，finally 内 return 正确链到外层）；某层终结（finally 自己 return/throw）则吞掉原跳转；内联后统一开 `jexit.dead.N` 死块承接后续语句（治双终结符/空块无终结）。**finally 本体正常路径不受影响**（after 标签照旧），无 finally 时三出口逐字节保持原行为。
- 实测 t_fin_return：continue/break 每层 finally 全跑（f0|f1|f2|f3），回归 60/60。
### 21. ✅ catch 内 throw 跳过 finally —— 已修复（并入 20 的内联机制）
- 机制：catch 阶段 FinallyCtx.inTryBody=false → generateThrow 检测后先 inlineFinallyLayers 再 br 外层 handler；try 体内的普通 throw 不内联（走 handler→after 正常跑 finally）。t_fin_rethrow 实测 `outer caught → outer fin` 顺序正确。
### 13（并入）return 在 try 内跳过 finally + 空块无终结符 —— 约 2223/1501/1237。

- **统一修复指引（三条同一机制）**：实现"finally 复制/跳板"——
  1. break/continue/return/throw 若位于 try 内：先跳到 finally 执行块，finally 完成后再 br 到真实目标（用一个"pending 目标"局部变量或按目标复制 finally 体）。
  2. toy 级简化方案：把 finally 体在每个退出点**内联重复生成**（代码膨胀但改动小、无新 IR 机制）——break/continue/return/正常落空/catch-throw 五个出口各插一份。
  3. 每个出口点同时保证"当前块有且仅有一个终结符"（emit 后续代码前检查/切块），治 13 的无效 IR。

---

## 六、语句分隔不看行号（Parser 设计层）

### 22. 🔴 分号可选但完全不比较行号 —— 待修（动静最大，改前需设计）
- 现象：语句靠"下一个 token 不能续写表达式"分隔，从不看 NEWLINE：`x = y⏎-1` 被静默解析成 `x = y - 1`；`a = bb⏎(3).foo()` 跨行链式 → 误导报错且定位 1:1；`var t = s⏎[1,2]` 解析错乱。
- 位置：`Parser.java` 语句循环（约 1076）只吃一个 `;`；无 NEWLINE token；token 有 `.line` 字段但未用于分隔判断。
- 修复指引（推荐路径）：
  1. Lexer 把换行保留为 `NEWLINE` token（或记录 token.line 即可，不需真 token）。
  2. 表达式续写规则收紧为：**仅当下一 token 与当前 token 不同行时**才允许 `(`/`[`/`.`/一元减 续写（同行的 `a\n-1` 拆开；跨行 `x\n(1)` 拆开——代价是 fluent 风格跨行链式调用需行尾加 `.` 或括号包裹，需在 doc.md 明确）。
  3. 语句结束判定：`;` 或 NEWLINE 或 `}`。
  4. 分两步落地：先加 NEWLINE token + 只在语句边界消费（不改续写规则）跑全量回归，再收紧续写。
- 回归要求：现有 23 项 + 全部 z_ 样例（修复时重建）。

---

## 七、体验 / 一致性问题

### 23. ✅ `+=` 词法有 Parser 不认 —— 已修复（parseAssignment 识别四复合算符 desugar）`x op= e` → `x = x e`（+= -= *= /= 全支持；标识符/字段/数组元素/循环步进实测七断言全对）。v1 已知限制：目标 AST 双侧各求值一次（有副作用的下标如 a[f()] 会跑两次，代码注释注明，后续可改临时变量）。t_compound；回归 85/85

### 24. ✅ switch —— 已修复（三子项）①重复 case 编译期报 `Duplicate case value '1'` 并指向 case 行（字面量查重，literalCaseKey 带类型前缀防 1 vs "1" 混淆，显示时去前缀）；②case 类型不匹配报错行号由 stmt.line 改 sc.line（实测 case 行 4:1 而非 switch 行）；③Increment 校验补 `(at line N)`（target.line——for 头等非 generateStmt 语境不再落 1:1，实测 a[0]++ 指 3:1）。t_switch 正例 + 3 负例；回归 91/91

### 25. ✅ 数字字面量 —— 已修复（四子项）①指数 `1e10`/`2.5E-3`/`1E+12`（e 后无数字回退保安全）；②前导点 `.5`；③`3.` 按 Java 规则算浮点（后随第二点不吞）；④未知转义 `\q` 报 `Unknown escape sequence`（单双引号与反引号三处，转义换行续行保留）。配套：LLVM 侧 normalizeFloatText 保证浮点常量含小数点（`1e10`→`1.0E10`，否则 clang 按整数解析报错）。t_num_lit 九断言 + t_num_escape 负例；回归 87/87

### 26. ✅ 词法错误吞文件 + 行号偏移 —— 已修复（未闭合立即报 + 换行计行）
- 修复：①未闭合 `"`/`'`/反引号 → 在**开引号位置**立即抛 `Unterminated string`（不再吞到 EOF）；②未闭合 `#*` → 开位置抛 `Unterminated comment`；③普通/转义/反引号字符串内换行均 `line++/column=1`（此前引用串不计行 → 后续全偏）；④反引号版顺带修正换行推进顺序。
- 实测：t_lex_unterm_str 2:16 / t_lex_unterm_cmt 2:1 / t_lex_unterm_tick 2:9 / t_lex_lineshift（跨行串后故意错）**4:9 正确**；多行串内容回归正常；全量 65/65。

### 27. ✅ 报错质量 —— 已修复（三件套）
- ①**codegen 无行号异常**：`generateStmt` 入口记 `lastStmtLine`，`generate(program)` 统一 wrapper——RuntimeException 消息不含 `at line` 时自动追加 `(at line N)`（CompileError 原样放行）；一次覆盖全部深层无定位异常。
- ②**import 文件词法错 → Internal error + 栈**：根因是 `loadImport` 外层 try 只有 finally、`tokenize()` 不在内层 catch 内 → 单独 try-catch `wrapError`。实测 Broken.cang 未闭合串现在渲染为标准诊断（文件:3:25 + 源行 + 脱字符）。
- ③**Lexer `&`/`|` 列号 +1**：advance 后 error 用当前列 → 新增 `errorAt(startLine, startCol, msg)` 用 token 起始列。实测 `int x = 1 & 2` 报 **2:11**（原 2:12）。
- 回归 65/65（全部错误例渲染零变化）。

### 28. ✅ CLI —— 已修复（usage + 友好错误全路径）①无参/首参为 flag → usage + exit 1；②所有带值选项（--target/--arch/--clang/--gcc/--gc-lib）经 requireValue 缺值报 `requires a value` 而非裸栈；③未知 flag 报 `unknown option`；④未知 target 值报 `unknown target ... expected windows|linux|macos`（原裸 exit 1）；⑤printUsage 列出全部选项。五错误场景+正向手验全过（错误类不入 harness），回归 87/87

### 29. ✅ `Thread.spawn` 无 join 兜底 —— 随 spawn 移除而关闭（对象式需显式 join，thread{} 有自动 join 收尾） —— `thread{}` 有全局句柄 join 收尾，`Thread.spawn` 没有 → main 返回即杀线程（实测 5 万行输出只剩 1 行）。修复：spawn 的句柄也进全局 join 数组（或文档明确"必须手动 join"）

### 30. 🟡 `System.exit` 跳过收尾 —— 直接 `exit()`：跳过 thread{} join 与后续 finally。修复指引：改为设置退出码 → 走统一收尾路径（join 全部句柄 → exit）；finally 语义可暂不承诺

### 31. 🟡 `Stdout.print(对象)` 输出乱码 —— 对 `%CangX*` 走固定 `%s` 直接打结构体字节，可能越界读。修复：对象类型改打 `<ClassName@addr>`（取 className 标签 + ptrtoint）或调用 toString()（若有）。用户串含 `%` 是安全的（格式串为编译器常量）

### 32. ✅ 自由/静态函数返回数组链式 `.length()` 报错 —— 已修复
- 原现象：实例调用路径附了 Array semanticType，独立/静态调用路径没附 → `Unknown method: i8*.length`。
- 修复（lambda 捕获批次顺带）：三处方法/函数调用返回点统一 `retSem = (isArraySemanticType || isFunctionType) ? returnType : null` —— 同时解决"方法返回 `Function<...>` 赋值报 `found object`"（checkFunctionValue 依赖 semanticType）

### 33. 🟡 `free f`（File 对象）不 free `path` 字段 —— path 为堆串（拼接而来）时 `--no-gc` 必漏。修复：free 对象时按 ClassInfo.fieldTypes 遍历 free 指针字段（字符串字段可 free；注意递归字段/环——v1 只 free 一层 String 字段，文档注明）。

---

## 八、审查过程中附带发现（本批已修 / 新增）

### 34. ✅ LLVM 值/标签命名空间冲突（本批已修）
- 现象：除零检查初版值名 `%divzero.N` 与标签 `divzero.N` 在 LLVM 是同一命名空间 → `'%divzero.2' is not a basic block`（靠两个计数器数值错开才侥幸通过，撞上必炸）。
- 修复：值名前缀改 `%iszero.`。**经验：所有"值 + 标签"成对生成处，值/标签前缀必须不同**。

### 35. ✅ `extractClassName("%Inner**")` 只剥一个 `*`（本批已修）
- 现象：类字段链 `p.inner.value` 永远编译失败 `Unknown class: Inner*`（`generateExprForPtr` 字段分支返回 `toLLVMType(ft)+"*"` 双星类型，下游剥星不彻底）。
- 修复：改为循环剥掉全部尾部 `*`。

### 36. ✅ 语句挂在非入口类下被静默丢弃 —— 已修复（编译期报错）
- 原现象：入口 = 文件第一个 class；语句写在后续 class 头后 → 归属非入口类 → entryClass 存在时 mainStatements 永不生成 → 静默丢代码。
- 修复：member loop 非入口类的顶层语句直接抛 `statements must appear right after the entry class (first class in file); class 'X' is not the entry class (at line N)`（指向语句行）。FuncDecl/FieldDecl（方法/字段定义）不受影响。
- 测试：t_stmt_lost 负例（P 第一、Main 后语句 → 报错 3:1）；回归 61/61（多类正例 arr_class 等零误伤）。

### 37. ✅ 缺运行时尺寸数组分配（纯 Cang 动态数组的根本前提）—— 已修复
- 原现象：`T[N]` 只收正整型字面量，无 `new T[expr]` → 纯 Cang 无法实现可扩容动态数组。
- 修复：新增 **`new T[sizeExpr]` 数组创建表达式**（AST `NewArrayExpr`，字段名 `type` 使单态化可替换其中的 T）：
  - Parser `new` 分支先解析 base type 再判 `[`（类创建/数组创建分流）；
  - codegen：负数运行时检查（`Negative array size`，可 catch）→ `allocFn(8 + n*elemBytes)` → 长度头存 n → `memset` 零填充（Java `new int[8]` 语义）→ 返回带 `Array<T>` 语义类型（链式 `.length()` 可用）。
- 验证：`new int[cap]` → `8 | 42 | 0 | 100000`（零填充/百万级分配）✓；回归 33/33。

### 38. ✅ 字段数组的元素类型追踪缺失 —— 已修复
- 原现象：`elemCangOf` 不识别 FieldAccessExpr → `this.data[i]` 回落 i32，String/对象元素报 `Array element type mismatch`；字段 `.length()` 报 `Unknown method: i8*.length`。
- 修复三件套：
  1. `elemCangOf` 增加 FieldAccess 分支：新增纯查询 `pureClassName`（ThisExpr/Identifier/嵌套 FieldAccess 递归解析接收者类）→ fieldIndices 反查 `Array<T>` 内层元素类型；
  2. `generateFieldAccess` 对 `Array<...>` 类型字段附 semanticType → 字段链式 `.length()` 分派成功；
  3. 单态化 `rewriteAstTypes` 支持**复合类型替换**（`Array<T>` → `Array<int>`，原实现只替换整串相等）——泛型 `T[]` 字段的前置。
- 验证：String 元素字段数组 `this.data[i]` 读写 ✓（poc_strlist `hello`）、泛型 `Box<int>` 字段数组全链 ✓（poc_mono `7|4|99`）；回归 33/33。

### 39. ✅ 带命名空间类的私有 `_` 方法/字段检查失败 —— 已修复
- 原现象：`class ArrayList<T>`（namespace cang/lang）内调用 `this._grow()` 报 `Method '_grow' is private and cannot be accessed from 'cang_lang_ArrayList_int'`——`isSameOrParentClass` 用 `currentClassName`（fullName）与 `ownerClass.simpleName` 直接字符串等值比较，命名空间类两侧形态不一致。私有字段检查（generateFieldAccess）同病，非命名空间类（fullName==simpleName）从未触发。
- 修复：`isSameOrParentClass` 入口把 currentClass 经 `classes` 表归一化为 simpleName 再比较/走父链（单点修复，方法与字段两处私有检查同时覆盖）。
- 验证：`t_arraylist` 的 `_grow` 私有调用 ✓；回归 35/35。

### 40. ✅ 泛型参数默认值 `null` 不随具体类型适配 —— 已修复（单态化处换零值）
- 修复：`rewriteAstTypes` 遍历到 Parameter 时，若 defaultValue 为 NullLit 且 type==被替换参数名，按 concrete 换零值——bool→`BoolLit(false)`，int/byte/long/float/double→`IntLit("0")`（调用点 castValue 统一转目标类型），引用 concrete（String/类/数组/Function）保持 null。检查在类型字符串替换**之前**（此时 p.type 仍是 T）。
- 实测 t_generic_null：`0|0|0|0.000000|0|null|5`（五值类型零值 + String null + 显式参）；回归 66/66。

### 41. ✅ List 内建 `%CangList` → 纯 Cang 实现迁移（用户定名，参考 ArrayList）—— 已完成
- 决策：`List` 正名回归 `cang/lang/List`（替换原 native 壳文件），实现存储/扩容参考 java.util.ArrayList；内建分派与纯 Cang 实现同名无法共存，故**移除内建**。
- 变更清单：
  - `stdlib/cang/lang/List.cang`：纯 Cang `class List<T>(T[] data = new T[10], int size = 0)`，11 个方法（size/isEmpty/get/set/add/insertAt/remove/contains/indexOf/clear/toArray + 私有 `_grow`）；**add 返回新元素下标**（与历史行为一致），remove 按下标返回元素；越界抛 Error 带行号。
  - LLVMGen 移除内建路径：generateMethodCall 的 List 分派、generateNew 的 %CangList 分配、generateVarDecl 的 List 归一化、toLLVMType 的 `%CangList*` 分支、单态化对 List 的两处豁免（现与普通泛型类一致走多类型特化）。
  - `generateListForEach` 重写为**方法调用式迭代**（`size()` 快照 + `get(i)`，与 ABI 无关，保留 break/continue + stacksave 回收）。
  - 符号注意：for-each 必须用 `ci.fullName`（`cang_lang_List_int.get`）而非 semanticType 短名——首次实现踩坑实测。
- 兼容性：list_demo/list_int/list_methods 输出与内建版**逐字节一致**（含 `add` 返回下标、`remove` 返回元素）；`import cang/lang/List` 测试原本就有；t_feloop 补了 import。
- 残留（记录不修）：`%CangList` 类型定义与 `generateListMethod` 等成为死代码（保留无害）；`new List<>(单元素)` 旧便捷构造不再支持（测试未用）；`free l` 不释放 `_data` 缓冲（同 #18，GC 默认模式无感）；解析器泛型实参只收 base type，`List<int[]>` 不支持（既有）。

---

## 九、已知且已文档化的限制（⚪ 不再展开）

- String 的 native 实例方法无分发（`Unknown method: i8*.toUpper`）——i8* 接收者无 semanticType，独立任务待修。
- 异常只支持同函数 handler，跨函数 propagate / landingpad 不在 v1。
- `--no-gc` 模式下字符串类返回值不可 free（`generateFree` 拒绝 String/str）。
- Linux/macOS 目标仅 IR 级验证（无 WSL/macOS 主机）；macOS `compileMacOS` 是 stub。
- `Math.random()` 固定种子（跨平台确定性是特性，文档已注明）。
- lambda 已支持按值捕获外部变量与 this（本批落地，doc.md 9.3 已更新）；`thread{}` 仍无捕获（v1 设计）；Thread 参数类型白名单（无对象/数组跨线程）。
- 单类文件 + class 位置决定函数语义（class 前 = 全局函数，class 后 = 方法）。
- 并发边角：`@cang.current.error` 全局非线程局部、print 无锁、`thread{}` 单全局句柄槽并发覆盖丢 join、双 join 标志非原子——等 Channel/Mutex 任务一并处理。
- 关键字全保留字（`var/free` 等不可作标识符）。

---

## 修复优先级建议（滚动更新）

1. ~~1–3 裸崩溃三件套~~ ✅
2. ~~4 深递归裸栈溢出~~ ✅（SP 探测 + thread_local 栈基址，见第 4 条）
3. ~~5 循环 alloca 栈溢出~~ ✅（stacksave/stackrestore，见第 5 条）
4. ~~37 + 38 运行时数组分配 + 字段数组元素追踪~~ ✅（见第 37/38 条）
5. ~~泛型多类型单态化（T71）+ 纯 Cang ArrayList（T72）~~ ✅：多具体类型每类一特化（重解析克隆 + 全键映射 + 钻石推断 + 未用模板跳过）；`stdlib/cang/lang/ArrayList.cang` 11 个方法，`t_arraylist` 29 断言（int+String 双类型同程序）全过，套件 35/35
6. ~~14–16 短路求值 + 重复求值~~ ✅（短路 phi / 三元分支+phi / objCache+iterable 复用，见第 14/15/16 条）
7. ~~10 + 7 + 9~~ ✅（assignableTo 三处检查 / callResultCarriesSemantic / 数组写 null+越界，见第 7/9/10 条）
8. ~~13/20/21~~ ✅（finally 五出口统一——finallyStack + inlineFinallyLayers 内联 + jexit.dead 死块，见第 13/20/21 条）
9. ~~36~~ ✅ → ~~26/27~~ ✅ → ~~40~~ ✅（见第 40 条）
10. ~~17/18~~ ✅（free 缺口——readLines 缓冲即释放 + 放开 String free + 对象 Array 字段连带 free，见第 17/18 条）→ 22（语句分隔，最后动，需设计评审）
11. 其余 🟡 按批次清理
