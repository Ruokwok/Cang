package codegen;

import parser.AST.*;
import parser.AST;
import lexer.Lexer;
import lexer.Token;
import parser.Parser;

import java.util.*;

public class LLVMGen {

    // ==================== Data structures ====================

    static class LLVMValue {
        String value;
        String type;
        String semanticType; // Cang type when LLVM types are shared, e.g. str/String -> i8*
        LLVMValue(String value, String type) {
            this(value, type, null);
        }
        LLVMValue(String value, String type, String semanticType) {
            this.value = value;
            this.type = type;
            this.semanticType = semanticType;
        }
    }

    static class ClassInfo {
        String llvmName;
        String fullName;
        String simpleName;
        String parentName; // simple name of parent class
        int typeId; // unique type ID for runtime type checking
        List<String> fieldNames = new ArrayList<>();
        List<String> fieldTypes = new ArrayList<>();
        Map<String, Integer> fieldIndices = new LinkedHashMap<>();
        List<Parameter> ctorParams = new ArrayList<>();
        List<FieldDecl> staticFields = new ArrayList<>(); // static fields (global)
        List<AST> superArgs = new ArrayList<>(); // parent constructor args
        boolean isAbstract; // abstract class: not instantiable, may declare abstract methods
        boolean isPrivateCtor; // '_' modifier on the class name: new only inside the class itself
    }

    static class FuncInfo {
        String name;
        String returnType;
        List<String> paramTypes = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();
        List<AST> paramDefaults = new ArrayList<>(); // null if no default
        String className; // non-null for methods
        boolean isConstructor;
        boolean isStatic;
        boolean isFinal;
        boolean isNative;
        boolean isAbstract; // declaration only: subclasses must provide a concrete implementation
        /** Unique LLVM symbol: base name for the first registration, base.N for overloads. */
        String llvmName;
    }

    static class LoopContext {
        String breakLabel;
        String continueLabel;
    }

    static class Scope {
        Map<String, LLVMValue> vars = new LinkedHashMap<>();
        Scope parent;

        Scope(Scope parent) { this.parent = parent; }

        LLVMValue lookup(String name) {
            if (vars.containsKey(name)) return vars.get(name);
            if (parent != null) return parent.lookup(name);
            return null;
        }

        void define(String name, LLVMValue value) {
            vars.put(name, value);
        }
    }

    // ==================== State ====================

    private final StringBuilder header = new StringBuilder();
    private final StringBuilder body = new StringBuilder();
    private final StringBuilder mainBody = new StringBuilder();

    private int tmpCount = 0;
    private int labelCount = 0;
    // Native stack budget measured from the per-thread stack base: ~1MB Windows main thread
    // crashes at ~20-30k skinny frames; 960KB trips the friendly error before the raw
    // 0xC00000FD blowout while leaving headroom for the error path itself.
    private static final long STACK_BUDGET = 983040L;
    private int strCount = 0;

    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    // Simple names claimed by two or more classes from different namespaces (imported or
    // auto-loaded): simple-name lookup was dropped for them — full name still resolves.
    private final Map<String, java.util.Set<String>> ambiguousShortNames = new LinkedHashMap<>();
    // ClassDecl -> full name, recorded at collect time so generation never depends on the
    // (overwritable) simple-name alias.
    private final Map<ClassDecl, String> declFullNames = new java.util.IdentityHashMap<>();
    // Import aliases: "import cc/ruok/Server as RuokServer" — alias -> real simple name.
    // Type strings are normalized through this before monomorphization; expression positions
    // (new/static/like) resolve through the classes table alias key instead.
    private final Map<String, String> importAliases = new LinkedHashMap<>();
    private final Map<String, ClassDecl> genericTemplates = new LinkedHashMap<>();
    private final Map<String, String> genericTypeOwners = new LinkedHashMap<>();
    private final Map<String, FuncInfo> functions = new LinkedHashMap<>();
    // All same-name registrations (singletons included) for overload resolution; `functions`
    // keeps the FIRST registration as the legacy single-entry view.
    private final Map<String, List<FuncInfo>> overloadGroups = new LinkedHashMap<>();
    private final Map<String, String> stringLiterals = new LinkedHashMap<>(); // text 閳?global name
    private final List<LoopContext> loopStack = new ArrayList<>();
    private final Deque<String> exceptionHandlers = new ArrayDeque<>();
    /** One in-scope try-with-finally. inTryBody distinguishes a throw inside the try body
     *  (normal handler path — finally runs at `after`) from one inside the catch clause
     *  (must inline this layer's finally before propagating outward). */
    private static final class FinallyCtx {
        final AST finallyBlock;
        boolean inTryBody = true;
        FinallyCtx(AST finallyBlock) { this.finallyBlock = finallyBlock; }
    }
    /** Active try-with-finally layers, innermost first (push = enter try). */
    private final Deque<FinallyCtx> finallyStack = new ArrayDeque<>();

    private Scope scope;
    private String currentClassName;
    private String currentFuncReturnType = "void";
    private String currentGcFrame = null;
    private String currentNamespace = ""; // e.g. "cc/ruok/cang"
    private final List<String> imports = new ArrayList<>();
    private final Map<String, String> importedClasses = new HashMap<>(); // simpleName -> fullName
    private final Map<String, String> arrayElemTypes = new HashMap<>(); // varName -> elem LLVM type
    private final Map<String, String> arrayElemCangTypes = new HashMap<>(); // varName -> elem Cang type ("int", "Array<int>", ...)
    private final Map<String, FieldDecl> staticFields = new HashMap<>(); // "ClassName.field" -> FieldDecl
    private String sourceFile = "unknown";
    private String targetPlatform = "windows";
    private String targetArchitecture = "amd64";
    private final java.util.Set<String> finalVars = new java.util.HashSet<>(); // final variable names
    private final java.util.Set<String> freedVars = new java.util.HashSet<>();

    // Function-level region allocations. Only allocations proven not to escape are released.
    private final List<String> regionAllocations = new ArrayList<>();
    private boolean regionActive;
    private boolean regionStraightLine;

    private void beginRegion() {
        regionAllocations.clear();
        regionActive = true;
        regionStraightLine = true;
    }

    private void registerRegionAllocation(String ptr) {
        if (regionActive) regionAllocations.add(ptr);
    }

    private void endRegionCleanup() {
        // Automatic region freeing is disabled when explicit free is part of the language.
        // Keep allocations alive until the user explicitly releases them or the process exits.
        abandonRegion();
    }

    private void abandonRegion() {
        // Explicit returns and escaping stores are conservatively left to process teardown.
        regionAllocations.clear();
        regionActive = false;
        regionStraightLine = false;
    }

    private void markRegionControlFlow() {
        regionStraightLine = false;
    }

    private void emitGcFrameSetup() {
        currentGcFrame = null; // Automatic GC is intentionally disabled; memory is manually managed.
    }

    private void registerGcRoot(String allocaName, String llvmType) {
        // Automatic GC is disabled; explicit free manages heap objects.
    }

    private boolean isReferenceLLVMType(String llvmType) {
        return llvmType.equals("i8*") || llvmType.startsWith("%") && llvmType.endsWith("*");
    }

    private int gcArrayFlags(String elemType) {
        return 5 | (isReferenceLLVMType(elemType) ? 2 : 0);
    }

    private void emitGcFramePop() {
        currentGcFrame = null;
    }

    public void setSourceFile(String file) {
        this.sourceFile = file;
    }

    public void setTargetPlatform(String target) {
        this.targetPlatform = target == null ? "windows" : target.toLowerCase();
    }

    public void setTargetArchitecture(String architecture) {
        if (architecture == null) return;
        String value = architecture.toLowerCase();
        if (value.equals("x86_64")) value = "amd64";
        if (value.equals("arm64")) value = "aarch64";
        this.targetArchitecture = value;
    }

    // Boehm GC mode: heap allocation goes through GC_*, explicit free maps to GC_free.
    private boolean gcEnabled = false;

    public void setGcEnabled(boolean enabled) {
        this.gcEnabled = enabled;
    }

    private String allocFn() { return gcEnabled ? "GC_malloc" : "malloc"; }
    private String reallocFn() { return gcEnabled ? "GC_realloc" : "realloc"; }
    private String freeFn() { return gcEnabled ? "GC_free" : "free"; }

    // ==================== Entry point ====================

    /**
     * Register built-in classes in cang.lang namespace.
     * Object: base class for all classes.
     * Stdout: standard output with static print() method.
     */
    private void registerBuiltinObject() {
        // Object class
        ClassInfo objInfo = new ClassInfo();
        objInfo.llvmName = "%cang_lang_Object";
        objInfo.fullName = "cang_lang_Object";
        objInfo.simpleName = "Object";
        objInfo.parentName = null;
        objInfo.fieldNames.add("_dummy");
        objInfo.fieldTypes.add("byte");
        objInfo.fieldIndices.put("_dummy", 0);
        classes.put("Object", objInfo);

        FuncInfo ctorInfo = new FuncInfo();
        ctorInfo.name = "cang_lang_Object.constructor";
        ctorInfo.returnType = "void";
        ctorInfo.className = "cang_lang_Object";
        ctorInfo.isConstructor = true;
        functions.put(ctorInfo.name, ctorInfo);

        // Stdout class (static print method)
        ClassInfo stdoutInfo = new ClassInfo();
        stdoutInfo.llvmName = "%cang_lang_Stdout";
        stdoutInfo.fullName = "cang_lang_Stdout";
        stdoutInfo.simpleName = "Stdout";
        stdoutInfo.parentName = "Object";
        stdoutInfo.fieldNames.add("_dummy");
        stdoutInfo.fieldTypes.add("byte");
        stdoutInfo.fieldIndices.put("_dummy", 0);
        classes.put("Stdout", stdoutInfo);

        // Stdout.print is handled specially in generateMethodCall
    }

    public String generate(Program program) {
        try {
            return generateInternal(program);
        } catch (util.CompileError e) {
            throw e; // already carries precise file/line/column
        } catch (RuntimeException e) {
            // debug.md #27: codegen exceptions without "(at line N)" were rendered at 1:1.
            // Attach the line of the statement being generated when available.
            String msg = e.getMessage();
            if (lastStmtLine > 0 && msg != null && !msg.contains("at line")) {
                throw new RuntimeException(msg + " (at line " + lastStmtLine + ")");
            }
            throw e;
        }
    }

    /** Line of the statement currently being generated (for wrapping bare codegen errors). */
    private int lastStmtLine = -1;

    private String generateInternal(Program program) {
        // Register built-in Object class (cang.lang.Object)
        registerBuiltinObject();

        // Pass 0: process namespace and imports
        for (AST member : program.members) {
            if (member instanceof NamespaceDecl) {
                currentNamespace = ((NamespaceDecl) member).path;
            } else if (member instanceof ImportDecl) {
                String path = ((ImportDecl) member).path;
                imports.add(path);
                String[] parts = path.split("/");
                String last = parts[parts.length - 1];
                if (!last.isEmpty() && Character.isUpperCase(last.charAt(0))) {
                    importedClasses.put(last, path);
                }
            }
        }

        prepareGenericMonomorphization(program.members);
        List<ClassDecl> classDecls = new ArrayList<>();
        List<AST> topLevelFuncs = new ArrayList<>();
        List<AST> mainStatements = new ArrayList<>(); // top-level statements for main()
        ClassDecl entryClass = null;

        // Statements in `members` (outside any class) can only sit BEFORE the first class —
        // once a class exists they would be dropped by generateEntryPointMain. Reject them
        // (mirror of debug.md #36). A program with no class at all keeps them for auto-main.
        boolean hasClassDecl = false;
        for (AST m : program.members) {
            if (m instanceof ClassDecl) { hasClassDecl = true; break; }
        }

        for (AST member : program.members) {
            if (member instanceof ClassDecl) {
                ClassDecl cd = (ClassDecl) member;
                if (!cd.genericParams.isEmpty()) {
                    genericTemplates.put(cd.name, cd);
                    // Un-specialized template: never collected nor generated — its method bodies
                    // still reference the unsubstituted type parameter (invalid IR like %T*).
                    // prepareGenericMonomorphization materializes one member per used type argument.
                    continue;
                }
                // Classes without explicit parent inherit from Object (but Object itself has no parent)
                if (cd.superClass == null && !cd.name.equals("Object")) {
                    cd.superClass = "Object";
                }
                boolean isEntry = cd.isEntryPoint && entryClass == null;
                if (isEntry) {
                    entryClass = cd;
                }
                // Move FuncDecl/FieldDecl to members; keep statements in topLevelBody for entry
                for (AST stmt : new ArrayList<>(cd.topLevelBody)) {
                    if (stmt instanceof FuncDecl || stmt instanceof FieldDecl) {
                        cd.members.add(stmt);
                        cd.topLevelBody.remove(stmt);
                    } else if (!isEntry) {
                        // debug.md #36: only the entry class's statements run (they become the
                        // main body); statements hanging on any later class would land in
                        // mainStatements, which is never generated once an entry exists —
                        // silently dropped. Fail loudly instead.
                        throw new RuntimeException("statements must appear right after the entry class (first class in file); class '"
                            + cd.name + "' is not the entry class (at line " + stmt.line + ")");
                    }
                    // Entry class: statements stay in topLevelBody
                }
                classDecls.add(cd);
                collectClass(cd);
            } else if (member instanceof FuncDecl) {
                collectFunction((FuncDecl) member, null);
                topLevelFuncs.add(member);
            } else if (member instanceof NamespaceDecl || member instanceof ImportDecl) {
                // Skip namespace/import declarations
                continue;
            } else {
                // Top-level statement outside class — legal only in a class-less program;
                // with a class present it would be silently dropped (debug.md #36 pair).
                if (hasClassDecl) {
                    throw new RuntimeException("statements must appear after the first class (or in a class-less program); move this statement below the entry class (at line "
                        + member.line + ")");
                }
                mainStatements.add(member);
            }
        }

        // Register import aliases now that every class has been collected: alias -> same
        // ClassInfo as the real simple name (aliases never generate code of their own).
        for (AST member : program.members) {
            if (member instanceof ImportDecl) {
                ImportDecl imp = (ImportDecl) member;
                if (imp.alias == null || imp.alias.isEmpty()) continue;
                String real = imp.path.substring(imp.path.lastIndexOf('/') + 1);
                ClassInfo existing = classes.get(imp.alias);
                if (existing != null) {
                    ClassInfo info = classes.get(real);
                    if (existing != info) {
                        throw new RuntimeException("Import alias '" + imp.alias + "' in 'import "
                            + imp.path + " as " + imp.alias + "' conflicts with class '"
                            + existing.fullName + "' (at line " + imp.line + "); choose another alias");
                    }
                    continue; // alias names its own class — no-op
                }
                ClassInfo info = classes.get(real);
                if (info != null) classes.put(imp.alias, info);
                // else: generic template or class-less file — aliases only resolve through
                // type-string normalization / monomorphized names for those.
            }
        }

        // Assign type IDs for runtime type checking (like operator)
        validateAbstractMethods(classDecls);
        assignTypeIds();

        // Pass 2: generate code
        emitHeader();

        // Generate all classes
        for (ClassDecl cd : classDecls) {
            generateClass(cd);
        }

        // Generate standalone functions
        for (AST func : topLevelFuncs) {
            generateFunction((FuncDecl) func, null);
        }

        // Generate main: entry class body or auto-wrapped statements
        if (entryClass != null) {
            generateEntryPointMain(entryClass);
        } else if (!mainStatements.isEmpty()) {
            topLevelStmts.addAll(mainStatements);
            generateAutoMain();
        }

        return header.toString() + "\n" + body.toString() + extraDefs.toString();
    }

    // Function objects: { code, receiver } pairs; thunks/lambdas/globals go here after main body.
    private final StringBuilder extraDefs = new StringBuilder();
    private int lambdaCount = 0;
    private int thunkCount = 0;
    private int fnrefCount = 0;
    private int threadCount = 0;
    private int runtimeErrorCount = 0;
    private int threadBlockCount = 0;
    // Globals holding spawned `thread { }` handles; joined just before program exit.
    private final List<String> threadBlockHandles = new ArrayList<>();
    // Expected Function<...> signature while generating a value used as a Function argument.
    private String expectedFunctionType = null;

    private final List<AST> topLevelStmts = new ArrayList<>();

    private void saveNodeForMain(AST node) {
        topLevelStmts.add(node);
    }

    // ==================== Pass 1: Collection ====================

    private void prepareGenericMonomorphization(List<AST> members) {
        // Import aliases must be known before type strings are inspected: Map<String,int>
        // has to be seen as Dict<String,int> for use-collection to hit the template.
        collectImportAliases(members);
        for (AST root : members) normalizeTypeAliasWalk(root);

        // Multi-specialization (T71): record every concrete argument per generic owner, then
        // materialize one ClassDecl per (generic, arg) pair — first arg reuses the original
        // decl, further args get a fresh copy re-parsed from the same source file.
        Map<String, Set<String>> allArgs = new LinkedHashMap<>();
        for (AST root : members) collectGenericUses(root, allArgs);

        List<ClassDecl> materialized = new ArrayList<>();
        for (AST root : new ArrayList<>(members)) {
            if (!(root instanceof ClassDecl)) continue;
            ClassDecl cd = (ClassDecl) root;
            if (cd.genericParams.isEmpty() || cd.name.equals("Thread")) continue;
            String baseName = cd.name;
            // Multi-parameter generics: params = [K, V]; each recorded use is a canonical
            // combo string like "int, String" (", " joined by parseType / collect).
            String[] params = cd.genericParams.toArray(new String[0]);
            Set<String> args = allArgs.get(baseName);
            if (args == null || args.isEmpty()) {
                // Generic template that is never used (e.g. auto-loaded stdlib): keep it as an
                // un-specialized template; the member loop skips templates entirely.
                continue;
            }
            boolean first = true;
            for (String argCombo : args) {
                if (argCombo.contains("<") || argCombo.contains(">")) {
                    throw new RuntimeException("Nested generic types are not supported");
                }
                String[] concrete = argCombo.split(", ");
                if (concrete.length != params.length) {
                    throw new RuntimeException("Generic class '" + baseName + "' expects " + params.length
                        + " type argument(s) but got " + concrete.length + " (" + argCombo + ")");
                }
                ClassDecl spec = cd;
                if (!first) {
                    spec = reparseClassDecl(cd, baseName);
                    if (spec == null) {
                        throw new RuntimeException("Cannot create specialization '" + baseName + "_" + argCombo + "' (source file unavailable: '" + cd.sourceFile + "')");
                    }
                    materialized.add(spec);
                }
                first = false;
                for (int i = 0; i < params.length; i++) {
                    rewriteAstTypes(spec, params[i], concrete[i]);
                }
                String specName = baseName + "_" + String.join("_", concrete);
                genericTypeOwners.put(baseName + "<" + argCombo + ">", specName);
                spec.name = specName;
                spec.genericParams.clear();
            }
        }
        members.addAll(materialized);
        for (AST root : members) rewriteGenericNames(root);
    }

    /** Record alias -> real simple name from every import declaration (incl. nested files). */
    private void collectImportAliases(List<AST> members) {
        for (AST root : members) {
            if (root instanceof ImportDecl) {
                ImportDecl imp = (ImportDecl) root;
                if (imp.alias != null && !imp.alias.isEmpty()) {
                    String real = imp.path.substring(imp.path.lastIndexOf('/') + 1);
                    importAliases.put(imp.alias, real);
                }
            }
        }
    }

    /** Rewrite alias owners inside type-carrying String fields (Map<..> -> Dict<..>) before
     *  monomorphization. Expression positions are intentionally NOT touched — they resolve
     *  via the classes-table alias key, which preserves full-name disambiguation. */
    private void normalizeTypeAliasWalk(AST node) {
        if (node == null || importAliases.isEmpty()) return;
        for (java.lang.reflect.Field f : node.getClass().getFields()) {
            try {
                Object v = f.get(node);
                String fn = f.getName();
                if (v instanceof String && (fn.equals("type") || fn.equals("returnType")
                        || fn.equals("varType"))) {
                    f.set(node, normalizeTypeAliases((String) v));
                } else if (v instanceof AST) {
                    normalizeTypeAliasWalk((AST) v);
                } else if (v instanceof List<?>) {
                    for (Object x : (List<?>) v) if (x instanceof AST) normalizeTypeAliasWalk((AST) x);
                }
            } catch (IllegalAccessException ignored) { }
        }
    }

    private String normalizeTypeAliases(String t) {
        if (t == null || t.isEmpty()) return t;
        for (Map.Entry<String, String> e : importAliases.entrySet()) {
            String a = e.getKey();
            if (t.equals(a)) return e.getValue();
            if (t.startsWith(a + "<") || t.startsWith(a + "[")) {
                t = e.getValue() + t.substring(a.length());
            }
        }
        return t;
    }

    /** Deep-copy a generic class by re-parsing its source file (multi-specialization, T71). */
    private ClassDecl reparseClassDecl(ClassDecl cd, String baseName) {
        String file = cd.sourceFile;
        if (file == null || file.isEmpty()) return null;
        try {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(file));
            Lexer lexer = new Lexer(text);
            List<Token> tokens = lexer.tokenize();
            Parser parser = new Parser(tokens);
            AST.Program prog = parser.parse();
            prog.setSourceFile(file);
            for (AST root : prog.members) {
                if (root instanceof ClassDecl) {
                    ClassDecl c = (ClassDecl) root;
                    if (c.name.equals(baseName) && !c.genericParams.isEmpty()) return c;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void collectGenericUses(AST node, Map<String, Set<String>> allArgs) {
        if (node == null) return;
        if (node instanceof VarDecl) {
            String t = ((VarDecl) node).type;
            int lt = t.indexOf('<'), gt = t.lastIndexOf('>');
            if (lt > 0 && gt > lt) {
                String owner = t.substring(0, lt);
                // Array<T> and Function<...> are built-in internal types, not generic classes.
                if (!owner.equals("Array") && !owner.equals("Function") && !owner.equals("Thread")) {
                    String arg = t.substring(lt + 1, gt);
                    allArgs.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(arg);
                }
            }
        }
        if (node instanceof NewExpr) {
            NewExpr n = (NewExpr) node;
            if (!n.typeArgs.isEmpty()) {
                // Canonical combo "int, String" — must match parseType's ", " join.
                // Owner may be an import alias (new Map<String,int> -> Dict uses).
                String combo = String.join(", ", n.typeArgs);
                String owner = importAliases.getOrDefault(n.className, n.className);
                allArgs.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(combo);
            }
        }
        for (java.lang.reflect.Field f : node.getClass().getFields()) {
            try {
                Object v = f.get(node);
                if (v instanceof AST) collectGenericUses((AST) v, allArgs);
                else if (v instanceof List<?>) for (Object x : (List<?>) v) if (x instanceof AST) collectGenericUses((AST) x, allArgs);
            } catch (IllegalAccessException ignored) { }
        }
    }

    private void rewriteAstTypes(AST node, String parameter, String concrete) {
        // debug.md #40: `T v = null` specialized to a value type must get a zero literal —
        // a NullLit default would emit `store i8* null, i32* ...` and fail at clang.
        // Checked BEFORE the type string rewrite below (p.type is still the parameter here).
        if (node instanceof Parameter) {
            Parameter p = (Parameter) node;
            if (p.defaultValue instanceof NullLit && parameter.equals(p.type)) {
                if (concrete.equals("bool")) {
                    p.defaultValue = new BoolLit(false, p.line);
                } else if (concrete.equals("int") || concrete.equals("byte") || concrete.equals("long")
                        || concrete.equals("float") || concrete.equals("double")) {
                    // castValue widens/narrows i32 -> any numeric LLVM type at the call site.
                    p.defaultValue = new IntLit("0", p.line);
                }
                // Reference concretes (String / classes / arrays / Function) keep null.
            }
        }
        for (java.lang.reflect.Field f : node.getClass().getFields()) {
            try {
                Object v = f.get(node);
                if (v instanceof String && (f.getName().equals("type") || f.getName().equals("returnType"))) {
                    String s = (String) v;
                    String r = replaceTypeParam(s, parameter, concrete);
                    if (!r.equals(s)) f.set(node, r);
                } else if (v instanceof AST) rewriteAstTypes((AST) v, parameter, concrete);
                else if (v instanceof List<?>) for (Object x : (List<?>) v) if (x instanceof AST) rewriteAstTypes((AST) x, parameter, concrete);
            } catch (IllegalAccessException ignored) { }
        }
    }

    /** Substitute one type parameter inside a type string, incl. multi-parameter composites
     *  (Pair<K, V> with param K) and nested Array<...>. No match returns s unchanged. */
    private String replaceTypeParam(String s, String param, String concrete) {
        if (s == null || s.isEmpty()) return s;
        if (s.equals(param)) return concrete;
        int lt = s.indexOf('<');
        if (lt < 0 || !s.endsWith(">")) return s;
        String head = s.substring(0, lt);
        String body = s.substring(lt + 1, s.length() - 1);
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        boolean matched = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                String seg = body.substring(start, i);
                String r = replaceTypeParam(seg.trim(), param, concrete);
                if (!r.equals(seg.trim())) matched = true;
                parts.add(r);
                start = i + 1;
            }
        }
        String last = body.substring(start);
        String lastR = replaceTypeParam(last.trim(), param, concrete);
        if (!lastR.equals(last.trim())) matched = true;
        parts.add(lastR);
        if (!matched) return s;
        return head + "<" + String.join(", ", parts) + ">";
    }

    private void rewriteGenericNames(AST node) {
        if (node instanceof VarDecl) {
            VarDecl v = (VarDecl) node;
            String replaced = replaceGenericKeys(v.type);
            if (!replaced.equals(v.type)) {
                String owner = v.type.substring(0, v.type.indexOf('<'));
                v.type = replaced;
                // Diamond `Box<int> b = new Box<>()`: infer the specialization from the decl.
                if (v.init instanceof NewExpr) {
                    NewExpr n = (NewExpr) v.init;
                    String nOwner = importAliases.getOrDefault(n.className, n.className);
                    if (n.typeArgs.isEmpty() && nOwner.equals(owner)) n.className = replaced;
                }
            }
        } else if (node instanceof NewExpr) {
            NewExpr n = (NewExpr) node;
            if (!n.typeArgs.isEmpty()) {
                String owner = importAliases.getOrDefault(n.className, n.className);
                String key = owner + "<" + String.join(", ", n.typeArgs) + ">";
                String spec = genericTypeOwners.get(key);
                if (spec != null) n.className = spec;
            }
        } else if (node instanceof FuncDecl) {
            FuncDecl f = (FuncDecl) node;
            f.returnType = replaceGenericKeys(f.returnType);
        } else if (node instanceof Parameter) {
            Parameter p = (Parameter) node;
            p.type = replaceGenericKeys(p.type);
        } else if (node instanceof NewArrayExpr) {
            NewArrayExpr na = (NewArrayExpr) node;
            na.type = replaceGenericKeys(na.type);
        }
        for (java.lang.reflect.Field f : node.getClass().getFields()) {
            try {
                Object v = f.get(node);
                if (v instanceof AST) rewriteGenericNames((AST) v);
                else if (v instanceof List<?>) for (Object x : (List<?>) v) if (x instanceof AST) rewriteGenericNames((AST) x);
            } catch (IllegalAccessException ignored) { }
        }
    }

    /** Rewrite every recorded "Owner<arg>" key inside a type string to its specialization. */
    private String replaceGenericKeys(String t) {
        if (t == null) return null;
        for (Map.Entry<String, String> e : genericTypeOwners.entrySet()) {
            if (t.contains(e.getKey())) t = t.replace(e.getKey(), e.getValue());
        }
        return t;
    }

    /** Abstract-method contract: a concrete (non-abstract) class must provide a concrete
     *  implementation for every abstract method inherited from its ancestors, and may not
     *  declare abstract methods itself. */
    private void validateAbstractMethods(List<ClassDecl> classDecls) {
        for (ClassDecl cd : classDecls) {
            if (cd.isAbstract) continue;
            String fullName = declFullNames.get(cd);
            if (fullName == null) fullName = cd.name;
            ClassInfo ci = classes.get(fullName);
            if (ci == null) continue;

            // 1) A concrete class cannot declare abstract methods itself.
            for (FuncInfo f : functions.values()) {
                if (f.isAbstract && f.className != null && f.className.equals(fullName)) {
                    throw new RuntimeException("Abstract method '" + f.name
                        + "' declared in non-abstract class '" + cd.name + "' (at line " + cd.line
                        + "); declare the class abstract");
                }
            }

            // 2) Every inherited abstract method needs a concrete implementation somewhere
            //    on this class's ancestry (nearest definition wins via hasConcreteImpl walk).
            ClassInfo cur = ci;
            int guard = 0;
            java.util.Set<String> checked = new java.util.HashSet<>();
            while (cur != null && guard++ < 64) {
                for (FuncInfo f : functions.values()) {
                    if (!f.isAbstract || f.isStatic) continue;
                    if (f.className == null || !f.className.equals(cur.fullName)) continue;
                    // FuncInfo.name is the fully-qualified key (Shape.area); groups use the short name.
                    String shortName = f.name.startsWith(cur.fullName + ".")
                        ? f.name.substring(cur.fullName.length() + 1) : f.name;
                    String sig = shortName + "(" + String.join(",", f.paramTypes) + ")";
                    if (!checked.add(sig)) continue;
                    if (!hasConcreteImpl(f, shortName, ci)) {
                        throw new RuntimeException("Class '" + cd.name + "' must implement abstract method '"
                            + sig + "' from '" + cur.simpleName + "' (at line " + cd.line
                            + "); implement it or declare the class abstract");
                    }
                }
                cur = cur.parentName != null ? classes.get(cur.parentName) : null;
            }
        }
    }

    /** True when fromClass or any ancestor defines a concrete (non-abstract) same-signature method. */
    private boolean hasConcreteImpl(FuncInfo required, String shortName, ClassInfo fromClass) {
        ClassInfo c = fromClass;
        int guard = 0;
        while (c != null && guard++ < 64) {
            List<FuncInfo> grp = overloadGroups.get(c.fullName + "." + shortName);
            if (grp != null) {
                for (FuncInfo g : grp) {
                    if (!g.isAbstract && g.paramTypes.equals(required.paramTypes)) return true;
                }
            }
            c = c.parentName != null ? classes.get(c.parentName) : null;
        }
        return false;
    }

    /** Unknown-class error with an ambiguity hint when the simple name is contested. */
    private RuntimeException unknownClassError(String name, int line) {
        java.util.Set<String> alts = ambiguousShortNames.get(name);
        if (alts != null) {
            return new RuntimeException("Class '" + name + "' is ambiguous across namespaces: "
                + alts + "; use the full name (at line " + line + ")");
        }
        return new RuntimeException("Unknown class: " + name + " (at line " + line + ")");
    }

    private void collectClass(ClassDecl decl) {
        ClassInfo info = new ClassInfo();
        String ns = (decl.namespace != null && !decl.namespace.isEmpty()) ? decl.namespace : currentNamespace;String fullName = ns.isEmpty() ? decl.name : ns.replace("/", "_") + "_" + decl.name;
        // Duplicate full name = the second definition silently overwrote the first before
        // (same file or across imports → misleading downstream errors, debug.md #11).
        if (classes.containsKey(fullName)) {
            String file = !decl.sourceFile.isEmpty() ? decl.sourceFile : sourceFile;
            String srcLine = readSourceLine(file, decl.line);
            int col = srcLine.indexOf(decl.name) >= 0 ? srcLine.indexOf(decl.name) + 1 : 1;
            throw new util.CompileError("Duplicate class: '" + fullName + "'",
                file, decl.line, col, srcLine, decl.name.length());
        }
        info.llvmName = "%" + fullName;
        info.fullName = fullName;
        info.simpleName = decl.name;
        info.ctorParams = decl.ctorParams;
        info.parentName = decl.superClass;
        info.superArgs = decl.superArgs;
        info.isAbstract = decl.isAbstract;
        info.isPrivateCtor = decl.isPrivateCtor;
        int idx = 1; // Index 0 is type ID, start fields at 1

        // Add parent fields first (inheritance layout)
        if (info.parentName != null) {
            ClassInfo parentInfo = classes.get(info.parentName);
            if (parentInfo != null) {
                for (int i = 0; i < parentInfo.fieldNames.size(); i++) {
                    info.fieldNames.add(parentInfo.fieldNames.get(i));
                    info.fieldTypes.add(parentInfo.fieldTypes.get(i));
                    info.fieldIndices.put(parentInfo.fieldNames.get(i), idx++);
                }
            }
        }

        // Add fields from constructor params (implicit fields)
        for (Parameter p : decl.ctorParams) {
            info.fieldNames.add(p.name);
            info.fieldTypes.add(p.type);
            info.fieldIndices.put(p.name, idx++);
        }

        // Add explicit fields from class body (only instance fields go in struct)
        for (AST member : decl.members) {
            if (member instanceof FieldDecl) {
                FieldDecl f = (FieldDecl) member;
                if (f.isStatic) {
                    // Static field: store separately
                    info.staticFields.add(f);
                } else {
                    info.fieldNames.add(f.name);
                    info.fieldTypes.add(f.type);
                    info.fieldIndices.put(f.name, idx++);
                }
            }
        }
        // Simple-name alias: usable only while unique. A second class with the same simple
        // name (different namespace) makes it ambiguous — drop the alias and record both
        // full names so use sites can suggest full-name qualification; fullName always works.
        ClassInfo shortExisting = classes.get(decl.name);
        if (shortExisting == null) {
            classes.put(decl.name, info);
        } else if (!shortExisting.fullName.equals(fullName)) {
            classes.remove(decl.name);
            ambiguousShortNames.computeIfAbsent(decl.name, k -> new java.util.LinkedHashSet<>())
                .add(shortExisting.fullName);
            ambiguousShortNames.get(decl.name).add(fullName);
        }
        classes.put(fullName, info);
        declFullNames.put(decl, fullName);

        // Register implicit constructor if there are ctor params OR has parent
        if (!decl.ctorParams.isEmpty() || decl.superClass != null) {
            FuncInfo ctorInfo = new FuncInfo();
            ctorInfo.name = fullName + ".constructor";
            ctorInfo.returnType = "void";
            ctorInfo.className = fullName;
            ctorInfo.isConstructor = true;
            for (Parameter p : decl.ctorParams) {
                ctorInfo.paramTypes.add(p.type);
                ctorInfo.paramNames.add(p.name);
                ctorInfo.paramDefaults.add(p.defaultValue);
            }
            functions.put(ctorInfo.name, ctorInfo);
        }

        // Register methods and static fields
        for (AST member : decl.members) {
            if (member instanceof FuncDecl) {
                collectFunction((FuncDecl) member, fullName);
            } else if (member instanceof ConstructorDecl) {
                collectConstructor((ConstructorDecl) member, fullName);
            } else if (member instanceof FieldDecl && ((FieldDecl) member).isStatic) {
                // Register static field as global
                FieldDecl f = (FieldDecl) member;
                staticFields.put(fullName + "." + f.name, f);
            }
        }

        // Check final method override: child cannot override parent's final method
        if (decl.superClass != null) {
            for (AST member : decl.members) {
                if (member instanceof FuncDecl) {
                    FuncDecl fd = (FuncDecl) member;
                    // Walk up parent chain
                    String parentName = decl.superClass;
                    while (parentName != null) {
                        String parentFuncName = classes.containsKey(parentName) ?
                            classes.get(parentName).fullName + "." + fd.name :
                            parentName + "." + fd.name;
                        FuncInfo parentFi = functions.get(parentFuncName);
                        if (parentFi != null && parentFi.isFinal) {
                            throw new RuntimeException(
                                "Cannot override final method '" + fd.name + "' from '" +
                                decl.superClass + "' (at line " + fd.line + ")");
                        }
                        ClassInfo parentCi = classes.get(parentName);
                        String nextParent = parentCi != null ? parentCi.parentName : null;
                        // Prevent infinite loop if class is its own parent (e.g. Object)
                        if (nextParent != null && nextParent.equals(parentName)) {
                            nextParent = null;
                        }
                        parentName = nextParent;
                    }
                }
            }
        }
    }

    /**
     * Assign unique type IDs to all classes for runtime type checking (like operator).
     * Object gets ID 0, other classes get 1, 2, 3, ...
     */
    private void assignTypeIds() {
        int nextId = 1; // Object = 0
        java.util.Set<String> assigned = new java.util.HashSet<>();
        // Object gets 0
        ClassInfo obj = classes.get("Object");
        if (obj != null) {
            obj.typeId = 0;
            assigned.add(obj.fullName);
        }
        // Assign IDs to other classes
        for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
            ClassInfo ci = entry.getValue();
            if (assigned.contains(ci.fullName)) continue;
            ci.typeId = nextId++;
            assigned.add(ci.fullName);
        }
    }

    /**
     * Check if child class is a subclass of parent class (including equality).
     */
    private boolean isSubclass(String childName, String parentName) {
        if (childName.equals(parentName)) return true;
        ClassInfo child = classes.get(childName);
        while (child != null && child.parentName != null) {
            if (child.parentName.equals(parentName)) return true;
            child = classes.get(child.parentName);
        }
        return false;
    }

    /**
     * Check if currentClass is same as targetClass OR a parent of targetClass.
     * Used for private access: _ fields accessible only within declaring class.
     */
    private boolean isSameOrParentClass(String currentClass, String targetClass) {
        if (currentClass == null) return false;
        // Normalize the current class to its simple name: callers pass either the full name
        // (currentClassName from generateFunction, e.g. "cang_lang_ArrayList_int") or a simple
        // name, while targetClass is always simple — string equality needs like-for-like.
        ClassInfo cur = classes.get(currentClass);
        String curName = cur != null ? cur.simpleName : currentClass;
        if (curName.equals(targetClass)) return true;
        // currentClass is a parent of targetClass (target extends current)
        return isSubclass(targetClass, curName);
    }

    /**
     * Check if a method is overridden in any subclass of the given class.
     * Returns true if polymorphic dispatch is needed.
     */
    private boolean isMethodOverridden(ClassInfo ci, String methodName) {
        // Check if any subclass of ci defines the same method
        for (ClassInfo other : classes.values()) {
            if (other == ci) continue;
            // other must be a subclass of ci
            if (!isSubclass(other.simpleName, ci.simpleName)) continue;
            // other must define the method
            String funcName = other.fullName + "." + methodName;
            if (functions.containsKey(funcName)) return true;
        }
        return false;
    }

    /**
     * Generate dynamic dispatch based on object's runtime typeId.
     * Loads typeId, then for each possible subclass, checks and calls the correct method.
     */
    private LLVMValue generateDynamicDispatch(LLVMValue objVal, MethodCallExpr node,
                                               FuncInfo staticFi, String staticClassName) {
        // Load typeId from object (first field)
        String typePtr = "%disp.tid.ptr." + tmpCount++;
        body.append("  ").append(typePtr).append(" = bitcast ").append(objVal.type)
             .append(" ").append(objVal.value).append(" to i32*\n");
        String typeId = "%disp.tid." + tmpCount++;
        body.append("  ").append(typeId).append(" = load i32, i32* ").append(typePtr).append("\n");

        // Determine return type
        boolean isVoid = staticFi.returnType.equals("void");
        String retType = toLLVMType(staticFi.returnType);
        String phiName = "%disp.ret." + tmpCount++;

        if (isVoid) {
            // For void, generate if-else chain
            generateVoidDispatch(typeId, objVal, node, staticFi, staticClassName);
            return new LLVMValue("void", "void");
        }

        // Allocate slot for return value
        String phiSlot = phiName + ".slot";
        body.append("  ").append(phiSlot).append(" = alloca ").append(retType).append("\n");

        // For non-void, use phi node
        int id = labelCount++;
        String endLabel = "disp.end." + id;

        // Collect all classes that could be the runtime type
        java.util.List<ClassInfo> possibleTypes = new ArrayList<>();
        ClassInfo staticClass = classes.get(staticClassName);
        possibleTypes.add(staticClass);
        String staticSimpleName = staticClass != null ? staticClass.simpleName : staticClassName;
        for (ClassInfo ci : classes.values()) {
            if (ci == staticClass) continue;
            if (isSubclass(ci.simpleName, staticSimpleName)) {
                possibleTypes.add(ci);
            }
        }
        // Abstract classes can never be a runtime instance — drop their dispatch arms.
        possibleTypes.removeIf(c -> c != null && c.isAbstract);
        if (possibleTypes.isEmpty()) {
            throw new RuntimeException("Cannot dispatch '" + node.method + "': '"
                + staticClassName + "' is abstract and has no concrete subclass (at line " + node.line + ")");
        }

        // Generate branch for each possible type
        for (int i = 0; i < possibleTypes.size(); i++) {
            ClassInfo ci = possibleTypes.get(i);
            String funcName = ci.fullName + "." + node.method;
            // Same-signature overload for this dynamic class (subclass without a matching
            // overload falls back to the resolved base implementation).
            FuncInfo target = dispatchTarget(funcName, staticFi);

            String callLabel = "disp.call." + id + "." + i;
            String checkLabel = "disp.check." + id + "." + i;
            String nextLabel = (i + 1 < possibleTypes.size()) ? "disp.check." + id + "." + (i + 1) : endLabel;

            // Emit check label (except first which is inline)
            if (i > 0) {
                body.append(checkLabel).append(":\n");
            }

            // Check typeId matches this class
            String cmp = "%disp.cmp." + tmpCount++;
            body.append("  ").append(cmp).append(" = icmp eq i32 ").append(typeId)
                 .append(", ").append(ci.typeId).append("\n");
            body.append("  br i1 ").append(cmp).append(", label %").append(callLabel)
                 .append(", label %").append(nextLabel).append("\n\n");

            // Call label
            body.append(callLabel).append(":\n");
            StringBuilder args = new StringBuilder();
            args.append(objVal.type).append(" ").append(objVal.value);
            for (AST arg : node.args) {
                LLVMValue argVal = generateExpr(arg);
                String paramType = staticFi.paramTypes.get(node.args.indexOf(arg));
                args.append(", ").append(toLLVMType(paramType)).append(" ")
                    .append(castValue(argVal, toLLVMType(paramType)));
            }
            String result = "%disp.val." + tmpCount++;
            body.append("  ").append(result).append(" = call ").append(retType)
                 .append(" @").append(symOf(target)).append("(").append(args).append(")\n");
            body.append("  store ").append(retType).append(" ").append(result)
                 .append(", ").append(retType).append("* ").append(phiSlot).append("\n");
            body.append("  br label %").append(endLabel).append("\n\n");
        }

        body.append(endLabel).append(":\n");
        String loaded = "%disp.loaded." + tmpCount++;
        body.append("  ").append(loaded).append(" = load ").append(retType)
             .append(", ").append(retType).append("* ").append(phiName).append(".slot\n");
        return new LLVMValue(loaded, retType);
    }

    private void generateVoidDispatch(String typeId, LLVMValue objVal, MethodCallExpr node,
                                       FuncInfo staticFi, String staticClassName) {
        // For void return, simpler if-else chain
        int id = labelCount++;
        String endLabel = "disp.end." + id;

        java.util.List<ClassInfo> possibleTypes = new ArrayList<>();
        possibleTypes.add(classes.get(staticClassName));
        for (ClassInfo ci : classes.values()) {
            if (ci != classes.get(staticClassName) && isSubclass(ci.simpleName, staticClassName)) {
                possibleTypes.add(ci);
            }
        }
        // Abstract classes can never be a runtime instance — drop their dispatch arms.
        possibleTypes.removeIf(c -> c != null && c.isAbstract);
        if (possibleTypes.isEmpty()) {
            throw new RuntimeException("Cannot dispatch void method: '"
                + staticClassName + "' is abstract and has no concrete subclass (at line " + node.line + ")");
        }

        // Allocate slot for return (not needed for void, but for consistency)
        // Actually for void we don't need it

        String currentCheck = null;
        for (int i = 0; i < possibleTypes.size(); i++) {
            ClassInfo ci = possibleTypes.get(i);
            String funcName = ci.fullName + "." + node.method;
            // Same-signature overload (base fallback), mirroring the non-void dispatcher.
            FuncInfo target = dispatchTarget(funcName, staticFi);

            String callLabel = "disp.call." + id + "." + i;

            // Check typeId
            String cmp = "%disp.vcmp." + tmpCount++;
            body.append("  ").append(cmp).append(" = icmp eq i32 ").append(typeId)
                 .append(", ").append(ci.typeId).append("\n");

            String nextLabel = (i + 1 < possibleTypes.size()) ? "disp.next." + id + "." + (i + 1) : endLabel;
            body.append("  br i1 ").append(cmp).append(", label %").append(callLabel)
                 .append(", label %").append(nextLabel).append("\n\n");

            // Call
            body.append(callLabel).append(":\n");
            StringBuilder args = new StringBuilder();
            args.append(objVal.type).append(" ").append(objVal.value);
            for (AST arg : node.args) {
                LLVMValue argVal = generateExpr(arg);
                String paramType = staticFi.paramTypes.get(node.args.indexOf(arg));
                args.append(", ").append(toLLVMType(paramType)).append(" ")
                    .append(castValue(argVal, toLLVMType(paramType)));
            }
            body.append("  call void @").append(symOf(target)).append("(").append(args).append(")\n");
            body.append("  br label %").append(endLabel).append("\n\n");

            // Next label
            if (i + 1 < possibleTypes.size()) {
                body.append("disp.next.").append(id).append(".").append(i + 1).append(":\n");
            }
        }

        body.append(endLabel).append(":\n");
    }

    private void collectFunction(FuncDecl decl, String className) {
        String funcName = (className != null ? className + "." : "") + decl.name;
        List<String> newTypes = new ArrayList<>();
        for (Parameter p : decl.params) newTypes.add(p.type);

        if (functions.containsKey(funcName)) {
            FuncInfo existing = functions.get(funcName);
            // Native overload surface (Stdout.print/println, Stderr...): declaration-only docs
            // for name-based builtin dispatch — first declaration wins instead of erroring,
            // so `import cang/lang/Stdout` no longer crashes (was: Duplicate function).
            if (existing.isNative && decl.isNative && existing.paramTypes.equals(newTypes)) {
                // Same native signature re-registered (e.g. duplicated imports) — first wins.
                // DIFFERENT signatures are genuine native overloads (readKey(), readKey(int),
                // the Stdout.print family) and must fall through to overload registration.
                return;
            }
            String file = !decl.sourceFile.isEmpty() ? decl.sourceFile : sourceFile;
            String srcLine = readSourceLine(file, decl.line);
            // Mixed native/non-native overloads can never link (native has no body).
            if (existing.isNative != decl.isNative) {
                throw new util.CompileError(
                    "Method '" + decl.name + "' mixes native and non-native declarations; overloads must be uniform",
                    file, decl.line, srcLine.indexOf(decl.name) >= 0 ? srcLine.indexOf(decl.name) + 1 : 1,
                    srcLine, decl.name.length());
            }
            boolean existingDefaults = false;
            for (AST d : existing.paramDefaults) if (d != null) { existingDefaults = true; break; }
            boolean newDefaults = false;
            for (Parameter p : decl.params) if (p.defaultValue != null) { newDefaults = true; break; }
            // User rule: a method with default parameter values may not be overloaded
            // (this also removes every defaults/overload resolution ambiguity).
            if (existingDefaults || newDefaults) {
                throw new util.CompileError(
                    "Method '" + decl.name + "' has default parameter values and cannot be overloaded",
                    file, decl.line, srcLine.indexOf(decl.name) >= 0 ? srcLine.indexOf(decl.name) + 1 : 1,
                    srcLine, decl.name.length());
            }
            if (existing.isStatic != decl.isStatic) {
                throw new util.CompileError(
                    "Method '" + decl.name + "' mixes static and instance declarations; overloads must be uniform",
                    file, decl.line, srcLine.indexOf(decl.name) >= 0 ? srcLine.indexOf(decl.name) + 1 : 1,
                    srcLine, decl.name.length());
            }
            if (existing.paramTypes.equals(newTypes)) {
                throw new util.CompileError(
                    "Duplicate function: '" + decl.name + "' is already defined with the same signature",
                    file, decl.line, srcLine.indexOf(decl.name) >= 0 ? srcLine.indexOf(decl.name) + 1 : 1,
                    srcLine, decl.name.length());
            }
            // Genuine overload: register under a unique LLVM symbol, keep `functions` on the first.
            FuncInfo info = buildFuncInfo(decl, className, funcName);
            List<FuncInfo> group = overloadGroups.get(funcName);
            info.llvmName = funcName + "." + group.size();
            group.add(info);
            return;
        }
        FuncInfo info = buildFuncInfo(decl, className, funcName);
        info.llvmName = funcName;
        functions.put(info.name, info);
        overloadGroups.computeIfAbsent(funcName, k -> new ArrayList<>()).add(info);
    }

    private FuncInfo buildFuncInfo(FuncDecl decl, String className, String funcName) {
        FuncInfo info = new FuncInfo();
        info.name = funcName;
        info.returnType = decl.returnType;
        info.className = className;
        info.isStatic = decl.isStatic;
        info.isFinal = decl.isFinal;
        info.isNative = decl.isNative;
        info.isAbstract = decl.isAbstract;
        for (Parameter p : decl.params) {
            info.paramTypes.add(p.type);
            info.paramNames.add(p.name);
            info.paramDefaults.add(p.defaultValue);
        }
        return info;
    }

    /** Read a source line from file for error reporting; returns "" if unavailable. */
    private String readSourceLine(String file, int line) {
        try {
            String[] lines = java.nio.file.Files.readString(java.nio.file.Path.of(file)).split("\n", -1);
            return line >= 1 && line <= lines.length ? lines[line - 1].replace("\r", "") : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void collectConstructor(ConstructorDecl decl, String fullName) {
        FuncInfo info = new FuncInfo();
        info.name = fullName + ".constructor";
        info.returnType = "void";
        info.className = fullName;
        info.isConstructor = true;
        for (Parameter p : decl.params) {
            info.paramTypes.add(p.type);
            info.paramNames.add(p.name);
            info.paramDefaults.add(p.defaultValue);
        }
        functions.put(info.name, info);
    }

    // ==================== Header ====================

    private void emitHeader() {
        header.append("; Generated by Cang compiler\n\n");

        // External declarations
        header.append("declare i8* @malloc(i64)\n");
        header.append("declare i8* @realloc(i8*, i64)\n");
        header.append("declare void @free(i8*)\n");
        header.append("declare i32 @printf(i8*, ...)\n");
        header.append("declare i32 @fprintf(i8*, i8*, ...)\n");
        if (targetPlatform.equals("windows")) {
            // MinGW/UCRT stderr = __acrt_iob_func(2) (FILE* slot for STDERR; linkprobe-verified)
            header.append("declare i8* @__acrt_iob_func(i32)\n");
        } else {
            // glibc: stderr is a global FILE* (darwin may need ___stderrp when compileMacOS lands)
            header.append("@stderr = external global i8*\n");
        }
        header.append("declare i32 @sprintf(i8*, i8*, ...)\n");
        header.append("declare i64 @strlen(i8*)\n");
        header.append("declare i32 @strcmp(i8*, i8*)\n");
        header.append("declare i32 @atoi(i8*)\n");
        header.append("declare i32 @sscanf(i8*, i8*, ...)\n");
        header.append("declare double @atof(i8*)\n");
        if (gcEnabled) {
            header.append("declare void @GC_gcollect()\n");
            header.append("declare void @GC_init()\n");
        header.append("declare i1 @GC_is_heap_ptr(i8*)\n");
            header.append("declare i8* @GC_malloc(i64)\n");
            header.append("declare i8* @GC_realloc(i8*, i64)\n");
            header.append("declare void @GC_free(i8*)\n");
            header.append("declare i32 @GC_get_stack_base(i8*)\n");
            header.append("declare i32 @GC_register_my_thread(i8*)\n");
            header.append("declare i32 @GC_unregister_my_thread(i8*)\n");
            header.append("declare i8* @GC_CreateThread(i8*, i64, i32 (i8*)*, i8*, i32, i32*)\n");
        }
        if (!targetPlatform.equals("windows")) {
            header.append("declare i32 @pthread_create(i8**, i8*, i8* (i8*)*, i8*)\n");
            header.append("declare i32 @pthread_join(i64, i8**)\n");
        }
        header.append("@math.seed = global i64 1\n");
        header.append("declare double @llvm.fabs.f64(double)\n");
        header.append("declare float @llvm.fabs.f32(float)\n");
        header.append("declare double @llvm.sqrt.f64(double)\n");
        header.append("declare double @llvm.pow.f64(double, double)\n");
        header.append("declare double @llvm.floor.f64(double)\n");
        header.append("declare double @llvm.ceil.f64(double)\n");
        header.append("declare double @llvm.round.f64(double)\n");
        header.append("declare double @llvm.sin.f64(double)\n");
        header.append("declare double @llvm.cos.f64(double)\n");
        header.append("declare double @llvm.tan.f64(double)\n");
        header.append("declare double @llvm.asin.f64(double)\n");
        header.append("declare double @llvm.acos.f64(double)\n");
        header.append("declare double @llvm.atan.f64(double)\n");
        header.append("declare double @llvm.atan2.f64(double, double)\n");
        header.append("declare double @llvm.log.f64(double)\n");
        header.append("declare double @llvm.log10.f64(double)\n");
        header.append("declare double @llvm.exp.f64(double)\n");
        header.append("declare void @exit(i32)\n");
        header.append("@cang.current.error = global i8* null\n");
        header.append("declare void @cang_throw(i8*)\n");
        header.append("declare i8* @getenv(i8*)\n");
        header.append("declare i64 @time(i64*)\n");
        if (targetPlatform.equals("windows")) {
            header.append("declare i8* @CreateThread(i8*, i64, i32 (i8*)*, i8*, i32, i32*)\n");
            header.append("declare i32 @WaitForSingleObject(i8*, i32)\n");
            header.append("declare i32 @CloseHandle(i8*)\n");
        }
        header.append("declare void @llvm.memcpy.p0i8.p0i8.i64(i8*, i8*, i64, i1)\n");
        header.append("declare void @llvm.memset.p0i8.p0i8.i64(i8*, i8, i64, i1)\n");
        header.append("declare i8* @llvm.stacksave()\n");
        header.append("declare void @llvm.stackrestore(i8*)\n\n");

        // Runtime stack trace support
        header.append("@.str.errprefix = private constant [11 x i8] c\"error: %s\\0A\\00\"\n");
        header.append("@.str.stack_entry = private unnamed_addr constant [17 x i8] c\"  at %s (%s:%d)\\0A\\00\"\n");
        header.append("@.str.null = private constant [6 x i8] c\"null\\0A\\00\"\n");
        header.append("@.str.null.p = private constant [5 x i8] c\"null\\00\"\n");
        header.append("@.stack_depth = global i32 0\n");
        // Per-thread native stack base (address of a frame near thread start). Set by main
        // and every thread entry; function-entry SP probes compare against it (debug.md #4).
        header.append("@.stack.base = internal thread_local global i64 0\n");
        header.append("@system.args = global i8* null\n\n");



        // Manual memory management is used by the current runtime.
        // Heap objects and arrays are allocated directly with malloc and released by free.

        // Runtime error function (for future use)
        header.append("define void @cang_error(i8* %msg, i8* %file, i32 %line) {\n");
        header.append("entry:\n");
        header.append("  %fmt1 = getelementptr [11 x i8], [11 x i8]* @.str.errprefix, i32 0, i32 0\n");
        header.append("  call i32 (i8*, ...) @printf(i8* %fmt1, i8* %msg)\n");
        header.append("  %fmt2 = getelementptr [17 x i8], [17 x i8]* @.str.stack_entry, i32 0, i32 0\n");
        header.append("  call i32 (i8*, ...) @printf(i8* %fmt2, i8* %file, i32 %line)\n");
        header.append("  call void @exit(i32 1)\n");
        header.append("  unreachable\n");
        header.append("}\n\n");

        // Struct types (deduplicate - same ClassInfo may be registered under multiple keys)
        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
            ClassInfo ci = entry.getValue();
            if (emitted.contains(ci.llvmName)) continue;
            emitted.add(ci.llvmName);
            header.append(ci.llvmName).append(" = type { ");

            // Type ID field first (for runtime type checking / like operator)
            header.append("i32");

            // All fields (parent fields already included in ci.fieldTypes by collectClass)
            for (int i = 0; i < ci.fieldTypes.size(); i++) {
                header.append(", ");
                header.append(toLLVMType(ci.fieldTypes.get(i)));
            }
            header.append(" }\n");
        }
        header.append("\n");

        // Function object: { code pointer, receiver/environment }
        header.append("%CangFunction = type { i8*, i8* }\n");
        header.append("%CangThreadHandle = type { i64, i8*, i32 }\n");
        // Object-style Thread: new Thread().task(fn).start() — { task code, task receiver, handle }
        header.append("%CangThreadObj = type { i8*, i8*, i8*, i1 }\n");
        // Registry of non-daemon object-style threads: joined at main exit unless daemon.
        header.append("@cang.thr.regs = internal global [256 x i8*] zeroinitializer\n");
        header.append("@cang.thr.regc = internal global i64 0\n");
        header.append("%CangList = type { i8*, i64, i64 }\n\n");

        // Object constructor (no-op)
        header.append("define void @cang_lang_Object.constructor(%cang_lang_Object* %this) {\n");
        header.append("entry:\n");
        header.append("  ret void\n");
        header.append("}\n\n");

        // String constants for printf format
        emitFmtConstants();

        header.append("\n");
    }

    private void emitFmtConstants() {
        // println format: %type + newline (0A) + null (00)
        addFmtConstant("int", "%d\\0A\\00", 4);
        addFmtConstant("long", "%lld\\0A\\00", 6);
        addFmtConstant("float", "%f\\0A\\00", 4);
        addFmtConstant("double", "%f\\0A\\00", 4);
        addFmtConstant("bool", "%d\\0A\\00", 4);
        addFmtConstant("String", "%s\\0A\\00", 4);
        addFmtConstant("byte", "%d\\0A\\00", 4);

        // print format (no newline): %type + null (00)
        addFmtConstantNoNL("int", "%d\\00", 3);
        addFmtConstantNoNL("long", "%lld\\00", 5);
        addFmtConstantNoNL("float", "%f\\00", 3);
        addFmtConstantNoNL("double", "%f\\00", 3);
        addFmtConstantNoNL("bool", "%d\\00", 3);
        addFmtConstantNoNL("String", "%s\\00", 3);
        addFmtConstantNoNL("byte", "%d\\00", 3);

        // Format strings for toString (no newline)
        header.append("@.fmt.tostr.int = private unnamed_addr constant [3 x i8] c\"%d\\00\"\n");
        header.append("@.fmt.tostr.long = private unnamed_addr constant [5 x i8] c\"%lld\\00\"\n");
        header.append("@.fmt.tostr.double = private unnamed_addr constant [3 x i8] c\"%g\\00\"\n");
        header.append("@.str.empty = private unnamed_addr constant [1 x i8] c\"\\00\"\n");

        // Static fields as global variables
        for (Map.Entry<String, FieldDecl> entry : staticFields.entrySet()) {
            FieldDecl f = entry.getValue();
            String globalName = "@static." + entry.getKey().replace(".", "_");
            String llvmType = toLLVMType(f.type);

            if (f.init instanceof StringLit) {
                // String literal: emit string constant and reference it
                StringLit sl = (StringLit) f.init;
                String strKey = sl.value;
                if (!stringLiterals.containsKey(strKey)) {
                    String strName = "@.str.stat." + stringLiterals.size();
                    int byteCount = sl.value.length() + 1;
                    StringBuilder escaped = new StringBuilder();
                    for (char c : sl.value.toCharArray()) {
                        if (c == '\\') escaped.append("\\5C");
                        else if (c == '\n') escaped.append("\\0A");
                        else if (c == '\t') escaped.append("\\09");
                        else if (c == '"') escaped.append("\\22");
                        else if (c >= 32 && c < 127) escaped.append(c);
                        else escaped.append(String.format("\\%02X", (int) c));
                    }
                    header.append(strName).append(" = private unnamed_addr constant [").append(byteCount)
                          .append(" x i8] c\"").append(escaped).append("\\00\"\n");
                    stringLiterals.put(strKey, strName);
                }
                String strName = stringLiterals.get(strKey);
                header.append(globalName).append(" = global i8* getelementptr ([")
                      .append(sl.value.length() + 1).append(" x i8], [")
                      .append(sl.value.length() + 1).append(" x i8]* ")
                      .append(strName).append(", i32 0, i32 0)\n");
            } else {
                // Constant literal init (static int n = 7, slot = null, ...) — emitted directly
                // into the global initializer. Non-constant expressions fall back to default
                // (static fields are constant-initialized only, documented).
                String initVal = defaultValueForType(f.type);
                AST init = f.init;
                if (init instanceof IntLit) {
                    initVal = ((IntLit) init).text;
                } else if (init instanceof LongLit) {
                    initVal = ((LongLit) init).text;
                } else if (init instanceof FloatLit) {
                    initVal = normalizeFloatText(((FloatLit) init).text, false);
                } else if (init instanceof DoubleLit) {
                    initVal = normalizeFloatText(((DoubleLit) init).text, true);
                } else if (init instanceof BoolLit) {
                    initVal = ((BoolLit) init).value ? "1" : "0";
                } else if (init instanceof NullLit) {
                    initVal = "null";
                } else if (init instanceof UnaryExpr && ((UnaryExpr) init).op.equals("-")
                        && ((UnaryExpr) init).operand instanceof IntLit) {
                    initVal = "-" + ((IntLit) ((UnaryExpr) init).operand).text;
                }
                header.append(globalName).append(" = global ").append(llvmType).append(" ").append(initVal).append("\n");
            }
        }
        header.append("\n");
        emitFileRuntime();
        emitScannerRuntime();
    }

    private final Map<String, String> fmtConstants = new LinkedHashMap<>();
    private final Map<String, String> fmtConstantsNoNL = new LinkedHashMap<>();

    private void addFmtConstant(String cangType, String format, int byteCount) {
        String name = "@.fmt." + cangType;
        fmtConstants.put(cangType, name);
        header.append(name).append(" = private unnamed_addr constant [").append(byteCount)
              .append(" x i8] c\"").append(format).append("\"\n");
    }

    private void addFmtConstantNoNL(String cangType, String format, int byteCount) {
        String name = "@.fmt." + cangType + ".print";
        fmtConstantsNoNL.put(cangType, name);
        header.append(name).append(" = private unnamed_addr constant [").append(byteCount)
              .append(" x i8] c\"").append(format).append("\"\n");
    }

    // ==================== Class generation ====================

    private void generateClass(ClassDecl decl) {
        // Resolve by the full name recorded at collect time: the simple-name alias may have
        // been dropped or overwritten by a same-named class from another namespace.
        String fullName = declFullNames.get(decl);
        if (fullName == null) fullName = decl.name;
        ClassInfo ci = classes.get(fullName);
        if (ci == null) ci = classes.get(decl.name);
        if (ci == null) return;
        currentClassName = fullName;

        // Generate implicit constructor if has params OR has parent (to call parent ctor)
        if (!decl.ctorParams.isEmpty() || ci.parentName != null) {
            generateImplicitCtor(decl, ci, fullName);
        }

        for (AST member : decl.members) {
            if (member instanceof FuncDecl) {
                generateFunction((FuncDecl) member, fullName);
            } else if (member instanceof ConstructorDecl) {
                generateConstructor((ConstructorDecl) member, fullName);
            }
        }

        currentClassName = null;
    }

    /**
     * Generate implicit constructor: assigns params to fields
     */
    private void generateImplicitCtor(ClassDecl decl, ClassInfo ci, String fullName) {
        body.append("define void @").append(fullName).append(".constructor(");
        body.append("%").append(fullName).append("* %this");
        for (Parameter p : decl.ctorParams) {
            body.append(", ").append(toLLVMType(p.type)).append(" %").append(p.name);
        }
        body.append(") {\n");
        body.append("entry:\n");

        scope = new Scope(null);
        beginRegion();
        emitGcFrameSetup();
        scope.define("this", new LLVMValue("%this", "%" + fullName + "*"));
        currentFuncReturnType = "void";

        // Store parameters to alloca (so they can be referenced by superArgs)
        for (Parameter p : decl.ctorParams) {
            String pType = toLLVMType(p.type);
            String allocaName = "%p." + p.name;
            body.append("  ").append(allocaName).append(" = alloca ").append(pType).append("\n");
            body.append("  store ").append(pType).append(" %").append(p.name)
                 .append(", ").append(pType).append("* ").append(allocaName).append("\n");
            scope.define(p.name, new LLVMValue(allocaName, pType,
                semanticKindOf(p.type)));
            registerGcRoot(allocaName, pType);
            if (p.type.startsWith("Array<") && p.type.endsWith(">")) {
                trackArrayVar(p.name, p.type.substring(6, p.type.length() - 1));
            }
        }

        // Call parent constructor if inheriting (this sets parent's type ID first)
        if (ci.parentName != null) {
            ClassInfo parentInfo = classes.get(ci.parentName);
            if (parentInfo != null) {
                String parentCtor = parentInfo.fullName + ".constructor";
                if (functions.containsKey(parentCtor)) {
                    // Bitcast this to parent type
                    String parentThis = "%parent.this." + tmpCount++;
                    body.append("  ").append(parentThis).append(" = bitcast %")
                         .append(fullName).append("* %this to %")
                         .append(parentInfo.fullName).append("*\n");
                    // Call parent constructor with superArgs
                    StringBuilder args = new StringBuilder();
                    args.append("%").append(parentInfo.fullName).append("* ").append(parentThis);
                    List<AST> superArgs = ci.superArgs;
                    for (int i = 0; i < superArgs.size(); i++) {
                        LLVMValue argVal = generateExpr(superArgs.get(i));
                        args.append(", ").append(argVal.type).append(" ").append(argVal.value);
                    }
                    body.append("  call void @").append(parentCtor).append("(").append(args).append(")\n");
                }
            }
        }

        // Set type ID AFTER parent constructor (so child's ID overwrites parent's)
        String typePtr = "%typeid." + fullName;
        body.append("  ").append(typePtr).append(" = getelementptr %")
             .append(fullName).append(", %").append(fullName).append("* %this, i32 0, i32 0\n");
        body.append("  store i32 ").append(ci.typeId).append(", i32* ").append(typePtr).append("\n");

        // Store each param to corresponding field (after type ID + parent fields)
        int parentFieldCount = 0;
        if (ci.parentName != null) {
            ClassInfo parentInfo = classes.get(ci.parentName);
            if (parentInfo != null) parentFieldCount = parentInfo.fieldNames.size();
        }
        for (int i = 0; i < decl.ctorParams.size(); i++) {
            Parameter p = decl.ctorParams.get(i);
            int fieldIdx = 1 + parentFieldCount + i; // +1 for type ID
            String pType = toLLVMType(p.type);
            // Load from alloca
            String loaded = "%pval." + p.name + "." + tmpCount++;
            body.append("  ").append(loaded).append(" = load ").append(pType)
                 .append(", ").append(pType).append("* %p.").append(p.name).append("\n");
            // Store to field
            body.append("  %f").append(p.name).append(" = getelementptr ")
                .append(ci.llvmName).append(", ").append(ci.llvmName)
                .append("* %this, i32 0, i32 ").append(fieldIdx).append("\n");
            body.append("  store ").append(pType).append(" ").append(loaded)
                .append(", ").append(pType).append("* %f").append(p.name).append("\n");
        }

        endRegionCleanup();
        emitGcFramePop();
        body.append("  ret void\n");
        body.append("}\n\n");
        scope = null;
    }

    private void generateConstructor(ConstructorDecl decl, String fullName) {
        String funcName = fullName + ".constructor";
        ClassInfo ci = classes.get(decl.className);

        body.append("define void @").append(funcName).append("(");
        body.append("%").append(fullName).append("* %this");
        for (int i = 0; i < decl.params.size(); i++) {
            Parameter p = decl.params.get(i);
            body.append(", ").append(toLLVMType(p.type)).append(" %").append(p.name);
        }
        body.append(") {\n");
        body.append("entry:\n");

        scope = new Scope(null);
        beginRegion();
        emitGcFrameSetup();
        // Define 'this'
        scope.define("this", new LLVMValue("%this", "%" + fullName + "*"));
        currentFuncReturnType = "void";

        // Define parameters
        for (Parameter p : decl.params) {
            String allocaName = "%p." + p.name;
            String pLLVMType = toLLVMType(p.type);
            body.append("  ").append(allocaName).append(" = alloca ").append(pLLVMType).append("\n");
            body.append("  store ").append(pLLVMType).append(" %").append(p.name)
                 .append(", ").append(pLLVMType).append("* ").append(allocaName).append("\n");
            scope.define(p.name, new LLVMValue(allocaName, pLLVMType,
                semanticKindOf(p.type)));
            registerGcRoot(allocaName, pLLVMType);
            if (p.type.startsWith("Array<") && p.type.endsWith(">")) {
                trackArrayVar(p.name, p.type.substring(6, p.type.length() - 1));
            }
        }

        // Initialize fields to default values
        if (ci != null) {
            for (int i = 0; i < ci.fieldNames.size(); i++) {
                String fieldType = ci.fieldTypes.get(i);
                String defaultVal = defaultValueForType(fieldType);
                String ptr = "%f." + ci.fieldNames.get(i);
                body.append("  ").append(ptr).append(" = getelementptr ").append(ci.llvmName)
                     .append(", ").append(ci.llvmName).append("* %this, i32 0, i32 ").append(i).append("\n");
                body.append("  store ").append(toLLVMType(fieldType)).append(" ").append(defaultVal)
                     .append(", ").append(toLLVMType(fieldType)).append("* ").append(ptr).append("\n");
            }
        }

        // Generate body
        generateBlockBody((Block) decl.body);

        endRegionCleanup();
        // Ensure return
        if (!body.toString().endsWith("  ret void\n")) {
            body.append("  ret void\n");
        }
        body.append("}\n\n");

        scope = null;
    }

    // ==================== Function generation ====================

    /** AST-level "never completes normally" analysis (debug.md #12). */
    private boolean alwaysReturns(AST node) {
        if (node == null) return false;
        if (node instanceof ReturnStmt) return true;
        if (node instanceof Block) return alwaysReturnsList(((Block) node).statements);
        if (node instanceof IfStmt) {
            IfStmt s = (IfStmt) node;
            return s.elseBlock != null && alwaysReturns(s.thenBlock) && alwaysReturns(s.elseBlock);
        }
        if (node instanceof WhileStmt) {
            WhileStmt s = (WhileStmt) node;
            // while(true) with no break never leaves the body
            return s.condition instanceof BoolLit && ((BoolLit) s.condition).value && !containsBreak(s.body);
        }
        if (node instanceof ForStmt) {
            ForStmt s = (ForStmt) node;
            boolean infinite = s.condition == null
                || (s.condition instanceof BoolLit && ((BoolLit) s.condition).value);
            return infinite && !containsBreak(s.body);
        }
        if (node instanceof SwitchStmt) {
            SwitchStmt s = (SwitchStmt) node;
            if (s.defaultBody == null) return false;
            // Conservative (cases fall through): require every case body and the default to
            // always return — accepts the all-return form; partial fall-through chains rejected.
            for (SwitchCase c : s.cases) if (!alwaysReturnsList(c.body)) return false;
            return alwaysReturnsList(s.defaultBody);
        }
        if (node instanceof TryStmt) {
            TryStmt s = (TryStmt) node;
            if (!alwaysReturns(s.tryBlock)) return false;
            if (s.catches != null) {
                for (CatchClause cc : s.catches) if (!alwaysReturns(cc.body)) return false;
            }
            return true;
        }
        // Lambda/thread-block bodies are separate functions; other statements never return.
        return false;
    }

    private boolean alwaysReturnsList(List<AST> stmts) {
        if (stmts == null) return false;
        for (AST s : stmts) if (alwaysReturns(s)) return true;
        return false;
    }

    /** Does this subtree contain a break bound to the ENCLOSING loop/switch? Nested loops,
     *  switches, lambdas and thread blocks own their breaks, so they are not descended into. */
    private boolean containsBreak(AST node) {
        if (node == null) return false;
        if (node instanceof BreakStmt) return true;
        if (node instanceof WhileStmt || node instanceof ForStmt || node instanceof ForEachStmt
                || node instanceof SwitchStmt || node instanceof LambdaExpr || node instanceof ThreadBlockStmt) {
            return false;
        }
        for (java.lang.reflect.Field f : node.getClass().getFields()) {
            try {
                Object v = f.get(node);
                if (v instanceof AST) {
                    if (containsBreak((AST) v)) return true;
                } else if (v instanceof List) {
                    for (Object x : (List) v) if (x instanceof AST && containsBreak((AST) x)) return true;
                }
            } catch (IllegalAccessException ignored) { }
        }
        return false;
    }

    private void generateFunction(FuncDecl decl, String className) {
        // Set source file for error reporting
        String prevSourceFile = this.sourceFile;
        
        // Native/abstract methods have no body - skip generation
        if (decl.isNative || decl.isAbstract) {
            // Declaration-only: native is implemented intrinsically; abstract must be
            // implemented by a subclass (validateAbstractMethods enforces it).
            return;
        }

        // Freed-variable records are per-function (debug.md #19): `free x` inside g() must not
        // make a same-named variable in h() look already-freed. Clear on entry, restore on exit.
        java.util.Set<String> savedFreed = new java.util.HashSet<>(freedVars);
        freedVars.clear();

        if (!decl.sourceFile.isEmpty()) this.sourceFile = decl.sourceFile;

        String funcName = (className != null ? className + "." : "") + decl.name;
        FuncInfo fi = findFuncInfoForDecl(funcName, decl);
        String llvmName = fi != null && fi.llvmName != null ? fi.llvmName : funcName;

        body.append("define ").append(toLLVMType(decl.returnType)).append(" @").append(llvmName).append("(");

        // Parameters
        boolean first = true;
        if (className != null && !decl.isStatic) {
            body.append("%").append(className).append("* %this");
            first = false;
        }
        for (int i = 0; i < decl.params.size(); i++) {
            Parameter p = decl.params.get(i);
            if (!first) body.append(", ");
            body.append(toLLVMType(p.type)).append(" %").append(p.name);
            first = false;
        }
        body.append(") {\n");
        body.append("entry:\n");

        // Setup scope
        scope = new Scope(null);
        tmpCount = 0;
        currentFuncReturnType = decl.returnType;
        emitGcFrameSetup();

        if (className != null && !decl.isStatic) {
            scope.define("this", new LLVMValue("%this", "%" + className + "*"));
        }

        // Allocate parameters
        for (Parameter p : decl.params) {
            String allocaName = "%p." + p.name;
            String pLLVMType = toLLVMType(p.type);
            body.append("  ").append(allocaName).append(" = alloca ").append(pLLVMType).append("\n");
            body.append("  store ").append(pLLVMType).append(" %").append(p.name)
                 .append(", ").append(pLLVMType).append("* ").append(allocaName).append("\n");
            scope.define(p.name, new LLVMValue(allocaName, pLLVMType,
                semanticKindOf(p.type)));
            registerGcRoot(allocaName, pLLVMType);
            if (p.type.startsWith("Array<") && p.type.endsWith(">")) {
                trackArrayVar(p.name, p.type.substring(6, p.type.length() - 1));
            }
        }

        // Stack-overflow guard (debug.md #4) — probe before running user code.
        emitStackOverflowProbe(decl.line);

        // Generate body
        int bodyStart = body.length();
        generateBlockBody((Block) decl.body);

        endRegionCleanup();
        // Must-return via AST analysis (debug.md #12): precise instead of an IR-text search
        // (if-without-else missing return is now caught; while(true){} is no longer rejected).
        String recent = body.substring(bodyStart).trim();
        String lastLine = recent.isEmpty() ? ""
            : (recent.contains("\n") ? recent.substring(recent.lastIndexOf('\n') + 1) : recent).trim();
        // A line ending in ':' is a LABEL (e.g. "switch.end.3:" starts with "switch" but is
        // not a switch instruction) — only real terminator instructions count.
        boolean termLast = !lastLine.endsWith(":")
            && (lastLine.startsWith("ret ") || lastLine.startsWith("br ")
                || lastLine.startsWith("switch ") || lastLine.startsWith("unreachable"));
        if (!decl.returnType.equals("void")) {
            if (!alwaysReturns((Block) decl.body)) {
                String srcFile = !decl.sourceFile.isEmpty() ? decl.sourceFile : sourceFile;
                String srcLine = "";
                try {
                    if (!srcFile.isEmpty()) {
                        String[] fileLines = java.nio.file.Files.readString(java.nio.file.Path.of(srcFile)).split("\n", -1);
                        if (decl.line > 0 && decl.line <= fileLines.length) {
                            srcLine = fileLines[decl.line - 1];
                        }
                    }
                } catch (Exception ignored) {}
                throw new util.CompileError(
                    "Function '" + decl.name + "' must return a value of type '" + decl.returnType + "'",
                    srcFile, decl.line, 1, srcLine, decl.name.length());
            }
            if (!termLast) {
                // Every path returned but the text ends on a join label (if/switch end):
                // that block is unreachable — give it a terminator so the IR stays valid.
                body.append("  unreachable\n");
            }
        } else if (!termLast) {
            body.append("  ret void\n");
        }

        body.append("}\n\n");
        scope = null;
        this.sourceFile = prevSourceFile;
        freedVars.clear();
        freedVars.addAll(savedFreed);
    }

    // ==================== Statement generation ====================

    private void generateBlockBody(Block block) {
        for (AST stmt : block.statements) {
            generateStmt(stmt);
        }
    }

    private void generateStmt(AST node) {
        lastStmtLine = node.line; // for wrapping bare codegen errors (debug.md #27)
        if (node instanceof Block) {
            Scope prev = scope;
            scope = new Scope(prev);
            generateBlockBody((Block) node);
            scope = prev;
        } else if (node instanceof VarDecl) {
            generateVarDecl((VarDecl) node);
        } else if (node instanceof IfStmt) {
            markRegionControlFlow();
            generateIf((IfStmt) node);
        } else if (node instanceof SwitchStmt) {
            markRegionControlFlow();
            generateSwitch((SwitchStmt) node);
        } else if (node instanceof WhileStmt) {
            markRegionControlFlow();
            generateWhile((WhileStmt) node);
        } else if (node instanceof ForStmt) {
            markRegionControlFlow();
            generateFor((ForStmt) node);
        } else if (node instanceof ForEachStmt) {
            markRegionControlFlow();
            generateForEach((ForEachStmt) node);
        } else if (node instanceof ReturnStmt) {
            generateReturn((ReturnStmt) node);
        } else if (node instanceof ThrowStmt) {
            generateThrow((ThrowStmt) node);
        } else if (node instanceof TryStmt) {
            generateTry((TryStmt) node);
        } else if (node instanceof BreakStmt) {
            if (!loopStack.isEmpty()) {
                emitLoopJump(loopStack.get(loopStack.size() - 1).breakLabel);
            }
        } else if (node instanceof ContinueStmt) {
            if (!loopStack.isEmpty()) {
                emitLoopJump(loopStack.get(loopStack.size() - 1).continueLabel);
            }
        } else if (node instanceof FreeStmt) {
            generateFree((FreeStmt) node);
        } else if (node instanceof ThreadBlockStmt) {
            generateThreadBlock((ThreadBlockStmt) node);
        } else if (node instanceof ExprStmt) {
            generateExpr(((ExprStmt) node).expr);
        } else if (node instanceof VarDecl) {
            generateVarDecl((VarDecl) node);
        }
    }

    /** Raise a runtime Error at a check site, then continue through an unreachable dead block. */
    private void emitRuntimeError(String message, int line, String continuationLabel) {
        if (classes.get("Error") == null) {
            emitFatalError(message, line, continuationLabel);
            return;
        }
        List<AST> args = new ArrayList<>();
        args.add(new StringLit(message, line));
        LLVMValue error = generateNew(new NewExpr("Error", args, line));
        String raw = "%runtime.error." + tmpCount++;
        body.append("  ").append(raw).append(" = bitcast ").append(error.type).append(" ").append(error.value).append(" to i8*\n");
        body.append("  store i8* ").append(raw).append(", i8** @cang.current.error\n");
        if (exceptionHandlers.isEmpty()) {
            emitUncaughtError(line);
        } else {
            body.append("  br label %").append(exceptionHandlers.peek()).append("\n");
        }
        // Unreachable continuation so following code keeps forming a valid block.
        body.append("runtime.error.dead.").append(runtimeErrorCount++).append(":\n");
        body.append("  br label %").append(continuationLabel).append("\n");
    }

    /**
     * Always-fatal runtime error (print + exit), for unrecoverable conditions such as
     * double join or thread creation failure. Never routed through catch handlers.
     */
    /**
     * Record the current thread's stack base (debug.md #4). Called once at the start of
     * main and of every thread entry; function-entry probes compare SP against this.
     */
    private void emitStackBaseSet(StringBuilder sb, String tag) {
        String p = "%stkbase.p." + tag;
        String v = "%stkbase.v." + tag;
        sb.append("  ").append(p).append(" = alloca i8\n");
        sb.append("  ").append(v).append(" = ptrtoint i8* ").append(p).append(" to i64\n");
        sb.append("  store i64 ").append(v).append(", i64* @.stack.base\n");
    }

    /** Capture SP at loop entry so per-iteration allocas can be reclaimed (debug.md #5). */
    private String emitStackSave() {
        String sv = "%stacksave." + tmpCount++;
        body.append("  ").append(sv).append(" = call i8* @llvm.stacksave()\n");
        return sv;
    }

    /** Restore SP to a previous emitStackSave point (idempotent when already at that level). */
    private void emitStackRestore(String sv) {
        if (sv == null) return;
        body.append("  call void @llvm.stackrestore(i8* ").append(sv).append(")\n");
    }

    /**
     * Stack-overflow guard at function entry (debug.md #4): probe this frame's address and
     * raise a friendly fatal error when the thread has used more than STACK_BUDGET bytes.
     * The probe is SP-based, so it adapts to any frame size and needs no decrement on ret.
     */
    private void emitStackOverflowProbe(int line) {
        int sid = labelCount++;
        String okL = "stk.ok." + sid;
        String ovfL = "stk.ovf." + sid;
        String probe = "%stk.probe." + tmpCount++;
        String sp = "%stk.sp." + tmpCount++;
        String base = "%stk.base." + tmpCount++;
        String lim = "%stk.lim." + tmpCount++;
        String under = "%stk.under." + tmpCount++;
        String nz = "%stk.nz." + tmpCount++;
        // Value prefix must differ from the label prefix (%stk.ovf.N vs label stk.ovf.N
        // share one namespace in LLVM — see debug.md #34).
        String ovf = "%stk.need." + tmpCount++;
        body.append("  ").append(probe).append(" = alloca i8\n");
        body.append("  ").append(sp).append(" = ptrtoint i8* ").append(probe).append(" to i64\n");
        body.append("  ").append(base).append(" = load i64, i64* @.stack.base\n");
        body.append("  ").append(lim).append(" = sub i64 ").append(base).append(", ").append(STACK_BUDGET).append("\n");
        body.append("  ").append(under).append(" = icmp ult i64 ").append(sp).append(", ").append(lim).append("\n");
        body.append("  ").append(nz).append(" = icmp ne i64 ").append(base).append(", 0\n");
        body.append("  ").append(ovf).append(" = and i1 ").append(nz).append(", ").append(under).append("\n");
        body.append("  br i1 ").append(ovf).append(", label %").append(ovfL)
             .append(", label %").append(okL).append("\n\n");
        body.append(ovfL).append(":\n");
        emitFatalError("Stack overflow: recursion too deep", line, okL);
        body.append(okL).append(":\n");
    }

    private void emitFatalError(String message, int line, String continuationLabel) {
        int id = runtimeErrorCount++;
        String msg = ensureStringConstant("@.str.rterror." + id, message + "\\0A\\00", message.length() + 2);
        String file = ensureStringConstant("@.str.rtfile." + id, sourceFile.replace('\\', '/') + "\\00", sourceFile.length() + 1);
        ensureStringConstant("@.str.locfmt", "  at %s:%d\\0A\\00", 12);
        body.append("  call i32 (i8*, ...) @printf(i8* getelementptr ([11 x i8], [11 x i8]* @.str.errprefix, i32 0, i32 0), i8* ")
             .append(msg).append(")\n");
        body.append("  call i32 (i8*, ...) @printf(i8* getelementptr ([12 x i8], [12 x i8]* @.str.locfmt, i32 0, i32 0), i8* ")
             .append(file).append(", i32 ").append(line).append(")\n");
        body.append("  call void @exit(i32 1)\n");
        body.append("  unreachable\n");
        body.append("runtime.error.dead.").append(id).append(":\n");
        body.append("  br label %").append(continuationLabel).append("\n");
    }

    private void generateThrow(ThrowStmt stmt) {
        LLVMValue value = generateExpr(stmt.value);
        if (!(value.type.endsWith("*") && value.type.startsWith("%"))) {
            throw new RuntimeException("throw requires Error or an Error subclass (at line " + stmt.line + ")");
        }
        String raw = "%throw.raw." + tmpCount++;
        body.append("  ").append(raw).append(" = bitcast ").append(value.type).append(" ").append(value.value).append(" to i8*\n");
        body.append("  store i8* ").append(raw).append(", i8** @cang.current.error\n");
        if (exceptionHandlers.isEmpty()) {
            emitUncaughtError(stmt.line);
            body.append("throw.dead.").append(labelCount++).append(":\n");
        } else {
            // Throw from a CATCH clause: inline every enclosing finally before propagating
            // outward (debug.md #21). A throw inside the try BODY keeps the normal handler
            // path — the shared finally runs at `after` and needs no inlining.
            boolean swallowed = false;
            if (!finallyStack.isEmpty() && !finallyStack.peek().inTryBody) {
                swallowed = inlineFinallyLayers();
            }
            if (!swallowed) {
                body.append("  br label %").append(exceptionHandlers.peek()).append("\n");
            }
            // Statements after throw are dead code but must still form a valid LLVM block.
            body.append("throw.dead.").append(labelCount++).append(":\n");
        }
    }

    /** Jump out of a loop: inline enclosing finally layers first (debug.md #20), then branch.
     *  Opens a dead label when a finally was involved so following statements stay valid. */
    private void emitLoopJump(String target) {
        boolean hadFinally = !finallyStack.isEmpty();
        boolean swallowed = inlineFinallyLayers();
        if (!swallowed) {
            body.append("  br label %").append(target).append("\n");
        }
        if (hadFinally) {
            body.append("jexit.dead.").append(labelCount++).append(":\n");
        }
    }

    /**
     * Emit an uncaught-exception path: print message (when Error is known) and exit.
     * Followed by a fresh label so subsequent dead statements stay well-formed.
     */
    private void emitUncaughtError(int line) {
        String uncaught = "uncaught." + labelCount++;
        body.append("  br label %").append(uncaught).append("\n");
        body.append(uncaught).append(":\n");
        String message = ensureStringConstant("@.str.uncaught", "uncaught exception\\0A\\00", 20);
        String fileName = ensureStringConstant("@.str.srcfile", sourceFile.replace('\\', '/') + "\\00", sourceFile.length() + 1);
        ensureStringConstant("@.str.locfmt", "  at %s:%d\\0A\\00", 12);
        // Prefer the thrown Error.message when the Error class layout is known.
        ClassInfo errorInfo = classes.get("Error");
        String msgValue = message;
        if (errorInfo != null) {
            Integer fieldIdx = errorInfo.fieldIndices.get("message");
            if (fieldIdx != null) {
                String caught = "%uncaught.raw." + tmpCount++;
                String typed = "%uncaught.err." + tmpCount++;
                String fieldPtr = "%uncaught.msg.ptr." + tmpCount++;
                String loaded = "%uncaught.msg." + tmpCount++;
                String chosen = "%uncaught.msg.use." + tmpCount++;
                body.append("  ").append(caught).append(" = load i8*, i8** @cang.current.error\n");
                body.append("  ").append(typed).append(" = bitcast i8* ").append(caught).append(" to ").append(errorInfo.llvmName).append("*\n");
                body.append("  ").append(fieldPtr).append(" = getelementptr ").append(errorInfo.llvmName)
                     .append(", ").append(errorInfo.llvmName).append("* ").append(typed)
                     .append(", i32 0, i32 ").append(fieldIdx).append("\n");
                body.append("  ").append(loaded).append(" = load i8*, i8** ").append(fieldPtr).append("\n");
                String hasMsg = "%uncaught.hasmsg." + tmpCount++;
                body.append("  ").append(hasMsg).append(" = icmp ne i8* ").append(loaded).append(", null\n");
                body.append("  ").append(chosen).append(" = select i1 ").append(hasMsg)
                     .append(", i8* ").append(loaded).append(", i8* ").append(message).append("\n");
                msgValue = chosen;
            }
        }
        body.append("  call i32 (i8*, ...) @printf(i8* getelementptr ([11 x i8], [11 x i8]* @.str.errprefix, i32 0, i32 0), i8* ")
             .append(msgValue).append(")\n");
        body.append("  call i32 (i8*, ...) @printf(i8* getelementptr ([12 x i8], [12 x i8]* @.str.locfmt, i32 0, i32 0), i8* ")
             .append(fileName).append(", i32 ").append(line).append(")\n");
        body.append("  call void @exit(i32 1)\n");
        body.append("  unreachable\n");
    }

    private void generateTry(TryStmt stmt) {
        if (stmt.catches.size() > 1) {
            throw new RuntimeException("Multiple catch clauses are not supported yet (at line " + stmt.line + ")");
        }
        int id = labelCount++;
        String handler = "try.handler." + id;
        String after = "try.after." + id;
        boolean hasCatch = !stmt.catches.isEmpty();
        boolean hasFinally = stmt.finallyBlock != null;
        // finally-only try must run finally and then propagate: track which path entered it.
        boolean needPending = !hasCatch && hasFinally;
        String pending = "%try.pending." + id;

        // Validate catch types: must be Error or an Error subclass.
        for (CatchClause c : stmt.catches) {
            if (c.type.equals("Error")) {
                if (classes.get("Error") == null) {
                    throw new RuntimeException("catch (Error ...) requires 'import cang/lang/Error' (at line " + c.line + ")");
                }
                continue;
            }
            ClassInfo ci = classes.get(c.type);
            if (ci == null) {
                throw new RuntimeException("Unknown catch type: " + c.type + " (at line " + c.line + ")");
            }
            if (!isErrorSubclass(ci)) {
                throw new RuntimeException("catch type must be Error or an Error subclass: " + c.type
                    + " (at line " + c.line + ")");
            }
        }

        if (needPending) {
            body.append("  ").append(pending).append(" = alloca i32\n");
            body.append("  store i32 0, i32* ").append(pending).append("\n");
        }

        exceptionHandlers.push(handler);
        FinallyCtx fctx = hasFinally ? new FinallyCtx(stmt.finallyBlock) : null;
        if (fctx != null) finallyStack.push(fctx);
        int tryStart = body.length();
        generateStmt(stmt.tryBlock);
        exceptionHandlers.pop();
        if (fctx != null) fctx.inTryBody = false; // catch phase: throws here must inline finally
        if (needPending && !tailTerminated(tryStart)) {
            body.append("  store i32 0, i32* ").append(pending).append("\n");
        }
        emitBranchIfNeeded(after, tryStart);
        body.append(handler).append(":\n");

        if (hasCatch) {
            CatchClause c = stmt.catches.get(0);
            ClassInfo errorInfo = classes.get(c.type);
            String errorType = errorInfo.llvmName + "*";
            String caught = "%catch.value." + tmpCount++;
            body.append("  ").append(caught).append(" = load i8*, i8** @cang.current.error\n");
            String typed = "%catch.typed." + tmpCount++;
            body.append("  ").append(typed).append(" = bitcast i8* ").append(caught).append(" to ").append(errorType).append("\n");
            String typedSlot = "%catch.typed.slot." + tmpCount++;
            body.append("  ").append(typedSlot).append(" = alloca ").append(errorType).append("\n");
            body.append("  store ").append(errorType).append(" ").append(typed).append(", ").append(errorType).append("* ").append(typedSlot).append("\n");
            scope.define(c.name, new LLVMValue(typedSlot, errorType, c.type));
            registerGcRoot(typedSlot, errorType);
            int catchStart = body.length();
            generateStmt(c.body);
            emitBranchIfNeeded(after, catchStart);
        } else {
            // finally-only: mark this path so the shared finally block propagates afterwards.
            body.append("  store i32 1, i32* ").append(pending).append("\n");
            body.append("  br label %").append(after).append("\n");
        }
        body.append(after).append(":\n");
        // The shared finally block runs without this layer on the stack: a return or throw
        // inside finally must only chain to the OUTER layers (Java semantics).
        if (fctx != null) finallyStack.remove(fctx);
        if (hasFinally) generateStmt(stmt.finallyBlock);

        if (needPending) {
            int finallyEnd = body.length();
            String loaded = "%try.pending.load." + tmpCount++;
            String isRethrow = "%try.pending.rethrow." + tmpCount++;
            String rethrow = "try.rethrow." + id;
            String continueLabel = "try.continue." + id;
            // Alloca/loads only after the finally block; the block may already be terminated.
            if (tailTerminated(finallyEnd)) {
                // Finally returned/threw: no continuation code is reachable here.
                return;
            }
            body.append("  ").append(loaded).append(" = load i32, i32* ").append(pending).append("\n");
            body.append("  ").append(isRethrow).append(" = icmp eq i32 ").append(loaded).append(", 1\n");
            body.append("  br i1 ").append(isRethrow).append(", label %").append(rethrow)
                 .append(", label %").append(continueLabel).append("\n\n");
            body.append(rethrow).append(":\n");
            if (!exceptionHandlers.isEmpty()) {
                body.append("  br label %").append(exceptionHandlers.peek()).append("\n");
            } else {
                emitUncaughtError(stmt.line);
                body.append("runtime.rethrow.dead.").append(labelCount++).append(":\n");
                // emitUncaughtError terminates the uncaught block; continue in a dead block.
                body.append("  br label %").append(continueLabel).append("\n");
            }
            body.append(continueLabel).append(":\n");
        }
    }

    /** Emit every active layer's finally body (innermost first) ahead of a control jump that
     *  leaves the try (return / break / continue / catch-throw). Each layer generates with only
     *  the OUTER layers active, so a return inside one finally chains outward correctly.
     *  Returns true when a layer swallowed the jump (finally returned/threw) — the caller must
     *  then NOT emit its own jump. Callers must open a fresh dead label when the stack was
     *  non-empty so following statements form a valid block. */
    private boolean inlineFinallyLayers() {
        if (finallyStack.isEmpty()) return false;
        List<FinallyCtx> layers = new ArrayList<>(finallyStack);
        finallyStack.clear();
        boolean dead = false;
        for (int i = 0; i < layers.size(); i++) {
            finallyStack.addAll(layers.subList(i + 1, layers.size()));
            if (dead || tailTerminated(body.length())) {
                body.append("fin.dead.").append(labelCount++).append(":\n");
                dead = true;
            }
            int segStart = body.length();
            generateStmt(layers.get(i).finallyBlock);
            if (tailTerminated(segStart)) dead = true;
            finallyStack.clear();
        }
        finallyStack.addAll(layers);
        return dead;
    }

    /** True when the text generated since offset already ends with a terminator instruction. */
    private boolean tailTerminated(int sinceOffset) {
        String trimmed = body.substring(sinceOffset).trim();
        int lastBreak = trimmed.lastIndexOf('\n');
        String lastLine = (lastBreak >= 0 ? trimmed.substring(lastBreak + 1) : trimmed).trim();
        return lastLine.startsWith("ret ") || lastLine.startsWith("br ") || lastLine.equals("unreachable");
    }

    /** True when a class derives (transitively) from cang/lang/Error. */
    private boolean isErrorSubclass(ClassInfo ci) {
        int guard = 0;
        while (ci != null && guard++ < 64) {
            if (ci.simpleName.equals("Error") || ci.fullName.equals("cang_lang_Error")) return true;
            ci = ci.parentName != null ? classes.get(ci.parentName) : null;
        }
        return false;
    }

    /**
     * thread { ... } sugar: compile the block into a synthetic top-level void function,
     * spawn it via the regular Thread.spawn path (GC-aware), store the handle globally,
     * and join all such threads just before program exit so their output is never lost.
     */
    private LLVMValue generateThreadBlockExpr(ThreadBlockExpr node) {
        // Sugar: new Thread(false).task(() -> { body }).start()
        // Each evaluation creates a fresh object/handle registered for the main-exit join
        // sweep, so loops are fine (the old single-global-handle mechanism was not).
        AST falseFlag = new BoolLit(false, node.line);
        NewExpr thrNew = new NewExpr("Thread", java.util.Collections.singletonList(falseFlag), node.line);
        LambdaExpr lam = new LambdaExpr(new ArrayList<>(), node.body, "void", node.line);
        MethodCallExpr taskCall = new MethodCallExpr(thrNew, "task",
            java.util.Collections.singletonList(lam), node.line);
        MethodCallExpr startCall = new MethodCallExpr(taskCall, "start", new ArrayList<>(), node.line);
        return generateExpr(startCall);
    }

    private void generateThreadBlock(ThreadBlockStmt stmt) {
        if (!loopStack.isEmpty()) {
            throw new RuntimeException("thread block inside loops is not supported yet (at line " + stmt.line + ")");
        }
        // Same no-capture rule as lambdas: the block runs on another stack.
        validateLambdaCapture(stmt.body, new java.util.HashSet<String>(), stmt.line, "Thread block");

        int n = threadBlockCount++;
        String entryName = "cang.threadblock." + n;
        String fnName = "@" + entryName;

        Scope savedScope = scope;
        String savedReturn = currentFuncReturnType;
        List<LoopContext> savedLoops = new ArrayList<>(loopStack);
        java.util.Set<String> savedFreed = new java.util.HashSet<>(freedVars);
        freedVars.clear();
        // Freed-records are per-function/lambda (debug.md #19).
        Deque<String> savedHandlers = new ArrayDeque<>(exceptionHandlers);
        loopStack.clear();
        exceptionHandlers.clear();
        scope = new Scope(null);
        currentFuncReturnType = "void";

        int start = body.length();
        body.append("define void ").append(fnName).append("() {\nentry:\n");
        generateBlockBody((Block) stmt.body);
        String trimmed = body.substring(start).trim();
        int lastBreak = trimmed.lastIndexOf('\n');
        String lastLine = (lastBreak >= 0 ? trimmed.substring(lastBreak + 1) : trimmed).trim();
        boolean terminated = lastLine.startsWith("ret ") || lastLine.startsWith("br ") || lastLine.equals("unreachable");
        if (!terminated) body.append("  ret void\n");
        body.append("}\n\n");
        int end = body.length();
        extraDefs.append(body, start, end);
        body.delete(start, end);

        scope = savedScope;
        currentFuncReturnType = savedReturn;
        loopStack.clear();
        loopStack.addAll(savedLoops);
        exceptionHandlers.clear();
        exceptionHandlers.addAll(savedHandlers);
        freedVars.clear();
        freedVars.addAll(savedFreed);

        // Register as a plain top-level void function so Thread.spawn accepts it.
        FuncInfo fi = new FuncInfo();
        fi.name = entryName;
        fi.returnType = "void";
        fi.isStatic = false;
        fi.className = null;
        functions.put(entryName, fi);

        LLVMValue handle = generateThreadSpawn(new MethodCallExpr(
            new Identifier("Thread", stmt.line),
            "spawn",
            java.util.Collections.singletonList(new Identifier(entryName, stmt.line)),
            stmt.line));

        String global = "@cang.thread.handle." + n;
        extraDefs.append(global).append(" = global %CangThreadHandle* null\n");
        body.append("  store %CangThreadHandle* ").append(handle.value)
             .append(", %CangThreadHandle** ").append(global).append("\n");
        threadBlockHandles.add(global);
    }

    /** Join every thread spawned by `thread { }` blocks, just before the program returns. */
    private void emitThreadBlockJoins() {
        for (String global : threadBlockHandles) {
            int id = labelCount++;
            String skip = "thb.join.skip." + id;
            String doJoin = "thb.join.do." + id;
            String handle = "%thb.join.h." + tmpCount++;
            body.append("  ").append(handle).append(" = load %CangThreadHandle*, %CangThreadHandle** ")
                 .append(global).append("\n");
            // A thread block in a method that was never called keeps its handle null.
            String isNull = "%thb.join.isnull." + tmpCount++;
            body.append("  ").append(isNull).append(" = icmp eq %CangThreadHandle* ").append(handle).append(", null\n");
            body.append("  br i1 ").append(isNull).append(", label %").append(skip).append(", label %").append(doJoin).append("\n");
            body.append(doJoin).append(":\n");
            String idPtr = "%thb.join.id." + tmpCount++;
            body.append("  ").append(idPtr).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ")
                 .append(handle).append(", i32 0, i32 0\n");
            String tid = "%thb.join.tid." + tmpCount++;
            body.append("  ").append(tid).append(" = load i64, i64* ").append(idPtr).append("\n");
            if (targetPlatform.equals("windows")) {
                String hp = "%thb.join.hp." + tmpCount++;
                body.append("  ").append(hp).append(" = inttoptr i64 ").append(tid).append(" to i8*\n");
                body.append("  call i32 @WaitForSingleObject(i8* ").append(hp).append(", i32 -1)\n");
                body.append("  call i32 @CloseHandle(i8* ").append(hp).append(")\n");
            } else {
                body.append("  call i32 @pthread_join(i64 ").append(tid).append(", i8** null)\n");
            }
            body.append("  br label %").append(skip).append("\n");
            body.append(skip).append(":\n");
        }
    }

    /** Main-exit sweep for registered non-daemon object-style threads: join each slot that is
     *  non-null and not already joined (manual join() sets the joined flag and we skip it). */
    private void emitRegisteredThreadJoins() {
        int id = labelCount++;
        String cond = "sweep.cond." + id;
        String loopBody = "sweep.body." + id;
        String update = "sweep.upd." + id;
        String end = "sweep.end." + id;
        String skip = "sweep.skip." + id;
        String doJoin = "sweep.do." + id;
        String already = "sweep.already." + id;
        String joinedChk = "sweep.joined." + id;
        String n = "%sweep.n." + tmpCount++;
        body.append("  ").append(n).append(" = load i64, i64* @cang.thr.regc\n");
        String idxPtr = "%sweep.i." + tmpCount++;
        body.append("  ").append(idxPtr).append(" = alloca i64\n");
        body.append("  store i64 0, i64* ").append(idxPtr).append("\n");
        body.append("  br label %").append(cond).append("\n\n");
        body.append(cond).append(":\n");
        String iv = "%sweep.iv." + tmpCount++;
        body.append("  ").append(iv).append(" = load i64, i64* ").append(idxPtr).append("\n");
        String cmp = "%sweep.cmp." + tmpCount++;
        body.append("  ").append(cmp).append(" = icmp slt i64 ").append(iv).append(", ").append(n).append("\n");
        body.append("  br i1 ").append(cmp).append(", label %").append(loopBody)
             .append(", label %").append(end).append("\n\n");
        body.append(loopBody).append(":\n");
        String slot = "%sweep.slot." + tmpCount++;
        body.append("  ").append(slot).append(" = getelementptr [256 x i8*], [256 x i8*]* @cang.thr.regs, i64 0, i64 ")
             .append(iv).append("\n");
        String hraw = "%sweep.h." + tmpCount++;
        body.append("  ").append(hraw).append(" = load i8*, i8** ").append(slot).append("\n");
        String isNull = "%sweep.isnull." + tmpCount++;
        body.append("  ").append(isNull).append(" = icmp eq i8* ").append(hraw).append(", null\n");
        body.append("  br i1 ").append(isNull).append(", label %").append(skip)
             .append(", label %").append(joinedChk).append("\n");
        body.append(joinedChk).append(":\n");
        String h = "%sweep.ht." + tmpCount++;
        body.append("  ").append(h).append(" = bitcast i8* ").append(hraw).append(" to %CangThreadHandle*\n");
        String joinedPtr = "%sweep.jp." + tmpCount++;
        body.append("  ").append(joinedPtr).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ")
             .append(h).append(", i32 0, i32 2\n");
        String joined = "%sweep.j." + tmpCount++;
        body.append("  ").append(joined).append(" = load i32, i32* ").append(joinedPtr).append("\n");
        String isJoined = "%sweep.isj." + tmpCount++;
        body.append("  ").append(isJoined).append(" = icmp eq i32 ").append(joined).append(", 1\n");
        body.append("  br i1 ").append(isJoined).append(", label %").append(already)
             .append(", label %").append(doJoin).append("\n");
        body.append(doJoin).append(":\n");
        String idPtr = "%sweep.id." + tmpCount++;
        body.append("  ").append(idPtr).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ")
             .append(h).append(", i32 0, i32 0\n");
        String tid = "%sweep.tid." + tmpCount++;
        body.append("  ").append(tid).append(" = load i64, i64* ").append(idPtr).append("\n");
        if (targetPlatform.equals("windows")) {
            String hp = "%sweep.hp." + tmpCount++;
            body.append("  ").append(hp).append(" = inttoptr i64 ").append(tid).append(" to i8*\n");
            body.append("  call i32 @WaitForSingleObject(i8* ").append(hp).append(", i32 -1)\n");
            body.append("  call i32 @CloseHandle(i8* ").append(hp).append(")\n");
        } else {
            body.append("  call i32 @pthread_join(i64 ").append(tid).append(", i8** null)\n");
        }
        // Clear the slot so a re-run (or nested sweep) is idempotent.
        body.append("  store i8* null, i8** ").append(slot).append("\n");
        body.append("  br label %").append(skip).append("\n");
        body.append(already).append(":\n");
        body.append("  br label %").append(skip).append("\n");
        body.append(skip).append(":\n");
        body.append("  br label %").append(update).append("\n");
        body.append(update).append(":\n");
        String iv2 = "%sweep.iv2." + tmpCount++;
        body.append("  ").append(iv2).append(" = add i64 ").append(iv).append(", 1\n");
        body.append("  store i64 ").append(iv2).append(", i64* ").append(idxPtr).append("\n");
        body.append("  br label %").append(cond).append("\n\n");
        body.append(end).append(":\n");
    }

    private void generateFree(FreeStmt stmt) {
        for (String name : stmt.names) {
            if (freedVars.contains(name)) {
                throw new RuntimeException("Variable '" + name + "' has already been freed (at line " + stmt.line + ")");
            }
            LLVMValue ptr = scope.lookup(name);
            if (ptr == null) {
                throw new RuntimeException("Undefined variable: " + name + " (at line " + stmt.line + ")");
            }
            if (ptr.type.equals("i8*") || (ptr.type.startsWith("%") && ptr.type.endsWith("*"))) {
                String value = "%free." + tmpCount++;
                body.append("  ").append(value).append(" = load ").append(ptr.type)
                     .append(", ").append(ptr.type).append("* ").append(ptr.value).append("\n");
                // Free Array fields of the object along with it (debug.md #18: List/Dict data
                // buffers) and — in GC mode only — String fields such as File.path (debug.md #33).
                // String frees are gated by GC_is_heap_ptr: GC_free on a constant-pool pointer
                // crashes (verified), and --no-gc's libc free cannot tell constants from heap
                // strings, so that mode skips String fields (documented). One level only;
                // nested object fields stay with the GC. NULL fields are skipped at runtime.
                String freeCls = extractClassName(ptr.type);
                ClassInfo freeCi = freeCls != null ? classes.get(freeCls) : null;
                if (freeCi != null && !freeCi.fieldIndices.isEmpty()) {
                    for (Map.Entry<String, Integer> fe : freeCi.fieldIndices.entrySet()) {
                        String ft = freeCi.fieldTypes.get(fe.getValue() - 1);
                        if (ft == null) continue;
                        boolean freeArray = ft.startsWith("Array<");
                        // String fields (e.g. File.path): free ONLY under GC mode — GC_free on a
                        // constant-pool pointer is a safe no-op, while --no-gc's libc free cannot
                        // distinguish constants from heap strings and would crash (debug.md #33).
                        boolean freeString = gcEnabled && (ft.equals("String") || ft.equals("str"));
                        if (!freeArray && !freeString) continue;
                        String fllvm = toLLVMType(ft);
                        String fptr = "%free.fptr." + tmpCount++;
                        String fval = "%free.fval." + tmpCount++;
                        body.append("  ").append(fptr).append(" = getelementptr ").append(freeCi.llvmName)
                             .append(", ").append(freeCi.llvmName).append("* ").append(value)
                             .append(", i32 0, i32 ").append(fe.getValue()).append("\n");
                        body.append("  ").append(fval).append(" = load ").append(fllvm).append(", ")
                             .append(fllvm).append("* ").append(fptr).append("\n");
                        String fnull = "%free.fnull." + tmpCount++;
                        String fok = "free.fok." + labelCount++;
                        String fskip = "free.fskip." + labelCount++;
                        body.append("  ").append(fnull).append(" = icmp eq ").append(fllvm).append(" ")
                             .append(fval).append(", null\n");
                        body.append("  br i1 ").append(fnull).append(", label %").append(fskip)
                             .append(", label %").append(fok).append("\n");
                        body.append(fok).append(":\n");
                        String fcast = fllvm.equals("i8*") ? fval : "%free.fcast." + tmpCount++;
                        if (!fllvm.equals("i8*")) {
                            body.append("  ").append(fcast).append(" = bitcast ").append(fllvm).append(" ")
                                 .append(fval).append(" to i8*\n");
                        }
                        if (freeString) {
                            // Gate on the Boehm heap: freeing a constant-pool pointer is UB/crash.
                            String heap = "%free.hp." + tmpCount++;
                            String fdo = "free.heap." + labelCount++;
                            body.append("  ").append(heap).append(" = call i1 @GC_is_heap_ptr(i8* ")
                                 .append(fcast).append(")\n");
                            body.append("  br i1 ").append(heap).append(", label %").append(fdo)
                                 .append(", label %").append(fskip).append("\n");
                            body.append(fdo).append(":\n");
                            body.append("  call void @").append(freeFn()).append("(i8* ").append(fcast).append(")\n");
                            body.append("  br label %").append(fskip).append("\n\n");
                        } else {
                            body.append("  call void @").append(freeFn()).append("(i8* ").append(fcast).append(")\n");
                            body.append("  br label %").append(fskip).append("\n\n");
                        }
                        body.append(fskip).append(":\n");
                    }
                }
                String freeValue = value;
                if (!ptr.type.equals("i8*")) {
                    freeValue = "%free.cast." + tmpCount++;
                    body.append("  ").append(freeValue).append(" = bitcast ").append(ptr.type).append(" ").append(value).append(" to i8*\n");
                }
                body.append("  call void @").append(freeFn()).append("(i8* ").append(freeValue).append(")\n");
                body.append("  store ").append(ptr.type).append(" null, ").append(ptr.type).append("* ").append(ptr.value).append("\n");
            }
            freedVars.add(name);
        }
    }

    /** Constant range / narrowing check for a literal assigned to a typed slot (debug.md #8).
     *  Variable RHS stays unchecked for now (per item guidance: literals first). */
    private void checkLiteralFits(AST init, String cangTarget, int line) {
        if (init == null || cangTarget == null) return;
        boolean intTarget = cangTarget.equals("int") || cangTarget.equals("byte") || cangTarget.equals("long");
        // Floating literal into an integer slot: Java-style lossy conversion error.
        if ((init instanceof DoubleLit || init instanceof FloatLit) && intTarget) {
            throw new RuntimeException("Lossy conversion: cannot assign literal to '" + cangTarget
                + "' without an explicit conversion (at line " + line + ")");
        }
        if (!intTarget) return;
        // Integer literal (optionally negated) with range check against the target type.
        boolean neg = false;
        AST probe = init;
        if (probe instanceof UnaryExpr && ((UnaryExpr) probe).op.equals("-")
                && ((UnaryExpr) probe).prefix && ((UnaryExpr) probe).operand instanceof IntLit) {
            neg = true;
            probe = ((UnaryExpr) probe).operand;
        }
        if (!(probe instanceof IntLit)) return;
        java.math.BigInteger bi;
        String t = ((IntLit) probe).text;
        try {
            if (t.startsWith("0x") || t.startsWith("0X")) bi = new java.math.BigInteger(t.substring(2), 16);
            else if (t.startsWith("0b") || t.startsWith("0B")) bi = new java.math.BigInteger(t.substring(2), 2);
            else bi = new java.math.BigInteger(t);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid integer literal '" + t + "' (at line " + line + ")");
        }
        java.math.BigInteger v = neg ? bi.negate() : bi;
        if (cangTarget.equals("byte") && (v.intValue() < -128 || v.intValue() > 127 || v.bitLength() > 7)) {
            throw new RuntimeException("Integer number out of range for 'byte' (-128 to 127): "
                + v + " (at line " + line + ")");
        }
        if (cangTarget.equals("int") && v.compareTo(java.math.BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || cangTarget.equals("int") && v.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new RuntimeException("Integer number out of range for 'int': " + v + " (at line " + line + ")");
        }
        if (cangTarget.equals("long") && (v.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0
                || v.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0)) {
            throw new RuntimeException("Integer number out of range for 'long': " + v + " (at line " + line + ")");
        }
    }

    private void generateVarDecl(VarDecl decl) {
        String cangType = decl.type;
        String llvmType;

        // Same-scope redeclaration (debug.md #11): previously emitted a second alloca and
        // only clang caught "multiple definition". Cross-scope shadowing stays allowed —
        // Block/lambda/for bodies get fresh Scope instances, so this checks OWN scope only.
        if (scope != null && scope.vars.containsKey(decl.name)) {
            throw new RuntimeException("Duplicate variable: '" + decl.name
                + "' is already declared in this scope (at line " + decl.line + ")");
        }

        // Literal range / narrowing check against the declared type (debug.md #8).
        if (!cangType.equals("var") && decl.init != null) {
            checkLiteralFits(decl.init, cangType, decl.line);
        }

        if (decl.isFinal) {
            finalVars.add(decl.name);
        }

        if (cangType.equals("var")) {
            // Type inference: evaluate init first, then infer type
            if (decl.init == null) {
                throw new RuntimeException("var requires initializer at line " + decl.line);
            }
            // null cannot be type-inferred
            if (decl.init instanceof NullLit) {
                throw new RuntimeException("Cannot infer type from null, use explicit type (at line " + decl.line + ")");
            }
            // Array literal: infer element type from first element, check the rest
            if (decl.init instanceof ArrayLit) {
                ArrayLit lit = (ArrayLit) decl.init;
                if (lit.elements.isEmpty()) {
                    throw new RuntimeException(
                        "Cannot infer element type from empty array literal, use explicit type e.g. int[] arr = [] (at line "
                        + decl.line + ")");
                }
                LLVMValue arrVal = generateArrayLit(lit, null, null);
                if (lastArrayElemType != null) {
                    arrayElemTypes.put(decl.name, lastArrayElemType);
                }
                if (lastArrayElemCangType != null) {
                    arrayElemCangTypes.put(decl.name, lastArrayElemCangType);
                }
                String ptrName = "%v." + decl.name + "." + tmpCount++;
                body.append("  ").append(ptrName).append(" = alloca i8*\n");
                scope.define(decl.name, new LLVMValue(ptrName, "i8*", "Array<" + lastArrayElemCangType + ">"));
                registerGcRoot(ptrName, "i8*");
                body.append("  store i8* ").append(arrVal.value).append(", i8** ").append(ptrName).append("\n");
                return;
            }
            LLVMValue val = generateExpr(decl.init);
            llvmType = val.type;
            String ptrName = "%v." + decl.name + "." + tmpCount++;
            body.append("  ").append(ptrName).append(" = alloca ").append(llvmType).append("\n");
            scope.define(decl.name, new LLVMValue(ptrName, llvmType, val.semanticType));
            body.append("  store ").append(llvmType).append(" ").append(val.value)
                 .append(", ").append(llvmType).append("* ").append(ptrName).append("\n");
            // Array aliasing (var b = a): carry element type tracking over
            if (decl.init instanceof Identifier) {
                String elem = arrayElemCangTypes.get(((Identifier) decl.init).name);
                if (elem != null) {
                    trackArrayVar(decl.name, elem);
                }
            } else if (val.semanticType != null && isArraySemanticType(val.semanticType)) {
                // var x = f.readLines(): element type comes from the call's Array<T> semantic type
                trackArrayVar(decl.name, val.semanticType.substring(6, val.semanticType.length() - 1));
            }
            return;
        }

        // Internal array type representation: T[N] / T[] are normalized to Array<T>
        if (cangType.startsWith("Array<") && cangType.endsWith(">")) {
            String elemCangType = cangType.substring(6, cangType.length() - 1);
            String expectedLLVMType = toLLVMType(elemCangType);
            trackArrayVar(decl.name, elemCangType);

            LLVMValue arrVal = null;
            if (decl.arraySize >= 0) {
                // Fixed size: T[N]
                if (decl.init == null) {
                    arrVal = generateFixedArray(decl.arraySize, expectedLLVMType);
                } else if (decl.init instanceof ArrayLit) {
                    ArrayLit lit = (ArrayLit) decl.init;
                    if (lit.elements.isEmpty()) {
                        arrVal = generateFixedArray(decl.arraySize, expectedLLVMType);
                    } else {
                        if (lit.elements.size() != decl.arraySize) {
                            throw new RuntimeException(
                                "Array size mismatch: expected " + decl.arraySize + " elements but found "
                                + lit.elements.size() + " (at line " + decl.line + ")");
                        }
                        arrVal = generateArrayLit(lit, expectedLLVMType, elemCangType);
                    }
                } else {
                    throw new RuntimeException(
                        "Fixed-size array must be initialized with an array literal of exactly "
                        + decl.arraySize + " elements (at line " + decl.line + ")");
                }
            } else if (decl.init instanceof ArrayLit) {
                // Length inferred from literal: T[] = [...]
                arrVal = generateArrayLit((ArrayLit) decl.init, expectedLLVMType, elemCangType);
            } else if (decl.init == null) {
                throw new RuntimeException(
                    "Array '" + decl.name + "' requires an initializer to infer length (at line " + decl.line + ")");
            }

            if (arrVal != null) {
                String ptrName = "%v." + decl.name + "." + tmpCount++;
                body.append("  ").append(ptrName).append(" = alloca i8*\n");
                scope.define(decl.name, new LLVMValue(ptrName, "i8*", "Array<" + elemCangType + ">"));
                registerGcRoot(ptrName, "i8*");
                body.append("  store i8* ").append(arrVal.value).append(", i8** ").append(ptrName).append("\n");
                return;
            }
            // Non-literal initializer (e.g. array aliasing): fall through to common path
        }

        llvmType = toLLVMType(cangType);
        String ptrName = "%v." + decl.name + "." + tmpCount++;

        body.append("  ").append(ptrName).append(" = alloca ").append(llvmType).append("\n");
        // Array<T> decl with a non-literal initializer (method call/alias) must keep its Array<T>
        // semantic type so x.length() dispatches; semanticKindOf returns null for arrays.
        String declSem = semanticKindOf(cangType);
        if (declSem == null && isArraySemanticType(cangType)) declSem = cangType;
        scope.define(decl.name, new LLVMValue(ptrName, llvmType, declSem));
        registerGcRoot(ptrName, llvmType);

        if (decl.init != null) {
            String savedExpected = expectedFunctionType;
            expectedFunctionType = isFunctionType(cangType) ? cangType : null;
            LLVMValue val;
            try {
                val = generateExpr(decl.init);
            } finally {
                expectedFunctionType = savedExpected;
            }
            if ((cangType.equals("str") || cangType.equals("String")) &&
                val.semanticType != null && !cangType.equals(val.semanticType)) {
                throw new RuntimeException("Cannot assign " + val.semanticType + " to " + cangType + " without an explicit conversion (at line " + decl.line + ")");
            }
            if (isFunctionType(cangType)) {
                checkFunctionValue(val, cangType, decl.line);
            }
            if (!assignableTo(llvmType, val)) {
                throw new RuntimeException("Cannot assign " + cangTypeFromLLVMFull(val.type)
                    + " to " + cangType + " (at line " + decl.line + ")");
            }
            String castedInit = castValue(val, llvmType);
            body.append("  store ").append(llvmType).append(" ").append(castedInit)
                 .append(", ").append(llvmType).append("* ").append(ptrName).append("\n");
            // Array aliasing (e.g. Array<int> b = a): carry element type tracking over
            if (decl.init instanceof Identifier) {
                String elem = arrayElemCangTypes.get(((Identifier) decl.init).name);
                if (elem != null) {
                    trackArrayVar(decl.name, elem);
                }
            }
        }
    }

    /**
     * Generate a zero-filled fixed-size array: [i64 length][n * elemType], all elements 0/null.
     */
    private LLVMValue generateFixedArray(int n, String elemType) {
        int elemBits = llvmTypeBits(elemType);
        if (elemBits <= 0) elemBits = 64; // pointers default to 8 bytes
        long elemBytes = (elemBits + 7) / 8;
        long totalSize = 8L + (long) n * elemBytes;
        long dataSize = (long) n * elemBytes;

        String ptr = "%arr.fix." + tmpCount++;
        body.append("  ").append(ptr).append(" = call i8* @").append(allocFn()).append("(i64 ").append(totalSize).append(")\n");
        registerRegionAllocation(ptr);
        body.append("  store i64 ").append(n).append(", i64* ").append(ptr).append("\n");

        String dataPtr = "%arr.fixdata." + tmpCount++;
        body.append("  ").append(dataPtr).append(" = getelementptr i8, i8* ").append(ptr).append(", i64 8\n");
        body.append("  call void @llvm.memset.p0i8.p0i8.i64(i8* ").append(dataPtr)
             .append(", i8 0, i64 ").append(dataSize).append(", i1 false)\n");
        return new LLVMValue(ptr, "i8*");
    }

    /**
     * Check if two LLVM types are compatible (can convert).
     */
    private boolean typesCompatible(String from, String to) {
        if (from.equals(to)) return true;
        // int can convert to long, float, double
        if (from.equals("i32") && (to.equals("i64") || to.equals("float") || to.equals("double"))) return true;
        // long can convert to double
        if (from.equals("i64") && (to.equals("double"))) return true;
        // float can convert to double
        if (from.equals("float") && to.equals("double")) return true;
        // bool can convert to int
        if (from.equals("i1") && (to.equals("i32") || to.equals("i64"))) return true;
        return false;
    }

    /** May `val` be stored into a slot of LLVM type `target`? (debug.md #7/#9)
     *  Same rules as call-argument checks plus loose pointer-to-pointer assignment and
     *  the null literal for pointer/Function slots; rejects kind mismatches like
     *  int <- string or str <- int that previously produced invalid IR. */
    private boolean assignableTo(String target, LLVMValue val) {
        if (val.type.equals(target)) return true;
        if (typesCompatible(val.type, target)) return true;
        if (isNumericLLVM(val.type) && isNumericLLVM(target)) return true;
        if (val.type.endsWith("*") && target.endsWith("*")) return true; // String/str/class refs
        if (val.value != null && val.value.equals("null")) {
            return target.endsWith("*") || target.equals("%CangFunction");
        }
        return false;
    }

    /** Return semantic types that must travel with call results (chaining/assign checks). */
    private boolean callResultCarriesSemantic(String cangType) {
        return isArraySemanticType(cangType) || isFunctionType(cangType)
            || cangType.equals("String") || cangType.equals("str");
    }

    private void generateIf(IfStmt stmt) {
        int id = labelCount++;
        String thenLabel = "if.then." + id;
        String elseLabel = "if.else." + id;
        String endLabel = "if.end." + id;

        LLVMValue cond = generateExpr(stmt.condition);
        String condVar = ensureI1(cond);

        if (stmt.elseBlock != null) {
            body.append("  br i1 ").append(condVar).append(", label %").append(thenLabel)
                 .append(", label %").append(elseLabel).append("\n\n");
        } else {
            body.append("  br i1 ").append(condVar).append(", label %").append(thenLabel)
                 .append(", label %").append(endLabel).append("\n\n");
        }

        // Then block
        body.append(thenLabel).append(":\n");
        int thenStart = body.length();
        generateStmt(stmt.thenBlock);
        emitBranchIfNeeded(endLabel, thenStart);
        body.append("\n");

        // Else block
        if (stmt.elseBlock != null) {
            body.append(elseLabel).append(":\n");
            int elseStart = body.length();
            generateStmt(stmt.elseBlock);
            emitBranchIfNeeded(endLabel, elseStart);
            body.append("\n");
        }

        // End label
        body.append(endLabel).append(":\n");
    }

    /**
     * Generate switch statement as if/else-if chain.
     */
    /** Canonical duplicate-case key for a literal case value; null when not a literal. */
    private String literalCaseKey(AST v) {
        if (v instanceof IntLit) return "i:" + ((IntLit) v).text;
        if (v instanceof LongLit) return "l:" + ((LongLit) v).text;
        if (v instanceof FloatLit) return "f:" + ((FloatLit) v).text;
        if (v instanceof DoubleLit) return "d:" + ((DoubleLit) v).text;
        if (v instanceof BoolLit) return "b:" + ((BoolLit) v).value;
        if (v instanceof StringLit) return "s:" + ((StringLit) v).value;
        if (v instanceof StrLit) return "t:" + ((StrLit) v).value;
        return null;
    }

    private void generateSwitch(SwitchStmt stmt) {
        int id = labelCount++;
        String endLabel = "switch.end." + id;

        // Duplicate case values are a compile error (debug.md #24); literal cases only.
        java.util.Set<String> seenCases = new java.util.HashSet<>();
        for (SwitchCase sc : stmt.cases) {
            String lit = literalCaseKey(sc.value);
            if (lit != null && !seenCases.add(lit)) {
                String shown = lit.length() > 2 ? lit.substring(2) : lit; // strip "i:" etc. prefix
                throw new util.CompileError("Duplicate case value '" + shown + "'",
                    sourceFile, sc.line, 1, readSourceLine(sourceFile, sc.line), 4);
            }
        }

        // Evaluate subject once
        LLVMValue subject = generateExpr(stmt.subject);

        // Jump to first check
        if (stmt.cases.size() > 0) {
            body.append("  br label %switch.check.").append(id).append(".0\n\n");
        } else if (stmt.defaultBody != null) {
            body.append("  br label %switch.default.").append(id).append("\n\n");
        } else {
            body.append("  br label %").append(endLabel).append("\n\n");
        }

        // Generate check chain
        int n = stmt.cases.size();
        for (int i = 0; i < n; i++) {
            SwitchCase sc = stmt.cases.get(i);
            String checkLabel = "switch.check." + id + "." + i;
            String caseLabel = "switch.case." + id + "." + i;

            // Emit check label
            body.append(checkLabel).append(":\n");

            // Compare subject with case value (error location = the case line, not the switch line)
            LLVMValue caseVal = generateExpr(sc.value);
            LLVMValue cmp = generateComparison(subject, "==", caseVal, sc.line);
            String cmpVar = ensureI1(cmp);

            // Determine else target
            String elseTarget;
            if (i < n - 1) {
                elseTarget = "switch.check." + id + "." + (i + 1);
            } else if (stmt.defaultBody != null) {
                elseTarget = "switch.default." + id;
            } else {
                elseTarget = endLabel;
            }

            body.append("  br i1 ").append(cmpVar).append(", label %").append(caseLabel)
                 .append(", label %").append(elseTarget).append("\n\n");

            // Case body
            body.append(caseLabel).append(":\n");
            int caseStart = body.length();
            for (AST s : sc.body) {
                generateStmt(s);
            }
            emitBranchIfNeeded(endLabel, caseStart);
            body.append("\n");
        }

        // Default body
        if (stmt.defaultBody != null) {
            body.append("switch.default.").append(id).append(":\n");
            int defStart = body.length();
            for (AST s : stmt.defaultBody) {
                generateStmt(s);
            }
            emitBranchIfNeeded(endLabel, defStart);
            body.append("\n");
        }

        // End label
        body.append(endLabel).append(":\n");
    }

    private void emitBranchIfNeeded(String targetLabel, int sinceOffset) {
        String recent = body.substring(sinceOffset).trim();
        // Terminator detection looks at the LAST line only: a trailing label opens a fresh
        // block (needs the branch), while any terminator (ret/br/switch/unreachable — even to
        // a different label, e.g. `break`) already closes the current block.
        String lastLine = recent.contains("\n") ? recent.substring(recent.lastIndexOf('\n') + 1) : recent;
        boolean hasTerminator = lastLine.startsWith("ret") || lastLine.startsWith("br ")
            || lastLine.startsWith("switch") || lastLine.startsWith("unreachable")
            || lastLine.startsWith("indirectbr");
        if (!hasTerminator) {
            body.append("  br label %").append(targetLabel).append("\n");
        }
    }

    private void generateWhile(WhileStmt stmt) {
        int id = labelCount++;
        String condLabel = "while.cond." + id;
        String bodyLabel = "while.body." + id;
        String cleanupLabel = "while.cleanup." + id;
        String endLabel = "while.end." + id;

        loopStack.add(new LoopContext());
        loopStack.get(loopStack.size() - 1).breakLabel = endLabel;
        loopStack.get(loopStack.size() - 1).continueLabel = cleanupLabel;

        // Reclaim per-iteration allocas (debug.md #5): save SP before the loop; restore on
        // every back-edge/continue (cleanup) and at the exit (covers break out of the body).
        String stackSave = emitStackSave();

        body.append("  br label %").append(condLabel).append("\n\n");

        body.append(condLabel).append(":\n");
        LLVMValue cond = generateExpr(stmt.condition);
        String condVar = ensureI1(cond);
        body.append("  br i1 ").append(condVar).append(", label %").append(bodyLabel)
             .append(", label %").append(endLabel).append("\n\n");

        body.append(bodyLabel).append(":\n");
        int whileBodyStart = body.length();
        generateStmt(stmt.body);
        emitBranchIfNeeded(cleanupLabel, whileBodyStart);
        body.append("\n");

        body.append(cleanupLabel).append(":\n");
        emitStackRestore(stackSave);
        body.append("  br label %").append(condLabel).append("\n\n");

        body.append(endLabel).append(":\n");
        emitStackRestore(stackSave);

        loopStack.remove(loopStack.size() - 1);
    }

    private void generateFor(ForStmt stmt) {
        int id = labelCount++;
        String condLabel = "for.cond." + id;
        String bodyLabel = "for.body." + id;
        String cleanupLabel = "for.cleanup." + id;
        String updateLabel = "for.update." + id;
        String endLabel = "for.end." + id;

        loopStack.add(new LoopContext());
        loopStack.get(loopStack.size() - 1).breakLabel = endLabel;
        loopStack.get(loopStack.size() - 1).continueLabel = cleanupLabel;

        // Loop-local scope: `for (int i ...)` declares i only for this loop, so two
        // sequential for-inits are not same-scope duplicates (debug.md #11 + shadowing).
        Scope prevForScope = scope;
        scope = new Scope(prevForScope);

        // Init
        if (stmt.init != null) {
            if (stmt.init instanceof VarDecl) {
                generateVarDecl((VarDecl) stmt.init);
            } else {
                generateExpr(stmt.init);
            }
        }

        // Save AFTER init so the init variable (e.g. `for (int i = 0; ...)`) survives restores.
        String stackSave = emitStackSave();

        body.append("  br label %").append(condLabel).append("\n\n");

        // Condition
        body.append(condLabel).append(":\n");
        if (stmt.condition != null) {
            LLVMValue cond = generateExpr(stmt.condition);
            String condVar = ensureI1(cond);
            body.append("  br i1 ").append(condVar).append(", label %").append(bodyLabel)
                 .append(", label %").append(endLabel).append("\n\n");
        } else {
            body.append("  br label %").append(bodyLabel).append("\n\n");
        }

        // Body
        body.append(bodyLabel).append(":\n");
        int forBodyStart = body.length();
        generateStmt(stmt.body);
        emitBranchIfNeeded(cleanupLabel, forBodyStart);
        body.append("\n");

        // Cleanup: reclaim this iteration's allocas before continue/update (debug.md #5)
        body.append(cleanupLabel).append(":\n");
        emitStackRestore(stackSave);
        body.append("  br label %").append(updateLabel).append("\n\n");

        // Update
        body.append(updateLabel).append(":\n");
        if (stmt.update != null) {
            generateExpr(stmt.update);
        }
        body.append("  br label %").append(condLabel).append("\n\n");

        // End
        body.append(endLabel).append(":\n");
        emitStackRestore(stackSave);

        loopStack.remove(loopStack.size() - 1);
        scope = prevForScope;
    }

    /**
     * Generate for-each: for(var i : arr)
     * Equivalent to: for(int idx=0; idx<len; idx++) { var i = arr[idx]; ... }
     */
    private void generateListForEach(ForEachStmt stmt, LLVMValue list, String elemCang) {
        String elemLLVM = toLLVMType(elemCang);
        // Function symbols use the FULL class name (namespace-qualified): semanticType is the
        // short "List_int" key while collectFunction registers "cang_lang_List_int.get".
        ClassInfo listCi = classes.get(list.semanticType);
        String cls = listCi != null ? listCi.fullName : list.semanticType;
        int id = labelCount++;
        String cond = "list.foreach.cond." + id;
        String bodyLabel = "list.foreach.body." + id;
        String cleanup = "list.foreach.cleanup." + id;
        String update = "list.foreach.update." + id;
        String end = "list.foreach.end." + id;
        String indexPtr = "%list.foreach.indexptr." + tmpCount++;
        String varPtr = "%list.foreach.var." + id;

        // break/continue support + per-iteration stack reclaim (debug.md #5)
        loopStack.add(new LoopContext());
        loopStack.get(loopStack.size() - 1).breakLabel = end;
        loopStack.get(loopStack.size() - 1).continueLabel = cleanup;

        // Loop-local scope for the iteration variable (debug.md #11 + shadowing).
        Scope prevLfScope = scope;
        scope = new Scope(prevLfScope);

        // Method-based iteration over the pure-Cang List (no %CangList ABI): snapshot size once.
        String recv = list.type + " " + list.value;
        String sizeRes = "%list.foreach.size." + tmpCount++;
        body.append("  ").append(sizeRes).append(" = call i32 @").append(cls).append(".size(").append(recv).append(")\n");

        // Invariant allocas BEFORE stacksave so they survive per-iteration restores.
        body.append("  ").append(indexPtr).append(" = alloca i32\n  store i32 0, i32* ").append(indexPtr).append("\n");
        body.append("  ").append(varPtr).append(" = alloca ").append(elemLLVM).append("\n");
        String stackSave = emitStackSave();
        body.append("  br label %").append(cond).append("\n").append(cond).append(":\n");
        String index = "%list.foreach.index." + tmpCount++;
        String cmp = "%list.foreach.cmp." + tmpCount++;
        body.append("  ").append(index).append(" = load i32, i32* ").append(indexPtr).append("\n");
        body.append("  ").append(cmp).append(" = icmp slt i32 ").append(index).append(", ").append(sizeRes).append("\n");
        body.append("  br i1 ").append(cmp).append(", label %").append(bodyLabel).append(", label %").append(end).append("\n").append(bodyLabel).append(":\n");
        String value = "%list.foreach.value." + tmpCount++;
        body.append("  ").append(value).append(" = call ").append(elemLLVM).append(" @").append(cls)
             .append(".get(").append(recv).append(", i32 ").append(index).append(")\n");
        body.append("  store ").append(elemLLVM).append(" ").append(value).append(", ").append(elemLLVM).append("* ").append(varPtr).append("\n");
        scope.define(stmt.varName, new LLVMValue(varPtr, elemLLVM, elemCang));
        int start = body.length();
        generateStmt(stmt.body);
        emitBranchIfNeeded(cleanup, start);
        body.append(cleanup).append(":\n");
        emitStackRestore(stackSave);
        body.append("  br label %").append(update).append("\n");
        body.append(update).append(":\n");
        String next = "%list.foreach.next." + tmpCount++;
        body.append("  ").append(next).append(" = add i32 ").append(index).append(", 1\n  store i32 ").append(next).append(", i32* ").append(indexPtr).append("\n  br label %").append(cond).append("\n");
        body.append(end).append(":\n");
        emitStackRestore(stackSave);

        loopStack.remove(loopStack.size() - 1);
        scope = prevLfScope;
    }

    private void generateForEach(ForEachStmt stmt) {
        LLVMValue iterable = generateExpr(stmt.iterable);
        if (iterable.semanticType != null && isListType(iterable.semanticType)) {
            generateListForEach(stmt, iterable, listElementType(iterable.semanticType));
            return;
        }
        int id = labelCount++;
        String condLabel = "foreach.cond." + id;
        String bodyLabel = "foreach.body." + id;
        String cleanupLabel = "foreach.cleanup." + id;
        String updateLabel = "foreach.update." + id;
        String endLabel = "foreach.end." + id;

        loopStack.add(new LoopContext());
        loopStack.get(loopStack.size() - 1).breakLabel = endLabel;
        loopStack.get(loopStack.size() - 1).continueLabel = cleanupLabel;

        // Loop-local scope for the iteration variable (debug.md #11 + shadowing).
        Scope prevFeScope = scope;
        scope = new Scope(prevFeScope);

        // Evaluate iterable (get array pointer)
        LLVMValue arrVal = iterable; // reuse: iterable was generated once (debug.md #16)

        // Load array length
        String lenVar = "%foreach.len." + tmpCount++;
        body.append("  ").append(lenVar).append(" = load i64, i64* ").append(arrVal.value).append("\n");

        // Initialize index
        String idxVar = "%v.foreach.idx." + id;
        body.append("  ").append(idxVar).append(" = alloca i64\n");
        body.append("  store i64 0, i64* ").append(idxVar).append("\n");

        // Allocate loop variable (id suffix: same var name in two loops must not collide in LLVM)
        String varPtr = "%v." + stmt.varName + "." + id;
        String varLLVMType;
        String realElemCang = forEachElemCang(iterable, stmt.iterable);
        if (stmt.varType.equals("var")) {
            // Infer from iterable element type (call-return semantic first, then AST tracking)
            if (realElemCang != null) {
                varLLVMType = toLLVMType(realElemCang);
            } else {
                String tracked = null;
                if (stmt.iterable instanceof Identifier) {
                    tracked = arrayElemTypes.get(((Identifier) stmt.iterable).name);
                } else if (isSystemArgs(stmt.iterable)) {
                    tracked = "i8*"; // ARGS elements are char*
                }
                varLLVMType = tracked != null ? tracked : "i32";
            }
        } else {
            varLLVMType = toLLVMType(stmt.varType);
            // Declared element type vs real array element type. Same LLVM type passes; two
            // pointer types pass (reference family, same width — e.g. Object over String[]);
            // anything else is refused: scalar/pointer mixes once silently read garbage
            // (Object over int[] loaded 8 bytes from 4-byte slots), and mixed-width scalars
            // (long over int[]) would read past the slot.
            if (realElemCang != null) {
                String want = toLLVMType(stmt.varType);
                String have = toLLVMType(realElemCang);
                boolean compatible = want.equals(have) || (want.endsWith("*") && have.endsWith("*"));
                if (!compatible) {
                    throw new RuntimeException("for-each element type '" + stmt.varType
                        + "' does not match array element type '" + realElemCang
                        + "' (at line " + stmt.line + ")");
                }
            }
        }
        body.append("  ").append(varPtr).append(" = alloca ").append(varLLVMType).append("\n");
        registerGcRoot(varPtr, varLLVMType);

        // Save AFTER the invariant allocas (idx/var) so they survive per-iteration restores.
        String stackSave = emitStackSave();

        // Determine element type from tracked info
        String elemType = varLLVMType;

        // Jump to condition
        body.append("  br label %").append(condLabel).append("\n\n");

        // Condition: idx < len
        body.append(condLabel).append(":\n");
        String curIdx = "%foreach.cidx." + tmpCount++;
        body.append("  ").append(curIdx).append(" = load i64, i64* ").append(idxVar).append("\n");
        String cmp = "%foreach.cmp." + tmpCount++;
        body.append("  ").append(cmp).append(" = icmp slt i64 ").append(curIdx).append(", ").append(lenVar).append("\n");
        body.append("  br i1 ").append(cmp).append(", label %").append(bodyLabel)
             .append(", label %").append(endLabel).append("\n\n");

        // Body
        body.append(bodyLabel).append(":\n");
        // Load element: skip 8-byte header + idx * elemSize
        String dataPtr = "%foreach.data." + tmpCount++;
        body.append("  ").append(dataPtr).append(" = getelementptr i8, i8* ").append(arrVal.value).append(", i64 8\n");
        String typedPtr = "%foreach.typed." + tmpCount++;
        body.append("  ").append(typedPtr).append(" = bitcast i8* ").append(dataPtr).append(" to ").append(elemType).append("*\n");
        String elemPtr = "%foreach.elem." + tmpCount++;
        body.append("  ").append(elemPtr).append(" = getelementptr ").append(elemType).append(", ")
             .append(elemType).append("* ").append(typedPtr).append(", i64 ").append(curIdx).append("\n");
        String elemVal = "%foreach.val." + tmpCount++;
        body.append("  ").append(elemVal).append(" = load ").append(elemType).append(", ")
             .append(elemType).append("* ").append(elemPtr).append("\n");
        // Store to loop variable
        body.append("  store ").append(varLLVMType).append(" ").append(elemVal)
             .append(", ").append(varLLVMType).append("* ").append(varPtr).append("\n");
        // Define loop variable in scope
        scope.define(stmt.varName, new LLVMValue(varPtr, varLLVMType));
        // Register element type tracking so loopVar[j] resolves correctly
        if (stmt.varType.startsWith("Array<") && stmt.varType.endsWith(">")) {
            trackArrayVar(stmt.varName, stmt.varType.substring(6, stmt.varType.length() - 1));
        } else if (stmt.varType.equals("var")) {
            String iterableElemCang = elemCangOf(stmt.iterable);
            String inner = innerElemCang(iterableElemCang);
            if (inner != null) {
                trackArrayVar(stmt.varName, inner);
            }
        }

        int bodyStart = body.length();
        generateStmt(stmt.body);
        emitBranchIfNeeded(cleanupLabel, bodyStart);
        body.append("\n");

        // Cleanup: reclaim this iteration's allocas before continue/update (debug.md #5)
        body.append(cleanupLabel).append(":\n");
        emitStackRestore(stackSave);
        body.append("  br label %").append(updateLabel).append("\n\n");

        // Update: idx++
        body.append(updateLabel).append(":\n");
        String nextIdx = "%foreach.next." + tmpCount++;
        body.append("  ").append(nextIdx).append(" = add i64 ").append(curIdx).append(", 1\n");
        body.append("  store i64 ").append(nextIdx).append(", i64* ").append(idxVar).append("\n");
        body.append("  br label %").append(condLabel).append("\n\n");

        // End
        body.append(endLabel).append(":\n");
        emitStackRestore(stackSave);

        loopStack.remove(loopStack.size() - 1);
        scope = prevFeScope;
    }

    private void generateReturn(ReturnStmt stmt) {
        abandonRegion();
        String retType = findCurrentReturnType();

        if (stmt.value != null) {
            String savedExpected = expectedFunctionType;
            expectedFunctionType = isFunctionType(retType) ? retType : null;
            LLVMValue val;
            try {
                val = generateExpr(stmt.value);
            } finally {
                expectedFunctionType = savedExpected;
            }

            // Type check: return value must match declared return type
            if (isFunctionType(retType)) {
                checkFunctionValue(val, retType, stmt.line);
            } else if (!retType.equals("void")) {
                String expectedLLVM = toLLVMType(retType);
                // bool → integral is allowed for call arguments, but returning `true`/a
                // comparison into int is a Java error too (debug.md #12).
                if (val.type.equals("i1") && !expectedLLVM.equals("i1")) {
                    throw new RuntimeException(
                        "Return type mismatch: expected '" + retType + "' but found 'bool' (at line " + stmt.line + ")");
                }
                if (!typesCompatible(val.type, expectedLLVM)) {
                    String actualCang = val.value != null && val.value.equals("null")
                        ? "null" : cangTypeFromLLVM(val.type);
                    throw new RuntimeException(
                        "Return type mismatch: expected '" + retType +
                        "' but found '" + actualCang + "' (at line " + stmt.line + ")");
                }
            }

            // Inline enclosing finally layers AFTER the value is computed (Java evaluates the
            // return expression first) and BEFORE the jump (debug.md #13).
            boolean hadFinally = !finallyStack.isEmpty();
            if (inlineFinallyLayers()) {
                // finally returned/threw: the return itself is swallowed
                if (hadFinally) body.append("jexit.dead.").append(labelCount++).append(":\n");
                return;
            }
            emitGcFramePop();
            String castedRet = castValue(val, toLLVMType(retType));
            body.append("  ret ").append(toLLVMType(retType)).append(" ")
                 .append(castedRet).append("\n");
            if (hadFinally) body.append("jexit.dead.").append(labelCount++).append(":\n");
        } else {
            // return; with no value 鈥?only valid for void functions
            if (!retType.equals("void")) {
                throw new RuntimeException(
                    "Function must return a value of type '" + retType + "' (at line " + stmt.line + ")");
            }
            boolean hadFinally = !finallyStack.isEmpty();
            if (inlineFinallyLayers()) {
                if (hadFinally) body.append("jexit.dead.").append(labelCount++).append(":\n");
                return;
            }
            emitGcFramePop();
            body.append("  ret void\n");
            if (hadFinally) body.append("jexit.dead.").append(labelCount++).append(":\n");
        }
    }

    private String findCurrentReturnType() {
        return currentFuncReturnType;
    }

    // ==================== Expression generation ====================

    /** LLVM floating constants must contain a '.' — `1e10` parses as an integer to LLVM and
     *  fails (debug.md #25). Java's toString always emits the dot (`1.0E10`). */
    private String normalizeFloatText(String text, boolean isDouble) {
        try {
            return isDouble
                ? Double.toString(Double.parseDouble(text))
                : Float.toString(Float.parseFloat(text));
        } catch (NumberFormatException e) {
            return text;
        }
    }

    private LLVMValue generateExpr(AST node) {
        if (node instanceof IntLit) {
            String val = ((IntLit) node).text;
            long n;
            if (val.startsWith("0x") || val.startsWith("0X")) {
                n = Long.parseLong(val.substring(2), 16);
            } else if (val.startsWith("0b") || val.startsWith("0B")) {
                n = Long.parseLong(val.substring(2), 2);
            } else {
                // Default width by magnitude (debug.md #8): fits i32 -> i32, else i64.
                // Beyond 64 bits there is nowhere to wrap silently — compile error.
                java.math.BigInteger bi = new java.math.BigInteger(val);
                if (bi.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                    throw new RuntimeException("Integer number too large: " + val + " (at line " + node.line + ")");
                }
                if (bi.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
                    throw new RuntimeException("Integer number too small: " + val + " (at line " + node.line + ")");
                }
                n = bi.longValue();
            }
            if (n > Integer.MAX_VALUE || n < Integer.MIN_VALUE) {
                return new LLVMValue(String.valueOf(n), "i64");
            }
            return new LLVMValue(String.valueOf(n), "i32");
        }
        if (node instanceof LongLit) {
            return new LLVMValue(((LongLit) node).text, "i64");
        }
        if (node instanceof FloatLit) {
            // Convert float literal to double for LLVM (float is promoted in printf)
            String text = ((FloatLit) node).text;
            return new LLVMValue(normalizeFloatText(text, false), "float");
        }
        if (node instanceof DoubleLit) {
            return new LLVMValue(normalizeFloatText(((DoubleLit) node).text, true), "double");
        }
        if (node instanceof BoolLit) {
            return new LLVMValue(((BoolLit) node).value ? "1" : "0", "i1");
        }
        if (node instanceof StringLit) {
            LLVMValue value = generateStringLit((StringLit) node);
            value.semanticType = "String";
            return value;
        }
        if (node instanceof StrLit) {
            LLVMValue value = generateStringLit(new StringLit(((StrLit) node).value, node.line));
            value.semanticType = "str";
            return value;
        }
        if (node instanceof NullLit) {
            return new LLVMValue("null", "i8*");
        }
        if (node instanceof ThisExpr) {
            LLVMValue thisVal = scope.lookup("this");
            return new LLVMValue(thisVal.value, thisVal.type);
        }
        if (node instanceof Identifier) {
            return generateIdentifier((Identifier) node);
        }
        if (node instanceof FunctionRefExpr) {
            return generateFunctionRef((FunctionRefExpr) node);
        }
        if (node instanceof LambdaExpr) {
            return generateLambda((LambdaExpr) node);
        }
        if (node instanceof ThreadBlockExpr) {
            return generateThreadBlockExpr((ThreadBlockExpr) node);
        }
        if (node instanceof BinaryExpr) {
            return generateBinary((BinaryExpr) node);
        }
        if (node instanceof UnaryExpr) {
            return generateUnary((UnaryExpr) node);
        }
        if (node instanceof AssignExpr) {
            return generateAssign((AssignExpr) node);
        }
        if (node instanceof TernaryExpr) {
            return generateTernary((TernaryExpr) node);
        }
        if (node instanceof LikeExpr) {
            return generateLike((LikeExpr) node);
        }
        if (node instanceof TypeCastExpr) {
            return generateTypeCast((TypeCastExpr) node);
        }
        if (node instanceof MethodCallExpr) {
            return generateMethodCall((MethodCallExpr) node);
        }
        if (node instanceof FieldAccessExpr) {
            return generateFieldAccess((FieldAccessExpr) node);
        }
        if (node instanceof NewExpr) {
            return generateNew((NewExpr) node);
        }
        if (node instanceof NewArrayExpr) {
            return generateNewArray((NewArrayExpr) node);
        }
        if (node instanceof ArrayLit) {
            return generateArrayLit((ArrayLit) node);
        }
        if (node instanceof ArrayAccessExpr) {
            return generateArrayAccess((ArrayAccessExpr) node);
        }

        throw new RuntimeException("Unknown expression type: " + node.getClass().getSimpleName());
    }

    private LLVMValue generateStringLit(StringLit node) {
        String text = node.value;
        String key = text;
        if (!stringLiterals.containsKey(key)) {
            String name = "@.str." + strCount++;
            int byteCount = text.length() + 1; // +1 for null terminator
            // Build escaped string
            StringBuilder escaped = new StringBuilder();
            for (char c : text.toCharArray()) {
                if (c == '\\') escaped.append("\\5C");
                else if (c == '\n') escaped.append("\\0A");
                else if (c == '\t') escaped.append("\\09");
                else if (c == '\0') escaped.append("\\00");
                else if (c == '"') escaped.append("\\22");
                else if (c >= 32 && c < 127) escaped.append(c);
                else escaped.append(String.format("\\%02X", (int) c));
            }
            header.append(name).append(" = private unnamed_addr constant [").append(byteCount)
                  .append(" x i8] c\"").append(escaped).append("\\00\"\n");
            stringLiterals.put(key, name);
        }
        String globalName = stringLiterals.get(key);
        String ptr = "%str." + tmpCount++;
        body.append("  ").append(ptr).append(" = getelementptr [")
            .append(text.length() + 1).append(" x i8], [")
            .append(text.length() + 1).append(" x i8]* ")
            .append(globalName).append(", i32 0, i32 0\n");
        return new LLVMValue(ptr, "i8*");
    }

    /**
     * Generate a no-capture lambda as a standalone LLVM function plus a Function pair value.
     * The expected Function<Void, T> signature provides the untyped lambda parameter's type.
     */
    private LLVMValue generateLambda(LambdaExpr node) {
        String expected = expectedFunctionType;
        if (expected == null || !isFunctionType(expected)) {
            // The parameter type comes from the expectation too, so there is nothing to build
            // from. When the return type was omitted, point at writing it (inference rule:
            // an expected Function<...> is required to omit the return type).
            String hint = node.returnType == null
                ? "Cannot infer lambda return type here - write it explicitly (e.g. '-> int { ... }') and use the lambda where Function<...> is expected"
                : "Cannot infer lambda type; use it where Function<...> is expected";
            throw new RuntimeException(hint + " (at line " + node.line + ")");
        }
        String[] parts = functionTypeParts(expected);
        int nParams = node.parameters.size();
        if (parts.length != nParams + 1) {
            throw new RuntimeException("Lambda has " + nParams + " parameter(s) but expected "
                + expected + " (" + (parts.length - 1) + " expected) (at line " + node.line + ")");
        }
        // Return type: explicit annotation must match the expectation; omitted inherits it.
        String expRetRaw = parts[0];
        boolean expVoid = expRetRaw.equals("void") || expRetRaw.equals("Void");
        String retCang;
        if (node.returnType != null) {
            boolean annVoid = node.returnType.equals("void");
            if (annVoid != expVoid || (!annVoid && !node.returnType.equals(expRetRaw))) {
                throw new RuntimeException("Lambda return type '" + node.returnType
                    + "' does not match expected " + expected + " (at line " + node.line + ")");
            }
        }
        retCang = expVoid ? "void" : expRetRaw;
        // By-value closure capture: resolve free variables against the ENCLOSING scope BEFORE
        // the scope swap. Values are snapshotted into a heap env at creation (Java semantics:
        // later mutations of the outer variable are not visible inside the lambda).
        List<String> captures = collectLambdaCaptures(node);
        List<LLVMValue> captureVals = new ArrayList<>();
        for (String capName : captures) captureVals.add(scope.lookup(capName));

        String[] paramCangs = new String[nParams];
        String[] paramLLVMs = new String[nParams];
        StringBuilder paramList = new StringBuilder();
        for (int i = 0; i < nParams; i++) {
            paramCangs[i] = parts[i + 1];
            paramLLVMs[i] = toLLVMType(paramCangs[i]);
            if (i > 0) paramList.append(", ");
            paramList.append(paramLLVMs[i]);
        }
        int lambdaId = lambdaCount++;
        String fnName = "@cang.lambda." + lambdaId;
        String envType = "%cang.env." + lambdaId;
        String retLLVM = toLLVMType(retCang);
        String codeType = retLLVM + " (i8*" + (nParams > 0 ? ", " + paramList : "") + ")*";

        Scope savedScope = scope;
        String savedReturn = currentFuncReturnType;
        List<LoopContext> savedLoops = new ArrayList<>(loopStack);
        // Freed-records are per-lambda too (debug.md #19): a free inside the lambda body must
        // not leak into the enclosing function's same-named variables.
        java.util.Set<String> savedFreed = new java.util.HashSet<>(freedVars);
        freedVars.clear();
        loopStack.clear();
        // A lambda is a separate LLVM function; enclosing handlers cannot be branched to.
        Deque<String> savedHandlers = new ArrayDeque<>(exceptionHandlers);
        exceptionHandlers.clear();
        scope = new Scope(null);
        currentFuncReturnType = retCang;

        int pid = tmpCount++;
        int start = body.length();
        StringBuilder def = new StringBuilder();
        def.append("define ").append(retLLVM).append(" ").append(fnName).append("(i8* %env");
        String[] argNames = new String[nParams];
        for (int i = 0; i < nParams; i++) {
            argNames[i] = "%lparg." + pid + "." + i;
            def.append(", ").append(paramLLVMs[i]).append(" ").append(argNames[i]);
        }
        def.append(") {\nentry:\n");
        body.append(def);
        for (int i = 0; i < nParams; i++) {
            String allocaName = "%lp." + pid + "." + i;
            body.append("  ").append(allocaName).append(" = alloca ").append(paramLLVMs[i]).append("\n");
            body.append("  store ").append(paramLLVMs[i]).append(" ").append(argNames[i])
                 .append(", ").append(paramLLVMs[i]).append("* ").append(allocaName).append("\n");
            scope.define(node.parameters.get(i), new LLVMValue(allocaName, paramLLVMs[i], paramCangs[i]));
        }

        // Unpack env slots into lambda-local scope entries. Shape-compatible with regular
        // declarations (alloca + value type) so generateIdentifier works unchanged; `this`
        // keeps its raw-pointer shape (matches generateExprForPtr(ThisExpr)).
        if (!captures.isEmpty()) {
            StringBuilder shape = new StringBuilder();
            for (LLVMValue v : captureVals) {
                if (shape.length() > 0) shape.append(", ");
                shape.append(v.type);
            }
            header.append(envType).append(" = type { ").append(shape).append(" }\n");
            String envCast = "%env.c." + tmpCount++;
            body.append("  ").append(envCast).append(" = bitcast i8* %env to ").append(envType).append("*\n");
            for (int i = 0; i < captures.size(); i++) {
                LLVMValue v = captureVals.get(i);
                String slot = "%cap.slot." + tmpCount++;
                body.append("  ").append(slot).append(" = getelementptr ").append(envType).append(", ")
                     .append(envType).append("* ").append(envCast).append(", i32 0, i32 ").append(i).append("\n");
                String loaded = "%cap.v." + tmpCount++;
                body.append("  ").append(loaded).append(" = load ").append(v.type).append(", ")
                     .append(v.type).append("* ").append(slot).append("\n");
                if (captures.get(i).equals("this")) {
                    scope.define("this", new LLVMValue(loaded, v.type, v.semanticType));
                } else {
                    String capAlloca = "%cap.a." + tmpCount++;
                    body.append("  ").append(capAlloca).append(" = alloca ").append(v.type).append("\n");
                    body.append("  store ").append(v.type).append(" ").append(loaded).append(", ")
                         .append(v.type).append("* ").append(capAlloca).append("\n");
                    scope.define(captures.get(i), new LLVMValue(capAlloca, v.type, v.semanticType));
                }
            }
        }

        generateBlockBody((Block) node.body);

        String trimmed = body.substring(start).trim();
        int lastBreak = trimmed.lastIndexOf('\n');
        String lastLine = (lastBreak >= 0 ? trimmed.substring(lastBreak + 1) : trimmed).trim();
        boolean terminated = lastLine.startsWith("ret ") || lastLine.startsWith("br ") || lastLine.equals("unreachable");
        if (retCang.equals("void")) {
            if (!terminated) body.append("  ret void\n");
        } else if (!alwaysReturns(node.body)) {
            throw new RuntimeException("Lambda must return a value of type '" + retCang
                + "' on every path (at line " + node.line + ")");
        } else if (!terminated) {
            // All paths already returned (e.g. if/else with returns) but the final block is
            // open — keep it well-formed for LLVM.
            body.append("  unreachable\n");
        }
        body.append("}\n\n");
        int end = body.length();
        extraDefs.append(body, start, end);
        body.delete(start, end);

        scope = savedScope;
        currentFuncReturnType = savedReturn;
        loopStack.clear();
        loopStack.addAll(savedLoops);
        exceptionHandlers.clear();
        exceptionHandlers.addAll(savedHandlers);
        freedVars.clear();
        freedVars.addAll(savedFreed);

        // Creation site (outer function): snapshot the captured values into a heap env struct.
        String envRaw = "null";
        if (!captures.isEmpty()) {
            String sizePtr = "%env.gep." + tmpCount++;
            body.append("  ").append(sizePtr).append(" = getelementptr ").append(envType).append(", ")
                 .append(envType).append("* null, i32 1\n");
            String size = "%env.sz." + tmpCount++;
            body.append("  ").append(size).append(" = ptrtoint ").append(envType).append("* ")
                 .append(sizePtr).append(" to i64\n");
            String alloc = "%env.alloc." + tmpCount++;
            body.append("  ").append(alloc).append(" = call i8* @").append(allocFn())
                 .append("(i64 ").append(size).append(")\n");
            registerRegionAllocation(alloc);
            String envP = "%env.p." + tmpCount++;
            body.append("  ").append(envP).append(" = bitcast i8* ").append(alloc).append(" to ")
                 .append(envType).append("*\n");
            for (int i = 0; i < captures.size(); i++) {
                LLVMValue v = captureVals.get(i);
                String src;
                if (captures.get(i).equals("this")) {
                    // `this` is a raw SSA parameter in scope, not an alloca — use the value
                    // itself (loading through it would dereference the object header).
                    src = v.value;
                } else {
                    src = "%cap.src." + tmpCount++;
                    body.append("  ").append(src).append(" = load ").append(v.type).append(", ")
                         .append(v.type).append("* ").append(v.value).append("\n");
                }
                String slot = "%env.slot." + tmpCount++;
                body.append("  ").append(slot).append(" = getelementptr ").append(envType).append(", ")
                     .append(envType).append("* ").append(envP).append(", i32 0, i32 ").append(i).append("\n");
                body.append("  store ").append(v.type).append(" ").append(src).append(", ")
                     .append(v.type).append("* ").append(slot).append("\n");
            }
            envRaw = alloc;
        }

        String code = "%fn.code." + tmpCount++;
        body.append("  ").append(code).append(" = bitcast ").append(codeType).append(" ")
             .append(fnName).append(" to i8*\n");
        String v0 = "%fn.v0." + tmpCount++;
        body.append("  ").append(v0).append(" = insertvalue %CangFunction undef, i8* ").append(code).append(", 0\n");
        String v1 = "%fn.v1." + tmpCount++;
        body.append("  ").append(v1).append(" = insertvalue %CangFunction ").append(v0)
             .append(", i8* ").append(envRaw).append(", 1\n");
        return new LLVMValue(v1, "%CangFunction", expected);
    }

    private void validateLambdaCapture(AST node, java.util.Set<String> bound, int line, String context) {
        if (node == null) return;
        if (node instanceof LambdaExpr) {
            java.util.Set<String> inner = new java.util.HashSet<>(bound);
            inner.addAll(((LambdaExpr) node).parameters);
            validateLambdaCapture(((LambdaExpr) node).body, inner, line, context);
            return;
        }
        if (node instanceof Identifier) {
            String name = ((Identifier) node).name;
            if (!bound.contains(name) && scope.lookup(name) != null) {
                throw new RuntimeException(context + " cannot capture external variable '" + name + "' (at line " + line + ")");
            }
            return;
        }
        if (node instanceof ThisExpr) {
            if (scope.lookup("this") != null) {
                throw new RuntimeException(context + " cannot capture 'this'; use this::method instead (at line " + line + ")");
            }
            return;
        }
        for (java.lang.reflect.Field field : node.getClass().getFields()) {
            Object value;
            try {
                value = field.get(node);
            } catch (Exception e) {
                continue;
            }
            if (value instanceof AST) {
                validateLambdaCapture((AST) value, bound, line, context);
            } else if (value instanceof List) {
                for (Object item : (List) value) {
                    if (item instanceof AST) validateLambdaCapture((AST) item, bound, line, context);
                }
            }
        }
    }

    /** Free variables of a lambda: referenced identifiers (plus `this`) that resolve in the
     *  enclosing scope and are not declared/shadowed inside the lambda. */
    private List<String> collectLambdaCaptures(LambdaExpr node) {
        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        collectDeclaredNames(node.body, declared);
        declared.addAll(node.parameters);
        List<String> refs = new ArrayList<>();
        collectFreeRefs(node.body, declared, refs);
        List<String> caps = new ArrayList<>();
        boolean thisRef = false;
        for (String n : refs) {
            if (n.equals("this")) { thisRef = true; continue; }
            if (!caps.contains(n) && scope.lookup(n) != null) caps.add(n);
        }
        if (thisRef && scope.lookup("this") != null) caps.add("this");
        return caps;
    }

    /** All names declared inside a subtree: locals, for-each vars, catch params, lambda params. */
    private void collectDeclaredNames(AST node, java.util.Set<String> out) {
        if (node == null) return;
        if (node instanceof VarDecl) out.add(((VarDecl) node).name);
        else if (node instanceof ForEachStmt) out.add(((ForEachStmt) node).varName);
        else if (node instanceof LambdaExpr) out.addAll(((LambdaExpr) node).parameters);
        else if (node instanceof CatchClause) out.add(((CatchClause) node).name);
        for (java.lang.reflect.Field f : node.getClass().getFields()) {
            try {
                Object v = f.get(node);
                if (v instanceof AST) collectDeclaredNames((AST) v, out);
                else if (v instanceof List) for (Object x : (List) v) if (x instanceof AST) collectDeclaredNames((AST) x, out);
            } catch (IllegalAccessException ignored) { }
        }
    }

    /** Referenced identifiers (first-occurrence order) + `this`, skipping shadow declarations. */
    private void collectFreeRefs(AST node, java.util.Set<String> declared, List<String> refs) {
        if (node == null) return;
        if (node instanceof Identifier) {
            String n = ((Identifier) node).name;
            if (!declared.contains(n) && !refs.contains(n)) refs.add(n);
            return;
        }
        if (node instanceof ThisExpr) {
            if (!refs.contains("this")) refs.add("this");
            return;
        }
        for (java.lang.reflect.Field f : node.getClass().getFields()) {
            try {
                Object v = f.get(node);
                if (v instanceof AST) collectFreeRefs((AST) v, declared, refs);
                else if (v instanceof List) for (Object x : (List) v) if (x instanceof AST) collectFreeRefs((AST) x, declared, refs);
            } catch (IllegalAccessException ignored) { }
        }
    }

    private LLVMValue generateFunctionRef(FunctionRefExpr node) {
        int line = node.line;
        // this::method 鈥?bind current receiver
        if (node.receiver instanceof ThisExpr) {
            if (currentClassName == null) {
                throw new RuntimeException("this:: is only valid inside a class method (at line " + line + ")");
            }
            FuncInfo fi = resolveMethodRef(lookupInstanceMethods(classes.get(currentClassName), node.method), line);
            if (fi == null) {
                throw new RuntimeException("Unknown method in reference: this::" + node.method + " (at line " + line + ")");
            }
            if (fi.isStatic) {
                throw new RuntimeException("this:: requires an instance method; use "
                    + classes.get(currentClassName).simpleName + "::" + node.method + " for static methods (at line " + line + ")");
            }
            LLVMValue receiver = scope.lookup("this");
            if (receiver == null) {
                throw new RuntimeException("this:: is only valid inside a class method (at line " + line + ")");
            }
            String thunk = emitThunk(fi, "%" + fi.className + "*");
            return buildFunctionValue(thunk, fi, receiver.value, receiver.type, functionSignature(fi));
        }

        if (!(node.receiver instanceof Identifier)) {
            throw new RuntimeException("Method reference receiver must be this, an object, or a class (at line " + line + ")");
        }
        String owner = ((Identifier) node.receiver).name;

        // Class::staticMethod 鈥?receiver-less reference
        ClassInfo ci = classes.get(owner);
        if (ci != null) {
            FuncInfo fi = resolveMethodRef(overloadGroups.get(ci.fullName + "." + node.method), line);
            if (fi != null && fi.isStatic) {
                String thunk = emitThunk(fi, null);
                return functionRefValue(thunk, fi);
            }
            if (fi != null) {
                throw new RuntimeException(owner + "::" + node.method
                    + " is an instance method; use an object or this:: to bind a receiver (at line " + line + ")");
            }
            if (scope.lookup(owner) == null) {
                throw new RuntimeException("Unknown method in reference: " + owner + "::" + node.method + " (at line " + line + ")");
            }
        }

        // object::method 鈥?bind runtime receiver
        if (scope.lookup(owner) == null) {
            throw unknownClassError(owner, line);
        }
        LLVMValue receiver = generateExpr(new Identifier(owner, line));
        if (!(receiver.type.startsWith("%") && receiver.type.endsWith("*"))) {
            throw new RuntimeException("Method reference receiver must be a class instance (at line " + line + ")");
        }
        ClassInfo receiverClass = classes.get(extractClassName(receiver.type));
        FuncInfo fi = resolveMethodRef(lookupInstanceMethods(receiverClass, node.method), line);
        if (fi == null) {
            throw new RuntimeException("Unknown method in reference: " + owner + "::" + node.method + " (at line " + line + ")");
        }
        if (fi.isStatic) {
            throw new RuntimeException(owner + "::" + node.method
                + " is static; use the class name without an object receiver (at line " + line + ")");
        }
        String thunk = emitThunk(fi, "%" + fi.className + "*");
        return buildFunctionValue(thunk, fi, receiver.value, receiver.type, functionSignature(fi));
    }

    /** Type-level assignability (mirrors assignableTo without a value). */
    private boolean typeAssignable(String from, String to) {
        if (from.equals(to)) return true;
        if (typesCompatible(from, to)) return true;
        if (isNumericLLVM(from) && isNumericLLVM(to)) return true;
        if (from.endsWith("*") && to.endsWith("*")) return true;
        if (from.equals("null")) return to.endsWith("*") || to.equals("%CangFunction");
        return false;
    }

    /** Static type hint for overload scoring — computed WITHOUT emitting IR. null = unknown. */
    private String callArgTypeHint(AST arg) {
        if (arg instanceof IntLit) return "i32";
        if (arg instanceof LongLit) return "i64";
        if (arg instanceof FloatLit) return "float";
        if (arg instanceof DoubleLit) return "double";
        if (arg instanceof BoolLit) return "i1";
        if (arg instanceof StringLit || arg instanceof StrLit) return "i8*";
        if (arg instanceof NullLit) return "null";
        if (arg instanceof Identifier && scope != null) {
            LLVMValue v = scope.lookup(((Identifier) arg).name);
            if (v != null) return v.type;
        }
        return null;
    }

    /**
     * Overload resolution. Defaults are banned on overloaded names (enforced at registration),
     * so arity is exact: candidates → lambda narrowing → static type-hint scoring → ambiguity error.
     */
    private FuncInfo resolveOverload(List<FuncInfo> cands, List<AST> args, int line) {
        if (cands == null || cands.isEmpty()) return null;
        if (cands.size() == 1) return cands.get(0);

        String base = cands.get(0).name;
        List<FuncInfo> arity = new ArrayList<>();
        for (FuncInfo f : cands) if (f.paramTypes.size() == args.size()) arity.add(f);
        if (arity.isEmpty()) throw new RuntimeException("No overload of '" + base + "' matches "
            + args.size() + " argument(s); candidates: " + candidateSignatures(cands) + " (at line " + line + ")");
        if (arity.size() == 1) return arity.get(0);

        boolean hasLambda = false;
        for (AST a : args) if (a instanceof LambdaExpr) { hasLambda = true; break; }
        if (hasLambda) {
            List<FuncInfo> fnCand = new ArrayList<>();
            for (FuncInfo f : arity) {
                boolean ok = true;
                for (int i = 0; i < args.size(); i++) {
                    if (args.get(i) instanceof LambdaExpr && !isFunctionType(f.paramTypes.get(i))) { ok = false; break; }
                }
                if (ok) fnCand.add(f);
            }
            if (fnCand.size() == 1) return fnCand.get(0);
            throw new RuntimeException("Ambiguous overload of '" + base + "' for a lambda argument (at line " + line
                + "); candidates: " + candidateSignatures(fnCand.isEmpty() ? arity : fnCand));
        }

        String[] hints = new String[args.size()];
        for (int i = 0; i < args.size(); i++) hints[i] = callArgTypeHint(args.get(i));
        List<FuncInfo> viable = new ArrayList<>();
        for (FuncInfo f : arity) {
            boolean ok = true;
            for (int i = 0; i < args.size() && ok; i++) {
                if (hints[i] == null) continue;
                if (!typeAssignable(hints[i], toLLVMType(f.paramTypes.get(i)))) ok = false;
            }
            if (ok) viable.add(f);
        }
        if (viable.isEmpty()) throw new RuntimeException("No overload of '" + base + "' matches argument types "
            + java.util.Arrays.toString(hints) + "; candidates: " + candidateSignatures(arity) + " (at line " + line + ")");
        if (viable.size() == 1) return viable.get(0);

        // Prefer the candidate with the most exact (non-widening) hint matches.
        FuncInfo best = null;
        int bestExact = -1;
        boolean tie = false;
        for (FuncInfo f : viable) {
            int exact = 0;
            for (int i = 0; i < args.size(); i++) {
                if (hints[i] != null && hints[i].equals(toLLVMType(f.paramTypes.get(i)))) exact++;
            }
            if (exact > bestExact) { best = f; bestExact = exact; tie = false; }
            else if (exact == bestExact) tie = true;
        }
        if (best != null && !tie) return best;
        throw new RuntimeException("Ambiguous overload of '" + base + "' for argument types "
            + java.util.Arrays.toString(hints) + "; candidates: " + candidateSignatures(viable) + " (at line " + line + ")");
    }

    private String candidateSignatures(List<FuncInfo> cands) {
        StringBuilder sb = new StringBuilder();
        for (FuncInfo f : cands) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(functionSignature(f));
        }
        return sb.toString();
    }

    /** Resolve a method reference against the expected Function<...> context. */
    private FuncInfo resolveMethodRef(List<FuncInfo> cands, int line) {
        if (cands == null || cands.isEmpty()) return null;
        if (cands.size() == 1) return cands.get(0);
        String expected = expectedFunctionType;
        if (expected != null && isFunctionType(expected)) {
            List<FuncInfo> exact = new ArrayList<>();
            for (FuncInfo f : cands) {
                if (sameFunctionSignature(functionSignature(f), expected)) exact.add(f);
            }
            if (exact.size() == 1) return exact.get(0);
        }
        throw new RuntimeException("Ambiguous method reference '" + cands.get(0).name
            + "'; candidates: " + candidateSignatures(cands)
            + " — provide the target Function<...> type (at line " + line + ")");
    }

    /** Signature equality that normalizes Void/void like checkFunctionValue. */
    private boolean sameFunctionSignature(String a, String b) {
        String[] pa = functionTypeParts(a), pb = functionTypeParts(b);
        if (pa.length != pb.length) return false;
        for (int i = 0; i < pa.length; i++) {
            String x = pa[i].equals("Void") ? "void" : pa[i];
            String y = pb[i].equals("Void") ? "void" : pb[i];
            if (x.equals(y)) continue;
            if (!typesCompatible(toLLVMType(x), toLLVMType(y))) return false;
        }
        return true;
    }

    /** Group of same-name methods on ci or the first ancestor declaring the name. */
    private List<FuncInfo> lookupInstanceMethods(ClassInfo ci, String method) {
        int guard = 0;
        while (ci != null && guard++ < 64) {
            List<FuncInfo> grp = overloadGroups.get(ci.fullName + "." + method);
            if (grp != null && !grp.isEmpty()) return grp;
            ci = ci.parentName != null ? classes.get(ci.parentName) : null;
        }
        return null;
    }

    /** Find a method (static or instance) on a class or its parents. */
    private FuncInfo lookupInstanceMethod(ClassInfo ci, String method) {
        int guard = 0;
        while (ci != null && guard++ < 64) {
            FuncInfo fi = functions.get(ci.fullName + "." + method);
            if (fi != null) return fi;
            ci = ci.parentName != null ? classes.get(ci.parentName) : null;
        }
        return null;
    }

    private String functionSignature(FuncInfo fi) {
        StringBuilder result = new StringBuilder("Function<").append(fi.returnType);
        for (String type : fi.paramTypes) result.append(',').append(type);
        return result.append('>').toString();
    }

    /** Canonical code type used inside Function objects: Ret (i8* env, Args...). */
    private String canonicalCodeType(FuncInfo fi) {
        StringBuilder result = new StringBuilder(toLLVMType(fi.returnType)).append(" (i8*");
        for (String type : fi.paramTypes) result.append(", ").append(toLLVMType(type));
        return result.append(")*").toString();
    }

    /**
     * Emit a top-level thunk implementing the canonical code ABI for a callee.
     * receiverType != null means env is bitcast to the receiver and passed first.
     */
    /** LLVM symbol for a FuncInfo (overloads carry a .N suffix; synthetic entries may not). */
    private String symOf(FuncInfo fi) {
        return fi.llvmName != null ? fi.llvmName : fi.name;
    }

    /** Find the FuncInfo registered for a specific FuncDecl (overload groups match by params). */
    private FuncInfo findFuncInfoForDecl(String funcName, FuncDecl decl) {
        List<FuncInfo> grp = overloadGroups.get(funcName);
        if (grp == null) return functions.get(funcName);
        List<String> myTypes = new ArrayList<>();
        for (Parameter p : decl.params) myTypes.add(p.type);
        for (FuncInfo g : grp) if (g.paramTypes.equals(myTypes)) return g;
        return functions.get(funcName);
    }

    /**
     * Overload target for dynamic dispatch: the same-signature method on this class, or the
     * resolved base implementation (inheritance) when the subclass does not override it.
     */
    private FuncInfo dispatchTarget(String funcName, FuncInfo staticFi) {
        List<FuncInfo> grp = overloadGroups.get(funcName);
        if (grp != null) {
            for (FuncInfo g : grp) if (g.paramTypes.equals(staticFi.paramTypes)) return g;
        }
        return staticFi;
    }

    private String emitThunk(FuncInfo fi, String receiverType) {
        String name = "@cang.thunk." + thunkCount++;
        String retType = toLLVMType(fi.returnType);
        StringBuilder params = new StringBuilder("i8* %env");
        List<String> callArgs = new ArrayList<>();
        StringBuilder inside = new StringBuilder();
        if (receiverType != null) {
            String recv = "%recv." + thunkCount;
            inside.append("  ").append(recv).append(" = bitcast i8* %env to ").append(receiverType).append("\n");
            callArgs.add(receiverType + " " + recv);
        }
        for (int i = 0; i < fi.paramTypes.size(); i++) {
            String llvmType = toLLVMType(fi.paramTypes.get(i));
            params.append(", ").append(llvmType).append(" %ca.").append(i);
            callArgs.add(llvmType + " %ca." + i);
        }
        String joined = String.join(", ", callArgs);
        extraDefs.append("define ").append(retType).append(" ").append(name)
                 .append("(").append(params).append(") {\nentry:\n").append(inside);
        if (retType.equals("void")) {
            extraDefs.append("  call void @").append(symOf(fi)).append("(").append(joined).append(")\n");
            extraDefs.append("  ret void\n");
        } else {
            String result = "%tr." + thunkCount;
            extraDefs.append("  ").append(result).append(" = call ").append(retType)
                     .append(" @").append(symOf(fi)).append("(").append(joined).append(")\n");
            extraDefs.append("  ret ").append(retType).append(" ").append(result).append("\n");
        }
        extraDefs.append("}\n\n");
        return name;
    }

    /** Global constant Function pair for receiver-less references, loaded as a value at the use site. */
    private String functionRefGlobal(String thunk, FuncInfo fi) {
        String global = "@cang.fnref." + fnrefCount++;
        extraDefs.append(global).append(" = internal constant %CangFunction { i8* bitcast(")
                 .append(canonicalCodeType(fi)).append(" ").append(thunk)
                 .append(" to i8*), i8* null }\n");
        return global;
    }

    private LLVMValue functionRefValue(String thunk, FuncInfo fi) {
        String global = functionRefGlobal(thunk, fi);
        String loaded = "%fn.load." + tmpCount++;
        body.append("  ").append(loaded).append(" = load %CangFunction, %CangFunction* ")
             .append(global).append("\n");
        return new LLVMValue(loaded, "%CangFunction", functionSignature(fi));
    }

    /** Build a runtime Function value with a bound receiver. */
    private LLVMValue buildFunctionValue(String thunk, FuncInfo fi, String receiverValue, String receiverType, String signature) {
        String code = "%fn.code." + tmpCount++;
        body.append("  ").append(code).append(" = bitcast ").append(canonicalCodeType(fi))
             .append(" ").append(thunk).append(" to i8*\n");
        String receiver = receiverValue;
        if (!receiverType.equals("i8*")) {
            receiver = "%fn.recv." + tmpCount++;
            body.append("  ").append(receiver).append(" = bitcast ").append(receiverType)
                 .append(" ").append(receiverValue).append(" to i8*\n");
        }
        String v0 = "%fn.v0." + tmpCount++;
        body.append("  ").append(v0).append(" = insertvalue %CangFunction undef, i8* ").append(code).append(", 0\n");
        String v1 = "%fn.v1." + tmpCount++;
        body.append("  ").append(v1).append(" = insertvalue %CangFunction ").append(v0)
             .append(", i8* ").append(receiver).append(", 1\n");
        return new LLVMValue(v1, "%CangFunction", signature);
    }

    /** Generate a value with the expected Function<...> context and validate it. */
    private LLVMValue generateCallArg(AST arg, String paramType, int line) {
        String savedExpected = expectedFunctionType;
        expectedFunctionType = isFunctionType(paramType) ? paramType : null;
        LLVMValue value;
        try {
            value = generateExpr(arg);
        } finally {
            expectedFunctionType = savedExpected;
        }
        if (isFunctionType(paramType)) {
            checkFunctionValue(value, paramType, line);
        } else {
            String expectedLLVM = toLLVMType(paramType);
            boolean compatible = value.type.equals(expectedLLVM)
                || typesCompatible(value.type, expectedLLVM)
                || (isNumericLLVM(value.type) && isNumericLLVM(expectedLLVM));
            if (!compatible) {
                throw new RuntimeException("Argument type mismatch: expected " + paramType
                    + " but found " + cangTypeFromLLVMFull(value.type) + " (at line " + line + ")");
            }
        }
        return value;
    }

    private boolean isNumericLLVM(String llvmType) {
        return llvmType.equals("i1") || llvmType.equals("i8") || llvmType.equals("i32")
            || llvmType.equals("i64") || llvmType.equals("float") || llvmType.equals("double");
    }

    private void checkFunctionValue(LLVMValue value, String expectedSignature, int line) {
        String actual = value.semanticType;
        if (actual == null || !isFunctionType(actual)) {
            throw new RuntimeException("Expected " + expectedSignature + " but found "
                + (actual != null ? actual : cangTypeFromLLVMFull(value.type)) + " (at line " + line + ")");
        }
        String[] expected = functionTypeParts(expectedSignature);
        String[] found = functionTypeParts(actual);
        if (expected.length != found.length) {
            throw new RuntimeException("Function type mismatch: expected " + expectedSignature
                + " but found " + actual + " (at line " + line + ")");
        }
        for (int i = 0; i < expected.length; i++) {
            String expectedPart = expected[i].equals("Void") ? "void" : expected[i];
            String foundPart = found[i].equals("Void") ? "void" : found[i];
            if (expectedPart.equals(foundPart)) continue;
            if (typesCompatible(toLLVMType(foundPart), toLLVMType(expectedPart))) continue;
            throw new RuntimeException("Function type mismatch: expected " + expectedSignature
                + " but found " + actual + " (at line " + line + ")");
        }
    }

    private LLVMValue generateIdentifier(Identifier node) {
        if (freedVars.contains(node.name)) {
            throw new RuntimeException("Use of freed variable: " + node.name + " (at line " + node.line + ")");
        }
        LLVMValue val = scope.lookup(node.name);
        if (val == null) {
            FuncInfo function = functions.get(node.name);
            if (function != null && function.className == null) {
                String thunk = emitThunk(function, null);
                return functionRefValue(thunk, function);
            }
            // Check static fields of current class
            if (currentClassName != null) {
                ClassInfo ci = classes.get(currentClassName);
                if (ci != null) {
                    for (FieldDecl f : ci.staticFields) {
                        if (f.name.equals(node.name)) {
                            // Load from global variable
                            String globalName = "@static." + currentClassName + "_" + f.name;
                            String llvmType = toLLVMType(f.type);
                            String loaded = "%static." + tmpCount++;
                            body.append("  ").append(loaded).append(" = load ").append(llvmType)
                                 .append(", ").append(llvmType).append("* ").append(globalName).append("\n");
                            return new LLVMValue(loaded, llvmType);
                        }
                    }
                }
            }
            // Check global static fields map
            for (Map.Entry<String, FieldDecl> entry : staticFields.entrySet()) {
                if (entry.getKey().endsWith("." + node.name)) {
                    FieldDecl f = entry.getValue();
                    String globalName = "@static." + entry.getKey().replace(".", "_");
                    String llvmType = toLLVMType(f.type);
                    String loaded = "%static." + tmpCount++;
                    body.append("  ").append(loaded).append(" = load ").append(llvmType)
                         .append(", ").append(llvmType).append("* ").append(globalName).append("\n");
                    return new LLVMValue(loaded, llvmType);
                }
            }
            throw new RuntimeException("Undefined variable: " + node.name + " at line " + node.line);
        }
        // Load the value
        String loaded = "%ld." + tmpCount++;
        body.append("  ").append(loaded).append(" = load ").append(val.type)
             .append(", ").append(val.type).append("* ").append(val.value).append("\n");
        return new LLVMValue(loaded, val.type, val.semanticType);
    }

    private LLVMValue generateBinary(BinaryExpr node) {
        LLVMValue left = generateExpr(node.left);

        String op = node.op;

        // Logical operators — short-circuit (debug.md #14): the right operand is only
        // evaluated when the result is still undetermined. Right-side code may open its own
        // blocks, so its value flows through a dedicated join-predecessor block for the phi.
        if (op.equals("&&") || op.equals("||")) {
            String l1 = ensureI1(left);
            int id = labelCount++;
            String rhsLabel = "sc.rhs." + id;
            String shortLabel = "sc.short." + id;
            String rhsDone = "sc.rhsdone." + id;
            String joinLabel = "sc.join." + id;
            if (op.equals("&&")) {
                // left false → short-circuit false
                body.append("  br i1 ").append(l1).append(", label %").append(rhsLabel)
                     .append(", label %").append(shortLabel).append("\n\n");
            } else {
                // left true → short-circuit true
                String lnot = "%sc.not." + tmpCount++;
                body.append("  ").append(lnot).append(" = xor i1 ").append(l1).append(", 1\n");
                body.append("  br i1 ").append(lnot).append(", label %").append(rhsLabel)
                     .append(", label %").append(shortLabel).append("\n\n");
            }
            body.append(shortLabel).append(":\n");
            body.append("  br label %").append(joinLabel).append("\n\n");
            body.append(rhsLabel).append(":\n");
            LLVMValue right = generateExpr(node.right);
            String r1 = ensureI1(right);
            body.append("  br label %").append(rhsDone).append("\n\n");
            body.append(rhsDone).append(":\n");
            body.append("  br label %").append(joinLabel).append("\n\n");
            body.append(joinLabel).append(":\n");
            String result = "%sc." + tmpCount++;
            body.append("  ").append(result).append(" = phi i1 [ ")
                 .append(op.equals("&&") ? "0" : "1").append(", %").append(shortLabel)
                 .append(" ], [ ").append(r1).append(", %").append(rhsDone).append(" ]\n");
            return new LLVMValue(result, "i1");
        }

        LLVMValue right = generateExpr(node.right);

        // Comparison operators
        if (op.equals("==") || op.equals("!=") || op.equals("<") || op.equals(">") ||
            op.equals("<=") || op.equals(">=")) {
            return generateComparison(left, op, right, node.line);
        }

        // // operator: always returns double
        if (op.equals("//")) {
            String l = castValue(left, "double");
            String r = castValue(right, "double");
            String result = "%fdiv." + tmpCount++;
            body.append("  ").append(result).append(" = fdiv double ")
                .append(l).append(", ").append(r).append("\n");
            return new LLVMValue(result, "double");
        }

        // String concatenation with +
        if (op.equals("+") && (left.type.equals("i8*") || right.type.equals("i8*"))) {
            return generateStringConcat(left, right);
        }

        // Arithmetic operators
        boolean isFloat = isFloatType(left.type) || isFloatType(right.type);
        String commonType = isFloat ? (left.type.equals("double") || right.type.equals("double") ? "double" : "float") : commonIntType(left.type, right.type);

        String l = castValue(left, commonType);
        String r = castValue(right, commonType);
        String result = "%arith." + tmpCount++;

        String irOp;
        switch (op) {
            case "+": irOp = isFloat ? "fadd" : "add"; break;
            case "-": irOp = isFloat ? "fsub" : "sub"; break;
            case "*": irOp = isFloat ? "fmul" : "mul"; break;
            case "/": irOp = isFloat ? "fdiv" : (commonType.contains("i") ? "sdiv" : "udiv"); break;
            case "%": irOp = isFloat ? "frem" : "srem"; break;
            default: throw new RuntimeException("Unknown operator: " + op);
        }

        // Integer division/remainder by zero traps on x86 (#DE → SIGFPE) and is poison in
        // LLVM semantics — raise a catchable error instead.
        if (!isFloat && (irOp.equals("sdiv") || irOp.equals("srem") || irOp.equals("udiv") || irOp.equals("urem"))) {
            int id = labelCount++;
            String okLabel = "divok." + id;
            String zeroLabel = "divzero." + id;
            // Value name must not collide with the basic-block label: LLVM keeps values and
            // labels in one namespace (%divzero.N vs label %divzero.N would be ambiguous).
            String isZero = "%iszero." + tmpCount++;
            body.append("  ").append(isZero).append(" = icmp eq ").append(commonType).append(" ")
                 .append(r).append(", 0\n");
            body.append("  br i1 ").append(isZero).append(", label %").append(zeroLabel)
                 .append(", label %").append(okLabel).append("\n\n");
            body.append(zeroLabel).append(":\n");
            emitRuntimeError("Division by zero", node.line, okLabel);
            body.append(okLabel).append(":\n");
        }

        body.append("  ").append(result).append(" = ").append(irOp).append(" ")
            .append(commonType).append(" ").append(l).append(", ").append(r).append("\n");

        return new LLVMValue(result, commonType);
    }

    /**
     * Generate string concatenation: a + b
     * First converts non-string operands to strings, then concatenates.
     */
    private LLVMValue generateStringConcat(LLVMValue left, LLVMValue right) {
        // Convert non-string operands to string
        if (!left.type.equals("i8*")) {
            left = convertToString(left);
        }
        if (!right.type.equals("i8*")) {
            right = convertToString(right);
        }

        // Java semantics: a null operand concatenates as the literal "null"
        // (calling strlen on NULL would SEGV).
        String nullStr = "%cat.nullp." + tmpCount++;
        body.append("  ").append(nullStr).append(" = getelementptr [5 x i8], [5 x i8]* @.str.null.p, i32 0, i32 0\n");
        String lNull = "%cat.ln." + tmpCount++;
        body.append("  ").append(lNull).append(" = icmp eq i8* ").append(left.value).append(", null\n");
        String lUse = "%cat.lu." + tmpCount++;
        body.append("  ").append(lUse).append(" = select i1 ").append(lNull).append(", i8* ").append(nullStr)
             .append(", i8* ").append(left.value).append("\n");
        String rNull = "%cat.rn." + tmpCount++;
        body.append("  ").append(rNull).append(" = icmp eq i8* ").append(right.value).append(", null\n");
        String rUse = "%cat.ru." + tmpCount++;
        body.append("  ").append(rUse).append(" = select i1 ").append(rNull).append(", i8* ").append(nullStr)
             .append(", i8* ").append(right.value).append("\n");
        left = new LLVMValue(lUse, "i8*");
        right = new LLVMValue(rUse, "i8*");

        // Get lengths
        String lenLeft = "%len.l." + tmpCount++;
        body.append("  ").append(lenLeft).append(" = call i64 @strlen(i8* ").append(left.value).append(")\n");

        String lenRight = "%len.r." + tmpCount++;
        body.append("  ").append(lenRight).append(" = call i64 @strlen(i8* ").append(right.value).append(")\n");

        // Total size = lenLeft + lenRight + 1 (null terminator)
        String totalLen = "%len.total." + tmpCount++;
        body.append("  ").append(totalLen).append(" = add i64 ").append(lenLeft).append(", ").append(lenRight).append("\n");
        String allocSize = "%size.alloc." + tmpCount++;
        body.append("  ").append(allocSize).append(" = add i64 ").append(totalLen).append(", 1\n");

        // malloc
        String buf = "%buf." + tmpCount++;
        body.append("  ").append(buf).append(" = call i8* @").append(allocFn()).append("(i64 ").append(allocSize).append(")\n");
        registerRegionAllocation(buf);

        // memcpy(buf, left, lenLeft)
        body.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* ").append(buf)
             .append(", i8* ").append(left.value)
             .append(", i64 ").append(lenLeft).append(", i1 false)\n");

        // memcpy(buf + lenLeft, right, lenRight + 1) 閳?include null terminator
        String offset = "%offset." + tmpCount++;
        body.append("  ").append(offset).append(" = getelementptr i8, i8* ").append(buf)
             .append(", i64 ").append(lenLeft).append("\n");
        String copyLen = "%copy.len." + tmpCount++;
        body.append("  ").append(copyLen).append(" = add i64 ").append(lenRight).append(", 1\n");
        body.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* ").append(offset)
             .append(", i8* ").append(right.value)
             .append(", i64 ").append(copyLen).append(", i1 false)\n");

        return new LLVMValue(buf, "i8*");
    }

    /**
     * Convert a non-string value to its string representation using snprintf.
     */
    private LLVMValue convertToString(LLVMValue val) {
        // Java-style: bool concatenates as "true"/"false" — pure constants, no buffer needed.
        // Handled before the snprintf path (which would itoa it to 1/0).
        if (val.type.equals("i1")) {
            String trueStr = ensureStringConstant("@.str.btrue", "true\\00", 5);
            String falseStr = ensureStringConstant("@.str.bfalse", "false\\00", 6);
            String sel = "%tostr.bool." + tmpCount++;
            body.append("  ").append(sel).append(" = select i1 ").append(val.value)
                 .append(", i8* ").append(trueStr).append(", i8* ").append(falseStr).append("\n");
            return new LLVMValue(sel, "i8*");
        }
        // Allocate buffer (enough for any number)
        String buf = "%tostr.buf." + tmpCount++;
        body.append("  ").append(buf).append(" = call i8* @").append(allocFn()).append("(i64 64)\n");
        registerRegionAllocation(buf);

        String fmt;
        if (val.type.equals("i8")) {
            // byte: zext to i32 first
            String ext = "%tostr.ext." + tmpCount++;
            body.append("  ").append(ext).append(" = sext i8 ").append(val.value).append(" to i32\n");
            fmt = "@.fmt.tostr.int";
            val = new LLVMValue(ext, "i32");
        } else if (val.type.equals("i32")) {
            fmt = "@.fmt.tostr.int";
        } else if (val.type.equals("i64")) {
            fmt = "@.fmt.tostr.long";
        } else if (val.type.equals("float")) {
            String ext = "%tostr.ext." + tmpCount++;
            body.append("  ").append(ext).append(" = fpext float ").append(val.value).append(" to double\n");
            fmt = "@.fmt.tostr.double";
            val = new LLVMValue(ext, "double");
        } else if (val.type.equals("double")) {
            fmt = "@.fmt.tostr.double";
        } else {
            // Unknown type 閳?return empty string
            return new LLVMValue("@.str.empty", "i8*");
        }

        body.append("  call i32 (i8*, i8*, ...) @sprintf(i8* ").append(buf)
             .append(", i8* ").append(fmt)
             .append(", ").append(val.type).append(" ").append(val.value).append(")\n");

        return new LLVMValue(buf, "i8*");
    }

    private LLVMValue generateComparison(BinaryExpr node) {
        LLVMValue left = generateExpr(node.left);
        LLVMValue right = generateExpr(node.right);
        return generateComparison(left, node.op, right, node.line);
    }

    private LLVMValue generateComparison(LLVMValue left, String op, LLVMValue right) {
        return generateComparison(left, op, right, 0);
    }

    private LLVMValue generateComparison(LLVMValue left, String op, LLVMValue right, int line) {
        // String comparison with strcmp
        if (left.type.equals("i8*") && right.type.equals("i8*")) {
            if (op.equals("==") || op.equals("!=")) {
                return generateStringCompare(left, op, right);
            }
            throw new RuntimeException("Only == and != are supported for str/String (at line " + line + ")");
        }

        // Reference == null / != null (pointer identity) — the singleton/optional idiom:
        // `if (instance == null)` must work for class objects, not just String (debug: the
        // null literal is i8* and previously fell into the String-vs-object rejection).
        boolean leftNull = left.value.equals("null");
        boolean rightNull = right.value.equals("null");
        if ((leftNull || rightNull) && !op.equals("==") && !op.equals("!=")) {
            throw new RuntimeException("null can only be compared with == or != (at line " + line + ")");
        }
        if (leftNull || rightNull) {
            LLVMValue ptrSide = leftNull ? right : left;
            LLVMValue otherSide = leftNull ? left : right;
            if (ptrSide.type.endsWith("*")) {
                String ptype = ptrSide.type;
                String cmp = "%nullcmp." + tmpCount++;
                body.append("  ").append(cmp).append(" = icmp ").append(op.equals("==") ? "eq" : "ne")
                     .append(" ").append(ptype).append(" ").append(ptrSide.value)
                     .append(", ").append(otherSide.value).append("\n");
                return new LLVMValue(cmp, "i1");
            }
            // non-pointer vs null (e.g. int == null): fall through to the type-mismatch error
        }

        // Reference vs reference: identity comparison (a == b, singleton checks). Both sides
        // bitcast to i8* so differing class pointer types still compare as addresses.
        if (left.type.endsWith("*") && right.type.endsWith("*")
                && !left.type.equals("i8*") && !right.type.equals("i8*")) {
            if (!op.equals("==") && !op.equals("!=")) {
                throw new RuntimeException("Objects only support == and != (at line " + line + ")");
            }
            String lb = "%objcmp.l." + tmpCount++;
            String rb = "%objcmp.r." + tmpCount++;
            body.append("  ").append(lb).append(" = bitcast ").append(left.type).append(" ")
                 .append(left.value).append(" to i8*\n");
            body.append("  ").append(rb).append(" = bitcast ").append(right.type).append(" ")
                 .append(right.value).append(" to i8*\n");
            String cmp = "%objcmp." + tmpCount++;
            body.append("  ").append(cmp).append(" = icmp ").append(op.equals("==") ? "eq" : "ne")
                 .append(" i8* ").append(lb).append(", ").append(rb).append("\n");
            return new LLVMValue(cmp, "i1");
        }

        // Type mismatch check: string vs non-string
        boolean leftIsString = left.type.equals("i8*");
        boolean rightIsString = right.type.equals("i8*");
        if (leftIsString || rightIsString) {
            String leftName = cangTypeFromLLVM(left.type);
            String rightName = cangTypeFromLLVM(right.type);
            if (line > 0) {
                throw new RuntimeException("Cannot compare " + leftName + " with " + rightName + " (at line " + line + ")");
            }
            throw new RuntimeException("Cannot compare " + leftName + " with " + rightName);
        }

        boolean isFloat = isFloatType(left.type) || isFloatType(right.type);
        String commonType = isFloat ? (left.type.equals("double") || right.type.equals("double") ? "double" : "float") : commonIntType(left.type, right.type);

        String l = castValue(left, commonType);
        String r = castValue(right, commonType);
        String result = "%cmp." + tmpCount++;

        String predicate;
        if (isFloat) {
            switch (op) {
                case "==": predicate = "oeq"; break;
                case "!=": predicate = "one"; break;
                case "<":  predicate = "olt"; break;
                case ">":  predicate = "ogt"; break;
                case "<=": predicate = "ole"; break;
                case ">=": predicate = "oge"; break;
                default: throw new RuntimeException("Unknown comparison: " + op);
            }
            body.append("  ").append(result).append(" = fcmp ").append(predicate).append(" ")
                .append(commonType).append(" ").append(l).append(", ").append(r).append("\n");
        } else {
            switch (op) {
                case "==": predicate = "eq"; break;
                case "!=": predicate = "ne"; break;
                case "<":  predicate = "slt"; break;
                case ">":  predicate = "sgt"; break;
                case "<=": predicate = "sle"; break;
                case ">=": predicate = "sge"; break;
                default: throw new RuntimeException("Unknown comparison: " + op);
            }
            body.append("  ").append(result).append(" = icmp ").append(predicate).append(" ")
                .append(commonType).append(" ").append(l).append(", ").append(r).append("\n");
        }

        return new LLVMValue(result, "i1");
    }

    /**
     * String comparison using strcmp.
     * strcmp returns 0 if equal, <0 if left<right, >0 if left>right
     */
    private LLVMValue generateStringCompare(LLVMValue left, String op, LLVMValue right) {
        // Null-safe string comparison: strcmp(NULL, ...) is undefined behaviour, so branch when
        // either operand may be null (File.getParent() == null, f.readText() == "x", ...).
        // Ordering ops treat null as the smallest value.
        int id = labelCount++;
        String nullL = "%sc.ln." + tmpCount++;
        String nullR = "%sc.rn." + tmpCount++;
        String either = "%sc.e." + tmpCount++;
        body.append("  ").append(nullL).append(" = icmp eq i8* ").append(left.value).append(", null\n");
        body.append("  ").append(nullR).append(" = icmp eq i8* ").append(right.value).append(", null\n");
        body.append("  ").append(either).append(" = or i1 ").append(nullL).append(", ").append(nullR).append("\n");
        String nullLabel = "sc.null." + id;
        String strLabel = "sc.str." + id;
        String endLabel = "sc.end." + id;
        body.append("  br i1 ").append(either).append(", label %").append(nullLabel)
             .append(", label %").append(strLabel).append("\n\n");

        // Null-case result (derived only from null-ness of both sides).
        body.append(nullLabel).append(":\n");
        String nullRes;
        String rnot = "%sc.rn2." + tmpCount++;
        String lnot = "%sc.ln2." + tmpCount++;
        body.append("  ").append(rnot).append(" = xor i1 ").append(nullR).append(", 1\n");
        body.append("  ").append(lnot).append(" = xor i1 ").append(nullL).append(", 1\n");
        switch (op) {
            case "==": { // true only when both null
                nullRes = "%sc.nr." + tmpCount++;
                body.append("  ").append(nullRes).append(" = and i1 ").append(nullL).append(", ").append(nullR).append("\n");
                break;
            }
            case "!=": { // true unless both null
                String both = "%sc.b." + tmpCount++;
                nullRes = "%sc.nr." + tmpCount++;
                body.append("  ").append(both).append(" = and i1 ").append(nullL).append(", ").append(nullR).append("\n");
                body.append("  ").append(nullRes).append(" = xor i1 ").append(both).append(", 1\n");
                break;
            }
            case "<": { // left null && right not-null
                nullRes = "%sc.nr." + tmpCount++;
                body.append("  ").append(nullRes).append(" = and i1 ").append(nullL).append(", ").append(rnot).append("\n");
                break;
            }
            case "<=": { // !(left not-null && right null)
                String gtNull = "%sc.gt." + tmpCount++;
                nullRes = "%sc.nr." + tmpCount++;
                body.append("  ").append(gtNull).append(" = and i1 ").append(lnot).append(", ").append(nullR).append("\n");
                body.append("  ").append(nullRes).append(" = xor i1 ").append(gtNull).append(", 1\n");
                break;
            }
            case ">": { // left not-null && right null
                nullRes = "%sc.nr." + tmpCount++;
                body.append("  ").append(nullRes).append(" = and i1 ").append(lnot).append(", ").append(nullR).append("\n");
                break;
            }
            case ">=": { // !(left null && right not-null)
                String ltNull = "%sc.lt." + tmpCount++;
                nullRes = "%sc.nr." + tmpCount++;
                body.append("  ").append(ltNull).append(" = and i1 ").append(nullL).append(", ").append(rnot).append("\n");
                body.append("  ").append(nullRes).append(" = xor i1 ").append(ltNull).append(", 1\n");
                break;
            }
            default: throw new RuntimeException("Unknown string comparison: " + op);
        }
        body.append("  br label %").append(endLabel).append("\n\n");

        // Neither side is null here: plain strcmp.
        body.append(strLabel).append(":\n");
        String cmpResult = "%strcmp." + tmpCount++;
        body.append("  ").append(cmpResult).append(" = call i32 @strcmp(i8* ")
             .append(left.value).append(", i8* ").append(right.value).append(")\n");
        String strRes = "%strcmp.cmp." + tmpCount++;
        String predicate;
        switch (op) {
            case "==": predicate = "eq"; break;
            case "!=": predicate = "ne"; break;
            case "<":  predicate = "slt"; break;
            case ">":  predicate = "sgt"; break;
            case "<=": predicate = "sle"; break;
            case ">=": predicate = "sge"; break;
            default: throw new RuntimeException("Unknown string comparison: " + op);
        }
        body.append("  ").append(strRes).append(" = icmp ").append(predicate)
             .append(" i32 ").append(cmpResult).append(", 0\n");
        body.append("  br label %").append(endLabel).append("\n\n");

        body.append(endLabel).append(":\n");
        String result = "%sc.m." + tmpCount++;
        body.append("  ").append(result).append(" = phi i1 [ ").append(nullRes).append(", %").append(nullLabel)
             .append(" ], [ ").append(strRes).append(", %").append(strLabel).append(" ]\n");
        return new LLVMValue(result, "i1");
    }

    private LLVMValue generateUnary(UnaryExpr node) {
        LLVMValue operand = generateExpr(node.operand);

        if (node.op.equals("-")) {
            if (isFloatType(operand.type)) {
                String result = "%neg." + tmpCount++;
                body.append("  ").append(result).append(" = fneg ").append(operand.type)
                     .append(" ").append(operand.value).append("\n");
                return new LLVMValue(result, operand.type);
            } else {
                String result = "%neg." + tmpCount++;
                body.append("  ").append(result).append(" = sub ").append(operand.type)
                     .append(" 0, ").append(operand.value).append("\n");
                return new LLVMValue(result, operand.type);
            }
        }
        if (node.op.equals("!")) {
            String result = "%not." + tmpCount++;
            String val = ensureI1(operand);
            body.append("  ").append(result).append(" = xor i1 ").append(val).append(", 1\n");
            return new LLVMValue(result, "i1");
        }
        if (node.op.equals("++") && node.prefix) {
            return generateIncrement(node.operand, "add", true);
        }
        if (node.op.equals("--") && node.prefix) {
            return generateIncrement(node.operand, "sub", true);
        }
        if (node.op.equals("++") && !node.prefix) {
            return generateIncrement(node.operand, "add", false);
        }
        if (node.op.equals("--") && !node.prefix) {
            return generateIncrement(node.operand, "sub", false);
        }

        throw new RuntimeException("Unknown unary operator: " + node.op);
    }

    private LLVMValue generateIncrement(AST target, String arithOp, boolean prefix) {
        if (!(target instanceof Identifier)) {
            throw new RuntimeException("Increment target must be a variable (at line " + target.line + ")");
        }
        Identifier id = (Identifier) target;
        LLVMValue ptr = scope.lookup(id.name);
        if (ptr == null) throw new RuntimeException("Undefined variable: " + id.name + " (at line " + target.line + ")");

        String loaded = "%inc.old." + tmpCount++;
        body.append("  ").append(loaded).append(" = load ").append(ptr.type)
             .append(", ").append(ptr.type).append("* ").append(ptr.value).append("\n");

        String one = isFloatType(ptr.type) ? "1.0" : "1";
        String irOp = isFloatType(ptr.type) ? (arithOp.equals("add") ? "fadd" : "fsub") : arithOp;
        String newVal = "%inc.new." + tmpCount++;
        body.append("  ").append(newVal).append(" = ").append(irOp).append(" ")
            .append(ptr.type).append(" ").append(loaded).append(", ").append(one).append("\n");

        body.append("  store ").append(ptr.type).append(" ").append(newVal)
             .append(", ").append(ptr.type).append("* ").append(ptr.value).append("\n");

        return new LLVMValue(prefix ? newVal : loaded, ptr.type);
    }

    private LLVMValue generateAssign(AssignExpr node) {
        markRegionControlFlow();
        // Provide expected Function<...> context when assigning to a Function variable.
        String assignExpected = null;
        if (node.target instanceof Identifier) {
            LLVMValue target = scope.lookup(((Identifier) node.target).name);
            if (target != null && isFunctionType(target.semanticType)) {
                assignExpected = target.semanticType;
            }
            if (target != null) {
                // Literal range check against the target slot type (debug.md #8).
                checkLiteralFits(node.value, cangTypeFromLLVM(target.type), node.line);
            }
        }
        String savedExpected = expectedFunctionType;
        expectedFunctionType = assignExpected;
        LLVMValue val;
        try {
            val = generateExpr(node.value);
        } finally {
            expectedFunctionType = savedExpected;
        }
        if (assignExpected != null) {
            checkFunctionValue(val, assignExpected, node.line);
        }

        if (node.target instanceof Identifier) {
            Identifier id = (Identifier) node.target;
            if (freedVars.contains(id.name)) {
                throw new RuntimeException("Assignment to freed variable: " + id.name + " (at line " + node.line + ")");
            }
            if (finalVars.contains(id.name)) {
                throw new RuntimeException("Cannot assign to final variable '" + id.name + "' (at line " + node.line + ")");
            }
            LLVMValue ptr = scope.lookup(id.name);
            if (ptr == null) {
                // Static field write of the current class by bare name (Java-style), e.g. the
                // singleton `instance = new Db()` inside its own static method.
                FieldDecl sf = findCurrentStaticField(id.name);
                if (sf != null && currentClassName != null) {
                    String gname = "@static." + currentClassName + "_" + sf.name;
                    String llvmT = toLLVMType(sf.type);
                    if (!assignableTo(llvmT, val)) {
                        throw new RuntimeException("Cannot assign " + cangTypeFromLLVMFull(val.type)
                            + " to " + cangTypeFromLLVMFull(llvmT) + " (at line " + node.line + ")");
                    }
                    String casted = castValue(val, llvmT);
                    body.append("  store ").append(llvmT).append(" ").append(casted)
                         .append(", ").append(llvmT).append("* ").append(gname).append("\n");
                    return new LLVMValue(casted, llvmT);
                }
                throw new RuntimeException("Undefined variable: " + id.name + " (at line " + node.line + ")");
            }
            if (!assignableTo(ptr.type, val)) {
                throw new RuntimeException("Cannot assign " + cangTypeFromLLVMFull(val.type)
                    + " to " + cangTypeFromLLVMFull(ptr.type) + " (at line " + node.line + ")");
            }
            String castedAssign = castValue(val, ptr.type);
            body.append("  store ").append(ptr.type).append(" ").append(castedAssign)
                 .append(", ").append(ptr.type).append("* ").append(ptr.value).append("\n");
            return new LLVMValue(val.value, ptr.type);
        }

        if (node.target instanceof FieldAccessExpr) {
            FieldAccessExpr fa = (FieldAccessExpr) node.target;
            // System built-in fields are final constants
            if (fa.object instanceof Identifier && ((Identifier) fa.object).name.equals("System")) {
                if (!fa.field.equals("ARGS") && !fa.field.equals("OS_TYPE") && !fa.field.equals("ARCH_TYPE")) {
                    throw new RuntimeException("Unknown System field: " + fa.field + " (at line " + node.line + ")");
                }
                throw new RuntimeException("Cannot assign to final variable '" + fa.field + "' (at line " + node.line + ")");
            }
            LLVMValue objPtr = generateExprForPtr(fa.object);
            // Null pointer check for field writes (store on null would SEGV).
            if (objPtr.type.equals("i8*") || (objPtr.type.startsWith("%") && objPtr.type.endsWith("*"))) {
                int id = labelCount++;
                String notNullLabel = "nonnull." + id;
                String nullLabel = "isnull." + id;
                String isNull = "%null." + tmpCount++;
                body.append("  ").append(isNull).append(" = icmp eq ").append(objPtr.type)
                     .append(" ").append(objPtr.value).append(", null\n");
                body.append("  br i1 ").append(isNull).append(", label %").append(nullLabel)
                     .append(", label %").append(notNullLabel).append("\n\n");
                body.append(nullLabel).append(":\n");
                emitRuntimeError("Null pointer dereference", node.line, notNullLabel);
                body.append(notNullLabel).append(":\n");
            }
            String className = extractClassName(objPtr.type);
            ClassInfo ci = classes.get(className);
            if (ci == null) throw unknownClassError(className, node.line);

            Integer fieldIdx = ci.fieldIndices.get(fa.field);
            if (fieldIdx == null) throw new RuntimeException("Unknown field: " + fa.field);

            // fieldIdx includes type ID offset, fieldTypes starts at 0
            String fieldType = ci.fieldTypes.get(fieldIdx - 1);
            String fieldPtr = "%fp." + tmpCount++;
            body.append("  ").append(fieldPtr).append(" = getelementptr ").append(ci.llvmName)
                 .append(", ").append(ci.llvmName).append("* ").append(objPtr.value)
                 .append(", i32 0, i32 ").append(fieldIdx).append("\n");
            String castedField = castValue(val, toLLVMType(fieldType));
            if (!assignableTo(toLLVMType(fieldType), val)) {
                throw new RuntimeException("Cannot assign " + cangTypeFromLLVMFull(val.type)
                    + " to field '" + fa.field + "' of type " + fieldType + " (at line " + node.line + ")");
            }
            body.append("  store ").append(toLLVMType(fieldType)).append(" ")
                 .append(castedField)
                 .append(", ").append(toLLVMType(fieldType)).append("* ").append(fieldPtr).append("\n");
            return val;
        }

        if (node.target instanceof ArrayAccessExpr) {
            return generateArrayAssign((ArrayAccessExpr) node.target, val, node.line);
        }

        throw new RuntimeException("Invalid assignment target");
    }

    /**
     * Generate array element assignment: arr[index] = value
     */
    private LLVMValue generateArrayAssign(ArrayAccessExpr target, LLVMValue val, int line) {
        LLVMValue arrPtr = generateExpr(target.array);
        LLVMValue idx = generateExpr(target.index);

        // Determine element type: Cang-level tracking resolves nested subscripts (a[i][j] = x)
        String elemType = "i32"; // default
        String cangElemType = "int";
        String elemCang = elemCangOf(target.array);
        if (elemCang != null) {
            elemType = toLLVMType(elemCang);
            cangElemType = elemCang;
        } else if (target.array instanceof Identifier) {
            String tracked = arrayElemTypes.get(((Identifier) target.array).name);
            if (tracked != null) {
                elemType = tracked;
                cangElemType = cangTypeFromLLVMFull(elemType);
            }
        }

        // Type check: value type must be compatible with element type
        if (!typesCompatible(val.type, elemType)) {
            String valCang = cangTypeFromLLVMFull(val.type);
            throw new RuntimeException(
                "Array element type mismatch: expected " + cangElemType +
                " but found " + valCang + " (at line " + line + ")");
        }

        // Skip 8-byte length header
        String dataPtr = "%arr.data." + tmpCount++;
        body.append("  ").append(dataPtr).append(" = getelementptr i8, i8* ")
             .append(arrPtr.value).append(", i64 8\n");

        String typedPtr = "%arr.typed." + tmpCount++;
        body.append("  ").append(typedPtr).append(" = bitcast i8* ").append(dataPtr)
             .append(" to ").append(elemType).append("*\n");

        // Cast index to i64
        String idxI64 = "%arr.idx." + tmpCount++;
        if (idx.type.equals("i32")) {
            body.append("  ").append(idxI64).append(" = sext i32 ").append(idx.value).append(" to i64\n");
        } else if (idx.type.equals("i64")) {
            body.append("  ").append(idxI64).append(" = ").append(idx.value).append("\n");
        } else {
            body.append("  ").append(idxI64).append(" = zext ").append(idx.type).append(" ").append(idx.value).append(" to i64\n");
        }

        // Null + bounds checks for writes (debug.md #10): mirror the read path so an
        // out-of-range store raises a catchable error instead of corrupting the heap.
        int wid = labelCount++;
        String wNullErr = "arr.wnull." + wid;
        String wOkNull = "arr.wnn." + wid;
        String wIsNull = "%arr.wnullc." + tmpCount++;
        body.append("  ").append(wIsNull).append(" = icmp eq i8* ").append(arrPtr.value).append(", null\n");
        body.append("  br i1 ").append(wIsNull).append(", label %").append(wNullErr)
             .append(", label %").append(wOkNull).append("\n\n");
        body.append(wNullErr).append(":\n");
        emitRuntimeError("Null pointer dereference", line, wOkNull);
        body.append(wOkNull).append(":\n");

        String wLen = "%arr.wlen." + tmpCount++;
        body.append("  ").append(wLen).append(" = load i64, i64* ").append(arrPtr.value).append("\n");
        String wGe = "%arr.wge." + tmpCount++;
        body.append("  ").append(wGe).append(" = icmp sge i64 ").append(idxI64).append(", ").append(wLen).append("\n");
        String wLt0 = "%arr.wlt0." + tmpCount++;
        body.append("  ").append(wLt0).append(" = icmp slt i64 ").append(idxI64).append(", 0\n");
        String wOob = "%arr.woob." + tmpCount++;
        body.append("  ").append(wOob).append(" = or i1 ").append(wGe).append(", ").append(wLt0).append("\n");
        String wErr = "arr.werr." + wid;
        String wOk = "arr.wok." + wid;
        body.append("  br i1 ").append(wOob).append(", label %").append(wErr)
             .append(", label %").append(wOk).append("\n\n");
        body.append(wErr).append(":\n");
        emitRuntimeError("Array index out of bounds", line, wOk);
        body.append(wOk).append(":\n");

        // Get element pointer
        String elemPtr = "%arr.elem." + tmpCount++;
        body.append("  ").append(elemPtr).append(" = getelementptr ").append(elemType).append(", ")
             .append(elemType).append("* ").append(typedPtr).append(", i64 ").append(idxI64).append("\n");

        // Store value (with cast if needed)
        String castedElem = castValue(val, elemType);
        body.append("  store ").append(elemType).append(" ").append(castedElem)
             .append(", ").append(elemType).append("* ").append(elemPtr).append("\n");

        return val;
    }

    private LLVMValue generateTernary(TernaryExpr node) {
        LLVMValue cond = generateExpr(node.condition);
        String i1Cond = ensureI1(cond);

        // Only the taken branch executes (debug.md #16): branch + phi instead of select.
        int id = labelCount++;
        String tLabel = "tern.t." + id;
        String fLabel = "tern.f." + id;
        String joinLabel = "tern.join." + id;
        body.append("  br i1 ").append(i1Cond).append(", label %").append(tLabel)
             .append(", label %").append(fLabel).append("\n\n");

        body.append(tLabel).append(":\n");
        LLVMValue trueVal = generateExpr(node.trueExpr);
        String tOp = castValue(trueVal, trueVal.type);
        body.append("  br label %").append(joinLabel).append("\n\n");

        body.append(fLabel).append(":\n");
        LLVMValue falseVal = generateExpr(node.falseExpr);
        // Casts must be emitted inside their own block (phi has to be first in the join block).
        String fOp = castValue(falseVal, trueVal.type);
        body.append("  br label %").append(joinLabel).append("\n\n");

        body.append(joinLabel).append(":\n");
        String result = "%ternary." + tmpCount++;
        body.append("  ").append(result).append(" = phi ").append(trueVal.type)
             .append(" [ ").append(tOp).append(", %").append(tLabel)
             .append(" ], [ ").append(fOp).append(", %").append(fLabel).append(" ]\n");
        return new LLVMValue(result, trueVal.type);
    }

    /**
     * Generate like expression: obj like ClassName
     * Checks if obj is an instance of ClassName or its subclass.
     */
    private LLVMValue generateLike(LikeExpr node) {
        LLVMValue objVal = generateExpr(node.expr);

        // Load type ID from object (first field, offset 0)
        String typePtr = "%like.tid.ptr." + tmpCount++;
        body.append("  ").append(typePtr).append(" = bitcast ").append(objVal.type)
             .append(" ").append(objVal.value).append(" to i32*\n");
        String typeId = "%like.tid." + tmpCount++;
        body.append("  ").append(typeId).append(" = load i32, i32* ").append(typePtr).append("\n");

        // Get target class type ID (className may be an import alias — compare real name)
        ClassInfo targetInfo = classes.get(node.className);
        if (targetInfo == null) {
            throw unknownClassError(node.className, node.line);
        }
        String likeTarget = importAliases.getOrDefault(node.className, targetInfo.simpleName);

        // Collect all type IDs that are target or subclass of target
        java.util.List<Integer> validIds = new ArrayList<>();
        for (ClassInfo ci : classes.values()) {
            if (isSubclass(ci.simpleName, likeTarget)) {
                validIds.add(ci.typeId);
            }
        }
        if (validIds.isEmpty()) validIds.add(targetInfo.typeId);

        // Generate OR chain of comparisons
        String current = "%like." + tmpCount++;
        body.append("  ").append(current).append(" = icmp eq i32 ").append(typeId)
             .append(", ").append(validIds.get(0)).append("\n");

        for (int i = 1; i < validIds.size(); i++) {
            String cmp = "%like.cmp." + tmpCount++;
            body.append("  ").append(cmp).append(" = icmp eq i32 ").append(typeId)
                 .append(", ").append(validIds.get(i)).append("\n");
            String next = "%like.next." + tmpCount++;
            body.append("  ").append(next).append(" = or i1 ").append(current)
                 .append(", ").append(cmp).append("\n");
            current = next;
        }

        return new LLVMValue(current, "i1");
    }

    private LLVMValue generateTypeCast(TypeCastExpr node) {
        LLVMValue val = generateExpr(node.expr);
        String targetType = toLLVMType(node.targetType);

        // String/str semantic conversion while sharing the same LLVM pointer type.
        if (targetType.equals("i8*") && node.targetType.equals("str")) {
            if (val.type.equals("i8*")) {
                return new LLVMValue(val.value, val.type, "str");
            }
            LLVMValue converted = convertToString(val);
            converted.semanticType = "str";
            return converted;
        }
        if (targetType.equals("i8*") && node.targetType.equals("String")) {
            if (val.type.equals("i8*")) {
                return new LLVMValue(val.value, val.type, "String");
            }
            LLVMValue converted = convertToString(val);
            converted.semanticType = "String";
            return converted;
        }

        // If types already match, return as-is
        if (val.type.equals(targetType)) {
            return val;
        }

        // String to number conversion
        if (val.type.equals("i8*") && !targetType.equals("i8*")) {
            return generateStringToNumber(val, node.targetType, targetType);
        }

        String result = "%cast." + tmpCount++;

        // Float to int
        if (!isFloatType(targetType) && isFloatType(val.type)) {
            body.append("  ").append(result).append(" = fptosi ").append(val.type)
                 .append(" ").append(val.value).append(" to ").append(targetType).append("\n");
            return new LLVMValue(result, targetType);
        }

        // Int to float
        if (isFloatType(targetType) && !isFloatType(val.type)) {
            body.append("  ").append(result).append(" = sitofp ").append(val.type)
                 .append(" ").append(val.value).append(" to ").append(targetType).append("\n");
            return new LLVMValue(result, targetType);
        }

        // Float to float
        if (isFloatType(targetType) && isFloatType(val.type)) {
            if (val.type.equals("float") && targetType.equals("double")) {
                body.append("  ").append(result).append(" = fpext float ").append(val.value).append(" to double\n");
            } else if (val.type.equals("double") && targetType.equals("float")) {
                body.append("  ").append(result).append(" = fptrunc double ").append(val.value).append(" to float\n");
            }
            return new LLVMValue(result, targetType);
        }

        // Int to int
        int fromBits = llvmTypeBits(val.type);
        int toBits = llvmTypeBits(targetType);
        if (fromBits > 0 && toBits > 0) {
            if (toBits > fromBits) {
                body.append("  ").append(result).append(" = zext ").append(val.type)
                     .append(" ").append(val.value).append(" to ").append(targetType).append("\n");
            } else if (toBits < fromBits) {
                body.append("  ").append(result).append(" = trunc ").append(val.type)
                     .append(" ").append(val.value).append(" to ").append(targetType).append("\n");
            }
            return new LLVMValue(result, targetType);
        }

        // bool to int
        if (val.type.equals("i1") && llvmTypeBits(targetType) > 1) {
            body.append("  ").append(result).append(" = zext i1 ").append(val.value)
                 .append(" to ").append(targetType).append("\n");
            return new LLVMValue(result, targetType);
        }

        // Fallback: just change type label (shouldn't happen often)
        return new LLVMValue(val.value, targetType);
    }

    /**
     * Convert String to number using atoi/atol/atof.
     * int("123") 閳?123, double("3.14") 閳?3.14, long("100") 閳?100
     */
    private LLVMValue generateStringToNumber(LLVMValue strVal, String cangTargetType, String llvmTargetType) {
        String result = "%str2num." + tmpCount++;

        switch (cangTargetType) {
            case "byte":
                // atoi returns i32, then trunc to i8
                String byteTmp = "%str2num.i32." + tmpCount++;
                body.append("  ").append(byteTmp).append(" = call i32 @atoi(i8* ").append(strVal.value).append(")\n");
                body.append("  ").append(result).append(" = trunc i32 ").append(byteTmp).append(" to i8\n");
                break;
            case "int":
                body.append("  ").append(result).append(" = call i32 @atoi(i8* ").append(strVal.value).append(")\n");
                break;
            case "long":
                // Use sscanf with %lld for 64-bit parsing
                String longFmt = "@.fmt.sscanf.long";
                if (!header.toString().contains(longFmt)) {
                    header.append(longFmt).append(" = private unnamed_addr constant [5 x i8] c\"%lld\\00\"\n");
                }
                // Allocate temp, sscanf writes to it, then load
                String longPtr = "%long.ptr." + tmpCount++;
                body.append("  ").append(longPtr).append(" = alloca i64\n");
                body.append("  call i32 @sscanf(i8* ").append(strVal.value)
                     .append(", i8* ").append(longFmt)
                     .append(", i64* ").append(longPtr).append(")\n");
                body.append("  ").append(result).append(" = load i64, i64* ").append(longPtr).append("\n");
                break;
            case "float":
                // atof returns double, then fptrunc to float
                String floatTmp = "%str2num.f64." + tmpCount++;
                body.append("  ").append(floatTmp).append(" = call double @atof(i8* ").append(strVal.value).append(")\n");
                body.append("  ").append(result).append(" = fptrunc double ").append(floatTmp).append(" to float\n");
                break;
            case "double":
                body.append("  ").append(result).append(" = call double @atof(i8* ").append(strVal.value).append(")\n");
                break;
            default:
                throw new RuntimeException("Cannot convert String to " + cangTargetType);
        }

        return new LLVMValue(result, llvmTargetType);
    }

    private LLVMValue generateListMethod(LLVMValue list, String listType, String method, List<AST> args, int line) {
        String elemCang = listElementType(listType);
        String elemLLVM = toLLVMType(elemCang);
        String dataPtr = "%list.data." + tmpCount++;
        String sizePtr = "%list.size.ptr." + tmpCount++;
        String capPtr = "%list.cap.ptr." + tmpCount++;
        body.append("  ").append(dataPtr).append(" = getelementptr %CangList, %CangList* ").append(list.value).append(", i32 0, i32 0\n");
        body.append("  ").append(sizePtr).append(" = getelementptr %CangList, %CangList* ").append(list.value).append(", i32 0, i32 1\n");
        body.append("  ").append(capPtr).append(" = getelementptr %CangList, %CangList* ").append(list.value).append(", i32 0, i32 2\n");

        if (method.equals("isEmpty") && args.isEmpty()) {
            String size = "%list.empty.size." + tmpCount++;
            String result = "%list.empty.result." + tmpCount++;
            body.append("  ").append(size).append(" = load i64, i64* ").append(sizePtr).append("\n");
            body.append("  ").append(result).append(" = icmp eq i64 ").append(size).append(", 0\n");
            return new LLVMValue(result, "i1");
        }

        if (method.equals("size") && args.isEmpty()) {
            String size = "%list.size." + tmpCount++;
            body.append("  ").append(size).append(" = load i64, i64* ").append(sizePtr).append("\n");
            String result = "%list.size.i32." + tmpCount++;
            body.append("  ").append(result).append(" = trunc i64 ").append(size).append(" to i32\n");
            return new LLVMValue(result, "i32");
        }

        if (method.equals("get") && args.size() == 1) {
            LLVMValue index = generateExpr(args.get(0));
            String index64 = "%list.index." + tmpCount++;
            body.append("  ").append(index64).append(" = sext i32 ").append(castValue(index, "i32")).append(" to i64\n");
            String size = "%list.get.size." + tmpCount++;
            body.append("  ").append(size).append(" = load i64, i64* ").append(sizePtr).append("\n");
            String bad = "list.get.bad." + tmpCount++;
            String ok = "list.get.ok." + tmpCount++;
            String check = "%list.get.check." + tmpCount++;
            body.append("  ").append(check).append(" = icmp ult i64 ").append(index64).append(", ").append(size).append("\n");
            body.append("  br i1 ").append(check).append(", label %").append(ok).append(", label %").append(bad).append("\n");
            body.append(bad).append(":\n");
            emitRuntimeError("List index out of bounds", line, ok);
            body.append(ok).append(":\n");
            String data = "%list.get.data." + tmpCount++;
            body.append("  ").append(data).append(" = load i8*, i8** ").append(dataPtr).append("\n");
            long elemBytes = llvmTypeBits(elemLLVM) <= 0 ? 8 : (llvmTypeBits(elemLLVM) + 7) / 8;
            String byteOffset = "%list.get.offset." + tmpCount++;
            body.append("  ").append(byteOffset).append(" = mul i64 ").append(index64).append(", ").append(elemBytes).append("\n");
            String elemPtr = "%list.get.elem." + tmpCount++;
            body.append("  ").append(elemPtr).append(" = getelementptr i8, i8* ").append(data).append(", i64 ").append(byteOffset).append("\n");
            String typed = "%list.get.typed." + tmpCount++;
            body.append("  ").append(typed).append(" = bitcast i8* ").append(elemPtr).append(" to ").append(elemLLVM).append("*\n");
            String result = "%list.get." + tmpCount++;
            body.append("  ").append(result).append(" = load ").append(elemLLVM).append(", ").append(elemLLVM).append("* ").append(typed).append("\n");
            return new LLVMValue(result, elemLLVM, elemCang);
        }

        if (method.equals("add") && args.size() == 1) {
            LLVMValue value = generateCallArg(args.get(0), elemCang, line);
            String size = "%list.add.size." + tmpCount++;
            String capacity = "%list.add.capacity." + tmpCount++;
            body.append("  ").append(size).append(" = load i64, i64* ").append(sizePtr).append("\n");
            body.append("  ").append(capacity).append(" = load i64, i64* ").append(capPtr).append("\n");
            String needs = "%list.add.needs." + tmpCount++;
            body.append("  ").append(needs).append(" = icmp uge i64 ").append(size).append(", ").append(capacity).append("\n");
            int id = labelCount++;
            String grow = "list.grow." + id;
            String append = "list.append." + id;
            String done = "list.add.done." + id;
            body.append("  br i1 ").append(needs).append(", label %").append(grow).append(", label %").append(append).append("\n");
            body.append(grow).append(":\n");
            String newCap = "%list.newcap." + tmpCount++;
            body.append("  ").append(newCap).append(" = add i64 ").append(capacity).append(", 8\n");
            String oldData = "%list.olddata." + tmpCount++;
            body.append("  ").append(oldData).append(" = load i8*, i8** ").append(dataPtr).append("\n");
            String bytes = "%list.bytes." + tmpCount++;
            long elemBytes = llvmTypeBits(elemLLVM) <= 0 ? 8 : (llvmTypeBits(elemLLVM) + 7) / 8;
            body.append("  ").append(bytes).append(" = mul i64 ").append(newCap).append(", ").append(elemBytes).append("\n");
            String resized = "%list.resized." + tmpCount++;
            body.append("  ").append(resized).append(" = call i8* @").append(reallocFn()).append("(i8* ").append(oldData).append(", i64 ").append(bytes).append(")\n");
            body.append("  store i8* ").append(resized).append(", i8** ").append(dataPtr).append("\n");
            body.append("  store i64 ").append(newCap).append(", i64* ").append(capPtr).append("\n");
            body.append("  br label %").append(append).append("\n");
            body.append(append).append(":\n");
            String data = "%list.add.data." + tmpCount++;
            body.append("  ").append(data).append(" = load i8*, i8** ").append(dataPtr).append("\n");
            String byteOffset = "%list.add.offset." + tmpCount++;
            body.append("  ").append(byteOffset).append(" = mul i64 ").append(size).append(", ").append(elemBytes).append("\n");
            String slot = "%list.add.slot." + tmpCount++;
            body.append("  ").append(slot).append(" = getelementptr i8, i8* ").append(data).append(", i64 ").append(byteOffset).append("\n");
            String typedSlot = "%list.add.typed." + tmpCount++;
            body.append("  ").append(typedSlot).append(" = bitcast i8* ").append(slot).append(" to ").append(elemLLVM).append("*\n");
            String casted = castValue(value, elemLLVM);
            body.append("  store ").append(elemLLVM).append(" ").append(casted).append(", ").append(elemLLVM).append("* ").append(typedSlot).append("\n");
            String next = "%list.add.next." + tmpCount++;
            body.append("  ").append(next).append(" = add i64 ").append(size).append(", 1\n");
            body.append("  store i64 ").append(next).append(", i64* ").append(sizePtr).append("\n");
            body.append("  br label %").append(done).append("\n");
            body.append(done).append(":\n");
            String indexResult = "%list.add.index." + tmpCount++;
            body.append("  ").append(indexResult).append(" = trunc i64 ").append(size).append(" to i32\n");
            return new LLVMValue(indexResult, "i32");
        }
        if (method.equals("remove") && args.size() == 1) return generateListRemove(list, elemCang, args.get(0), line);
        if (method.equals("contains") && args.size() == 1) return generateListContains(list, elemCang, args.get(0), line);
        if (method.equals("clear") && args.isEmpty()) {
            body.append("  store i64 0, i64* ").append(sizePtr).append("\n");
            return new LLVMValue("void", "void");
        }
        throw new RuntimeException("Unknown List method or argument count: " + method + " (at line " + line + ")");
    }

    private LLVMValue generateListRemove(LLVMValue list, String elemCang, AST indexNode, int line) {
        String elemLLVM = toLLVMType(elemCang);
        String index = "%list.remove.index." + tmpCount++;
        body.append("  ").append(index).append(" = sext i32 ").append(castValue(generateExpr(indexNode), "i32")).append(" to i64\n");
        String sizePtr = "%list.remove.sizeptr." + tmpCount++;
        String dataPtr = "%list.remove.dataptr." + tmpCount++;
        body.append("  ").append(sizePtr).append(" = getelementptr %CangList, %CangList* ").append(list.value).append(", i32 0, i32 1\n");
        body.append("  ").append(dataPtr).append(" = getelementptr %CangList, %CangList* ").append(list.value).append(", i32 0, i32 0\n");
        String size = "%list.remove.size." + tmpCount++;
        body.append("  ").append(size).append(" = load i64, i64* ").append(sizePtr).append("\n");
        String check = "%list.remove.check." + tmpCount++;
        String bad = "list.remove.bad." + tmpCount++;
        String ok = "list.remove.ok." + tmpCount++;
        body.append("  ").append(check).append(" = icmp ult i64 ").append(index).append(", ").append(size).append("\n");
        body.append("  br i1 ").append(check).append(", label %").append(ok).append(", label %").append(bad).append("\n").append(bad).append(":\n");
        emitRuntimeError("List index out of bounds", line, ok);
        body.append(ok).append(":\n");
        String data = "%list.remove.data." + tmpCount++;
        body.append("  ").append(data).append(" = load i8*, i8** ").append(dataPtr).append("\n");
        long bytes = llvmTypeBits(elemLLVM) <= 0 ? 8 : (llvmTypeBits(elemLLVM) + 7) / 8;
        String offset = "%list.remove.offset." + tmpCount++;
        body.append("  ").append(offset).append(" = mul i64 ").append(index).append(", ").append(bytes).append("\n");
        String oldPtr = "%list.remove.oldptr." + tmpCount++;
        String oldTyped = "%list.remove.oldtyped." + tmpCount++;
        body.append("  ").append(oldPtr).append(" = getelementptr i8, i8* ").append(data).append(", i64 ").append(offset).append("\n");
        body.append("  ").append(oldTyped).append(" = bitcast i8* ").append(oldPtr).append(" to ").append(elemLLVM).append("*\n");
        String removed = "%list.removed." + tmpCount++;
        body.append("  ").append(removed).append(" = load ").append(elemLLVM).append(", ").append(elemLLVM).append("* ").append(oldTyped).append("\n");
        String loop = "list.remove.loop." + labelCount++;
        String copy = "list.remove.copy." + labelCount++;
        String done = "list.remove.done." + labelCount++;
        String iPtr = "%list.remove.iptr." + tmpCount++;
        body.append("  ").append(iPtr).append(" = alloca i64\n  store i64 ").append(index).append(", i64* ").append(iPtr).append("\n  br label %").append(loop).append("\n").append(loop).append(":\n");
        String i = "%list.remove.i." + tmpCount++;
        String next = "%list.remove.next." + tmpCount++;
        String hasNext = "%list.remove.hasnext." + tmpCount++;
        body.append("  ").append(i).append(" = load i64, i64* ").append(iPtr).append("\n  ").append(next).append(" = add i64 ").append(i).append(", 1\n  ").append(hasNext).append(" = icmp ult i64 ").append(next).append(", ").append(size).append("\n  br i1 ").append(hasNext).append(", label %").append(copy).append(", label %").append(done).append("\n").append(copy).append(":\n");
        String srcOff = "%list.remove.srcoff." + tmpCount++;
        String dstOff = "%list.remove.dstoff." + tmpCount++;
        body.append("  ").append(srcOff).append(" = mul i64 ").append(next).append(", ").append(bytes).append("\n  ").append(dstOff).append(" = mul i64 ").append(i).append(", ").append(bytes).append("\n");
        String srcPtr = "%list.remove.src." + tmpCount++;
        String dstPtr = "%list.remove.dst." + tmpCount++;
        body.append("  ").append(srcPtr).append(" = getelementptr i8, i8* ").append(data).append(", i64 ").append(srcOff).append("\n  ").append(dstPtr).append(" = getelementptr i8, i8* ").append(data).append(", i64 ").append(dstOff).append("\n");
        String srcTyped = "%list.remove.srctyped." + tmpCount++;
        String dstTyped = "%list.remove.dsttyped." + tmpCount++;
        body.append("  ").append(srcTyped).append(" = bitcast i8* ").append(srcPtr).append(" to ").append(elemLLVM).append("*\n  ").append(dstTyped).append(" = bitcast i8* ").append(dstPtr).append(" to ").append(elemLLVM).append("*\n");
        String copied = "%list.remove.copied." + tmpCount++;
        body.append("  ").append(copied).append(" = load ").append(elemLLVM).append(", ").append(elemLLVM).append("* ").append(srcTyped).append("\n  store ").append(elemLLVM).append(" ").append(copied).append(", ").append(elemLLVM).append("* ").append(dstTyped).append("\n  store i64 ").append(next).append(", i64* ").append(iPtr).append("\n  br label %").append(loop).append("\n").append(done).append(":\n");
        String newSize = "%list.remove.newsize." + tmpCount++;
        body.append("  ").append(newSize).append(" = sub i64 ").append(size).append(", 1\n  store i64 ").append(newSize).append(", i64* ").append(sizePtr).append("\n");
        return new LLVMValue(removed, elemLLVM, elemCang);
    }

    private LLVMValue generateListContains(LLVMValue list, String elemCang, AST targetNode, int line) {
        String elemLLVM = toLLVMType(elemCang);
        LLVMValue target = generateCallArg(targetNode, elemCang, line);
        String dataPtr = "%list.contains.dataptr." + tmpCount++;
        String sizePtr = "%list.contains.sizeptr." + tmpCount++;
        body.append("  ").append(dataPtr).append(" = getelementptr %CangList, %CangList* ").append(list.value).append(", i32 0, i32 0\n  ").append(sizePtr).append(" = getelementptr %CangList, %CangList* ").append(list.value).append(", i32 0, i32 1\n");
        String data = "%list.contains.data." + tmpCount++;
        String size = "%list.contains.size." + tmpCount++;
        body.append("  ").append(data).append(" = load i8*, i8** ").append(dataPtr).append("\n  ").append(size).append(" = load i64, i64* ").append(sizePtr).append("\n");
        String foundPtr = "%list.contains.foundptr." + tmpCount++;
        String indexPtr = "%list.contains.indexptr." + tmpCount++;
        body.append("  ").append(foundPtr).append(" = alloca i1\n  store i1 0, i1* ").append(foundPtr).append("\n  ").append(indexPtr).append(" = alloca i64\n  store i64 0, i64* ").append(indexPtr).append("\n");
        int id = labelCount++;
        String loop = "list.contains.loop." + id;
        String check = "list.contains.check." + id;
        String done = "list.contains.done." + id;
        body.append("  br label %").append(loop).append("\n").append(loop).append(":\n");
        String index = "%list.contains.index." + tmpCount++;
        String active = "%list.contains.active." + tmpCount++;
        body.append("  ").append(index).append(" = load i64, i64* ").append(indexPtr).append("\n  ").append(active).append(" = icmp ult i64 ").append(index).append(", ").append(size).append("\n  br i1 ").append(active).append(", label %").append(check).append(", label %").append(done).append("\n").append(check).append(":\n");
        long bytes = llvmTypeBits(elemLLVM) <= 0 ? 8 : (llvmTypeBits(elemLLVM) + 7) / 8;
        String off = "%list.contains.off." + tmpCount++;
        String slot = "%list.contains.slot." + tmpCount++;
        String typed = "%list.contains.typed." + tmpCount++;
        body.append("  ").append(off).append(" = mul i64 ").append(index).append(", ").append(bytes).append("\n  ").append(slot).append(" = getelementptr i8, i8* ").append(data).append(", i64 ").append(off).append("\n  ").append(typed).append(" = bitcast i8* ").append(slot).append(" to ").append(elemLLVM).append("*\n");
        String current = "%list.contains.current." + tmpCount++;
        body.append("  ").append(current).append(" = load ").append(elemLLVM).append(", ").append(elemLLVM).append("* ").append(typed).append("\n");
        String equal = "%list.contains.equal." + tmpCount++;
        if (elemLLVM.equals("i8*")) {
            String cmp = "%list.contains.strcmp." + tmpCount++;
            body.append("  ").append(cmp).append(" = call i32 @strcmp(i8* ").append(current).append(", i8* ").append(target.value).append(")\n  ").append(equal).append(" = icmp eq i32 ").append(cmp).append(", 0\n");
        } else {
            body.append("  ").append(equal).append(" = icmp eq ").append(elemLLVM).append(" ").append(current).append(", ").append(castValue(target, elemLLVM)).append("\n");
        }
        String next = "%list.contains.next." + tmpCount++;
        body.append("  ").append(next).append(" = add i64 ").append(index).append(", 1\n  store i64 ").append(next).append(", i64* ").append(indexPtr).append("\n");
        String found = "%list.contains.found." + tmpCount++;
        body.append("  ").append(found).append(" = load i1, i1* ").append(foundPtr).append("\n");
        String finalFound = "%list.contains.final." + tmpCount++;
        body.append("  ").append(finalFound).append(" = or i1 ").append(found).append(", ").append(equal).append("\n  store i1 ").append(finalFound).append(", i1* ").append(foundPtr).append("\n");
        body.append("  br label %").append(loop).append("\n").append(done).append(":\n");
        String result = "%list.contains.result." + tmpCount++;
        body.append("  ").append(result).append(" = load i1, i1* ").append(foundPtr).append("\n");
        return new LLVMValue(result, "i1");
    }

    private LLVMValue generateMethodCall(MethodCallExpr node) {
        markRegionControlFlow();
        LLVMValue objCache = null;
        // Thread<T> is a compiler intrinsic. Phase one intentionally targets Windows/MinGW.
        if (node.object instanceof Identifier && ((Identifier) node.object).name.equals("Thread")
                && node.method.equals("spawn")) {
            // Thread.spawn was removed — the object-style API is the only explicit form.
            // (thread { } still uses generateThreadSpawn internally through a direct call.)
            throw new RuntimeException("Thread.spawn has been removed; use new Thread().task(fn).start() (at line "
                + node.line + ")");
        }
        // Object-style Thread: task(fn) / start() on new Thread() results (exact type gate so
        // unrelated classes' task/start methods fall through to the normal instance path).
        if (node.object != null && (node.method.equals("task") || node.method.equals("start"))) {
            objCache = generateExpr(node.object);
            if (objCache.type.equals("%CangThreadObj*")) {
                if (node.method.equals("task")) return generateThreadObjTask(objCache, node);
                return generateThreadObjStart(objCache, node);
            }
        }
        // Receiver is generated at most once (debug.md #15): the join probe, the array-length
        // probe and the regular instance path share the same value.
        if (objCache == null && node.object != null && node.method.equals("join")) {
            objCache = generateExpr(node.object);
            LLVMValue receiver = objCache;
            if (receiver.semanticType != null && isThreadType(receiver.semanticType)) {
                if (!node.args.isEmpty()) throw new RuntimeException("Thread.join accepts no arguments (at line " + node.line + ")");
                if (receiver.type.equals("%CangThreadObj*")) {
                    receiver = threadObjHandle(receiver, node.line);
                }
                return generateThreadJoin(receiver, node.line);
            }
        }

        // Handle built-in Stdout.print
        if (node.object instanceof Identifier) {
            String objName = ((Identifier) node.object).name;
            if (objName.equals("Stdout") && node.method.equals("print")) {
                return generateStdoutPrint(node.args, false);
            }
            if (objName.equals("Stdout") && node.method.equals("println")) {
                return generateStdoutPrint(node.args, true);
            }
            if (objName.equals("Stderr") && node.method.equals("print")) {
                return generateStdoutPrint(node.args, false, true);
            }
            if (objName.equals("Stderr") && node.method.equals("println")) {
                return generateStdoutPrint(node.args, true, true);
            }
            if (objName.equals("Math")) {
                return generateMathCall(node.method, node.args, node.line);
            }
            if (objName.equals("System")) {
                return generateSystemCall(node.method, node.args, node.line);
            }
            // File.separator() method-call form (field form handled in generateFieldAccess)
            if (objName.equals("File") && node.method.equals("separator") && node.args.isEmpty()
                    && classes.containsKey("cang_io_File")) {
                return makeStringConstant(targetPlatform.equals("windows") ? "\\" : "/");
            }
        }

        // Arrays expose length() as a built-in method. The length is stored in the i64 header.
        if (node.method.equals("length") && node.args.isEmpty()) {
            if (objCache == null) objCache = generateExpr(node.object);
            LLVMValue arrayValue = objCache;
            if (arrayValue.semanticType != null && isArraySemanticType(arrayValue.semanticType)) {
                String length64 = "%arr.length." + tmpCount++;
                String length32 = "%arr.length.i32." + tmpCount++;
                body.append("  ").append(length64).append(" = load i64, i64* ")
                     .append(arrayValue.value).append("\n");
                body.append("  ").append(length32).append(" = trunc i64 ").append(length64).append(" to i32\n");
                return new LLVMValue(length32, "i32");
            }
        }

        // Static method call: ClassName.method(...)
        if (node.object instanceof Identifier) {
            String objName = ((Identifier) node.object).name;
            if (!classes.containsKey(objName) && ambiguousShortNames.containsKey(objName)) {
                throw unknownClassError(objName, node.line);
            }
            if (classes.containsKey(objName)) {
                String classFullName = classes.get(objName).fullName;
                String funcName = classFullName + "." + node.method;
                FuncInfo fi = resolveOverload(overloadGroups.get(funcName), node.args, node.line);
                if (fi != null && fi.isStatic) {
                    return generateStaticCall(fi, funcName, node.args, node.line);
                }
                // Class exists but method is not static or not found
                if (fi != null && !fi.isStatic) {
                    throw new RuntimeException("Method '" + node.method + "' in '" + objName + "' is not static, cannot call via class name (at line " + node.line + ")");
                }
                if (fi == null) {
                    throw new RuntimeException("Unknown method: " + objName + "." + node.method + " (at line " + node.line + ")");
                }
            }
        }

        // Standalone function call (object is null)
        if (node.object == null) {
            LLVMValue callableSlot = scope.lookup(node.method);
            if (callableSlot != null && callableSlot.type.equals("%CangFunction")) {
                LLVMValue callable = generateExpr(new Identifier(node.method, node.line));
                return generateIndirectCall(callable, node.args, node.line);
            }
            FuncInfo fi = resolveOverload(overloadGroups.get(node.method), node.args, node.line);
            if (fi == null) throw new RuntimeException("Unknown function: " + node.method);

            StringBuilder args = new StringBuilder();
            int argCount = node.args.size();
            int paramCount = fi.paramTypes.size();

            for (int i = 0; i < paramCount; i++) {
                if (i > 0) args.append(", ");
                String paramType = fi.paramTypes.get(i);
                String llvmParamType = toLLVMType(paramType);

                if (i < argCount && !(node.args.get(i) instanceof VoidPlaceholder)) {
                    // Provided argument
                    LLVMValue argVal = generateCallArg(node.args.get(i), paramType, node.line);
                    args.append(llvmParamType).append(" ").append(castValue(argVal, llvmParamType));
                } else {
                    // Use default value (either void placeholder or omitted)
                    AST defaultVal = fi.paramDefaults.get(i);
                    if (defaultVal != null) {
                        LLVMValue defVal = generateExpr(defaultVal);
                        args.append(llvmParamType).append(" ").append(castValue(defVal, llvmParamType));
                    } else {
                        args.append(llvmParamType).append(" ").append(defaultValueForType(paramType));
                    }
                }
            }

            if (fi.returnType.equals("void")) {
                body.append("  call void @").append(fi.llvmName).append("(").append(args).append(")\n");
                return new LLVMValue("void", "void");
            } else {
                String result = "%call." + tmpCount++;
                body.append("  ").append(result).append(" = call ").append(toLLVMType(fi.returnType))
                     .append(" @").append(fi.llvmName).append("(").append(args).append(")\n");
                String retSem = (callResultCarriesSemantic(fi.returnType))
                    ? fi.returnType : null;
                return new LLVMValue(result, toLLVMType(fi.returnType), retSem);
            }
        }

        // Regular method call
        if (objCache == null) objCache = generateExpr(node.object);
        LLVMValue objVal = objCache;
        if ("str".equals(objVal.semanticType)) {
            throw new RuntimeException("str is a primitive type and has no methods (at line " + node.line + ")");
        }

        // Null pointer check for object method calls
        if (objVal.type.equals("i8*") || (objVal.type.startsWith("%") && objVal.type.endsWith("*"))) {
            int id = labelCount++;
            String notNullLabel = "nonnull." + id;
            String nullLabel = "isnull." + id;
            String isNull = "%null." + tmpCount++;
            body.append("  ").append(isNull).append(" = icmp eq ").append(objVal.type)
                 .append(" ").append(objVal.value).append(", null\n");
            body.append("  br i1 ").append(isNull).append(", label %").append(nullLabel)
                 .append(", label %").append(notNullLabel).append("\n\n");

            // Null handler: raise a catchable Error, then fall through to the not-null path label.
            body.append(nullLabel).append(":\n");
            emitRuntimeError("Null pointer dereference", node.line, notNullLabel);

            body.append(notNullLabel).append(":\n");
        }

        String className = extractClassName(objVal.type);
        String funcName = className + "." + node.method;

        List<FuncInfo> cands = overloadGroups.get(funcName);
        if (cands == null || cands.isEmpty()) {
            // Try with namespace prefix
            ClassInfo ci = classes.get(className);
            if (ci != null && !ci.fullName.equals(className)) {
                funcName = ci.fullName + "." + node.method;
                cands = overloadGroups.get(funcName);
            }
        }
        // Walk up parent chain for inherited methods
        if (cands == null || cands.isEmpty()) {
            ClassInfo ci = classes.get(className);
            while (ci != null && ci.parentName != null) {
                ClassInfo parent = classes.get(ci.parentName);
                if (parent == null) break;
                funcName = parent.fullName + "." + node.method;
                cands = overloadGroups.get(funcName);
                if (cands != null && !cands.isEmpty()) break;
                ci = parent;
            }
        }
        if (cands == null || cands.isEmpty()) throw new RuntimeException("Unknown method: " + funcName + " (at line " + node.line + ")");
        FuncInfo fi = resolveOverload(cands, node.args, node.line);

        // Private access check: _ prefix methods only accessible within same class
        if (node.method.startsWith("_")) {
            ClassInfo ownerClass = classes.get(className);
            String ownerName = ownerClass != null ? ownerClass.simpleName : className;
            if (!isSameOrParentClass(currentClassName, ownerName)) {
                String context = currentClassName != null ? currentClassName : "top-level code";
                throw new RuntimeException("Method '" + node.method + "' is private and cannot be accessed from '" + context + "' (at line " + node.line + ")");
            }
        }

        // Check if polymorphic dispatch is needed
        ClassInfo objClass = classes.get(className);
        if (objClass != null) {
            boolean overridden = isMethodOverridden(objClass, node.method);
            if (overridden) {
                return generateDynamicDispatch(objVal, node, fi, className);
            }
        }

        StringBuilder args = new StringBuilder();
        args.append(objVal.type).append(" ").append(objVal.value);
        for (AST arg : node.args) {
            String paramType = fi.paramTypes.get(node.args.indexOf(arg));
            LLVMValue argVal = generateCallArg(arg, paramType, node.line);
            args.append(", ").append(toLLVMType(paramType)).append(" ")
                .append(castValue(argVal, toLLVMType(paramType)));
        }

        if (fi.returnType.equals("void")) {
            body.append("  call void @").append(fi.llvmName).append("(").append(args).append(")\n");
            return new LLVMValue("void", "void");
        } else {
            String result = "%call." + tmpCount++;
            body.append("  ").append(result).append(" = call ").append(toLLVMType(fi.returnType))
                 .append(" @").append(fi.llvmName).append("(").append(args).append(")\n");
            // Array-returning calls (File.readLines/list) expose their Array<T> semantic type
            // so chained .length() and var-decl element tracking work; Function returns need
            // their signature semantic for checkFunctionValue.
            String retSem = (callResultCarriesSemantic(fi.returnType))
                ? fi.returnType : null;
            return new LLVMValue(result, toLLVMType(fi.returnType), retSem);
        }
    }

    private boolean isThreadType(String type) {
        return type != null && type.startsWith("Thread<") && type.endsWith(">") && type.length() > 8;
    }

    private String threadResultType(String type) {
        return type.substring(7, type.length() - 1);
    }

    /** Value types allowed as thread entry return/parameter types (no objects/arrays/Function). */
    private static boolean isThreadValueType(String t) {
        return t.equals("void") || t.equals("int") || t.equals("long") || t.equals("float")
            || t.equals("double") || t.equals("bool") || t.equals("byte")
            || t.equals("String") || t.equals("str");
    }

    private LLVMValue generateThreadSpawn(MethodCallExpr node) {
        if (node.args.isEmpty()) throw new RuntimeException("Thread.spawn requires a non-capturing ordinary function identifier (at line " + node.line + ")");
        AST entry = node.args.get(0);
        if (!(entry instanceof Identifier)) {
            throw new RuntimeException("Thread.spawn requires a non-capturing ordinary function identifier; capturing lambda and Function receiver are not supported (at line " + node.line + ")");
        }
        String entryName = ((Identifier) entry).name;
        // Same-signature overload resolution on the entry args (arity alone would pick the
        // first declared — wrong target for a by-arity-matched thread entry).
        List<AST> entryArgs = node.args.subList(1, node.args.size());
        FuncInfo ordinary = overloadGroups.containsKey(entryName)
            ? resolveOverload(overloadGroups.get(entryName), entryArgs, node.line)
            : functions.get(entryName);
        if (ordinary == null || ordinary.className != null || ordinary.isStatic) {
            throw new RuntimeException("Thread.spawn requires a top-level ordinary function identifier (at line " + node.line + ")");
        }
        if (!isThreadValueType(ordinary.returnType)) {
            throw new RuntimeException("Thread result type must be void or a value type (int/long/float/double/bool/byte/String); objects and arrays cannot cross threads (at line " + node.line + ")");
        }
        for (String p : ordinary.paramTypes) {
            if (p.equals("void") || !isThreadValueType(p)) {
                throw new RuntimeException("Thread parameters must be value types (int/long/float/double/bool/byte/String); objects and arrays cannot cross threads (at line " + node.line + ")");
            }
        }
        if (node.args.size() - 1 != ordinary.paramTypes.size()) {
            throw new RuntimeException("Thread.spawn argument count mismatch: expected " + ordinary.paramTypes.size() + " but found " + (node.args.size() - 1) + " (at line " + node.line + ")");
        }

        boolean isVoid = ordinary.returnType.equals("void");

        // Pack layout: { fn ptr, args..., [i8* resultSlot] }
        StringBuilder st = new StringBuilder("{ i8*");
        for (String p : ordinary.paramTypes) st.append(", ").append(toLLVMType(p));
        if (!isVoid) st.append(", i8*");
        st.append(" }");
        String startType = st.toString();

        List<LLVMValue> values = new ArrayList<>();
        for (int i = 1; i < node.args.size(); i++) {
            values.add(generateCallArg(node.args.get(i), ordinary.paramTypes.get(i - 1), node.line));
        }

        String raw = "%thread.raw." + tmpCount++;
        body.append("  ").append(raw).append(" = call i8* @").append(allocFn()).append("(i64 256)\n");
        String start = "%thread.start." + tmpCount++;
        body.append("  ").append(start).append(" = bitcast i8* ").append(raw).append(" to ").append(startType).append("*\n");
        String fp = "%thread.fp." + tmpCount++;
        body.append("  ").append(fp).append(" = getelementptr ").append(startType).append(", ").append(startType).append("* ").append(start).append(", i32 0, i32 0\n");
        body.append("  store i8* bitcast (").append(functionPointerType(ordinary)).append(" @").append(symOf(ordinary)).append(" to i8*), i8** ").append(fp).append("\n");
        for (int i = 0; i < values.size(); i++) {
            String paramLLVM = toLLVMType(ordinary.paramTypes.get(i));
            String ap = "%thread.ap." + tmpCount++;
            body.append("  ").append(ap).append(" = getelementptr ").append(startType).append(", ").append(startType).append("* ").append(start).append(", i32 0, i32 ").append(i + 1).append("\n");
            body.append("  store ").append(paramLLVM).append(" ").append(castValue(values.get(i), paramLLVM)).append(", ").append(paramLLVM).append("* ").append(ap).append("\n");
        }
        String rpRaw = "null";
        if (!isVoid) {
            String resultLLVM = toLLVMType(ordinary.returnType);
            long bits = llvmTypeBits(resultLLVM);
            long slotBytes = bits <= 0 ? 8 : Math.max(8, (bits + 7) / 8);
            rpRaw = "%thread.result.raw." + tmpCount++;
            body.append("  ").append(rpRaw).append(" = call i8* @").append(allocFn()).append("(i64 ").append(slotBytes).append(")\n");
            String rfield = "%thread.rfield." + tmpCount++;
            int resultIndex = 1 + values.size();
            body.append("  ").append(rfield).append(" = getelementptr ").append(startType).append(", ").append(startType).append("* ").append(start).append(", i32 0, i32 ").append(resultIndex).append("\n");
            body.append("  store i8* ").append(rpRaw).append(", i8** ").append(rfield).append("\n");
        }
        String handleRaw = "%thread.handle.raw." + tmpCount++;
        body.append("  ").append(handleRaw).append(" = call i8* @").append(allocFn()).append("(i64 24)\n");
        String handle = "%thread.handle." + tmpCount++;
        body.append("  ").append(handle).append(" = bitcast i8* ").append(handleRaw).append(" to %CangThreadHandle*\n");
        String tid = "%thread.id." + tmpCount++;
        body.append("  ").append(tid).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle).append(", i32 0, i32 0\n");
        String rid = "%thread.rid." + tmpCount++;
        body.append("  ").append(rid).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle).append(", i32 0, i32 1\n");
        body.append("  store i8* ").append(rpRaw).append(", i8** ").append(rid).append("\n");
        String joined = "%thread.joined." + tmpCount++;
        body.append("  ").append(joined).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle).append(", i32 0, i32 2\n");
        body.append("  store i32 0, i32* ").append(joined).append("\n");

        emitThreadEntry(ordinary, startType, isVoid, threadCount);
        emitThreadCreate(raw, tid, node.line);

        threadCount++;
        return new LLVMValue(handle, "%CangThreadHandle*", "Thread<" + ordinary.returnType + ">");
    }

    /** Create the native thread running @cang.thread.entry.<threadCount> with pack raw;
     *  writes the OS thread id into tidPtr (i64*). Bumps threadCount on success path. */
    private void emitThreadCreate(String raw, String tidPtr, int line) {
        if (targetPlatform.equals("windows")) {
            String entryFn = "@cang.thread.entry." + threadCount;
            String createFn = gcEnabled ? "@GC_CreateThread" : "@CreateThread";
            String created = "%thread.created." + tmpCount++;
            body.append("  ").append(created).append(" = call i8* ").append(createFn).append("(i8* null, i64 0, i32 (i8*)* ").append(entryFn).append(", i8* ").append(raw).append(", i32 0, i32* null)\n");
            String isNull = "%thread.isnull." + tmpCount++;
            String bad = "thread.create.bad." + threadCount;
            String ok = "thread.create.ok." + threadCount;
            body.append("  ").append(isNull).append(" = icmp eq i8* ").append(created).append(", null\n");
            body.append("  br i1 ").append(isNull).append(", label %").append(bad).append(", label %").append(ok).append("\n");
            body.append(bad).append(":\n");
            emitFatalError("Thread creation failed", line, ok);
            body.append(ok).append(":\n");
            String tid32 = "%thread.id32." + tmpCount++;
            body.append("  ").append(tid32).append(" = ptrtoint i8* ").append(created).append(" to i64\n");
            body.append("  store i64 ").append(tid32).append(", i64* ").append(tidPtr).append("\n");
        } else {
            String entryFn = "@cang.thread.entry." + threadCount;
            String ptidSlot = "%thread.ptid.slot." + tmpCount++;
            String ptid = "%thread.ptid." + tmpCount++;
            body.append("  ").append(ptidSlot).append(" = alloca i64\n");
            body.append("  ").append(ptid).append(" = bitcast i64* ").append(ptidSlot).append(" to i8**\n");
            String rc = "%thread.rc." + tmpCount++;
            body.append("  ").append(rc).append(" = call i32 @pthread_create(i8** ").append(ptid).append(", i8* null, i8* (i8*)* ").append(entryFn).append(", i8* ").append(raw).append(")\n");
            String failed = "%thread.failed." + tmpCount++;
            String bad = "thread.create.bad." + threadCount;
            String ok = "thread.create.ok." + threadCount;
            body.append("  ").append(failed).append(" = icmp ne i32 ").append(rc).append(", 0\n");
            body.append("  br i1 ").append(failed).append(", label %").append(bad).append(", label %").append(ok).append("\n");
            body.append(bad).append(":\n");
            emitFatalError("Thread creation failed", line, ok);
            body.append(ok).append(":\n");
            String tidVal = "%thread.ptid.load." + tmpCount++;
            body.append("  ").append(tidVal).append(" = load i64, i64* ").append(ptidSlot).append("\n");
            body.append("  store i64 ").append(tidVal).append(", i64* ").append(tidPtr).append("\n");
        }
    }

    private void emitThreadEntry(FuncInfo ordinary, String startType, boolean isVoid, int id) {
        String retLLVM = toLLVMType(ordinary.returnType);
        boolean posix = !targetPlatform.equals("windows");
        StringBuilder x = new StringBuilder();
        x.append("define ").append(posix ? "i8*" : "i32")
         .append(" @cang.thread.entry.").append(id).append("(i8* %arg) {\nentry:\n");
        // Per-thread stack base: the thread's own stack top (debug.md #4).
        emitStackBaseSet(x, "th." + id);

        // Register this native thread with Boehm GC before any GC-managed allocation.
        // On Windows, GC_CreateThread attaches the thread itself; posix threads register here.
        String sb = "%gc.sb." + id;
        String sb8 = "%gc.sb8." + id;
        boolean registerWithGc = gcEnabled && !targetPlatform.equals("windows");
        if (registerWithGc) {
            x.append("  ").append(sb).append(" = alloca [64 x i8]\n");
            x.append("  ").append(sb8).append(" = getelementptr [64 x i8], [64 x i8]* ").append(sb).append(", i32 0, i32 0\n");
            x.append("  call void @llvm.memset.p0i8.p0i8.i64(i8* ").append(sb8).append(", i8 0, i64 64, i1 false)\n");
            x.append("  %gc.sbres1.").append(id).append(" = call i32 @GC_get_stack_base(i8* ").append(sb8).append(")\n");
            x.append("  %gc.sbres2.").append(id).append(" = call i32 @GC_register_my_thread(i8* ").append(sb8).append(")\n");
        }

        x.append("  %s = bitcast i8* %arg to ").append(startType).append("*\n");
        x.append("  %f = getelementptr ").append(startType).append(", ").append(startType).append("* %s, i32 0, i32 0\n");
        x.append("  %code = load i8*, i8** %f\n");

        StringBuilder fnType = new StringBuilder(toLLVMType(ordinary.returnType)).append(" (");
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < ordinary.paramTypes.size(); i++) {
            String llvmType = toLLVMType(ordinary.paramTypes.get(i));
            if (i > 0) { fnType.append(", "); args.append(", "); }
            fnType.append(llvmType);
            String p = "%p" + (i + 1);
            x.append("  ").append(p).append("p = getelementptr ").append(startType).append(", ").append(startType)
             .append("* %s, i32 0, i32 ").append(i + 1).append("\n");
            x.append("  ").append(p).append(" = load ").append(llvmType).append(", ").append(llvmType).append("* ").append(p).append("p\n");
            args.append(llvmType).append(" ").append(p);
        }
        fnType.append(")*");
        x.append("  %fp = bitcast i8* %code to ").append(fnType).append("\n");
        if (isVoid) {
            x.append("  call void %fp(").append(args).append(")\n");
        } else {
            x.append("  %r = call ").append(retLLVM).append(" %fp(").append(args).append(")\n");
            int ri = 1 + ordinary.paramTypes.size();
            x.append("  %rp = getelementptr ").append(startType).append(", ").append(startType).append("* %s, i32 0, i32 ").append(ri).append("\n");
            x.append("  %rpp = load i8*, i8** %rp\n");
            x.append("  %rpt = bitcast i8* %rpp to ").append(retLLVM).append("*\n");
            x.append("  store ").append(retLLVM).append(" %r, ").append(retLLVM).append("* %rpt\n");
        }
        if (registerWithGc) {
            x.append("  %gc.sbres3.").append(id).append(" = call i32 @GC_unregister_my_thread(i8* ").append(sb8).append(")\n");
        }
        x.append("  call void @").append(freeFn()).append("(i8* %arg)\n");
        if (posix) x.append("  ret i8* null\n}\n\n");
        else x.append("  ret i32 0\n}\n\n");
        extraDefs.append(x);
    }

    /** Object-style .task(fn): validate Function<void>, store { code, receiver }; returns this. */
    private LLVMValue generateThreadObjTask(LLVMValue receiver, MethodCallExpr node) {
        if (node.args.size() != 1) {
            throw new RuntimeException("Thread.task accepts exactly one function argument (at line " + node.line + ")");
        }
        if (receiver.semanticType == null || !isThreadType(receiver.semanticType)) {
            throw new RuntimeException("Thread.task must be called on new Thread() (at line " + node.line + ")");
        }
        // Task takes a no-arg no-return function — give lambdas the expectation they infer from.
        String savedTaskExpected = expectedFunctionType;
        expectedFunctionType = "Function<void>";
        LLVMValue fn;
        try {
            fn = generateExpr(node.args.get(0));
        } finally {
            expectedFunctionType = savedTaskExpected;
        }
        String sig = fn.semanticType;
        if (sig == null || !isFunctionType(sig)) {
            throw new RuntimeException("Thread.task requires a no-argument no-return function, e.g. this::run or a lambda (at line " + node.line + ")");
        }
        String[] parts = functionTypeParts(sig);
        if (parts.length != 1 || !parts[0].equals("void")) {
            throw new RuntimeException("Thread.task requires a no-argument no-return function (Function<void>), got " + sig + " (at line " + node.line + ")");
        }
        if (!fn.type.equals("%CangFunction")) {
            throw new RuntimeException("Thread.task requires a method reference, function name or lambda (at line " + node.line + ")");
        }
        String code = "%thr.task.code." + tmpCount++;
        body.append("  ").append(code).append(" = extractvalue %CangFunction ").append(fn.value).append(", 0\n");
        String recv = "%thr.task.recv." + tmpCount++;
        body.append("  ").append(recv).append(" = extractvalue %CangFunction ").append(fn.value).append(", 1\n");
        String cfield = "%thr.task.cf." + tmpCount++;
        body.append("  ").append(cfield).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
             .append(", i32 0, i32 0\n");
        body.append("  store i8* ").append(code).append(", i8** ").append(cfield).append("\n");
        String rfield = "%thr.task.rf." + tmpCount++;
        body.append("  ").append(rfield).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
             .append(", i32 0, i32 1\n");
        body.append("  store i8* ").append(recv).append(", i8** ").append(rfield).append("\n");
        return receiver;
    }

    /** Object-style .start(): pack { code, receiver }, create the thread, store the handle;
     *  returns the handle (Thread<void>) so .join() chains. */
    private LLVMValue generateThreadObjStart(LLVMValue receiver, MethodCallExpr node) {
        if (!node.args.isEmpty()) {
            throw new RuntimeException("Thread.start accepts no arguments (at line " + node.line + ")");
        }
        String cfield = "%thr.start.cf." + tmpCount++;
        body.append("  ").append(cfield).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
             .append(", i32 0, i32 0\n");
        String code = "%thr.start.code." + tmpCount++;
        body.append("  ").append(code).append(" = load i8*, i8** ").append(cfield).append("\n");
        String rfield = "%thr.start.rf." + tmpCount++;
        body.append("  ").append(rfield).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
             .append(", i32 0, i32 1\n");
        String recv = "%thr.start.recv." + tmpCount++;
        body.append("  ").append(recv).append(" = load i8*, i8** ").append(rfield).append("\n");
        String hfield = "%thr.start.hf." + tmpCount++;
        body.append("  ").append(hfield).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
             .append(", i32 0, i32 2\n");
        String oldHandle = "%thr.start.old." + tmpCount++;
        body.append("  ").append(oldHandle).append(" = load i8*, i8** ").append(hfield).append("\n");

        int id = labelCount++;
        String noTask = "thr.start.notask." + id;
        String taskOk = "thr.start.taskok." + id;
        String noTaskCmp = "%thr.start.notask.cmp." + tmpCount++;
        body.append("  ").append(noTaskCmp).append(" = icmp eq i8* ").append(code).append(", null\n");
        body.append("  br i1 ").append(noTaskCmp).append(", label %").append(noTask).append(", label %").append(taskOk).append("\n");
        body.append(noTask).append(":\n");
        emitRuntimeError("Thread has no task; call task() before start", node.line, taskOk);
        body.append(taskOk).append(":\n");

        String already = "thr.start.already." + id;
        String startOk = "thr.start.ok." + id;
        String alreadyCmp = "%thr.start.already.cmp." + tmpCount++;
        body.append("  ").append(alreadyCmp).append(" = icmp ne i8* ").append(oldHandle).append(", null\n");
        body.append("  br i1 ").append(alreadyCmp).append(", label %").append(already).append(", label %").append(startOk).append("\n");
        body.append(already).append(":\n");
        emitRuntimeError("Thread already started", node.line, startOk);
        body.append(startOk).append(":\n");

        // Pack { i8* code, i8* receiver }
        String startType = "{ i8*, i8* }";
        String raw = "%thr.start.raw." + tmpCount++;
        body.append("  ").append(raw).append(" = call i8* @").append(allocFn()).append("(i64 16)\n");
        String start = "%thr.start.pack." + tmpCount++;
        body.append("  ").append(start).append(" = bitcast i8* ").append(raw).append(" to ").append(startType).append("*\n");
        String p0 = "%thr.start.p0." + tmpCount++;
        body.append("  ").append(p0).append(" = getelementptr ").append(startType).append(", ").append(startType)
             .append("* ").append(start).append(", i32 0, i32 0\n");
        body.append("  store i8* ").append(code).append(", i8** ").append(p0).append("\n");
        String p1 = "%thr.start.p1." + tmpCount++;
        body.append("  ").append(p1).append(" = getelementptr ").append(startType).append(", ").append(startType)
             .append("* ").append(start).append(", i32 0, i32 1\n");
        body.append("  store i8* ").append(recv).append(", i8** ").append(p1).append("\n");

        // Handle { tid, resultSlot=null, joined=0 }, store into obj field 2
        String handleRaw = "%thr.start.hraw." + tmpCount++;
        body.append("  ").append(handleRaw).append(" = call i8* @").append(allocFn()).append("(i64 24)\n");
        String handle = "%thr.start.handle." + tmpCount++;
        body.append("  ").append(handle).append(" = bitcast i8* ").append(handleRaw).append(" to %CangThreadHandle*\n");
        String tid = "%thr.start.tid." + tmpCount++;
        body.append("  ").append(tid).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle)
             .append(", i32 0, i32 0\n");
        String rid = "%thr.start.rid." + tmpCount++;
        body.append("  ").append(rid).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle)
             .append(", i32 0, i32 1\n");
        body.append("  store i8* null, i8** ").append(rid).append("\n");
        String joined = "%thr.start.joined." + tmpCount++;
        body.append("  ").append(joined).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle)
             .append(", i32 0, i32 2\n");
        body.append("  store i32 0, i32* ").append(joined).append("\n");
        body.append("  store i8* ").append(handleRaw).append(", i8** ").append(hfield).append("\n");

        // Non-daemon threads join at main exit; daemon (new Thread(true)) does not register.
        String dptr = "%thr.start.dp." + tmpCount++;
        body.append("  ").append(dptr).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
             .append(", i32 0, i32 3\n");
        String daemon = "%thr.start.daemon." + tmpCount++;
        body.append("  ").append(daemon).append(" = load i1, i1* ").append(dptr).append("\n");
        int regId = labelCount++;
        String doReg = "thrreg.enter." + regId;
        String skipReg = "thrreg.skip." + regId;
        body.append("  br i1 ").append(daemon).append(", label %").append(skipReg)
             .append(", label %").append(doReg).append("\n");
        body.append(doReg).append(":\n");
        String regCnt = "%thrreg.cnt." + tmpCount++;
        body.append("  ").append(regCnt).append(" = load i64, i64* @cang.thr.regc\n");
        String regFull = "%thrreg.full." + tmpCount++;
        String regOk = "thrreg.ok." + regId;
        String regBad = "thrreg.bad." + regId;
        body.append("  ").append(regFull).append(" = icmp uge i64 ").append(regCnt).append(", 256\n");
        body.append("  br i1 ").append(regFull).append(", label %").append(regBad)
             .append(", label %").append(regOk).append("\n");
        body.append(regBad).append(":\n");
        emitFatalError("Too many live threads (registry full)", node.line, regOk);
        body.append(regOk).append(":\n");
        String regSlot = "%thrreg.slot." + tmpCount++;
        body.append("  ").append(regSlot).append(" = getelementptr [256 x i8*], [256 x i8*]* @cang.thr.regs, i64 0, i64 ")
             .append(regCnt).append("\n");
        body.append("  store i8* ").append(handleRaw).append(", i8** ").append(regSlot).append("\n");
        String regCnt2 = "%thrreg.cnt2." + tmpCount++;
        body.append("  ").append(regCnt2).append(" = add i64 ").append(regCnt).append(", 1\n");
        body.append("  store i64 ").append(regCnt2).append(", i64* @cang.thr.regc\n");
        body.append("  br label %").append(skipReg).append("\n");
        body.append(skipReg).append(":\n");

        emitThreadEntryFunction(threadCount);
        emitThreadCreate(raw, tid, node.line);
        threadCount++;
        return new LLVMValue(handle, "%CangThreadHandle*", "Thread<void>");
    }

    /** Object-style Thread join: load the handle from field 2 with a not-started runtime check. */
    private LLVMValue threadObjHandle(LLVMValue receiver, int line) {
        String hfield = "%thr.join.hf." + tmpCount++;
        body.append("  ").append(hfield).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
             .append(", i32 0, i32 2\n");
        String handleRaw = "%thr.join.handle." + tmpCount++;
        body.append("  ").append(handleRaw).append(" = load i8*, i8** ").append(hfield).append("\n");
        int id = labelCount++;
        String notStarted = "thr.join.notstarted." + id;
        String ok = "thr.join.started." + id;
        String cmp = "%thr.join.cmp." + tmpCount++;
        body.append("  ").append(cmp).append(" = icmp eq i8* ").append(handleRaw).append(", null\n");
        body.append("  br i1 ").append(cmp).append(", label %").append(notStarted).append(", label %").append(ok).append("\n");
        body.append(notStarted).append(":\n");
        emitRuntimeError("Thread not started: call start() before join", line, ok);
        body.append(ok).append(":\n");
        String handle = "%thr.join.htyped." + tmpCount++;
        body.append("  ").append(handle).append(" = bitcast i8* ").append(handleRaw).append(" to %CangThreadHandle*\n");
        return new LLVMValue(handle, "%CangThreadHandle*", receiver.semanticType);
    }

    /** Thread entry for the object-style pack { i8* code, i8* receiver }: call void (i8*, i8*). */
    private void emitThreadEntryFunction(int id) {
        String startType = "{ i8*, i8* }";
        boolean posix = !targetPlatform.equals("windows");
        StringBuilder x = new StringBuilder();
        x.append("define ").append(posix ? "i8*" : "i32")
         .append(" @cang.thread.entry.").append(id).append("(i8* %arg) {\nentry:\n");
        emitStackBaseSet(x, "th." + id);
        String sb = "%gc.sb." + id;
        String sb8 = "%gc.sb8." + id;
        boolean registerWithGc = gcEnabled && !targetPlatform.equals("windows");
        if (registerWithGc) {
            x.append("  ").append(sb).append(" = alloca [64 x i8]\n");
            x.append("  ").append(sb8).append(" = getelementptr [64 x i8], [64 x i8]* ").append(sb).append(", i32 0, i32 0\n");
            x.append("  call void @llvm.memset.p0i8.p0i8.i64(i8* ").append(sb8).append(", i8 0, i64 64, i1 false)\n");
            x.append("  %gc.sbres1.").append(id).append(" = call i32 @GC_get_stack_base(i8* ").append(sb8).append(")\n");
            x.append("  %gc.sbres2.").append(id).append(" = call i32 @GC_register_my_thread(i8* ").append(sb8).append(")\n");
        }
        x.append("  %s = bitcast i8* %arg to ").append(startType).append("*\n");
        x.append("  %cf = getelementptr ").append(startType).append(", ").append(startType).append("* %s, i32 0, i32 0\n");
        x.append("  %code = load i8*, i8** %cf\n");
        x.append("  %rf = getelementptr ").append(startType).append(", ").append(startType).append("* %s, i32 0, i32 1\n");
        x.append("  %recv = load i8*, i8** %rf\n");
        // Function ABI: the callee is bitcast from `code`; the ONLY argument is the receiver
        // (same as generateIndirectCall). Task is Function<void> = void (i8* env).
        x.append("  %fp = bitcast i8* %code to void (i8*)*\n");
        x.append("  call void %fp(i8* %recv)\n");
        if (registerWithGc) {
            x.append("  %gc.sbres3.").append(id).append(" = call i32 @GC_unregister_my_thread(i8* ").append(sb8).append(")\n");
        }
        x.append("  call void @").append(freeFn()).append("(i8* %arg)\n");
        if (posix) x.append("  ret i8* null\n}\n\n");
        else x.append("  ret i32 0\n}\n\n");
        extraDefs.append(x);
    }

    private LLVMValue generateThreadJoin(LLVMValue receiver, int line) {
        String semantic = receiver.semanticType;
        String resultCang = semantic != null && isThreadType(semantic) ? threadResultType(semantic) : "int";
        boolean isVoid = resultCang.equals("void") || resultCang.equals("Void");

        String joinedPtr = "%thread.join.joined." + tmpCount++;
        body.append("  ").append(joinedPtr).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(receiver.value).append(", i32 0, i32 2\n");
        String joined = "%thread.join.joinedval." + tmpCount++;
        body.append("  ").append(joined).append(" = load i32, i32* ").append(joinedPtr).append("\n");
        String isJoined = "%thread.join.isjoined." + tmpCount++;
        String bad = "thread.join.already." + labelCount++;
        String ok = "thread.join.ok." + labelCount++;
        body.append("  ").append(isJoined).append(" = icmp eq i32 ").append(joined).append(", 1\n");
        body.append("  br i1 ").append(isJoined).append(", label %").append(bad).append(", label %").append(ok).append("\n");
        body.append(bad).append(":\n");
        emitFatalError("Thread already joined", line, ok);
        body.append(ok).append(":\n");
        body.append("  store i32 1, i32* ").append(joinedPtr).append("\n");

        String tid = "%thread.join.id." + tmpCount++;
        body.append("  ").append(tid).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(receiver.value).append(", i32 0, i32 0\n");
        String handle = "%thread.join.handle." + tmpCount++;
        body.append("  ").append(handle).append(" = load i64, i64* ").append(tid).append("\n");
        if (targetPlatform.equals("windows")) {
            String hp = "%thread.join.hp." + tmpCount++;
            body.append("  ").append(hp).append(" = inttoptr i64 ").append(handle).append(" to i8*\n");
            body.append("  call i32 @WaitForSingleObject(i8* ").append(hp).append(", i32 -1)\n");
            body.append("  call i32 @CloseHandle(i8* ").append(hp).append(")\n");
        } else {
            body.append("  call i32 @pthread_join(i64 ").append(handle).append(", i8** null)\n");
        }

        if (isVoid) {
            return new LLVMValue("void", "void");
        }
        String resultLLVM = toLLVMType(resultCang);
        String rid = "%thread.join.result." + tmpCount++;
        body.append("  ").append(rid).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(receiver.value).append(", i32 0, i32 1\n");
        String result = "%thread.join.result.ptr." + tmpCount++;
        body.append("  ").append(result).append(" = load i8*, i8** ").append(rid).append("\n");
        String typed = "%thread.join.typed." + tmpCount++;
        body.append("  ").append(typed).append(" = bitcast i8* ").append(result).append(" to ").append(resultLLVM).append("*\n");
        String value = "%thread.join.value." + tmpCount++;
        body.append("  ").append(value).append(" = load ").append(resultLLVM).append(", ").append(resultLLVM).append("* ").append(typed).append("\n");
        body.append("  call void @").append(freeFn()).append("(i8* ").append(result).append(")\n");
        String resultSemantic = null;
        if (resultCang.equals("String") || resultCang.equals("str")) resultSemantic = resultCang;
        return new LLVMValue(value, resultLLVM, resultSemantic);
    }

    private LLVMValue generateMathCall(String method, List<AST> nodes, int line) {
        List<LLVMValue> args = new ArrayList<>();
        for (AST node : nodes) args.add(generateExpr(node));

        if (method.equals("abs") && args.size() == 1) {
            LLVMValue v = args.get(0);
            if (v.type.equals("i32") || v.type.equals("i64")) {
                String cmp = "%math.abs.cmp." + tmpCount++;
                String neg = "%math.abs.neg." + tmpCount++;
                String r = "%math.abs." + tmpCount++;
                body.append("  ").append(cmp).append(" = icmp sge ").append(v.type).append(" ").append(v.value).append(", 0\n");
                body.append("  ").append(neg).append(" = sub ").append(v.type).append(" 0, ").append(v.value).append("\n");
                body.append("  ").append(r).append(" = select i1 ").append(cmp)
                    .append(", ").append(v.type).append(" ").append(v.value).append(", ").append(v.type).append(" ").append(neg).append("\n");
                return new LLVMValue(r, v.type);
            }
            if (v.type.equals("double") || v.type.equals("float")) {
                String r = "%math.abs." + tmpCount++;
                body.append("  ").append(r).append(" = call ").append(v.type).append(" @llvm.fabs.").append(v.type.equals("double") ? "f64" : "f32").append("(").append(v.type).append(" ").append(v.value).append(")\n");
                return new LLVMValue(r, v.type);
            }
        }

        if ((method.equals("min") || method.equals("max")) && args.size() == 2) {
            LLVMValue a = args.get(0), b = args.get(1);
            String type = a.type.equals("double") || b.type.equals("double") ? "double" : (a.type.equals("i64") || b.type.equals("i64") ? "i64" : "i32");
            String av = castValue(a, type), bv = castValue(b, type);
            String cmp = "%math.cmp." + tmpCount++;
            String r = "%math." + method + "." + tmpCount++;
            String pred = method.equals("min") ? "sle" : "sge";
            if (isFloatType(type)) {
                pred = method.equals("min") ? "ole" : "oge";
                body.append("  ").append(cmp).append(" = fcmp ").append(pred).append(" ").append(type).append(" ").append(av).append(", ").append(bv).append("\n");
            } else {
                body.append("  ").append(cmp).append(" = icmp ").append(pred).append(" ").append(type).append(" ").append(av).append(", ").append(bv).append("\n");
            }
            body.append("  ").append(r).append(" = select i1 ").append(cmp).append(", ").append(type).append(" ").append(av).append(", ").append(type).append(" ").append(bv).append("\n");
            return new LLVMValue(r, type);
        }

        String intrinsic = null;
        if (method.equals("sqrt")) intrinsic = "sqrt";
        else if (method.equals("pow")) intrinsic = "pow";
        else if (method.equals("floor")) intrinsic = "floor";
        else if (method.equals("ceil")) intrinsic = "ceil";
        else if (method.equals("round")) intrinsic = "round";
        else if (method.equals("sin")) intrinsic = "sin";
        else if (method.equals("cos")) intrinsic = "cos";
        else if (method.equals("tan")) intrinsic = "tan";
        else if (method.equals("asin")) intrinsic = "asin";
        else if (method.equals("acos")) intrinsic = "acos";
        else if (method.equals("atan")) intrinsic = "atan";
        else if (method.equals("atan2")) intrinsic = "atan2";
        else if (method.equals("log")) intrinsic = "log";
        else if (method.equals("log10")) intrinsic = "log10";
        else if (method.equals("exp")) intrinsic = "exp";
        if (intrinsic != null && (args.size() == 1 || (intrinsic.equals("pow") || intrinsic.equals("atan2")) && args.size() == 2)) {
            String r = "%math." + intrinsic + "." + tmpCount++;
            StringBuilder callArgs = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) callArgs.append(", ");
                callArgs.append("double ").append(castValue(args.get(i), "double"));
            }
            body.append("  ").append(r).append(" = call double @llvm.").append(intrinsic).append(".f64(").append(callArgs).append(")\n");
            return new LLVMValue(r, "double");
        }

        if (method.equals("random") && args.isEmpty()) {
            // Platform-independent 64-bit LCG: state = state*A + C (mod 2^64).
            String oldState = "%math.seed.old." + tmpCount++;
            String multiplied = "%math.seed.multiplied." + tmpCount++;
            String nextState = "%math.seed.next." + tmpCount++;
            String highBits = "%math.random.bits." + tmpCount++;
            String converted = "%math.random.converted." + tmpCount++;
            String result = "%math.random." + tmpCount++;
            body.append("  ").append(oldState).append(" = load i64, i64* @math.seed\n");
            body.append("  ").append(multiplied).append(" = mul i64 ").append(oldState).append(", 6364136223846793005\n");
            body.append("  ").append(nextState).append(" = add i64 ").append(multiplied).append(", 1442695040888963407\n");
            body.append("  store i64 ").append(nextState).append(", i64* @math.seed\n");
            body.append("  ").append(highBits).append(" = lshr i64 ").append(nextState).append(", 32\n");
            body.append("  ").append(converted).append(" = uitofp i64 ").append(highBits).append(" to double\n");
            body.append("  ").append(result).append(" = fdiv double ").append(converted).append(", 4294967296.0\n");
            return new LLVMValue(result, "double");
        }
        throw new RuntimeException("Unknown Math method or argument count: Math." + method + " (at line " + line + ")");
    }

    /**
     * Generate call to a static method (no 'this' parameter).
     */
    private LLVMValue generateSystemCall(String method, List<AST> nodes, int line) {
        if (method.equals("exit") && nodes.size() == 1) {
            LLVMValue status = generateExpr(nodes.get(0));
            String castedExit = castValue(status, "i32");
            // Same end-of-main sweep as a normal return (debug.md #30): join thread{} handles
            // and registered non-daemon object threads before the process dies. finally is
            // explicitly out of scope per the item's guidance.
            emitThreadBlockJoins();
            emitRegisteredThreadJoins();
            body.append("  call void @exit(i32 ").append(castedExit).append(")\n");
            body.append("  unreachable\n");
            return new LLVMValue("void", "void");
        }
        if (method.equals("gc") && nodes.isEmpty()) {
            // Manual collection trigger; no-op when running without --gc.
            if (gcEnabled) {
                body.append("  call void @GC_gcollect()\n");
            }
            return new LLVMValue("void", "void");
        }
        if (method.equals("getenv") && nodes.size() == 1) {
            LLVMValue name = generateExpr(nodes.get(0));
            if (!name.type.equals("i8*")) throw new RuntimeException("System.getenv expects String (at line " + line + ")");
            String result = "%system.getenv." + tmpCount++;
            body.append("  ").append(result).append(" = call i8* @getenv(i8* ").append(name.value).append(")\n");
            return new LLVMValue(result, "i8*", "String");
        }
        if (method.equals("gc") && nodes.isEmpty()) {

            return new LLVMValue("void", "void");
        }
        if (method.equals("currentTimeMillis") && nodes.isEmpty()) {
            String seconds = "%system.time.seconds." + tmpCount++;
            String millis = "%system.time.millis." + tmpCount++;
            body.append("  ").append(seconds).append(" = call i64 @time(i64* null)\n");
            body.append("  ").append(millis).append(" = mul i64 ").append(seconds).append(", 1000\n");
            return new LLVMValue(millis, "i64");
        }
        throw new RuntimeException("Unknown System method or argument count: System." + method + " (at line " + line + ")");
    }

    private LLVMValue generateStaticCall(FuncInfo fi, String funcName, List<AST> nodeArgs, int line) {
        StringBuilder args = new StringBuilder();
        int argCount = nodeArgs.size();
        int paramCount = fi.paramTypes.size();

        for (int i = 0; i < paramCount; i++) {
            if (i > 0) args.append(", ");
            String paramType = fi.paramTypes.get(i);
            String llvmParamType = toLLVMType(paramType);

            if (i < argCount && !(nodeArgs.get(i) instanceof VoidPlaceholder)) {
                LLVMValue argVal = generateCallArg(nodeArgs.get(i), paramType, line);
                args.append(llvmParamType).append(" ").append(castValue(argVal, llvmParamType));
            } else {
                AST defaultVal = fi.paramDefaults.get(i);
                if (defaultVal != null) {
                    LLVMValue defVal = generateExpr(defaultVal);
                    args.append(llvmParamType).append(" ").append(castValue(defVal, llvmParamType));
                } else {
                    args.append(llvmParamType).append(" ").append(defaultValueForType(paramType));
                }
            }
        }

        if (fi.returnType.equals("void")) {
            body.append("  call void @").append(fi.llvmName).append("(").append(args).append(")\n");
            return new LLVMValue("void", "void");
        } else {
            String result = "%call." + tmpCount++;
            body.append("  ").append(result).append(" = call ").append(toLLVMType(fi.returnType))
                 .append(" @").append(fi.llvmName).append("(").append(args).append(")\n");
            String retSem = (callResultCarriesSemantic(fi.returnType))
                ? fi.returnType : null;
            return new LLVMValue(result, toLLVMType(fi.returnType), retSem);
        }
    }

    private LLVMValue generateStdoutPrint(List<AST> args, boolean newline) {
        return generateStdoutPrint(args, newline, false);
    }

    private LLVMValue generateStdoutPrint(List<AST> args, boolean newline, boolean toStderr) {
        if (args.isEmpty()) throw new RuntimeException((toStderr ? "Stderr" : "Stdout") + ".print requires an argument");
        // Call prefix: stdout uses printf(fmt, ...); stderr uses fprintf(stream, fmt, ...).
        String callPre;
        if (toStderr) {
            String se = "%stderr." + tmpCount++;
            if (targetPlatform.equals("windows")) {
                body.append("  ").append(se).append(" = call i8* @__acrt_iob_func(i32 2)\n");
            } else {
                body.append("  ").append(se).append(" = load i8*, i8** @stderr\n");
            }
            callPre = "call i32 (i8*, i8*, ...) @fprintf(i8* " + se + ", i8* ";
        } else {
            callPre = "call i32 (i8*, ...) @printf(i8* ";
        }
        LLVMValue argVal = generateExpr(args.get(0));
        String fmtType = cangTypeFromLLVM(argVal.type);
        // println uses fmtConstants (with \n), print uses fmtConstantsNoNL (without \n)
        Map<String, String> fmtMap = newline ? fmtConstants : fmtConstantsNoNL;
        String fmtName = fmtMap.get(fmtType);
        if (fmtName == null) fmtName = fmtMap.get("String"); // fallback

        // For pointer types (String, class objects), check for null
        if (argVal.type.equals("i8*") || argVal.type.endsWith("*")) {
            int id = labelCount++;
            String notNullLabel = "print.notnull." + id;
            String endLabel = "print.end." + id;
            // Class objects must NOT go through %s (that reads struct bytes as a C string —
            // garbage / out-of-bounds, debug.md #31); they print as <ClassName@addr>.
            boolean isObj = argVal.type.startsWith("%");

            // Check if pointer is null. NOTE: name must NOT collide with the `isnull.N`
            // LABELS used by deref checks — LLVM values and labels share one namespace
            // (tmpCount/labelCount both hitting N made br label %isnull.N resolve to this value).
            String isNull = "%pnull." + tmpCount++;
            body.append("  ").append(isNull).append(" = icmp eq i8* ").append(argVal.value).append(", null\n");
            body.append("  br i1 ").append(isNull).append(", label %print.null.").append(id)
                 .append(", label %").append(notNullLabel).append("\n\n");

            // Null case: print "null" (with or without newline)
            body.append("print.null.").append(id).append(":\n");
            if (newline) {
                body.append("  ").append(callPre).append("getelementptr ([6 x i8], [6 x i8]* ")
                     .append("@.str.null").append(", i32 0, i32 0))\n");
            } else {
                body.append("  ").append(callPre).append("getelementptr ([5 x i8], [5 x i8]* ")
                     .append("@.str.null.p").append(", i32 0, i32 0))\n");
            }
            body.append("  br label %").append(endLabel).append("\n\n");

            // Not null: print normally
            body.append(notNullLabel).append(":\n");
            if (isObj) {
                String clsName = cangTypeFromLLVMFull(argVal.type);
                String fmtObj = ensureStringConstant(newline ? "@.str.obj.nl" : "@.str.obj",
                    newline ? "<%s@%p>\\0A\\00" : "<%s@%p>\\00", newline ? 9 : 8);
                String clsStr = ensureStringConstant("@.str.cls." + clsName, clsName + "\\00",
                    clsName.length() + 1);
                String objp = "%obj.p." + tmpCount++;
                body.append("  ").append(objp).append(" = bitcast ").append(argVal.type).append(" ")
                     .append(argVal.value).append(" to i8*\n");
                body.append("  ").append(callPre).append(fmtObj)
                     .append(", i8* ").append(clsStr).append(", i8* ").append(objp).append(")\n");
            } else {
                body.append("  ").append(callPre).append(fmtName)
                     .append(", i8* ").append(argVal.value).append(")\n");
            }
            body.append("  br label %").append(endLabel).append("\n\n");

            body.append(endLabel).append(":\n");
            return new LLVMValue("void", "void");
        }

        // For i1 (bool), we need to zext to i32 for printf
        String argStr;
        String argType;
        if (argVal.type.equals("i1")) {
            // Java-style boolean printing: true / false (not 1 / 0).
            String trueStr = ensureStringConstant("@.str.btrue", "true\\00", 5);
            String falseStr = ensureStringConstant("@.str.bfalse", "false\\00", 6);
            String sel = "%bool.str." + tmpCount++;
            body.append("  ").append(sel).append(" = select i1 ").append(argVal.value)
                 .append(", i8* ").append(trueStr).append(", i8* ").append(falseStr).append("\n");
            argStr = sel;
            argType = "i8*";
            fmtName = fmtMap.get("String"); // %s (with or without newline)
        } else if (argVal.type.equals("i8")) {
            String ext = "%byte.ext." + tmpCount++;
            body.append("  ").append(ext).append(" = sext i8 ").append(argVal.value).append(" to i32\n");
            argStr = ext;
            argType = "i32";
        } else if (argVal.type.equals("float")) {
            // printf varargs promote float to double
            String ext = "%float.ext." + tmpCount++;
            body.append("  ").append(ext).append(" = fpext float ").append(argVal.value).append(" to double\n");
            argStr = ext;
            argType = "double";
        } else {
            argStr = argVal.value;
            argType = argVal.type;
        }

        body.append("  ").append(callPre).append(fmtName)
             .append(", ").append(argType).append(" ").append(argStr).append(")\n");

        return new LLVMValue("void", "void");
    }

    /**
     * True when expr is System.ARGS field access.
     */
    private boolean isSystemArgs(AST expr) {
        if (!(expr instanceof FieldAccessExpr)) return false;
        FieldAccessExpr fa = (FieldAccessExpr) expr;
        return fa.object instanceof Identifier
            && ((Identifier) fa.object).name.equals("System")
            && fa.field.equals("ARGS");
    }

    /**
     * Generate access to System built-in fields: ARGS, OS_TYPE, ARCH_TYPE.
     */
    private LLVMValue generateSystemField(FieldAccessExpr node) {
        String field = node.field;
        int line = node.line;

        if (node.object instanceof Identifier && ((Identifier) node.object).name.equals("Math")) {
            if (field.equals("PI")) return new LLVMValue("0x400921FB54442D18", "double");
            if (field.equals("E")) return new LLVMValue("0x4005BF0A8B145769", "double");
            throw new RuntimeException("Unknown Math field: " + field + " (at line " + line + ")");
        }

        switch (field) {
            case "OS_TYPE":
                return makeStringConstant(targetPlatform.equals("macos") ? "macos" : targetPlatform);

            case "ARCH_TYPE":
                return makeStringConstant(targetArchitecture);

            case "ARGS":
                // Load array built at main entry: [i64 length][i8* ...]
                String argsPtr = "%sysargs." + tmpCount++;
                body.append("  ").append(argsPtr).append(" = load i8*, i8** @system.args\n");
                return new LLVMValue(argsPtr, "i8*");

            default:
                throw new RuntimeException("Unknown System field: " + field + " (at line " + line + ")");
        }
    }

    /**
     * Create a string constant and return it as LLVM value.
     */
    private LLVMValue makeStringConstant(String value) {
        String key = value;
        if (!stringLiterals.containsKey(key)) {
            String name = "@.str.sys." + stringLiterals.size();
            int byteCount = value.length() + 1;
            StringBuilder escaped = new StringBuilder();
            for (char c : value.toCharArray()) {
                if (c == '\\') escaped.append("\\5C");
                else if (c == '\n') escaped.append("\\0A");
                else if (c == '\t') escaped.append("\\09");
                else if (c == '"') escaped.append("\\22");
                else if (c >= 32 && c < 127) escaped.append(c);
                else escaped.append(String.format("\\%02X", (int) c));
            }
            header.append(name).append(" = private unnamed_addr constant [").append(byteCount)
                  .append(" x i8] c\"").append(escaped).append("\\00\"\n");
            stringLiterals.put(key, name);
        }
        String globalName = stringLiterals.get(key);
        String ptr = "%str." + tmpCount++;
        body.append("  ").append(ptr).append(" = getelementptr [")
            .append(value.length() + 1).append(" x i8], [")
            .append(value.length() + 1).append(" x i8]* ")
            .append(globalName).append(", i32 0, i32 0\n");
        return new LLVMValue(ptr, "i8*");
    }

    /** Static field of the CURRENT class by bare name (class-internal `instance` etc.), or null. */
    private FieldDecl findCurrentStaticField(String name) {
        if (currentClassName == null) return null;
        ClassInfo ci = classes.get(currentClassName);
        if (ci == null) return null;
        for (FieldDecl f : ci.staticFields) {
            if (f.name.equals(name)) return f;
        }
        return null;
    }

    private LLVMValue generateFieldAccess(FieldAccessExpr node) {
        // Handle System built-in fields
        if (node.object instanceof Identifier && ((Identifier) node.object).name.equals("System")) {
            return generateSystemField(node);
        }
        if (node.object instanceof Identifier && ((Identifier) node.object).name.equals("Math")) {
            return generateSystemField(node);
        }
        // cang/io/File built-in static field: File.separator (platform-specific constant)
        if (node.object instanceof Identifier && ((Identifier) node.object).name.equals("File")
                && node.field.equals("separator") && classes.containsKey("cang_io_File")) {
            return makeStringConstant(targetPlatform.equals("windows") ? "\\" : "/");
        }
        // User-class static field: Counter.n / Box.slot (ClassName.staticField read)
        if (node.object instanceof Identifier) {
            ClassInfo ownerCi = classes.get(((Identifier) node.object).name);
            if (ownerCi != null) {
                for (FieldDecl sf : ownerCi.staticFields) {
                    if (sf.name.equals(node.field)) {
                        String gname = "@static." + ownerCi.fullName + "_" + sf.name;
                        String llvmType = toLLVMType(sf.type);
                        String loaded = "%staticf." + tmpCount++;
                        body.append("  ").append(loaded).append(" = load ").append(llvmType)
                             .append(", ").append(llvmType).append("* ").append(gname).append("\n");
                        return new LLVMValue(loaded, llvmType);
                    }
                }
            }
        }

        LLVMValue objPtr = generateExprForPtr(node.object);
        if (objPtr == null) {
            String objDesc = node.object instanceof ThisExpr ? "this" :
                           node.object instanceof Identifier ? ((Identifier) node.object).name : "?";
            throw new RuntimeException("Cannot access field '" + node.field + "' on '" + objDesc +
                "' 閳?'this' is only valid inside class methods (line " + node.line + ")");
        }
        // Null pointer check for field reads (same pattern as method calls): a GEP+load on
        // null would SEGV instead of raising a catchable Error.
        if (objPtr.type.equals("i8*") || (objPtr.type.startsWith("%") && objPtr.type.endsWith("*"))) {
            int id = labelCount++;
            String notNullLabel = "nonnull." + id;
            String nullLabel = "isnull." + id;
            String isNull = "%null." + tmpCount++;
            body.append("  ").append(isNull).append(" = icmp eq ").append(objPtr.type)
                 .append(" ").append(objPtr.value).append(", null\n");
            body.append("  br i1 ").append(isNull).append(", label %").append(nullLabel)
                 .append(", label %").append(notNullLabel).append("\n\n");
            body.append(nullLabel).append(":\n");
            emitRuntimeError("Null pointer dereference", node.line, notNullLabel);
            body.append(notNullLabel).append(":\n");
        }

        String className = extractClassName(objPtr.type);
        ClassInfo ci = classes.get(className);
        if (ci == null) throw unknownClassError(className, node.line);

        // Private access check: _ prefix fields only accessible within same class
        if (node.field.startsWith("_") && !isSameOrParentClass(currentClassName, ci.simpleName)) {
            String context = currentClassName != null ? currentClassName : "top-level code";
            throw new RuntimeException("Field '" + node.field + "' is private and cannot be accessed from '" + context + "' (at line " + node.line + ")");
        }

        Integer fieldIdx = ci.fieldIndices.get(node.field);
        if (fieldIdx == null) throw new RuntimeException("Unknown field: " + node.field + " (at line " + node.line + ")");

        // fieldIdx includes type ID offset (starts at 1), fieldTypes starts at 0
        String fieldType = ci.fieldTypes.get(fieldIdx - 1);
        String fieldPtr = "%fp." + tmpCount++;
        body.append("  ").append(fieldPtr).append(" = getelementptr ").append(ci.llvmName)
             .append(", ").append(ci.llvmName).append("* ").append(objPtr.value)
             .append(", i32 0, i32 ").append(fieldIdx).append("\n");

        String loaded = "%fld." + tmpCount++;
        body.append("  ").append(loaded).append(" = load ").append(toLLVMType(fieldType))
             .append(", ").append(toLLVMType(fieldType)).append("* ").append(fieldPtr).append("\n");
        // Array-typed fields carry their Array<T> semantic type so chained .length() works
        // (debug.md #38): e.g. this.items.length().
        String fldSem = isArraySemanticType(fieldType) ? fieldType : null;
        return new LLVMValue(loaded, toLLVMType(fieldType), fldSem);
    }

    private LLVMValue generateExprForPtr(AST node) {
        if (node instanceof Identifier) {
            LLVMValue val = scope.lookup(((Identifier) node).name);
            if (val == null) throw new RuntimeException("Undefined variable: " + ((Identifier) node).name + " (at line " + node.line + ")");
            // Scope stores value type. Alloca type is value_type + "*".
            // For pointer-typed variables (reference types), load the pointer from alloca.
            if (val.type.endsWith("*")) {
                String allocaType = val.type + "*";
                String loaded = "%deref." + tmpCount++;
                body.append("  ").append(loaded).append(" = load ").append(val.type)
                     .append(", ").append(allocaType).append(" ").append(val.value).append("\n");
                return new LLVMValue(loaded, val.type);
            }
            return val;
        }
        if (node instanceof ThisExpr) {
            return scope.lookup("this");
        }
        if (node instanceof FieldAccessExpr) {
            // Chain: obj.field 閳?generate field access, which returns a loaded value
            // For chained field access (obj.field.field2), we need the intermediate pointer
            FieldAccessExpr fa = (FieldAccessExpr) node;
            LLVMValue objPtr = generateExprForPtr(fa.object);
            // Null check on the object being GEP'd: GEP on null yields a small non-null
            // address, so the outer load/store would still SEGV without this check.
            if (objPtr.type.equals("i8*") || (objPtr.type.startsWith("%") && objPtr.type.endsWith("*"))) {
                int id = labelCount++;
                String notNullLabel = "nonnull." + id;
                String nullLabel = "isnull." + id;
                String isNull = "%null." + tmpCount++;
                body.append("  ").append(isNull).append(" = icmp eq ").append(objPtr.type)
                     .append(" ").append(objPtr.value).append(", null\n");
                body.append("  br i1 ").append(isNull).append(", label %").append(nullLabel)
                     .append(", label %").append(notNullLabel).append("\n\n");
                body.append(nullLabel).append(":\n");
                emitRuntimeError("Null pointer dereference", node.line, notNullLabel);
                body.append(notNullLabel).append(":\n");
            }
            String className = extractClassName(objPtr.type);
            ClassInfo ci = classes.get(className);
            if (ci == null) throw unknownClassError(className, node.line);
            Integer fieldIdx = ci.fieldIndices.get(fa.field);
            if (fieldIdx == null) throw new RuntimeException("Unknown field: " + fa.field + " (at line " + node.line + ")");
            // fieldIdx includes type ID offset, fieldTypes starts at 0
            String fieldType = ci.fieldTypes.get(fieldIdx - 1);
            String fieldPtr = "%fp." + tmpCount++;
            body.append("  ").append(fieldPtr).append(" = getelementptr ").append(ci.llvmName)
                 .append(", ").append(ci.llvmName).append("* ").append(objPtr.value)
                 .append(", i32 0, i32 ").append(fieldIdx).append("\n");
            return new LLVMValue(fieldPtr, toLLVMType(fieldType) + "*");
        }
        // Fallback: generate the expression and use the value
        return generateExpr(node);
    }

    /**
     * Runtime-sized array creation `new T[size]` (debug.md #37): length-prefixed heap block
     * with a zero-filled data region — same layout as array literals, size evaluated at runtime.
     */
    private LLVMValue generateNewArray(NewArrayExpr node) {
        if (node.type.equals("var") || node.type.equals("void")) {
            throw new RuntimeException("'var'/'void' cannot be an array element type (at line " + node.line + ")");
        }
        LLVMValue sizeV = generateExpr(node.size);
        if (!isNumericLLVM(sizeV.type)) {
            throw new RuntimeException("Array size must be numeric (at line " + node.line + ")");
        }
        String n = castValue(sizeV, "i64");

        // Negative sizes are a runtime error (Java: NegativeArraySizeException).
        int id = labelCount++;
        String okL = "arrsize.ok." + id;
        String negL = "arrsize.neg." + id;
        // Value prefix must differ from the label prefix (LLVM values/labels share one
        // namespace — third recurrence of debug.md #34, keep prefixes disjoint!).
        String neg = "%isneg." + tmpCount++;
        body.append("  ").append(neg).append(" = icmp slt i64 ").append(n).append(", 0\n");
        body.append("  br i1 ").append(neg).append(", label %").append(negL)
             .append(", label %").append(okL).append("\n\n");
        body.append(negL).append(":\n");
        emitRuntimeError("Negative array size", node.line, okL);
        body.append(okL).append(":\n");

        String elemLLVM = toLLVMType(node.type);
        long elemBits = llvmTypeBits(elemLLVM);
        if (elemBits <= 0) elemBits = 64; // pointers occupy 8 bytes (same rule as array literals)
        long elemBytes = (elemBits + 7) / 8;

        String bytes = "%arr.bytes." + tmpCount++;
        body.append("  ").append(bytes).append(" = mul i64 ").append(n).append(", ").append(elemBytes).append("\n");
        String total = "%arr.total." + tmpCount++;
        body.append("  ").append(total).append(" = add i64 ").append(bytes).append(", 8\n");
        String ptr = "%arr.new." + tmpCount++;
        body.append("  ").append(ptr).append(" = call i8* @").append(allocFn()).append("(i64 ").append(total).append(")\n");
        registerRegionAllocation(ptr);
        body.append("  store i64 ").append(n).append(", i64* ").append(ptr).append("\n");

        // Zero-fill the data region (Java `new int[8]` semantics: elements start as 0/null).
        String data = "%arr.data." + tmpCount++;
        body.append("  ").append(data).append(" = getelementptr i8, i8* ").append(ptr).append(", i64 8\n");
        body.append("  call void @llvm.memset.p0i8.p0i8.i64(i8* ").append(data)
             .append(", i8 0, i64 ").append(bytes).append(", i1 false)\n");

        return new LLVMValue(ptr, "i8*", "Array<" + node.type + ">");
    }

    private LLVMValue generateNew(NewExpr node) {
        // Boxing a primitive str into the immutable String reference wrapper.
        if (node.className.equals("String") && node.args.size() == 1) {
            LLVMValue value = generateExpr(node.args.get(0));
            if (!value.type.equals("i8*")) {
                throw new RuntimeException("new String(...) expects a string value (at line " + node.line + ")");
            }
            return new LLVMValue(value.value, "i8*", "String");
        }
        // new Thread() — intrinsic object-style handle; no Cang class body. Fields start null;
        // .task(fn) stores { code, receiver }, .start() creates the thread and stores the handle.
        if (node.className.equals("Thread")) {
            if (node.typeArgs != null && !node.typeArgs.isEmpty()) {
                throw new RuntimeException("new Thread() does not take type parameters (at line " + node.line + ")");
            }
            if (node.args.size() > 1) {
                throw new RuntimeException("new Thread() takes at most one bool daemon flag (at line " + node.line + ")");
            }
            // Daemon flag: new Thread(true) = daemon (main exit does NOT wait);
            // new Thread() / new Thread(false) = non-daemon (joined at main exit).
            String daemonVal = "false";
            if (node.args.size() == 1) {
                LLVMValue flag = generateExpr(node.args.get(0));
                if (!flag.type.equals("i1")) {
                    throw new RuntimeException("new Thread() daemon flag must be a bool (at line " + node.line + ")");
                }
                daemonVal = flag.value;
            }
            String raw = "%thr.obj.raw." + tmpCount++;
            body.append("  ").append(raw).append(" = call i8* @").append(allocFn()).append("(i64 32)\n");
            String obj = "%thr.obj." + tmpCount++;
            body.append("  ").append(obj).append(" = bitcast i8* ").append(raw).append(" to %CangThreadObj*\n");
            for (int f = 0; f < 3; f++) {
                String fp = "%thr.obj.f." + tmpCount++;
                body.append("  ").append(fp).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(obj)
                     .append(", i32 0, i32 ").append(f).append("\n");
                body.append("  store i8* null, i8** ").append(fp).append("\n");
            }
            String dptr = "%thr.obj.daemon." + tmpCount++;
            body.append("  ").append(dptr).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(obj)
                 .append(", i32 0, i32 3\n");
            body.append("  store i1 ").append(daemonVal).append(", i1* ").append(dptr).append("\n");
            return new LLVMValue(obj, "%CangThreadObj*", "Thread<void>");
        }

        ClassInfo ci = classes.get(node.className);
        if (ci == null) throw unknownClassError(node.className, node.line);
        if (ci.isAbstract) {
            throw new RuntimeException("Cannot instantiate abstract class '" + node.className
                + "' (at line " + node.line + ")");
        }
        // Private constructor: `class _Server` / `class _ Server` — real name is `Server`, the
        // '_' is a modifier only; new is allowed solely inside the class's own methods
        // (entry-class top-level counts as its own body).
        if (ci.isPrivateCtor) {
            if (currentClassName == null || !currentClassName.equals(ci.fullName)) {
                throw new RuntimeException("Class '" + node.className
                    + "' has a private constructor; new is only allowed inside the class itself (at line "
                    + node.line + ")");
            }
        }

        // Calculate struct size
        String sizeVar = "%size." + tmpCount++;
        body.append("  ").append(sizeVar).append(" = getelementptr ").append(ci.llvmName)
             .append(", ").append(ci.llvmName).append("* null, i32 1\n");
        String sizeOf = "%sizeof." + tmpCount++;
        body.append("  ").append(sizeOf).append(" = ptrtoint ").append(ci.llvmName)
             .append("* ").append(sizeVar).append(" to i64\n");

        // Malloc
        String raw = "%raw." + tmpCount++;
        body.append("  ").append(raw).append(" = call i8* @").append(allocFn()).append("(i64 ").append(sizeOf).append(")\n");
        registerRegionAllocation(raw);

        // Bitcast
        String obj = "%obj." + tmpCount++;
        body.append("  ").append(obj).append(" = bitcast i8* ").append(raw)
             .append(" to ").append(ci.llvmName).append("*\n");

        // Call constructor if exists
        String ctorName = ci.fullName + ".constructor";
        if (functions.containsKey(ctorName)) {
            FuncInfo fi = functions.get(ctorName);
            StringBuilder args = new StringBuilder();
            args.append(ci.llvmName).append("* ").append(obj);
            int argCount = node.args.size();
            int paramCount = fi.paramTypes.size();

            for (int i = 0; i < paramCount; i++) {
                args.append(", ");
                String paramType = fi.paramTypes.get(i);
                String llvmParamType = toLLVMType(paramType);

                if (i < argCount && !(node.args.get(i) instanceof VoidPlaceholder)) {
                    LLVMValue argVal = generateExpr(node.args.get(i));
                    args.append(llvmParamType).append(" ").append(castValue(argVal, llvmParamType));
                } else {
                    // Use default value
                    AST defaultVal = fi.paramDefaults.get(i);
                    if (defaultVal != null) {
                        LLVMValue defVal = generateExpr(defaultVal);
                        args.append(llvmParamType).append(" ").append(castValue(defVal, llvmParamType));
                    } else {
                        args.append(llvmParamType).append(" ").append(defaultValueForType(paramType));
                    }
                }
            }
            body.append("  call void @").append(ctorName).append("(").append(args).append(")\n");
        }

        return new LLVMValue(obj, ci.llvmName + "*",
            isListType(node.className) ? node.className : null);
    }

    // ==================== Array generation ====================

    /** Element LLVM type used by the most recent generateArrayLit call (for var inference). */
    private String lastArrayElemType = null;
    /** Element Cang type used by the most recent generateArrayLit call (for var inference). */
    private String lastArrayElemCangType = null;

    /** Register a variable as an array with the given Cang element type. */
    private void trackArrayVar(String varName, String elemCangType) {
        arrayElemCangTypes.put(varName, elemCangType);
        arrayElemTypes.put(varName, toLLVMType(elemCangType));
    }

    /** If t is "Array<X>", return X; otherwise null. */
    private String innerElemCang(String t) {
        if (t != null && t.startsWith("Array<") && t.endsWith(">")) {
            return t.substring(6, t.length() - 1);
        }
        return null;
    }

    /**
     * Element Cang type of an array expression (the type of expr[0]), or null if unknown.
     * a : Array<T>            -> T
     * a[i] where a : Array<Array<T>> -> T   (i.e. elem(elem(a)))
     */
    private String elemCangOf(AST expr) {
        if (expr instanceof Identifier) {
            return arrayElemCangTypes.get(((Identifier) expr).name);
        }
        if (expr instanceof ArrayAccessExpr) {
            return innerElemCang(elemCangOf(((ArrayAccessExpr) expr).array));
        }
        if (expr instanceof FieldAccessExpr) {
            // Field arrays: resolve the receiver class and read the declared Array<T> element
            // type out of its field table (debug.md #38). Covers `this.data[i]` / `obj.buf[i]`.
            FieldAccessExpr fa = (FieldAccessExpr) expr;
            String cn = pureClassName(fa.object);
            while (cn != null) {
                ClassInfo ci = classes.get(cn);
                if (ci == null) return null;
                Integer idx = ci.fieldIndices.get(fa.field);
                if (idx != null) {
                    String ft = ci.fieldTypes.get(idx - 1);
                    if (ft != null && ft.startsWith("Array<") && ft.endsWith(">")) {
                        return ft.substring(6, ft.length() - 1);
                    }
                    return null;
                }
                cn = ci.parentName;
            }
            return null;
        }
        return null;
    }

    /** Real element Cang type of a for-each iterable. The generated value's Array<T> semantic
     *  comes first (covers method calls like Dict.keys() which elemCangOf cannot see), then
     *  AST-level tracking (identifier/field/array-access), then System.ARGS. */
    private String forEachElemCang(LLVMValue iterableVal, AST iterable) {
        if (iterableVal != null && iterableVal.semanticType != null
                && iterableVal.semanticType.startsWith("Array<")
                && iterableVal.semanticType.endsWith(">")) {
            return iterableVal.semanticType.substring(6, iterableVal.semanticType.length() - 1);
        }
        String cang = elemCangOf(iterable);
        if (cang != null) return cang;
        if (isSystemArgs(iterable)) return "String";
        return null;
    }

    /** Pure (no IR emission) resolver: AST -> its Cang class name, or null. */
    private String pureClassName(AST expr) {
        if (expr instanceof ThisExpr) return currentClassName;
        if (expr instanceof Identifier) {
            LLVMValue v = scope != null ? scope.lookup(((Identifier) expr).name) : null;
            if (v == null) return null;
            String cn = extractClassName(v.type);
            return classes.containsKey(cn) ? cn : null;
        }
        if (expr instanceof FieldAccessExpr) {
            FieldAccessExpr fa = (FieldAccessExpr) expr;
            String recv = pureClassName(fa.object);
            while (recv != null) {
                ClassInfo ci = classes.get(recv);
                if (ci == null) return null;
                Integer idx = ci.fieldIndices.get(fa.field);
                if (idx != null) {
                    String ft = ci.fieldTypes.get(idx - 1);
                    return ft != null && classes.containsKey(ft) ? ft : null;
                }
                recv = ci.parentName;
            }
            return null;
        }
        return null;
    }

    /** Best-effort Cang type name from an LLVM type (class pointers resolve to the class name). */
    private String cangTypeFromLLVMFull(String llvmType) {
        if (llvmType.startsWith("%") && llvmType.endsWith("*")) {
            String cls = extractClassName(llvmType);
            if (classes.containsKey(cls)) return cls;
        }
        return cangTypeFromLLVM(llvmType);
    }

    /**
     * Infer the Cang type of an already-generated array element value, for error messages
     * and for var element-type inference.
     */
    private String inferElemCang(AST node) {
        if (node instanceof IntLit) return "int";
        if (node instanceof LongLit) return "long";
        if (node instanceof FloatLit) return "float";
        if (node instanceof DoubleLit) return "double";
        if (node instanceof BoolLit) return "bool";
        if (node instanceof StringLit) return "String";
        if (node instanceof StrLit) return "str";
        if (node instanceof ArrayLit) {
            return lastArrayElemCangType != null ? "Array<" + lastArrayElemCangType + ">" : null;
        }
        if (node instanceof Identifier) {
            String elem = arrayElemCangTypes.get(((Identifier) node).name);
            if (elem != null) return "Array<" + elem + ">";
        }
        return null; // caller falls back to LLVM-based name
    }

    /**
     * Generate array literal: [1, 2, 3]
     * Layout: [i64 length][elements...]  (length header + data)
     * Returns pointer to the header.
     */
    private LLVMValue generateArrayLit(ArrayLit node) {
        return generateArrayLit(node, null, null);
    }

    /**
     * Generate array literal with optional expected element type.
     * Every element's type is checked (literals and expressions alike); mismatches are compile errors.
     * Nested ArrayLit elements inherit the expected element type recursively (int[][] = [[...],[...]]).
     * When expectedCangType is null (var inference), the first element defines the element type.
     */
    private LLVMValue generateArrayLit(ArrayLit node, String expectedLLVMType, String expectedCangType) {
        int n = node.elements.size();
        if (n == 0) {
            // Empty array: malloc 8 bytes (just length)
            String ptr = "%arr.empty." + tmpCount++;
            body.append("  ").append(ptr).append(" = call i8* @").append(allocFn()).append("(i64 8)\n");
        registerRegionAllocation(ptr);
            // store length 0
            body.append("  store i64 0, i64* ").append(ptr).append("\n");
            lastArrayElemType = expectedLLVMType;
            lastArrayElemCangType = expectedCangType;
            return new LLVMValue(ptr, "i8*");
        }

        // Determine element type: declared type wins, otherwise infer from first element
        AST firstNode = node.elements.get(0);
        LLVMValue first = generateElemValue(firstNode, expectedCangType);
        String elemType = expectedLLVMType != null ? expectedLLVMType : first.type;
        String elemCang;
        if (expectedCangType != null) {
            elemCang = expectedCangType;
        } else {
            elemCang = inferElemCang(firstNode);
            if (elemCang == null) {
                if (firstNode instanceof ArrayLit) {
                    throw new RuntimeException(
                        "Cannot infer element type from nested empty array literal, use explicit type e.g. int[][] m = [[1],[2]] (at line "
                        + node.line + ")");
                }
                elemCang = cangTypeFromLLVMFull(first.type);
            }
        }
        checkArrayElemType(first.type, elemType, actualCangOf(firstNode, first), elemCang, node.line);
        int elemBits = llvmTypeBits(elemType);
        if (elemBits <= 0) elemBits = 64; // default for pointers
        long elemBytes = (elemBits + 7) / 8;

        // Allocate: 8 bytes (length) + n * elemBytes
        long totalSize = 8L + (long) n * elemBytes;
        String ptr = "%arr." + tmpCount++;
        body.append("  ").append(ptr).append(" = call i8* @").append(allocFn()).append("(i64 ").append(totalSize).append(")\n");

        // Store length
        body.append("  store i64 ").append(n).append(", i64* ").append(ptr).append("\n");

        // Store elements (skip the 8-byte header)
        String dataPtr = "%arr.data." + tmpCount++;
        body.append("  ").append(dataPtr).append(" = getelementptr i8, i8* ").append(ptr).append(", i64 8\n");
        String typedPtr = "%arr.typed." + tmpCount++;
        body.append("  ").append(typedPtr).append(" = bitcast i8* ").append(dataPtr).append(" to ").append(elemType).append("*\n");

        // Store first element (already generated)
        String elem0Ptr = "%arr.e0." + tmpCount++;
        body.append("  ").append(elem0Ptr).append(" = getelementptr ").append(elemType).append(", ")
             .append(elemType).append("* ").append(typedPtr).append(", i64 0\n");
        String castedFirst = castValue(first, elemType);
        body.append("  store ").append(elemType).append(" ").append(castedFirst)
             .append(", ").append(elemType).append("* ").append(elem0Ptr).append("\n");

        // Store remaining elements
        for (int i = 1; i < n; i++) {
            AST elemNode = node.elements.get(i);
            LLVMValue val = generateElemValue(elemNode, elemCang);
            checkArrayElemType(val.type, elemType, actualCangOf(elemNode, val), elemCang, node.line);
            String elemPtr = "%arr.e" + i + "." + tmpCount++;
            body.append("  ").append(elemPtr).append(" = getelementptr ").append(elemType).append(", ")
                 .append(elemType).append("* ").append(typedPtr).append(", i64 ").append(i).append("\n");
            String castedElem = castValue(val, elemType);
            body.append("  store ").append(elemType).append(" ").append(castedElem)
                 .append(", ").append(elemType).append("* ").append(elemPtr).append("\n");
        }

        lastArrayElemType = elemType;
        lastArrayElemCangType = elemCang;
        return new LLVMValue(ptr, "i8*");
    }

    /**
     * Generate one array-literal element, propagating the declared element type into nested
     * array literals so int[][] literals are checked recursively.
     */
    private LLVMValue generateElemValue(AST node, String expectedCang) {
        if (node instanceof ArrayLit && expectedCang != null) {
            String inner = innerElemCang(expectedCang);
            if (inner != null) {
                return generateArrayLit((ArrayLit) node, toLLVMType(inner), inner);
            }
        }
        return generateExpr(node);
    }

    /** Best Cang type name for an element value: prefer node-based inference, fall back to LLVM type. */
    private String actualCangOf(AST node, LLVMValue val) {
        String inferred = inferElemCang(node);
        return inferred != null ? inferred : cangTypeFromLLVMFull(val.type);
    }

    /**
     * Check one array element's value against the expected element type.
     */
    private void checkArrayElemType(String actualLLVMType, String expectedLLVMType,
                                    String actualCangType, String expectedCangType, int line) {
        if (actualLLVMType.equals(expectedLLVMType) || typesCompatible(actualLLVMType, expectedLLVMType)) {
            return;
        }
        String expectedCang = expectedCangType != null ? expectedCangType : cangTypeFromLLVMFull(expectedLLVMType);
        throw new RuntimeException(
            "Array element type mismatch: expected " + expectedCang +
            " but found " + actualCangType + " (at line " + line + ")");
    }

    /**
     * Generate array access: arr[index]
     * Layout: [i64 length][elements...]
     * Includes bounds checking.
     */
    private LLVMValue generateArrayAccess(ArrayAccessExpr node) {
        LLVMValue arrPtr = generateExpr(node.array);
        LLVMValue idx = generateExpr(node.index);

        // Cast index to i64
        String idxI64 = "%arr.idx." + tmpCount++;
        if (idx.type.equals("i32")) {
            body.append("  ").append(idxI64).append(" = sext i32 ").append(idx.value).append(" to i64\n");
        } else if (idx.type.equals("i64")) {
            body.append("  ").append(idxI64).append(" = ").append(idx.value).append("\n");
        } else {
            body.append("  ").append(idxI64).append(" = zext ").append(idx.type).append(" ").append(idx.value).append(" to i64\n");
        }

        // Null array check BEFORE touching the header (debug.md #10: load on null SEGVd).
        int nullId = labelCount++;
        String nullErr = "arr.nullerr." + nullId;
        String notNull = "arr.nn." + nullId;
        String isNull = "%arr.null." + tmpCount++;
        body.append("  ").append(isNull).append(" = icmp eq i8* ").append(arrPtr.value).append(", null\n");
        body.append("  br i1 ").append(isNull).append(", label %").append(nullErr)
             .append(", label %").append(notNull).append("\n\n");
        body.append(nullErr).append(":\n");
        emitRuntimeError("Null pointer dereference", node.line, notNull);
        body.append(notNull).append(":\n");

        // Load array length from header
        String lenVar = "%arr.len." + tmpCount++;
        body.append("  ").append(lenVar).append(" = load i64, i64* ").append(arrPtr.value).append("\n");

        // Bounds check: index >= length || index < 0
        int id = labelCount++;
        String okLabel = "arr.ok." + id;
        String errLabel = "arr.err." + id;

        // Check index >= length
        String geCheck = "%arr.ge." + tmpCount++;
        body.append("  ").append(geCheck).append(" = icmp sge i64 ").append(idxI64).append(", ").append(lenVar).append("\n");

        // Check index < 0
        String ltZero = "%arr.lt0." + tmpCount++;
        body.append("  ").append(ltZero).append(" = icmp slt i64 ").append(idxI64).append(", 0\n");

        // Combine: out of bounds if (idx >= len) || (idx < 0)
        String oob = "%arr.oob." + tmpCount++;
        body.append("  ").append(oob).append(" = or i1 ").append(geCheck).append(", ").append(ltZero).append("\n");
        body.append("  br i1 ").append(oob).append(", label %").append(errLabel)
             .append(", label %").append(okLabel).append("\n\n");

        // Error handler - raise a catchable Error (falls back to print+exit without Error class)
        body.append(errLabel).append(":\n");
        emitRuntimeError("Array index out of bounds", node.line, okLabel);

        // OK path
        body.append(okLabel).append(":\n");

        // Skip 8-byte length header
        String dataPtr = "%arr.data." + tmpCount++;
        body.append("  ").append(dataPtr).append(" = getelementptr i8, i8* ")
             .append(arrPtr.value).append(", i64 8\n");

        // Determine element type: Cang-level tracking resolves nested subscripts (a[i][j])
        String elemType; // LLVM type of elements inside node.array
        String elemCang = elemCangOf(node.array);
        if (elemCang != null) {
            elemType = toLLVMType(elemCang);
        } else if (node.array instanceof Identifier) {
            String tracked = arrayElemTypes.get(((Identifier) node.array).name);
            elemType = tracked != null ? tracked : "i32"; // default
        } else if (isSystemArgs(node.array)) {
            elemType = "i8*"; // ARGS elements are char*
        } else {
            elemType = "i32"; // default
        }

        String typedPtr = "%arr.typed." + tmpCount++;
        body.append("  ").append(typedPtr).append(" = bitcast i8* ").append(dataPtr).append(" to ").append(elemType).append("*\n");

        // Get element pointer
        String elemPtr = "%arr.elem." + tmpCount++;
        body.append("  ").append(elemPtr).append(" = getelementptr ").append(elemType).append(", ")
             .append(elemType).append("* ").append(typedPtr).append(", i64 ").append(idxI64).append("\n");

        // Load value
        String result = "%arr.load." + tmpCount++;
        body.append("  ").append(result).append(" = load ").append(elemType).append(", ")
             .append(elemType).append("* ").append(elemPtr).append("\n");

        return new LLVMValue(result, elemType, elemCang);
    }

    /**
     * Ensure a string constant exists in header (idempotent).
     */
    private String ensureStringConstant(String name, String text, int byteCount) {
        // Match the definition form (`name = ...`): a plain contains() would treat
        // `@.str.obj` as present because `@.str.obj.nl` contains it as a prefix (debug.md #31).
        if (!header.toString().contains(name + " =")) {
            // Ensure null terminator
            String content = text;
            if (!content.endsWith("\\00")) {
                content = content + "\\00";
                byteCount = text.length() + 1;
            }
            header.append(name).append(" = private unnamed_addr constant [").append(byteCount)
                  .append(" x i8] c\"").append(content).append("\"\n");
        }
        return name;
    }

    // ==================== Main function ====================

    /**
     * Build System.ARGS array from main's argc/argv and store into @system.args.
     * Layout: [i64 length][elem0][elem1]... where element is i8* (char*).
     * Must be called while the current block can still branch (emits a loop).
     */
    private void emitArgsArrayInit() {
        int id = labelCount++;
        String loopLabel = "args.loop." + id;
        String bodyLabel = "args.body." + id;
        String doneLabel = "args.done." + id;

        // argc64 = sext i32 %argc to i64
        body.append("  %argc64 = sext i32 %argc to i64\n");
        // total = 8 + argc*8
        body.append("  %args.slots = mul i64 %argc64, 8\n");
        body.append("  %args.total = add i64 %args.slots, 8\n");
        // mem = malloc(total)
        body.append("  %args.mem = call i8* @").append(allocFn()).append("(i64 %args.total)\n");
        // System.ARGS is a global root and must outlive the entry function.
        // It is intentionally not registered in the function region.
        // store length header
        body.append("  %args.lenptr = bitcast i8* %args.mem to i64*\n");
        body.append("  store i64 %argc64, i64* %args.lenptr\n");
        // publish to @system.args
        body.append("  store i8* %args.mem, i8** @system.args\n");
        // loop init
        body.append("  %args.i = alloca i64\n");
        body.append("  store i64 0, i64* %args.i\n");
        body.append("  br label %").append(loopLabel).append("\n\n");

        // loop condition: i < argc
        body.append(loopLabel).append(":\n");
        body.append("  %args.iv = load i64, i64* %args.i\n");
        body.append("  %args.cmp = icmp slt i64 %args.iv, %argc64\n");
        body.append("  br i1 %args.cmp, label %").append(bodyLabel)
             .append(", label %").append(doneLabel).append("\n\n");

        // loop body: slot[i] = argv[i]
        body.append(bodyLabel).append(":\n");
        body.append("  %args.off = mul i64 %args.iv, 8\n");
        body.append("  %args.slot = getelementptr i8, i8* %args.mem, i64 %args.off\n");
        body.append("  %args.slot2 = getelementptr i8, i8* %args.slot, i64 8\n");
        body.append("  %args.slotp = bitcast i8* %args.slot2 to i8**\n");
        body.append("  %args.ap = getelementptr i8*, i8** %argv, i64 %args.iv\n");
        body.append("  %args.av = load i8*, i8** %args.ap\n");
        body.append("  store i8* %args.av, i8** %args.slotp\n");
        body.append("  %args.in = add i64 %args.iv, 1\n");
        body.append("  store i64 %args.in, i64* %args.i\n");
        body.append("  br label %").append(loopLabel).append("\n\n");

        body.append(doneLabel).append(":\n");
    }

    private void generateAutoMain() {
        body.append("define i32 @main(i32 %argc, i8** %argv) {\n");
        body.append("entry:\n");
        emitStackBaseSet(body, "main");
        if (gcEnabled) body.append("  call void @GC_init()\n");

        scope = new Scope(null);
        tmpCount = 0;
        currentFuncReturnType = "int";
        emitGcFrameSetup();

        emitArgsArrayInit();

        for (AST stmt : topLevelStmts) {
            generateStmt(stmt);
        }

        emitGcFramePop();
        emitThreadBlockJoins();
        emitRegisteredThreadJoins();
        body.append("  ret i32 0\n");
        body.append("}\n\n");
        scope = null;
    }

    /**
     * Generate main for entry-point class (class with no braces).
     * Params become local variables initialized with defaults.
     * Rest of file is the body.
     */
    private void generateEntryPointMain(ClassDecl entryClass) {
        body.append("define i32 @main(i32 %argc, i8** %argv) {\n");
        body.append("entry:\n");
        emitStackBaseSet(body, "main");
        if (gcEnabled) body.append("  call void @GC_init()\n");

        scope = new Scope(null);
        tmpCount = 0;
        currentFuncReturnType = "int";
        emitGcFrameSetup();

        // Entry-class instance for top-level `this`: the rest of the file is the entry
        // instance's body, so this:: method references (e.g. task(this::run)) work at top level.
        ClassInfo entryCi = classes.get(entryClass.name);
        if (entryCi != null) {
            String eSizeGep = "%entry.obj.size." + tmpCount++;
            body.append("  ").append(eSizeGep).append(" = getelementptr ").append(entryCi.llvmName)
                 .append(", ").append(entryCi.llvmName).append("* null, i32 1\n");
            String eSize = "%entry.obj.sizeof." + tmpCount++;
            body.append("  ").append(eSize).append(" = ptrtoint ").append(entryCi.llvmName)
                 .append("* ").append(eSizeGep).append(" to i64\n");
            String eRaw = "%entry.obj.raw." + tmpCount++;
            body.append("  ").append(eRaw).append(" = call i8* @").append(allocFn()).append("(i64 ").append(eSize).append(")\n");
            String eObj = "%entry.obj." + tmpCount++;
            body.append("  ").append(eObj).append(" = bitcast i8* ").append(eRaw).append(" to ").append(entryCi.llvmName).append("*\n");
            String eTid = "%entry.obj.tid." + tmpCount++;
            body.append("  ").append(eTid).append(" = getelementptr ").append(entryCi.llvmName)
                 .append(", ").append(entryCi.llvmName).append("* ").append(eObj).append(", i32 0, i32 0\n");
            body.append("  store i32 ").append(entryCi.typeId).append(", i32* ").append(eTid).append("\n");
            scope.define("this", new LLVMValue(eObj, entryCi.llvmName + "*"));
            // Top-level code is the entry instance's body: allow this:: references.
            currentClassName = entryCi.fullName;
        }

        // Allocate and initialize parameters with default values
        for (Parameter p : entryClass.ctorParams) {
            String llvmType = toLLVMType(p.type);
            String ptr = "%v." + p.name;
            body.append("  ").append(ptr).append(" = alloca ").append(llvmType).append("\n");
            scope.define(p.name, new LLVMValue(ptr, llvmType,
                semanticKindOf(p.type)));
            registerGcRoot(ptr, llvmType);
            if (p.type.startsWith("Array<") && p.type.endsWith(">")) {
                trackArrayVar(p.name, p.type.substring(6, p.type.length() - 1));
            }

            if (p.defaultValue != null) {
                LLVMValue defVal = generateExpr(p.defaultValue);
                String castedDefault = castValue(defVal, llvmType);
                body.append("  store ").append(llvmType).append(" ")
                     .append(castedDefault)
                     .append(", ").append(llvmType).append("* ").append(ptr).append("\n");
            } else {
                body.append("  store ").append(llvmType).append(" ")
                     .append(defaultValueForType(p.type))
                     .append(", ").append(llvmType).append("* ").append(ptr).append("\n");
            }
        }

        // Build System.ARGS before user code runs
        emitArgsArrayInit();

        // Generate body (rest of file)
        for (AST stmt : entryClass.topLevelBody) {
            generateStmt(stmt);
        }

        emitGcFramePop();
        emitThreadBlockJoins();
        emitRegisteredThreadJoins();
        body.append("  ret i32 0\n");
        body.append("}\n\n");
        scope = null;
    }

    // ==================== Type utilities ====================

    /** Cang semantic kind carried on LLVMValue for types sharing an LLVM representation. */
    /**
     * cang/io/File stdlib runtime: libc declares, intrinsic helper bodies, and wrapper defines
     * for every native File method. The generic instance-call path emits
     * `call @cang_io_File.&lt;m&gt;(%cang_io_File* %recv, ...)`; these defines satisfy those
     * symbols so no dispatch interception is needed. Helpers are define-only (never declared —
     * declare+define in one module is an invalid redefinition).
     */
    /** Scanner wrappers (Design B like File): native readKey/readLine over libc stdin.
     *  Windows: _getch (conio, no echo, full special keys) + fgets(stdin).
     *  POSIX v1: getchar (line-buffered downgrade) + fgets — documented. */
    private void emitScannerRuntime() {
        ClassInfo scCi = classes.get("cang_io_Scanner");
        if (scCi == null || !imports.contains("cang/io/Scanner")) return;
        boolean win = targetPlatform.equals("windows");

        // libc declares (strlen/malloc/free already declared in emitHeader)
        if (!header.toString().contains("declare i8* @fgets(")) {
            header.append("declare i8* @fgets(i8*, i32, i8*)\n");
        }
        if (win) {
            header.append("declare i32 @_getch()\n");
            header.append("declare i32 @kbhit()\n");
            header.append("declare void @Sleep(i32)\n");
            header.append("declare i64 @GetTickCount64()\n");
            // FILE* __acrt_iob_func(int) — same i8* form the stderr path already declares.
            if (!header.toString().contains("declare i8* @__acrt_iob_func(")) {
                header.append("declare i8* @__acrt_iob_func(i32)\n");
            }
        } else {
            if (!header.toString().contains("@stdin = external")) {
                header.append("@stdin = external global i8*\n");
            }
            header.append("declare i32 @tcgetattr(i32, i8*)\n");
            header.append("declare i32 @tcsetattr(i32, i32, i8*)\n");
            header.append("declare void @cfmakeraw(i8*)\n");
            header.append("declare i64 @read(i32, i8*, i64)\n");
            header.append("declare i32 @poll(i8*, i64, i32)\n");
        }

        // ---- readKey (overloads: readKey() -> readKey.1(-1); readKey(ms) timed) ----
        StringBuilder rk = new StringBuilder();
        // 0-arg: permanent wait (forwards to the timed entry with -1)
        rk.append("define i32 @cang_io_Scanner.readKey(i8* %this) {\nentry:\n");
        rk.append("  %r0 = call i32 @cang_io_Scanner.readKey.1(i8* %this, i32 -1)\n");
        rk.append("  ret i32 %r0\n");
        rk.append("}\n\n");
        // 1-arg: timeout in milliseconds; -1 = block forever; timeout/EOF -> -1
        rk.append("define i32 @cang_io_Scanner.readKey.1(i8* %this, i32 %timeoutMs) {\nentry:\n");
        if (win) {
            // -1 skips the wait; otherwise poll kbhit + GetTickCount64 + Sleep(1).
            // (WaitForSingleObject on a redirected pipe handle reads as always-signaled —
            // verified — so a kernel wait would fall into _getch and hang; kbhit is 0 on
            // pipes, giving a correct timeout there and instant readiness on a console.)
            rk.append("  %neg = icmp slt i32 %timeoutMs, 0\n");
            rk.append("  br i1 %neg, label %direct, label %wait\n");
            rk.append("wait:\n");
            rk.append("  %t0 = call i64 @GetTickCount64()\n");
            rk.append("  br label %loopk\n");
            rk.append("loopk:\n");
            rk.append("  %hit = call i32 @kbhit()\n");
            rk.append("  %has = icmp ne i32 %hit, 0\n");
            rk.append("  br i1 %has, label %direct, label %tick\n");
            rk.append("tick:\n");
            rk.append("  %now = call i64 @GetTickCount64()\n");
            rk.append("  %t0z = zext i32 %timeoutMs to i64\n");
            rk.append("  %dt = sub i64 %now, %t0\n");
            rk.append("  %exp = icmp uge i64 %dt, %t0z\n");
            rk.append("  br i1 %exp, label %notready, label %nap\n");
            rk.append("nap:\n");
            rk.append("  call void @Sleep(i32 1)\n");
            rk.append("  br label %loopk\n");
            rk.append("notready:\n");
            rk.append("  ret i32 -1\n");
            rk.append("direct:\n");
            // _getch: plain ASCII/13/27..., or 0/0xE0 prefix + scan code for special keys.
            // Special keys are returned as 256 + scancode so KEY_UP=328 (256+72) etc. match.
            rk.append("  %c0 = call i32 @_getch()\n");
            rk.append("  %p0 = icmp eq i32 %c0, 0\n");
            rk.append("  %pe = icmp eq i32 %c0, 224\n");
            rk.append("  %pre = or i1 %p0, %pe\n");
            rk.append("  br i1 %pre, label %sc, label %ret\n");
            rk.append("sc:\n");
            rk.append("  %c1 = call i32 @_getch()\n");
            rk.append("  %m = or i32 256, %c1\n");
            rk.append("  ret i32 %m\n");
            rk.append("ret:\n");
            rk.append("  ret i32 %c0\n");
        } else {
            // POSIX raw mode, per-call and restored before returning so readLine stays
            // canonical: tcgetattr(orig) -> memcpy -> cfmakeraw(raw) -> tcsetattr;
            // read(0,1) blocks on a key; ESC(27) probes the rest of an escape sequence with
            // poll(100ms). CSI direction keys map to the same KEY_* codes as Windows
            // (A/B/C/D -> 328/332/333/331). Incomplete sequences degrade to 27 (ESC).
            rk.append("  %to = alloca [64 x i8]\n");
            rk.append("  %tr = alloca [64 x i8]\n");
            rk.append("  %res = alloca i32\n");
            rk.append("  %cb = alloca i8\n");
            rk.append("  %pfd = alloca i64\n");
            rk.append("  call void @llvm.memset.p0i8.p0i8.i64(i8* %to, i8 0, i64 64, i1 false)\n");
            rk.append("  call void @llvm.memset.p0i8.p0i8.i64(i8* %tr, i8 0, i64 64, i1 false)\n");
            rk.append("  %tg = call i32 @tcgetattr(i32 0, i8* %to)\n");
            rk.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* %tr, i8* %to, i64 64, i1 false)\n");
            rk.append("  call void @cfmakeraw(i8* %tr)\n");
            rk.append("  %tw = call i32 @tcsetattr(i32 0, i32 0, i8* %tr)\n");
            // poll(timeoutMs) gates readability: -1 = infinite (native poll semantics),
            // >=0 wakes on data/EOF or returns -1 on timeout. Then read the byte.
            rk.append("  store i64 4294967296, i64* %pfd\n");
            rk.append("  %prk = call i32 @poll(i8* %pfd, i64 1, i32 %timeoutMs)\n");
            rk.append("  %rdy = icmp sgt i32 %prk, 0\n");
            rk.append("  br i1 %rdy, label %doread, label %toT\n");
            rk.append("toT:\n");
            rk.append("  store i32 -1, i32* %res\n");
            rk.append("  br label %restore\n");
            rk.append("doread:\n");
            rk.append("  %n0 = call i64 @read(i32 0, i8* %cb, i64 1)\n");
            rk.append("  %e0 = icmp slt i64 %n0, 1\n");
            rk.append("  br i1 %e0, label %eofb, label %chk\n");
            rk.append("eofb:\n");
            rk.append("  store i32 -1, i32* %res\n");
            rk.append("  br label %restore\n");
            rk.append("chk:\n");
            rk.append("  %v0 = load i8, i8* %cb\n");
            rk.append("  %k0 = zext i8 %v0 to i32\n");
            rk.append("  %isEsc = icmp eq i32 %k0, 27\n");
            rk.append("  br i1 %isEsc, label %esc, label %plain\n");
            rk.append("plain:\n");
            rk.append("  store i32 %k0, i32* %res\n");
            rk.append("  br label %restore\n");
            rk.append("esc:\n");
            // pollfd {fd=0, events=POLLIN@bit32} == 0x0000000100000000; 100ms probe
            rk.append("  store i64 4294967296, i64* %pfd\n");
            rk.append("  %pr = call i32 @poll(i8* %pfd, i64 1, i32 100)\n");
            rk.append("  %h1 = icmp sgt i32 %pr, 0\n");
            rk.append("  br i1 %h1, label %br1, label %bare\n");
            rk.append("bare:\n");
            rk.append("  store i32 27, i32* %res\n");
            rk.append("  br label %restore\n");
            rk.append("br1:\n");
            rk.append("  %n1 = call i64 @read(i32 0, i8* %cb, i64 1)\n");
            rk.append("  %v1 = load i8, i8* %cb\n");
            rk.append("  %k1 = zext i8 %v1 to i32\n");
            rk.append("  %isL = icmp eq i32 %k1, 91\n");
            rk.append("  br i1 %isL, label %br2, label %bare2\n");
            rk.append("bare2:\n");
            rk.append("  store i32 27, i32* %res\n");
            rk.append("  br label %restore\n");
            rk.append("br2:\n");
            rk.append("  store i64 4294967296, i64* %pfd\n");
            rk.append("  %pr2 = call i32 @poll(i8* %pfd, i64 1, i32 100)\n");
            rk.append("  %h2 = icmp sgt i32 %pr2, 0\n");
            rk.append("  br i1 %h2, label %br3, label %bare3\n");
            rk.append("bare3:\n");
            rk.append("  store i32 27, i32* %res\n");
            rk.append("  br label %restore\n");
            rk.append("br3:\n");
            rk.append("  %n2 = call i64 @read(i32 0, i8* %cb, i64 1)\n");
            rk.append("  %v2 = load i8, i8* %cb\n");
            rk.append("  %k2 = zext i8 %v2 to i32\n");
            rk.append("  %isA = icmp eq i32 %k2, 65\n");
            rk.append("  %isBc = icmp eq i32 %k2, 66\n");
            rk.append("  %isCc = icmp eq i32 %k2, 67\n");
            rk.append("  %isD = icmp eq i32 %k2, 68\n");
            rk.append("  br i1 %isA, label %ku, label %chkB\n");
            rk.append("ku:\n  store i32 328, i32* %res\n  br label %restore\n");
            rk.append("chkB:\n  br i1 %isBc, label %kd, label %chkC\n");
            rk.append("kd:\n  store i32 332, i32* %res\n  br label %restore\n");
            rk.append("chkC:\n  br i1 %isCc, label %kr, label %chkD\n");
            rk.append("kr:\n  store i32 333, i32* %res\n  br label %restore\n");
            rk.append("chkD:\n  br i1 %isD, label %kl, label %other\n");
            rk.append("kl:\n  store i32 331, i32* %res\n  br label %restore\n");
            rk.append("other:\n  store i32 27, i32* %res\n  br label %restore\n");
            rk.append("restore:\n");
            rk.append("  %toret = call i32 @tcsetattr(i32 0, i32 0, i8* %to)\n");
            rk.append("  %rv = load i32, i32* %res\n");
            rk.append("  ret i32 %rv\n");
        }
        rk.append("}\n\n");
        header.append(rk);

        // ---- readLine ----
        StringBuilder rl = new StringBuilder();
        rl.append("define i8* @cang_io_Scanner.readLine(i8* %this) {\nentry:\n");
        rl.append("  %buf = call i8* @").append(allocFn()).append("(i64 4096)\n");
        if (win) {
            rl.append("  %stdin = call i8* @__acrt_iob_func(i32 0)\n");
        } else {
            rl.append("  %stdin = load i8*, i8** @stdin\n");
        }
        rl.append("  %got = call i8* @fgets(i8* %buf, i32 4096, i8* %stdin)\n");
        rl.append("  %eof = icmp eq i8* %got, null\n");
        rl.append("  br i1 %eof, label %fail, label %trim\n");
        rl.append("fail:\n");
        rl.append("  call void @").append(freeFn()).append("(i8* %buf)\n");
        rl.append("  ret i8* null\n");
        rl.append("trim:\n");
        rl.append("  %len = call i64 @strlen(i8* %buf)\n");
        rl.append("  %dec = sub i64 %len, 1\n");
        rl.append("  %np = getelementptr i8, i8* %buf, i64 %dec\n");
        rl.append("  %nl = load i8, i8* %np\n");
        rl.append("  %isnl = icmp eq i8 %nl, 10\n");
        rl.append("  br i1 %isnl, label %cut, label %done\n");
        rl.append("cut:\n");
        rl.append("  store i8 0, i8* %np\n");
        rl.append("  br label %done\n");
        rl.append("done:\n");
        rl.append("  ret i8* %buf\n");
        rl.append("}\n\n");
        header.append(rl);
    }

    private void emitFileRuntime() {
        ClassInfo fileCi = classes.get("cang_io_File");
        if (fileCi == null || !imports.contains("cang/io/File")) return;
        if (fileCi.fieldIndices.get("path") == null) return;

        // --- string constants used by helpers ---
        header.append("@.f.rb = private constant [3 x i8] c\"rb\\00\"\n");
        header.append("@.f.wb = private constant [3 x i8] c\"wb\\00\"\n");
        header.append("@.f.ab = private constant [3 x i8] c\"ab\\00\"\n");
        header.append("@.f.dot = private constant [2 x i8] c\".\\00\"\n");
        header.append("@.f.dotdot = private constant [3 x i8] c\"..\\00\"\n\n");

        // --- libc declares (platform-specific names where the ABI differs) ---
        header.append("declare i8* @fopen(i8*, i8*)\n");
        header.append("declare i32 @fclose(i8*)\n");
        header.append("declare i64 @fread(i8*, i64, i64, i8*)\n");
        header.append("declare i64 @fwrite(i8*, i64, i64, i8*)\n");
        header.append("declare i32 @remove(i8*)\n");
        header.append("declare i32 @rename(i8*, i8*)\n");
        header.append("declare i8* @opendir(i8*)\n");
        header.append("declare i8* @readdir(i8*)\n");
        header.append("declare i32 @closedir(i8*)\n");
        header.append("declare i32 @access(i8*, i32)\n");
        header.append("declare i8* @strrchr(i8*, i32)\n");
        if (targetPlatform.equals("windows")) {
            header.append("declare i8* @_getcwd(i8*, i32)\n");
            header.append("declare i32 @_mkdir(i8*)\n");
            header.append("declare i32 @_rmdir(i8*)\n");
            header.append("declare i32 @_fseeki64(i8*, i64, i32)\n");
            header.append("declare i64 @_ftelli64(i8*)\n");
        } else {
            header.append("declare i8* @getcwd(i8*, i64)\n");
            header.append("declare i32 @mkdir(i8*, i32)\n");
            header.append("declare i32 @rmdir(i8*)\n");
            header.append("declare i32 @fseeko(i8*, i64, i32)\n");
            header.append("declare i64 @ftello(i8*)\n");
        }
        header.append("\n");

        emitFilePathHelpers();
        emitFileIoHelpers();
        emitFileWrappers();
    }

    /** Heap-string and path-string helpers for File (define-only). */
    private void emitFilePathHelpers() {
        boolean win = targetPlatform.equals("windows");
        String alloc = allocFn();
        int sepChar = win ? 92 : 47;

        // strdup: heap copy of a whole NUL-terminated string (copies the terminator too).
        header.append("define i8* @cang.f.strdup(i8* %s) {\n");
        header.append("entry:\n");
        header.append("  %len = call i64 @strlen(i8* %s)\n");
        header.append("  %n1 = add i64 %len, 1\n");
        header.append("  %buf = call i8* @").append(alloc).append("(i64 %n1)\n");
        header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* %buf, i8* %s, i64 %n1, i1 false)\n");
        header.append("  ret i8* %buf\n");
        header.append("}\n\n");

        // strndup: heap copy of the first n bytes plus an explicit terminator.
        header.append("define i8* @cang.f.strndup(i8* %s, i64 %n) {\n");
        header.append("entry:\n");
        header.append("  %n1 = add i64 %n, 1\n");
        header.append("  %buf = call i8* @").append(alloc).append("(i64 %n1)\n");
        header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* %buf, i8* %s, i64 %n, i1 false)\n");
        header.append("  %z = getelementptr i8, i8* %buf, i64 %n\n");
        header.append("  store i8 0, i8* %z\n");
        header.append("  ret i8* %buf\n");
        header.append("}\n\n");

        // lastsep: pointer to the last path separator, or null. Windows accepts both '/' and '\'.
        if (win) {
            header.append("define i8* @cang.f.lastsep(i8* %p) {\n");
            header.append("entry:\n");
            header.append("  %a = call i8* @strrchr(i8* %p, i32 47)\n");
            header.append("  %b = call i8* @strrchr(i8* %p, i32 92)\n");
            header.append("  %an = icmp eq i8* %a, null\n");
            header.append("  br i1 %an, label %bonly, label %anext\n");
            header.append("bonly:\n");
            header.append("  ret i8* %b\n");
            header.append("anext:\n");
            header.append("  %bn = icmp eq i8* %b, null\n");
            header.append("  br i1 %bn, label %aonly, label %both\n");
            header.append("aonly:\n");
            header.append("  ret i8* %a\n");
            header.append("both:\n");
            header.append("  %ai = ptrtoint i8* %a to i64\n");
            header.append("  %bi = ptrtoint i8* %b to i64\n");
            header.append("  %cmp = icmp ugt i64 %ai, %bi\n");
            header.append("  %r = select i1 %cmp, i8* %a, i8* %b\n");
            header.append("  ret i8* %r\n");
            header.append("}\n\n");
        } else {
            header.append("define i8* @cang.f.lastsep(i8* %p) {\n");
            header.append("entry:\n");
            header.append("  %a = call i8* @strrchr(i8* %p, i32 47)\n");
            header.append("  ret i8* %a\n");
            header.append("}\n\n");
        }

        // getname: copy of the segment after the last separator; whole path when none.
        header.append("define i8* @cang.f.getname(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %s = call i8* @cang.f.lastsep(i8* %p)\n");
        header.append("  %n = icmp eq i8* %s, null\n");
        header.append("  br i1 %n, label %whole, label %part\n");
        header.append("whole:\n");
        header.append("  %r0 = call i8* @cang.f.strdup(i8* %p)\n");
        header.append("  ret i8* %r0\n");
        header.append("part:\n");
        header.append("  %after = getelementptr i8, i8* %s, i64 1\n");
        header.append("  %r1 = call i8* @cang.f.strdup(i8* %after)\n");
        header.append("  ret i8* %r1\n");
        header.append("}\n\n");

        // getparent: prefix before the last separator; null when there is none.
        // A separator at index 0 means the parent is the root itself ("/" or "\").
        header.append("define i8* @cang.f.getparent(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %s = call i8* @cang.f.lastsep(i8* %p)\n");
        header.append("  %n = icmp eq i8* %s, null\n");
        header.append("  br i1 %n, label %none, label %some\n");
        header.append("none:\n");
        header.append("  ret i8* null\n");
        header.append("some:\n");
        header.append("  %pi = ptrtoint i8* %p to i64\n");
        header.append("  %si = ptrtoint i8* %s to i64\n");
        header.append("  %idx = sub i64 %si, %pi\n");
        header.append("  %isroot = icmp eq i64 %idx, 0\n");
        header.append("  br i1 %isroot, label %root, label %prefix\n");
        header.append("root:\n");
        header.append("  %buf = call i8* @").append(alloc).append("(i64 2)\n");
        header.append("  %c = load i8, i8* %s\n");
        header.append("  store i8 %c, i8* %buf\n");
        header.append("  %z = getelementptr i8, i8* %buf, i64 1\n");
        header.append("  store i8 0, i8* %z\n");
        header.append("  ret i8* %buf\n");
        header.append("prefix:\n");
        header.append("  %r = call i8* @cang.f.strndup(i8* %p, i64 %idx)\n");
        header.append("  ret i8* %r\n");
        header.append("}\n\n");

        if (win) {
            // Windows: leading '/' or '\', or <alpha> ':' + separator at index 2.
            header.append("define i1 @cang.f.isabs(i8* %p) {\n");
            header.append("entry:\n");
            header.append("  %c0 = load i8, i8* %p\n");
            header.append("  %s1 = icmp eq i8 %c0, 47\n");
            header.append("  %s2 = icmp eq i8 %c0, 92\n");
            header.append("  %lead = or i1 %s1, %s2\n");
            header.append("  br i1 %lead, label %yes, label %drive\n");
            header.append("drive:\n");
            header.append("  %geA = icmp uge i8 %c0, 65\n");
            header.append("  %leZ = icmp ule i8 %c0, 90\n");
            header.append("  %up = and i1 %geA, %leZ\n");
            header.append("  %gea = icmp uge i8 %c0, 97\n");
            header.append("  %lez = icmp ule i8 %c0, 122\n");
            header.append("  %lo = and i1 %gea, %lez\n");
            header.append("  %alpha = or i1 %up, %lo\n");
            header.append("  br i1 %alpha, label %cklen, label %no\n");
            header.append("cklen:\n");
            header.append("  %len = call i64 @strlen(i8* %p)\n");
            header.append("  %ge3 = icmp uge i64 %len, 3\n");
            header.append("  br i1 %ge3, label %ckcolon, label %no\n");
            header.append("ckcolon:\n");
            header.append("  %p1 = getelementptr i8, i8* %p, i64 1\n");
            header.append("  %c1 = load i8, i8* %p1\n");
            header.append("  %colon = icmp eq i8 %c1, 58\n");
            header.append("  br i1 %colon, label %cksep, label %no\n");
            header.append("cksep:\n");
            header.append("  %p2 = getelementptr i8, i8* %p, i64 2\n");
            header.append("  %c2 = load i8, i8* %p2\n");
            header.append("  %q1 = icmp eq i8 %c2, 47\n");
            header.append("  %q2 = icmp eq i8 %c2, 92\n");
            header.append("  %ok = or i1 %q1, %q2\n");
            header.append("  br i1 %ok, label %yes, label %no\n");
            header.append("yes:\n");
            header.append("  ret i1 true\n");
            header.append("no:\n");
            header.append("  ret i1 false\n");
            header.append("}\n\n");
        } else {
            header.append("define i1 @cang.f.isabs(i8* %p) {\n");
            header.append("entry:\n");
            header.append("  %c0 = load i8, i8* %p\n");
            header.append("  %r = icmp eq i8 %c0, 47\n");
            header.append("  ret i1 %r\n");
            header.append("}\n\n");
        }

        // abspath: already-absolute paths are copied as-is; relative paths get cwd prepended.
        header.append("define i8* @cang.f.abspath(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %cw = alloca [4096 x i8]\n");
        header.append("  %abs = call i1 @cang.f.isabs(i8* %p)\n");
        header.append("  br i1 %abs, label %done, label %join\n");
        header.append("done:\n");
        header.append("  %copy = call i8* @cang.f.strdup(i8* %p)\n");
        header.append("  ret i8* %copy\n");
        header.append("join:\n");
        header.append("  %cb = getelementptr [4096 x i8], [4096 x i8]* %cw, i32 0, i32 0\n");
        if (win) {
            header.append("  %g = call i8* @_getcwd(i8* %cb, i32 4096)\n");
        } else {
            header.append("  %g = call i8* @getcwd(i8* %cb, i64 4096)\n");
        }
        header.append("  %gn = icmp eq i8* %g, null\n");
        header.append("  br i1 %gn, label %fail, label %concat\n");
        header.append("fail:\n");
        header.append("  %fb = call i8* @cang.f.strdup(i8* %p)\n");
        header.append("  ret i8* %fb\n");
        header.append("concat:\n");
        header.append("  %clen = call i64 @strlen(i8* %g)\n");
        header.append("  %plen = call i64 @strlen(i8* %p)\n");
        header.append("  %last = sub i64 %clen, 1\n");
        header.append("  %lp = getelementptr i8, i8* %g, i64 %last\n");
        header.append("  %lc = load i8, i8* %lp\n");
        header.append("  %e1 = icmp eq i8 %lc, 47\n");
        String endsep;
        if (win) {
            header.append("  %e2 = icmp eq i8 %lc, 92\n");
            header.append("  %es = or i1 %e1, %e2\n");
            endsep = "%es";
        } else {
            endsep = "%e1";
        }
        header.append("  %extra = select i1 ").append(endsep).append(", i64 0, i64 1\n");
        header.append("  %tot0 = add i64 %clen, %extra\n");
        header.append("  %tot1 = add i64 %tot0, %plen\n");
        header.append("  %tot = add i64 %tot1, 1\n");
        header.append("  %buf = call i8* @").append(alloc).append("(i64 %tot)\n");
        header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* %buf, i8* %g, i64 %clen, i1 false)\n");
        header.append("  br i1 ").append(endsep).append(", label %nosep, label %withsep\n");
        header.append("withsep:\n");
        header.append("  %sp = getelementptr i8, i8* %buf, i64 %clen\n");
        header.append("  store i8 ").append(sepChar).append(", i8* %sp\n");
        header.append("  %offw = add i64 %clen, 1\n");
        header.append("  br label %copypart\n");
        header.append("nosep:\n");
        header.append("  br label %copypart\n");
        header.append("copypart:\n");
        header.append("  %off = phi i64 [ %clen, %nosep ], [ %offw, %withsep ]\n");
        header.append("  %dst = getelementptr i8, i8* %buf, i64 %off\n");
        header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* %dst, i8* %p, i64 %plen, i1 false)\n");
        header.append("  %zi = add i64 %off, %plen\n");
        header.append("  %zp = getelementptr i8, i8* %buf, i64 %zi\n");
        header.append("  store i8 0, i8* %zp\n");
        header.append("  ret i8* %buf\n");
        header.append("}\n\n");

        // tosystem: rewrite every separator to the platform separator.
        int fromChar = win ? 47 : 92;
        int toChar = win ? 92 : 47;
        header.append("define i8* @cang.f.tosystem(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %len = call i64 @strlen(i8* %p)\n");
        header.append("  %n1 = add i64 %len, 1\n");
        header.append("  %buf = call i8* @").append(alloc).append("(i64 %n1)\n");
        header.append("  %i = alloca i64\n");
        header.append("  store i64 0, i64* %i\n");
        header.append("  br label %loop\n");
        header.append("loop:\n");
        header.append("  %iv = load i64, i64* %i\n");
        header.append("  %go = icmp ult i64 %iv, %len\n");
        header.append("  br i1 %go, label %body, label %done\n");
        header.append("body:\n");
        header.append("  %sp = getelementptr i8, i8* %p, i64 %iv\n");
        header.append("  %ch = load i8, i8* %sp\n");
        header.append("  %is = icmp eq i8 %ch, ").append(fromChar).append("\n");
        header.append("  %ch2 = select i1 %is, i8 ").append(toChar).append(", i8 %ch\n");
        header.append("  %dp = getelementptr i8, i8* %buf, i64 %iv\n");
        header.append("  store i8 %ch2, i8* %dp\n");
        header.append("  %i2 = add i64 %iv, 1\n");
        header.append("  store i64 %i2, i64* %i\n");
        header.append("  br label %loop\n");
        header.append("done:\n");
        header.append("  %zp = getelementptr i8, i8* %buf, i64 %len\n");
        header.append("  store i8 0, i8* %zp\n");
        header.append("  ret i8* %buf\n");
        header.append("}\n\n");
    }

    /** Filesystem and text-IO helper bodies for File (define-only). */
    private void emitFileIoHelpers() {
        boolean win = targetPlatform.equals("windows");
        String alloc = allocFn();
        String fseekFn = win ? "_fseeki64" : "fseeko";
        String ftellFn = win ? "_ftelli64" : "ftello";
        String mkdirFn = win ? "_mkdir" : "mkdir";
        String rmdirFn = win ? "_rmdir" : "rmdir";
        int dnameOff = win ? 8 : 19; // offsetof(struct dirent, d_name)

        // exists: access(path, F_OK=0) == 0
        header.append("define i1 @cang.f.exists(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %r = call i32 @access(i8* %p, i32 0)\n");
        header.append("  %ok = icmp eq i32 %r, 0\n");
        header.append("  ret i1 %ok\n");
        header.append("}\n\n");

        // isdir: opendir succeeds (works for dirs, fails for files and missing paths).
        header.append("define i1 @cang.f.isdir(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %d = call i8* @opendir(i8* %p)\n");
        header.append("  %n = icmp eq i8* %d, null\n");
        header.append("  br i1 %n, label %no, label %yes\n");
        header.append("yes:\n");
        header.append("  %c = call i32 @closedir(i8* %d)\n");
        header.append("  ret i1 true\n");
        header.append("no:\n");
        header.append("  ret i1 false\n");
        header.append("}\n\n");

        // isfile: exists && !isdir (avoids fopen on directories, which succeeds on Linux).
        header.append("define i1 @cang.f.isfile(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %e = call i1 @cang.f.exists(i8* %p)\n");
        header.append("  br i1 %e, label %chk, label %no\n");
        header.append("chk:\n");
        header.append("  %d = call i1 @cang.f.isdir(i8* %p)\n");
        header.append("  %r = xor i1 %d, 1\n");
        header.append("  ret i1 %r\n");
        header.append("no:\n");
        header.append("  ret i1 false\n");
        header.append("}\n\n");

        // length: size of a regular file via seek-to-end; 0 for dirs/missing/unseekable.
        header.append("define i64 @cang.f.length(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %f = call i1 @cang.f.isfile(i8* %p)\n");
        header.append("  br i1 %f, label %open, label %zero\n");
        header.append("open:\n");
        header.append("  %mrb = getelementptr [3 x i8], [3 x i8]* @.f.rb, i32 0, i32 0\n");
        header.append("  %h = call i8* @fopen(i8* %p, i8* %mrb)\n");
        header.append("  %hn = icmp eq i8* %h, null\n");
        header.append("  br i1 %hn, label %zero, label %seek\n");
        header.append("seek:\n");
        header.append("  %s1 = call i32 @").append(fseekFn).append("(i8* %h, i64 0, i32 2)\n");
        header.append("  %sz = call i64 @").append(ftellFn).append("(i8* %h)\n");
        header.append("  %c = call i32 @fclose(i8* %h)\n");
        header.append("  %neg = icmp slt i64 %sz, 0\n");
        header.append("  %z = select i1 %neg, i64 0, i64 %sz\n");
        header.append("  ret i64 %z\n");
        header.append("zero:\n");
        header.append("  ret i64 0\n");
        header.append("}\n\n");

        // delete: rmdir for directories, remove for files.
        header.append("define i1 @cang.f.delete(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %d = call i1 @cang.f.isdir(i8* %p)\n");
        header.append("  br i1 %d, label %dir, label %file\n");
        header.append("dir:\n");
        header.append("  %r1 = call i32 @").append(rmdirFn).append("(i8* %p)\n");
        header.append("  %ok1 = icmp eq i32 %r1, 0\n");
        header.append("  ret i1 %ok1\n");
        header.append("file:\n");
        header.append("  %r2 = call i32 @remove(i8* %p)\n");
        header.append("  %ok2 = icmp eq i32 %r2, 0\n");
        header.append("  ret i1 %ok2\n");
        header.append("}\n\n");

        // mkdir: single-level creation. Windows takes one argument; POSIX wants a mode.
        header.append("define i1 @cang.f.mkdir(i8* %p) {\n");
        header.append("entry:\n");
        if (win) {
            header.append("  %r = call i32 @_mkdir(i8* %p)\n");
        } else {
            header.append("  %r = call i32 @mkdir(i8* %p, i32 511)\n"); // 0777
        }
        header.append("  %ok = icmp eq i32 %r, 0\n");
        header.append("  ret i1 %ok\n");
        header.append("}\n\n");

        // mkdirs: copy the path, strip trailing separators, mkdir() every prefix (ignore
        // errors: they are usually EEXIST), then mkdir() the full path for the result.
        header.append("define i1 @cang.f.mkdirs(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %len0 = call i64 @strlen(i8* %p)\n");
        header.append("  %n1 = add i64 %len0, 1\n");
        header.append("  %buf = call i8* @").append(alloc).append("(i64 %n1)\n");
        header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* %buf, i8* %p, i64 %n1, i1 false)\n");
        header.append("  %L = alloca i64\n");
        header.append("  store i64 %len0, i64* %L\n");
        header.append("  %i = alloca i64\n");
        header.append("  br label %trimchk\n");
        header.append("trimchk:\n");
        header.append("  %lv = load i64, i64* %L\n");
        header.append("  %tg = icmp ugt i64 %lv, 1\n");
        header.append("  br i1 %tg, label %trimbody, label %walk\n");
        header.append("trimbody:\n");
        header.append("  %li = sub i64 %lv, 1\n");
        header.append("  %tp = getelementptr i8, i8* %buf, i64 %li\n");
        header.append("  %tc = load i8, i8* %tp\n");
        header.append("  %t1 = icmp eq i8 %tc, 47\n");
        if (win) {
            header.append("  %t2 = icmp eq i8 %tc, 92\n");
            header.append("  %ts = or i1 %t1, %t2\n");
        } else {
            header.append("  %ts = icmp eq i8 %tc, 47\n");
        }
        header.append("  br i1 %ts, label %trimdec, label %walk\n");
        header.append("trimdec:\n");
        header.append("  store i64 %li, i64* %L\n");
        header.append("  store i8 0, i8* %tp\n");
        header.append("  br label %trimchk\n");
        header.append("walk:\n");
        header.append("  store i64 1, i64* %i\n");
        header.append("  br label %wloop\n");
        header.append("wloop:\n");
        header.append("  %iv = load i64, i64* %i\n");
        header.append("  %wl = load i64, i64* %L\n");
        header.append("  %go = icmp ult i64 %iv, %wl\n");
        header.append("  br i1 %go, label %wbody, label %final\n");
        header.append("wbody:\n");
        header.append("  %wp = getelementptr i8, i8* %buf, i64 %iv\n");
        header.append("  %wc = load i8, i8* %wp\n");
        header.append("  %w1 = icmp eq i8 %wc, 47\n");
        if (win) {
            header.append("  %w2 = icmp eq i8 %wc, 92\n");
            header.append("  %ws = or i1 %w1, %w2\n");
        } else {
            header.append("  %ws = icmp eq i8 %wc, 47\n");
        }
        header.append("  br i1 %ws, label %wsep, label %wnext\n");
        header.append("wsep:\n");
        header.append("  store i8 0, i8* %wp\n");
        header.append("  %mr = call i1 @cang.f.mkdir(i8* %buf)\n");
        header.append("  store i8 %wc, i8* %wp\n");
        header.append("  br label %wnext\n");
        header.append("wnext:\n");
        header.append("  %i2 = add i64 %iv, 1\n");
        header.append("  store i64 %i2, i64* %i\n");
        header.append("  br label %wloop\n");
        header.append("final:\n");
        header.append("  %fr = call i1 @cang.f.mkdir(i8* %buf)\n");
        header.append("  ret i1 %fr\n");
        header.append("}\n\n");

        // create: createNewFile — false when it exists, fopen("wb") to create.
        header.append("define i1 @cang.f.create(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %e = call i1 @cang.f.exists(i8* %p)\n");
        header.append("  br i1 %e, label %no, label %mk\n");
        header.append("no:\n");
        header.append("  ret i1 false\n");
        header.append("mk:\n");
        header.append("  %mwb = getelementptr [3 x i8], [3 x i8]* @.f.wb, i32 0, i32 0\n");
        header.append("  %h = call i8* @fopen(i8* %p, i8* %mwb)\n");
        header.append("  %hn = icmp eq i8* %h, null\n");
        header.append("  br i1 %hn, label %fail, label %ok\n");
        header.append("fail:\n");
        header.append("  ret i1 false\n");
        header.append("ok:\n");
        header.append("  %c = call i32 @fclose(i8* %h)\n");
        header.append("  ret i1 true\n");
        header.append("}\n\n");

        // rename: POSIX rename(2) semantics (same-volume move).
        header.append("define i1 @cang.f.rename(i8* %a, i8* %b) {\n");
        header.append("entry:\n");
        header.append("  %r = call i32 @rename(i8* %a, i8* %b)\n");
        header.append("  %ok = icmp eq i32 %r, 0\n");
        header.append("  ret i1 %ok\n");
        header.append("}\n\n");

        // readtext: whole file into a heap string; null when the file cannot be opened.
        header.append("define i8* @cang.f.readtext(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %mrb = getelementptr [3 x i8], [3 x i8]* @.f.rb, i32 0, i32 0\n");
        header.append("  %h = call i8* @fopen(i8* %p, i8* %mrb)\n");
        header.append("  %hn = icmp eq i8* %h, null\n");
        header.append("  br i1 %hn, label %fail, label %size\n");
        header.append("fail:\n");
        header.append("  ret i8* null\n");
        header.append("size:\n");
        header.append("  %s1 = call i32 @").append(fseekFn).append("(i8* %h, i64 0, i32 2)\n");
        header.append("  %sz = call i64 @").append(ftellFn).append("(i8* %h)\n");
        header.append("  %s2 = call i32 @").append(fseekFn).append("(i8* %h, i64 0, i32 0)\n");
        header.append("  %neg = icmp slt i64 %sz, 0\n");
        header.append("  br i1 %neg, label %failclose, label %alloc\n");
        header.append("failclose:\n");
        header.append("  %c0 = call i32 @fclose(i8* %h)\n");
        header.append("  ret i8* null\n");
        header.append("alloc:\n");
        header.append("  %n1 = add i64 %sz, 1\n");
        header.append("  %buf = call i8* @").append(alloc).append("(i64 %n1)\n");
        header.append("  %rd = call i64 @fread(i8* %buf, i64 1, i64 %sz, i8* %h)\n");
        header.append("  %zp = getelementptr i8, i8* %buf, i64 %rd\n");
        header.append("  store i8 0, i8* %zp\n");
        header.append("  %c = call i32 @fclose(i8* %h)\n");
        header.append("  ret i8* %buf\n");
        header.append("}\n\n");

        // write: mode 0 = overwrite ("wb"), 1 = append ("ab"); null content writes nothing.
        header.append("define i1 @cang.f.write(i8* %p, i8* %content, i32 %mode) {\n");
        header.append("entry:\n");
        header.append("  %isab = icmp eq i32 %mode, 1\n");
        header.append("  %mwb = getelementptr [3 x i8], [3 x i8]* @.f.wb, i32 0, i32 0\n");
        header.append("  %mab = getelementptr [3 x i8], [3 x i8]* @.f.ab, i32 0, i32 0\n");
        header.append("  %m = select i1 %isab, i8* %mab, i8* %mwb\n");
        header.append("  %h = call i8* @fopen(i8* %p, i8* %m)\n");
        header.append("  %hn = icmp eq i8* %h, null\n");
        header.append("  br i1 %hn, label %fail, label %chk\n");
        header.append("fail:\n");
        header.append("  ret i1 false\n");
        header.append("chk:\n");
        header.append("  %cn = icmp eq i8* %content, null\n");
        header.append("  br i1 %cn, label %empty, label %calc\n");
        header.append("calc:\n");
        header.append("  %len = call i64 @strlen(i8* %content)\n");
        header.append("  %nw = call i64 @fwrite(i8* %content, i64 1, i64 %len, i8* %h)\n");
        header.append("  %c1 = call i32 @fclose(i8* %h)\n");
        header.append("  %ok = icmp eq i64 %nw, %len\n");
        header.append("  ret i1 %ok\n");
        header.append("empty:\n");
        header.append("  %c2 = call i32 @fclose(i8* %h)\n");
        header.append("  ret i1 true\n");
        header.append("}\n\n");

        emitFileReadLines(alloc);
        emitFileList(alloc);
    }

    /** readlines: split a whole file into a heap array of heap strings (never null — empty on IO failure). */
    private void emitFileReadLines(String alloc) {
        header.append("define i8* @cang.f.readlines(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %text = call i8* @cang.f.readtext(i8* %p)\n");
        header.append("  %isnull = icmp eq i8* %text, null\n");
        header.append("  br i1 %isnull, label %emptyall, label %count\n");
        header.append("emptyall:\n");
        header.append("  %ea = call i8* @").append(alloc).append("(i64 8)\n");
        header.append("  store i64 0, i64* %ea\n");
        header.append("  ret i8* %ea\n");
        header.append("count:\n");
        header.append("  %len = call i64 @strlen(i8* %text)\n");
        header.append("  %n = alloca i64\n");
        header.append("  store i64 0, i64* %n\n");
        header.append("  %i = alloca i64\n");
        header.append("  store i64 0, i64* %i\n");
        header.append("  br label %cloop\n");
        header.append("cloop:\n");
        header.append("  %iv = load i64, i64* %i\n");
        header.append("  %go = icmp ult i64 %iv, %len\n");
        header.append("  br i1 %go, label %cbody, label %cdone\n");
        header.append("cbody:\n");
        header.append("  %cp = getelementptr i8, i8* %text, i64 %iv\n");
        header.append("  %ch = load i8, i8* %cp\n");
        header.append("  %isnl = icmp eq i8 %ch, 10\n");
        header.append("  br i1 %isnl, label %cinc, label %cnext\n");
        header.append("cinc:\n");
        header.append("  %nv = load i64, i64* %n\n");
        header.append("  %n2 = add i64 %nv, 1\n");
        header.append("  store i64 %n2, i64* %n\n");
        header.append("  br label %cnext\n");
        header.append("cnext:\n");
        header.append("  %i2 = add i64 %iv, 1\n");
        header.append("  store i64 %i2, i64* %i\n");
        header.append("  br label %cloop\n");
        header.append("cdone:\n");
        header.append("  %z = icmp eq i64 %len, 0\n");
        header.append("  br i1 %z, label %mkarr, label %checklast\n");
        header.append("checklast:\n");
        header.append("  %lm1 = sub i64 %len, 1\n");
        header.append("  %lp = getelementptr i8, i8* %text, i64 %lm1\n");
        header.append("  %lc = load i8, i8* %lp\n");
        header.append("  %notnl = icmp ne i8 %lc, 10\n");
        header.append("  br i1 %notnl, label %tailinc, label %mkarr\n");
        header.append("tailinc:\n");
        header.append("  %n3 = load i64, i64* %n\n");
        header.append("  %n4 = add i64 %n3, 1\n");
        header.append("  store i64 %n4, i64* %n\n");
        header.append("  br label %mkarr\n");
        header.append("mkarr:\n");
        header.append("  %cnt = load i64, i64* %n\n");
        header.append("  %dsz = shl i64 %cnt, 3\n");
        header.append("  %tsz = add i64 %dsz, 8\n");
        header.append("  %arr = call i8* @").append(alloc).append("(i64 %tsz)\n");
        header.append("  store i64 %cnt, i64* %arr\n");
        header.append("  %data = getelementptr i8, i8* %arr, i64 8\n");
        header.append("  %typed = bitcast i8* %data to i8**\n");
        header.append("  %start = alloca i64\n");
        header.append("  store i64 0, i64* %start\n");
        header.append("  %j = alloca i64\n");
        header.append("  store i64 0, i64* %j\n");
        header.append("  %slot = alloca i64\n");
        header.append("  store i64 0, i64* %slot\n");
        header.append("  br label %floop\n");
        header.append("floop:\n");
        header.append("  %jv = load i64, i64* %j\n");
        header.append("  %fgo = icmp ult i64 %jv, %len\n");
        header.append("  br i1 %fgo, label %fbody, label %tail\n");
        header.append("fbody:\n");
        header.append("  %jp = getelementptr i8, i8* %text, i64 %jv\n");
        header.append("  %jc = load i8, i8* %jp\n");
        header.append("  %jisnl = icmp eq i8 %jc, 10\n");
        header.append("  br i1 %jisnl, label %emit, label %fnext\n");
        header.append("emit:\n");
        header.append("  %sv = load i64, i64* %start\n");
        header.append("  %ll0 = sub i64 %jv, %sv\n");
        header.append("  %gtpos = icmp ugt i64 %ll0, 0\n");
        header.append("  br i1 %gtpos, label %chkcr, label %donestr\n");
        header.append("chkcr:\n");
        header.append("  %last = sub i64 %jv, 1\n");
        header.append("  %pp = getelementptr i8, i8* %text, i64 %last\n");
        header.append("  %pc = load i8, i8* %pp\n");
        header.append("  %iscr = icmp eq i8 %pc, 13\n");
        header.append("  br i1 %iscr, label %crtrim, label %donestr\n");
        header.append("crtrim:\n");
        header.append("  %ll = sub i64 %ll0, 1\n");
        header.append("  br label %storeline\n");
        header.append("donestr:\n");
        header.append("  br label %storeline\n");
        header.append("storeline:\n");
        header.append("  %l = phi i64 [ %ll0, %donestr ], [ %ll, %crtrim ]\n");
        header.append("  %src = getelementptr i8, i8* %text, i64 %sv\n");
        header.append("  %line = call i8* @cang.f.strndup(i8* %src, i64 %l)\n");
        header.append("  %sl = load i64, i64* %slot\n");
        header.append("  %ep = getelementptr i8*, i8** %typed, i64 %sl\n");
        header.append("  store i8* %line, i8** %ep\n");
        header.append("  %sl2 = add i64 %sl, 1\n");
        header.append("  store i64 %sl2, i64* %slot\n");
        header.append("  %ns = add i64 %jv, 1\n");
        header.append("  store i64 %ns, i64* %start\n");
        header.append("  br label %fnext\n");
        header.append("fnext:\n");
        header.append("  %j2 = add i64 %jv, 1\n");
        header.append("  store i64 %j2, i64* %j\n");
        header.append("  br label %floop\n");
        header.append("tail:\n");
        header.append("  %sv2 = load i64, i64* %start\n");
        header.append("  %has = icmp ult i64 %sv2, %len\n");
        header.append("  br i1 %has, label %emitlast, label %fin\n");
        header.append("emitlast:\n");
        header.append("  %ll1 = sub i64 %len, %sv2\n");
        header.append("  %gt1 = icmp ugt i64 %ll1, 0\n");
        header.append("  br i1 %gt1, label %chkcr1, label %donestr1\n");
        header.append("chkcr1:\n");
        header.append("  %last1 = sub i64 %len, 1\n");
        header.append("  %pp1 = getelementptr i8, i8* %text, i64 %last1\n");
        header.append("  %pc1 = load i8, i8* %pp1\n");
        header.append("  %iscr1 = icmp eq i8 %pc1, 13\n");
        header.append("  br i1 %iscr1, label %crtrim1, label %donestr1\n");
        header.append("crtrim1:\n");
        header.append("  %ll1b = sub i64 %ll1, 1\n");
        header.append("  br label %storelast\n");
        header.append("donestr1:\n");
        header.append("  br label %storelast\n");
        header.append("storelast:\n");
        header.append("  %l1 = phi i64 [ %ll1, %donestr1 ], [ %ll1b, %crtrim1 ]\n");
        header.append("  %src1 = getelementptr i8, i8* %text, i64 %sv2\n");
        header.append("  %line1 = call i8* @cang.f.strndup(i8* %src1, i64 %l1)\n");
        header.append("  %sl1 = load i64, i64* %slot\n");
        header.append("  %ep1 = getelementptr i8*, i8** %typed, i64 %sl1\n");
        header.append("  store i8* %line1, i8** %ep1\n");
        header.append("  %sl1b = add i64 %sl1, 1\n");
        header.append("  store i64 %sl1b, i64* %slot\n");
        header.append("  br label %fin\n");
        header.append("fin:\n");
        // The whole-file buffer is a temporary: every line was strndup'd above (debug.md #17).
        header.append("  call void @").append(freeFn()).append("(i8* %text)\n");
        header.append("  ret i8* %arr\n");
        header.append("}\n\n");
    }

    /** list: two readdir passes (count, then copy names); null when the path is not a directory. */
    private void emitFileList(String alloc) {
        boolean win = targetPlatform.equals("windows");
        int dnameOff = win ? 8 : 19;
        header.append("define i8* @cang.f.list(i8* %p) {\n");
        header.append("entry:\n");
        header.append("  %d = call i8* @opendir(i8* %p)\n");
        header.append("  %dn = icmp eq i8* %d, null\n");
        header.append("  br i1 %dn, label %retnull, label %count\n");
        header.append("retnull:\n");
        header.append("  ret i8* null\n");
        header.append("count:\n");
        header.append("  %n = alloca i64\n");
        header.append("  store i64 0, i64* %n\n");
        header.append("  br label %cloop\n");
        header.append("cloop:\n");
        header.append("  %de = call i8* @readdir(i8* %d)\n");
        header.append("  %en = icmp eq i8* %de, null\n");
        header.append("  br i1 %en, label %cdone, label %cbody\n");
        header.append("cbody:\n");
        header.append("  %nm = getelementptr i8, i8* %de, i64 ").append(dnameOff).append("\n");
        header.append("  %c1 = call i32 @strcmp(i8* %nm, i8* @.f.dot)\n");
        header.append("  %e1 = icmp eq i32 %c1, 0\n");
        header.append("  br i1 %e1, label %cloop, label %chkdot\n");
        header.append("chkdot:\n");
        header.append("  %c2 = call i32 @strcmp(i8* %nm, i8* @.f.dotdot)\n");
        header.append("  %e2 = icmp eq i32 %c2, 0\n");
        header.append("  br i1 %e2, label %cloop, label %cinc\n");
        header.append("cinc:\n");
        header.append("  %nv = load i64, i64* %n\n");
        header.append("  %n2 = add i64 %nv, 1\n");
        header.append("  store i64 %n2, i64* %n\n");
        header.append("  br label %cloop\n");
        header.append("cdone:\n");
        header.append("  %cc1 = call i32 @closedir(i8* %d)\n");
        header.append("  %cnt = load i64, i64* %n\n");
        header.append("  %dsz = shl i64 %cnt, 3\n");
        header.append("  %tsz = add i64 %dsz, 8\n");
        header.append("  %arr = call i8* @").append(alloc).append("(i64 %tsz)\n");
        header.append("  store i64 %cnt, i64* %arr\n");
        header.append("  %data = getelementptr i8, i8* %arr, i64 8\n");
        header.append("  %typed = bitcast i8* %data to i8**\n");
        header.append("  %d2 = call i8* @opendir(i8* %p)\n");
        header.append("  %d2n = icmp eq i8* %d2, null\n");
        header.append("  br i1 %d2n, label %resempty, label %fill\n");
        header.append("resempty:\n");
        header.append("  store i64 0, i64* %arr\n");
        header.append("  ret i8* %arr\n");
        header.append("fill:\n");
        header.append("  %slot = alloca i64\n");
        header.append("  store i64 0, i64* %slot\n");
        header.append("  br label %floop\n");
        header.append("floop:\n");
        header.append("  %de2 = call i8* @readdir(i8* %d2)\n");
        header.append("  %en2 = icmp eq i8* %de2, null\n");
        header.append("  br i1 %en2, label %fdone, label %fbody\n");
        header.append("fbody:\n");
        header.append("  %nm2 = getelementptr i8, i8* %de2, i64 ").append(dnameOff).append("\n");
        header.append("  %f1 = call i32 @strcmp(i8* %nm2, i8* @.f.dot)\n");
        header.append("  %fe1 = icmp eq i32 %f1, 0\n");
        header.append("  br i1 %fe1, label %floop, label %fchk\n");
        header.append("fchk:\n");
        header.append("  %f2 = call i32 @strcmp(i8* %nm2, i8* @.f.dotdot)\n");
        header.append("  %fe2 = icmp eq i32 %f2, 0\n");
        header.append("  br i1 %fe2, label %floop, label %fpush\n");
        header.append("fpush:\n");
        header.append("  %sl = load i64, i64* %slot\n");
        header.append("  %cap = icmp ult i64 %sl, %cnt\n");
        header.append("  br i1 %cap, label %dostore, label %fdone\n");
        header.append("dostore:\n");
        header.append("  %dup = call i8* @cang.f.strdup(i8* %nm2)\n");
        header.append("  %ep = getelementptr i8*, i8** %typed, i64 %sl\n");
        header.append("  store i8* %dup, i8** %ep\n");
        header.append("  %sl2 = add i64 %sl, 1\n");
        header.append("  store i64 %sl2, i64* %slot\n");
        header.append("  br label %floop\n");
        header.append("fdone:\n");
        header.append("  %cc2 = call i32 @closedir(i8* %d2)\n");
        header.append("  %fin = load i64, i64* %slot\n");
        header.append("  store i64 %fin, i64* %arr\n");
        header.append("  ret i8* %arr\n");
        header.append("}\n\n");
    }

    /** Emit `define ... @cang_io_File.<name>(...)` plus the path-field load; returns the path value. */
    private String fileMethodPrologue(String retType, String name, String params, int pathIdx) {
        header.append("define ").append(retType).append(" @cang_io_File.").append(name)
              .append("(").append(params).append(") {\n");
        header.append("entry:\n");
        String pp = "%f.pp." + tmpCount++;
        String path = "%f.path." + tmpCount++;
        header.append("  ").append(pp).append(" = getelementptr %cang_io_File, %cang_io_File* %this, i32 0, i32 ")
              .append(pathIdx).append("\n");
        header.append("  ").append(path).append(" = load i8*, i8** ").append(pp).append("\n");
        return path;
    }

    /** Wrapper defines for all 20 native File methods — these satisfy the generic call sites. */
    private void emitFileWrappers() {
        ClassInfo fileCi = classes.get("cang_io_File");
        if (fileCi == null) return;
        Integer pIdx = fileCi.fieldIndices.get("path");
        if (pIdx == null) return;
        int pathIdx = pIdx;
        String thisParam = "%cang_io_File* %this";

        // getPath: the stored path string itself (no copy).
        String p0 = fileMethodPrologue("i8*", "getPath", thisParam, pathIdx);
        header.append("  ret i8* ").append(p0).append("\n}\n\n");

        // getName / getParent / getAbsolutePath / toSystemPath
        String[][] strHelpers = {
            { "getName", "cang.f.getname" },
            { "getParent", "cang.f.getparent" },
            { "getAbsolutePath", "cang.f.abspath" },
            { "toSystemPath", "cang.f.tosystem" },
        };
        for (String[] m : strHelpers) {
            String path = fileMethodPrologue("i8*", m[0], thisParam, pathIdx);
            String r = "%r." + tmpCount++;
            header.append("  ").append(r).append(" = call i8* @").append(m[1]).append("(i8* ").append(path).append(")\n");
            header.append("  ret i8* ").append(r).append("\n}\n\n");
        }

        // bool-returning single-arg helpers
        String[][] boolHelpers = {
            { "isAbsolute", "cang.f.isabs" },
            { "exists", "cang.f.exists" },
            { "isFile", "cang.f.isfile" },
            { "isDirectory", "cang.f.isdir" },
            { "delete", "cang.f.delete" },
            { "mkdir", "cang.f.mkdir" },
            { "mkdirs", "cang.f.mkdirs" },
            { "createNewFile", "cang.f.create" },
        };
        for (String[] m : boolHelpers) {
            String path = fileMethodPrologue("i1", m[0], thisParam, pathIdx);
            String r = "%r." + tmpCount++;
            header.append("  ").append(r).append(" = call i1 @").append(m[1]).append("(i8* ").append(path).append(")\n");
            header.append("  ret i1 ").append(r).append("\n}\n\n");
        }

        // length -> i64
        String lp = fileMethodPrologue("i64", "length", thisParam, pathIdx);
        String lr = "%r." + tmpCount++;
        header.append("  ").append(lr).append(" = call i64 @cang.f.length(i8* ").append(lp).append(")\n");
        header.append("  ret i64 ").append(lr).append("\n}\n\n");

        // renameTo(File dest): load both path fields.
        {
            header.append("define i1 @cang_io_File.renameTo(%cang_io_File* %this, %cang_io_File* %dest) {\n");
            header.append("entry:\n");
            String pp1 = "%f.pp." + tmpCount++;
            String pa = "%f.path." + tmpCount++;
            header.append("  ").append(pp1).append(" = getelementptr %cang_io_File, %cang_io_File* %this, i32 0, i32 ")
                  .append(pathIdx).append("\n");
            header.append("  ").append(pa).append(" = load i8*, i8** ").append(pp1).append("\n");
            String pp2 = "%f.dpp." + tmpCount++;
            String pb = "%f.dpath." + tmpCount++;
            header.append("  ").append(pp2).append(" = getelementptr %cang_io_File, %cang_io_File* %dest, i32 0, i32 ")
                  .append(pathIdx).append("\n");
            header.append("  ").append(pb).append(" = load i8*, i8** ").append(pp2).append("\n");
            String r = "%r." + tmpCount++;
            header.append("  ").append(r).append(" = call i1 @cang.f.rename(i8* ").append(pa)
                  .append(", i8* ").append(pb).append(")\n");
            header.append("  ret i1 ").append(r).append("\n}\n\n");
        }

        // readText -> i8*
        String rp = fileMethodPrologue("i8*", "readText", thisParam, pathIdx);
        String rr = "%r." + tmpCount++;
        header.append("  ").append(rr).append(" = call i8* @cang.f.readtext(i8* ").append(rp).append(")\n");
        header.append("  ret i8* ").append(rr).append("\n}\n\n");

        // writeText / appendText: mode 0 = overwrite, 1 = append.
        String[][] ioMethods = {
            { "writeText", "0" },
            { "appendText", "1" },
        };
        for (String[] m : ioMethods) {
            String path = fileMethodPrologue("i1", m[0], thisParam + ", i8* %content", pathIdx);
            String r = "%r." + tmpCount++;
            header.append("  ").append(r).append(" = call i1 @cang.f.write(i8* ").append(path)
                  .append(", i8* %content, i32 ").append(m[1]).append(")\n");
            header.append("  ret i1 ").append(r).append("\n}\n\n");
        }

        // readLines / list -> i8* (Array<String> at the call site)
        String[][] arrayMethods = {
            { "readLines", "cang.f.readlines" },
            { "list", "cang.f.list" },
        };
        for (String[] m : arrayMethods) {
            String path = fileMethodPrologue("i8*", m[0], thisParam, pathIdx);
            String r = "%r." + tmpCount++;
            header.append("  ").append(r).append(" = call i8* @").append(m[1]).append("(i8* ").append(path).append(")\n");
            header.append("  ret i8* ").append(r).append("\n}\n\n");
        }
    }

    private String semanticKindOf(String cangType) {
        return cangType != null && (cangType.equals("str") || cangType.equals("String") || isFunctionType(cangType) || isListType(cangType) || isThreadType(cangType))
            ? cangType : null;
    }

    private boolean isFunctionType(String cangType) {
        return cangType != null && cangType.startsWith("Function<") && cangType.endsWith(">");
    }

    private boolean isArraySemanticType(String cangType) {
        return cangType != null && cangType.startsWith("Array<") && cangType.endsWith(">");
    }

    private boolean isListType(String cangType) {
        return cangType != null && ((cangType.startsWith("List<") && cangType.endsWith(">")) || cangType.startsWith("List_"));
    }

    private String listElementType(String cangType) {
        if (cangType.startsWith("List<")) return cangType.substring(5, cangType.length() - 1);
        if (cangType.startsWith("List_")) return cangType.substring(5);
        return null;
    }

    private String[] functionTypeParts(String cangType) {
        String inner = cangType.substring(9, cangType.length() - 1);
        if (inner.isEmpty()) return new String[0];
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(inner.substring(start));
        return parts.toArray(new String[0]);
    }

    private String functionPointerType(FuncInfo fi) {
        StringBuilder result = new StringBuilder(toLLVMType(fi.returnType)).append(" (");
        for (int i = 0; i < fi.paramTypes.size(); i++) {
            if (i > 0) result.append(", ");
            result.append(toLLVMType(fi.paramTypes.get(i)));
        }
        return result.append(")*").toString();
    }

    private boolean isFunctionPointerType(String llvmType) {
        return llvmType != null && llvmType.contains("(") && llvmType.endsWith(")*");
    }

    private LLVMValue generateIndirectCall(LLVMValue callable, List<AST> nodes, int line) {
        String signature = callable.semanticType;
        if (signature == null || !isFunctionType(signature)) {
            throw new RuntimeException("Cannot call value without Function type (at line " + line + ")");
        }
        String[] parts = functionTypeParts(signature);
        if (parts.length == 0) {
            throw new RuntimeException("Invalid Function type: " + signature + " (at line " + line + ")");
        }
        String returnCang = parts[0];
        String returnLLVM = toLLVMType(returnCang);
        int paramCount = parts.length - 1;
        if (nodes.size() != paramCount) {
            throw new RuntimeException("Function argument count mismatch: expected " + paramCount
                + " but found " + nodes.size() + " (at line " + line + ")");
        }

        String code = "%fn.code." + tmpCount++;
        body.append("  ").append(code).append(" = extractvalue %CangFunction ").append(callable.value).append(", 0\n");
        String receiver = "%fn.env." + tmpCount++;
        body.append("  ").append(receiver).append(" = extractvalue %CangFunction ").append(callable.value).append(", 1\n");

        List<String> callArgs = new ArrayList<>();
        callArgs.add("i8* " + receiver);
        StringBuilder paramList = new StringBuilder("i8*");
        for (int i = 0; i < paramCount; i++) {
            String paramCang = parts[i + 1];
            String paramLLVM = toLLVMType(paramCang);
            String savedExpected = expectedFunctionType;
            expectedFunctionType = isFunctionType(paramCang) ? paramCang : null;
            LLVMValue value;
            try {
                value = generateExpr(nodes.get(i));
            } finally {
                expectedFunctionType = savedExpected;
            }
            if (value.semanticType != null && isFunctionType(value.semanticType)) {
                checkFunctionValue(value, paramCang, line);
            } else if (!value.type.equals(paramLLVM) && !typesCompatible(value.type, paramLLVM)
                    && !(isNumericLLVM(value.type) && isNumericLLVM(paramLLVM))) {
                throw new RuntimeException("Function argument type mismatch: expected " + paramCang
                    + " but found " + cangTypeFromLLVMFull(value.type) + " (at line " + line + ")");
            }
            paramList.append(", ").append(paramLLVM);
            callArgs.add(paramLLVM + " " + castValue(value, paramLLVM));
        }

        String codeType = returnLLVM + " (" + paramList + ")*";
        String codePtr = "%fn.ptr." + tmpCount++;
        body.append("  ").append(codePtr).append(" = bitcast i8* ").append(code).append(" to ").append(codeType).append("\n");
        if (returnLLVM.equals("void")) {
            body.append("  call void ").append(codePtr).append("(").append(String.join(", ", callArgs)).append(")\n");
            return new LLVMValue("void", "void");
        }
        String result = "%indirect.call." + tmpCount++;
        body.append("  ").append(result).append(" = call ").append(returnLLVM).append(" ")
             .append(codePtr).append("(").append(String.join(", ", callArgs)).append(")\n");
        return new LLVMValue(result, returnLLVM);
    }

    private String toLLVMType(String cangType) {
        // Function values are uniform { code, receiver } structs.
        if (isFunctionType(cangType)) {
            return "%CangFunction";
        }
        if (isThreadType(cangType)) {
            return "%CangThreadHandle*";
        }
        // Array<T> is represented as i8* (pointer to length-prefixed data)
        if (cangType.startsWith("Array<") && cangType.endsWith(">")) {
            return "i8*";
        }
        switch (cangType) {
            case "byte": return "i8";
            case "int": return "i32";
            case "long": return "i64";
            case "float": return "float";
            case "double": return "double";
            case "bool": return "i1";
            case "String": return "i8*";
            case "str": return "i8*";
            case "void": return "void";
            case "Void": return "void"; // Void wrapper class is the Function return-type spelling
            case "var": return "auto";
            default:
                // Class type - look up by simple name to get full name
                ClassInfo ci = classes.get(cangType);
                if (ci != null) {
                    return ci.llvmName + "*"; // llvmName already includes %
                }
                return "%" + cangType + "*";
        }
    }

    private String cangTypeFromLLVM(String llvmType) {
        switch (llvmType) {
            case "i8": return "byte";
            case "i32": return "int";
            case "i64": return "long";
            case "float": return "float";
            case "double": return "double";
            case "i1": return "bool";
            case "i8*": return "String";
            default: return "object";
        }
    }

    private boolean isFloatType(String llvmType) {
        return llvmType.equals("float") || llvmType.equals("double");
    }

    private String commonIntType(String a, String b) {
        if (a.equals("i64") || b.equals("i64")) return "i64";
        if (a.equals("i32") || b.equals("i32")) return "i32";
        if (a.equals("i8") || b.equals("i8")) return "i8";
        return "i32";
    }

    private String defaultValueForType(String cangType) {
        if (isFunctionType(cangType)) return "zeroinitializer";
        switch (cangType) {
            case "byte": return "0";
            case "int": return "0";
            case "long": return "0";
            case "float": return "0.0";
            case "double": return "0.0";
            case "bool": return "0";
            case "String": return "null";
            case "str": return "null";
            default: return "null";
        }
    }

    private String castValue(LLVMValue val, String targetType) {
        if (val.type.equals(targetType)) return val.value;

        // Int to float
        if (isFloatType(targetType) && !isFloatType(val.type)) {
            String result = "%cast." + tmpCount++;
            body.append("  ").append(result).append(" = sitofp ").append(val.type)
                 .append(" ").append(val.value).append(" to ").append(targetType).append("\n");
            return result;
        }

        // Float to int
        if (!isFloatType(targetType) && isFloatType(val.type)) {
            String result = "%cast." + tmpCount++;
            body.append("  ").append(result).append(" = fptosi ").append(val.type)
                 .append(" ").append(val.value).append(" to ").append(targetType).append("\n");
            return result;
        }

        // Float to float
        if (isFloatType(targetType) && isFloatType(val.type)) {
            if (val.type.equals("float") && targetType.equals("double")) {
                String result = "%cast." + tmpCount++;
                body.append("  ").append(result).append(" = fpext float ").append(val.value).append(" to double\n");
                return result;
            }
            if (val.type.equals("double") && targetType.equals("float")) {
                String result = "%cast." + tmpCount++;
                body.append("  ").append(result).append(" = fptrunc double ").append(val.value).append(" to float\n");
                return result;
            }
        }

        // Int to int (widening)
        int fromBits = llvmTypeBits(val.type);
        int toBits = llvmTypeBits(targetType);
        if (fromBits > 0 && toBits > 0) {
            if (toBits > fromBits) {
                String result = "%cast." + tmpCount++;
                body.append("  ").append(result).append(" = zext ").append(val.type)
                     .append(" ").append(val.value).append(" to ").append(targetType).append("\n");
                return result;
            } else if (toBits < fromBits) {
                String result = "%cast." + tmpCount++;
                body.append("  ").append(result).append(" = trunc ").append(val.type)
                     .append(" ").append(val.value).append(" to ").append(targetType).append("\n");
                return result;
            }
        }

        // bool to int
        if (val.type.equals("i1") && targetType.equals("i32")) {
            String result = "%cast." + tmpCount++;
            body.append("  ").append(result).append(" = zext i1 ").append(val.value).append(" to i32\n");
            return result;
        }

        // If we can't cast, return as-is (will likely cause LLVM error but at least won't crash the compiler)
        return val.value;
    }

    private int llvmTypeBits(String llvmType) {
        switch (llvmType) {
            case "i1": return 1;
            case "i8": return 8;
            case "i32": return 32;
            case "i64": return 64;
            case "float": return 32;
            case "double": return 64;
            default: return -1;
        }
    }

    private String ensureI1(LLVMValue val) {
        if (val.type.equals("i1")) return val.value;
        // Compare to 0
        String result = "%tobool." + tmpCount++;
        if (isFloatType(val.type)) {
            body.append("  ").append(result).append(" = fcmp one ").append(val.type)
                 .append(" ").append(val.value).append(", 0.0\n");
        } else {
            body.append("  ").append(result).append(" = icmp ne ").append(val.type)
                 .append(" ").append(val.value).append(", 0\n");
        }
        return result;
    }

    private String extractClassName(String llvmType) {
        // "%ClassName*" 閳?"ClassName"
        // Strip every trailing '*' so pointer-to-pointer types ("%Inner**" from
        // chained field access p.inner.value) also resolve to the class name.
        if (llvmType.startsWith("%") && llvmType.endsWith("*")) {
            String s = llvmType.substring(1);
            while (s.endsWith("*")) s = s.substring(0, s.length() - 1);
            return s;
        }
        if (llvmType.startsWith("%")) {
            return llvmType.substring(1);
        }
        return llvmType;
    }

    private long estimateStructSize(ClassInfo ci) {
        // Rough estimate for malloc sizing 閳?actual LLVM does this correctly with getelementptr
        long size = 0;
        for (String type : ci.fieldTypes) {
            switch (type) {
                case "byte": size += 1; break;
                case "int": size += 4; break;
                case "long": size += 8; break;
                case "float": size += 4; break;
                case "double": size += 8; break;
                case "bool": size += 1; break;
                default: size += 8; break; // pointer
            }
        }
        return Math.max(size, 1);
    }

    private void emitFuncParamTypes(FuncInfo fi, StringBuilder target) {
        boolean first = true;
        if (fi.className != null && !fi.isConstructor) {
            target.append(toLLVMType(fi.className)).append("*");
            first = false;
        }
        for (String pt : fi.paramTypes) {
            if (!first) target.append(", ");
            target.append(toLLVMType(pt));
            first = false;
        }
    }
}

