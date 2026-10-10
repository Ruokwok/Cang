package cc.ruok.cang.api;

import cc.ruok.cang.Cang;
import cc.ruok.cang.lexer.Lexer;
import cc.ruok.cang.lexer.TokenType;
import cc.ruok.cang.parser.AST;
import cc.ruok.cang.parser.Parser;
import cc.ruok.cang.util.CompileError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cang IDE 语言服务门面（只读，复用编译器的 Lexer/Parser）。
 *
 * <p>典型用法：
 * <pre>
 *   CangApi.addSourceRoot("/path/to/project/src");   // 项目源码根（可多次）
 *   // 自动加载工作目录下的 stdlib/（含 ../stdlib）
 *
 *   List&lt;Class&gt; classes = CangApi.getClasses();       // 全部类
 *   Class list = CangApi.getClass("List");            // 按简单名 / 全限定名 / 路径查
 *   List&lt;Function&gt; methods = CangApi.getMemberFunctions("List");
 *
 *   List&lt;Completion&gt; cs = CangApi.complete(buffer, caretOffset);
 *   SignatureHelp sh = CangApi.getSignatureHelp(buffer, caretOffset);
 *   List&lt;Diagnostic&gt; ds = CangApi.check(buffer);      // 空 = 语法正确
 * </pre>
 *
 * <p>Cang 是玩具/教学级语言，本接口同样定位为实验性。
 */
public final class CangApi {

    private CangApi() {
    }

    // ------------------------------------------------------------------
    // 源码根与索引
    // ------------------------------------------------------------------

    private static final List<Path> roots = new ArrayList<>();
    private static Index index;
    private static final Object LOCK = new Object();

    /** 追加一个源码根（目录递归扫 *.cang，或单个 .cang 文件）。改动后索引自动重建。 */
    public static void addSourceRoot(String path) {
        synchronized (LOCK) {
            roots.add(Paths.get(path));
            index = null;
        }
    }

    /** 清空源码根与索引（IDE 打开新工程 / 热重载时调用）。 */
    public static void reset() {
        synchronized (LOCK) {
            roots.clear();
            index = null;
        }
    }

    // ------------------------------------------------------------------
    // 编译（in-process：不 spawn 子进程、不 System.exit、默认静默）
    // ------------------------------------------------------------------

    private static final Object COMPILE_LOCK = new Object();

    /** 默认选项编译一个 .cang 源文件（含 import 解析；链接出可执行文件）。 */
    public static CompileResult compile(String sourceFile) {
        return compile(sourceFile, new CompileOptions());
    }

    /**
     * 编译源文件。编译器内部为单实例状态，调用被 {@code COMPILE_LOCK} 串行化。
     *
     * <p>结果：{@code success} + {@code diagnostics}（源码错误带行列；构建错误 0:0）、
     * {@code llFile}、{@code exeFile}（链接后）、{@code irText}（IR 预览）、
     * {@code buildOutput}（链接失败时的 clang/gcc 原文）。
     * 默认 {@code quiet}：过程输出不进 stdout。
     */
    public static CompileResult compile(String sourceFile, CompileOptions opt) {
        if (opt == null) opt = new CompileOptions();
        CompileResult r = new CompileResult();
        r.diagnostics = new ArrayList<>();
        synchronized (COMPILE_LOCK) {
            Cang.setLogStream(opt.isQuiet() ? null : System.out);
            try {
                String code = java.nio.file.Files.readString(java.nio.file.Path.of(sourceFile));
                String base = Cang.outputBase(sourceFile, opt.getOutputName());
                String ll = Cang.compile(sourceFile, code, opt.isNoLink(), opt.getTarget(),
                        opt.getArch(), opt.isGc(), base);
                r.llFile = base + ".ll";
                if (!opt.isNoLink()) {
                    switch (opt.getTarget()) {
                        case "windows":
                            Cang.compileWindows(ll, base, opt.getClangPath(), opt.getGccPath(),
                                    opt.isGc(), opt.getLibRoot());
                            r.exeFile = base + ".exe";
                            break;
                        case "linux":
                            Cang.compileLinux(ll, base, opt.isGc(), opt.getLibRoot());
                            r.exeFile = base;
                            break;
                        case "macos":
                            Cang.compileMacOS(ll, base, opt.isGc(), opt.getLibRoot());
                            r.exeFile = base;
                            break;
                        default:
                            r.diagnostics.add(new Diagnostic("unknown target: " + opt.getTarget(), 0, 0));
                            return r;
                    }
                }
                try {
                    java.nio.file.Path llPath = java.nio.file.Path.of(r.llFile);
                    if (java.nio.file.Files.exists(llPath)) {
                        r.irText = java.nio.file.Files.readString(llPath);
                    }
                } catch (java.io.IOException ignored) {
                }
                r.success = true;
            } catch (Cang.LinkFailed e) {
                r.diagnostics.add(new Diagnostic(e.getMessage(), 0, 0));
                r.buildOutput = e.output;
            } catch (CompileError e) {
                r.diagnostics.add(new Diagnostic(e.getMessage(), e.line, e.column));
            } catch (Exception e) {
                String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                r.diagnostics.add(new Diagnostic(m, 0, 0));
            } finally {
                Cang.setLogStream(System.out);
            }
        }
        return r;
    }

    /** 索引是否需要重建（源码根变更后为 true）。 */
    public static boolean isDirty() {
        synchronized (LOCK) {
            return index == null;
        }
    }

    private static Index idx() {
        synchronized (LOCK) {
            if (index == null) index = build();
            return index;
        }
    }

    // ------------------------------------------------------------------
    // 查询：类 / 全局函数 / 关键字
    // ------------------------------------------------------------------

    /** 所有已知类（自动 stdlib + addSourceRoot 的根），按名字排序。 */
    public static List<Class> getClasses() {
        Index ix = idx();
        List<Class> r = new ArrayList<>(ix.bySimple.values());
        r.sort(Comparator.comparing(c -> c.name == null ? "" : c.name));
        return r;
    }

    /**
     * 按名字查类：简单名（List）、全限定名（a_util_Logger）、路径（a/util/Logger、a_util_Logger 同义）。
     * 找不到返回 null。
     */
    public static Class getClass(String name) {
        if (name == null || name.isEmpty()) return null;
        Index ix = idx();
        Class c = ix.bySimple.get(name);
        if (c != null) return c;
        return ix.byQualified.get(name.replace('/', '_'));
    }

    /** 顶层全局函数（不属于任何类）。 */
    public static List<Function> getGlobalFunctions() {
        return new ArrayList<>(idx().globals);
    }

    /** 语言关键字（补全用，只读集合的拷贝）。 */
    public static List<String> getKeywords() {
        List<String> r = new ArrayList<>(TokenType.keywords());
        r.sort(String::compareTo);
        return r;
    }

    // ------------------------------------------------------------------
    // 查询：类成员（自动沿继承链收集；静态字段不继承）
    // ------------------------------------------------------------------

    /** 类的全部方法：成员 + 静态 + init 重载，含继承（父类在前）。 */
    public static List<Function> getFunctions(String className) {
        List<Function> r = new ArrayList<>();
        for (Class c : chain(className)) r.addAll(c.functions);
        return r;
    }

    /** 成员（实例）方法 + 构造方法，含继承。 */
    public static List<Function> getMemberFunctions(String className) {
        List<Function> r = new ArrayList<>();
        for (Class c : chain(className)) {
            for (Function f : c.functions) if (f.memberMethod) r.add(f);
        }
        return r;
    }

    /** 静态方法，含继承（父类在前）。 */
    public static List<Function> getStaticFunctions(String className) {
        List<Function> r = new ArrayList<>();
        for (Class c : chain(className)) {
            for (Function f : c.functions) if (f.staticMethod) r.add(f);
        }
        return r;
    }

    /** 实例字段，含继承（父类在前——与对象布局一致）。 */
    public static List<Variable> getFields(String className) {
        List<Variable> r = new ArrayList<>();
        for (Class c : chain(className)) {
            for (Variable v : c.fields) if (!v.isStatic) r.add(v);
        }
        return r;
    }

    /** 静态字段（仅本类声明，不继承）。 */
    public static List<Variable> getStaticFields(String className) {
        Class c = getClass(className);
        return c == null ? new ArrayList<>() : c.getStaticVariables();
    }

    /**
     * 按方法名收集全工程的候选重载：任意类（含继承可达）+ 全局函数。
     * receiverClass 非空时限定在该类继承链内。
     */
    public static List<Function> findFunctions(String name, String receiverClass) {
        List<Function> out = new ArrayList<>();
        if (receiverClass != null) {
            for (Class c : chain(receiverClass)) {
                for (Function f : c.functions) if (name.equals(f.name)) out.add(f);
            }
            return out;
        }
        for (Function f : idx().globals) if (name.equals(f.name)) out.add(f);
        for (Class c : idx().bySimple.values()) {
            for (Function f : c.functions) if (name.equals(f.name)) out.add(f);
        }
        return out;
    }

    /** 见 {@link #findFunctions(String, String)}（不按接收者过滤）。 */
    public static List<Function> findFunctions(String name) {
        return findFunctions(name, null);
    }

    /** 继承链：自身 → 父 → … → Object（环保护）。 */
    private static List<Class> chain(String className) {
        List<Class> r = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (className == null) return r;
        int lt = className.indexOf('<');
        if (lt > 0) className = className.substring(0, lt);   // List<int> -> List
        Class c = getClass(className);
        int guard = 0;
        while (c != null && seen.add(c.name) && guard++ < 32) {
            r.add(c);
            c = getClass(c.superClass);
        }
        return r;
    }

    // ------------------------------------------------------------------
    // 语法诊断
    // ------------------------------------------------------------------

    /**
     * 对一段缓冲做词法 + 语法检查，返回诊断列表（空 = 正确）。
     * 消息为编译器原文（短错误风格）。
     */
    public static List<Diagnostic> check(String source) {
        List<Diagnostic> out = new ArrayList<>();
        if (source == null || source.isEmpty()) return out;
        try {
            List<?> tokens = new Lexer(source).tokenize();
            new Parser(castTokens(tokens)).parse();
        } catch (RuntimeException e) {
            out.add(toDiagnostic(e.getMessage()));
        } catch (StackOverflowError e) {
            out.add(new Diagnostic("Expression nesting too deep", 1, 1));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<cc.ruok.cang.lexer.Token> castTokens(List<?> tokens) {
        return (List<cc.ruok.cang.lexer.Token>) tokens;
    }

    private static Diagnostic toDiagnostic(String raw) {
        if (raw == null) raw = "unknown error";
        int line = 1, col = 1;
        String msg = raw;
        Matcher m = Pattern.compile("line (\\d+), column (\\d+)").matcher(raw);
        if (m.find()) {
            line = Integer.parseInt(m.group(1));
            col = Integer.parseInt(m.group(2));
            msg = raw.substring(m.end());
            if (msg.startsWith(": ")) msg = msg.substring(2);
        }
        return new Diagnostic(msg, line, col);
    }

    // ------------------------------------------------------------------
    // 代码补全
    // ------------------------------------------------------------------

    /**
     * 在 offset 处补全。容忍未写完的代码（不依赖整缓冲可解析，按行扫描作用域）。
     *
     * <p>点前（{@code x.} / {@code Foo.}）→ 接收者成员；否则 → 局部变量 → 当前类成员 →
     * 全局函数 → 类名 → 关键字。
     */
    public static List<Completion> complete(String source, int offset) {
        List<Completion> out = new ArrayList<>();
        if (source == null || offset < 0) return out;
        offset = Math.min(offset, source.length());

        int lineStart = source.lastIndexOf('\n', offset - 1) + 1;
        String prefix = identBack(source, offset);
        int dot = lastNonWsBefore(source, offset - prefix.length());
        boolean dotted = dot >= 0 && source.charAt(dot) == '.';

        String receiver = null;
        if (dotted) {
            receiver = identBefore(source, dot);
            if (receiver.isEmpty()) receiver = null;
        }

        int lineNo = countLines(source, offset);
        Scope sc = scanScope(source, lineNo);

        if (dotted && receiver != null) {
            String type = resolveReceiverType(receiver, sc);
            if (type != null) {
                if (type.endsWith("]")) {
                    // 数组：仅 length()（元素级 API 由语言的下标访问承担）
                    if (match("length", prefix)) {
                        out.add(comp("length", Completion.Kind.METHOD, "int length()", false));
                    }
                } else {
                    addMembers(out, type, prefix, receiverIsClass(receiver));
                }
            }
            return sort(out);
        }

        // 非点位：局部 → 当前类成员 → 全局 → 类 → 关键字
        for (Variable v : sc.locals) {
            if (match(v.name, prefix)) out.add(comp(v.name, Completion.Kind.VARIABLE, v.type, false));
        }
        if (sc.currentClass != null) {
            addMembers(out, sc.currentClass, prefix, false);
        }
        for (Function f : idx().globals) {
            if (match(f.name, prefix)) {
                out.add(comp(f.name, Completion.Kind.FUNCTION, f.getSignature(), false));
            }
        }
        for (Class c : getClasses()) {
            if (match(c.name, prefix)) out.add(comp(c.name, Completion.Kind.CLASS, c.getQualifiedName(), false));
        }
        for (String k : TokenType.keywords()) {
            if (match(k, prefix)) out.add(comp(k, Completion.Kind.KEYWORD, null, false));
        }
        return sort(out);
    }

    private static boolean match(String name, String prefix) {
        return name != null && (prefix.isEmpty() || name.startsWith(prefix));
    }

    private static Completion comp(String name, Completion.Kind kind, String detail, boolean isStatic) {
        Completion c = new Completion();
        c.name = name;
        c.kind = kind;
        c.detail = detail;
        c.insertText = name;
        c.isStatic = isStatic;
        return c;
    }

    private static void addMembers(List<Completion> out, String className, String prefix, boolean staticsOnly) {
        boolean viaClass = receiverIsClass(className);
        // 接收者是类名 → 只给静态成员；否则实例成员 + 静态（类内也可见静态）
        for (Class c : chain(className)) {
            for (Function f : c.functions) {
                if (f.constructor) continue;
                boolean want = staticsOnly ? f.staticMethod : true;
                if (want && match(f.name, prefix)) {
                    out.add(comp(f.name, Completion.Kind.METHOD, f.getSignature(), f.staticMethod));
                }
            }
            for (Variable v : c.fields) {
                boolean want = staticsOnly ? v.isStatic : !v.isStatic;
                if (want && match(v.name, prefix)) {
                    out.add(comp(v.name, Completion.Kind.FIELD, v.type, v.isStatic));
                }
            }
        }
    }

    private static List<Completion> sort(List<Completion> list) {
        list.sort((a, b) -> {
            int r = Integer.compare(kindRank(a.kind), kindRank(b.kind));
            return r != 0 ? r : a.name.compareTo(b.name);
        });
        return list;
    }

    private static int kindRank(Completion.Kind k) {
        switch (k) {
            case VARIABLE: return 0;
            case FIELD: return 1;
            case METHOD: return 2;
            case FUNCTION: return 3;
            case CLASS: return 4;
            default: return 5;
        }
    }

    private static boolean receiverIsClass(String token) {
        return token != null && getClass(token) != null;
    }

    /** 点前接收者 → 类型：类名 / this / 局部变量 / 当前类字段。 */
    private static String resolveReceiverType(String receiver, Scope sc) {
        if ("this".equals(receiver)) return sc.currentClass;
        if (receiverIsClass(receiver)) {
            Class c = getClass(receiver);
            return c != null ? c.name : receiver;
        }
        for (int i = sc.locals.size() - 1; i >= 0; i--) {
            Variable v = sc.locals.get(i);
            if (receiver.equals(v.name)) return v.type;
        }
        if (sc.currentClass != null) {
            for (Variable v : getFields(sc.currentClass)) {
                if (receiver.equals(v.name)) return v.type;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 签名帮助（调用处参数提示）
    // ------------------------------------------------------------------

    /**
     * 光标处的调用签名帮助：往回找所属调用括号 → 方法名（含 new 后的构造重载、
     * {@code x.foo(} 的接收者过滤）→ 重载列表 + 激活参数下标。
     */
    public static SignatureHelp getSignatureHelp(String source, int offset) {
        SignatureHelp h = new SignatureHelp();
        h.overloads = new ArrayList<>();
        if (source == null || offset < 0) return h;
        offset = Math.min(offset, source.length());

        int depth = 0;
        int callParen = -1;
        for (int i = offset - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (c == ')' ) depth++;
            else if (c == '(') {
                if (depth == 0) { callParen = i; break; }
                depth--;
            } else if (c == ';' || c == '{' || c == '}' || c == '\n') {
                break; // 不在任何调用里
            }
        }
        if (callParen < 0) return h;

        // 括号前的标识符（括号前可能有空白）
        int lastNonWs = skipWsBack(source, callParen);      // 最后一个非空白字符下标，或 -1
        if (lastNonWs < 0 || !isIdent(source.charAt(lastNonWs))) return h;
        int nameEnd = lastNonWs + 1;
        int nameStart = identBack(source, nameEnd).isEmpty() ? nameEnd : nameStartOf(source, nameEnd);
        String name = source.substring(nameStart, nameEnd);
        if (name.isEmpty()) return h;

        String receiver = null;
        boolean isNew = false;
        int before = skipWsBack(source, nameStart);         // 名字前一个非空白
        if (before >= 0 && source.charAt(before) == '.') {
            receiver = identBefore(source, before);
            if (receiver.isEmpty()) receiver = null;
            String w = identBefore(source, skipWsBack(source, before - (receiver == null ? 0 : receiver.length())) + 1);
            isNew = "new".equals(w);
        } else if (before >= 2) {
            int wStart = before;
            while (wStart >= 0 && isIdent(source.charAt(wStart))) wStart--;
            wStart++;
            isNew = source.regionMatches(wStart, "new", 0, 3)
                    && (wStart + 3 > source.length() || !isIdent(source.charAt(wStart + 3)));
        }

        String recvClass = null;
        if (isNew) {
            recvClass = name; // new Foo( → Foo 的构造重载
            Class c = getClass(recvClass);
            List<Function> ctors = c == null ? new ArrayList<>() : c.getConstructors();
            h.overloads.addAll(ctors);
        } else {
            if (receiver != null) recvClass = resolveReceiverTypeStatic(receiver, source, offset);
            h.overloads.addAll(findFunctions(name, recvClass));
            if (h.overloads.isEmpty() && receiver != null) h.overloads.addAll(findFunctions(name));
        }

        // 激活参数 = 调用括号与光标之间的顶层逗号数
        int commas = 0, d2 = 0;
        for (int i = callParen + 1; i < offset; i++) {
            char c = source.charAt(i);
            if (c == '(' || c == '[') d2++;
            else if (c == ')' || c == ']') d2--;
            else if (c == ',' && d2 == 0) commas++;
        }
        h.activeParameter = commas;

        if (h.overloads.size() == 1) {
            h.activeIndex = 0;
        } else if (h.overloads.size() > 1) {
            List<Integer> arityOk = new ArrayList<>();
            for (int i = 0; i < h.overloads.size(); i++) {
                if (h.overloads.get(i).params.size() > commas) arityOk.add(i);
            }
            if (arityOk.size() == 1) h.activeIndex = arityOk.get(0);
        }
        return h;
    }

    /** 标识符（结束于 end）的起始下标。 */
    private static int nameStartOf(String src, int end) {
        int i = end;
        while (i > 0 && isIdent(src.charAt(i - 1))) i--;
        return i;
    }

    /** 接收者解析（作用域感知：类名 / 局部变量 / 当前类字段）。 */
    private static String resolveReceiverTypeStatic(String receiver, String source, int offset) {
        if (receiver == null) return null;
        if (receiverIsClass(receiver)) {
            Class c = getClass(receiver);
            return c != null ? c.name : null;
        }
        Scope sc = scanScope(source, countLines(source, offset));
        return resolveReceiverType(receiver, sc);
    }

    // ------------------------------------------------------------------
    // 作用域扫描（行级、容忍坏缓冲）
    // ------------------------------------------------------------------

    private static final class Scope {
        String currentClass;
        List<Variable> locals = new ArrayList<>();
    }

    /** 当前文件的类（单类模型：第一个 class 行即当前类）+ 光标所在函数的参数与局部变量。 */
    private static Scope scanScope(String src, int lineNo) {
        Scope sc = new Scope();
        String[] lines = src.split("\n", -1);

        int classLine = -1;
        Pattern classPat = Pattern.compile("^\\s*(?:abstract\\s+)?class\\s+([A-Za-z_]\\w*)");
        for (int i = 0; i < lines.length && i < lineNo; i++) {
            Matcher m = classPat.matcher(lines[i]);
            if (m.find()) {
                sc.currentClass = m.group(1);
                classLine = i;
            }
        }

        // 最近的 func 头（含跨行参数表），且其大括号范围覆盖 lineNo
        int funcStart = -1;
        Pattern funcPat = Pattern.compile("^\\s*(?:(?:static|final|native|abstract)\\s+)*func\\s+.*");
        for (int i = Math.min(lineNo - 1, lines.length - 1); i >= 0; i--) {
            if (funcPat.matcher(lines[i]).find()) { funcStart = i; break; }
        }
        if (funcStart < 0) return sc;

        // 函数头可能跨行：找到参数表收尾 '{' 或同行结束
        int bodyStart = funcStart;
        int brace = 0;
        boolean opened = false;
        for (int i = funcStart; i < lines.length; i++) {
            for (int j = 0; j < lines[i].length(); j++) {
                char c = lines[i].charAt(j);
                if (c == '{') { brace++; opened = true; }
                else if (c == '}') brace--;
            }
            if (opened && brace > 0 && i >= lineNo - 1) break;
            if (opened && brace <= 0 && i >= funcStart) { bodyStart = -1; break; } // 单行函数体已闭合
            if (opened) { bodyStart = i; break; }
        }
        if (!opened) return sc;
        // 光标必须在函数体内（或头行之后）
        if (lineNo - 1 < funcStart) return sc;

        // 参数：头区文本里第一个 ( ... )
        StringBuilder head = new StringBuilder();
        int pdepth = 0; boolean inParams = false; boolean seen = false;
        for (int i = funcStart; i < lines.length && i <= Math.max(funcStart, lineNo - 1); i++) {
            head.append(lines[i]).append('\n');
            for (char c : lines[i].toCharArray()) {
                if (c == '(') { inParams = true; seen = true; pdepth++; }
                else if (c == ')' && seen) { pdepth--; if (pdepth == 0) inParams = false; }
                else if (c == '{' && seen && pdepth == 0) { i = lines.length; break; }
            }
        }
        Matcher pm = Pattern.compile("\\(([^)]*)\\)").matcher(head);
        if (pm.find()) {
            String ps = pm.group(1);
            for (String part : splitTopLevel(ps)) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                Matcher vm = Pattern.compile("^(?:final\\s+|var\\s+)?([A-Za-z_][\\w<>,\\.\\[\\]]*)\\s+([A-Za-z_]\\w*)").matcher(t);
                if (vm.find()) {
                    Variable v = new Variable();
                    v.name = vm.group(2);
                    v.type = vm.group(1);
                    sc.locals.add(v);
                }
            }
        }

        // 局部变量：从函数头下一行到光标行
        for (int i = funcStart + 1; i < lineNo && i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue;
            Matcher lm = Pattern.compile("^(?:final\\s+)?var\\s+([A-Za-z_]\\w*)\\s*=\\s*(.+?);?$").matcher(t);
            if (lm.find()) {
                Variable v = new Variable();
                v.name = lm.group(1);
                v.type = inferType(lm.group(2), sc);
                sc.locals.add(v);
                continue;
            }
            Matcher tm = Pattern.compile("^(?:final\\s+)?([A-Za-z_][\\w<>,\\.\\[\\]]*(?:\\[\\])?)\\s+([A-Za-z_]\\w*)\\s*=").matcher(t);
            if (tm.find()) {
                Variable v = new Variable();
                v.name = tm.group(2);
                v.type = tm.group(1);
                sc.locals.add(v);
                continue;
            }
            Matcher fm = Pattern.compile("^for\\s*\\(\\s*(?:var|([A-Za-z_][\\w<>,\\.\\[\\]]*))\\s+([A-Za-z_]\\w*)\\s*:").matcher(t);
            if (fm.find()) {
                Variable v = new Variable();
                v.name = fm.group(2);
                v.type = fm.group(1) != null ? fm.group(1) : "var";
                sc.locals.add(v);
            }
        }
        return sc;
    }

    /** var x = <expr> 的粗略类型推导。 */
    private static String inferType(String init, Scope sc) {
        String e = init.trim();
        if (e.endsWith(";")) e = e.substring(0, e.length() - 1).trim();
        if (e.startsWith("new ")) {
            int cut = e.indexOf('(');
            String t = cut > 0 ? e.substring(4, cut).trim() : e.substring(4).trim();
            t = t.replace("<>", "");
            return t;
        }
        if (e.equals("null")) return null;
        if (e.equals("true") || e.equals("false")) return "bool";
        if (e.startsWith("\"") || e.startsWith("`")) return "String";
        if (e.startsWith("'")) return "str";
        if (e.matches("^[+-]?\\d+$")) return "int";
        if (e.matches("^[+-]?\\d+[lL]$")) return "long";
        if (e.matches("^[+-]?(\\d+\\.\\d*|\\d*\\.\\d+)([eE][+-]?\\d+)?[fFdD]?")) return "double";
        if (e.startsWith("[")) return "array";
        // 标识符：已知局部/字段 → 其类型
        for (int i = sc.locals.size() - 1; i >= 0; i--) {
            if (e.equals(sc.locals.get(i).name)) return sc.locals.get(i).type;
        }
        if (sc.currentClass != null) {
            for (Variable v : getFields(sc.currentClass)) if (e.equals(v.name)) return v.type;
        }
        return null;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '[' || c == '(') depth++;
            else if (c == '>' || c == ']' || c == ')') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    // ------------------------------------------------------------------
    // 文本工具
    // ------------------------------------------------------------------

    /** 结束于 offset 处的标识符（offset 前连续的 ident 字符），无则空串。 */
    private static String identBack(String src, int offset) {
        int i = Math.min(offset, src.length());
        while (i > 0 && isIdent(src.charAt(i - 1))) i--;
        return src.substring(i, Math.min(offset, src.length()));
    }

    /** 结束于 idx 处（idx 本身不参与）的标识符，用于取点号前的接收者。 */
    private static String identBefore(String src, int idx) {
        return identBack(src, idx);
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int lastNonWsBefore(String src, int offset) {
        int i = Math.min(offset, src.length()) - 1;
        while (i >= 0 && Character.isWhitespace(src.charAt(i))) i--;
        return i;
    }

    private static int skipWsBack(String src, int offset) {
        int i = offset;
        while (i > 0 && Character.isWhitespace(src.charAt(i - 1))) i--;
        return i - 1; // 指向 offset 前最后一个非空白；可能为 -1
    }

    private static int countLines(String src, int offset) {
        int n = 1;
        for (int i = 0; i < offset && i < src.length(); i++) if (src.charAt(i) == '\n') n++;
        return n;
    }

    // ------------------------------------------------------------------
    // 索引构建
    // ------------------------------------------------------------------

    private static final class Index {
        final Map<String, Class> bySimple = new HashMap<>();
        final Map<String, Class> byQualified = new HashMap<>();
        final List<Function> globals = new ArrayList<>();
    }

    private static Index build() {
        Index ix = new Index();
        List<Path> files = new ArrayList<>();
        // 自动 stdlib（与编译器同前两级：cwd/stdlib、cwd/../stdlib）
        String cwd = System.getProperty("user.dir");
        addCangFiles(files, Paths.get(cwd, "stdlib"));
        addCangFiles(files, Paths.get(cwd, "..", "stdlib"));
        // 显式源码根
        synchronized (LOCK) {
            for (Path r : roots) addCangFiles(files, r);
        }
        Set<String> seenFiles = new HashSet<>();
        for (Path f : files) {
            try {
                String key = f.toRealPath().toString();
                if (!seenFiles.add(key)) continue;
                String text = Files.readString(f);
                indexFile(ix, text);
            } catch (IOException ignored) {
                // 读不到的文件不参与索引
            }
        }
        ix.globals.sort(Comparator.comparing(f -> f.name == null ? "" : f.name));
        return ix;
    }

    private static void addCangFiles(List<Path> out, Path p) {
        try {
            if (Files.isRegularFile(p)) {
                if (p.toString().endsWith(".cang")) out.add(p);
            } else if (Files.isDirectory(p)) {
                try (var walk = Files.walk(p)) {
                    walk.filter(f -> f.toString().endsWith(".cang")).forEach(out::add);
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** 解析一段源码并入索引（解析失败的文件整体跳过）。 */
    private static void indexFile(Index ix, String text) {
        try {
            List<cc.ruok.cang.lexer.Token> tokens = new Lexer(text).tokenize();
            AST.Program prog = new Parser(tokens).parse();
            String ns = "";
            for (AST m : prog.members) {
                if (m instanceof AST.NamespaceDecl) {
                    ns = ((AST.NamespaceDecl) m).path;
                    break;
                }
            }
            for (AST m : prog.members) {
                if (m instanceof AST.FuncDecl) {
                    ix.globals.add(toFunction((AST.FuncDecl) m, null, ns));
                } else if (m instanceof AST.ClassDecl) {
                    Class c = toClass((AST.ClassDecl) m, ns);
                    c.namespace = ns;
                    ix.bySimple.putIfAbsent(c.name, c);
                    ix.byQualified.putIfAbsent(c.getQualifiedName(), c);
                }
            }
        } catch (Throwable ignored) {
            // 坏文件不入索引（诊断由 check() 负责）
        }
    }

    private static Class toClass(AST.ClassDecl cd, String ns) {
        Class c = new Class();
        c.name = cd.name;
        c.line = cd.line;
        c.namespace = ns == null ? "" : ns;
        c.superClass = cd.superClass == null || cd.superClass.isEmpty() ? "Object" : cd.superClass;
        c.isAbstract = cd.isAbstract;

        // 类体成员：members + 顶层尾巴（单类文件里 class 之后的声明）
        List<AST> all = new ArrayList<>(cd.members);
        for (AST s : cd.topLevelBody) {
            if (s instanceof AST.FuncDecl || s instanceof AST.FieldDecl || s instanceof AST.ConstructorDecl) {
                all.add(s);
            }
        }
        for (AST m : all) {
            if (m instanceof AST.FuncDecl) {
                c.functions.add(toFunction((AST.FuncDecl) m, c.name, ns));
            } else if (m instanceof AST.ConstructorDecl) {
                AST.ConstructorDecl ctor = (AST.ConstructorDecl) m;
                Function f = new Function();
                f.name = ctor.className;
                f.returnType = "void";
                f.line = ctor.line;
                f.owner = c.name;
                f.memberMethod = true;
                f.constructor = true;
                f.params = toParams(ctor.params, c.name);
                c.functions.add(f);
            } else if (m instanceof AST.FieldDecl) {
                AST.FieldDecl fd = (AST.FieldDecl) m;
                Variable v = new Variable();
                v.name = fd.name;
                v.type = fd.type;
                v.line = fd.line;
                v.isStatic = fd.isStatic;
                v.isFinal = fd.isFinal;
                v.owner = c.name;
                v.defaultValue = renderExpr(fd.init);
                c.fields.add(v);
            }
        }
        return c;
    }

    private static Function toFunction(AST.FuncDecl fd, String owner, String ns) {
        Function f = new Function();
        f.name = fd.name;
        f.returnType = fd.returnType;
        f.line = fd.line;
        f.owner = owner;
        f.staticMethod = fd.isStatic;
        f.memberMethod = owner != null && !fd.isStatic;
        f.finalMethod = fd.isFinal;
        f.nativeMethod = fd.isNative;
        f.abstractMethod = fd.isAbstract;
        f.params = toParams(fd.params, owner);
        return f;
    }

    private static List<Variable> toParams(List<AST.Parameter> ps, String owner) {
        List<Variable> out = new ArrayList<>();
        if (ps == null) return out;
        for (AST.Parameter p : ps) {
            Variable v = new Variable();
            v.name = p.name;
            v.type = p.type;
            v.line = p.line;
            v.owner = owner;
            v.defaultValue = renderExpr(p.defaultValue);
            out.add(v);
        }
        return out;
    }

    /** 字面量 / 简单表达式 → 展示文本；复杂表达式返回 null。 */
    private static String renderExpr(AST node) {
        if (node == null) return null;
        if (node instanceof AST.NullLit) return "null";
        if (node instanceof AST.BoolLit) return String.valueOf(getBool(node));
        if (node instanceof AST.IntLit) return str(node, "value");
        if (node instanceof AST.LongLit) return str(node, "value");
        if (node instanceof AST.FloatLit) return str(node, "value");
        if (node instanceof AST.DoubleLit) return str(node, "value");
        if (node instanceof AST.StringLit) return "\"" + str(node, "value") + "\"";
        if (node instanceof AST.StrLit) return "'" + str(node, "value") + "'";
        if (node instanceof AST.Identifier) return str(node, "name");
        return null;
    }

    private static String str(Object o, String field) {
        try {
            java.lang.reflect.Field f = o.getClass().getField(field);
            Object v = f.get(o);
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean getBool(Object o) {
        try {
            java.lang.reflect.Field f = o.getClass().getField("value");
            return Boolean.TRUE.equals(f.get(o));
        } catch (Exception e) {
            return false;
        }
    }
}
