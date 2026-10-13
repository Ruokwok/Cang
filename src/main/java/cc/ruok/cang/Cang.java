package cc.ruok.cang;

import cc.ruok.cang.codegen.LLVMGen;
import cc.ruok.cang.lexer.Lexer;
import cc.ruok.cang.lexer.Token;
import cc.ruok.cang.lexer.TokenType;
import cc.ruok.cang.parser.AST;
import cc.ruok.cang.parser.Parser;
import cc.ruok.cang.util.CompileError;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Cang {

    private static String sourceCode = "";
    private static String sourceFile = "";
    private static String bundledStdlibDir = null;

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }
        for (String a : args) {
            if (a.equals("--version")) {
                System.out.println("Cang " + readProjectVersion());
                System.exit(0);
            }
        }
        if (args[0].startsWith("-")) {
            System.err.println("error: missing source file (first argument must be a .cang file)");
            printUsage();
            System.exit(1);
        }

        String filename = resolveSourcePath(args[0]);
        boolean noLink = false;
        String target = "windows";
        String architecture = "amd64";
        String clangPath = "clang";
        String gccPath = "gcc";
        boolean gc = true;          // Boehm GC is on by default
        String libRoot = null;   // --gc-lib: 库根目录（其下 lib/ 存库文件）
        String outputName = null;   // -o / --output: executable name (.exe optional)

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--no-link": noLink = true; break;
                case "--target": target = requireValue(args, ++i, "--target", "windows|linux|macos").toLowerCase(); break;
                case "--arch": architecture = requireValue(args, ++i, "--arch", "amd64|...").toLowerCase(); break;
                case "--clang": clangPath = requireValue(args, ++i, "--clang", "<path>"); break;
                case "--gcc": gccPath = requireValue(args, ++i, "--gcc", "<path>"); break;
                case "--gc": gc = true; break;
                case "--no-gc": gc = false; break;
                case "--gc-lib": libRoot = requireValue(args, ++i, "--gc-lib", "<dir>"); break;
                case "-o":
                case "--output": outputName = requireValue(args, ++i, "-o", "<name>"); break;
                default:
                    System.err.println("error: unknown option '" + args[i] + "'");
                    printUsage();
                    System.exit(1);
            }
        }

        if (outputName != null && outputName.toLowerCase().endsWith(".exe")) {
            outputName = outputName.substring(0, outputName.length() - ".exe".length());
        }
        if (outputName != null && outputName.isEmpty()) {
            System.err.println("error: -o requires a non-empty name");
            System.exit(1);
        }

        sourceFile = filename;
        sourceCode = Files.readString(Path.of(filename));

        String llFile;
        String baseName = outputBase(filename, outputName);
        try {
            llFile = compile(filename, sourceCode, noLink, target, architecture, gc, baseName);
            if (noLink || llFile == null) {
                System.out.println("Done (--no-link: skipping compilation)");
                return;
            }
            switch (target) {
                case "windows": compileWindows(llFile, baseName, clangPath, gccPath, gc, libRoot); break;
                case "linux":   compileLinux(llFile, baseName, gc, libRoot); break;
                case "macos":   compileMacOS(llFile, baseName, gc, libRoot); break;
                default:
                    System.err.println("error: unknown target '" + target + "'; expected windows|linux|macos");
                    printUsage();
                    System.exit(1);
            }
        } catch (CompileError e) {
            System.err.println(e.format());
            System.exit(1);
        } catch (LinkFailed e) {
            System.err.println("error: " + e.getMessage());
            if (e.output != null && !e.output.isBlank()) System.err.println(e.output);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Internal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Build/link failure: short message + captured toolchain output. Never exits the JVM. */
    public static class LinkFailed extends RuntimeException {
        public final String output;
        public LinkFailed(String message) { this(message, null); }
        public LinkFailed(String message, String output) { super(message); this.output = output; }
    }

    /** Compile-pipeline progress sink. null = silent (IDE); default System.out (CLI). */
    private static java.io.PrintStream log = System.out;

    public static void setLogStream(java.io.PrintStream out) {
        log = out != null ? out : new java.io.PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) { /* discard */ }
        });
    }

    /**
     * Output base for all artifacts (.ll/.o/exe): &lt;targetRoot&gt;/target/&lt;source name&gt;
     * with an output name, ".exe" is optional and a name containing a path separator
     * is used as-is (parent directories created).
     */
    public static String outputBase(String sourceFile, String outputName) {
        return outputBase(sourceFile, outputName, null);
    }

    /**
     * Like {@link #outputBase(String, String)} but with an artifact root: plain names
     * land in {@code <targetRoot>/target/} (created on demand). A name containing a
     * path separator is an explicit path and is honored as-is, ignoring targetRoot.
     */
    public static String outputBase(String sourceFile, String outputName, String targetRoot) {
        if (outputName != null && (outputName.contains("/") || outputName.contains("\\"))) {
            String n = outputName.toLowerCase().endsWith(".exe")
                    ? outputName.substring(0, outputName.length() - ".exe".length())
                    : outputName;
            if (n.isEmpty()) throw new IllegalArgumentException("output name is empty");
            try {
                String parent = new File(n).getParent();
                if (parent != null && !parent.isEmpty()) Files.createDirectories(Path.of(parent));
            } catch (IOException ignored) {}
            return n;
        }
        String dir = (targetRoot == null || targetRoot.isBlank())
                ? "target"
                : Path.of(targetRoot, "target").toString();
        try { Files.createDirectories(Path.of(dir)); } catch (IOException ignored) {}
        String n = outputName;
        if (n == null || n.isEmpty()) {
            n = new File(sourceFile).getName();
            if (n.endsWith(".cang")) n = n.substring(0, n.length() - ".cang".length());
        } else if (n.toLowerCase().endsWith(".exe")) {
            n = n.substring(0, n.length() - ".exe".length());
            if (n.isEmpty()) throw new IllegalArgumentException("output name is empty");
        }
        return dir + File.separator + n;
    }

    /** Option value accessor: missing value is a usage error, not an ArrayIndexOutOfBounds. */
    private static String requireValue(String[] args, int idx, String opt, String expected) {
        if (idx >= args.length) {
            System.err.println("error: " + opt + " requires a value (" + expected + ")");
            printUsage();
            System.exit(1);
        }
        return args[idx];
    }

    /**
     * Project version from pom.xml: cwd first, then walking up from the
     * code source (covers running from target/classes or from a jar).
     * The first &lt;version&gt; outside any &lt;parent&gt; block wins; "unknown" if unreadable.
     */
    private static String readProjectVersion() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("pom.xml"));
        try {
            Path cs = Path.of(Cang.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(cs) ? cs : cs.getParent();
            for (int i = 0; i < 3 && dir != null; i++) {
                candidates.add(dir.resolve("pom.xml"));
                dir = dir.getParent();
            }
        } catch (Exception ignored) {
        }
        for (Path p : candidates) {
            try {
                if (!Files.exists(p)) continue;
                String xml = Files.readString(p);
                xml = xml.replaceAll("(?s)<parent>.*?</parent>", "");
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<version>\\s*(.*?)\\s*</version>").matcher(xml);
                if (m.find()) return m.group(1);
            } catch (Exception ignored) {
            }
        }
        return "unknown";
    }

    private static void printUsage() {
        System.err.println("Usage: Cang <source.cang> [options]");
        System.err.println("  --version          print compiler version and exit");
        System.err.println("  --no-link          compile to .ll only (skip clang/gcc)");
        System.err.println("  --target <t>       windows | linux | macos   (default: windows)");
        System.err.println("  --arch <a>         target architecture         (default: amd64)");
        System.err.println("  --clang <path>     clang executable");
        System.err.println("  --gcc <path>       gcc executable");
        System.err.println("  --gc / --no-gc     Boehm GC on (default) / manual malloc-free");
        System.err.println("  --gc-lib <dir>     override libgc lookup directory");
        System.err.println("  -o, --output <name>  output executable name (default: source name; .exe optional)");
    }

    public static String compile(String filename, String code, boolean noLink, String target,
                                 String architecture, boolean gc, String baseName) throws IOException {
        long startTime = System.currentTimeMillis();
        // Error rendering context (wrapError/loadImport diagnostics) — also set by API callers.
        sourceFile = filename;
        sourceCode = code;

        // 1. Lexing
        log.println();
        log.println("\u001B[1m[1/3] Lexing\u001B[0m  " + filename);
        long lexStart = System.currentTimeMillis();
        Lexer lexer = new Lexer(code);
        List<Token> tokens;
        try {
            tokens = lexer.tokenize();
        } catch (RuntimeException e) {
            throw wrapError(e);
        }
        long lexTime = System.currentTimeMillis() - lexStart;
        log.println("      " + tokens.size() + " tokens (" + lexTime + "ms)");

        // 2. Parsing (main file)
        log.println("\u001B[1m[2/3] Parsing\u001B[0m");
        long parseStart = System.currentTimeMillis();
        Parser parser = new Parser(tokens);
        AST.Program mainProgram;
        try {
            mainProgram = parser.parse();
            mainProgram.setSourceFile(filename);
        } catch (RuntimeException e) {
            throw wrapError(e);
        } catch (StackOverflowError e) {
            // Depth guard in parsePrimary normally fires first; this is the hard floor (debug.md #6).
            throw new CompileError("Expression nesting too deep", filename, 1, 1, "", 1);
        }

        // 2b. Resolve imports - parse imported files and merge
        AST.Program fullProgram = resolveImports(filename, mainProgram);
        long parseTime = System.currentTimeMillis() - parseStart;
        log.println("      " + fullProgram.members.size() + " top-level nodes (" + parseTime + "ms)");

        // 3. Code generation
        log.println("\u001B[1m[3/3] Generating LLVM IR\u001B[0m");
        long genStart = System.currentTimeMillis();
        LLVMGen codegen = new LLVMGen();
        codegen.setSourceFile(filename);
        codegen.setTargetPlatform(target);
        codegen.setTargetArchitecture(architecture);
        codegen.setGcEnabled(gc);
        String llvmIR;
        try {
            llvmIR = codegen.generate(fullProgram);
        } catch (CompileError e) {
            throw e; // rethrow CompileError directly with correct file info
        } catch (RuntimeException e) {
            throw wrapError(e);
        }
        long genTime = System.currentTimeMillis() - genStart;

        // Write .ll file
        String llFile = baseName + ".ll";
        Files.writeString(Path.of(llFile), llvmIR);
        long llLines = llvmIR.split("\n").length;
        log.println("      " + llFile + " (" + llLines + " lines, " + genTime + "ms)");

        long totalTime = System.currentTimeMillis() - startTime;
        log.println("\u001B[2m      Total: " + totalTime + "ms\u001B[0m");

        return noLink ? null : llFile;
    }

    /**
     * Resolve imports: find and parse imported .cang files, merge into one Program.
     * Import path "cc/ruok/Apple" resolves to <sourceRoot>/cc/ruok/Apple.cang
     */
    private static AST.Program resolveImports(String mainFilename, AST.Program mainProgram) throws IOException {
        // Find namespace
        String namespace = "";
        for (AST member : mainProgram.members) {
            if (member instanceof AST.NamespaceDecl) {
                namespace = ((AST.NamespaceDecl) member).path;
                break;
            }
        }

        // Determine source root:
        // Main file at <root>/cc/ruok/Main.cang, namespace "cc/ruok" → root is <root>
        // Source root: the namespace is a directory suffix of the main file's folder
        // (namespace cc/ruok + .../cc/ruok/Main.cang -> root ...). Compared on the full
        // directory path so a file named test_args.cang can never match a "/test" prefix.
        String mainPath = new File(mainFilename).getCanonicalPath().replace('\\', '/');
        String sourceRoot = mainPath;
        int lastSlash = mainPath.lastIndexOf('/');
        String mainDir = lastSlash >= 0 ? mainPath.substring(0, lastSlash) : ".";
        if (!namespace.isEmpty()) {
            String suffix = "/" + namespace;
            if (mainDir.endsWith(suffix)) {
                String root = mainDir.substring(0, mainDir.length() - suffix.length());
                sourceRoot = root.isEmpty() ? "/" : root;
            } else {
                sourceRoot = mainDir;
            }
        } else {
            // No namespace: source root is the main file's directory
            sourceRoot = mainDir;
        }

        // Statement-before-class is validated on the MAIN file only: imported classes are
        // merged first and must not make a class-less main look class-bearing (debug.md #36
        // pair). A class-less main keeps its top-level statements as the auto-main body.
        boolean mainHasClass = false;
        for (AST member : mainProgram.members) {
            if (member instanceof AST.ClassDecl) { mainHasClass = true; break; }
        }
        if (mainHasClass) {
            boolean seenClass = false;
            for (AST member : mainProgram.members) {
                if (member instanceof AST.ClassDecl) { seenClass = true; continue; }
                boolean decl = member instanceof AST.FuncDecl || member instanceof AST.NamespaceDecl
                    || member instanceof AST.ImportDecl;
                if (!seenClass && !decl) {
                    String[] srcLines = sourceCode.split("\n", -1);
                    int ln = Math.max(1, Math.min(member.line, srcLines.length));
                    String srcLine = srcLines[ln - 1];
                    int col = srcLine.length() - srcLine.stripLeading().length() + 1;
                    throw new CompileError("statements are not allowed before the class",
                        sourceFile, ln, col, srcLine, Math.max(1, srcLine.strip().length()));
                }
            }
        }

        // Collect all members
        List<AST> allMembers = new ArrayList<>();
        java.util.Set<String> visitedFiles = new java.util.HashSet<>();
        visitedFiles.add(new File(mainFilename).getCanonicalPath());

        // Process explicit imports FIRST so imported parent classes are registered
        // before the main file's classes (collectClass needs parents to copy fields).
        List<String> explicitImports = collectImports(mainProgram);
        for (String importPath : explicitImports) {
            allMembers.addAll(loadImport(importPath, sourceRoot, visitedFiles));
        }


        // Bare type references resolve as: explicit imports (above) -> stdlib cang/lang/
        // -> stdlib cang/io/. Anything else stays unresolved and fails later with
        // "Unknown class". Keywords (int/String/...) are builtin and never looked up.
        java.util.Set<String> autoTried = new java.util.HashSet<>();
        for (int round = 0; round < 32; round++) {
            java.util.Set<String> refs = new java.util.LinkedHashSet<>();
            collectTypeRefs(mainProgram, refs);
            for (AST m : allMembers) collectTypeRefs(m, refs);
            java.util.Set<String> known = new java.util.HashSet<>();
            for (AST m : allMembers) {
                if (m instanceof AST.ClassDecl) known.add(((AST.ClassDecl) m).name);
            }
            for (AST m : mainProgram.members) {
                if (m instanceof AST.ClassDecl) known.add(((AST.ClassDecl) m).name);
            }
            boolean loadedAny = false;
            for (String r : refs) {
                if (r == null || known.contains(r) || autoTried.contains(r)) continue;
                autoTried.add(r);
                if (TokenType.keywords().contains(r)) continue;
                String pkg = null;
                if (resolveImportFile(sourceRoot, "cang/lang/" + r) != null) {
                    pkg = "cang/lang/" + r;
                } else if (resolveImportFile(sourceRoot, "cang/io/" + r) != null) {
                    pkg = "cang/io/" + r;
                }
                if (pkg == null) continue;
                List<AST> more = loadImport(pkg, sourceRoot, visitedFiles);
                if (!more.isEmpty()) {
                    allMembers.addAll(more);
                    loadedAny = true;
                }
            }
            if (!loadedAny) break;
        }

        // Add main file's members LAST (after imports so parents are collected first)
        for (AST member : mainProgram.members) {
            allMembers.add(member);
        }

        return new AST.Program(allMembers);
    }

    private static void collectTypeRefs(java.util.List<?> nodes, java.util.Set<String> out) {
        if (nodes == null) return;
        for (Object n : nodes) {
            if (n instanceof AST) collectTypeRefs((AST) n, out);
        }
    }

    /** Collect referenced type names from an AST subtree (type positions only). */
    private static void collectTypeRefs(AST node, java.util.Set<String> out) {
        if (node == null) return;
        if (node instanceof AST.VarDecl) {
            AST.VarDecl v = (AST.VarDecl) node;
            addTypeName(v.type, out);
            collectTypeRefs(v.init, out);
            return;
        }
        if (node instanceof AST.FieldDecl) {
            AST.FieldDecl f = (AST.FieldDecl) node;
            addTypeName(f.type, out);
            collectTypeRefs(f.init, out);
            return;
        }
        if (node instanceof AST.FuncDecl) {
            AST.FuncDecl f = (AST.FuncDecl) node;
            addTypeName(f.returnType, out);
            collectTypeRefs(f.params, out);
            collectTypeRefs(f.body, out);
            return;
        }
        if (node instanceof AST.ConstructorDecl) {
            AST.ConstructorDecl c = (AST.ConstructorDecl) node;
            collectTypeRefs(c.params, out);
            collectTypeRefs(c.body, out);
            return;
        }
        if (node instanceof AST.Parameter) {
            AST.Parameter p = (AST.Parameter) node;
            addTypeName(p.type, out);
            collectTypeRefs(p.defaultValue, out);
            return;
        }
        if (node instanceof AST.ClassDecl) {
            addTypeName(((AST.ClassDecl) node).superClass, out);
            // fall through to the generic walk for members / ctor params
        } else if (node instanceof AST.NewExpr) {
            AST.NewExpr n = (AST.NewExpr) node;
            addTypeName(n.className, out);
            if (n.typeArgs != null) for (String t : n.typeArgs) addTypeName(t, out);
            collectTypeRefs(n.args, out);
            return;
        } else if (node instanceof AST.NewArrayExpr) {
            AST.NewArrayExpr a = (AST.NewArrayExpr) node;
            addTypeName(a.type, out);
            collectTypeRefs(a.size, out);
            return;
        } else if (node instanceof AST.CatchClause) {
            AST.CatchClause c = (AST.CatchClause) node;
            addTypeName(c.type, out);
            collectTypeRefs(c.body, out);
            return;
        } else if (node instanceof AST.ForEachStmt) {
            AST.ForEachStmt fe = (AST.ForEachStmt) node;
            addTypeName(fe.varType, out);
            collectTypeRefs(fe.iterable, out);
            collectTypeRefs(fe.body, out);
            return;
        } else if (node instanceof AST.MethodCallExpr) {
            AST.MethodCallExpr mc = (AST.MethodCallExpr) node;
            // Static-call candidate: `Process.exec(...)` names the class as the receiver.
            if (mc.object instanceof AST.Identifier) {
                addTypeName(((AST.Identifier) mc.object).name, out);
            }
            collectTypeRefs(mc.object, out);
            collectTypeRefs(mc.args, out);
            return;
        } else if (node instanceof AST.FunctionRefExpr) {
            AST.FunctionRefExpr fr = (AST.FunctionRefExpr) node;
            if (fr.receiver instanceof AST.Identifier) {
                addTypeName(((AST.Identifier) fr.receiver).name, out);
            }
            collectTypeRefs(fr.receiver, out);
            return;
        } else if (node instanceof AST.LikeExpr) {
            addTypeName(((AST.LikeExpr) node).className, out);
            collectTypeRefs(((AST.LikeExpr) node).expr, out);
            return;
        } else if (node instanceof AST.LambdaExpr) {
            AST.LambdaExpr lam = (AST.LambdaExpr) node;
            addTypeName(lam.returnType, out);
            collectTypeRefs(lam.body, out);
            return;
        }

        for (java.lang.reflect.Field f : node.getClass().getFields()) {
            try {
                Object v = f.get(node);
                if (v instanceof AST) {
                    collectTypeRefs((AST) v, out);
                } else if (v instanceof java.util.List) {
                    for (Object x : (java.util.List<?>) v) {
                        if (x instanceof AST) collectTypeRefs((AST) x, out);
                    }
                }
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    /**
     * Normalize a type string to candidate class names: strips array dims, walks into
     * generic arguments (Function&lt;Void, Error&gt; contributes Function AND Error),
     * keeps plain identifiers only.
     */
    private static void addTypeName(String raw, java.util.Set<String> out) {
        if (raw == null) return;
        String s = raw.trim();
        if (s.isEmpty()) return;
        while (s.endsWith("]")) {
            int i = s.lastIndexOf('[');
            if (i <= 0) return;
            s = s.substring(0, i).trim();
        }
        int lt = s.indexOf('<');
        if (lt > 0 && s.endsWith(">")) {
            addTypeName(s.substring(0, lt), out);
            for (String part : splitTopLevelArgs(s.substring(lt + 1, s.length() - 1))) {
                addTypeName(part, out);
            }
            return;
        }
        if (s.indexOf('/') >= 0) return;
        if (!s.matches("[A-Za-z_][A-Za-z_0-9]*")) return;
        out.add(s);
    }

    /** Split on top-level commas (nesting-aware for &lt;&gt; () []). */
    private static List<String> splitTopLevelArgs(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) parts.add(s.substring(start));
        return parts;
    }

    /**
     * Load an imported file and return its members (excluding imports and namespace).
     */
    private static List<AST> loadImport(String importPath, String sourceRoot,
                                         java.util.Set<String> visitedFiles) throws IOException {
        List<AST> members = new ArrayList<>();

        String importFile = resolveImportFile(sourceRoot, importPath);
        if (importFile == null) {
            // Not found in stdlib or source dir
            // Find the import line in source code
            int impLine = 1;
            String[] srcLines = sourceCode.split("\n", -1);
            for (int i = 0; i < srcLines.length; i++) {
                if (srcLines[i].contains("import") && srcLines[i].contains(importPath)) {
                    impLine = i + 1;
                    break;
                }
            }
            String srcLine = (impLine <= srcLines.length) ? srcLines[impLine - 1] : "";
            int impCol = srcLine.contains(importPath) ? srcLine.indexOf(importPath) + 1 : 1;
            throw new CompileError("Cannot find import: " + importPath, sourceFile, impLine, impCol, srcLine, importPath.length());
        }

        String canonical = new File(importFile).getCanonicalPath();
        if (visitedFiles.contains(canonical)) return members;
        visitedFiles.add(canonical);

        log.println("      \u2192 " + new File(importFile).getName());
        String importCode = Files.readString(Path.of(importFile));

        // Save current source context, switch to imported file
        String prevSourceFile = sourceFile;
        String prevSourceCode = sourceCode;
        sourceFile = importFile;
        sourceCode = importCode;

        try {
            Lexer importLexer = new Lexer(importCode);
            List<Token> importTokens;
            try {
                importTokens = importLexer.tokenize();
            } catch (RuntimeException e) {
                // Lexical errors in an imported file must render like any other diagnostic —
                // unwrapped they surface as "Internal error" + a Java stack trace (debug.md #27).
                throw wrapError(e);
            }
            Parser importParser = new Parser(importTokens);
            AST.Program importProgram;
            try {
                importProgram = importParser.parse();
                importProgram.setSourceFile(importFile);
            } catch (RuntimeException e) {
                // Format error with imported file context
                throw wrapError(e);
            } catch (StackOverflowError e) {
                throw new CompileError("Expression nesting too deep", importFile, 1, 1, "", 1);
            }

            // Find ClassDecl and attach functions as methods
            AST.ClassDecl classDecl = null;
            for (AST member : importProgram.members) {
                if (member instanceof AST.ClassDecl) {
                    classDecl = (AST.ClassDecl) member;
                    classDecl.isEntryPoint = false;
                    break;
                }
            }

            // Move topLevelBody members to class members (for imported files)
            if (classDecl != null && !classDecl.topLevelBody.isEmpty()) {
                for (AST stmt : classDecl.topLevelBody) {
                    if (stmt instanceof AST.FuncDecl || stmt instanceof AST.FieldDecl
                            || stmt instanceof AST.ConstructorDecl) {
                        classDecl.members.add(stmt);
                    }
                }
                classDecl.topLevelBody.clear();
            }

            // Load nested imports FIRST (parents must come before children)
            for (AST member : importProgram.members) {
                if (member instanceof AST.ImportDecl) {
                    members.addAll(loadImport(((AST.ImportDecl) member).path, sourceRoot, visitedFiles));
                }
            }

            // Then add this file's classes/members
            String importNamespace = "";
            for (AST member : importProgram.members) {
                if (member instanceof AST.NamespaceDecl) {
                    importNamespace = ((AST.NamespaceDecl) member).path;
                    break;
                }
            }
            for (AST member : importProgram.members) {
                // Pass import declarations through so "import ... as Alias" aliases declared
                // inside imported files register too (nested loading itself already happened).
                if (member instanceof AST.ImportDecl) {
                    members.add(member);
                    continue;
                }
                if (member instanceof AST.NamespaceDecl) continue;

                if (member instanceof AST.ClassDecl) {
                    ((AST.ClassDecl) member).namespace = importNamespace;
                    members.add(member);
                } else if (member instanceof AST.FuncDecl && classDecl != null) {
                    classDecl.members.add(member);
                } else if (member instanceof AST.FuncDecl) {
                    members.add(member);
                }
            }
        } finally {
            // Restore source context
            sourceFile = prevSourceFile;
            sourceCode = prevSourceCode;
        }

        return members;
    }

    /**
     * Collect import paths from a program.
     */
    private static List<String> collectImports(AST.Program program) {
        List<String> imports = new ArrayList<>();
        for (AST member : program.members) {
            if (member instanceof AST.ImportDecl) {
                imports.add(((AST.ImportDecl) member).path);
            }
        }
        return imports;
    }

    /**
     * Resolve import path to file path.
     * "cc/ruok/Apple" → "<baseDir>/cc/ruok/Apple.cang"
     * "cang/lang/Stdout" → "<projectDir>/stdlib/cang/lang/Stdout.cang"
     * "cang/io/File" → "<projectDir>/stdlib/cang/io/File.cang"
     */
    private static String resolveImportFile(String baseDir, String importPath) {
        String relative = importPath.replace('/', java.io.File.separatorChar);

        // Try stdlib directory first for cang/* imports (lang, io, ...)
        if (importPath.startsWith("cang/")) {
            String stdlibDir = findStdlibDir();
            if (stdlibDir != null) {
                String stdlibPath = stdlibDir + java.io.File.separator + relative + ".cang";
                if (new File(stdlibPath).exists()) return stdlibPath;
            }
        }

        // Try relative to base directory
        String filePath = baseDir + java.io.File.separator + relative + ".cang";
        if (new File(filePath).exists()) return filePath;

        // Try without .cang extension
        filePath = baseDir + java.io.File.separator + relative;
        if (new File(filePath).exists()) return filePath;

        return null;
    }

    /**
     * Find the stdlib directory: working directory first, then the packaged-jar
     * fallback (bundled resources extracted to <code dir>/../stdlib). No hardcoded paths.
     */
    private static String findStdlibDir() {
        String cwd = System.getProperty("user.dir");
        String[] candidates = {
            cwd + File.separator + "stdlib",
            cwd + File.separator + ".." + File.separator + "stdlib"
        };
        for (String dir : candidates) {
            if (new File(dir).isDirectory()) {
                try {
                    return new File(dir).getCanonicalPath();
                } catch (IOException e) {
                    return dir;
                }
            }
        }
        // Packaged jar: extract bundled stdlib resources to <code dir>/../stdlib.
        try {
            java.net.URI uri = Cang.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path loc = Path.of(uri).toAbsolutePath();
            // jar file -> its containing folder; running from classes -> the classes folder's parent
            Path jarDir = loc.getParent();
            if (jarDir != null) {
                return extractBundledStdlib(jarDir.resolve("..").resolve("stdlib").normalize());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Standard library files shipped as classpath resources inside the packaged jar (paths relative to stdlib/). */
    private static final String[] BUNDLED_STDLIB_FILES = {
        "cang/lang/Object.cang", "cang/lang/Stdout.cang", "cang/lang/Stderr.cang", "cang/lang/String.cang",
        "cang/lang/Math.cang", "cang/lang/System.cang", "cang/lang/Function.cang",
        "cang/lang/Void.cang", "cang/lang/Thread.cang", "cang/lang/Error.cang",
        "cang/lang/List.cang",
        "cang/lang/Dict.cang",
        "cang/io/File.cang",
        "cang/io/Scanner.cang",
        "cang/io/Process.cang",
        "cang/lang/StringBuilder.cang"
    };

    /**
     * Extract the stdlib bundled in the jar to targetDir so imports can be read as files.
     * Returns null when no bundled stdlib is present (e.g. running from target/classes
     * without the resources on the classpath).
     */
    private static String extractBundledStdlib(Path targetDir) {
        if (bundledStdlibDir != null) return bundledStdlibDir;
        try {
            Path root = targetDir;
            if (!Files.isDirectory(root)) Files.createDirectories(root);
            boolean found = false;
            for (String rel : BUNDLED_STDLIB_FILES) {
                try (java.io.InputStream in = Cang.class.getResourceAsStream("/stdlib/" + rel)) {
                    if (in == null) continue;
                    found = true;
                    File out = root.resolve(rel).toFile();
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    Files.copy(in, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!found) return null;
            bundledStdlibDir = root.toFile().getCanonicalPath();
            return bundledStdlibDir;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Wrap a RuntimeException into a CompileError with source location.
     * Parses line info from message if present (e.g. "at line 9").
     */
    private static CompileError wrapError(RuntimeException e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

        // Try to extract line number from message
        int line = extractLine(msg);
        int col = 1;
        String srcLine = "";
        int caretLen = 1;

        if (line > 0 && line <= sourceCode.split("\n", -1).length) {
            String[] lines = sourceCode.split("\n", -1);
            srcLine = lines[line - 1];
            // Try to extract column
            col = extractColumn(msg, srcLine);
            caretLen = estimateLength(msg, srcLine, col);
        }

        // Remove "at line X, column Y" from message for cleaner display
        String cleanMsg = msg.replaceAll("\\s*\\(at line \\d+(?:, column \\d+)?\\)", "")
                             .replaceAll("\\s*at line \\d+(?:, column \\d+)?", "")
                             .replaceAll("\\s*\\(line \\d+\\)", "");

        return new CompileError(cleanMsg, sourceFile, line, col, srcLine, caretLen);
    }

    private static int extractLine(String msg) {
        // Match "line 9" or "line 9, column 5"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("line (\\d+)").matcher(msg);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 1;
    }

    private static int extractColumn(String msg, String srcLine) {
        // Special case: "Static methods must use 'func'" → find method name (before column check)
        if (msg.contains("Static methods must use")) {
            java.util.regex.Matcher mFunc = java.util.regex.Pattern.compile("static func \\w+ (\\w+)").matcher(msg);
            if (mFunc.find()) {
                String methodName = mFunc.group(1);
                int idx = srcLine.indexOf(methodName);
                if (idx >= 0) return idx + 1;
            }
        }

        // Try to match "column X"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("column (\\d+)").matcher(msg);
        if (m.find()) return Integer.parseInt(m.group(1));

        // Special case: "Unknown method: X.Y" → find method call
        if (msg.contains("Unknown method:")) {
            java.util.regex.Matcher mMethod = java.util.regex.Pattern.compile("Unknown method: [^ ]+\\.([^ ]+)").matcher(msg);
            if (mMethod.find()) {
                String methodName = mMethod.group(1);
                // Find "methodName(" in source
                int idx = srcLine.indexOf(methodName + "(");
                if (idx >= 0) return idx + 1;
            }
        }

        // Special case: "Cannot compare X with Y" → find comparison operator
        if (msg.contains("Cannot compare")) {
            String[] ops = {"==", "!=", "<=", ">=", "<", ">"};
            for (String op : ops) {
                int idx = srcLine.indexOf(op);
                if (idx >= 0) return idx + 1;
            }
        }

        // Special case: "Undefined variable: X" → find X
        if (msg.contains("Undefined variable:")) {
            java.util.regex.Matcher mVar = java.util.regex.Pattern.compile("Undefined variable: (\\w+)").matcher(msg);
            if (mVar.find()) {
                String varName = mVar.group(1);
                int idx = srcLine.indexOf(varName);
                if (idx >= 0) return idx + 1;
            }
        }

        // Special case: System field errors → point at field name after "System."
        if (msg.contains("System field") || msg.contains("System.")) {
            java.util.regex.Matcher mSys = java.util.regex.Pattern.compile("System field: (\\w+)|System\\.(\\w+)").matcher(msg);
            if (mSys.find()) {
                String field = mSys.group(1) != null ? mSys.group(1) : mSys.group(2);
                int sysIdx = srcLine.indexOf("System");
                int idx = sysIdx >= 0 ? srcLine.indexOf(field, sysIdx) : srcLine.indexOf(field);
                if (idx >= 0) return idx + 1;
            }
        }

        // Special case: "Cannot access field 'X' on 'this'" → find "this"
        if (msg.contains("'this'") || msg.contains("on 'this'")) {
            int idx = srcLine.indexOf("this");
            if (idx >= 0) return idx + 1;
        }

        // Extract quoted identifiers and find in source
        java.util.regex.Matcher mQuoted = java.util.regex.Pattern.compile("'([^']+)'").matcher(msg);
        while (mQuoted.find()) {
            String token = mQuoted.group(1);
            if (token.length() <= 1) continue; // skip single chars like 'i'
            int idx = srcLine.indexOf(token);
            if (idx >= 0) return idx + 1;
        }

        // Try quoted identifiers again (including short ones)
        mQuoted.reset();
        while (mQuoted.find()) {
            String token = mQuoted.group(1);
            int idx = srcLine.indexOf(token);
            if (idx >= 0) return idx + 1;
        }

        // Try "got X" pattern
        java.util.regex.Matcher mGot = java.util.regex.Pattern.compile("got \\w+ \\(([^)]+)\\)").matcher(msg);
        if (mGot.find()) {
            String token = mGot.group(1);
            int idx = srcLine.indexOf(token);
            if (idx >= 0) return idx + 1;
        }

        // Try keyword names
        String[][] kwMap = {
            {"SEMICOLON", ";"}, {"LPAREN", "("}, {"RPAREN", ")"},
            {"LBRACE", "{"}, {"RBRACE", "}"}, {"COMMA", ","},
            {"DOT", "."}, {"ASSIGN", "="}
        };
        for (String[] pair : kwMap) {
            if (msg.contains(pair[0])) {
                int idx = srcLine.indexOf(pair[1]);
                if (idx >= 0) return idx + 1;
            }
        }

        return 1;
    }

    private static int estimateLength(String msg, String srcLine, int col) {
        if (srcLine.isEmpty() || col < 1 || col > srcLine.length()) return 1;

        int start = col - 1;

        // For comparison errors, underline the operator (2 chars)
        if (msg.contains("Cannot compare")) {
            if (start + 1 < srcLine.length()) {
                String twoChar = srcLine.substring(start, start + 2);
                if (twoChar.equals("==") || twoChar.equals("!=") ||
                    twoChar.equals("<=") || twoChar.equals(">=")) {
                    return 2;
                }
            }
            return 1;
        }

        // Underline identifier/keyword at column
        int end = start;
        while (end < srcLine.length() && (Character.isLetterOrDigit(srcLine.charAt(end)) || srcLine.charAt(end) == '_')) {
            end++;
        }
        if (end == start) end = start + 1;
        return Math.min(end - start, srcLine.length() - start);
    }

    private static String formatUnexpectedError(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u001B[31merror\u001B[0m: ").append(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()).append("\n");
        sb.append("  --> ").append(sourceFile).append("\n");
        sb.append("   |\n");
        sb.append("   = Internal compiler error\n");
        return sb.toString();
    }

    public static void compileWindows(String llFile, String baseName,
                                        String clangPath, String gccPath, boolean gc, String libRoot) throws IOException, InterruptedException {
        String objFile = baseName + ".o";
        String exeFile = baseName + ".exe";

        // Auto-detect clang
        if (!new java.io.File(clangPath).exists()) {
            clangPath = findTool("clang.exe", new String[]{
                "D:\\Program Files\\LLVM\\bin\\clang.exe",
                "C:\\Program Files\\LLVM\\bin\\clang.exe"
            });
        }

        // Auto-detect gcc
        if (!new java.io.File(gccPath).exists()) {
            gccPath = findTool("gcc.exe", new String[]{
                "D:\\Program Files\\mingw64\\bin\\gcc.exe",
                "C:\\mingw64\\bin\\gcc.exe",
                "D:\\mingw64\\bin\\gcc.exe"
            });
        }

        log.println();
        log.println("[4/4] Native compilation");
        log.println("      clang: " + clangPath);
        log.println("      gcc:   " + gccPath);

        List<String> linkArgs = new ArrayList<>();
        linkArgs.add(gccPath);
        linkArgs.add(objFile);
        linkArgs.add("-o");
        linkArgs.add(exeFile);
        if (gc) {
            String gcLib = findGcLib("windows", libRoot);
            if (gcLib == null) {
                throw new LinkFailed("Boehm GC static library not found for windows target");
            }
            linkArgs.add(gcLib);
            log.println("      gc:     " + gcLib);
        }

        long t1 = System.currentTimeMillis();
        log.print("      Compiling .ll -> .o ... ");
        ProcessBuilder pb1 = new ProcessBuilder(clangPath,
            "-target", "x86_64-w64-windows-gnu",
            "-c", llFile, "-o", objFile);
        pb1.redirectErrorStream(true);
        Process p1 = pb1.start();
        String clangOut = new String(p1.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p1.waitFor() != 0) {
            log.println("FAILED");
            throw new LinkFailed("clang compilation failed", clangOut.trim());
        }
        log.println("OK (" + (System.currentTimeMillis() - t1) + "ms)");

        long t2 = System.currentTimeMillis();
        log.print("      Linking .o -> .exe ... ");
        ProcessBuilder pb2 = new ProcessBuilder(linkArgs);
        pb2.redirectErrorStream(true);
        Process p2 = pb2.start();
        String linkOut = new String(p2.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p2.waitFor() == 0) {
            log.println("OK (" + (System.currentTimeMillis() - t2) + "ms)");
            log.println();
            log.println("[OK] Compiled: " + exeFile);
        } else {
            log.println("FAILED");
            throw new LinkFailed("link failed", linkOut.trim());
        }
    }

    /**
     * Locate the Boehm GC static library FILE for --gc linking.
     * override accepts a file path or a directory containing libgc.a
     * (plus lib/libgc_<os>_amd64.a inside that directory).
     * Default search: ONLY lib/libgc_<os>_amd64.a under cwd and its parent.
     */
    private static String findGcLib(String target, String override) {
        String osKey = target.equals("windows") ? "window" : target.equals("macos") ? "mac" : target;
        String altKey = target.equals("windows") ? "windows" : osKey;
        String[] libNames = { "libgc_" + osKey + "_amd64.a", "libgc_" + altKey + "_amd64.a" };
        // 1) explicit --gc-lib / CompileOptions.libRoot: a file as-is, or a directory:
        //    <dir>/libgc.a, <dir>/lib/libgc.a, or <dir>/lib/libgc_<os>_amd64.a (project layout)
        if (override != null) {
            File f = new File(override);
            if (f.isFile()) return f.getAbsolutePath();
            File inDir = new File(f, "libgc.a");
            if (inDir.isFile()) return inDir.getAbsolutePath();
            File inLibGeneric = new File(f, "lib" + File.separator + "libgc.a");
            if (inLibGeneric.isFile()) return inLibGeneric.getAbsolutePath();
            for (String name : libNames) {
                File inLibDir = new File(f, "lib" + File.separator + name);
                if (inLibDir.isFile()) {
                    try {
                        return inLibDir.getCanonicalPath();
                    } catch (IOException e) {
                        return inLibDir.getAbsolutePath();
                    }
                }
            }
        }
        // 2) default: ONLY lib/libgc_<os>_amd64.a under cwd and its parent
        //    (the old runtime/boehm + boehm-build fallbacks are removed —
        //    they are machine-local leftovers and absent on other devices)
        String cwd = System.getProperty("user.dir");
        String[] bases = { cwd, cwd + File.separator + ".." };
        for (String base : bases) {
            for (String name : libNames) {
                File f = new File(base, "lib" + File.separator + name);
                if (f.isFile()) {
                    try {
                        return f.getCanonicalPath();
                    } catch (IOException e) {
                        return f.getAbsolutePath();
                    }
                }
            }
        }
        return null;
    }

    private static String findTool(String toolName, String[] candidates) {
        for (String path : candidates) {
            if (new java.io.File(path).exists()) return path;
        }
        try {
            Process p = new ProcessBuilder("where", toolName).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            if (line != null && !line.isBlank()) return line.trim();
        } catch (Exception ignored) {}
        return toolName;
    }

    public static void compileLinux(String llFile, String baseName, boolean gc, String libRoot) throws IOException, InterruptedException {
        String exeFile = baseName;
        String wslLlFile = toWslPath(llFile);
        String wslExeFile = toWslPath(exeFile);

        String gcArgs = "";
        if (gc) {
            String gcLib = findGcLib("linux", libRoot);
            if (gcLib == null) {
                throw new LinkFailed("Boehm GC static library not found for linux target");
            }
            gcArgs = " \"" + toWslPath(gcLib) + "\"";
        }

        log.println("Compiling for Linux via WSL...");
        ProcessBuilder pb = new ProcessBuilder("wsl",
            "sh", "-c",
            "clang -target x86_64-unknown-linux-gnu -fuse-ld=lld \"" + wslLlFile + "\"" + gcArgs + " -o \"" + wslExeFile + "\"");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String wslOut = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.waitFor() == 0) {
            log.println("Success: " + exeFile + " (Linux ELF)");
        } else {
            throw new LinkFailed("linux link failed", wslOut.trim());
        }
    }

    public static void compileMacOS(String llFile, String baseName, boolean gc, String libRoot) throws IOException, InterruptedException {
        throw new LinkFailed("macOS target is not implemented");
    }

    private static String toWslPath(String winPath) {
        String p = winPath.replace('\\', '/');
        if (p.length() >= 2 && p.charAt(1) == ':') {
            char drive = Character.toLowerCase(p.charAt(0));
            p = "/mnt/" + drive + p.substring(2);
        }
        return p;
    }

    /**
     * Resolve a Cang source argument:
     *   cc/ruok/Demo.cang   -> ./cc/ruok/Demo.cang (direct, relative to the working directory)
     *   cc/ruok/Demo        -> src/cc/ruok/Demo.cang (no extension = namespace under src/)
     *   D:/x/Demo           -> absolute, no src/ prefix; .cang appended in place
     */
    private static String resolveSourcePath(String arg) {
        String normalized = arg.replace('\\', '/');
        boolean hasExt = normalized.endsWith(".cang");
        String withExt = hasExt ? normalized : normalized + ".cang";
        String flat = withExt.replace('/', File.separatorChar);
        String candidate;
        if (hasExt || new File(arg).isAbsolute()) {
            candidate = flat;
        } else {
            candidate = "src" + File.separator + flat;
        }
        if (new File(candidate).isFile()) return candidate;
        System.err.println("error: cannot find source: " + arg);
        System.err.println("  searched: " + candidate);
        System.exit(1);
        return null;
    }

    /** Build artifacts always go to target/<source name> (e.g. target/Main.exe). */
    private static String outputFileBase(String sourceFile) {
        String simple = new File(sourceFile).getName();
        if (simple.endsWith(".cang")) simple = simple.substring(0, simple.length() - ".cang".length());
        try {
            Files.createDirectories(Path.of("target"));
        } catch (IOException ignored) {}
        return "target" + File.separator + simple;
    }
}
