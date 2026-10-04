import codegen.LLVMGen;
import lexer.Lexer;
import lexer.Token;
import parser.AST;
import parser.Parser;
import util.CompileError;

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
        String gcLibDir = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--no-link": noLink = true; break;
                case "--target": target = requireValue(args, ++i, "--target", "windows|linux|macos").toLowerCase(); break;
                case "--arch": architecture = requireValue(args, ++i, "--arch", "amd64|...").toLowerCase(); break;
                case "--clang": clangPath = requireValue(args, ++i, "--clang", "<path>"); break;
                case "--gcc": gccPath = requireValue(args, ++i, "--gcc", "<path>"); break;
                case "--gc": gc = true; break;
                case "--no-gc": gc = false; break;
                case "--gc-lib": gcLibDir = requireValue(args, ++i, "--gc-lib", "<dir>"); break;
                default:
                    System.err.println("error: unknown option '" + args[i] + "'");
                    printUsage();
                    System.exit(1);
            }
        }

        sourceFile = filename;
        sourceCode = Files.readString(Path.of(filename));

        String llFile;
        try {
            llFile = compile(filename, sourceCode, noLink, target, architecture, gc);
        } catch (CompileError e) {
            System.err.println(e.format());
            System.exit(1);
            return;
        } catch (Exception e) {
            System.err.println("Internal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
            return;
        }

        if (noLink || llFile == null) {
            System.out.println("Done (--no-link: skipping compilation)");
            return;
        }

        String baseName = outputFileBase(filename);
        switch (target) {
            case "windows": compileWindows(llFile, baseName, clangPath, gccPath, gc, gcLibDir); break;
            case "linux":   compileLinux(llFile, baseName, gc, gcLibDir); break;
            case "macos":   compileMacOS(llFile, baseName, gc, gcLibDir); break;
            default:
                System.err.println("error: unknown target '" + target + "'; expected windows|linux|macos");
                printUsage();
                System.exit(1);
        }
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

    private static void printUsage() {
        System.err.println("Usage: Cang <source.cang> [options]");
        System.err.println("  --no-link          compile to .ll only (skip clang/gcc)");
        System.err.println("  --target <t>       windows | linux | macos   (default: windows)");
        System.err.println("  --arch <a>         target architecture         (default: amd64)");
        System.err.println("  --clang <path>     clang executable");
        System.err.println("  --gcc <path>       gcc executable");
        System.err.println("  --gc / --no-gc     Boehm GC on (default) / manual malloc-free");
        System.err.println("  --gc-lib <dir>     override libgc lookup directory");
    }

    private static String compile(String filename, String code, boolean noLink, String target, String architecture, boolean gc) throws IOException {
        long startTime = System.currentTimeMillis();

        // 1. Lexing
        System.out.println();
        System.out.println("\u001B[1m[1/3] Lexing\u001B[0m  " + filename);
        long lexStart = System.currentTimeMillis();
        Lexer lexer = new Lexer(code);
        List<Token> tokens;
        try {
            tokens = lexer.tokenize();
        } catch (RuntimeException e) {
            throw wrapError(e);
        }
        long lexTime = System.currentTimeMillis() - lexStart;
        System.out.println("      " + tokens.size() + " tokens (" + lexTime + "ms)");

        // 2. Parsing (main file)
        System.out.println("\u001B[1m[2/3] Parsing\u001B[0m");
        long parseStart = System.currentTimeMillis();
        Parser parser = new Parser(tokens);
        AST.Program mainProgram;
        try {
            mainProgram = parser.parse();
            mainProgram.setSourceFile(filename);
        } catch (RuntimeException e) {
            throw wrapError(e);
        }

        // 2b. Resolve imports - parse imported files and merge
        AST.Program fullProgram = resolveImports(filename, mainProgram);
        long parseTime = System.currentTimeMillis() - parseStart;
        System.out.println("      " + fullProgram.members.size() + " top-level nodes (" + parseTime + "ms)");

        // 3. Code generation
        System.out.println("\u001B[1m[3/3] Generating LLVM IR\u001B[0m");
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
        String baseName = outputFileBase(filename);
        String llFile = baseName + ".ll";
        Files.writeString(Path.of(llFile), llvmIR);
        long llLines = llvmIR.split("\n").length;
        System.out.println("      " + llFile + " (" + llLines + " lines, " + genTime + "ms)");

        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println("\u001B[2m      Total: " + totalTime + "ms\u001B[0m");

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
        String mainPath = new File(mainFilename).getCanonicalPath().replace('\\', '/');
        String sourceRoot = mainPath;
        if (!namespace.isEmpty()) {
            // Remove namespace path from main file path
            String nsPath = "/" + namespace.replace('/', '/');
            int idx = sourceRoot.lastIndexOf(nsPath);
            if (idx > 0) {
                sourceRoot = sourceRoot.substring(0, idx);
            }
        } else {
            // No namespace: source root is main file's directory
            int lastSlash = mainPath.lastIndexOf('/');
            sourceRoot = lastSlash >= 0 ? mainPath.substring(0, lastSlash) : ".";
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

        // Auto-load classes in the same namespace (no explicit import needed)
        if (!namespace.isEmpty()) {
            String nsDir = sourceRoot + "/" + namespace.replace('/', File.separatorChar);
            File dir = new File(nsDir);
            if (dir.isDirectory()) {
                File[] files = dir.listFiles((d, name) -> name.endsWith(".cang"));
                if (files != null) {
                    for (File f : files) {
                        String canonical = f.getCanonicalPath();
                        if (visitedFiles.contains(canonical)) continue;
                        // Skip files already explicitly imported
                        boolean explicitlyImported = false;
                        List<String> imports = collectImports(mainProgram);
                        for (String imp : imports) {
                            String impFile = resolveImportFile(sourceRoot, imp);
                            if (impFile != null && new File(impFile).getCanonicalPath().equals(canonical)) {
                                explicitlyImported = true;
                                break;
                            }
                        }
                        if (explicitlyImported) continue;

                        System.out.println("      \u2192 " + f.getName());
                        visitedFiles.add(canonical);
                        String code = Files.readString(f.toPath());

                        // Save source context
                        String prevFile = sourceFile;
                        String prevCode = sourceCode;
                        sourceFile = f.getPath();
                        sourceCode = code;

                        try {
                            Lexer lexer = new Lexer(code);
                            List<Token> tokens = lexer.tokenize();
                            Parser parser = new Parser(tokens);
                            AST.Program prog;
                            try {
                                prog = parser.parse();
                                prog.setSourceFile(f.getPath());
                            } catch (RuntimeException e) {
                                throw wrapError(e);
                            }

                            // Find class and attach methods
                            AST.ClassDecl classDecl = null;
                            for (AST m : prog.members) {
                                if (m instanceof AST.ClassDecl) {
                                    classDecl = (AST.ClassDecl) m;
                                    classDecl.isEntryPoint = false;
                                    break;
                                }
                            }
                            if (classDecl != null) {
                                if (!classDecl.topLevelBody.isEmpty()) {
                                    for (AST stmt : classDecl.topLevelBody) {
                                        if (stmt instanceof AST.FuncDecl || stmt instanceof AST.FieldDecl) {
                                            classDecl.members.add(stmt);
                                        }
                                    }
                                    classDecl.topLevelBody.clear();
                                }
                                // Load nested imports first
                                for (AST m : prog.members) {
                                    if (m instanceof AST.ImportDecl) {
                                        allMembers.addAll(loadImport(
                                            ((AST.ImportDecl) m).path, sourceRoot, visitedFiles));
                                    }
                                }
                                // Add the class
                                for (AST m : prog.members) {
                                    if (m instanceof AST.ClassDecl) {
                                        allMembers.add(m);
                                    }
                                }
                            }
                        } finally {
                            // Restore source context
                            sourceFile = prevFile;
                            sourceCode = prevCode;
                        }
                    }
                }
            }
        }

        // Add main file's members LAST (after imports so parents are collected first)
        for (AST member : mainProgram.members) {
            allMembers.add(member);
        }

        return new AST.Program(allMembers);
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

        System.out.println("      \u2192 " + new File(importFile).getName());
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
                    if (stmt instanceof AST.FuncDecl) {
                        classDecl.members.add(stmt);
                    } else if (stmt instanceof AST.FieldDecl) {
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
     * Find the stdlib directory by searching up from working directory.
     * Falls back to the stdlib bundled inside the packaged jar.
     */
    private static String findStdlibDir() {
        String cwd = System.getProperty("user.dir");
        String[] candidates = {
            cwd + File.separator + "stdlib",
            cwd + File.separator + ".." + File.separator + "stdlib",
            "D:\\IDEA\\toy\\Cang\\stdlib"
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
        // Packaged jar: extract bundled stdlib resources to a temp directory once.
        return extractBundledStdlib();
    }

    /** Standard library files shipped as classpath resources inside the packaged jar (paths relative to stdlib/). */
    private static final String[] BUNDLED_STDLIB_FILES = {
        "cang/lang/Object.cang", "cang/lang/Stdout.cang", "cang/lang/Stderr.cang", "cang/lang/String.cang",
        "cang/lang/Math.cang", "cang/lang/System.cang", "cang/lang/Function.cang",
        "cang/lang/Void.cang", "cang/lang/Thread.cang", "cang/lang/Error.cang",
        "cang/lang/List.cang",
        "cang/lang/Dict.cang",
        "cang/io/File.cang"
    };

    /**
     * Extract the stdlib bundled in the jar to a temp directory so imports can be read as files.
     * Returns null when no bundled stdlib is present (e.g. running from target/classes).
     */
    private static String extractBundledStdlib() {
        if (bundledStdlibDir != null) return bundledStdlibDir;
        try {
            File root = new File(System.getProperty("java.io.tmpdir"), "cang-stdlib");
            if (!root.isDirectory() && !root.mkdirs()) return null;
            boolean found = false;
            for (String rel : BUNDLED_STDLIB_FILES) {
                try (java.io.InputStream in = Cang.class.getResourceAsStream("/stdlib/" + rel)) {
                    if (in == null) continue;
                    found = true;
                    File out = new File(root, rel);
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    Files.copy(in, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!found) return null;
            bundledStdlibDir = root.getCanonicalPath();
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

    private static void compileWindows(String llFile, String baseName,
                                        String clangPath, String gccPath, boolean gc, String gcLibDir) throws IOException, InterruptedException {
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

        System.out.println();
        System.out.println("[4/4] Native compilation");
        System.out.println("      clang: " + clangPath);
        System.out.println("      gcc:   " + gccPath);

        List<String> linkArgs = new ArrayList<>();
        linkArgs.add(gccPath);
        linkArgs.add(objFile);
        linkArgs.add("-o");
        linkArgs.add(exeFile);
        if (gc) {
            String dir = findGcLibDir("windows", gcLibDir);
            if (dir == null) {
                System.err.println("error: --gc requires Boehm GC static library libgc.a");
                System.err.println("       provide it at runtime/boehm/windows-amd64/lib/libgc.a or pass --gc-lib <dir>");
                System.exit(1);
            }
            linkArgs.add("-L" + dir);
            linkArgs.add("-lgc");
            System.out.println("      gc:     " + dir);
        }

        long t1 = System.currentTimeMillis();
        System.out.print("      Compiling .ll -> .o ... ");
        ProcessBuilder pb1 = new ProcessBuilder(clangPath,
            "-target", "x86_64-w64-windows-gnu",
            "-c", llFile, "-o", objFile);
        pb1.redirectErrorStream(true);
        pb1.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        if (pb1.start().waitFor() != 0) {
            System.out.println("FAILED");
            ProcessBuilder pbDebug = new ProcessBuilder(clangPath,
                "-target", "x86_64-w64-windows-gnu", "-c", llFile, "-o", objFile);
            pbDebug.inheritIO();
            pbDebug.start().waitFor();
            System.exit(1);
        }
        System.out.println("OK (" + (System.currentTimeMillis() - t1) + "ms)");

        long t2 = System.currentTimeMillis();
        System.out.print("      Linking .o -> .exe ... ");
        ProcessBuilder pb2 = new ProcessBuilder(linkArgs);
        pb2.redirectErrorStream(true);
        pb2.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        if (pb2.start().waitFor() == 0) {
            System.out.println("OK (" + (System.currentTimeMillis() - t2) + "ms)");
            System.out.println();
            System.out.println("[OK] Compiled: " + exeFile);
        } else {
            System.out.println("FAILED");
            ProcessBuilder pbDebug = new ProcessBuilder(linkArgs);
            pbDebug.inheritIO();
            pbDebug.start().waitFor();
            System.exit(1);
        }
    }

    /** Locate a Boehm GC static library directory for --gc linking. */
    private static String findGcLibDir(String target, String override) {
        List<String> candidates = new ArrayList<>();
        if (override != null) candidates.add(override);
        String cwd = System.getProperty("user.dir");
        candidates.add(cwd + File.separator + "runtime" + File.separator + "boehm" + File.separator + target + "-amd64" + File.separator + "lib");
        candidates.add(cwd + File.separator + ".." + File.separator + "runtime" + File.separator + "boehm" + File.separator + target + "-amd64" + File.separator + "lib");
        if (target.equals("windows")) {
            // Development build fallback produced from the bundled bdwgc sources.
            candidates.add(cwd + File.separator + "target" + File.separator + "boehm-build" + File.separator + "bdwgc");
        }
        for (String dir : candidates) {
            if (dir == null) continue;
            if (new File(dir, "libgc.a").isFile()) {
                try {
                    return new File(dir).getCanonicalPath();
                } catch (IOException e) {
                    return dir;
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

    private static void compileLinux(String llFile, String baseName, boolean gc, String gcLibDir) throws IOException, InterruptedException {
        String exeFile = baseName;
        String wslLlFile = toWslPath(llFile);
        String wslExeFile = toWslPath(exeFile);

        String gcArgs = "";
        if (gc) {
            String dir = findGcLibDir("linux", gcLibDir);
            if (dir == null) {
                System.err.println("error: --gc for Linux requires Boehm GC static library libgc.a");
                System.err.println("       provide it at runtime/boehm/linux-amd64/lib/libgc.a or pass --gc-lib <dir>");
                System.err.println("       (the library must be built for the Linux target, not Windows)");
                System.exit(1);
            }
            gcArgs = " -L\"" + toWslPath(dir) + "\" -lgc";
        }

        System.out.println("Compiling for Linux via WSL...");
        ProcessBuilder pb = new ProcessBuilder("wsl",
            "sh", "-c",
            "clang -target x86_64-unknown-linux-gnu -fuse-ld=lld \"" + wslLlFile + "\"" + gcArgs + " -o \"" + wslExeFile + "\"");
        pb.inheritIO();
        if (pb.start().waitFor() == 0) {
            System.out.println("Success: " + exeFile + " (Linux ELF)");
            System.out.println("Run in WSL: wsl ./" + baseName);
        } else {
            System.exit(1);
        }
    }

    private static void compileMacOS(String llFile, String baseName, boolean gc, String gcLibDir) throws IOException, InterruptedException {
        if (gc && findGcLibDir("macos", gcLibDir) == null) {
            System.err.println("error: --gc for macOS requires Boehm GC static library libgc.a");
            System.err.println("       provide it at runtime/boehm/macos-amd64/lib/libgc.a or pass --gc-lib <dir>");
        }
        System.exit(1);
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
     *   cc/ruok/Main        -> src/cc/ruok/Main.cang (preferred, namespace under src/)
     *   cc/ruok/Main.cang   -> src/cc/ruok/Main.cang (preferred)
     *   test/foo.cang       -> test/foo.cang (direct path fallback, keeps old workflows)
     */
    private static String resolveSourcePath(String arg) {
        String withExt = arg.endsWith(".cang") ? arg : arg + ".cang";
        String normalized = withExt.replace('\\', '/');
        String flat = normalized.replace('/', File.separatorChar);
        String direct = arg.replace('\\', '/').replace('/', File.separatorChar);
        String[] candidates = {
            "src" + File.separator + flat,
            flat,
            direct
        };
        java.util.List<String> searched = new java.util.ArrayList<>();
        for (String c : candidates) {
            if (new File(c).isFile()) return c;
            if (!searched.contains(c)) searched.add(c);
        }
        System.err.println("error: cannot find source: " + arg);
        for (String c : searched) System.err.println("  searched: " + c);
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
