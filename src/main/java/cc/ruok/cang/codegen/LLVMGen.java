package cc.ruok.cang.codegen;

import cc.ruok.cang.parser.AST.*;
import cc.ruok.cang.parser.AST;
import cc.ruok.cang.lexer.Lexer;
import cc.ruok.cang.lexer.Token;
import cc.ruok.cang.parser.Parser;
import cc.ruok.cang.util.CompileError;

import java.util.*;

public class LLVMGen {

    // ==================== Data structures ====================

    static class LLVMValue {
        String value;
        String type;
        String semanticType; // Cang type when LLVM types are shared, e.g. String -> i8*
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
        Map<String, FieldDecl> fieldDecls = new LinkedHashMap<>(); // own instance fields by name (declared initializers)
        List<AST> superArgs = new ArrayList<>(); // parent constructor args
        boolean isAbstract; // abstract class: not instantiable, may declare abstract methods
        boolean isPrivateCtor; // '_' modifier on the class name: new only inside the class itself
        // Signatures (","-joined param types) of _init overloads: new matching them is rejected
        // outside the class. Empty set = every constructor is public.
        java.util.Set<String> privateInitSigs = new java.util.LinkedHashSet<>();
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
    private String currentFuncName = "";
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

    // Class literals (Apple.class): cang.lang.Class was auto-loaded and the singleton table +
    // @cang.classinit() body were emitted into the header.
    private boolean classObjectInitEmitted = false;

    /** Class-literal names that are not real ClassInfo entries: String and primitives. */
    private static final String[] SPECIAL_CLASS_LITERAL_NAMES = {
        "String", "byte", "int", "long", "float", "double", "bool", "void"
    };

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
        // Boxed: hidden builtin wrapper for Field.getObject() boxing of primitive fields.
        // Not constructible from Cang source (no constructor); layout { i32 typeId, i64 raw }.
        ClassInfo boxedInfo = new ClassInfo();
        boxedInfo.llvmName = "%cang_lang_Boxed";
        boxedInfo.fullName = "cang_lang_Boxed";
        boxedInfo.simpleName = "Boxed";
        boxedInfo.parentName = null;
        boxedInfo.fieldNames.add("raw");
        boxedInfo.fieldTypes.add("long");
        boxedInfo.fieldIndices.put("raw", 0);
        classes.put("Boxed", boxedInfo);
        classes.put("cang_lang_Boxed", boxedInfo);
    }

    public String generate(Program program) {
        try {
            return generateInternal(program);
        } catch (CompileError e) {
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

        // Only the MAIN file's own class (isEntryPoint) gates top-level statements:
        // imported classes arrive merged first and must not disqualify a class-less main's
        // auto-main statements. Statement-before-class itself is validated on the main
        // program during import resolution; a class-bearing main cannot contribute
        // statements here (the parser rejects them in the class body).
        boolean hasEntryClass = false;
        for (AST m : program.members) {
            if (m instanceof ClassDecl && ((ClassDecl) m).isEntryPoint) { hasEntryClass = true; break; }
        }

        // Main file's own classes only (isEntryPoint): imported classes come first in the
        // merged list and must never be blamed for a missing entry.
        String mainClassName = null;
        int mainClassLine = 1;
        int firstTopFuncLine = 0;
        for (AST member : program.members) {
            if (member instanceof ClassDecl) {
                ClassDecl cd = (ClassDecl) member;
                if (cd.isEntryPoint && mainClassName == null) {
                    mainClassName = cd.name;
                    mainClassLine = cd.line;
                }
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
                // Move declarations to members. The parser rejects statements in the class
                // body (batch 4), so topLevelBody holds declarations only.
                for (AST stmt : new ArrayList<>(cd.topLevelBody)) {
                    if (stmt instanceof FuncDecl || stmt instanceof FieldDecl || stmt instanceof ConstructorDecl) {
                        cd.members.add(stmt);
                        cd.topLevelBody.remove(stmt);
                    }
                }
                classDecls.add(cd);
                collectClass(cd);
            } else if (member instanceof FuncDecl) {
                if (firstTopFuncLine == 0) firstTopFuncLine = member.line;
                collectFunction((FuncDecl) member, null);
                topLevelFuncs.add(member);
            } else if (member instanceof NamespaceDecl || member instanceof ImportDecl) {
                // Skip namespace/import declarations
                continue;
            } else {
                // Top-level statement outside class — legal only in a class-less program;
                // with a class present it would be silently dropped (debug.md #36 pair).
                if (hasEntryClass) {
                    throw new RuntimeException("statements are not allowed before the class (at line " + member.line + ")");
                }
                mainStatements.add(member);
            }
        }

        // Ban writes to captured outer locals inside lambda/thread{} blocks (by-value capture).
        validateLambdaCaptures(classDecls, topLevelFuncs, mainStatements);

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

        // Generate main: static void main(String[] args). A class-bearing program without
        // one is an error (batch 4); class-less programs keep the auto-wrapped top-level
        // statements as their main body.
        if (entryClass != null) {
            FuncInfo mainFi = findStaticMain(entryClass);
            if (mainFi == null) {
                // Pin to the class declaration line (the generate() wrapper only fills in
                // lastStmtLine when no at-line is present).
                throw new RuntimeException("class '" + entryClass.name
                    + "' has no main method (at line " + entryClass.line + ")");
            }
            generateMainEntry(mainFi);
        } else if (!mainStatements.isEmpty()) {
            topLevelStmts.addAll(mainStatements);
            generateAutoMain();
        } else if (mainClassName != null) {
            // Class-bearing main file whose class never became the entry class (generic
            // template) — error here instead of emitting a main-less module.
            throw new RuntimeException("class '" + mainClassName
                + "' has no main method (at line " + mainClassLine + ")");
        } else if (!topLevelFuncs.isEmpty()) {
            // Class-less file with only function definitions: nothing to run.
            throw new RuntimeException("no main method (at line "
                + (firstTopFuncLine > 0 ? firstTopFuncLine : 1) + ")");
        } else {
            // No class, no statements, no functions (imports/namespace only, or an empty
            // file): nothing would be emitted — fail here instead of letting the linker
            // die on "undefined reference to WinMain".
            throw new RuntimeException("no entry point: no class and no top-level statements (at line 1)");
        }

        return header.toString() + "\n" + body.toString() + extraDefs.toString();
    }

    /** The entry point is exactly `static void main(String[] args)` or `static void main()`;
     *  any other `main(...)` signature is an ordinary static method. When both valid forms
     *  exist, the String[] form wins (launcher convention). */
    private FuncInfo findStaticMain(ClassDecl cd) {
        String fullName = declFullNames.get(cd);
        if (fullName == null) return null;
        List<FuncInfo> grp = overloadGroups.get(fullName + ".main");
        if (grp == null) return null;
        FuncInfo zeroArg = null;
        for (FuncInfo fi : grp) {
            if (!"void".equals(fi.returnType) || !fi.isStatic) continue;
            if (fi.paramTypes.size() == 1
                    && ("String[]".equals(fi.paramTypes.get(0)) || "Array<String>".equals(fi.paramTypes.get(0)))) {
                return fi;
            }
            if (fi.paramTypes.isEmpty()) zeroArg = fi;
        }
        return zeroArg;
    }

    /**
     * Entry wrapper for the new model: build System.ARGS, then call
     * static void main(String[] args); thread joins and GC frame mirror generateAutoMain.
     */
    private void generateMainEntry(FuncInfo mainFi) {
        body.append("define i32 @main(i32 %argc, i8** %argv) {\n");
        body.append("entry:\n");
        emitStackBaseSet(body, "main");
        if (gcEnabled) body.append("  call void @GC_init()\n");

        scope = new Scope(null);
        tmpCount = 0;
        currentFuncReturnType = "int";
        emitGcFrameSetup();

        emitArgsArrayInit();
        if (classObjectInitEmitted) body.append("  call void @cang.classinit()\n");

        if (mainFi.paramTypes.isEmpty()) {
            // static void main() — the args array is still initialized so System.ARGS works.
            body.append("  call void @").append(mainFi.llvmName).append("()\n");
            emitBoundaryUnwindCheck(body);
        } else {
            String argsV = "%main.args." + tmpCount++;
            body.append("  ").append(argsV).append(" = load i8*, i8** @system.args\n");
            body.append("  call void @").append(mainFi.llvmName).append("(i8* ").append(argsV).append(")\n");
            emitBoundaryUnwindCheck(body);
        }

        emitGcFramePop();
        emitCoroutineRegistryJoin();
        emitThreadBlockJoins();
        emitRegisteredThreadJoins();
        body.append("  ret i32 0\n");
        body.append("}\n\n");
        scope = null;
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
        // Same guard for body field initializers: `T v = null` in a class field (batch-4
        // field model) specialized to a value type must store a zero, not i32 null.
        if (node instanceof FieldDecl) {
            FieldDecl fd = (FieldDecl) node;
            if (fd.init instanceof NullLit && parameter.equals(fd.type)) {
                if (concrete.equals("bool")) {
                    fd.init = new BoolLit(false, fd.line);
                } else if (concrete.equals("int") || concrete.equals("byte") || concrete.equals("long")
                        || concrete.equals("float") || concrete.equals("double")) {
                    fd.init = new IntLit("0", fd.line);
                }
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
                            + sig + "' from '" + cur.simpleName + "' (at line " + cd.line + ")");
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
            throw new cc.ruok.cang.util.CompileError("Duplicate class: '" + fullName + "'",
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
                    info.fieldDecls.put(f.name, f);
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
        // Built-in registrations and stdlib files can both provide a ClassInfo for the same
        // full name (Object/Stdout): the map then holds two distinct instances. Key IDs by
        // full name so every instance of a class shares one typeId — otherwise the second
        // instance keeps its default 0 and aliases Object in @cang.classobjs.
        Map<String, Integer> idByName = new java.util.HashMap<>();
        ClassInfo obj = classes.get("Object");
        if (obj == null) obj = classes.get("cang_lang_Object");
        if (obj != null) {
            obj.typeId = 0;
            idByName.put(obj.fullName, 0);
        }
        for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
            ClassInfo ci = entry.getValue();
            Integer id = idByName.get(ci.fullName);
            if (id == null) {
                id = nextId++;
                idByName.put(ci.fullName, id);
            }
            ci.typeId = id;
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
            emitPostCallUnwindCheck();
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
            emitPostCallUnwindCheck();
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
                throw new cc.ruok.cang.util.CompileError(
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
                throw new cc.ruok.cang.util.CompileError(
                    "Method '" + decl.name + "' has default parameter values and cannot be overloaded",
                    file, decl.line, srcLine.indexOf(decl.name) >= 0 ? srcLine.indexOf(decl.name) + 1 : 1,
                    srcLine, decl.name.length());
            }
            if (existing.isStatic != decl.isStatic) {
                throw new cc.ruok.cang.util.CompileError(
                    "Method '" + decl.name + "' mixes static and instance declarations; overloads must be uniform",
                    file, decl.line, srcLine.indexOf(decl.name) >= 0 ? srcLine.indexOf(decl.name) + 1 : 1,
                    srcLine, decl.name.length());
            }
            if (existing.paramTypes.equals(newTypes)) {
                throw new cc.ruok.cang.util.CompileError(
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
        String funcName = fullName + ".constructor";
        FuncInfo info = new FuncInfo();
        info.name = funcName;
        info.returnType = "void";
        info.className = fullName;
        info.isConstructor = true;
        info.isNative = decl.isNative;
        boolean hasDefault = false;
        for (Parameter p : decl.params) {
            info.paramTypes.add(p.type);
            info.paramNames.add(p.name);
            info.paramDefaults.add(p.defaultValue);
            if (p.defaultValue != null) hasDefault = true;
        }
        // Overloads share the group (mirrors collectFunction); defaults and overloads
        // are mutually exclusive (user rule, same message as methods).
        List<FuncInfo> grp = overloadGroups.get(funcName);
        if (grp == null) {
            info.llvmName = funcName;
            overloadGroups.put(funcName, new ArrayList<>());
            overloadGroups.get(funcName).add(info);
            functions.put(funcName, info);
        } else {
            for (FuncInfo other : grp) {
                boolean otherDefault = false;
                for (AST d : other.paramDefaults) if (d != null) { otherDefault = true; break; }
                if (hasDefault || otherDefault) {
                    throw new RuntimeException("Constructor '" + decl.className
                        + "' has default parameter values and cannot be overloaded (at line " + decl.line + ")");
                }
            }
            info.llvmName = funcName + "." + grp.size();
            grp.add(info);
        }
        // _init marks this signature private (new forbidden outside the class)
        if (decl.className.startsWith("_")) {
            ClassInfo ci = classes.get(fullName);
            if (ci != null) ci.privateInitSigs.add(String.join(",", info.paramTypes));
        }
    }

    // ==================== Header ====================

    private void emitHeader() {
        header.append("; Generated by Cang compiler\n\n");

        // External declarations
        header.append("declare i8* @malloc(i64)\n");
        header.append("declare i8* @realloc(i8*, i64)\n");
        header.append("declare void @free(i8*)\n");
        header.append("declare i32 @printf(i8*, ...)\n");
        if (!header.toString().contains("declare i32 @fflush(")) {
            header.append("declare i32 @fflush(i8*)\n");
        }
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
        // Cross-function exception propagation (flag-based unwinding): a throw with no
        // local handler sets these and returns; every user-call site checks the flag.
        header.append("@cang.err.pending = global i32 0\n");
        header.append("@cang.err.line = global i32 0\n");
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
        // Handle { osTid, resultSlot, joined, id, parent, kind } — id: atomic 0..N-1;
        // parent: creator's self handle (uncollectable) or null for main;
        // kind: 0 = birth handle (osTid = OS handle / pthread_t), 1 = currentThread self
        // handle (osTid = thread id) — isAlive probes differ per kind on Windows.
        header.append("%CangThreadHandle = type { i64, i8*, i32, i32, i8*, i32 }\n");
        // Object-style Thread: new Thread().task(fn).start() — { task code, task receiver,
        // handle, daemon, id, parent } — id/parent assigned at new Thread(), copied at start().
        header.append("%CangThreadObj = type { i8*, i8*, i8*, i1, i32, i8* }\n");
        // Registry of non-daemon object-style threads: joined at main exit unless daemon.
        header.append("@cang.thr.regs = internal global [256 x i8*] zeroinitializer\n");
        header.append("@cang.thr.regc = internal global i64 0\n");
        // Global logical-id counter (atomic, from 0) + per-thread birth id (-1 = unassigned).
        header.append("@cang.thr.seq = internal global i32 0\n");
        header.append("@cang.thread.id = thread_local global i32 -1\n");
        // Thread.currentThread(): per-thread cached synthetic handle (identity only, not joinable).
        header.append("@cang.thread.self = thread_local global i8* null\n");
        // Birth parent (creator's self handle) written by thread entries; null on main.
        header.append("@cang.thread.parent = thread_local global i8* null\n");
        // Coroutine (libuv single-thread event loop): { code, receiver, done, daemon, id, block }.
        header.append("%CangCo = type { i8*, i8*, i32, i1, i32, i8* }\n");
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
        // String intrinsics support (libc)
        if (!header.toString().contains("declare i32 @strncmp(")) {
            header.append("declare i32 @strncmp(i8*, i8*, i64)\n");
            header.append("declare i8* @strstr(i8*, i8*)\n");
            header.append("declare i32 @memcmp(i8*, i8*, i64)\n");
        }
        // String.parse* (strtoll = 64-bit long on BOTH ABIs, unlike strtol: Win long=32)
        if (!header.toString().contains("declare i64 @strtoll(")) {
            header.append("declare i64 @strtoll(i8*, i8**, i32)\n");
            header.append("declare double @strtod(i8*, i8**)\n");
        }

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
                    int byteCount = utf8Len(sl.value) + 1;
                    String escaped = escapeBytes(sl.value);
                    header.append(strName).append(" = private unnamed_addr constant [").append(byteCount)
                          .append(" x i8] c\"").append(escaped).append("\\00\"\n");
                    stringLiterals.put(strKey, strName);
                }
                String strName = stringLiterals.get(strKey);
                header.append(globalName).append(" = global i8* getelementptr ([")
                      .append(utf8Len(sl.value) + 1).append(" x i8], [")
                      .append(utf8Len(sl.value) + 1).append(" x i8]* ")
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
        emitProcessRuntime();
        emitStringBuilderWrappers();
        emitClassObjects();
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
        // Generate implicit constructor if has params OR has parent — unless an explicit
        // init(...) exists (the new ctor model owns the super chain then).
        boolean hasExplicitInit = false;
        for (AST member : decl.members) {
            if (member instanceof ConstructorDecl) { hasExplicitInit = true; break; }
        }
        if ((!decl.ctorParams.isEmpty() || ci.parentName != null) && !hasExplicitInit) {
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
     * Store each OWN field's declared initializer (or the type default when the field
     * declares none). Parent fields are initialized by the parent constructor. Runs after
     * super() and the type id, before the constructor body - Java initialization order.
     */
    private void emitOwnFieldInitializers(ClassInfo ci) {
        if (ci == null) return;
        int parentFieldCount = 0;
        if (ci.parentName != null) {
            ClassInfo parentInfo = classes.get(ci.parentName);
            if (parentInfo != null) parentFieldCount = parentInfo.fieldNames.size();
        }
        for (int i = parentFieldCount; i < ci.fieldNames.size(); i++) {
            String fieldName = ci.fieldNames.get(i);
            String fieldType = ci.fieldTypes.get(i);
            String llvmType = toLLVMType(fieldType);
            String ptr = "%f.init." + fieldName + "." + tmpCount++;
            body.append("  ").append(ptr).append(" = getelementptr ").append(ci.llvmName)
                 .append(", ").append(ci.llvmName).append("* %this, i32 0, i32 ").append(1 + i).append("\n");
            FieldDecl fd = ci.fieldDecls.get(fieldName);
            String stored;
            if (fd != null && fd.init != null) {
                stored = castValue(generateExpr(fd.init), llvmType);
            } else {
                stored = defaultValueForType(fieldType);
            }
            body.append("  store ").append(llvmType).append(" ").append(stored)
                 .append(", ").append(llvmType).append("* ").append(ptr).append("\n");
        }
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

        // Call parent constructor if inheriting (this sets parent's type ID first).
        // Native parent ctors (Thread's marker inits) have no body to call — skip them.
        if (ci.parentName != null) {
            ClassInfo parentInfo = classes.get(ci.parentName);
            if (parentInfo != null) {
                String parentCtor = parentInfo.fullName + ".constructor";
                FuncInfo parentFi = functions.get(parentCtor);
                if (parentFi != null && !parentFi.isNative) {
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
            emitPostCallUnwindCheck();
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

        // Own fields: declared initializers or type defaults (parent set its own in super()).
        emitOwnFieldInitializers(ci);

        endRegionCleanup();
        emitGcFramePop();
        body.append("  ret void\n");
        body.append("}\n\n");
        scope = null;
    }

    private void generateConstructor(ConstructorDecl decl, String fullName) {
        // Native ctors are declaration-only (IDE surface; the new-expression is intrinsic).
        if (decl.isNative) return;
        // Overload-aware symbol: match this decl's param types inside the group.
        String funcName = fullName + ".constructor";
        String llvmSym = funcName;
        List<FuncInfo> grp = overloadGroups.get(funcName);
        if (grp != null) {
            StringBuilder sig = new StringBuilder();
            for (int i = 0; i < decl.params.size(); i++) {
                if (i > 0) sig.append(",");
                sig.append(decl.params.get(i).type);
            }
            for (FuncInfo f : grp) {
                if (String.join(",", f.paramTypes).equals(sig.toString())) {
                    llvmSym = f.llvmName;
                    break;
                }
            }
        }
        ClassInfo ci = classes.get(fullName);

        body.append("define void @").append(llvmSym).append("(");
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

        // super chain (Java-style): an explicit super(args) as the FIRST statement supplies
        // the parent arguments; otherwise call the parent's no-arg constructor implicitly.
        SuperCall sc = null;
        if (decl.body instanceof Block) {
            List<AST> stmts = ((Block) decl.body).statements;
            if (!stmts.isEmpty() && stmts.get(0) instanceof SuperCall) {
                sc = (SuperCall) stmts.get(0);
            }
        }
        if (ci != null && ci.parentName != null) {
            ClassInfo parentInfo = classes.get(ci.parentName);
            if (parentInfo != null) {
                List<AST> scArgs = sc != null ? sc.args : new ArrayList<>();
                String parentCtor = parentInfo.fullName + ".constructor";
                FuncInfo pfi = null;
                List<FuncInfo> pgrp = overloadGroups.get(parentCtor);
                if (pgrp != null && !pgrp.isEmpty()) {
                    pfi = resolveOverload(pgrp, scArgs, decl.line);
                } else if (functions.containsKey(parentCtor)) {
                    pfi = functions.get(parentCtor);
                }
                if (pfi != null && !pfi.isNative) {
                    // Bitcast this to parent type
                    String parentThis = "%parent.this." + tmpCount++;
                    body.append("  ").append(parentThis).append(" = bitcast %")
                         .append(fullName).append("* %this to %")
                         .append(parentInfo.fullName).append("*\n");
                    StringBuilder pargs = new StringBuilder();
                    pargs.append("%").append(parentInfo.fullName).append("* ").append(parentThis);
                    for (int i = 0; i < scArgs.size(); i++) {
                        LLVMValue argVal = generateExpr(scArgs.get(i));
                        pargs.append(", ").append(argVal.type).append(" ").append(argVal.value);
                    }
                    String pSym = pfi.llvmName != null ? pfi.llvmName : pfi.name;
                    body.append("  call void @").append(pSym).append("(").append(pargs).append(")\n");
            emitPostCallUnwindCheck();
                }
            }
        }

        // Set type ID AFTER the parent constructor (so the child's ID overwrites the parent's)
        String typePtr = "%typeid." + fullName;
        body.append("  ").append(typePtr).append(" = getelementptr %")
             .append(fullName).append(", %").append(fullName).append("* %this, i32 0, i32 0\n");
        body.append("  store i32 ").append(ci != null ? ci.typeId : 0).append(", i32* ").append(typePtr).append("\n");

        // Own fields: declared initializers (or type defaults). Parent fields belong to
        // the parent constructor - Java initialization order: super() -> fields -> body.
        emitOwnFieldInitializers(ci);

        // Generate body (a leading SuperCall statement is a no-op — it was emitted above)
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
        currentFuncName = funcName;
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
                throw new cc.ruok.cang.util.CompileError(
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
        } else if (node instanceof SuperCall) {
            // super(args) inside an init was already emitted by generateConstructor
            // (resolved against the parent overload group before the body runs).
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
        emitUnwindOrHandler(line);
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
        String file = ensureStringConstant("@.str.rtfile." + id, escapeBytes(sourceFile.replace('\\', '/')) + "\\00", utf8Len(sourceFile) + 1);
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
        emitThrowValue(value, stmt.line);
    }

    /** Hand a constructed Error object to the in-scope catch (or the uncaught path). */
    private void emitThrowValue(LLVMValue value, int line) {
        if (!(value.type.endsWith("*") && value.type.startsWith("%"))) {
            throw new RuntimeException("throw requires Error or an Error subclass (at line " + line + ")");
        }
        String raw = "%throw.raw." + tmpCount++;
        body.append("  ").append(raw).append(" = bitcast ").append(value.type).append(" ").append(value.value).append(" to i8*\n");
        body.append("  store i8* ").append(raw).append(", i8** @cang.current.error\n");
        body.append("  store i32 ").append(Math.max(1, line)).append(", i32* @cang.err.line\n");
        if (exceptionHandlers.isEmpty()) {
            // No local catch: mark pending and return from this function so the caller's
            // post-call check picks it up (Java-style cross-function propagation).
            emitStartPropagation();
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

    // ------------------------------------------------------------------
    // Cross-function exception propagation (flag-based unwinding).
    // A throw with no local handler stores @cang.err.pending = 1 and returns from the
    // current function; every user-function/method/constructor call site checks the
    // flag afterwards: with a local catch it is absorbed (cleared, jump to handler),
    // otherwise the frame returns too — unwinding one frame per call boundary.
    // ------------------------------------------------------------------

    /** Set the pending flag and return from the current function (inline finally layers first). */
    private void emitStartPropagation() {
        body.append("  store i32 1, i32* @cang.err.pending\n");
        emitUnwindReturn();
    }

    /**
     * After a user-function/method/constructor call: if the pending flag is set, absorb
     * the error into the lexically enclosing catch (clearing the flag) or return from
     * this function to keep unwinding. Ends by opening a fresh block for the code that
     * follows the call site.
     */
    private void emitPostCallUnwindCheck() {
        int id = labelCount++;
        String p = "%ehc.p." + tmpCount++;
        String nz = "%ehc.nz." + tmpCount++;
        String cont = "eh.cont." + id;
        String unw = "eh.unw." + id;
        body.append("  ").append(p).append(" = load i32, i32* @cang.err.pending\n");
        body.append("  ").append(nz).append(" = icmp ne i32 ").append(p).append(", 0\n");
        body.append("  br i1 ").append(nz).append(", label %").append(unw).append(", label %").append(cont).append("\n");
        body.append(unw).append(":\n");
        if (exceptionHandlers.isEmpty()) {
            emitUnwindReturn();
        } else {
            body.append("  store i32 0, i32* @cang.err.pending\n");
            body.append("  br label %").append(exceptionHandlers.peek()).append("\n");
        }
        body.append(cont).append(":\n");    }

    /** Return from the current function while unwinding: inline finally layers, pop the
     *  GC frame, then ret (void) or ret a zero/default value (the caller discards it when
     *  the pending flag is set). */
    private void emitUnwindReturn() {
        abandonRegion();
        boolean hadFinally = !finallyStack.isEmpty();
        boolean swallowed = inlineFinallyLayers();
        if (swallowed) {
            // A finally layer returned or threw itself: it decides the control flow.
            body.append("uh.dead.").append(labelCount++).append(":\n");
            body.append("  unreachable\n");
            return;
        }
        emitGcFramePop();
        String retType = currentFuncReturnType;
        if (retType == null || retType.equals("void")) {
            body.append("  ret void\n");
        } else {
            String llvm = toLLVMType(retType);
            body.append("  ret ").append(llvm).append(" ").append(defaultLLVMValue(llvm)).append("\n");
        }
        // Statements still left in the caller's buffer after this ret are dead code; they
        // must not dangle as an unterminated block.
        body.append("uh.dead.").append(labelCount++).append(":\n");
        body.append("  unreachable\n");
    }

    /** Unwind decision shared by runtime-error emitters: jump to the local catch, or
     *  start propagating to the caller (the Error object was already stored by them).
     *  Callers append their own dead continuation block afterwards. */
    private void emitUnwindOrHandler(int line) {
        if (exceptionHandlers.isEmpty()) {
            body.append("  store i32 ").append(Math.max(1, line)).append(", i32* @cang.err.line\n");
            emitStartPropagation();
        } else {
            body.append("  br label %").append(exceptionHandlers.peek()).append("\n");
        }
    }

    /** Zero/default value for an LLVM type, used by unwind returns (callers never use it). */
    private String defaultLLVMValue(String llvmType) {
        switch (llvmType) {
            case "i1": return "false";
            case "i8": case "i16": case "i32": case "i64": return "0";
            case "float": return "0.0";
            case "double": return "0.0";
            case "%CangFunction": return "zeroinitializer";
            default: return "null";
        }
    }

    /** Post-call check for boundary wrappers (process main / thread entry): a pending flag
     *  here means the exception escaped every function — print it and exit(1). */
    private void emitBoundaryUnwindCheck(StringBuilder out) {
        int id = labelCount++;
        String p = "%ehb.p." + tmpCount++;
        String nz = "%ehb.nz." + tmpCount++;
        String cont = "eh.bcont." + id;
        String unw = "eh.bunw." + id;
        out.append("  ").append(p).append(" = load i32, i32* @cang.err.pending\n");
        out.append("  ").append(nz).append(" = icmp ne i32 ").append(p).append(", 0\n");
        out.append("  br i1 ").append(nz).append(", label %").append(unw).append(", label %").append(cont).append("\n");
        out.append(unw).append(":\n");
        emitUncaughtFinal(out);
        out.append(cont).append(":\n");
    }

    /** Print the pending error (message from @cang.current.error when Error is known) with
     *  the runtime throw line, then exit(1). Used by main and thread-entry boundaries. */
    private void emitUncaughtFinal(StringBuilder out) {
        out.append("  store i32 0, i32* @cang.err.pending\n");
        String msgReg;
        ClassInfo errorInfo = classes.get("Error");
        if (errorInfo != null && errorInfo.fieldIndices.containsKey("message")) {
            String raw = "%uf.raw." + tmpCount++;
            String isnul = "%uf.isnul." + tmpCount++;
            String typed = "%uf.err." + tmpCount++;
            String fptr = "%uf.msgp." + tmpCount++;
            String msg = "%uf.msg." + tmpCount++;
            String use = "%uf.use." + tmpCount++;
            out.append("  ").append(raw).append(" = load i8*, i8** @cang.current.error\n");
            out.append("  ").append(isnul).append(" = icmp eq i8* ").append(raw).append(", null\n");
            out.append("  ").append(typed).append(" = bitcast i8* ").append(raw).append(" to ").append(errorInfo.llvmName).append("*\n");
            out.append("  ").append(fptr).append(" = getelementptr ").append(errorInfo.llvmName)
                 .append(", ").append(errorInfo.llvmName).append("* ").append(typed)
                 .append(", i32 0, i32 ").append(errorInfo.fieldIndices.get("message")).append("\n");
            out.append("  ").append(msg).append(" = load i8*, i8** ").append(fptr).append("\n");
            String fallback = ensureStringConstant("@.str.uncaught", "uncaught exception\\0A\\00", 20);
            out.append("  ").append(use).append(" = select i1 ").append(isnul).append(", i8* ").append(fallback)
                 .append(", i8* ").append(msg).append("\n");
            msgReg = use;
        } else {
            msgReg = ensureStringConstant("@.str.uncaught", "uncaught exception\\0A\\00", 20);
        }
        String fileName = ensureStringConstant("@.str.srcfile", escapeBytes(sourceFile.replace('\\', '/')) + "\\00", utf8Len(sourceFile) + 1);
        ensureStringConstant("@.str.locfmt", "  at %s:%d\\0A\\00", 12);
        String line = "%uf.line." + tmpCount++;
        out.append("  ").append(line).append(" = load i32, i32* @cang.err.line\n");
        out.append("  call i32 (i8*, ...) @printf(i8* getelementptr ([11 x i8], [11 x i8]* @.str.errprefix, i32 0, i32 0), i8* ")
             .append(msgReg).append(")\n");
        out.append("  call i32 (i8*, ...) @printf(i8* getelementptr ([12 x i8], [12 x i8]* @.str.locfmt, i32 0, i32 0), i8* ")
             .append(fileName).append(", i32 ").append(line).append(")\n");
        out.append("  call void @exit(i32 1)\n");
        out.append("  unreachable\n");
    }

    // ------------------------------------------------------------------
    // Lambda / thread{} capture validation: outer locals are captured BY VALUE,
    // so assigning (or ++) to them inside the block is a compile error (Java-style
    // effectively-final). Fields/globals are shared storage and stay allowed.
    // ------------------------------------------------------------------

    private void validateLambdaCaptures(java.util.List<ClassDecl> classDecls,
                                        java.util.List<AST> topLevelFuncs,
                                        java.util.List<AST> mainStatements) {
        java.util.Deque<java.util.Set<String>> frames = new java.util.ArrayDeque<>();
        for (AST cd : classDecls) {
            if (!(cd instanceof ClassDecl)) continue;
            for (AST cm : ((ClassDecl) cd).members) {
                if (cm instanceof FuncDecl) {
                    validateInFunction((FuncDecl) cm, frames);
                } else if (cm instanceof ConstructorDecl) {
                    validateInCtor((ConstructorDecl) cm, frames);
                } else if (cm instanceof FieldDecl) {
                    AST init = ((FieldDecl) cm).init;
                    if (init != null) {
                        frames.push(new java.util.LinkedHashSet<>());
                        validateCaptures(init, frames);
                        frames.pop();
                    }
                }
            }
        }
        for (AST f : topLevelFuncs) {
            if (f instanceof FuncDecl) validateInFunction((FuncDecl) f, frames);
        }
        if (!mainStatements.isEmpty()) {
            frames.push(new java.util.LinkedHashSet<>());
            for (AST s : mainStatements) validateCaptures(s, frames);
            frames.pop();
        }
    }

    private void validateInFunction(FuncDecl fd, java.util.Deque<java.util.Set<String>> frames) {
        java.util.Set<String> p = new java.util.LinkedHashSet<>();
        if (fd.params != null) for (AST.Parameter prm : fd.params) p.add(prm.name);
        frames.push(p);
        validateCaptures(fd.body, frames);
        frames.pop();
    }

    private void validateInCtor(ConstructorDecl cd, java.util.Deque<java.util.Set<String>> frames) {
        java.util.Set<String> p = new java.util.LinkedHashSet<>();
        if (cd.params != null) for (AST.Parameter prm : cd.params) p.add(prm.name);
        frames.push(p);
        validateCaptures(cd.body, frames);
        frames.pop();
    }

    /** Depth-0 frame = current function; each lambda/thread{} pushes another. */
    private void validateCaptures(AST node, java.util.Deque<java.util.Set<String>> frames) {
        if (node == null) return;

        if (node instanceof LambdaExpr) {
            LambdaExpr lam = (LambdaExpr) node;
            java.util.Set<String> p = new java.util.LinkedHashSet<>();
            if (lam.parameters != null) p.addAll(lam.parameters);
            frames.push(p);
            validateCaptures(lam.body, frames);
            frames.pop();
            return;
        }
        if (node instanceof ThreadBlockStmt) {
            frames.push(new java.util.LinkedHashSet<>());
            validateCaptures(((ThreadBlockStmt) node).body, frames);
            frames.pop();
            return;
        }
        if (node instanceof ThreadBlockExpr) {
            frames.push(new java.util.LinkedHashSet<>());
            validateCaptures(((ThreadBlockExpr) node).body, frames);
            frames.pop();
            return;
        }
        if (node instanceof CoroutineBlockExpr) {
            frames.push(new java.util.LinkedHashSet<>());
            validateCaptures(((CoroutineBlockExpr) node).body, frames);
            frames.pop();
            return;
        }
        if (node instanceof FuncDecl) {
            validateInFunction((FuncDecl) node, frames);
            return;
        }
        if (node instanceof ConstructorDecl) {
            validateInCtor((ConstructorDecl) node, frames);
            return;
        }
        if (node instanceof AssignExpr) {
            AssignExpr a = (AssignExpr) node;
            if (a.target instanceof Identifier) {
                checkCapturedWrite(((Identifier) a.target).name, node.line, frames);
            } else {
                validateCaptures(a.target, frames);
            }
            validateCaptures(a.value, frames);
            return;
        }
        if (node instanceof UnaryExpr) {
            UnaryExpr u = (UnaryExpr) node;
            if ("++".equals(u.op) || "--".equals(u.op)) {
                if (u.operand instanceof Identifier) {
                    checkCapturedWrite(((Identifier) u.operand).name, node.line, frames);
                }
            }
            validateCaptures(u.operand, frames);
            return;
        }
        if (node instanceof VarDecl) {
            VarDecl v = (VarDecl) node;
            validateCaptures(v.init, frames);          // initializer runs before the name exists
            if (frames.peek() != null && v.name != null) frames.peek().add(v.name);
            return;
        }
        if (node instanceof ForEachStmt) {
            ForEachStmt fe = (ForEachStmt) node;
            validateCaptures(fe.iterable, frames);
            if (frames.peek() != null) frames.peek().add(fe.varName);
            validateCaptures(fe.body, frames);
            return;
        }
        if (node instanceof CatchClause) {
            CatchClause cc = (CatchClause) node;
            if (frames.peek() != null && cc.name != null) frames.peek().add(cc.name);
            validateCaptures(cc.body, frames);
            return;
        }

        // Generic walk (statement/expression containers); scope carriers handled above.
        for (java.lang.reflect.Field f : node.getClass().getFields()) {
            try {
                Object v = f.get(node);
                if (v instanceof AST) {
                    validateCaptures((AST) v, frames);
                } else if (v instanceof java.util.List) {
                    for (Object x : (java.util.List<?>) v) {
                        if (x instanceof AST) validateCaptures((AST) x, frames);
                    }
                }
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    /** A write target found in an enclosing frame is a captured outer local — ban it. */
    private void checkCapturedWrite(String name, int line, java.util.Deque<java.util.Set<String>> frames) {
        if (name == null) return;
        int k = 0;
        for (java.util.Set<String> frame : frames) {
            if (frame.contains(name)) {
                if (k == 0) return;          // current frame: own local/param — fine
                throw new RuntimeException("cannot assign to captured variable '" + name
                    + "' (at line " + line + ")");
            }
            k++;
        }
        // Not a local anywhere: field/global/static — shared storage, allowed.
    }

    /** ProcessBuilder.exec returned -2: the executable could not be started (catchable Error). */
    private void emitSpawnFailThrow(String codeVal, int line) {
        int id = labelCount++;
        String sf = "psf.throw." + id, cont = "psf.cont." + id;
        String cmp = "%psf.cmp." + tmpCount++;
        body.append("  ").append(cmp).append(" = icmp eq i32 ").append(codeVal).append(", -2\n");
        body.append("  br i1 ").append(cmp).append(", label %").append(sf).append(", label %").append(cont).append("\n");
        body.append(sf).append(":\n");
        LLVMValue msg = emitSpawnFailMessage();
        LLVMValue err = buildErrorWithMessage(msg);
        if (err != null) {
            emitThrowValue(err, line);
        } else {
            // Program did not import Error: nothing catchable exists — surface it like
            // an uncaught runtime error instead of vanishing.
            emitUncaughtErrorWith(msg, line);
            body.append("psf.dead.").append(id).append(":\n");
        }
        body.append("  br label %").append(cont).append("\n");
        body.append(cont).append(":\n");
    }

    /** "cannot start 'argv0'" (or a plain fallback) formatted into a fresh buffer. */
    private LLVMValue emitSpawnFailMessage() {
        String fmtWith = ensureStringConstant("@.str.psf.with", "cannot start '%s'\\00", 18);
        String fmtPlain = ensureStringConstant("@.str.psf.plain", "cannot start process\\00", 21);
        String buf = "%psf.buf." + tmpCount++;
        body.append("  ").append(buf).append(" = alloca [192 x i8]\n");
        String bp = "%psf.bp." + tmpCount++;
        body.append("  ").append(bp).append(" = bitcast [192 x i8]* ").append(buf).append(" to i8*\n");
        String nm = "%psf.nm." + tmpCount++;
        body.append("  ").append(nm).append(" = load i8*, i8** @cang.proc.spawnname\n");
        String has = "%psf.has." + tmpCount++;
        body.append("  ").append(has).append(" = icmp ne i8* ").append(nm).append(", null\n");
        String fmt = "%psf.fmt." + tmpCount++;
        body.append("  ").append(fmt).append(" = select i1 ").append(has)
             .append(", i8* ").append(fmtWith).append(", i8* ").append(fmtPlain).append("\n");
        body.append("  call i32 (i8*, i8*, ...) @sprintf(i8* ").append(bp)
             .append(", i8* ").append(fmt).append(", i8* ").append(nm).append(")\n");
        return new LLVMValue(bp, "i8*");
    }

    /** Build a fresh Error object carrying the message (null when Error is unavailable). */
    private LLVMValue buildErrorWithMessage(LLVMValue msg) {
        ClassInfo ei = classes.get("Error");
        if (ei == null) return null;
        Integer msgIdx = ei.fieldIndices.get("message");
        if (msgIdx == null) return null;
        String ctorSym = null;
        List<FuncInfo> grp = overloadGroups.get(ei.fullName + ".constructor");
        if (grp != null) {
            for (FuncInfo f : grp) {
                if (f.paramTypes.isEmpty()) {
                    ctorSym = f.llvmName != null ? f.llvmName : f.name;
                    break;
                }
            }
        }
        if (ctorSym == null) return null;
        String sizeVar = "%psf.size." + tmpCount++;
        body.append("  ").append(sizeVar).append(" = getelementptr ").append(ei.llvmName)
             .append(", ").append(ei.llvmName).append("* null, i32 1\n");
        String sz = "%psf.sizeof." + tmpCount++;
        body.append("  ").append(sz).append(" = ptrtoint ").append(ei.llvmName).append("* ")
             .append(sizeVar).append(" to i64\n");
        String raw = "%psf.raw." + tmpCount++;
        body.append("  ").append(raw).append(" = call i8* @").append(allocFn())
             .append("(i64 ").append(sz).append(")\n");
        registerRegionAllocation(raw);
        String obj = "%psf.err." + tmpCount++;
        body.append("  ").append(obj).append(" = bitcast i8* ").append(raw).append(" to ")
             .append(ei.llvmName).append("*\n");
        body.append("  call void @").append(ctorSym).append("(").append(ei.llvmName)
             .append("* ").append(obj).append(")\n");
        String mp = "%psf.msgp." + tmpCount++;
        body.append("  ").append(mp).append(" = getelementptr ").append(ei.llvmName)
             .append(", ").append(ei.llvmName).append("* ").append(obj)
             .append(", i32 0, i32 ").append(msgIdx).append("\n");
        body.append("  store i8* ").append(msg.value).append(", i8** ").append(mp).append("\n");
        return new LLVMValue(obj, ei.llvmName + "*");
    }

    /** Uncaught variant with an explicit message (no Error object involved). */
    private void emitUncaughtErrorWith(LLVMValue msgValue, int line) {
        String uncaught = "uncaught." + labelCount++;
        body.append("  br label %").append(uncaught).append("\n");
        body.append(uncaught).append(":\n");
        String fileName = ensureStringConstant("@.str.srcfile", escapeBytes(sourceFile.replace('\\', '/')) + "\\00", utf8Len(sourceFile) + 1);
        ensureStringConstant("@.str.locfmt", "  at %s:%d\\0A\\00", 12);
        body.append("  call i32 (i8*, ...) @printf(i8* getelementptr ([11 x i8], [11 x i8]* @.str.errprefix, i32 0, i32 0), i8* ")
             .append(msgValue.value).append(")\n");
        body.append("  call i32 (i8*, ...) @printf(i8* getelementptr ([12 x i8], [12 x i8]* @.str.locfmt, i32 0, i32 0), i8* ")
             .append(fileName).append(", i32 ").append(line).append(")\n");
        body.append("  call void @exit(i32 1)\n");
        body.append("  unreachable\n");
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
        String fileName = ensureStringConstant("@.str.srcfile", escapeBytes(sourceFile.replace('\\', '/')) + "\\00", utf8Len(sourceFile) + 1);
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
                // No outer handler in this function: propagate to the caller instead.
                emitStartPropagation();
                body.append("prop.dead.").append(labelCount++).append(":\n");
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

    /** The label currently open in `body` (last plain `xxx:` line). Post-call unwind checks
     *  re-open blocks inside expression arms, so phi preds must name the real branching
     *  block rather than the arm label. */
    private String currentBlockName() {
        String[] lines = body.toString().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.endsWith(":") && !line.contains(" ") && line.matches("[A-Za-z0-9_.]+:")) {
                return line.substring(0, line.length() - 1);
            }
        }
        return "entry";
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

    /** coroutine { ... } sugar: new Coroutine(false).task(() -> { body }).start(). */
    private LLVMValue generateCoroutineBlockExpr(CoroutineBlockExpr node) {
        AST falseFlag = new BoolLit(false, node.line);
        NewExpr coNew = new NewExpr("Coroutine", java.util.Collections.singletonList(falseFlag), node.line);
        LambdaExpr lam = new LambdaExpr(new ArrayList<>(), node.body, "void", node.line);
        MethodCallExpr taskCall = new MethodCallExpr(coNew, "task",
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
                        boolean freeString = gcEnabled && ft.equals("String");
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
            if (cangType.equals("String") &&
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
     *  int <- String that previously produced invalid IR. */
    private boolean assignableTo(String target, LLVMValue val) {
        if (val.type.equals(target)) return true;
        if (typesCompatible(val.type, target)) return true;
        if (isNumericLLVM(val.type) && isNumericLLVM(target)) return true;
        if (val.type.endsWith("*") && target.endsWith("*")) return true; // String/class refs
        if (val.value != null && val.value.equals("null")) {
            return target.endsWith("*") || target.equals("%CangFunction");
        }
        return false;
    }

    /** Return semantic types that must travel with call results (chaining/assign checks). */
    private boolean callResultCarriesSemantic(String cangType) {
        return isArraySemanticType(cangType) || isFunctionType(cangType)
            || cangType.equals("String");
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
                throw new cc.ruok.cang.util.CompileError("Duplicate case value '" + shown + "'",
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
                        "Return type mismatch in " + currentFuncName + ": expected '" + retType +
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
        if (node instanceof CoroutineBlockExpr) {
            return generateCoroutineBlockExpr((CoroutineBlockExpr) node);
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
        if (node instanceof ClassLiteralExpr) {
            return generateClassLiteral((ClassLiteralExpr) node);
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
            int byteCount = utf8Len(text) + 1; // +1 for null terminator
            String escaped = escapeBytes(text);
            header.append(name).append(" = private unnamed_addr constant [").append(byteCount)
                  .append(" x i8] c\"").append(escaped).append("\\00\"\n");
            stringLiterals.put(key, name);
        }
        String globalName = stringLiterals.get(key);
        String ptr = "%str." + tmpCount++;
        body.append("  ").append(ptr).append(" = getelementptr [")
            .append(utf8Len(text) + 1).append(" x i8], [")
            .append(utf8Len(text) + 1).append(" x i8]* ")
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
                throw new RuntimeException("this:: requires an instance method (at line " + line + ")");
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
        if (arg instanceof StringLit) return "i8*";
        if (arg instanceof NullLit) return "null";
        return staticExprType(arg);
    }

    /**
     * Best-effort static LLVM type of an expression, used only to disambiguate overloads.
     * Never emits IR. Returns null when the type cannot be determined statically.
     */
    private String staticExprType(AST expr) {
        if (expr == null) return null;
        if (expr instanceof Identifier && scope != null) {
            LLVMValue v = scope.lookup(((Identifier) expr).name);
            if (v != null) return v.type;
            return null;
        }
        if (expr instanceof ThisExpr && scope != null) {
            LLVMValue v = scope.lookup("this");
            if (v != null) return v.type;
            return null;
        }
        if (expr instanceof FieldAccessExpr) {
            FieldAccessExpr fa = (FieldAccessExpr) expr;
            String owner = staticExprType(fa.object);
            ClassInfo ci = classInfoOfLLVMType(owner);
            if (ci == null) return null;
            Integer idx = ci.fieldIndices.get(fa.field);
            if (idx == null || idx - 1 >= ci.fieldTypes.size()) return null;
            return toLLVMType(ci.fieldTypes.get(idx - 1));
        }
        if (expr instanceof MethodCallExpr) {
            MethodCallExpr mc = (MethodCallExpr) expr;
            String owner = staticExprType(mc.object);
            ClassInfo ci = classInfoOfLLVMType(owner);
            if (ci == null) return null;
            List<FuncInfo> cands = lookupInstanceMethods(ci, mc.method);
            if (cands == null) return null;
            String ret = null;
            for (FuncInfo f : cands) {
                if (f.paramTypes.size() != mc.args.size()) continue;
                String t = toLLVMType(f.returnType);
                if (ret == null) ret = t;
                else if (!ret.equals(t)) return null; // overloads disagree — stay conservative
            }
            return ret;
        }
        return null;
    }

    /** Resolve a class from an LLVM value type such as "%Foo*"; null when primitive/unknown. */
    private ClassInfo classInfoOfLLVMType(String llvmType) {
        if (llvmType == null || !llvmType.startsWith("%") || !llvmType.endsWith("*")) return null;
        return classes.get(extractClassName(llvmType));
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

    /** True when the class chain defines `method` itself (beyond Object's native marker). */
    private boolean hasUserDefinedMethod(ClassInfo ci, String method) {
        List<FuncInfo> grp = lookupInstanceMethods(ci, method);
        if (grp != null) {
            for (FuncInfo f : grp) {
                if (!"cang_lang_Object".equals(f.className)) return true;
            }
        }
        return false;
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
            // Java-style bare field access inside instance methods / init / main
            LLVMValue faddr = instanceFieldAddr(node.name);
            if (faddr != null) {
                ClassInfo fci = classes.get(currentClassName);
                String cangT = fci.fieldTypes.get(fci.fieldIndices.get(node.name) - 1);
                String ldy = "%fldld." + tmpCount++;
                String base = faddr.type.substring(0, faddr.type.length() - 1);
                body.append("  ").append(ldy).append(" = load ").append(base)
                     .append(", ").append(faddr.type).append(" ").append(faddr.value).append("\n");
                return new LLVMValue(ldy, base, semanticKindOf(cangT));
            }
            throw new RuntimeException("Undefined variable: " + node.name + " at line " + node.line);
        }
        // Load the value
        String loaded = "%ld." + tmpCount++;
        body.append("  ").append(loaded).append(" = load ").append(val.type)
             .append(", ").append(val.type).append("* ").append(val.value).append("\n");
        return new LLVMValue(loaded, val.type, val.semanticType);
    }

    /** Address of an instance field of the class being compiled (Java-style bare field access),
     *  or null when there is no `this` in scope / the field does not exist. */
    private LLVMValue instanceFieldAddr(String name) {
        if (currentClassName == null) return null;
        ClassInfo ci = classes.get(currentClassName);
        if (ci == null) return null;
        Integer idx = ci.fieldIndices.get(name);
        if (idx == null) return null;
        LLVMValue thiz = scope.lookup("this");
        if (thiz == null) return null;
        String ptr = "%fld." + tmpCount++;
        body.append("  ").append(ptr).append(" = getelementptr ").append(ci.llvmName)
             .append(", ").append(ci.llvmName).append("* ").append(thiz.value)
             .append(", i32 0, i32 ").append(idx).append("\n");
        return new LLVMValue(ptr, toLLVMType(ci.fieldTypes.get(idx - 1)) + "*");
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

        // String concatenation with + — strings, numbers, and objects (objects via toString(),
        // user overrides win; null object → "null" like Java).
        if (op.equals("+") && (left.type.equals("i8*") || right.type.equals("i8*")
                || (left.type.startsWith("%") && left.type.endsWith("*"))
                || (right.type.startsWith("%") && right.type.endsWith("*")))) {
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
        // Objects: toString() semantics (user overrides win; default ClassName@addr;
        // null → "null"). Arrays/Thread handles stringify to their type name.
        if (val.type.startsWith("%") && val.type.endsWith("*")) {
            return objectToStringForConcat(val);
        }
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

    /** String-concat stringification of an object pointer: overrides win, null → "null". */
    private LLVMValue objectToStringForConcat(LLVMValue val) {
        String sem = val.semanticType;
        if (sem != null && sem.equals("String")) return val;
        if (sem != null && sem.startsWith("Array<")) return makeStringConstant("Array");
        if (sem != null && isThreadType(sem)) return makeStringConstant("Thread");
        ClassInfo ci = classes.get(extractClassName(val.type));
        int id = labelCount++;
        String nullL = "catobj.null." + id;
        String elseL = "catobj.el." + id;
        String joinL = "catobj.j." + id;
        // Slot instead of phi: nested dispatch arms all store here and branch to join.
        String slot = "%catobj.slot." + tmpCount++;
        body.append("  ").append(slot).append(" = alloca i8*\n");
        String nullS = "%catobj.np." + tmpCount++;
        body.append("  ").append(nullS).append(" = getelementptr [5 x i8], [5 x i8]* @.str.null.p, i32 0, i32 0\n");
        String isN = "%catobj.n." + tmpCount++;
        body.append("  ").append(isN).append(" = icmp eq ").append(val.type).append(" ")
             .append(val.value).append(", null\n");
        body.append("  br i1 ").append(isN).append(", label %").append(nullL)
             .append(", label %").append(elseL).append("\n");
        body.append(nullL).append(":\n");
        body.append("  store i8* ").append(nullS).append(", i8** ").append(slot).append("\n");
        body.append("  br label %").append(joinL).append("\n");
        body.append(elseL).append(":\n");
        if (ci == null) {
            String fallback = makeStringConstant(extractClassName(val.type)).value;
            body.append("  store i8* ").append(fallback).append(", i8** ").append(slot).append("\n");
            body.append("  br label %").append(joinL).append("\n");
        } else {
            emitToStringStore(val, ci, slot, joinL);
        }
        body.append(joinL).append(":\n");
        String r = "%catobj.r." + tmpCount++;
        body.append("  ").append(r).append(" = load i8*, i8** ").append(slot).append("\n");
        return new LLVMValue(r, "i8*");
    }

    /**
     * toString() of a class object WITH override support: concrete classes (the static type
     * and its subclasses) that declare their own no-arg String toString() get a dispatch arm;
     * everything else falls to the default ClassName@addr — Object's native marker has no
     * body to call, so it can't be an arm target (unlike generateDynamicDispatch). Every
     * arm stores its result into slotPtr and branches to destLabel (no phis).
     */
    private void emitToStringStore(LLVMValue val, ClassInfo staticCi, String slotPtr, String destLabel) {
        java.util.List<ClassInfo> types = new ArrayList<>();
        if (!staticCi.isAbstract) types.add(staticCi);
        for (ClassInfo ci : classes.values()) {
            if (ci != staticCi && !ci.isAbstract && isSubclass(ci.simpleName, staticCi.simpleName)) {
                types.add(ci);
            }
        }
        java.util.List<ClassInfo> overriders = new ArrayList<>();
        for (ClassInfo c : types) {
            if (ownToString(c) != null) overriders.add(c);
        }
        if (overriders.isEmpty()) {
            String d = emitDefaultToString(val).value;
            body.append("  store i8* ").append(d).append(", i8** ").append(slotPtr).append("\n");
            body.append("  br label %").append(destLabel).append("\n");
            return;
        }

        String base = extractClassName(val.type);
        String recv = classPtrBaseAndValue(val)[1];
        String tidPtr = "%ots.tidp." + tmpCount++;
        body.append("  ").append(tidPtr).append(" = getelementptr %").append(base).append(", %").append(base)
             .append("* ").append(recv).append(", i32 0, i32 0\n");
        String tid = "%ots.tid." + tmpCount++;
        body.append("  ").append(tid).append(" = load i32, i32* ").append(tidPtr).append("\n");

        int id = labelCount++;
        String firstCheck = "ots.chk." + id + ".0";
        String defL = "ots.def." + id;
        body.append("  br label %").append(firstCheck).append("\n");
        for (int i = 0; i < overriders.size(); i++) {
            ClassInfo oc = overriders.get(i);
            String chk = (i == 0) ? firstCheck : "ots.chk." + id + "." + i;
            String call = "ots.call." + id + "." + i;
            String next = (i + 1 < overriders.size()) ? "ots.chk." + id + "." + (i + 1) : defL;
            body.append(chk).append(":\n");
            String c = "%ots.c." + tmpCount++;
            body.append("  ").append(c).append(" = icmp eq i32 ").append(tid).append(", ").append(oc.typeId).append("\n");
            body.append("  br i1 ").append(c).append(", label %").append(call).append(", label %").append(next).append("\n");
            body.append(call).append(":\n");
            String p = "%ots.p." + tmpCount++;
            body.append("  ").append(p).append(" = bitcast %").append(base).append("* ").append(recv)
                 .append(" to %").append(oc.fullName).append("*\n");
            String arm = "%ots.r." + tmpCount++;
            body.append("  ").append(arm).append(" = call i8* @").append(symOf(ownToString(oc)))
                 .append("(%").append(oc.fullName).append("* ").append(p).append(")\n");
            body.append("  store i8* ").append(arm).append(", i8** ").append(slotPtr).append("\n");
            body.append("  br label %").append(destLabel).append("\n");
        }
        body.append(defL).append(":\n");
        String defVal = emitDefaultToString(val).value;
        body.append("  store i8* ").append(defVal).append(", i8** ").append(slotPtr).append("\n");
        body.append("  br label %").append(destLabel).append("\n");
    }

    /** Default toString: ClassName@addr via the type-id table (same as the intrinsic path). */
    private LLVMValue emitDefaultToString(LLVMValue val) {
        LLVMValue name = emitClassNameOf(val);
        emitObjectToStringHelper();
        String[] bv = classPtrBaseAndValue(val);
        String obj = "%otsd.o." + tmpCount++;
        body.append("  ").append(obj).append(" = bitcast %").append(bv[0]).append("* ").append(bv[1])
             .append(" to i8*\n");
        String s = "%otsd.s." + tmpCount++;
        body.append("  ").append(s).append(" = call i8* @cang.f.objtostr(i8* ")
             .append(name.value).append(", i8* ").append(obj).append(")\n");
        return new LLVMValue(s, "i8*", "String");
    }

    /** This class's OWN no-arg String toString() FuncInfo (not inherited from Object), or null. */
    private FuncInfo ownToString(ClassInfo ci) {
        List<FuncInfo> grp = overloadGroups.get(ci.fullName + ".toString");
        if (grp == null) return null;
        for (FuncInfo f : grp) {
            if (f.paramTypes.isEmpty() && "String".equals(f.returnType)) return f;
        }
        return null;
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
            throw new RuntimeException("Only == and != are supported for String (at line " + line + ")");
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
        if (ptr == null) ptr = instanceFieldAddr(id.name);
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
                // Java-style bare field assignment (e.g. inside init)
                LLVMValue faddrA = instanceFieldAddr(id.name);
                if (faddrA != null) {
                    ClassInfo fciA = classes.get(currentClassName);
                    String llvmTA = toLLVMType(fciA.fieldTypes.get(fciA.fieldIndices.get(id.name) - 1));
                    if (!assignableTo(llvmTA, val)) {
                        throw new RuntimeException("Cannot assign " + cangTypeFromLLVMFull(val.type)
                            + " to " + cangTypeFromLLVMFull(llvmTA) + " (at line " + node.line + ")");
                    }
                    String castedF = castValue(val, llvmTA);
                    body.append("  store ").append(llvmTA).append(" ").append(castedF)
                         .append(", ").append(llvmTA).append("* ").append(faddrA.value).append("\n");
                    return new LLVMValue(castedF, llvmTA);
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
        // The post-call unwind check may split this arm into an eh.cont block; the phi
        // pred must be the block that actually branches to the join, so scan for the
        // currently open block instead of trusting the arm label.
        String tPred = currentBlockName();
        body.append("  br label %").append(joinLabel).append("\n\n");

        body.append(fLabel).append(":\n");
        LLVMValue falseVal = generateExpr(node.falseExpr);
        // Casts must be emitted inside their own block (phi has to be first in the join block).
        String fOp = castValue(falseVal, trueVal.type);
        String fPred = currentBlockName();
        body.append("  br label %").append(joinLabel).append("\n\n");

        body.append(joinLabel).append(":\n");
        String result = "%ternary." + tmpCount++;
        body.append("  ").append(result).append(" = phi ").append(trueVal.type)
             .append(" [ ").append(tOp).append(", %").append(tPred)
             .append(" ], [ ").append(fOp).append(", %").append(fPred).append(" ]\n");
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

    /**
     * Class downcast: {@code Apple!$(fruitRef)} 强制 / {@code Apple$(fruitRef)} 安全.
     * Runtime type check (target or a concrete subclass — same id set as `like`), then
     * bitcast. Null casts to null (Java semantics). force=true raises a catchable runtime
     * error on mismatch; force=false (safe cast) yields null instead.
     */
    private LLVMValue generateClassCast(LLVMValue val, String className, int line, boolean force) {
        ClassInfo targetInfo = classes.get(className);
        if (targetInfo == null) throw unknownClassError(className, line);
        // Literal null casts to null (Java: (Apple) null == null).
        if (val.value.equals("null") && val.type.equals("i8*")) {
            return new LLVMValue("null", targetInfo.llvmName + "*", className);
        }
        if (!val.type.startsWith("%") || !val.type.endsWith("*")) {
            throw new RuntimeException("Cannot cast a non-object value to '" + className
                + "' (at line " + line + ")");
        }
        java.util.List<Integer> validIds = new ArrayList<>();
        for (ClassInfo ci : classes.values()) {
            if (isSubclass(ci.simpleName, targetInfo.simpleName) && !ci.isAbstract) {
                validIds.add(ci.typeId);
            }
        }
        if (validIds.isEmpty()) validIds.add(targetInfo.typeId);

        String[] bv = classPtrBaseAndValue(val);
        String base = bv[0];
        String recv = bv[1];
        int id = labelCount++;
        String nullL = "cast.null." + id;
        String checkL = "cast.check." + id;
        String hitL = "cast.hit." + id;
        String missL = "cast.miss." + id;
        String resumeL = "cast.resume." + id;
        String endL = "cast.end." + id;
        String isn = "%cast.isn." + tmpCount++;
        body.append("  ").append(isn).append(" = icmp eq %").append(base).append("* ").append(recv).append(", null\n");
        body.append("  br i1 ").append(isn).append(", label %").append(nullL).append(", label %").append(checkL).append("\n");
        body.append(nullL).append(":\n");
        body.append("  br label %").append(endL).append("\n");
        body.append(checkL).append(":\n");
        // Type-id check only on the non-null path (a null receiver must not be dereferenced).
        String tidPtr = "%cast.tidp." + tmpCount++;
        body.append("  ").append(tidPtr).append(" = getelementptr %").append(base).append(", %").append(base)
             .append("* ").append(recv).append(", i32 0, i32 0\n");
        String tid = "%cast.tid." + tmpCount++;
        body.append("  ").append(tid).append(" = load i32, i32* ").append(tidPtr).append("\n");
        String match = "%cast.m." + tmpCount++;
        body.append("  ").append(match).append(" = icmp eq i32 ").append(tid)
             .append(", ").append(validIds.get(0)).append("\n");
        for (int i = 1; i < validIds.size(); i++) {
            String cmp = "%cast.c." + tmpCount++;
            body.append("  ").append(cmp).append(" = icmp eq i32 ").append(tid)
                 .append(", ").append(validIds.get(i)).append("\n");
            String next = "%cast.n." + tmpCount++;
            body.append("  ").append(next).append(" = or i1 ").append(match).append(", ").append(cmp).append("\n");
            match = next;
        }
        body.append("  br i1 ").append(match).append(", label %").append(hitL).append(", label %").append(missL).append("\n");
        body.append(missL).append(":\n");
        // force: catchable runtime error; safe: fall through to null.
        if (force) {
            emitRuntimeError("Cannot cast to " + className + ": incompatible object type", line, resumeL);
            body.append(resumeL).append(":\n");
        }
        body.append("  br label %").append(endL).append("\n");
        body.append(hitL).append(":\n");
        String c = "%cast.v." + tmpCount++;
        body.append("  ").append(c).append(" = bitcast %").append(base).append("* ").append(recv)
             .append(" to ").append(targetInfo.llvmName).append("*\n");
        body.append("  br label %").append(endL).append("\n");
        body.append(endL).append(":\n");
        String r = "%cast.r." + tmpCount++;
        body.append("  ").append(r).append(" = phi ").append(targetInfo.llvmName).append("* [ null, %")
             .append(nullL).append(" ], [ null, %").append(force ? resumeL : missL).append(" ], [ ").append(c)
             .append(", %").append(hitL).append(" ]\n");
        return new LLVMValue(r, targetInfo.llvmName + "*", className);
    }

    private LLVMValue generateTypeCast(TypeCastExpr node) {
        boolean force = node.force;
        LLVMValue val = generateExpr(node.expr);

        // String target: any value → its string form; toString always succeeds.
        if (node.targetType.equals("String")) {
            if (val.type.equals("i8*")) return new LLVMValue(val.value, val.type, "String");
            LLVMValue converted = convertToString(val);
            converted.semanticType = "String";
            return converted;
        }

        // Class downcast (target is a class name; unknown names → unknownClassError).
        if (!isPrimitiveCastTarget(node.targetType)) {
            return generateClassCast(val, node.targetType, node.line, force);
        }

        // null literal → primitive: not a value conversion (force and safe both reject).
        if (val.value.equals("null")) {
            throw new RuntimeException("Cannot cast null to '" + node.targetType + "' (at line " + node.line + ")");
        }
        // Object value → primitive: a class reference is never a number.
        if (val.type.startsWith("%") && val.type.endsWith("*")) {
            throw new RuntimeException("Cannot cast a class value to '" + node.targetType
                + "' (at line " + node.line + ")");
        }

        String targetType = toLLVMType(node.targetType);
        // Same LLVM type: nothing to convert, nothing can fail.
        if (val.type.equals(targetType)) return val;

        // String → number: strict parse (full-consume) + the usual range check.
        if (val.type.equals("i8*") && !targetType.equals("i8*")) {
            return generateStringToNumber(val, node.targetType, targetType, node.line, force);
        }

        return convertNumeric(val, node.targetType, targetType, node.line, force);
    }

    /** Castable primitive targets (class names take the runtime downcast path instead). */
    private boolean isPrimitiveCastTarget(String t) {
        return t.equals("byte") || t.equals("int") || t.equals("long")
            || t.equals("float") || t.equals("double");
    }

    /**
     * Numeric conversion. Widening never fails; narrowing is overflow-checked —
     * force raises a catchable runtime error, safe yields the target default (0).
     */
    private LLVMValue convertNumeric(LLVMValue val, String cangTarget, String targetType, int line, boolean force) {
        // Same LLVM type: nothing to convert, nothing can fail.
        if (val.type.equals(targetType)) return val;

        // Integer → wider integer: sign-extend (bool zero-extends so true stays 1).
        int fromBits = llvmTypeBits(val.type);
        int toBits = llvmTypeBits(targetType);
        if (!isFloatType(targetType) && !isFloatType(val.type) && fromBits > 0 && toBits > fromBits) {
            String result = "%cast." + tmpCount++;
            String widen = val.type.equals("i1") ? "zext" : "sext";
            body.append("  ").append(result).append(" = ").append(widen).append(" ").append(val.type)
                 .append(" ").append(val.value).append(" to ").append(targetType).append("\n");
            return new LLVMValue(result, targetType);
        }

        // Integer → narrower integer: trunc round-trip must reproduce the input.
        if (!isFloatType(targetType) && !isFloatType(val.type) && fromBits > toBits && toBits > 0) {
            String truncated = "%cast.trunc." + tmpCount++;
            body.append("  ").append(truncated).append(" = trunc ").append(val.type)
                 .append(" ").append(val.value).append(" to ").append(targetType).append("\n");
            String back = "%cast.truncback." + tmpCount++;
            body.append("  ").append(back).append(" = sext ").append(targetType).append(" ")
                 .append(truncated).append(" to ").append(val.type).append("\n");
            String ok = "%cast.truncok." + tmpCount++;
            body.append("  ").append(ok).append(" = icmp eq ").append(val.type)
                 .append(" ").append(back).append(", ").append(val.value).append("\n");
            String r = emitChecked(ok, "Cannot cast to '" + cangTarget + "': value out of range",
                line, force, "0", targetType, () -> truncated);
            return new LLVMValue(r, targetType);
        }

        // Integer → float: always representable (precision may drop, never overflows).
        if (isFloatType(targetType) && !isFloatType(val.type)) {
            String result = "%cast." + tmpCount++;
            body.append("  ").append(result).append(" = sitofp ").append(val.type)
                 .append(" ").append(val.value).append(" to ").append(targetType).append("\n");
            return new LLVMValue(result, targetType);
        }

        // Float → wider/equal float: exact widening.
        if (isFloatType(targetType) && val.type.equals("float") && targetType.equals("double")) {
            String result = "%cast." + tmpCount++;
            body.append("  ").append(result).append(" = fpext float ").append(val.value).append(" to double\n");
            return new LLVMValue(result, targetType);
        }

        // double → float: overflow check (|v| > FLT_MAX fails; NaN passes through as NaN).
        if (isFloatType(targetType) && val.type.equals("double") && targetType.equals("float")) {
            String ge = "%cast.fge." + tmpCount++;
            body.append("  ").append(ge).append(" = fcmp oge double ").append(val.value)
                 .append(", -3.4028234663852886E+38\n");
            String le = "%cast.fle." + tmpCount++;
            body.append("  ").append(le).append(" = fcmp ole double ").append(val.value)
                 .append(", 3.4028234663852886E+38\n");
            String inRange = "%cast.fin." + tmpCount++;
            body.append("  ").append(inRange).append(" = and i1 ").append(ge).append(", ").append(le).append("\n");
            String isNan = "%cast.fnan." + tmpCount++;
            body.append("  ").append(isNan).append(" = fcmp uno double ").append(val.value).append(", ").append(val.value).append("\n");
            String ok = "%cast.fok." + tmpCount++;
            body.append("  ").append(ok).append(" = or i1 ").append(inRange).append(", ").append(isNan).append("\n");
            String r = emitChecked(ok, "Cannot cast to 'float': value out of range", line, force, "0.0", "float", () -> {
                String c = "%cast." + tmpCount++;
                body.append("  ").append(c).append(" = fptrunc double ").append(val.value).append(" to float\n");
                return c;
            });
            return new LLVMValue(r, targetType);
        }

        // Float → integer: range (+NaN) checked — fptosi is poison out of range.
        if (!isFloatType(targetType) && isFloatType(val.type)) {
            // fptosi needs a double; fpext float first (always exact).
            final String srcValue;
            if (val.type.equals("float")) {
                String ext = "%cast.fext." + tmpCount++;
                body.append("  ").append(ext).append(" = fpext float ").append(val.value).append(" to double\n");
                srcValue = ext;
            } else {
                srcValue = val.value;
            }
            String min, upper;
            if (targetType.equals("i8")) { min = "-1.28E+02"; upper = "1.28E+02"; }
            else if (targetType.equals("i32")) { min = "-2.147483648E+09"; upper = "2.147483648E+09"; }
            else { min = "-9.223372036854775808E+18"; upper = "9.223372036854775808E+18"; }
            // NaN fails both ordered compares, so this test also rejects NaN.
            String ge = "%cast.ge." + tmpCount++;
            body.append("  ").append(ge).append(" = fcmp oge double ").append(srcValue).append(", ").append(min).append("\n");
            String lt = "%cast.lt." + tmpCount++;
            body.append("  ").append(lt).append(" = fcmp olt double ").append(srcValue).append(", ").append(upper).append("\n");
            String ok = "%cast.in." + tmpCount++;
            body.append("  ").append(ok).append(" = and i1 ").append(ge).append(", ").append(lt).append("\n");
            String r = emitChecked(ok, "Cannot cast to '" + cangTarget + "': value out of range",
                line, force, "0", targetType, () -> {
                    String c = "%cast." + tmpCount++;
                    body.append("  ").append(c).append(" = fptosi double ").append(srcValue)
                         .append(" to ").append(targetType).append("\n");
                    return c;
                });
            return new LLVMValue(r, targetType);
        }

        // Fallback: relabel (unreachable for the supported cast matrix).
        String result = "%cast." + tmpCount++;
        return new LLVMValue(result, targetType);
    }

    /**
     * Branch on {@code ok}: the true path emits {@code convert} and joins; the false path
     * raises a catchable runtime error (force) or yields {@code defaultValue} (safe).
     * Returns the phi register holding the cast result.
     */
    private String emitChecked(String okCond, String message, int line, boolean force,
                               String defaultValue, String phiType,
                               java.util.function.Supplier<String> convert) {
        int id = labelCount++;
        String okL = "cast.ok." + id, failL = "cast.bad." + id, joinL = "cast.join." + id;
        body.append("  br i1 ").append(okCond).append(", label %").append(okL)
             .append(", label %").append(failL).append("\n");
        body.append(okL).append(":\n");
        String converted = convert.get();
        body.append("  br label %").append(joinL).append("\n");
        body.append(failL).append(":\n");
        String failPred = failL;
        if (force) {
            String resumeL = "cast.rcv." + id;
            emitRuntimeError(message, line, resumeL);
            body.append(resumeL).append(":\n");
            failPred = resumeL;
        }
        body.append("  br label %").append(joinL).append("\n");
        body.append(joinL).append(":\n");
        String r = "%cast.chk." + tmpCount++;
        body.append("  ").append(r).append(" = phi ").append(phiType)
             .append(" [ ").append(converted).append(", %").append(okL).append(" ], [ ")
             .append(defaultValue).append(", %").append(failPred).append(" ]\n");
        return r;
    }

    /**
     * String → number: strict full-consume parse (strtoll/strtod + end-pointer guard, no
     * surrounding whitespace tolerance — a cast is not parseInt). Parse failure or a
     * value outside the target range is a cast failure: force → catchable runtime error,
     * safe → 0. Values are parsed as i64/double first, then the usual range checks apply.
     */
    private LLVMValue generateStringToNumber(LLVMValue strVal, String cangTargetType, String llvmTargetType,
                                             int line, boolean force) {
        boolean dbl = cangTargetType.equals("float") || cangTargetType.equals("double");
        int id = labelCount++;
        String okL = "s2n.ok." + id, failL = "s2n.fail." + id, joinL = "s2n.join." + id;
        String resumeL = "s2n.rcv." + id;
        String parsedType = dbl ? "double" : "i64";

        // Null string: no value to parse — same failure path as an unparsable one.
        String isNull = "%s2n.isnull." + tmpCount++;
        body.append("  ").append(isNull).append(" = icmp eq i8* ").append(strVal.value).append(", null\n");

        String epSlot = "%s2n.ep." + tmpCount++;
        body.append("  ").append(epSlot).append(" = alloca i8*\n");
        String parsed = "%s2n.v." + tmpCount++;
        // strtoll/strtod on a null pointer is UB — branch first.
        String parseL = "s2n.parse." + id;
        body.append("  br i1 ").append(isNull).append(", label %").append(failL)
             .append(", label %").append(parseL).append("\n");
        body.append(parseL).append(":\n");
        if (dbl) {
            body.append("  ").append(parsed).append(" = call double @strtod(i8* ").append(strVal.value)
                 .append(", i8** ").append(epSlot).append(")\n");
        } else {
            body.append("  ").append(parsed).append(" = call i64 @strtoll(i8* ").append(strVal.value)
                 .append(", i8** ").append(epSlot).append(", i32 10)\n");
        }
        String ep = "%s2n.epv." + tmpCount++;
        body.append("  ").append(ep).append(" = load i8*, i8** ").append(epSlot).append("\n");
        String c = "%s2n.c." + tmpCount++;
        body.append("  ").append(c).append(" = load i8, i8* ").append(ep).append("\n");
        // Full-consume: at least one character consumed AND the end pointer is at '\0'.
        String consumed = "%s2n.cons." + tmpCount++;
        body.append("  ").append(consumed).append(" = icmp ne i8* ").append(ep).append(", ").append(strVal.value).append("\n");
        String term = "%s2n.term." + tmpCount++;
        body.append("  ").append(term).append(" = icmp eq i8 ").append(c).append(", 0\n");
        String ok = "%s2n.pok." + tmpCount++;
        body.append("  ").append(ok).append(" = and i1 ").append(consumed).append(", ").append(term).append("\n");
        body.append("  br i1 ").append(ok).append(", label %").append(okL)
             .append(", label %").append(failL).append("\n");
        body.append(okL).append(":\n");
        body.append("  br label %").append(joinL).append("\n");
        body.append(failL).append(":\n");
        String failPred = failL;
        if (force) {
            emitRuntimeError("Cannot convert String to '" + cangTargetType + "'", line, resumeL);
            body.append(resumeL).append(":\n");
            failPred = resumeL;
        }
        body.append("  br label %").append(joinL).append("\n");
        body.append(joinL).append(":\n");
        // On the null/unparsable path `parsed` is undefined — the phi takes the zero default.
        String zero = dbl ? "0.0" : "0";
        String v = "%s2n.parsed." + tmpCount++;
        body.append("  ").append(v).append(" = phi ").append(parsedType)
             .append(" [ ").append(parsed).append(", %").append(okL).append(" ], [ ")
             .append(zero).append(", %").append(failPred).append(" ]\n");

        // Range-check the parsed value against the real target (int/byte narrowing, f64→f32).
        return convertNumeric(new LLVMValue(v, parsedType), cangTargetType, llvmTargetType, line, force);
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
            throw new RuntimeException("Thread.spawn has been removed (at line "
                + node.line + ")");
        }
        // Coroutine (libuv single-thread event loop): one object carries task/setDaemon/start/
        // join/getId/isAlive — dispatched before the Thread gates (method names overlap).
        if (objCache == null && node.object != null
                && (node.method.equals("task") || node.method.equals("setDaemon")
                        || node.method.equals("start") || node.method.equals("join")
                        || node.method.equals("getId") || node.method.equals("isAlive"))) {
            objCache = generateExpr(node.object);
            if (objCache.type.equals("%CangCo*")) {
                if (node.method.equals("task")) return generateCoroutineTask(objCache, node);
                if (node.method.equals("setDaemon")) return generateCoroutineSetDaemon(objCache, node);
                if (node.method.equals("start")) return generateCoroutineStart(objCache, node);
                if (node.method.equals("join")) return generateCoroutineJoin(objCache, node);
                if (node.method.equals("getId")) return generateCoroutineGetId(objCache, node);
                return generateCoroutineIsAlive(objCache, node);
            }
            // non-Coroutine receiver with a clashing name — reuse the generated value below
        }
        // Object-style Thread: task/setDaemon/start (plus run on a subclass receiver) — exact
        // type gate so unrelated classes' same-named methods use the normal instance path.
        if (node.object != null && (node.method.equals("task") || node.method.equals("setDaemon")
                || node.method.equals("start") || node.method.equals("run"))) {
            if (objCache == null) objCache = generateExpr(node.object);
            if (objCache.type.equals("%CangThreadObj*")) {
                if (node.method.equals("task")) return generateThreadObjTask(objCache, node);
                if (node.method.equals("setDaemon")) return generateThreadObjSetDaemon(objCache, node);
                if (node.method.equals("start")) return generateThreadObjStart(objCache, node);
                throw new RuntimeException("Thread object has no run(); use task(fn), or subclass "
                    + "Thread and override run() (at line " + node.line + ")");
            }
            // Pre-start-only APIs on a started handle (thread{}/start()/currentThread()/getParent()
            // returns) — statically decidable, so a clear compile error beats both the leaked
            // "Unknown method: CangThreadHandle..." and any runtime check.
            if (objCache.semanticType != null && isThreadType(objCache.semanticType)) {
                if (node.method.equals("start")) {
                    throw new RuntimeException("Thread.start cannot be called on an already-started "
                        + "thread handle (at line " + node.line + ")");
                }
                if (node.method.equals("run")) {
                    throw new RuntimeException("run() executes automatically on start(); it cannot be "
                        + "called on a started handle (at line " + node.line + ")");
                }
                throw new RuntimeException("Thread." + node.method + " must be called before start; "
                    + "thread{} / start() already return a started handle (at line " + node.line + ")");
            }
            // Thread subclass instance (class My : Thread): start() runs the overridden run()
            // on a new thread via an internal adapter; task/setDaemon have no storage here.
            ClassInfo subCi = threadSubclassInfo(objCache);
            if (subCi != null) {
                if (node.method.equals("start")) return generateSubclassStart(objCache, subCi, node);
                if (node.method.equals("run")) {
                    if (!chainHasUserRun(subCi)) {
                        throw new RuntimeException("Thread subclass '" + subCi.simpleName
                            + "' must override run() (at line " + node.line + ")");
                    }
                    // user-defined run — direct t.run() on the current thread (normal path)
                } else {
                    throw new RuntimeException("Thread subclass '" + subCi.simpleName + "': override "
                        + "run() and call start(); " + node.method + "() is only for new Thread() "
                        + "objects (at line " + node.line + ")");
                }
            }
        }
        // Receiver is generated at most once (debug.md #15): the join probe, the array-length
        // probe and the regular instance path share the same value.
        if (node.object != null && node.method.equals("join")) {
            if (objCache == null) objCache = generateExpr(node.object);
            LLVMValue receiver = objCache;
            if (receiver.semanticType != null && isThreadType(receiver.semanticType)) {
                if (!node.args.isEmpty()) throw new RuntimeException("Thread.join accepts no arguments (at line " + node.line + ")");
                if (receiver.type.equals("%CangThreadObj*")) {
                    receiver = threadObjHandle(receiver, node.line);
                }
                return generateThreadJoin(receiver, node.line);
            }
            if (threadSubclassInfo(receiver) != null) {
                throw new RuntimeException("Thread subclass: join the handle from start() — "
                    + "var h = t.start(); h.join() (at line " + node.line + ")");
            }
        }
        // getId / getParent / isAlive: logical id on the object (assigned at new Thread()) or
        // on any Thread handle; parent = creator's self handle (null for main); isAlive probes
        // the OS thread (unstarted object is never alive).
        if (node.object != null
                && (node.method.equals("getId") || node.method.equals("getParent")
                        || node.method.equals("isAlive"))) {
            if (objCache == null) objCache = generateExpr(node.object);
            LLVMValue receiver = objCache;
            if (!node.args.isEmpty()) {
                throw new RuntimeException("Thread." + node.method + " accepts no arguments (at line " + node.line + ")");
            }
            boolean wantParent = node.method.equals("getParent");
            boolean wantAlive = node.method.equals("isAlive");
            if (wantAlive) {
                emitThreadIsAliveHelper();
                if (receiver.type.equals("%CangThreadObj*")) {
                    String hp = "%thr.alive.hp." + tmpCount++;
                    body.append("  ").append(hp).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
                         .append(", i32 0, i32 2\n");
                    String h = "%thr.alive.h." + tmpCount++;
                    body.append("  ").append(h).append(" = load i8*, i8** ").append(hp).append("\n");
                    String hn = "%thr.alive.hn." + tmpCount++;
                    body.append("  ").append(hn).append(" = icmp eq i8* ").append(h).append(", null\n");
                    int lbl = labelCount++;
                    String notStarted = "thr.alive.ns." + lbl;
                    String probe = "thr.alive.pr." + lbl;
                    String res = "thr.alive.res." + lbl;
                    body.append("  br i1 ").append(hn).append(", label %").append(notStarted).append(", label %").append(probe).append("\n");
                    body.append(notStarted).append(":\n");
                    body.append("  br label %").append(res).append("\n");
                    body.append(probe).append(":\n");
                    String a = "%thr.alive.a." + tmpCount++;
                    body.append("  ").append(a).append(" = call i1 @cang.thread.isalive(i8* ").append(h).append(")\n");
                    body.append("  br label %").append(res).append("\n");
                    body.append(res).append(":\n");
                    String r = "%thr.alive.r." + tmpCount++;
                    body.append("  ").append(r).append(" = phi i1 [ false, %").append(notStarted).append(" ], [ ").append(a).append(", %").append(probe).append(" ]\n");
                    return new LLVMValue(r, "i1");
                }
                if (receiver.type.equals("%CangThreadHandle*")
                        && receiver.semanticType != null && isThreadType(receiver.semanticType)) {
                    String cast = "%thr.alive.c." + tmpCount++;
                    body.append("  ").append(cast).append(" = bitcast %CangThreadHandle* ").append(receiver.value).append(" to i8*\n");
                    String r = "%thr.alive.r." + tmpCount++;
                    body.append("  ").append(r).append(" = call i1 @cang.thread.isalive(i8* ").append(cast).append(")\n");
                    return new LLVMValue(r, "i1");
                }
                if (threadSubclassInfo(receiver) != null) {
                    throw new RuntimeException("Thread subclass: isAlive() is available on the handle "
                        + "from start() — var h = t.start(); h.isAlive() (at line " + node.line + ")");
                }
                // not a Thread receiver — fall through
            } else {
                String gep = null;
                if (receiver.type.equals("%CangThreadObj*")) {
                    gep = "%thr.getid.objp." + tmpCount++;
                    body.append("  ").append(gep).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
                         .append(", i32 0, i32 ").append(wantParent ? 5 : 4).append("\n");
                } else if (receiver.type.equals("%CangThreadHandle*")
                        && receiver.semanticType != null && isThreadType(receiver.semanticType)) {
                    gep = "%thr.getid.hp." + tmpCount++;
                    body.append("  ").append(gep).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(receiver.value)
                         .append(", i32 0, i32 ").append(wantParent ? 4 : 3).append("\n");
                }
                if (gep != null) {
                    String val = "%thr.getid.v." + tmpCount++;
                    if (wantParent) {
                        body.append("  ").append(val).append(" = load i8*, i8** ").append(gep).append("\n");
                        String typed = "%thr.getid.t." + tmpCount++;
                        body.append("  ").append(typed).append(" = bitcast i8* ").append(val).append(" to %CangThreadHandle*\n");
                        return new LLVMValue(typed, "%CangThreadHandle*", "Thread<void>");
                    }
                    body.append("  ").append(val).append(" = load i32, i32* ").append(gep).append("\n");
                    return new LLVMValue(val, "i32");
                }
                if (threadSubclassInfo(receiver) != null) {
                    throw new RuntimeException("Thread subclass: " + node.method + "() is available on "
                        + "the handle from start() — var h = t.start(); h." + node.method
                        + "() (at line " + node.line + ")");
                }
                // not a Thread receiver — fall through to the normal instance path
            }
        }
        // Class.newInstance(): reflection construction via @cang.newinstance(typeId).
        // Detection: the receiver's class is the builtin Class class (cang_lang_Class).
        if (node.object != null && node.method.equals("newInstance")) {
            if (objCache == null) objCache = generateExpr(node.object);
            ClassInfo recvCi = classes.get(extractClassName(objCache.type));
            if (recvCi != null && "cang_lang_Class".equals(recvCi.fullName)) {
                return generateClassNewInstance(recvCi, objCache, node);
            }
            // not a Class receiver — fall through (objCache reused below)
        }
        // Field reflection: getObject/getInt/.../getType by field name on the bound target.
        // Detection: the receiver's class is the builtin Field class (cang_lang_Field).
        if (node.object != null && FIELD_ACCESSOR_METHODS.contains(node.method)) {
            if (objCache == null) objCache = generateExpr(node.object);
            ClassInfo fieldCi = classes.get(extractClassName(objCache.type));
            if (fieldCi != null && "cang_lang_Field".equals(fieldCi.fullName)) {
                return generateFieldAccessor(fieldCi, objCache, node);
            }
            // not a Field receiver — fall through (objCache reused below)
        }
        // Object.getClassName()/toString()/getAddress(): intrinsic default when nothing on the
        // receiver's class chain defines its own (Object.cang only carries native markers — an
        // inherited body can't work: call sites pass the derived %X* where the define would take
        // %Object*). getAddress() = the pointer itself as long (full 64-bit; int would truncate).
        if (objCache == null && node.object != null
                && (node.method.equals("toString") || node.method.equals("getClassName")
                        || node.method.equals("getAddress"))) {
            objCache = generateExpr(node.object);
            LLVMValue receiver = objCache;
            if (!node.args.isEmpty()) {
                throw new RuntimeException("Object." + node.method + " accepts no arguments (at line " + node.line + ")");
            }
            if (node.method.equals("getAddress")) {
                if (receiver.type.endsWith("*")) {
                    ClassInfo addrCi = receiver.type.startsWith("%")
                        ? classes.get(extractClassName(receiver.type)) : null;
                    if (addrCi == null || !hasUserDefinedMethod(addrCi, "getAddress")) {
                        String a = "%objaddr." + tmpCount++;
                        body.append("  ").append(a).append(" = ptrtoint ").append(receiver.type)
                             .append(" ").append(receiver.value).append(" to i64\n");
                        return new LLVMValue(a, "i64");
                    }
                    // user-defined getAddress — normal path (objCache reused)
                }
                // non-reference receiver — fall through to the normal path
            } else if (receiver.semanticType != null
                    && receiver.semanticType.equals("String")) {
                if (node.method.equals("toString")) return receiver;
                return makeStringConstant("String");
            } else if (receiver.semanticType != null && receiver.semanticType.startsWith("Array<")) {
                return makeStringConstant("Array");
            } else if (receiver.semanticType != null && receiver.semanticType.equals("Coroutine")) {
                if (node.method.equals("getClassName")) return makeStringConstant("Coroutine");
                emitObjectToStringHelper();
                LLVMValue coName = makeStringConstant("Coroutine");
                String coObj = "%co.ts.o." + tmpCount++;
                body.append("  ").append(coObj).append(" = bitcast ").append(receiver.type).append(" ")
                     .append(receiver.value).append(" to i8*\n");
                String s = "%co.ts.s." + tmpCount++;
                body.append("  ").append(s).append(" = call i8* @cang.f.objtostr(i8* ")
                     .append(coName.value).append(", i8* ").append(coObj).append(")\n");
                return new LLVMValue(s, "i8*", "String");
            } else if (receiver.semanticType != null && isThreadType(receiver.semanticType)) {
                return makeStringConstant("Thread");
            } else if (receiver.type.startsWith("%") && receiver.type.endsWith("*")) {
                ClassInfo ci = classes.get(extractClassName(receiver.type));
                if (ci != null && !hasUserDefinedMethod(ci, node.method)) {
                    if (node.method.equals("getClassName")) return emitClassNameOf(receiver);
                    // toString = ClassName@addr (lowercase hex, no 0x — Java-style).
                    return emitDefaultToString(receiver);
                }
                // user-defined toString/getClassName on the chain — normal path (objCache reused)
            }
            // unrecognized receiver — fall through
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
            if (objName.equals("Thread") && node.method.equals("sleep")) {
                if (node.args.size() != 1) {
                    throw new RuntimeException("Thread.sleep expects one argument (at line " + node.line + ")");
                }
                return generateThreadSleep(node.args.get(0), node.line);
            }
            if (objName.equals("Thread") && node.method.equals("currentThread")) {
                if (!node.args.isEmpty()) {
                    throw new RuntimeException("Thread.currentThread accepts no arguments (at line " + node.line + ")");
                }
                return generateThreadCurrent();
            }
            // String.fromCodePoint(int): UTF-8 encode a scalar (JSON uXXXX escape reverse;
            // combine surrogate pairs before calling). Range [0, 0x10FFFF].
            if (objName.equals("String") && node.method.equals("fromCodePoint")) {
                if (node.args.size() != 1) {
                    throw new RuntimeException("String.fromCodePoint expects one int argument (at line " + node.line + ")");
                }
                LLVMValue cp = generateExpr(node.args.get(0));
                if (!cp.type.equals("i32")) {
                    throw new RuntimeException("String.fromCodePoint expects an int argument (at line " + node.line + ")");
                }
                String cp64 = "%fcp.v." + tmpCount++;
                body.append("  ").append(cp64).append(" = sext i32 ").append(cp.value).append(" to i64\n");
                String neg = "%fcp.neg." + tmpCount++;
                body.append("  ").append(neg).append(" = icmp slt i64 ").append(cp64).append(", 0\n");
                String hi = "%fcp.hi." + tmpCount++;
                body.append("  ").append(hi).append(" = icmp sgt i64 ").append(cp64).append(", 1114111\n");
                String bad = "str.fcp.bad." + labelCount, ok = "str.fcp.ok." + labelCount;
                labelCount++;
                String any = "%fcp.any." + tmpCount++;
                body.append("  ").append(any).append(" = or i1 ").append(neg).append(", ").append(hi).append("\n");
                body.append("  br i1 ").append(any).append(", label %").append(bad).append(", label %").append(ok).append("\n");
                body.append(bad).append(":\n");
                emitRuntimeError("String.fromCodePoint: code point out of range", node.line, ok);
                body.append(ok).append(":\n");
                emitStringFromCodePointHelper();
                String r = "%fcp.r." + tmpCount++;
                body.append("  ").append(r).append(" = call i8* @cang.str.fromcp(i64 ").append(cp64).append(")\n");
                return new LLVMValue(r, "i8*", "String");
            }
            // String.fromDouble(double): shortest round-trip formatting (%.15g..%.17g with
            // strtod verification); NaN/±Infinity -> "null" (JSON semantics).
            if (objName.equals("String") && node.method.equals("fromDouble")) {
                if (node.args.size() != 1) {
                    throw new RuntimeException("String.fromDouble expects one argument (at line " + node.line + ")");
                }
                LLVMValue d = generateExpr(node.args.get(0));
                if (d.type.equals("float")) {
                    String ext = "%fd.ext." + tmpCount++;
                    body.append("  ").append(ext).append(" = fpext float ").append(d.value).append(" to double\n");
                    d = new LLVMValue(ext, "double");
                } else if (!d.type.equals("double")) {
                    throw new RuntimeException("String.fromDouble expects a double or float (at line " + node.line + ")");
                }
                emitStringFromDoubleHelper();
                String buf = "%fd.buf." + tmpCount++;
                body.append("  ").append(buf).append(" = call i8* @").append(allocFn()).append("(i64 64)\n");
                registerRegionAllocation(buf);
                String r = "%fd.r." + tmpCount++;
                body.append("  ").append(r).append(" = call i8* @cang.str.dtoa(i8* ").append(buf)
                     .append(", double ").append(d.value).append(")\n");
                return new LLVMValue(r, "i8*", "String");
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
            if (fi == null) {
                // `Apple(fruitRef)` used to be the downcast; it is now a plain (unknown) call.
                if (node.args.size() == 1 && classes.containsKey(node.method)) {
                    throw new RuntimeException("'" + node.method + "(...)' cast syntax has been removed, use "
                        + node.method + "!$(...) or " + node.method + "$(...) (at line " + node.line + ")");
                }
                throw new RuntimeException("Unknown function: " + node.method);
            }

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
                String ctorSym = fi.llvmName != null ? fi.llvmName : fi.name;
            body.append("  call void @").append(ctorSym).append("(").append(args).append(")\n");
                emitPostCallUnwindCheck();
                return new LLVMValue("void", "void");
            } else {
                String result = "%call." + tmpCount++;
                body.append("  ").append(result).append(" = call ").append(toLLVMType(fi.returnType))
                     .append(" @").append(fi.llvmName).append("(").append(args).append(")\n");
                emitPostCallUnwindCheck();
                String retSem = (callResultCarriesSemantic(fi.returnType))
                    ? fi.returnType : null;
                return new LLVMValue(result, toLLVMType(fi.returnType), retSem);
            }
        }

        // Regular method call
        if (objCache == null) objCache = generateExpr(node.object);
        LLVMValue objVal = objCache;
        // String methods (length/substring/...) — compiler intrinsics like Stdout dispatch.
        if ("String".equals(objVal.semanticType) && STRING_METHODS.contains(node.method)) {
            return generateStringMethod(objVal, node);
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
            String ctorSym = fi.llvmName != null ? fi.llvmName : fi.name;
            body.append("  call void @").append(ctorSym).append("(").append(args).append(")\n");
            emitPostCallUnwindCheck();
            return new LLVMValue("void", "void");
        } else {
            String result = "%call." + tmpCount++;
            body.append("  ").append(result).append(" = call ").append(toLLVMType(fi.returnType))
                 .append(" @").append(fi.llvmName).append("(").append(args).append(")\n");
            emitPostCallUnwindCheck();
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
            || t.equals("String");
    }

    /** Next logical thread id: atomic increment of @cang.thr.seq (0-based, thread-safe). */
    private String emitThreadIdAlloc() {
        String id = "%thr.seq.id." + tmpCount++;
        body.append("  ").append(id).append(" = atomicrmw add i32* @cang.thr.seq, i32 1 seq_cst\n");
        return id;
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

        // Pack layout: { fn ptr, args..., [i8* resultSlot], i32 threadId, i8* parentSelf }
        StringBuilder st = new StringBuilder("{ i8*");
        for (String p : ordinary.paramTypes) st.append(", ").append(toLLVMType(p));
        if (!isVoid) st.append(", i8*");
        st.append(", i32, i8* }");
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
        int resultIndex = -1;
        if (!isVoid) {
            String resultLLVM = toLLVMType(ordinary.returnType);
            long bits = llvmTypeBits(resultLLVM);
            long slotBytes = bits <= 0 ? 8 : Math.max(8, (bits + 7) / 8);
            rpRaw = "%thread.result.raw." + tmpCount++;
            body.append("  ").append(rpRaw).append(" = call i8* @").append(allocFn()).append("(i64 ").append(slotBytes).append(")\n");
            String rfield = "%thread.rfield." + tmpCount++;
            resultIndex = 1 + values.size();
            body.append("  ").append(rfield).append(" = getelementptr ").append(startType).append(", ").append(startType).append("* ").append(start).append(", i32 0, i32 ").append(resultIndex).append("\n");
            body.append("  store i8* ").append(rpRaw).append(", i8** ").append(rfield).append("\n");
        }
        int packIdIndex = (resultIndex >= 0 ? resultIndex : values.size()) + 1;
        // Parent self handle first (parent id < child id), then this thread's id.
        LLVMValue parentSelf = ensureThreadSelf();
        String parentCast = "%thread.pcast." + tmpCount++;
        body.append("  ").append(parentCast).append(" = bitcast %CangThreadHandle* ").append(parentSelf.value)
             .append(" to i8*\n");
        String threadId = emitThreadIdAlloc();
        String idField = "%thread.idf." + tmpCount++;
        body.append("  ").append(idField).append(" = getelementptr ").append(startType).append(", ").append(startType)
             .append("* ").append(start).append(", i32 0, i32 ").append(packIdIndex).append("\n");
        body.append("  store i32 ").append(threadId).append(", i32* ").append(idField).append("\n");
        String packParentField = "%thread.ppf." + tmpCount++;
        body.append("  ").append(packParentField).append(" = getelementptr ").append(startType).append(", ").append(startType)
             .append("* ").append(start).append(", i32 0, i32 ").append(packIdIndex + 1).append("\n");
        body.append("  store i8* ").append(parentCast).append(", i8** ").append(packParentField).append("\n");
        String handleRaw = "%thread.handle.raw." + tmpCount++;
        body.append("  ").append(handleRaw).append(" = call i8* @").append(allocFn()).append("(i64 40)\n");
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
        String hid = "%thread.hid." + tmpCount++;
        body.append("  ").append(hid).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle).append(", i32 0, i32 3\n");
        body.append("  store i32 ").append(threadId).append(", i32* ").append(hid).append("\n");
        String hparent = "%thread.hpf." + tmpCount++;
        body.append("  ").append(hparent).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle).append(", i32 0, i32 4\n");
        body.append("  store i8* ").append(parentCast).append(", i8** ").append(hparent).append("\n");
        String hkind = "%thread.hk." + tmpCount++;
        body.append("  ").append(hkind).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle).append(", i32 0, i32 5\n");
        body.append("  store i32 0, i32* ").append(hkind).append("\n");

        emitThreadEntry(ordinary, startType, isVoid, packIdIndex, threadCount);
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

    private void emitThreadEntry(FuncInfo ordinary, String startType, boolean isVoid, int packIdIndex, int id) {
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
        // Birth id + birth parent → TLS so currentThread()/getParent() match the spawn handle.
        x.append("  %tidp = getelementptr ").append(startType).append(", ").append(startType)
         .append("* %s, i32 0, i32 ").append(packIdIndex).append("\n");
        x.append("  %tidv = load i32, i32* %tidp\n");
        x.append("  store i32 %tidv, i32* @cang.thread.id\n");
        x.append("  %tpar = getelementptr ").append(startType).append(", ").append(startType)
         .append("* %s, i32 0, i32 ").append(packIdIndex + 1).append("\n");
        x.append("  %tparv = load i8*, i8** %tpar\n");
        x.append("  store i8* %tparv, i8** @cang.thread.parent\n");
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
        // Thread boundary: exceptions never cross threads (doc 15/17); isolate the flag.
        x.append("  store i32 0, i32* @cang.err.pending\n");
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
        emitBoundaryUnwindCheck(x);
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
        validateThreadTaskFn(fn, node.line, "Thread.task");
        emitThreadTaskFields(receiver, fn, "thr.task");
        return receiver;
    }

    /** Shared task-fn checks for new Thread(f) and .task(f): must be Function<void> code+receiver. */
    private void validateThreadTaskFn(LLVMValue fn, int line, String via) {
        String sig = fn.semanticType;
        if (sig == null || !isFunctionType(sig)) {
            throw new RuntimeException(via + " requires a no-argument no-return function, e.g. this::run or a lambda (at line " + line + ")");
        }
        String[] parts = functionTypeParts(sig);
        if (parts.length != 1 || !parts[0].equals("void")) {
            throw new RuntimeException(via + " requires a no-argument no-return function (Function<void>), got " + sig + " (at line " + line + ")");
        }
        if (!fn.type.equals("%CangFunction")) {
            throw new RuntimeException(via + " requires a method reference, function name or lambda (at line " + line + ")");
        }
    }

    /** Store { code, receiver } from a validated %CangFunction into Thread obj fields 0/1. */
    private void emitThreadTaskFields(LLVMValue obj, LLVMValue fn, String tag) {
        String code = "%" + tag + ".code." + tmpCount++;
        body.append("  ").append(code).append(" = extractvalue %CangFunction ").append(fn.value).append(", 0\n");
        String recv = "%" + tag + ".recv." + tmpCount++;
        body.append("  ").append(recv).append(" = extractvalue %CangFunction ").append(fn.value).append(", 1\n");
        String cfield = "%" + tag + ".cf." + tmpCount++;
        body.append("  ").append(cfield).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(obj.value)
             .append(", i32 0, i32 0\n");
        body.append("  store i8* ").append(code).append(", i8** ").append(cfield).append("\n");
        String rfield = "%" + tag + ".rf." + tmpCount++;
        body.append("  ").append(rfield).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(obj.value)
             .append(", i32 0, i32 1\n");
        body.append("  store i8* ").append(recv).append(", i8** ").append(rfield).append("\n");
    }

    /** Object-style .setDaemon(b): store the daemon flag; only legal before start();
     *  returns this so the chain new Thread().setDaemon(true).task(fn).start() works. */
    private LLVMValue generateThreadObjSetDaemon(LLVMValue receiver, MethodCallExpr node) {
        if (node.args.size() != 1) {
            throw new RuntimeException("Thread.setDaemon expects one bool argument (at line " + node.line + ")");
        }
        LLVMValue flag = generateExpr(node.args.get(0));
        if (!flag.type.equals("i1")) {
            throw new RuntimeException("Thread.setDaemon flag must be a bool (at line " + node.line + ")");
        }
        // The start-time registry decision (join-at-exit vs not) already ran — reject the change.
        String hfield = "%thr.setdaemon.hf." + tmpCount++;
        body.append("  ").append(hfield).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
             .append(", i32 0, i32 2\n");
        String oldHandle = "%thr.setdaemon.old." + tmpCount++;
        body.append("  ").append(oldHandle).append(" = load i8*, i8** ").append(hfield).append("\n");
        int id = labelCount++;
        String started = "thr.setdaemon.started." + id;
        String ok = "thr.setdaemon.ok." + id;
        String cmp = "%thr.setdaemon.cmp." + tmpCount++;
        body.append("  ").append(cmp).append(" = icmp ne i8* ").append(oldHandle).append(", null\n");
        body.append("  br i1 ").append(cmp).append(", label %").append(started).append(", label %").append(ok).append("\n");
        body.append(started).append(":\n");
        emitRuntimeError("Thread already started: setDaemon must be called before start", node.line, ok);
        body.append(ok).append(":\n");
        String dptr = "%thr.setdaemon.dp." + tmpCount++;
        body.append("  ").append(dptr).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
             .append(", i32 0, i32 3\n");
        body.append("  store i1 ").append(flag.value).append(", i1* ").append(dptr).append("\n");
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

        // Pack { i8* code, i8* receiver, i32 threadId, i8* parentSelf } — id/parent from new Thread().
        String objIdPtr = "%thr.start.idp." + tmpCount++;
        body.append("  ").append(objIdPtr).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
             .append(", i32 0, i32 4\n");
        String objId = "%thr.start.id." + tmpCount++;
        body.append("  ").append(objId).append(" = load i32, i32* ").append(objIdPtr).append("\n");
        String objParPtr = "%thr.start.ppp." + tmpCount++;
        body.append("  ").append(objParPtr).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(receiver.value)
             .append(", i32 0, i32 5\n");
        String objParent = "%thr.start.pv." + tmpCount++;
        body.append("  ").append(objParent).append(" = load i8*, i8** ").append(objParPtr).append("\n");
        String startType = "{ i8*, i8*, i32, i8* }";
        String raw = "%thr.start.raw." + tmpCount++;
        body.append("  ").append(raw).append(" = call i8* @").append(allocFn()).append("(i64 32)\n");
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
        String p2 = "%thr.start.p2." + tmpCount++;
        body.append("  ").append(p2).append(" = getelementptr ").append(startType).append(", ").append(startType)
             .append("* ").append(start).append(", i32 0, i32 2\n");
        body.append("  store i32 ").append(objId).append(", i32* ").append(p2).append("\n");
        String p3 = "%thr.start.p3." + tmpCount++;
        body.append("  ").append(p3).append(" = getelementptr ").append(startType).append(", ").append(startType)
             .append("* ").append(start).append(", i32 0, i32 3\n");
        body.append("  store i8* ").append(objParent).append(", i8** ").append(p3).append("\n");

        // Handle { tid, resultSlot=null, joined=0, id, parent }, store into obj field 2
        String handleRaw = "%thr.start.hraw." + tmpCount++;
        body.append("  ").append(handleRaw).append(" = call i8* @").append(allocFn()).append("(i64 40)\n");
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
        String hid = "%thr.start.hid." + tmpCount++;
        body.append("  ").append(hid).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle)
             .append(", i32 0, i32 3\n");
        body.append("  store i32 ").append(objId).append(", i32* ").append(hid).append("\n");
        String hpar = "%thr.start.hpar." + tmpCount++;
        body.append("  ").append(hpar).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle)
             .append(", i32 0, i32 4\n");
        body.append("  store i8* ").append(objParent).append(", i8** ").append(hpar).append("\n");
        String hkind = "%thr.start.hk." + tmpCount++;
        body.append("  ").append(hkind).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(handle)
             .append(", i32 0, i32 5\n");
        body.append("  store i32 0, i32* ").append(hkind).append("\n");
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
        String startType = "{ i8*, i8*, i32, i8* }";
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
        // Birth id + birth parent → TLS so currentThread()/getParent() match the start() handle.
        x.append("  %tidp = getelementptr ").append(startType).append(", ").append(startType)
         .append("* %s, i32 0, i32 2\n");
        x.append("  %tidv = load i32, i32* %tidp\n");
        x.append("  store i32 %tidv, i32* @cang.thread.id\n");
        x.append("  %tpar = getelementptr ").append(startType).append(", ").append(startType)
         .append("* %s, i32 0, i32 3\n");
        x.append("  %tparv = load i8*, i8** %tpar\n");
        x.append("  store i8* %tparv, i8** @cang.thread.parent\n");
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

    /**
     * static Thread.sleep(time): park the CURRENT thread for `time` milliseconds.
     * Windows Sleep(DWORD); posix nanosleep(timespec). Negative clamps to 0.
     */
    private LLVMValue generateThreadSleep(AST msAst, int line) {
        LLVMValue msVal = generateExpr(msAst);
        String ms64 = castValue(msVal, "i64");
        String neg = "%sleep.neg." + tmpCount++;
        body.append("  ").append(neg).append(" = icmp slt i64 ").append(ms64).append(", 0\n");
        String z = "%sleep.z." + tmpCount++;
        body.append("  ").append(z).append(" = select i1 ").append(neg).append(", i64 0, i64 ").append(ms64).append("\n");
        if (targetPlatform.equals("windows")) {
            if (!header.toString().contains("declare void @Sleep(i32)")) {
                header.append("declare void @Sleep(i32)\n");
            }
            String big = "%sleep.big." + tmpCount++;
            body.append("  ").append(big).append(" = icmp sgt i64 ").append(z).append(", 4294967295\n");
            String clamped = "%sleep.cl." + tmpCount++;
            body.append("  ").append(clamped).append(" = select i1 ").append(big).append(", i64 4294967295, i64 ").append(z).append("\n");
            String ms32 = "%sleep.ms32." + tmpCount++;
            body.append("  ").append(ms32).append(" = trunc i64 ").append(clamped).append(" to i32\n");
            body.append("  call void @Sleep(i32 ").append(ms32).append(")\n");
        } else {
            if (!header.toString().contains("declare i32 @nanosleep(")) {
                header.append("declare i32 @nanosleep(i8*, i8*)\n");
            }
            String sec = "%sleep.sec." + tmpCount++;
            body.append("  ").append(sec).append(" = sdiv i64 ").append(z).append(", 1000\n");
            String rem = "%sleep.rem." + tmpCount++;
            body.append("  ").append(rem).append(" = srem i64 ").append(z).append(", 1000\n");
            String nsec = "%sleep.ns." + tmpCount++;
            body.append("  ").append(nsec).append(" = mul i64 ").append(rem).append(", 1000000\n");
            String ts = "%sleep.ts." + tmpCount++;
            body.append("  ").append(ts).append(" = alloca { i64, i64 }\n");
            String ts0 = "%sleep.ts0." + tmpCount++;
            body.append("  ").append(ts0).append(" = getelementptr { i64, i64 }, { i64, i64 }* ").append(ts)
                 .append(", i32 0, i32 0\n");
            body.append("  store i64 ").append(sec).append(", i64* ").append(ts0).append("\n");
            String ts1 = "%sleep.ts1." + tmpCount++;
            body.append("  ").append(ts1).append(" = getelementptr { i64, i64 }, { i64, i64 }* ").append(ts)
                 .append(", i32 0, i32 1\n");
            body.append("  store i64 ").append(nsec).append(", i64* ").append(ts1).append("\n");
            String tsp = "%sleep.tsp." + tmpCount++;
            body.append("  ").append(tsp).append(" = bitcast { i64, i64 }* ").append(ts).append(" to i8*\n");
            body.append("  %sleep.rv.").append(tmpCount++)
                 .append(" = call i32 @nanosleep(i8* ").append(tsp).append(", i8* null)\n");
        }
        return new LLVMValue("void", "void");
    }

    /**
     * Thread.currentThread(): synthetic %CangThreadHandle { osTid, result=null, joined=1 }
     * cached per-thread in @cang.thread.self (thread_local) — repeated calls on the same
     * thread return the same pointer, so reference equality identifies the thread.
     * joined=1 makes .join() fail fast (a thread cannot join itself). Rooted via
     * GC_malloc_uncollectable / malloc because TLS is not a reliable GC root on all targets.
     */
    /**
     * Resolve this thread's cached self handle, creating it on first use (main lazily gets
     * one when it creates its first child or calls currentThread). The self handle's parent
     * field comes from @cang.thread.parent — null on main (written by thread entries on
     * workers), so getParent() == null identifies the root thread.
     */
    private LLVMValue ensureThreadSelf() {
        if (!header.toString().contains("@cang.thread.self = ")) {
            header.append("@cang.thread.self = thread_local global i8* null\n");
        }
        if (!header.toString().contains("@cang.thread.id = ")) {
            header.append("@cang.thread.id = thread_local global i32 -1\n");
        }
        if (!header.toString().contains("@cang.thread.parent = ")) {
            header.append("@cang.thread.parent = thread_local global i8* null\n");
        }
        // Birth id: entry functions store it before the task runs; main lazily takes one.
        int idLbl = labelCount++;
        String idAlloc = "thr.self.idalloc." + idLbl;
        String idDone = "thr.self.iddone." + idLbl;
        String curId = "%thr.self.curid." + tmpCount++;
        body.append("  ").append(curId).append(" = load i32, i32* @cang.thread.id\n");
        String hasId = "%thr.self.hasid." + tmpCount++;
        body.append("  ").append(hasId).append(" = icmp sge i32 ").append(curId).append(", 0\n");
        body.append("  br i1 ").append(hasId).append(", label %").append(idDone).append(", label %").append(idAlloc).append("\n");
        body.append(idAlloc).append(":\n");
        String freshId = emitThreadIdAlloc();
        body.append("  store i32 ").append(freshId).append(", i32* @cang.thread.id\n");
        body.append("  br label %").append(idDone).append("\n");
        body.append(idDone).append(":\n");
        String threadId = "%thr.self.tid." + tmpCount++;
        body.append("  ").append(threadId).append(" = load i32, i32* @cang.thread.id\n");

        String cur = "%thr.self.cur." + tmpCount++;
        body.append("  ").append(cur).append(" = load i8*, i8** @cang.thread.self\n");
        String isNull = "%thr.self.isnull." + tmpCount++;
        body.append("  ").append(isNull).append(" = icmp eq i8* ").append(cur).append(", null\n");
        int id = labelCount++;
        String create = "thr.self.create." + id;
        String done = "thr.self.done." + id;
        body.append("  br i1 ").append(isNull).append(", label %").append(create)
             .append(", label %").append(done).append("\n");
        body.append(create).append(":\n");
        String raw = "%thr.self.raw." + tmpCount++;
        if (gcEnabled) {
            if (!header.toString().contains("declare i8* @GC_malloc_uncollectable(")) {
                header.append("declare i8* @GC_malloc_uncollectable(i64)\n");
            }
            body.append("  ").append(raw).append(" = call i8* @GC_malloc_uncollectable(i64 40)\n");
        } else {
            body.append("  ").append(raw).append(" = call i8* @malloc(i64 40)\n");
        }
        String h = "%thr.self.h." + tmpCount++;
        body.append("  ").append(h).append(" = bitcast i8* ").append(raw).append(" to %CangThreadHandle*\n");
        String tidPtr = "%thr.self.tidp." + tmpCount++;
        body.append("  ").append(tidPtr).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(h)
             .append(", i32 0, i32 0\n");
        if (targetPlatform.equals("windows")) {
            if (!header.toString().contains("declare i32 @GetCurrentThreadId(")) {
                header.append("declare i32 @GetCurrentThreadId()\n");
            }
            String tid32 = "%thr.self.tid32." + tmpCount++;
            body.append("  ").append(tid32).append(" = call i32 @GetCurrentThreadId()\n");
            String tid64 = "%thr.self.tid64." + tmpCount++;
            body.append("  ").append(tid64).append(" = zext i32 ").append(tid32).append(" to i64\n");
            body.append("  store i64 ").append(tid64).append(", i64* ").append(tidPtr).append("\n");
        } else {
            if (!header.toString().contains("declare i64 @pthread_self(")) {
                header.append("declare i64 @pthread_self()\n");
            }
            String osTid = "%thr.self.ostid." + tmpCount++;
            body.append("  ").append(osTid).append(" = call i64 @pthread_self()\n");
            body.append("  store i64 ").append(osTid).append(", i64* ").append(tidPtr).append("\n");
        }
        String resPtr = "%thr.self.resp." + tmpCount++;
        body.append("  ").append(resPtr).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(h)
             .append(", i32 0, i32 1\n");
        body.append("  store i8* null, i8** ").append(resPtr).append("\n");
        String joinPtr = "%thr.self.jp." + tmpCount++;
        body.append("  ").append(joinPtr).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(h)
             .append(", i32 0, i32 2\n");
        body.append("  store i32 1, i32* ").append(joinPtr).append("\n");
        String idFieldPtr = "%thr.self.idfp." + tmpCount++;
        body.append("  ").append(idFieldPtr).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(h)
             .append(", i32 0, i32 3\n");
        body.append("  store i32 ").append(threadId).append(", i32* ").append(idFieldPtr).append("\n");
        // Parent: birth parent from TLS (null on main; entries write it for workers).
        String parentPtr = "%thr.self.pfp." + tmpCount++;
        body.append("  ").append(parentPtr).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(h)
             .append(", i32 0, i32 4\n");
        String birthParent = "%thr.self.bp." + tmpCount++;
        body.append("  ").append(birthParent).append(" = load i8*, i8** @cang.thread.parent\n");
        body.append("  store i8* ").append(birthParent).append(", i8** ").append(parentPtr).append("\n");
        String kindPtr = "%thr.self.kp." + tmpCount++;
        body.append("  ").append(kindPtr).append(" = getelementptr %CangThreadHandle, %CangThreadHandle* ").append(h)
             .append(", i32 0, i32 5\n");
        body.append("  store i32 1, i32* ").append(kindPtr).append("\n");
        body.append("  store i8* ").append(raw).append(", i8** @cang.thread.self\n");
        body.append("  br label %").append(done).append("\n");
        body.append(done).append(":\n");
        String res = "%thr.self.res." + tmpCount++;
        body.append("  ").append(res).append(" = load i8*, i8** @cang.thread.self\n");
        return new LLVMValue(res, "%CangThreadHandle*", "Thread<void>");
    }

    /**
     * Header helper: is the thread behind this handle still running?
     * Birth handles (kind=0): joined flag short-circuits to false; Windows probes the OS
     * handle via GetExitCodeThread (closed at join → dead), posix pthread_kill(tid, 0).
     * Self handles (kind=1): osTid is a thread id on both platforms — Windows OpenThread
     * + WaitForSingleObject(0): WAIT_TIMEOUT = alive; posix pthread_kill on pthread_t.
     */
    private void emitThreadIsAliveHelper() {
        if (header.toString().contains("define i1 @cang.thread.isalive(")) return;
        boolean win = targetPlatform.equals("windows");
        StringBuilder x = new StringBuilder();
        if (win) {
            if (!header.toString().contains("declare i32 @GetExitCodeThread(")) {
                header.append("declare i32 @GetExitCodeThread(i8*, i32*)\n");
            }
            if (!header.toString().contains("declare i8* @OpenThread(")) {
                header.append("declare i8* @OpenThread(i32, i32, i32)\n");
            }
            if (!header.toString().contains("declare i32 @WaitForSingleObject(")) {
                header.append("declare i32 @WaitForSingleObject(i8*, i32)\n");
            }
            if (!header.toString().contains("declare i32 @CloseHandle(")) {
                header.append("declare i32 @CloseHandle(i8*)\n");
            }
        } else {
            if (!header.toString().contains("declare i32 @pthread_kill(")) {
                header.append("declare i32 @pthread_kill(i64, i32)\n");
            }
        }
        x.append("define i1 @cang.thread.isalive(i8* %hraw) {\n");
        x.append("entry:\n");
        x.append("  %h = bitcast i8* %hraw to %CangThreadHandle*\n");
        x.append("  %kp = getelementptr %CangThreadHandle, %CangThreadHandle* %h, i32 0, i32 5\n");
        x.append("  %kind = load i32, i32* %kp\n");
        x.append("  %isself = icmp eq i32 %kind, 1\n");
        x.append("  br i1 %isself, label %SELF, label %BIRTH\n");
        x.append("BIRTH:\n");
        x.append("  %jp = getelementptr %CangThreadHandle, %CangThreadHandle* %h, i32 0, i32 2\n");
        x.append("  %j = load i32, i32* %jp\n");
        x.append("  %jc = icmp eq i32 %j, 1\n");
        x.append("  br i1 %jc, label %DEAD, label %BPROBE\n");
        if (win) {
            x.append("BPROBE:\n");
            x.append("  %btp = getelementptr %CangThreadHandle, %CangThreadHandle* %h, i32 0, i32 0\n");
            x.append("  %bth = load i64, i64* %btp\n");
            x.append("  %bhh = inttoptr i64 %bth to i8*\n");
            x.append("  %cp = alloca i32\n");
            x.append("  %ok = call i32 @GetExitCodeThread(i8* %bhh, i32* %cp)\n");
            x.append("  %okn = icmp ne i32 %ok, 0\n");
            x.append("  %st = load i32, i32* %cp\n");
            x.append("  %sta = icmp eq i32 %st, 259\n");
            x.append("  %ba = and i1 %okn, %sta\n");
            x.append("  br label %RES\n");
            x.append("DEAD:\n");
            x.append("  br label %RES\n");
            x.append("SELF:\n");
            x.append("  %stp = getelementptr %CangThreadHandle, %CangThreadHandle* %h, i32 0, i32 0\n");
            x.append("  %stid = load i64, i64* %stp\n");
            x.append("  %stid32 = trunc i64 %stid to i32\n");
            x.append("  %oh = call i8* @OpenThread(i32 1048576, i32 0, i32 %stid32)\n");
            x.append("  %ohn = icmp eq i8* %oh, null\n");
            x.append("  br i1 %ohn, label %SDEAD, label %SWAIT\n");
            x.append("SDEAD:\n");
            x.append("  br label %RES\n");
            x.append("SWAIT:\n");
            x.append("  %w = call i32 @WaitForSingleObject(i8* %oh, i32 0)\n");
            x.append("  %c1 = call i32 @CloseHandle(i8* %oh)\n");
            x.append("  %wt = icmp eq i32 %w, 258\n");
            x.append("  br label %RES\n");
            x.append("RES:\n");
            x.append("  %r = phi i1 [ false, %DEAD ], [ %ba, %BPROBE ], [ false, %SDEAD ], [ %wt, %SWAIT ]\n");
        } else {
            x.append("BPROBE:\n");
            x.append("  %btp = getelementptr %CangThreadHandle, %CangThreadHandle* %h, i32 0, i32 0\n");
            x.append("  %btt = load i64, i64* %btp\n");
            x.append("  %be = call i32 @pthread_kill(i64 %btt, i32 0)\n");
            x.append("  %ba = icmp eq i32 %be, 0\n");
            x.append("  br label %RES\n");
            x.append("DEAD:\n");
            x.append("  br label %RES\n");
            x.append("SELF:\n");
            x.append("  %stp = getelementptr %CangThreadHandle, %CangThreadHandle* %h, i32 0, i32 0\n");
            x.append("  %stt = load i64, i64* %stp\n");
            x.append("  %se = call i32 @pthread_kill(i64 %stt, i32 0)\n");
            x.append("  %sa = icmp eq i32 %se, 0\n");
            x.append("  br label %RES\n");
            x.append("RES:\n");
            x.append("  %r = phi i1 [ false, %DEAD ], [ %ba, %BPROBE ], [ %sa, %SELF ]\n");
        }
        x.append("  ret i1 %r\n");
        x.append("}\n\n");
        header.append(x);
    }

    private LLVMValue generateThreadCurrent() {
        return ensureThreadSelf();
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
        if (resultCang.equals("String")) resultSemantic = resultCang;
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

        if (method.equals("hypot") && args.size() == 2) {
            String a = castValue(args.get(0), "double"), b = castValue(args.get(1), "double");
            String sa = "%math.ha." + tmpCount++;
            String sb = "%math.hb." + tmpCount++;
            String s = "%math.hs." + tmpCount++;
            body.append("  ").append(sa).append(" = fmul double ").append(a).append(", ").append(a).append("\n");
            body.append("  ").append(sb).append(" = fmul double ").append(b).append(", ").append(b).append("\n");
            body.append("  ").append(s).append(" = fadd double ").append(sa).append(", ").append(sb).append("\n");
            String r = "%math.hypot." + tmpCount++;
            body.append("  ").append(r).append(" = call double @llvm.sqrt.f64(double ").append(s).append(")\n");
            return new LLVMValue(r, "double");
        }
        if (method.equals("fmod") && args.size() == 2) {
            String r = "%math.fmod." + tmpCount++;
            body.append("  ").append(r).append(" = frem double ")
                 .append(castValue(args.get(0), "double")).append(", ")
                 .append(castValue(args.get(1), "double")).append("\n");
            return new LLVMValue(r, "double");
        }
        if (method.equals("signum") && args.size() == 1) {
            // NaN falls into the 0.0 arm (v1 boundary, documented)
            String v = castValue(args.get(0), "double");
            String gt = "%math.sg.gt." + tmpCount++;
            String lt = "%math.sg.lt." + tmpCount++;
            String pos = "%math.sg.pos." + tmpCount++;
            String r = "%math.signum." + tmpCount++;
            body.append("  ").append(gt).append(" = fcmp ogt double ").append(v).append(", 0.0\n");
            body.append("  ").append(lt).append(" = fcmp olt double ").append(v).append(", 0.0\n");
            body.append("  ").append(pos).append(" = select i1 ").append(gt).append(", double 1.0, double 0.0\n");
            body.append("  ").append(r).append(" = select i1 ").append(lt).append(", double -1.0, double ").append(pos).append("\n");
            return new LLVMValue(r, "double");
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
        else if (method.equals("log2")) intrinsic = "log2";
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
            // True millisecond wall clock (was time()*1000 = second resolution).
            String millis = "%system.time.millis." + tmpCount++;
            if (targetPlatform.equals("windows")) {
                // FILETIME (100ns since 1601) -> Unix ms; Precise keeps sub-ms updates.
                if (!header.toString().contains("declare void @GetSystemTimePreciseAsFileTime(i8*)")) {
                    header.append("declare void @GetSystemTimePreciseAsFileTime(i8*)\n");
                }
                String ft = "%system.time.ft." + tmpCount++;
                body.append("  ").append(ft).append(" = alloca i64\n");
                String ftp = "%system.time.ftp." + tmpCount++;
                body.append("  ").append(ftp).append(" = bitcast i64* ").append(ft).append(" to i8*\n");
                body.append("  call void @GetSystemTimePreciseAsFileTime(i8* ").append(ftp).append(")\n");
                String ftv = "%system.time.ftv." + tmpCount++;
                body.append("  ").append(ftv).append(" = load i64, i64* ").append(ft).append("\n");
                String adj = "%system.time.adj." + tmpCount++;
                body.append("  ").append(adj).append(" = sub i64 ").append(ftv).append(", 116444736000000000\n");
                body.append("  ").append(millis).append(" = udiv i64 ").append(adj).append(", 10000\n");
            } else {
                // posix: gettimeofday(&tv, NULL); tv = { tv_sec, tv_usec }
                if (!header.toString().contains("declare i32 @gettimeofday(")) {
                    header.append("declare i32 @gettimeofday(i8*, i8*)\n");
                }
                String tv = "%system.time.tv." + tmpCount++;
                body.append("  ").append(tv).append(" = alloca { i64, i64 }\n");
                String tvp = "%system.time.tvp." + tmpCount++;
                body.append("  ").append(tvp).append(" = bitcast { i64, i64 }* ").append(tv).append(" to i8*\n");
                body.append("  %system.time.tvr.").append(tmpCount++)
                     .append(" = call i32 @gettimeofday(i8* ").append(tvp).append(", i8* null)\n");
                String secp = "%system.time.secp." + tmpCount++;
                body.append("  ").append(secp).append(" = getelementptr { i64, i64 }, { i64, i64 }* ").append(tv)
                     .append(", i32 0, i32 0\n");
                String sec = "%system.time.sec." + tmpCount++;
                body.append("  ").append(sec).append(" = load i64, i64* ").append(secp).append("\n");
                String usecp = "%system.time.usecp." + tmpCount++;
                body.append("  ").append(usecp).append(" = getelementptr { i64, i64 }, { i64, i64 }* ").append(tv)
                     .append(", i32 0, i32 1\n");
                String usec = "%system.time.usec." + tmpCount++;
                body.append("  ").append(usec).append(" = load i64, i64* ").append(usecp).append("\n");
                String secMs = "%system.time.secms." + tmpCount++;
                body.append("  ").append(secMs).append(" = mul i64 ").append(sec).append(", 1000\n");
                String usecMs = "%system.time.usecms." + tmpCount++;
                body.append("  ").append(usecMs).append(" = sdiv i64 ").append(usec).append(", 1000\n");
                body.append("  ").append(millis).append(" = add i64 ").append(secMs).append(", ").append(usecMs).append("\n");
            }
            return new LLVMValue(millis, "i64");
        }
        if (method.equals("getCwd") && nodes.isEmpty()) {
            // Inlined into the caller: never `ret` here — one result slot, branches join at `done`.
            int cid = labelCount++;
            String slot = "%system.cwd.slot." + tmpCount;
            body.append("  ").append(slot).append(" = alloca i8*\n");
            tmpCount++;
            if (targetPlatform.equals("windows")) {
                if (!header.toString().contains("declare i32 @GetCurrentDirectoryW(")) {
                    header.append("declare i32 @GetCurrentDirectoryW(i32, i16*)\n");
                }
                if (!header.toString().contains("declare i32 @WideCharToMultiByte(")) {
                    header.append("declare i32 @WideCharToMultiByte(i32, i32, i16*, i32, i8*, i32, i8*, i8*)\n");
                }
                String need = "%system.cwd.need." + tmpCount++;
                body.append("  ").append(need).append(" = call i32 @GetCurrentDirectoryW(i32 0, i16* null)\n");
                String needZ = "%system.cwd.needz." + tmpCount++;
                body.append("  ").append(needZ).append(" = icmp eq i32 ").append(need).append(", 0\n");
                body.append("  br i1 ").append(needZ).append(", label %system.cwd.fail." + cid
                    + ", label %system.cwd.ok." + cid + "\n");
                body.append("system.cwd.ok.").append(cid).append(":\n");
                String need64 = "%system.cwd.need64." + tmpCount++;
                body.append("  ").append(need64).append(" = zext i32 ").append(need).append(" to i64\n");
                String wbytes = "%system.cwd.wbytes." + tmpCount++;
                body.append("  ").append(wbytes).append(" = mul i64 ").append(need64).append(", 2\n");
                String wbuf = "%system.cwd.wbuf." + tmpCount;
                body.append("  ").append(wbuf).append(" = call i8* @").append(allocFn())
                     .append("(i64 ").append(wbytes).append(")\n");
                tmpCount++;
                String w16 = "%system.cwd.w16." + tmpCount++;
                body.append("  ").append(w16).append(" = bitcast i8* ").append(wbuf).append(" to i16*\n");
                String got = "%system.cwd.got." + tmpCount++;
                body.append("  ").append(got).append(" = call i32 @GetCurrentDirectoryW(i32 ").append(need)
                     .append(", i16* ").append(w16).append(")\n");
                String u8len = "%system.cwd.u8len." + tmpCount++;
                body.append("  ").append(u8len).append(" = call i32 @WideCharToMultiByte(i32 65001, i32 0, i16* ")
                     .append(w16).append(", i32 -1, i8* null, i32 0, i8* null, i8* null)\n");
                String u8sz = "%system.cwd.u8sz." + tmpCount++;
                body.append("  ").append(u8sz).append(" = sext i32 ").append(u8len).append(" to i64\n");
                String out = "%system.cwd.out." + tmpCount;
                body.append("  ").append(out).append(" = call i8* @").append(allocFn())
                     .append("(i64 ").append(u8sz).append(")\n");
                tmpCount++;
                String cvt = "%system.cwd.cvt." + tmpCount++;
                body.append("  ").append(cvt).append(" = call i32 @WideCharToMultiByte(i32 65001, i32 0, i16* ")
                     .append(w16).append(", i32 -1, i8* ").append(out).append(", i32 ").append(u8len)
                     .append(", i8* null, i8* null)\n");
                body.append("  store i8* ").append(out).append(", i8** ").append(slot).append("\n");
                body.append("  br label %system.cwd.done." + cid + "\n");
                body.append("system.cwd.fail.").append(cid).append(":\n");
                body.append("  store i8* null, i8** ").append(slot).append("\n");
                body.append("  br label %system.cwd.done." + cid + "\n");
            } else {
                if (!header.toString().contains("declare i8* @getcwd(")) {
                    header.append("declare i8* @getcwd(i8*, i64)\n");
                }
                String buf = "%system.cwd.buf." + tmpCount;
                body.append("  ").append(buf).append(" = call i8* @").append(allocFn()).append("(i64 4096)\n");
                tmpCount++;
                String r = "%system.cwd.r." + tmpCount++;
                body.append("  ").append(r).append(" = call i8* @getcwd(i8* ").append(buf).append(", i64 4096)\n");
                String rZ = "%system.cwd.rz." + tmpCount++;
                body.append("  ").append(rZ).append(" = icmp eq i8* ").append(r).append(", null\n");
                body.append("  br i1 ").append(rZ).append(", label %system.cwd.fail." + cid
                    + ", label %system.cwd.ok2." + cid + "\n");
                body.append("system.cwd.ok2.").append(cid).append(":\n");
                body.append("  store i8* ").append(buf).append(", i8** ").append(slot).append("\n");
                body.append("  br label %system.cwd.done." + cid + "\n");
                body.append("system.cwd.fail.").append(cid).append(":\n");
                body.append("  store i8* null, i8** ").append(slot).append("\n");
                body.append("  br label %system.cwd.done." + cid + "\n");
            }
            body.append("system.cwd.done.").append(cid).append(":\n");
            String ret = "%system.cwd.ret." + tmpCount++;
            body.append("  ").append(ret).append(" = load i8*, i8** ").append(slot).append("\n");
            return new LLVMValue(ret, "i8*", "String");
        }
        if (method.equals("getExecDir") && nodes.isEmpty()) {
            // Own executable's directory, independent of cwd. Result-slot + join (inlined, no ret).
            int eid = labelCount++;
            String eslot = "%system.exdir.slot." + tmpCount;
            body.append("  ").append(eslot).append(" = alloca i8*\n");
            tmpCount++;
            if (targetPlatform.equals("windows")) {
                if (!header.toString().contains("declare i32 @GetModuleFileNameW(")) {
                    header.append("declare i32 @GetModuleFileNameW(i16*, i16*, i32)\n");
                }
                if (!header.toString().contains("declare i32 @WideCharToMultiByte(")) {
                    header.append("declare i32 @WideCharToMultiByte(i32, i32, i16*, i32, i8*, i32, i8*, i8*)\n");
                }
                // 1024 WCHAR buffer — covers long paths; 0 = failure, n == cap = truncated.
                String wbytes = "%system.exdir.wbytes." + tmpCount++;
                body.append("  ").append(wbytes).append(" = mul i64 1024, 2\n");
                String wbuf = "%system.exdir.wbuf." + tmpCount;
                body.append("  ").append(wbuf).append(" = call i8* @").append(allocFn())
                     .append("(i64 ").append(wbytes).append(")\n");
                tmpCount++;
                String w16 = "%system.exdir.w16." + tmpCount++;
                body.append("  ").append(w16).append(" = bitcast i8* ").append(wbuf).append(" to i16*\n");
                String n = "%system.exdir.n." + tmpCount++;
                body.append("  ").append(n).append(" = call i32 @GetModuleFileNameW(i16* null, i16* ")
                     .append(w16).append(", i32 1024)\n");
                String nZ = "%system.exdir.nz." + tmpCount++;
                body.append("  ").append(nZ).append(" = icmp eq i32 ").append(n).append(", 0\n");
                String nCap = "%system.exdir.ncap." + tmpCount++;
                body.append("  ").append(nCap).append(" = icmp eq i32 ").append(n).append(", 1024\n");
                String nBad = "%system.exdir.nbad." + tmpCount++;
                body.append("  ").append(nBad).append(" = or i1 ").append(nZ).append(", ").append(nCap).append("\n");
                body.append("  br i1 ").append(nBad).append(", label %system.exdir.fail." + eid
                    + ", label %system.exdir.scan." + eid + "\n");
                // scan for the last path separator (0x5C '\' / 0x2F '/')
                body.append("system.exdir.scan.").append(eid).append(":\n");
                String last = "%system.exdir.last." + tmpCount;
                body.append("  ").append(last).append(" = alloca i64\n  store i64 -1, i64* ").append(last).append("\n");
                tmpCount++;
                String idx = "%system.exdir.i." + tmpCount;
                body.append("  ").append(idx).append(" = alloca i64\n  store i64 0, i64* ").append(idx).append("\n");
                tmpCount++;
                String n64 = "%system.exdir.n64." + tmpCount++;
                body.append("  ").append(n64).append(" = zext i32 ").append(n).append(" to i64\n");
                body.append("  br label %system.exdir.loop." + eid + "\n");
                body.append("system.exdir.loop.").append(eid).append(":\n");
                String iv = "%system.exdir.iv." + tmpCount++;
                body.append("  ").append(iv).append(" = load i64, i64* ").append(idx).append("\n");
                String ilt = "%system.exdir.ilt." + tmpCount++;
                body.append("  ").append(ilt).append(" = icmp slt i64 ").append(iv).append(", ").append(n64).append("\n");
                body.append("  br i1 ").append(ilt).append(", label %system.exdir.ch." + eid
                    + ", label %system.exdir.scandone." + eid + "\n");
                body.append("system.exdir.ch.").append(eid).append(":\n");
                String cp = "%system.exdir.cp." + tmpCount++;
                body.append("  ").append(cp).append(" = getelementptr i16, i16* ").append(w16).append(", i64 ").append(iv).append("\n");
                String cv = "%system.exdir.cv." + tmpCount++;
                body.append("  ").append(cv).append(" = load i16, i16* ").append(cp).append("\n");
                String cBS = "%system.exdir.cbs." + tmpCount++;
                body.append("  ").append(cBS).append(" = icmp eq i16 ").append(cv).append(", 92\n");
                String cFS = "%system.exdir.cfs." + tmpCount++;
                body.append("  ").append(cFS).append(" = icmp eq i16 ").append(cv).append(", 47\n");
                String cSep = "%system.exdir.csep." + tmpCount++;
                body.append("  ").append(cSep).append(" = or i1 ").append(cBS).append(", ").append(cFS).append("\n");
                body.append("  br i1 ").append(cSep).append(", label %system.exdir.setsep." + eid
                    + ", label %system.exdir.next." + eid + "\n");
                body.append("system.exdir.setsep.").append(eid).append(":\n");
                body.append("  store i64 ").append(iv).append(", i64* ").append(last).append("\n");
                body.append("  br label %system.exdir.next.").append(eid).append("\n");
                body.append("system.exdir.next.").append(eid).append(":\n");
                String iinc = "%system.exdir.iinc." + tmpCount++;
                body.append("  ").append(iinc).append(" = add i64 ").append(iv).append(", 1\n");
                body.append("  store i64 ").append(iinc).append(", i64* ").append(idx).append("\n");
                body.append("  br label %system.exdir.loop.").append(eid).append("\n");
                body.append("system.exdir.scandone.").append(eid).append(":\n");
                String lv = "%system.exdir.lv." + tmpCount++;
                body.append("  ").append(lv).append(" = load i64, i64* ").append(last).append("\n");
                String lBad = "%system.exdir.lbad." + tmpCount++;
                body.append("  ").append(lBad).append(" = icmp sle i64 ").append(lv).append(", 0\n");
                body.append("  br i1 ").append(lBad).append(", label %system.exdir.fail." + eid
                    + ", label %system.exdir.cut." + eid + "\n");
                // overwrite the separator with NUL so the wide string ends at the directory
                body.append("system.exdir.cut.").append(eid).append(":\n");
                String zp = "%system.exdir.zp." + tmpCount++;
                body.append("  ").append(zp).append(" = getelementptr i16, i16* ").append(w16).append(", i64 ").append(lv).append("\n");
                body.append("  store i16 0, i16* ").append(zp).append("\n");
                String u8len = "%system.exdir.u8len." + tmpCount++;
                body.append("  ").append(u8len).append(" = call i32 @WideCharToMultiByte(i32 65001, i32 0, i16* ")
                     .append(w16).append(", i32 -1, i8* null, i32 0, i8* null, i8* null)\n");
                String u8sz = "%system.exdir.u8sz." + tmpCount++;
                body.append("  ").append(u8sz).append(" = sext i32 ").append(u8len).append(" to i64\n");
                String out = "%system.exdir.out." + tmpCount;
                body.append("  ").append(out).append(" = call i8* @").append(allocFn())
                     .append("(i64 ").append(u8sz).append(")\n");
                tmpCount++;
                String cvt = "%system.exdir.cvt." + tmpCount++;
                body.append("  ").append(cvt).append(" = call i32 @WideCharToMultiByte(i32 65001, i32 0, i16* ")
                     .append(w16).append(", i32 -1, i8* ").append(out).append(", i32 ").append(u8len)
                     .append(", i8* null, i8* null)\n");
                body.append("  store i8* ").append(out).append(", i8** ").append(eslot).append("\n");
                body.append("  br label %system.exdir.done.").append(eid).append("\n");
                body.append("system.exdir.fail.").append(eid).append(":\n");
                body.append("  store i8* null, i8** ").append(eslot).append("\n");
                body.append("  br label %system.exdir.done.").append(eid).append("\n");
            } else {
                if (!header.toString().contains("declare i64 @readlink(")) {
                    header.append("declare i64 @readlink(i8*, i8*, i64)\n");
                }
                // "/proc/self/exe" constant via the shared string pool
                LLVMValue selfPath = makeStringConstant("/proc/self/exe");
                String buf = "%system.exdir.buf." + tmpCount;
                body.append("  ").append(buf).append(" = call i8* @").append(allocFn()).append("(i64 1024)\n");
                tmpCount++;
                String n = "%system.exdir.n." + tmpCount++;
                body.append("  ").append(n).append(" = call i64 @readlink(i8* ").append(selfPath.value)
                     .append(", i8* ").append(buf).append(", i64 1023)\n");
                String nNeg = "%system.exdir.nneg." + tmpCount++;
                body.append("  ").append(nNeg).append(" = icmp slt i64 ").append(n).append(", 0\n");
                body.append("  br i1 ").append(nNeg).append(", label %system.exdir.fail." + eid
                    + ", label %system.exdir.term." + eid + "\n");
                body.append("system.exdir.term.").append(eid).append(":\n");
                // readlink does not NUL-terminate
                String tzp = "%system.exdir.tzp." + tmpCount++;
                body.append("  ").append(tzp).append(" = getelementptr i8, i8* ").append(buf).append(", i64 ").append(n).append("\n");
                body.append("  store i8 0, i8* ").append(tzp).append("\n");
                // walk for the last '/'
                String last = "%system.exdir.last." + tmpCount;
                body.append("  ").append(last).append(" = alloca i64\n  store i64 -1, i64* ").append(last).append("\n");
                tmpCount++;
                String idx = "%system.exdir.i." + tmpCount;
                body.append("  ").append(idx).append(" = alloca i64\n  store i64 0, i64* ").append(idx).append("\n");
                tmpCount++;
                body.append("  br label %system.exdir.loop." + eid + "\n");
                body.append("system.exdir.loop.").append(eid).append(":\n");
                String iv = "%system.exdir.iv." + tmpCount++;
                body.append("  ").append(iv).append(" = load i64, i64* ").append(idx).append("\n");
                String ilt = "%system.exdir.ilt." + tmpCount++;
                body.append("  ").append(ilt).append(" = icmp slt i64 ").append(iv).append(", ").append(n).append("\n");
                body.append("  br i1 ").append(ilt).append(", label %system.exdir.ch." + eid
                    + ", label %system.exdir.scandone." + eid + "\n");
                body.append("system.exdir.ch.").append(eid).append(":\n");
                String cp = "%system.exdir.cp." + tmpCount++;
                body.append("  ").append(cp).append(" = getelementptr i8, i8* ").append(buf).append(", i64 ").append(iv).append("\n");
                String cv = "%system.exdir.cv." + tmpCount++;
                body.append("  ").append(cv).append(" = load i8, i8* ").append(cp).append("\n");
                String cFS = "%system.exdir.cfs." + tmpCount++;
                body.append("  ").append(cFS).append(" = icmp eq i8 ").append(cv).append(", 47\n");
                body.append("  br i1 ").append(cFS).append(", label %system.exdir.setsep." + eid
                    + ", label %system.exdir.next." + eid + "\n");
                body.append("system.exdir.setsep.").append(eid).append(":\n");
                body.append("  store i64 ").append(iv).append(", i64* ").append(last).append("\n");
                body.append("  br label %system.exdir.next.").append(eid).append("\n");
                body.append("system.exdir.next.").append(eid).append(":\n");
                String iinc = "%system.exdir.iinc." + tmpCount++;
                body.append("  ").append(iinc).append(" = add i64 ").append(iv).append(", 1\n");
                body.append("  store i64 ").append(iinc).append(", i64* ").append(idx).append("\n");
                body.append("  br label %system.exdir.loop.").append(eid).append("\n");
                body.append("system.exdir.scandone.").append(eid).append(":\n");
                String lv = "%system.exdir.lv." + tmpCount++;
                body.append("  ").append(lv).append(" = load i64, i64* ").append(last).append("\n");
                String lBad = "%system.exdir.lbad." + tmpCount++;
                body.append("  ").append(lBad).append(" = icmp sle i64 ").append(lv).append(", 0\n");
                body.append("  br i1 ").append(lBad).append(", label %system.exdir.fail." + eid
                    + ", label %system.exdir.cut." + eid + "\n");
                body.append("system.exdir.cut.").append(eid).append(":\n");
                String zp = "%system.exdir.zp." + tmpCount++;
                body.append("  ").append(zp).append(" = getelementptr i8, i8* ").append(buf).append(", i64 ").append(lv).append("\n");
                body.append("  store i8 0, i8* ").append(zp).append("\n");
                body.append("  store i8* ").append(buf).append(", i8** ").append(eslot).append("\n");
                body.append("  br label %system.exdir.done.").append(eid).append("\n");
                body.append("system.exdir.fail.").append(eid).append(":\n");
                body.append("  store i8* null, i8** ").append(eslot).append("\n");
                body.append("  br label %system.exdir.done.").append(eid).append("\n");
            }
            body.append("system.exdir.done.").append(eid).append(":\n");
            String eret = "%system.exdir.ret." + tmpCount++;
            body.append("  ").append(eret).append(" = load i8*, i8** ").append(eslot).append("\n");
            return new LLVMValue(eret, "i8*", "String");
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
            String ctorSym = fi.llvmName != null ? fi.llvmName : fi.name;
            body.append("  call void @").append(ctorSym).append("(").append(args).append(")\n");
            emitPostCallUnwindCheck();
            return new LLVMValue("void", "void");
        } else {
            String result = "%call." + tmpCount++;
            body.append("  ").append(result).append(" = call ").append(toLLVMType(fi.returnType))
                 .append(" @").append(fi.llvmName).append("(").append(args).append(")\n");
            emitPostCallUnwindCheck();
            if (isProcessExec(fi)) {
                emitSpawnFailThrow(result, line);
            }
            String retSem = (callResultCarriesSemantic(fi.returnType))
                ? fi.returnType : null;
            return new LLVMValue(result, toLLVMType(fi.returnType), retSem);
        }
    }

    /** ProcessBuilder.exec: spawn failure returns -2 from the wrapper and throws at the call site. */
    private static boolean isProcessExec(FuncInfo fi) {
        boolean byClass = "cang_lang_ProcessBuilder".equals(fi.className);
        boolean byName = fi.name != null && fi.name.endsWith(".exec")
                && (fi.className != null || fi.name.startsWith("cang_lang_ProcessBuilder."));
        return (byClass || byName) && "int".equals(fi.returnType) && !fi.isStatic == false;
    }

    /** The native String surface (stdlib/cang/lang/String.cang) — dispatched intrinsically. */
    private static final java.util.Set<String> STRING_METHODS = java.util.Set.of(
        "length", "startsWith", "endsWith", "indexOf", "indexOfIgnoreCase",
        "substring", "toUpper", "toLower", "trim", "charAt", "byteAt", "replace", "split",
        "parseInt", "parseLong", "parseDouble");

    /** String intrinsics: receiver is the i8* string itself (no struct header). */
    private LLVMValue generateStringMethod(LLVMValue recv, MethodCallExpr node) {
        String m = node.method;
        int line = node.line;
        // Null receiver -> catchable error (same pattern as object field access).
        int id = labelCount++;
        String nn = "str.nn." + id;
        String isNull = "%str.isnull." + tmpCount++;
        body.append("  ").append(isNull).append(" = icmp eq i8* ").append(recv.value).append(", null\n");
        body.append("  br i1 ").append(isNull).append(", label %str.null.").append(id)
             .append(", label %").append(nn).append("\n\n");
        body.append("str.null.").append(id).append(":\n");
        emitRuntimeError("Null pointer dereference: String." + m, line, nn);
        body.append(nn).append(":\n");

        switch (m) {
            case "length": {
                String l = "%len." + tmpCount++;
                body.append("  ").append(l).append(" = call i64 @strlen(i8* ").append(recv.value).append(")\n");
                String r = "%l32." + tmpCount++;
                body.append("  ").append(r).append(" = trunc i64 ").append(l).append(" to i32\n");
                return new LLVMValue(r, "i32");
            }
            case "startsWith": {
                requireArgs(node, 1, m);
                LLVMValue p = generateExpr(node.args.get(0));
                String lp = "%lps." + tmpCount++;
                body.append("  ").append(lp).append(" = call i64 @strlen(i8* ").append(p.value).append(")\n");
                String c = "%cmps." + tmpCount++;
                body.append("  ").append(c).append(" = call i32 @strncmp(i8* ").append(recv.value)
                     .append(", i8* ").append(p.value).append(", i64 ").append(lp).append(")\n");
                String e = "%eq." + tmpCount++;
                body.append("  ").append(e).append(" = icmp eq i32 ").append(c).append(", 0\n");
                return new LLVMValue(e, "i1");
            }
            case "endsWith": {
                requireArgs(node, 1, m);
                LLVMValue p = generateExpr(node.args.get(0));
                String ls = "%lss." + tmpCount++;
                body.append("  ").append(ls).append(" = call i64 @strlen(i8* ").append(p.value).append(")\n");
                String lr = "%lsr." + tmpCount++;
                body.append("  ").append(lr).append(" = call i64 @strlen(i8* ").append(recv.value).append(")\n");
                String gt = "%gt." + tmpCount++;
                body.append("  ").append(gt).append(" = icmp ugt i64 ").append(ls).append(", ").append(lr).append("\n");
                // Inlined into the CALLER function — must not `ret` here; store the result and join.
                String res = "%sfx.res." + tmpCount++;
                body.append("  ").append(res).append(" = alloca i1\n");
                String yes = "str.sfx.yes." + id, no = "str.sfx.no." + id, join = "str.sfx.join." + id;
                body.append("  br i1 ").append(gt).append(", label %").append(yes)
                     .append(", label %").append(no).append("\n");
                body.append(yes).append(":\n");
                body.append("  store i1 0, i1* ").append(res).append("\n");
                body.append("  br label %").append(join).append("\n");
                body.append(no).append(":\n");
                String off = "%off." + tmpCount++;
                body.append("  ").append(off).append(" = sub i64 ").append(lr).append(", ").append(ls).append("\n");
                String base = "%sfx." + tmpCount++;
                body.append("  ").append(base).append(" = getelementptr i8, i8* ").append(recv.value)
                     .append(", i64 ").append(off).append("\n");
                String c = "%cmpsfx." + tmpCount++;
                body.append("  ").append(c).append(" = call i32 @memcmp(i8* ").append(base)
                     .append(", i8* ").append(p.value).append(", i64 ").append(ls).append(")\n");
                String e = "%eqs." + tmpCount++;
                body.append("  ").append(e).append(" = icmp eq i32 ").append(c).append(", 0\n");
                body.append("  store i1 ").append(e).append(", i1* ").append(res).append("\n");
                body.append("  br label %").append(join).append("\n");
                body.append(join).append(":\n");
                String out = "%sfx.out." + tmpCount++;
                body.append("  ").append(out).append(" = load i1, i1* ").append(res).append("\n");
                return new LLVMValue(out, "i1");
            }
            case "indexOf": {
                requireArgs(node, 1, m);
                LLVMValue sub = generateExpr(node.args.get(0));
                return emitIndexOf(recv.value, sub.value);
            }
            case "indexOfIgnoreCase": {
                requireArgs(node, 1, m);
                LLVMValue sub = generateExpr(node.args.get(0));
                String la = emitCaseConv(recv.value, false, line);
                String lb = emitCaseConv(sub.value, false, line);
                LLVMValue r = emitIndexOf(la, lb);
                body.append("  call void @").append(freeFn()).append("(i8* ").append(la).append(")\n");
                body.append("  call void @").append(freeFn()).append("(i8* ").append(lb).append(")\n");
                return r;
            }
            case "substring": {
                requireArgs(node, 2, m);
                LLVMValue st = generateExpr(node.args.get(0));
                LLVMValue en = generateExpr(node.args.get(1));
                String s64 = "%st." + tmpCount++;
                body.append("  ").append(s64).append(" = sext i32 ").append(st.value).append(" to i64\n");
                String e64 = "%en." + tmpCount++;
                body.append("  ").append(e64).append(" = sext i32 ").append(en.value).append(" to i64\n");
                String len = "%tlen." + tmpCount++;
                body.append("  ").append(len).append(" = call i64 @strlen(i8* ").append(recv.value).append(")\n");
                String b1 = "%b1." + tmpCount++;
                body.append("  ").append(b1).append(" = icmp slt i64 ").append(s64).append(", 0\n");
                String b2 = "%b2." + tmpCount++;
                body.append("  ").append(b2).append(" = icmp slt i64 ").append(e64).append(", 0\n");
                String b3 = "%b3." + tmpCount++;
                body.append("  ").append(b3).append(" = icmp ugt i64 ").append(e64).append(", ").append(len).append("\n");
                String b4 = "%b4." + tmpCount++;
                body.append("  ").append(b4).append(" = icmp ugt i64 ").append(s64).append(", ").append(e64).append("\n");
                String o1 = "%o1." + tmpCount++;
                body.append("  ").append(o1).append(" = or i1 ").append(b1).append(", ").append(b2).append("\n");
                String o2 = "%o2." + tmpCount++;
                body.append("  ").append(o2).append(" = or i1 ").append(o1).append(", ").append(b3).append("\n");
                String bad = "str.sub.bad." + id, sok = "str.sub.ok." + id;
                String o3 = "%o3." + tmpCount++;
                body.append("  ").append(o3).append(" = or i1 ").append(o2).append(", ").append(b4).append("\n");
                body.append("  br i1 ").append(o3).append(", label %").append(bad)
                     .append(", label %").append(sok).append("\n");
                body.append(bad).append(":\n");
                emitRuntimeError("String index out of bounds", line, sok);
                body.append(sok).append(":\n");
                String l = "%sub.l." + tmpCount++;
                body.append("  ").append(l).append(" = sub i64 ").append(e64).append(", ").append(s64).append("\n");
                String sz = "%sub.sz." + tmpCount++;
                body.append("  ").append(sz).append(" = add i64 ").append(l).append(", 1\n");
                String buf = "%sub.buf." + tmpCount++;
                body.append("  ").append(buf).append(" = call i8* @").append(allocFn())
                     .append("(i64 ").append(sz).append(")\n");
                registerRegionAllocation(buf);
                String src = "%sub.src." + tmpCount++;
                body.append("  ").append(src).append(" = getelementptr i8, i8* ").append(recv.value)
                     .append(", i64 ").append(s64).append("\n");
                body.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* ").append(buf)
                     .append(", i8* ").append(src).append(", i64 ").append(l).append(", i1 false)\n");
                String term = "%sub.term." + tmpCount++;
                body.append("  ").append(term).append(" = getelementptr i8, i8* ").append(buf)
                     .append(", i64 ").append(l).append("\n");
                body.append("  store i8 0, i8* ").append(term).append("\n");
                return new LLVMValue(buf, "i8*", "String");
            }
            case "charAt": {
                requireArgs(node, 1, m);
                LLVMValue ix = generateExpr(node.args.get(0));
                if (!ix.type.equals("i32")) {
                    throw new RuntimeException("String.charAt expects an int index (at line " + line + ")");
                }
                String i64 = "%ca.i." + tmpCount++;
                body.append("  ").append(i64).append(" = sext i32 ").append(ix.value).append(" to i64\n");
                String len = "%ca.len." + tmpCount++;
                body.append("  ").append(len).append(" = call i64 @strlen(i8* ").append(recv.value).append(")\n");
                String neg = "%ca.neg." + tmpCount++;
                body.append("  ").append(neg).append(" = icmp slt i64 ").append(i64).append(", 0\n");
                String past = "%ca.past." + tmpCount++;
                body.append("  ").append(past).append(" = icmp uge i64 ").append(i64).append(", ").append(len).append("\n");
                String bad = "str.ca.bad." + id, cok = "str.ca.ok." + id;
                String any = "%ca.any." + tmpCount++;
                body.append("  ").append(any).append(" = or i1 ").append(neg).append(", ").append(past).append("\n");
                body.append("  br i1 ").append(any).append(", label %").append(bad).append(", label %").append(cok).append("\n");
                body.append(bad).append(":\n");
                emitRuntimeError("String index out of bounds", line, cok);
                body.append(cok).append(":\n");
                String buf = "%ca.buf." + tmpCount++;
                body.append("  ").append(buf).append(" = call i8* @").append(allocFn()).append("(i64 2)\n");
                registerRegionAllocation(buf);
                String src = "%ca.src." + tmpCount++;
                body.append("  ").append(src).append(" = getelementptr i8, i8* ").append(recv.value)
                     .append(", i64 ").append(i64).append("\n");
                String ch = "%ca.ch." + tmpCount++;
                body.append("  ").append(ch).append(" = load i8, i8* ").append(src).append("\n");
                body.append("  store i8 ").append(ch).append(", i8* ").append(buf).append("\n");
                String term = "%ca.term." + tmpCount++;
                body.append("  ").append(term).append(" = getelementptr i8, i8* ").append(buf).append(", i64 1\n");
                body.append("  store i8 0, i8* ").append(term).append("\n");
                return new LLVMValue(buf, "i8*", "String");
            }
            case "byteAt": {
                requireArgs(node, 1, m);
                LLVMValue ix = generateExpr(node.args.get(0));
                if (!ix.type.equals("i32")) {
                    throw new RuntimeException("String.byteAt expects an int index (at line " + line + ")");
                }
                String i64 = "%ba.i." + tmpCount++;
                body.append("  ").append(i64).append(" = sext i32 ").append(ix.value).append(" to i64\n");
                // 只检查负下标：上界检查需要 strlen，会让逐字节扫描退化为 O(n^2)。
                // 调用方必须自行保证 index < length()（与 C 的指针访问一致）。
                String neg = "%ba.neg." + tmpCount++;
                body.append("  ").append(neg).append(" = icmp slt i64 ").append(i64).append(", 0\n");
                String bad = "str.ba.bad." + id, bok = "str.ba.ok." + id;
                body.append("  br i1 ").append(neg).append(", label %").append(bad)
                     .append(", label %").append(bok).append("\n");
                body.append(bad).append(":\n");
                emitRuntimeError("String index out of bounds", line, bok);
                body.append(bok).append(":\n");
                String ptr = "%ba.src." + tmpCount++;
                body.append("  ").append(ptr).append(" = getelementptr i8, i8* ").append(recv.value)
                     .append(", i64 ").append(i64).append("\n");
                String ch = "%ba.ch." + tmpCount++;
                body.append("  ").append(ch).append(" = load i8, i8* ").append(ptr).append("\n");
                String out = "%ba.out." + tmpCount++;
                body.append("  ").append(out).append(" = zext i8 ").append(ch).append(" to i32\n");
                return new LLVMValue(out, "i32");
            }
            case "replace": {
                requireArgs(node, 2, m);
                LLVMValue from = generateExpr(node.args.get(0));
                LLVMValue to = generateExpr(node.args.get(1));
                if (!from.type.equals("i8*") || !to.type.equals("i8*")) {
                    throw new RuntimeException("String.replace expects two String arguments (at line " + line + ")");
                }
                // Empty needle would make the copy loop rescan forever — reject it.
                String fp = "%rp.from." + tmpCount++;
                body.append("  ").append(fp).append(" = getelementptr i8, i8* ").append(from.value).append(", i64 0\n");
                String fb = "%rp.fb." + tmpCount++;
                body.append("  ").append(fb).append(" = load i8, i8* ").append(fp).append("\n");
                String femp = "%rp.empty." + tmpCount++;
                body.append("  ").append(femp).append(" = icmp eq i8 ").append(fb).append(", 0\n");
                String bad = "str.rp.bad." + id, rok = "str.rp.ok." + id;
                body.append("  br i1 ").append(femp).append(", label %").append(bad).append(", label %").append(rok).append("\n");
                body.append(bad).append(":\n");
                emitRuntimeError("String.replace: 'from' must be non-empty", line, rok);
                body.append(rok).append(":\n");
                emitStringReplaceHelper();
                String r = "%rp.r." + tmpCount++;
                body.append("  ").append(r).append(" = call i8* @cang.str.replace(i8* ").append(recv.value)
                     .append(", i8* ").append(from.value).append(", i8* ").append(to.value).append(")\n");
                return new LLVMValue(r, "i8*", "String");
            }
            case "split": {
                requireArgs(node, 1, m);
                LLVMValue sep = generateExpr(node.args.get(0));
                if (!sep.type.equals("i8*")) {
                    throw new RuntimeException("String.split expects a String separator (at line " + line + ")");
                }
                String sp = "%sp.sep." + tmpCount++;
                body.append("  ").append(sp).append(" = getelementptr i8, i8* ").append(sep.value).append(", i64 0\n");
                String sb = "%sp.sb." + tmpCount++;
                body.append("  ").append(sb).append(" = load i8, i8* ").append(sp).append("\n");
                String semp = "%sp.empty." + tmpCount++;
                body.append("  ").append(semp).append(" = icmp eq i8 ").append(sb).append(", 0\n");
                String bad = "str.sp.bad." + id, sok2 = "str.sp.ok." + id;
                body.append("  br i1 ").append(semp).append(", label %").append(bad).append(", label %").append(sok2).append("\n");
                body.append(bad).append(":\n");
                emitRuntimeError("String.split: separator must be non-empty", line, sok2);
                body.append(sok2).append(":\n");
                emitStringSplitHelper();
                String r = "%sp.r." + tmpCount++;
                body.append("  ").append(r).append(" = call i8* @cang.str.split(i8* ").append(recv.value)
                     .append(", i8* ").append(sep.value).append(")\n");
                return new LLVMValue(r, "i8*", "Array<String>");
            }
            case "toUpper":
                return new LLVMValue(emitCaseConv(recv.value, true, line), "i8*", "String");
            case "toLower":
                return new LLVMValue(emitCaseConv(recv.value, false, line), "i8*", "String");
            case "trim":
                return new LLVMValue(emitTrim(recv.value), "i8*", "String");
            case "parseInt": {
                requireArgs(node, 0, m);
                LLVMValue raw = emitParseNum(recv, false, line);
                // i32 range guard (strtoll happily parses beyond int)
                int rid = labelCount++;
                String rbad = "str.pi.bad." + rid, rok = "str.pi.ok." + rid;
                String hi = "%p.hi." + tmpCount++;
                body.append("  ").append(hi).append(" = icmp sgt i64 ").append(raw.value).append(", 2147483647\n");
                String lo = "%p.lo." + tmpCount++;
                body.append("  ").append(lo).append(" = icmp slt i64 ").append(raw.value).append(", -2147483648\n");
                String oob = "%p.oob." + tmpCount++;
                body.append("  ").append(oob).append(" = or i1 ").append(hi).append(", ").append(lo).append("\n");
                body.append("  br i1 ").append(oob).append(", label %").append(rbad)
                     .append(", label %").append(rok).append("\n");
                body.append(rbad).append(":\n");
                emitRuntimeError("Number out of range for 'int'", line, rok);
                body.append(rok).append(":\n");
                String r32 = "%p.i32." + tmpCount++;
                body.append("  ").append(r32).append(" = trunc i64 ").append(raw.value).append(" to i32\n");
                return new LLVMValue(r32, "i32", "int");
            }
            case "parseLong":
                requireArgs(node, 0, m);
                return emitParseNum(recv, false, line);
            case "parseDouble":
                requireArgs(node, 0, m);
                return emitParseNum(recv, true, line);
            default:
                throw new RuntimeException("Unknown String method: " + m + " (at line " + line + ")");
        }
    }

    private void requireArgs(MethodCallExpr node, int n, String m) {
        if (node.args.size() != n) {
            throw new RuntimeException("String." + m + " expects " + n + " argument(s) (at line " + node.line + ")");
        }
    }

    /** strtoll/strtod with an end-pointer full-consume guard: input that is not a
     *  complete number (or is empty) is a runtime error. Returns i64 (ints) or double. */
    private LLVMValue emitParseNum(LLVMValue recv, boolean dbl, int line) {
        int id = labelCount++;
        String okL = "str.parse.ok." + id, failL = "str.parse.fail." + id;
        // Java-style: surrounding whitespace is ignored (trim first; strtoll/strtod skip leading too)
        String trimmed = emitTrim(recv.value);
        String slot = "%ep.slot." + tmpCount++;
        body.append("  ").append(slot).append(" = alloca i8*\n");
        String v = "%pv." + tmpCount++;
        if (dbl) {
            body.append("  ").append(v).append(" = call double @strtod(i8* ").append(trimmed)
                 .append(", i8** ").append(slot).append(")\n");
        } else {
            body.append("  ").append(v).append(" = call i64 @strtoll(i8* ").append(trimmed)
                 .append(", i8** ").append(slot).append(", i32 10)\n");
        }
        String ep = "%ep." + tmpCount++;
        body.append("  ").append(ep).append(" = load i8*, i8** ").append(slot).append("\n");
        String c = "%epc." + tmpCount++;
        body.append("  ").append(c).append(" = load i8, i8* ").append(ep).append("\n");
        String consumed = "%pcons." + tmpCount++;
        body.append("  ").append(consumed).append(" = icmp ne i8* ").append(ep).append(", ").append(trimmed).append("\n");
        String term = "%pterm." + tmpCount++;
        body.append("  ").append(term).append(" = icmp eq i8 ").append(c).append(", 0\n");
        String ok = "%pok." + tmpCount++;
        body.append("  ").append(ok).append(" = and i1 ").append(consumed).append(", ").append(term).append("\n");
        body.append("  br i1 ").append(ok).append(", label %").append(okL)
             .append(", label %").append(failL).append("\n");
        body.append(failL).append(":\n");
        emitRuntimeError("Invalid number", line, okL);
        body.append(okL).append(":\n");
        return new LLVMValue(v, dbl ? "double" : "i64", dbl ? "double" : "long");
    }

    /** strstr + pointer delta -> index or -1 (inlined: stores the result, never `ret`s). */
    private LLVMValue emitIndexOf(String s, String sub) {
        String r = "%idx.r." + tmpCount++;
        body.append("  ").append(r).append(" = call i8* @strstr(i8* ").append(s).append(", i8* ").append(sub).append(")\n");
        String isn = "%idx.nil." + tmpCount++;
        body.append("  ").append(isn).append(" = icmp eq i8* ").append(r).append(", null\n");
        String res = "%idx.res." + tmpCount++;
        body.append("  ").append(res).append(" = alloca i32\n");
        String neg = "str.idx.neg." + labelCount++, hit = "str.idx.hit." + labelCount++;
        String join = "str.idx.join." + labelCount++;
        body.append("  br i1 ").append(isn).append(", label %").append(neg).append(", label %").append(hit).append("\n");
        body.append(neg).append(":\n  store i32 -1, i32* ").append(res).append("\n");
        body.append("  br label %").append(join).append("\n");
        body.append(hit).append(":\n");
        String p1 = "%idx.p1." + tmpCount++;
        body.append("  ").append(p1).append(" = ptrtoint i8* ").append(s).append(" to i64\n");
        String p2 = "%idx.p2." + tmpCount++;
        body.append("  ").append(p2).append(" = ptrtoint i8* ").append(r).append(" to i64\n");
        String d = "%idx.d." + tmpCount++;
        body.append("  ").append(d).append(" = sub i64 ").append(p2).append(", ").append(p1).append("\n");
        String t = "%idx.t." + tmpCount++;
        body.append("  ").append(t).append(" = trunc i64 ").append(d).append(" to i32\n");
        body.append("  store i32 ").append(t).append(", i32* ").append(res).append("\n");
        body.append("  br label %").append(join).append("\n");
        body.append(join).append(":\n");
        String out = "%idx.out." + tmpCount++;
        body.append("  ").append(out).append(" = load i32, i32* ").append(res).append("\n");
        return new LLVMValue(out, "i32");
    }

    /** ASCII case conversion into a fresh heap buffer (toUpper=true -> upper). */
    private String emitCaseConv(String s, boolean toUpper, int line) {
        String len = "%cc.len." + tmpCount++;
        body.append("  ").append(len).append(" = call i64 @strlen(i8* ").append(s).append(")\n");
        String sz = "%cc.sz." + tmpCount++;
        body.append("  ").append(sz).append(" = add i64 ").append(len).append(", 1\n");
        String buf = "%cc.buf." + tmpCount++;
        body.append("  ").append(buf).append(" = call i8* @").append(allocFn()).append("(i64 ").append(sz).append(")\n");
        registerRegionAllocation(buf);
        String ip = "%cc.i." + tmpCount++;
        body.append("  ").append(ip).append(" = alloca i64\n");
        body.append("  store i64 0, i64* ").append(ip).append("\n");
        int id = labelCount++;
        String cond = "cc.cond." + id, bodyL = "cc.body." + id, done = "cc.done." + id, upd = "cc.upd." + id;
        body.append("  br label %").append(cond).append("\n\n");
        body.append(cond).append(":\n");
        String iv = "%cc.iv." + tmpCount++;
        body.append("  ").append(iv).append(" = load i64, i64* ").append(ip).append("\n");
        String c = "%cc.c." + tmpCount++;
        body.append("  ").append(c).append(" = icmp slt i64 ").append(iv).append(", ").append(len).append("\n");
        body.append("  br i1 ").append(c).append(", label %").append(bodyL).append(", label %").append(done).append("\n\n");
        body.append(bodyL).append(":\n");
        String sp = "%cc.sp." + tmpCount++;
        body.append("  ").append(sp).append(" = getelementptr i8, i8* ").append(s).append(", i64 ").append(iv).append("\n");
        String ch = "%cc.ch." + tmpCount++;
        body.append("  ").append(ch).append(" = load i8, i8* ").append(sp).append("\n");
        String ge, le, in, adj, conv;
        ge = "%cc.ge." + tmpCount++;
        le = "%cc.le." + tmpCount++;
        in = "%cc.in." + tmpCount++;
        adj = "%cc.adj." + tmpCount++;
        conv = "%cc.cv." + tmpCount++;
        if (toUpper) {
            body.append("  ").append(ge).append(" = icmp uge i8 ").append(ch).append(", 97\n");
            body.append("  ").append(le).append(" = icmp ule i8 ").append(ch).append(", 122\n");
            body.append("  ").append(in).append(" = and i1 ").append(ge).append(", ").append(le).append("\n");
            body.append("  ").append(adj).append(" = sub i8 ").append(ch).append(", 32\n");
        } else {
            body.append("  ").append(ge).append(" = icmp uge i8 ").append(ch).append(", 65\n");
            body.append("  ").append(le).append(" = icmp ule i8 ").append(ch).append(", 90\n");
            body.append("  ").append(in).append(" = and i1 ").append(ge).append(", ").append(le).append("\n");
            body.append("  ").append(adj).append(" = add i8 ").append(ch).append(", 32\n");
        }
        body.append("  ").append(conv).append(" = select i1 ").append(in).append(", i8 ").append(adj)
             .append(", i8 ").append(ch).append("\n");
        String bp = "%cc.bp." + tmpCount++;
        body.append("  ").append(bp).append(" = getelementptr i8, i8* ").append(buf).append(", i64 ").append(iv).append("\n");
        body.append("  store i8 ").append(conv).append(", i8* ").append(bp).append("\n");
        body.append("  br label %").append(upd).append("\n");
        body.append(upd).append(":\n");
        String iv2 = "%cc.iv2." + tmpCount++;
        body.append("  ").append(iv2).append(" = add i64 ").append(iv).append(", 1\n");
        body.append("  store i64 ").append(iv2).append(", i64* ").append(ip).append("\n");
        body.append("  br label %").append(cond).append("\n\n");
        body.append(done).append(":\n");
        String np = "%cc.np." + tmpCount++;
        body.append("  ").append(np).append(" = getelementptr i8, i8* ").append(buf).append(", i64 ").append(len).append("\n");
        body.append("  store i8 0, i8* ").append(np).append("\n");
        return buf;
    }

    /**
     * String.fromCodePoint runtime: scalar → UTF-8 (1–4 bytes + NUL).
     * Range is validated at the call site (it has the source line for the error).
     */
    private void emitStringFromCodePointHelper() {
        if (header.toString().contains("define i8* @cang.str.fromcp(")) return;
        String alloc = allocFn();
        header.append("define i8* @cang.str.fromcp(i64 %cp) {\n");
        header.append("entry:\n");
        header.append("  %buf = call i8* @").append(alloc).append("(i64 5)\n");
        header.append("  %lt80 = icmp ult i64 %cp, 128\n");
        header.append("  br i1 %lt80, label %b1, label %chk2\n");
        header.append("b1:\n");
        header.append("  %c1 = trunc i64 %cp to i8\n");
        header.append("  store i8 %c1, i8* %buf\n");
        header.append("  %z1 = getelementptr i8, i8* %buf, i64 1\n");
        header.append("  store i8 0, i8* %z1\n");
        header.append("  br label %exit\n");
        header.append("chk2:\n");
        header.append("  %lt800 = icmp ult i64 %cp, 2048\n");
        header.append("  br i1 %lt800, label %b2, label %chk3\n");
        header.append("b2:\n");
        // 110xxxxx 10xxxxxx
        header.append("  %r2s = lshr i64 %cp, 6\n");
        header.append("  %b02 = or i64 %r2s, 192\n");
        header.append("  %b02t = trunc i64 %b02 to i8\n");
        header.append("  store i8 %b02t, i8* %buf\n");
        header.append("  %lo2 = and i64 %cp, 63\n");
        header.append("  %lo2o = or i64 %lo2, 128\n");
        header.append("  %lo2c = trunc i64 %lo2o to i8\n");
        header.append("  %p2 = getelementptr i8, i8* %buf, i64 1\n");
        header.append("  store i8 %lo2c, i8* %p2\n");
        header.append("  %z2 = getelementptr i8, i8* %buf, i64 2\n");
        header.append("  store i8 0, i8* %z2\n");
        header.append("  br label %exit\n");
        header.append("chk3:\n");
        header.append("  %lt10000 = icmp ult i64 %cp, 65536\n");
        header.append("  br i1 %lt10000, label %b3, label %b4\n");
        header.append("b3:\n");
        // 1110xxxx 10xxxxxx 10xxxxxx
        header.append("  %s1 = lshr i64 %cp, 12\n");
        header.append("  %e1 = or i64 %s1, 224\n");
        header.append("  %e1t = trunc i64 %e1 to i8\n");
        header.append("  store i8 %e1t, i8* %buf\n");
        header.append("  %m2s = lshr i64 %cp, 6\n");
        header.append("  %m2a = and i64 %m2s, 63\n");
        header.append("  %m2o = or i64 %m2a, 128\n");
        header.append("  %m2t = trunc i64 %m2o to i8\n");
        header.append("  %p31 = getelementptr i8, i8* %buf, i64 1\n");
        header.append("  store i8 %m2t, i8* %p31\n");
        header.append("  %m3a = and i64 %cp, 63\n");
        header.append("  %m3o = or i64 %m3a, 128\n");
        header.append("  %m3t = trunc i64 %m3o to i8\n");
        header.append("  %p32 = getelementptr i8, i8* %buf, i64 2\n");
        header.append("  store i8 %m3t, i8* %p32\n");
        header.append("  %z3 = getelementptr i8, i8* %buf, i64 3\n");
        header.append("  store i8 0, i8* %z3\n");
        header.append("  br label %exit\n");
        header.append("b4:\n");
        // 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
        header.append("  %s41 = lshr i64 %cp, 18\n");
        header.append("  %e41 = or i64 %s41, 240\n");
        header.append("  %e41t = trunc i64 %e41 to i8\n");
        header.append("  store i8 %e41t, i8* %buf\n");
        header.append("  %s42 = lshr i64 %cp, 12\n");
        header.append("  %a42 = and i64 %s42, 63\n");
        header.append("  %o42 = or i64 %a42, 128\n");
        header.append("  %t42 = trunc i64 %o42 to i8\n");
        header.append("  %p41 = getelementptr i8, i8* %buf, i64 1\n");
        header.append("  store i8 %t42, i8* %p41\n");
        header.append("  %s43 = lshr i64 %cp, 6\n");
        header.append("  %a43 = and i64 %s43, 63\n");
        header.append("  %o43 = or i64 %a43, 128\n");
        header.append("  %t43 = trunc i64 %o43 to i8\n");
        header.append("  %p42 = getelementptr i8, i8* %buf, i64 2\n");
        header.append("  store i8 %t43, i8* %p42\n");
        header.append("  %a44 = and i64 %cp, 63\n");
        header.append("  %o44 = or i64 %a44, 128\n");
        header.append("  %t44 = trunc i64 %o44 to i8\n");
        header.append("  %p43 = getelementptr i8, i8* %buf, i64 3\n");
        header.append("  store i8 %t44, i8* %p43\n");
        header.append("  %z4 = getelementptr i8, i8* %buf, i64 4\n");
        header.append("  store i8 0, i8* %z4\n");
        header.append("  br label %exit\n");
        header.append("exit:\n");
        header.append("  ret i8* %buf\n");
        header.append("}\n\n");
    }

    /**
     * String.fromDouble runtime: shortest round-trip decimal — try %.15g, %.16g, %.17g
     * and keep the first whose strtod parse equals the original bit pattern.
     * NaN/±Infinity return the JSON null literal.
     */
    private void emitStringFromDoubleHelper() {
        if (header.toString().contains("define i8* @cang.str.dtoa(")) return;
        if (!header.toString().contains("declare double @strtod(")) {
            header.append("declare double @strtod(i8*, i8**)\n");
        }
        if (!header.toString().contains("@.str.dtoa.fmt = ")) {
            header.append("@.str.dtoa.fmt = private constant [5 x i8] c\"%.*g\\00\"\n");
        }
        header.append("define i8* @cang.str.dtoa(i8* %buf, double %d) {\n");
        header.append("entry:\n");
        header.append("  %isnan = fcmp une double %d, %d\n");
        header.append("  br i1 %isnan, label %jsonnull, label %chkinf\n");
        header.append("chkinf:\n");
        header.append("  %pinf = fcmp oeq double %d, 0x7FF0000000000000\n");
        header.append("  %ninf = fcmp oeq double %d, 0xFFF0000000000000\n");
        header.append("  %isinf = or i1 %pinf, %ninf\n");
        header.append("  br i1 %isinf, label %jsonnull, label %init\n");
        header.append("jsonnull:\n");
        header.append("  ret i8* @.str.null.p\n");
        header.append("init:\n");
        header.append("  %prec = alloca i32\n  store i32 15, i32* %prec\n");
        header.append("  br label %loop\n");
        header.append("loop:\n");
        header.append("  %p = load i32, i32* %prec\n");
        header.append("  %r = call i32 (i8*, i8*, ...) @sprintf(i8* %buf, i8* @.str.dtoa.fmt, i32 %p, double %d)\n");
        header.append("  %rt = call double @strtod(i8* %buf, i8** null)\n");
        header.append("  %eq = fcmp oeq double %rt, %d\n");
        header.append("  br i1 %eq, label %done, label %next\n");
        header.append("next:\n");
        header.append("  %p2 = add i32 %p, 1\n");
        header.append("  store i32 %p2, i32* %prec\n");
        header.append("  %more = icmp sle i32 %p2, 17\n");
        header.append("  br i1 %more, label %loop, label %done\n");
        header.append("done:\n");
        header.append("  ret i8* %buf\n");
        header.append("}\n\n");
    }

    /**
     * String.replace runtime: literal full replacement — count matches with strstr,
     * size the buffer (len + n*(lenTo - lenFrom)), then copy chunks. Inserted text is
     * never rescanned (a `to` containing `from` cannot loop forever).
     */
    private void emitStringReplaceHelper() {
        if (header.toString().contains("define i8* @cang.str.replace(")) return;
        String alloc = allocFn();
        if (!header.toString().contains("declare i8* @strstr(")) {
            header.append("declare i8* @strstr(i8*, i8*)\n");
        }
        header.append("define i8* @cang.str.replace(i8* %s, i8* %from, i8* %to) {\n");
        header.append("entry:\n");
        header.append("  %flen = call i64 @strlen(i8* %from)\n");
        header.append("  %femp = icmp eq i64 %flen, 0\n");
        header.append("  br i1 %femp, label %retorig, label %countinit\n");
        header.append("retorig:\n");
        header.append("  ret i8* %s\n");
        header.append("countinit:\n");
        header.append("  %n = alloca i64\n  store i64 0, i64* %n\n");
        header.append("  %scan = alloca i8*\n  store i8* %s, i8** %scan\n");
        header.append("  %slen = call i64 @strlen(i8* %s)\n");
        header.append("  %tlen = call i64 @strlen(i8* %to)\n");
        header.append("  br label %cloop\n");
        header.append("cloop:\n");
        header.append("  %sc = load i8*, i8** %scan\n");
        header.append("  %hit = call i8* @strstr(i8* %sc, i8* %from)\n");
        header.append("  %has = icmp ne i8* %hit, null\n");
        header.append("  br i1 %has, label %cinc, label %cdone\n");
        header.append("cinc:\n");
        header.append("  %nv = load i64, i64* %n\n");
        header.append("  %n2 = add i64 %nv, 1\n");
        header.append("  store i64 %n2, i64* %n\n");
        header.append("  %adv = getelementptr i8, i8* %hit, i64 %flen\n");
        header.append("  store i8* %adv, i8** %scan\n");
        header.append("  br label %cloop\n");
        header.append("cdone:\n");
        // newlen = slen + n*tlen - n*flen
        header.append("  %nv2 = load i64, i64* %n\n");
        header.append("  %nt = mul i64 %nv2, %tlen\n");
        header.append("  %nf = mul i64 %nv2, %flen\n");
        header.append("  %grown = add i64 %slen, %nt\n");
        header.append("  %newlen = sub i64 %grown, %nf\n");
        header.append("  %sz = add i64 %newlen, 1\n");
        header.append("  %buf = call i8* @").append(alloc).append("(i64 %sz)\n");
        header.append("  %out = alloca i64\n  store i64 0, i64* %out\n");
        header.append("  %wscan = alloca i8*\n  store i8* %s, i8** %wscan\n");
        header.append("  br label %wloop\n");
        header.append("wloop:\n");
        header.append("  %ws = load i8*, i8** %wscan\n");
        header.append("  %whit = call i8* @strstr(i8* %ws, i8* %from)\n");
        header.append("  %whas = icmp ne i8* %whit, null\n");
        header.append("  br i1 %whas, label %wchunk, label %wtail\n");
        header.append("wchunk:\n");
        header.append("  %wo = load i64, i64* %out\n");
        header.append("  %wdst = getelementptr i8, i8* %buf, i64 %wo\n");
        header.append("  %ws2 = load i8*, i8** %wscan\n");
        header.append("  %ws2p = ptrtoint i8* %ws2 to i64\n");
        header.append("  %whitp = ptrtoint i8* %whit to i64\n");
        header.append("  %clen = sub i64 %whitp, %ws2p\n");
        header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* %wdst, i8* %ws2, i64 %clen, i1 false)\n");
        header.append("  %wo2 = add i64 %wo, %clen\n");
        header.append("  store i64 %wo2, i64* %out\n");
        header.append("  %tdst = getelementptr i8, i8* %buf, i64 %wo2\n");
        header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* %tdst, i8* %to, i64 %tlen, i1 false)\n");
        header.append("  %wo3 = add i64 %wo2, %tlen\n");
        header.append("  store i64 %wo3, i64* %out\n");
        header.append("  %wnext = getelementptr i8, i8* %whit, i64 %flen\n");
        header.append("  store i8* %wnext, i8** %wscan\n");
        header.append("  br label %wloop\n");
        header.append("wtail:\n");
        header.append("  %wo4 = load i64, i64* %out\n");
        header.append("  %tdst2 = getelementptr i8, i8* %buf, i64 %wo4\n");
        header.append("  %ws3 = load i8*, i8** %wscan\n");
        header.append("  %tail = sub i64 %newlen, %wo4\n");
        // +1: copy the source's NUL terminator with the last chunk.
        header.append("  %tail1 = add i64 %tail, 1\n");
        header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* %tdst2, i8* %ws3, i64 %tail1, i1 false)\n");
        header.append("  ret i8* %buf\n");
        header.append("}\n\n");
    }

    /**
     * String.split runtime: literal separator, Java semantics — trailing empty segments
     * are dropped ("" → empty array; "a,b," → ["a","b"]; middle empties kept).
     * Returns the standard array ABI: [i64 count][i8* elements...].
     */
    private void emitStringSplitHelper() {
        if (header.toString().contains("define i8* @cang.str.split(")) return;
        String alloc = allocFn();
        if (!header.toString().contains("declare i32 @strncmp(")) {
            header.append("declare i32 @strncmp(i8*, i8*, i64)\n");
        }
        if (!header.toString().contains("declare i8* @strstr(")) {
            header.append("declare i8* @strstr(i8*, i8*)\n");
        }
        // strndup lives in the File runtime, which only emits when File is used.
        if (!header.toString().contains("define i8* @cang.f.strndup(")) {
            header.append("define i8* @cang.f.strndup(i8* %s, i64 %n) {\n");
            header.append("entry:\n");
            header.append("  %n1 = add i64 %n, 1\n");
            header.append("  %buf = call i8* @").append(alloc).append("(i64 %n1)\n");
            header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* %buf, i8* %s, i64 %n, i1 false)\n");
            header.append("  %z = getelementptr i8, i8* %buf, i64 %n\n");
            header.append("  store i8 0, i8* %z\n");
            header.append("  ret i8* %buf\n");
            header.append("}\n\n");
        }
        header.append("define i8* @cang.str.split(i8* %s, i8* %sep) {\n");
        header.append("entry:\n");
        header.append("  %snull = icmp eq i8* %s, null\n");
        header.append("  br i1 %snull, label %empty, label %init\n");
        header.append("init:\n");
        header.append("  %seplen = call i64 @strlen(i8* %sep)\n");
        header.append("  %sep0 = icmp eq i64 %seplen, 0\n");
        header.append("  br i1 %sep0, label %empty, label %start1\n");
        header.append("empty:\n");
        header.append("  %ea = call i8* @").append(alloc).append("(i64 8)\n");
        header.append("  store i64 0, i64* %ea\n");
        header.append("  ret i8* %ea\n");
        header.append("start1:\n");
        // Pass 1: count segments, tracking the last NON-empty segment (Java trailing-drop).
        header.append("  %len = call i64 @strlen(i8* %s)\n");
        header.append("  %bound = sub i64 %len, %seplen\n");
        header.append("  %segStart = alloca i64\n  store i64 0, i64* %segStart\n");
        header.append("  %segIdx = alloca i64\n  store i64 0, i64* %segIdx\n");
        header.append("  %last = alloca i64\n  store i64 -1, i64* %last\n");
        header.append("  %i = alloca i64\n  store i64 0, i64* %i\n");
        header.append("  br label %c1loop\n");
        header.append("c1loop:\n");
        header.append("  %iv = load i64, i64* %i\n");
        header.append("  %go = icmp sle i64 %iv, %bound\n");
        header.append("  br i1 %go, label %c1body, label %c1done\n");
        header.append("c1body:\n");
        header.append("  %p = getelementptr i8, i8* %s, i64 %iv\n");
        header.append("  %m = call i32 @strncmp(i8* %p, i8* %sep, i64 %seplen)\n");
        header.append("  %is = icmp eq i32 %m, 0\n");
        header.append("  br i1 %is, label %c1match, label %c1next\n");
        header.append("c1match:\n");
        header.append("  %sv = load i64, i64* %segStart\n");
        header.append("  %emptyseg = icmp eq i64 %iv, %sv\n");
        header.append("  br i1 %emptyseg, label %c1skip, label %c1content\n");
        header.append("c1content:\n");
        header.append("  %ix = load i64, i64* %segIdx\n");
        header.append("  store i64 %ix, i64* %last\n");
        header.append("  br label %c1skip\n");
        header.append("c1skip:\n");
        header.append("  %ix2 = load i64, i64* %segIdx\n");
        header.append("  %ix3 = add i64 %ix2, 1\n");
        header.append("  store i64 %ix3, i64* %segIdx\n");
        header.append("  %ns = add i64 %iv, %seplen\n");
        header.append("  store i64 %ns, i64* %segStart\n");
        header.append("  store i64 %ns, i64* %i\n");
        header.append("  br label %c1loop\n");
        header.append("c1next:\n");
        header.append("  %i2 = add i64 %iv, 1\n");
        header.append("  store i64 %i2, i64* %i\n");
        header.append("  br label %c1loop\n");
        header.append("c1done:\n");
        // Tail segment: non-empty when segStart < len.
        header.append("  %tsv = load i64, i64* %segStart\n");
        header.append("  %thas = icmp ult i64 %tsv, %len\n");
        header.append("  br i1 %thas, label %c1tail, label %mkcount\n");
        header.append("c1tail:\n");
        header.append("  %tix = load i64, i64* %segIdx\n");
        header.append("  store i64 %tix, i64* %last\n");
        header.append("  br label %mkcount\n");
        header.append("mkcount:\n");
        // n = last + 1, or 0 when nothing had content.
        header.append("  %lv = load i64, i64* %last\n");
        header.append("  %lneg = icmp slt i64 %lv, 0\n");
        header.append("  %lp1 = add i64 %lv, 1\n");
        header.append("  %cnt = select i1 %lneg, i64 0, i64 %lp1\n");
        header.append("  br label %mkarr\n");
        header.append("mkarr:\n");
        header.append("  %dsz = shl i64 %cnt, 3\n");
        header.append("  %tsz = add i64 %dsz, 8\n");
        header.append("  %arr = call i8* @").append(alloc).append("(i64 %tsz)\n");
        header.append("  store i64 %cnt, i64* %arr\n");
        header.append("  %data = getelementptr i8, i8* %arr, i64 8\n");
        header.append("  %typed = bitcast i8* %data to i8**\n");
        header.append("  %slot = alloca i64\n  store i64 0, i64* %slot\n");
        header.append("  %segStart2 = alloca i64\n  store i64 0, i64* %segStart2\n");
        header.append("  %segIdx2 = alloca i64\n  store i64 0, i64* %segIdx2\n");
        header.append("  %i2p = alloca i64\n  store i64 0, i64* %i2p\n");
        header.append("  br label %c2loop\n");
        header.append("c2loop:\n");
        header.append("  %iv2 = load i64, i64* %i2p\n");
        header.append("  %go2 = icmp sle i64 %iv2, %bound\n");
        header.append("  br i1 %go2, label %c2body, label %c2done\n");
        header.append("c2body:\n");
        header.append("  %p2 = getelementptr i8, i8* %s, i64 %iv2\n");
        header.append("  %m2 = call i32 @strncmp(i8* %p2, i8* %sep, i64 %seplen)\n");
        header.append("  %is2 = icmp eq i32 %m2, 0\n");
        header.append("  br i1 %is2, label %c2match, label %c2next\n");
        header.append("c2match:\n");
        header.append("  %ix4 = load i64, i64* %segIdx2\n");
        header.append("  %lt = icmp slt i64 %ix4, %cnt\n");
        header.append("  br i1 %lt, label %c2store, label %c2skip\n");
        header.append("c2store:\n");
        header.append("  %sv2 = load i64, i64* %segStart2\n");
        header.append("  %slen2 = sub i64 %iv2, %sv2\n");
        header.append("  %src2 = getelementptr i8, i8* %s, i64 %sv2\n");
        header.append("  %seg = call i8* @cang.f.strndup(i8* %src2, i64 %slen2)\n");
        header.append("  %sl2 = load i64, i64* %slot\n");
        header.append("  %ep2 = getelementptr i8*, i8** %typed, i64 %sl2\n");
        header.append("  store i8* %seg, i8** %ep2\n");
        header.append("  %sl3 = add i64 %sl2, 1\n");
        header.append("  store i64 %sl3, i64* %slot\n");
        header.append("  br label %c2skip\n");
        header.append("c2skip:\n");
        header.append("  %ix5 = load i64, i64* %segIdx2\n");
        header.append("  %ix6 = add i64 %ix5, 1\n");
        header.append("  store i64 %ix6, i64* %segIdx2\n");
        header.append("  %ns2 = add i64 %iv2, %seplen\n");
        header.append("  store i64 %ns2, i64* %segStart2\n");
        header.append("  store i64 %ns2, i64* %i2p\n");
        header.append("  br label %c2loop\n");
        header.append("c2next:\n");
        header.append("  %i3 = add i64 %iv2, 1\n");
        header.append("  store i64 %i3, i64* %i2p\n");
        header.append("  br label %c2loop\n");
        header.append("c2done:\n");
        // Tail segment (kept only when its index < cnt, i.e. it had content).
        header.append("  %ix7 = load i64, i64* %segIdx2\n");
        header.append("  %lt2 = icmp slt i64 %ix7, %cnt\n");
        header.append("  br i1 %lt2, label %c2tail, label %fin\n");
        header.append("c2tail:\n");
        header.append("  %tsv2 = load i64, i64* %segStart2\n");
        header.append("  %tlen2 = sub i64 %len, %tsv2\n");
        header.append("  %tsrc2 = getelementptr i8, i8* %s, i64 %tsv2\n");
        header.append("  %tseg = call i8* @cang.f.strndup(i8* %tsrc2, i64 %tlen2)\n");
        header.append("  %sl4 = load i64, i64* %slot\n");
        header.append("  %ep3 = getelementptr i8*, i8** %typed, i64 %sl4\n");
        header.append("  store i8* %tseg, i8** %ep3\n");
        header.append("  %sl5 = add i64 %sl4, 1\n");
        header.append("  store i64 %sl5, i64* %slot\n");
        header.append("  br label %fin\n");
        header.append("fin:\n");
        header.append("  ret i8* %arr\n");
        header.append("}\n\n");
    }

    /** Trim ASCII whitespace both ends into a fresh heap buffer. */
    private String emitTrim(String s) {
        String len = "%tr.len." + tmpCount++;
        body.append("  ").append(len).append(" = call i64 @strlen(i8* ").append(s).append(")\n");
        String sp = "%tr.sp." + tmpCount++;
        body.append("  ").append(sp).append(" = alloca i64\n");
        body.append("  store i64 0, i64* ").append(sp).append("\n");
        String ep = "%tr.ep." + tmpCount++;
        body.append("  ").append(ep).append(" = alloca i64\n");
        body.append("  store i64 ").append(len).append(", i64* ").append(ep).append("\n");
        int id = labelCount++;
        String lc = "tr.lc." + id, lb = "tr.lb." + id, linc = "tr.linc." + id, ldone = "tr.ldone." + id;
        body.append("  br label %").append(lc).append("\n\n");
        body.append(lc).append(":\n");
        String sl = "%tr.sl." + tmpCount++;
        body.append("  ").append(sl).append(" = load i64, i64* ").append(sp).append("\n");
        String c1 = "%tr.c1." + tmpCount++;
        body.append("  ").append(c1).append(" = icmp slt i64 ").append(sl).append(", ").append(len).append("\n");
        body.append("  br i1 ").append(c1).append(", label %").append(lb).append(", label %").append(ldone).append("\n\n");
        body.append(lb).append(":\n");
        String lp = "%tr.lp." + tmpCount++;
        body.append("  ").append(lp).append(" = getelementptr i8, i8* ").append(s).append(", i64 ").append(sl).append("\n");
        String ch = "%tr.ch." + tmpCount++;
        body.append("  ").append(ch).append(" = load i8, i8* ").append(lp).append("\n");
        String w1 = emitIsSpace(ch);
        body.append("  br i1 ").append(w1).append(", label %").append(linc).append(", label %").append(ldone).append("\n\n");
        body.append(linc).append(":\n");
        String s2 = "%tr.s2." + tmpCount++;
        body.append("  ").append(s2).append(" = add i64 ").append(sl).append(", 1\n");
        body.append("  store i64 ").append(s2).append(", i64* ").append(sp).append("\n");
        body.append("  br label %").append(lc).append("\n\n");
        body.append(ldone).append(":\n");
        String ec = "tr.ec." + id, eb = "tr.eb." + id, edec = "tr.edec." + id, edone = "tr.edone." + id;
        body.append("  br label %").append(ec).append("\n\n");
        body.append(ec).append(":\n");
        String sl2 = "%tr.sl2." + tmpCount++;
        body.append("  ").append(sl2).append(" = load i64, i64* ").append(sp).append("\n");
        String el = "%tr.el." + tmpCount++;
        body.append("  ").append(el).append(" = load i64, i64* ").append(ep).append("\n");
        String c2 = "%tr.c2." + tmpCount++;
        body.append("  ").append(c2).append(" = icmp sgt i64 ").append(el).append(", ").append(sl2).append("\n");
        body.append("  br i1 ").append(c2).append(", label %").append(eb).append(", label %").append(edone).append("\n\n");
        body.append(eb).append(":\n");
        String ei = "%tr.ei." + tmpCount++;
        body.append("  ").append(ei).append(" = sub i64 ").append(el).append(", 1\n");
        String ep2 = "%tr.ep2." + tmpCount++;
        body.append("  ").append(ep2).append(" = getelementptr i8, i8* ").append(s).append(", i64 ").append(ei).append("\n");
        String ch2 = "%tr.ch2." + tmpCount++;
        body.append("  ").append(ch2).append(" = load i8, i8* ").append(ep2).append("\n");
        String w2 = emitIsSpace(ch2);
        body.append("  br i1 ").append(w2).append(", label %").append(edec).append(", label %").append(edone).append("\n\n");
        body.append(edec).append(":\n");
        String e2 = "%tr.e2." + tmpCount++;
        body.append("  ").append(e2).append(" = sub i64 ").append(el).append(", 1\n");
        body.append("  store i64 ").append(e2).append(", i64* ").append(ep).append("\n");
        body.append("  br label %").append(ec).append("\n\n");
        body.append(edone).append(":\n");
        String slf = "%tr.slf." + tmpCount++;
        body.append("  ").append(slf).append(" = load i64, i64* ").append(sp).append("\n");
        String elf = "%tr.elf." + tmpCount++;
        body.append("  ").append(elf).append(" = load i64, i64* ").append(ep).append("\n");
        String tl = "%tr.tl." + tmpCount++;
        body.append("  ").append(tl).append(" = sub i64 ").append(elf).append(", ").append(slf).append("\n");
        String tsz = "%tr.tsz." + tmpCount++;
        body.append("  ").append(tsz).append(" = add i64 ").append(tl).append(", 1\n");
        String buf = "%tr.buf." + tmpCount++;
        body.append("  ").append(buf).append(" = call i8* @").append(allocFn()).append("(i64 ").append(tsz).append(")\n");
        registerRegionAllocation(buf);
        String src = "%tr.src." + tmpCount++;
        body.append("  ").append(src).append(" = getelementptr i8, i8* ").append(s).append(", i64 ").append(slf).append("\n");
        body.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* ").append(buf)
             .append(", i8* ").append(src).append(", i64 ").append(tl).append(", i1 false)\n");
        String term = "%tr.term." + tmpCount++;
        body.append("  ").append(term).append(" = getelementptr i8, i8* ").append(buf).append(", i64 ").append(tl).append("\n");
        body.append("  store i8 0, i8* ").append(term).append("\n");
        return buf;
    }

    /** i1: byte is ASCII whitespace (space/tab/lf/cr). */
    private String emitIsSpace(String ch) {
        String a = "%ws.a." + tmpCount++;
        body.append("  ").append(a).append(" = icmp eq i8 ").append(ch).append(", 32\n");
        String b = "%ws.b." + tmpCount++;
        body.append("  ").append(b).append(" = icmp eq i8 ").append(ch).append(", 9\n");
        String c = "%ws.c." + tmpCount++;
        body.append("  ").append(c).append(" = icmp eq i8 ").append(ch).append(", 10\n");
        String d = "%ws.d." + tmpCount++;
        body.append("  ").append(d).append(" = icmp eq i8 ").append(ch).append(", 13\n");
        String ab = "%ws.ab." + tmpCount++;
        body.append("  ").append(ab).append(" = or i1 ").append(a).append(", ").append(b).append("\n");
        String cd = "%ws.cd." + tmpCount++;
        body.append("  ").append(cd).append(" = or i1 ").append(c).append(", ").append(d).append("\n");
        String r = "%ws.r." + tmpCount++;
        body.append("  ").append(r).append(" = or i1 ").append(ab).append(", ").append(cd).append("\n");
        return r;
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
            } else if (targetPlatform.equals("windows")) {
                // Console-safe string print: WriteConsoleW (UTF-8 -> UTF-16) when attached
                // to a console so rendering is correct under ANY chcp (936/65001);
                // redirected stdout/stderr keeps raw UTF-8 bytes like printf("%s").
                ensurePrintStrHelper();
                body.append("  call void @cang.f.printstr(i8* ").append(argVal.value)
                     .append(", i1 ").append(newline ? "true" : "false")
                     .append(", i1 ").append(toStderr ? "true" : "false").append(")\n");
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
     * Windows-only runtime helper: console-safe string printing.
     * GetConsoleMode decides the path: a real console gets MultiByteToWideChar(CP_UTF8)
     * + WriteConsoleW (renders correctly whatever the console codepage is — fixes the
     * UTF-8-bytes-read-as-GBK mojibake, e.g. 你好世界 printed as 浣犲ソ涓栫界);
     * a redirected pipe/file falls back to fwrite of the raw UTF-8 bytes (same bytes
     * printf would write). Newline: console writes CRLF directly, file relies on CRT
     * text-mode \n translation exactly as printf does today.
     */
    private void ensurePrintStrHelper() {
        if (header.toString().contains("define void @cang.f.printstr(")) return;
        if (!header.toString().contains("declare i8* @GetStdHandle(")) {
            header.append("declare i8* @GetStdHandle(i32)\n");
        }
        if (!header.toString().contains("declare i32 @GetConsoleMode(")) {
            header.append("declare i32 @GetConsoleMode(i8*, i32*)\n");
        }
        if (!header.toString().contains("declare i32 @WriteConsoleW(")) {
            header.append("declare i32 @WriteConsoleW(i8*, i8*, i32, i32*, i8*)\n");
        }
        if (!header.toString().contains("declare i32 @MultiByteToWideChar(")) {
            header.append("declare i32 @MultiByteToWideChar(i32, i32, i8*, i32, i8*, i32)\n");
        }
        if (!header.toString().contains("declare i64 @fwrite(")) {
            header.append("declare i64 @fwrite(i8*, i64, i64, i8*)\n");
        }
        if (!header.toString().contains("declare i32 @fputc(")) {
            header.append("declare i32 @fputc(i32, i8*)\n");
        }
        if (!header.toString().contains("declare i8* @__acrt_iob_func(")) {
            header.append("declare i8* @__acrt_iob_func(i32)\n");
        }
        if (!header.toString().contains("@.str.crlf16")) {
            // UTF-16LE CRLF: 0D 00 0A 00
            header.append("@.str.crlf16 = private constant [4 x i8] c\"\\0D\\00\\0A\\00\"\n");
        }
        header.append("define void @cang.f.printstr(i8* %s, i1 %nl, i1 %err) {\n");
        header.append("entry:\n");
        header.append("  %mode = alloca i32\n");
        header.append("  %len64 = call i64 @strlen(i8* %s)\n");
        header.append("  %len = trunc i64 %len64 to i32\n");
        header.append("  %hsel = select i1 %err, i32 -12, i32 -11\n"); // STD_ERROR/STD_OUTPUT_HANDLE
        header.append("  %handle = call i8* @GetStdHandle(i32 %hsel)\n");
        header.append("  %gm = call i32 @GetConsoleMode(i8* %handle, i32* %mode)\n");
        header.append("  %iscon = icmp ne i32 %gm, 0\n");
        header.append("  br i1 %iscon, label %console, label %file\n");
        header.append("console:\n");
        header.append("  %wn = call i32 @MultiByteToWideChar(i32 65001, i32 0, i8* %s, i32 %len, i8* null, i32 0)\n");
        header.append("  %wn1 = add i32 %wn, 1\n");
        header.append("  %wn64 = zext i32 %wn1 to i64\n");
        header.append("  %bufsz = mul i64 %wn64, 2\n");
        header.append("  %buf = call i8* @malloc(i64 %bufsz)\n");
        header.append("  %cv = call i32 @MultiByteToWideChar(i32 65001, i32 0, i8* %s, i32 %len, i8* %buf, i32 %wn)\n");
        header.append("  %wc = call i32 @WriteConsoleW(i8* %handle, i8* %buf, i32 %wn, i32* %mode, i8* null)\n");
        header.append("  call void @free(i8* %buf)\n");
        header.append("  br i1 %nl, label %cnl, label %done\n");
        header.append("cnl:\n");
        header.append("  %cr = getelementptr [4 x i8], [4 x i8]* @.str.crlf16, i32 0, i32 0\n");
        header.append("  %wc2 = call i32 @WriteConsoleW(i8* %handle, i8* %cr, i32 2, i32* %mode, i8* null)\n");
        header.append("  br label %done\n");
        header.append("file:\n");
        header.append("  %fo = call i8* @__acrt_iob_func(i32 1)\n");
        header.append("  %fe = call i8* @__acrt_iob_func(i32 2)\n");
        header.append("  %stream = select i1 %err, i8* %fe, i8* %fo\n");
        header.append("  %fw = call i64 @fwrite(i8* %s, i64 1, i64 %len64, i8* %stream)\n");
        header.append("  br i1 %nl, label %fnl, label %done\n");
        header.append("fnl:\n");
        header.append("  %pc = call i32 @fputc(i32 10, i8* %stream)\n");
        header.append("  br label %done\n");
        header.append("done:\n");
        header.append("  ret void\n");
        header.append("}\n\n");
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
            int byteCount = utf8Len(value) + 1;
            String escaped = escapeBytes(value);
            header.append(name).append(" = private unnamed_addr constant [").append(byteCount)
                  .append(" x i8] c\"").append(escaped).append("\\00\"\n");
            stringLiterals.put(key, name);
        }
        String globalName = stringLiterals.get(key);
        String ptr = "%str." + tmpCount++;
        body.append("  ").append(ptr).append(" = getelementptr [")
            .append(utf8Len(value) + 1).append(" x i8], [")
            .append(utf8Len(value) + 1).append(" x i8]* ")
            .append(globalName).append(", i32 0, i32 0\n");
        return new LLVMValue(ptr, "i8*");
    }

    /** Class full name of a class-object pointer, via @cang.classnames[typeId] (getClassName/toString). */
    private LLVMValue emitClassNameOf(LLVMValue receiver) {
        emitClassNameTable();
        int size = classNameTableSize();
        String[] bv = classPtrBaseAndValue(receiver);
        String base = bv[0];
        String recv = bv[1];
        String tidPtr = "%objcn.tidp." + tmpCount++;
        body.append("  ").append(tidPtr).append(" = getelementptr %").append(base).append(", %").append(base)
             .append("* ").append(recv).append(", i32 0, i32 0\n");
        String tid = "%objcn.tid." + tmpCount++;
        body.append("  ").append(tid).append(" = load i32, i32* ").append(tidPtr).append("\n");
        // Bounds-guarded lookup: values that are not real objects (e.g. a String pointer
        // reached through an Object-typed variable) have garbage typeIds — index clamping
        // maps them to a null name instead of an out-of-bounds load.
        String inRange = "%objcn.inrange." + tmpCount++;
        body.append("  ").append(inRange).append(" = icmp ult i32 ").append(tid).append(", ")
             .append(size).append("\n");
        String safeTid = "%objcn.safetid." + tmpCount++;
        body.append("  ").append(safeTid).append(" = select i1 ").append(inRange).append(", i32 ")
             .append(tid).append(", i32 0\n");
        String idx = "%objcn.idx." + tmpCount++;
        body.append("  ").append(idx).append(" = zext i32 ").append(safeTid).append(" to i64\n");
        String namePtr = "%objcn.np." + tmpCount++;
        body.append("  ").append(namePtr).append(" = getelementptr [").append(size).append(" x i8*], [")
             .append(size).append(" x i8*]* @cang.classnames, i64 0, i64 ").append(idx).append("\n");
        String nameRaw = "%objcn.nraw." + tmpCount++;
        body.append("  ").append(nameRaw).append(" = load i8*, i8** ").append(namePtr).append("\n");
        String name = "%objcn.n." + tmpCount++;
        body.append("  ").append(name).append(" = select i1 ").append(inRange).append(", i8* ")
             .append(nameRaw).append(", i8* null\n");
        return new LLVMValue(name, "i8*", "String");
    }

    /** Class-object pointer → { base struct name, %X* register } (bitcast from %X** if needed). */
    private String[] classPtrBaseAndValue(LLVMValue r) {
        String base = extractClassName(r.type);
        if (r.type.equals("%" + base + "*")) return new String[]{ base, r.value };
        String bc = "%objptr.bc." + tmpCount++;
        body.append("  ").append(bc).append(" = bitcast ").append(r.type).append(" ").append(r.value)
             .append(" to %").append(base).append("*\n");
        return new String[]{ base, bc };
    }

    /** toString runtime formatter: sprintf("%s@%llx") into a heap buffer — ClassName@addr. */
    private void emitObjectToStringHelper() {
        if (header.toString().contains("define i8* @cang.f.objtostr(")) return;
        if (!header.toString().contains("declare i64 @strlen(")) {
            header.append("declare i64 @strlen(i8*)\n");
        }
        if (!header.toString().contains("declare i32 @sprintf(")) {
            header.append("declare i32 @sprintf(i8*, i8*, ...)\n");
        }
        if (!header.toString().contains("@.str.objtostr = ")) {
            header.append("@.str.objtostr = private constant [8 x i8] c\"%s@%llx\\00\"\n");
        }
        header.append("define i8* @cang.f.objtostr(i8* %cls, i8* %obj) {\n");
        header.append("entry:\n");
        header.append("  %len = call i64 @strlen(i8* %cls)\n");
        // '@' + 16 hex digits + NUL, padded.
        header.append("  %sz = add i64 %len, 24\n");
        if (gcEnabled) {
            header.append("  %buf = call i8* @GC_malloc(i64 %sz)\n");
        } else {
            header.append("  %buf = call i8* @malloc(i64 %sz)\n");
        }
        header.append("  %addr = ptrtoint i8* %obj to i64\n");
        header.append("  %n = call i32 (i8*, i8*, ...) @sprintf(i8* %buf, i8* @.str.objtostr, i8* %cls, i64 %addr)\n");
        header.append("  ret i8* %buf\n");
        header.append("}\n\n");
    }

    /** Table size (typeId 0..max) for @cang.classnames. */
    private int classNameTableSize() {
        int maxId = 0;
        for (ClassInfo ci : classes.values()) maxId = Math.max(maxId, ci.typeId);
        return maxId + 1;
    }

    /** Global i8* table indexed by runtime typeId → class full name constant. Built lazily. */
    private void emitClassNameTable() {
        if (header.toString().contains("@cang.classnames = ")) return;
        int size = classNameTableSize();
        String[] names = new String[size];
        for (ClassInfo ci : classes.values()) names[ci.typeId] = ci.fullName;
        StringBuilder inits = new StringBuilder();
        for (int i = 0; i < size; i++) {
            String nm = names[i];
            String constName = ensureClassNameStrConst(nm);
            int len = utf8Len(nm) + 1;
            if (i > 0) inits.append(", ");
            inits.append("i8* getelementptr inbounds ([").append(len).append(" x i8], [")
                 .append(len).append(" x i8]* ").append(constName).append(", i32 0, i32 0)");
        }
        header.append("@cang.classnames = internal global [").append(size)
              .append(" x i8*] [").append(inits).append("]\n");
    }

    /** Register a string constant by VALUE (header-side, no body gep); returns its @.str name. */
    private String ensureClassNameStrConst(String value) {
        if (!stringLiterals.containsKey(value)) {
            String name = "@.str.clsname." + stringLiterals.size();
            int byteCount = utf8Len(value) + 1;
            header.append(name).append(" = private unnamed_addr constant [").append(byteCount)
                  .append(" x i8] c\"").append(escapeBytes(value)).append("\\00\"\n");
            stringLiterals.put(value, name);
        }
        return stringLiterals.get(value);
    }

    /** True for class-literal spellings that have no ClassInfo (String / primitives / void). */
    private boolean isSpecialClassLiteral(String name) {
        for (String s : SPECIAL_CLASS_LITERAL_NAMES) if (s.equals(name)) return true;
        return false;
    }

    private String specialClassObjectGlobal(String name) {
        return "@cang.classobj.special." + name;
    }

    /** cang.lang.Class ClassInfo, or null when the program never references Class/.class. */
    private ClassInfo classInfoOfClass() {
        ClassInfo ci = classes.get("cang_lang_Class");
        if (ci == null) ci = classes.get("Class");
        return ci;
    }

    /** Runtime ClassInfo of a class's parent, or null (Object / native synthetic roots). */
    private ClassInfo resolveParentInfo(ClassInfo ci) {
        if (ci == null || ci.parentName == null) return null;
        ClassInfo parent = classes.get(ci.parentName);
        if (parent == null) {
            String mapped = importAliases.getOrDefault(ci.parentName, ci.parentName);
            parent = classes.get(mapped);
        }
        return parent;
    }

    /**
     * Class literals: one global Class object per runtime type, created once at startup.
     * The table is indexed by the existing compiler typeId, so `Apple.class` is just a load
     * and `Apple.class == Apple.class` is reference identity. String/primitives get their own
     * globals (they have no ClassInfo). The initializer runs from @main after GC_init().
     */
    private void emitClassObjects() {
        ClassInfo cls = classInfoOfClass();
        if (cls == null || classObjectInitEmitted) return;
        classObjectInitEmitted = true;
        String clsPtr = cls.llvmName + "*";
        int size = classNameTableSize();

        // Unique real classes by typeId (alias keys point at the same ClassInfo).
        java.util.TreeMap<Integer, ClassInfo> byId = new java.util.TreeMap<>();
        for (ClassInfo ci : classes.values()) byId.put(ci.typeId, ci);

        // Reserve every string constant BEFORE writing the function body:
        // ensureClassNameStrConst appends globals to `header`, and globals must not be
        // interleaved into a function definition.
        Map<Integer, String> nameConst = new java.util.HashMap<>();
        Map<Integer, String> simpleConst = new java.util.HashMap<>();
        for (Map.Entry<Integer, ClassInfo> e : byId.entrySet()) {
            nameConst.put(e.getKey(), ensureClassNameStrConst(e.getValue().fullName));
            simpleConst.put(e.getKey(), ensureClassNameStrConst(e.getValue().simpleName));
        }
        Map<String, String> specialConst = new java.util.HashMap<>();
        for (String n : SPECIAL_CLASS_LITERAL_NAMES) {
            specialConst.put(n, ensureClassNameStrConst(n));
        }

        header.append("@cang.classobjs = internal global [").append(size).append(" x ")
              .append(clsPtr).append("] zeroinitializer\n");
        for (String n : SPECIAL_CLASS_LITERAL_NAMES) {
            header.append(specialClassObjectGlobal(n)).append(" = internal global ")
                  .append(clsPtr).append(" null\n");
        }

        StringBuilder f = new StringBuilder();
        f.append("define void @cang.classinit() {\nentry:\n");

        // Pass 1: allocate every Class object and fill the name fields.
        Map<Integer, String> objVar = new java.util.HashMap<>();
        for (Map.Entry<Integer, ClassInfo> e : byId.entrySet()) {
            ClassInfo ci = e.getValue();
            String p = "c" + e.getKey();
            String obj = emitClassObjectAlloc(f, p, cls, clsPtr);
            objVar.put(e.getKey(), obj);
            emitClassObjectFieldDefaults(f, p, obj, cls, clsPtr);
            emitClassObjectNameStore(f, p + ".name", obj, cls, clsPtr, "_name",
                nameConst.get(e.getKey()), ci.fullName);
            emitClassObjectNameStore(f, p + ".simple", obj, cls, clsPtr, "_simpleName",
                simpleConst.get(e.getKey()), ci.simpleName);
            emitClassObjectIdStore(f, p + ".tid", obj, cls, clsPtr, "_typeId", ci.typeId);
            String slot = "%cls.slot." + p;
            f.append("  ").append(slot).append(" = getelementptr [").append(size).append(" x ")
             .append(clsPtr).append("], [").append(size).append(" x ").append(clsPtr)
             .append("]* @cang.classobjs, i64 0, i64 ").append(e.getKey()).append("\n");
            f.append("  store ").append(clsPtr).append(" ").append(obj)
             .append(", ").append(clsPtr).append("* ").append(slot).append("\n");
        }

        // String.class / int.class / ...: Class objects without a ClassInfo.
        // Their _typeId stays -1 (emitClassObjectFieldDefaults wrote 0 for int, fix to -1)
        // so Class.newInstance() rejects them at runtime.
        Map<String, String> specialObj = new java.util.HashMap<>();
        for (String n : SPECIAL_CLASS_LITERAL_NAMES) {
            String p = "special." + n;
            String obj = emitClassObjectAlloc(f, p, cls, clsPtr);
            specialObj.put(n, obj);
            emitClassObjectFieldDefaults(f, p, obj, cls, clsPtr);
            emitClassObjectNameStore(f, p + ".name", obj, cls, clsPtr, "_name",
                specialConst.get(n), n);
            emitClassObjectNameStore(f, p + ".simple", obj, cls, clsPtr, "_simpleName",
                specialConst.get(n), n);
            emitClassObjectIdStore(f, p + ".tid", obj, cls, clsPtr, "_typeId", -1);
            f.append("  store ").append(clsPtr).append(" ").append(obj).append(", ")
             .append(clsPtr).append("* ").append(specialClassObjectGlobal(n)).append("\n");
        }

        // Pass 2: link superClass now that every pointer exists.
        Integer superIdx = cls.fieldIndices.get("_superClass");
        if (superIdx == null) {
            throw new RuntimeException("cang/lang/Class must declare a 'Class _superClass' field");
        }
        for (Map.Entry<Integer, ClassInfo> e : byId.entrySet()) {
            ClassInfo parent = resolveParentInfo(e.getValue());
            if (parent == null) continue;
            String p = "super." + e.getKey();
            String slot = "%cls.pslot." + p;
            f.append("  ").append(slot).append(" = getelementptr [").append(size).append(" x ")
             .append(clsPtr).append("], [").append(size).append(" x ").append(clsPtr)
             .append("]* @cang.classobjs, i64 0, i64 ").append(parent.typeId).append("\n");
            String pv = "%cls.pval." + p;
            f.append("  ").append(pv).append(" = load ").append(clsPtr).append(", ")
             .append(clsPtr).append("* ").append(slot).append("\n");
            String fp = "%cls.sfield." + p;
            f.append("  ").append(fp).append(" = getelementptr ").append(cls.llvmName)
             .append(", ").append(clsPtr).append(" ").append(objVar.get(e.getKey()))
             .append(", i32 0, i32 ").append(superIdx).append("\n");
            f.append("  store ").append(clsPtr).append(" ").append(pv).append(", ")
             .append(clsPtr).append("* ").append(fp).append("\n");
        }
        // String is a normal reference class in Cang: String.class extends Object.class.
        String objectSlot = "%cls.pslot.String";
        f.append("  ").append(objectSlot).append(" = getelementptr [").append(size).append(" x ")
         .append(clsPtr).append("], [").append(size).append(" x ").append(clsPtr)
         .append("]* @cang.classobjs, i64 0, i64 0\n");
        String objectVal = "%cls.pval.String";
        f.append("  ").append(objectVal).append(" = load ").append(clsPtr).append(", ")
         .append(clsPtr).append("* ").append(objectSlot).append("\n");
        String stringField = "%cls.sfield.special.String";
        f.append("  ").append(stringField).append(" = getelementptr ").append(cls.llvmName)
         .append(", ").append(clsPtr).append(" ").append(specialObj.get("String"))
         .append(", i32 0, i32 ").append(superIdx).append("\n");
        f.append("  store ").append(clsPtr).append(" ").append(objectVal).append(", ")
         .append(clsPtr).append("* ").append(stringField).append("\n");

        f.append("  ret void\n}\n\n");
        header.append(f);
    }

    private String emitClassObjectAlloc(StringBuilder f, String suffix, ClassInfo cls, String clsPtr) {
        String sizePtr = "%cls.size." + suffix;
        f.append("  ").append(sizePtr).append(" = getelementptr ").append(cls.llvmName)
         .append(", ").append(clsPtr).append(" null, i32 1\n");
        String sizeI = "%cls.sizei." + suffix;
        f.append("  ").append(sizeI).append(" = ptrtoint ").append(clsPtr).append(" ")
         .append(sizePtr).append(" to i64\n");
        String raw = "%cls.raw." + suffix;
        f.append("  ").append(raw).append(" = call i8* @").append(allocFn())
         .append("(i64 ").append(sizeI).append(")\n");
        String obj = "%cls.obj." + suffix;
        f.append("  ").append(obj).append(" = bitcast i8* ").append(raw).append(" to ")
         .append(clsPtr).append("\n");
        String tidp = "%cls.tid." + suffix;
        f.append("  ").append(tidp).append(" = getelementptr ").append(cls.llvmName)
         .append(", ").append(clsPtr).append(" ").append(obj).append(", i32 0, i32 0\n");
        f.append("  store i32 ").append(cls.typeId).append(", i32* ").append(tidp).append("\n");
        return obj;
    }

    private void emitClassObjectFieldDefaults(StringBuilder f, String suffix, String obj,
                                              ClassInfo cls, String clsPtr) {
        for (int i = 0; i < cls.fieldTypes.size(); i++) {
            String fieldType = cls.fieldTypes.get(i);
            String llvmType = toLLVMType(fieldType);
            String ptr = "%cls.f." + suffix + "." + i;
            f.append("  ").append(ptr).append(" = getelementptr ").append(cls.llvmName)
             .append(", ").append(clsPtr).append(" ").append(obj)
             .append(", i32 0, i32 ").append(i + 1).append("\n");
            f.append("  store ").append(llvmType).append(" ")
             .append(defaultValueForType(fieldType)).append(", ").append(llvmType)
             .append("* ").append(ptr).append("\n");
        }
    }

    private void emitClassObjectNameStore(StringBuilder f, String suffix, String obj,
                                          ClassInfo cls, String clsPtr, String fieldName,
                                          String constant, String value) {
        Integer idx = cls.fieldIndices.get(fieldName);
        if (idx == null) {
            throw new RuntimeException("cang/lang/Class must declare a 'String " + fieldName + "' field");
        }
        String ptr = "%cls.nameptr." + suffix;
        f.append("  ").append(ptr).append(" = getelementptr ").append(cls.llvmName)
         .append(", ").append(clsPtr).append(" ").append(obj)
         .append(", i32 0, i32 ").append(idx).append("\n");
        String sp = "%cls.namev." + suffix;
        int len = utf8Len(value) + 1;
        f.append("  ").append(sp).append(" = getelementptr inbounds [").append(len)
         .append(" x i8], [").append(len).append(" x i8]* ").append(constant)
         .append(", i32 0, i32 0\n");
        f.append("  store i8* ").append(sp).append(", i8** ").append(ptr).append("\n");
    }

    /** Store an int constant into a named field of a compiler-built Class object (e.g. _typeId). */
    private void emitClassObjectIdStore(StringBuilder f, String suffix, String obj,
                                        ClassInfo cls, String clsPtr, String fieldName, int value) {
        Integer idx = cls.fieldIndices.get(fieldName);
        if (idx == null) {
            throw new RuntimeException("cang/lang/Class must declare an 'int " + fieldName + "' field");
        }
        String ptr = "%cls.idptr." + suffix;
        f.append("  ").append(ptr).append(" = getelementptr ").append(cls.llvmName)
         .append(", ").append(clsPtr).append(" ").append(obj)
         .append(", i32 0, i32 ").append(idx).append("\n");
        f.append("  store i32 ").append(value).append(", i32* ").append(ptr).append("\n");
    }

    /**
     * Public constructors of ci that Class.newInstance can use, most-specific parameter
     * lists first. Abstract/private classes, native constructors, private init signatures
     * and parameter kinds that cannot be carried through an Object value are omitted.
     */
    private List<FuncInfo> findNewInstanceCtors(ClassInfo ci) {
        List<FuncInfo> result = new ArrayList<>();
        if (ci == null || ci.isAbstract || ci.isPrivateCtor) return result;
        String ctorName = ci.fullName + ".constructor";
        List<FuncInfo> candidates = new ArrayList<>();
        List<FuncInfo> grp = overloadGroups.get(ctorName);
        if (grp != null) candidates.addAll(grp);
        else {
            FuncInfo fi = functions.get(ctorName);
            if (fi != null) candidates.add(fi);
        }
        for (FuncInfo fi : candidates) {
            if (fi.isNative) continue;
            if (ci.privateInitSigs.contains(String.join(",", fi.paramTypes))) continue;
            if (!isNewInstanceCtorSupported(fi)) continue;
            result.add(fi);
        }
        // Prefer concrete parameter kinds over Object when several constructors share an arity.
        result.sort((a, b) -> Integer.compare(
            newInstanceCtorPriority(b), newInstanceCtorPriority(a)));
        return result;
    }

    /** True when every constructor parameter can be decoded from an Object/Object[] arg. */
    private boolean isNewInstanceCtorSupported(FuncInfo fi) {
        for (String p : fi.paramTypes) {
            if (isFieldPrimType(p) || "String".equals(p) || isObjectParam(p) || isArrayParam(p)) {
                continue;
            }
            ClassInfo pci = classes.get(p);
            if (pci == null) pci = classes.get(importAliases.getOrDefault(p, p));
            if (pci == null || pci.typeId < 0) return false;
        }
        return true;
    }

    private int newInstanceCtorPriority(FuncInfo fi) {
        int score = 0;
        for (String p : fi.paramTypes) {
            if (isFieldPrimType(p)) score += 4;
            else if ("String".equals(p) || isArrayParam(p)) score += 3;
            else if (isObjectParam(p)) score += 0;
            else score += 2;
        }
        return score;
    }

    private boolean isObjectParam(String t) {
        if ("Object".equals(t)) return true;
        ClassInfo ci = classes.get(t);
        return ci != null && "cang_lang_Object".equals(ci.fullName);
    }

    private boolean isArrayParam(String t) {
        return innerElemCang(t) != null;
    }

    /** Runtime class check: actual typeId equals expected or one of its subclasses. */
    private boolean isAssignableTo(ClassInfo actual, ClassInfo expected) {
        ClassInfo cur = actual;
        int guard = 0;
        while (cur != null && guard++ < 128) {
            if (cur.typeId == expected.typeId || cur.fullName.equals(expected.fullName)) {
                return true;
            }
            cur = cur.parentName != null ? classes.get(cur.parentName) : null;
        }
        return false;
    }

    /**
     * Zero-arg entry point used by Class.newInstance(): delegate to the shared constructor
     * dispatcher with an empty argv. Kept as a separate symbol so existing calls stay tiny.
     */
    private void emitNewInstanceHelper() {
        if (header.toString().contains("define i8* @cang.newinstance(")) return;
        emitNewInstanceArgsHelper();
        header.append("define i8* @cang.newinstance(i32 %tid) {\n");
        header.append("entry:\n");
        header.append("  %ni.zero = call i8* @cang.newinstance.args(i32 %tid, i32 0, i8** null)\n");
        header.append("  ret i8* %ni.zero\n");
        header.append("}\n\n");
    }

    /**
     * Class.newInstance argument dispatcher: @cang.newinstance.args(typeId, argc, argv).
     * Walks every reflectively constructible class, then every public constructor of the
     * requested arity. Primitive parameters are decoded from the hidden Boxed wrapper
     * (Field.getObject), reference parameters receive the Object pointer; null is accepted
     * for reference parameters only. Returns null when no constructor matches.
     */
    private void emitNewInstanceArgsHelper() {
        if (header.toString().contains("define i8* @cang.newinstance.args(")) return;
        // Unique real classes by typeId (aliases share ClassInfo).
        java.util.TreeMap<Integer, ClassInfo> byId = new java.util.TreeMap<>();
        for (ClassInfo ci : classes.values()) byId.put(ci.typeId, ci);
        List<ClassInfo> ctorClasses = new ArrayList<>();
        for (ClassInfo ci : byId.values()) {
            if (!findNewInstanceCtors(ci).isEmpty()) ctorClasses.add(ci);
        }

        StringBuilder f = new StringBuilder();
        f.append("define i8* @cang.newinstance.args(i32 %tid, i32 %argc, i8** %argv) {\n");
        f.append("entry:\n");
        if (ctorClasses.isEmpty()) {
            f.append("  ret i8* null\n");
            f.append("}\n\n");
            header.append(f);
            return;
        }
        f.append("  br label %ni.a.class.0\n");
        for (int ciIdx = 0; ciIdx < ctorClasses.size(); ciIdx++) {
            ClassInfo ci = ctorClasses.get(ciIdx);
            List<FuncInfo> ctors = findNewInstanceCtors(ci);
            String fallback = (ciIdx + 1 < ctorClasses.size())
                ? "ni.a.class." + (ciIdx + 1) : "ni.a.done";
            f.append("ni.a.class.").append(ciIdx).append(":\n");
            String cmp = "%ni.a.cmp." + ciIdx;
            f.append("  ").append(cmp).append(" = icmp eq i32 %tid, ").append(ci.typeId).append("\n");
            f.append("  br i1 ").append(cmp).append(", label %ni.a.case.").append(ciIdx)
             .append(", label %").append(fallback).append("\n");
            f.append("ni.a.case.").append(ciIdx).append(":\n");
            int candBase = labelCount;
            labelCount += ctors.size();
            f.append("  br label %ni.a.cand.").append(candBase).append("\n");
            for (int j = 0; j < ctors.size(); j++) {
                String next = (j + 1 < ctors.size())
                    ? "ni.a.cand." + (candBase + j + 1) : fallback;
                emitNewInstanceCandidate(f, ci, ctors.get(j), candBase + j, next);
            }
        }
        f.append("ni.a.done:\n");
        f.append("  ret i8* null\n");
        f.append("}\n\n");
        header.append(f);
    }

    /** One candidate constructor: arity test, per-argument conversion, allocate + call. */
    private void emitNewInstanceCandidate(StringBuilder f, ClassInfo ci, FuncInfo ctor,
                                          int candId, String nextCand) {
        int argc = ctor.paramTypes.size();
        String candLabel = "ni.a.cand." + candId;
        String failLabel = "ni.a.fail." + candId;
        String callLabel = "ni.a.call." + candId;
        String argcOk = "%ni.a.argcok." + candId;
        f.append(candLabel).append(":\n");
        f.append("  ").append(argcOk).append(" = icmp eq i32 %argc, ").append(argc).append("\n");
        if (argc == 0) {
            f.append("  br i1 ").append(argcOk).append(", label %").append(callLabel)
             .append(", label %").append(failLabel).append("\n");
        } else {
            f.append("  br i1 ").append(argcOk).append(", label %ni.a.args.")
             .append(candId).append(".0, label %").append(failLabel).append("\n");
        }

        List<String> converted = new ArrayList<>();
        for (int i = 0; i < argc; i++) {
            String label = "ni.a.args." + candId + "." + i;
            f.append(label).append(":\n");
            String argPtr = "%ni.a.argp." + candId + "." + i;
            String arg = "%ni.a.arg." + candId + "." + i;
            f.append("  ").append(argPtr).append(" = getelementptr i8*, i8** %argv, i32 ")
             .append(i).append("\n");
            f.append("  ").append(arg).append(" = load i8*, i8** ").append(argPtr).append("\n");
            String expected = ctor.paramTypes.get(i);
            String next = (i + 1 < argc) ? "ni.a.args." + candId + "." + (i + 1) : callLabel;
            if (isFieldPrimType(expected)) {
                converted.add(emitNewInstanceUnbox(f, arg, expected, candId, i, next, failLabel));
            } else if (isObjectParam(expected)) {
                converted.add(arg);
                f.append("  br label %").append(next).append("\n");
            } else if ("String".equals(expected)) {
                // Strings have no typeId; reject pointers that do look like class instances.
                emitNewInstanceReferenceCheck(f, arg, candId, i, next, failLabel, null, true);
                converted.add(arg);
            } else if (isArrayParam(expected)) {
                emitNewInstanceReferenceCheck(f, arg, candId, i, next, failLabel, null, false);
                converted.add(arg);
            } else {
                ClassInfo expectedCi = classes.get(expected);
                if (expectedCi == null) {
                    expectedCi = classes.get(importAliases.getOrDefault(expected, expected));
                }
                emitNewInstanceReferenceCheck(f, arg, candId, i, next, failLabel, expectedCi, false);
                converted.add(arg);
            }
        }

        f.append(callLabel).append(":\n");
        String szp = "%ni.a.szp." + candId;
        f.append("  ").append(szp).append(" = getelementptr ").append(ci.llvmName)
         .append(", ").append(ci.llvmName).append("* null, i32 1\n");
        String sz = "%ni.a.sz." + candId;
        f.append("  ").append(sz).append(" = ptrtoint ").append(ci.llvmName).append("* ")
         .append(szp).append(" to i64\n");
        String raw = "%ni.a.raw." + candId;
        f.append("  ").append(raw).append(" = call i8* @").append(allocFn())
         .append("(i64 ").append(sz).append(")\n");
        String obj = "%ni.a.obj." + candId;
        f.append("  ").append(obj).append(" = bitcast i8* ").append(raw).append(" to ")
         .append(ci.llvmName).append("*\n");

        StringBuilder call = new StringBuilder();
        call.append(ci.llvmName).append("* ").append(obj);
        for (int i = 0; i < argc; i++) {
            String expected = ctor.paramTypes.get(i);
            String llvmT = toLLVMType(expected);
            String value = converted.get(i);
            if (isFieldPrimType(expected)) {
                call.append(", ").append(llvmT).append(" ").append(value);
            } else {
                if (!"i8*".equals(llvmT)) {
                    String cast = "%ni.a.cast." + candId + "." + i;
                    f.append("  ").append(cast).append(" = bitcast i8* ").append(value)
                     .append(" to ").append(llvmT).append("\n");
                    value = cast;
                }
                call.append(", ").append(llvmT).append(" ").append(value);
            }
        }
        String ctorSym = ctor.llvmName != null ? ctor.llvmName : ctor.name;
        f.append("  call void @").append(ctorSym).append("(").append(call).append(")\n");
        f.append("  ret i8* ").append(raw).append("\n");
        f.append(failLabel).append(":\n");
        f.append("  br label %").append(nextCand).append("\n");
    }

    /** Unbox one primitive Object argument (must be a Boxed wrapper) for the expected type. */
    private String emitNewInstanceUnbox(StringBuilder f, String arg, String expected,
                                        int candId, int argIndex, String next, String fail) {
        String prefix = candId + "." + argIndex;
        String isnull = "%ni.a.null." + prefix;
        f.append("  ").append(isnull).append(" = icmp eq i8* ").append(arg).append(", null\n");
        String nonnull = "ni.a.nn." + prefix;
        f.append("  br i1 ").append(isnull).append(", label %").append(fail)
         .append(", label %").append(nonnull).append("\n");
        f.append(nonnull).append(":\n");
        String tidp = "%ni.a.tidp." + prefix;
        f.append("  ").append(tidp).append(" = bitcast i8* ").append(arg).append(" to i32*\n");
        String tid = "%ni.a.tid." + prefix;
        f.append("  ").append(tid).append(" = load i32, i32* ").append(tidp).append("\n");
        ClassInfo bx = classes.get("Boxed");
        String isbox = "%ni.a.isbox." + prefix;
        if (bx == null || bx.typeId < 0) {
            f.append("  ").append(isbox).append(" = icmp eq i32 0, 1\n");
        } else {
            f.append("  ").append(isbox).append(" = icmp eq i32 ").append(tid)
             .append(", ").append(bx.typeId).append("\n");
        }
        String boxed = "ni.a.boxed." + prefix;
        f.append("  br i1 ").append(isbox).append(", label %").append(boxed)
         .append(", label %").append(fail).append("\n");
        f.append(boxed).append(":\n");
        String rawp = "%ni.a.rawp." + prefix;
        f.append("  ").append(rawp).append(" = getelementptr i8, i8* ").append(arg).append(", i64 8\n");
        String raw64p = "%ni.a.raw64p." + prefix;
        f.append("  ").append(raw64p).append(" = bitcast i8* ").append(rawp).append(" to i64*\n");
        String raw = "%ni.a.raw." + prefix;
        f.append("  ").append(raw).append(" = load i64, i64* ").append(raw64p).append("\n");
        String value;
        switch (expected) {
            case "byte": {
                value = "%ni.a.v." + prefix;
                f.append("  ").append(value).append(" = trunc i64 ").append(raw).append(" to i8\n");
                break;
            }
            case "int": {
                value = "%ni.a.v." + prefix;
                f.append("  ").append(value).append(" = trunc i64 ").append(raw).append(" to i32\n");
                break;
            }
            case "long": {
                value = raw;
                break;
            }
            case "bool": {
                value = "%ni.a.v." + prefix;
                f.append("  ").append(value).append(" = icmp ne i64 ").append(raw).append(", 0\n");
                break;
            }
            case "float": {
                String bits = "%ni.a.bits." + prefix;
                f.append("  ").append(bits).append(" = trunc i64 ").append(raw).append(" to i32\n");
                value = "%ni.a.v." + prefix;
                f.append("  ").append(value).append(" = bitcast i32 ").append(bits).append(" to float\n");
                break;
            }
            case "double": {
                value = "%ni.a.v." + prefix;
                f.append("  ").append(value).append(" = bitcast i64 ").append(raw).append(" to double\n");
                break;
            }
            default:
                throw new RuntimeException("internal: unsupported primitive constructor parameter: " + expected);
        }
        f.append("  br label %").append(next).append("\n");
        return value;
    }

    /**
     * Null/reference check for one argument. expected == null means String/array; strings
     * additionally reject pointers whose first word is a known class typeId, because Cang
     * strings have no runtime tag of their own. Arrays have no tag either, so they accept
     * any non-null pointer. Otherwise the runtime typeId must be expected or a subclass.
     */
    private void emitNewInstanceReferenceCheck(StringBuilder f, String arg, int candId,
                                               int argIndex, String next, String fail,
                                               ClassInfo expected, boolean rejectClassLike) {
        String prefix = candId + "." + argIndex;
        String isnull = "%ni.a.null." + prefix;
        f.append("  ").append(isnull).append(" = icmp eq i8* ").append(arg).append(", null\n");
        String nullok = "ni.a.nullok." + prefix;
        String nonnull = "ni.a.nn." + prefix;
        f.append("  br i1 ").append(isnull).append(", label %").append(nullok)
         .append(", label %").append(nonnull).append("\n");
        f.append(nullok).append(":\n");
        f.append("  br label %").append(next).append("\n");
        f.append(nonnull).append(":\n");
        String tidp = "%ni.a.tidp." + prefix;
        f.append("  ").append(tidp).append(" = bitcast i8* ").append(arg).append(" to i32*\n");
        String tid = "%ni.a.tid." + prefix;
        f.append("  ").append(tid).append(" = load i32, i32* ").append(tidp).append("\n");
        if (expected != null) {
            String match = emitNewInstanceClassMatch(f, tid, expected, prefix);
            f.append("  br i1 ").append(match).append(", label %").append(next)
             .append(", label %").append(fail).append("\n");
        } else if (rejectClassLike) {
            String classLike = emitNewInstanceAnyClassMatch(f, tid, prefix);
            String notclass = "%ni.a.notclass." + prefix;
            f.append("  ").append(notclass).append(" = icmp eq i1 ").append(classLike).append(", 0\n");
            f.append("  br i1 ").append(notclass).append(", label %").append(next)
             .append(", label %").append(fail).append("\n");
        } else {
            // Arrays have no runtime type tag. Their first word is the length, which may
            // legitimately equal a class/Boxed typeId, so no check is safe here.
            f.append("  br label %").append(next).append("\n");
        }
    }

    /** OR together all non-Object class typeIds (used to reject class pointers as String). */
    private String emitNewInstanceAnyClassMatch(StringBuilder f, String tid, String prefix) {
        String result = null;
        java.util.Set<Integer> seen = new java.util.LinkedHashSet<>();
        for (ClassInfo actual : classes.values()) {
            if (actual.typeId <= 0 || !seen.add(actual.typeId)) continue;
            String cmp = "%ni.a.acmp." + prefix + "." + actual.typeId;
            f.append("  ").append(cmp).append(" = icmp eq i32 ").append(tid)
             .append(", ").append(actual.typeId).append("\n");
            if (result == null) {
                result = cmp;
            } else {
                String or = "%ni.a.aor." + prefix + "." + actual.typeId;
                f.append("  ").append(or).append(" = or i1 ").append(result)
                 .append(", ").append(cmp).append("\n");
                result = or;
            }
        }
        if (result == null) {
            result = "%ni.a.afalse." + prefix;
            f.append("  ").append(result).append(" = icmp eq i32 0, 1\n");
        }
        return result;
    }

    /** OR together the typeIds assignable to expected; constant false when none. */
    private String emitNewInstanceClassMatch(StringBuilder f, String tid, ClassInfo expected,
                                             String prefix) {
        String result = null;
        java.util.Set<Integer> seen = new java.util.LinkedHashSet<>();
        for (ClassInfo actual : classes.values()) {
            if (!seen.add(actual.typeId)) continue;
            if (!isAssignableTo(actual, expected)) continue;
            String cmp = "%ni.a.tcmp." + prefix + "." + actual.typeId;
            f.append("  ").append(cmp).append(" = icmp eq i32 ").append(tid)
             .append(", ").append(actual.typeId).append("\n");
            if (result == null) {
                result = cmp;
            } else {
                String or = "%ni.a.tor." + prefix + "." + actual.typeId;
                f.append("  ").append(or).append(" = or i1 ").append(result)
                 .append(", ").append(cmp).append("\n");
                result = or;
            }
        }
        if (result == null) {
            result = "%ni.a.tfalse." + prefix;
            f.append("  ").append(result).append(" = icmp eq i32 0, 1\n");
        }
        return result;
    }

    /** Apple.class: load the singleton Class* for the resolved class (or String/primitive). */
    private LLVMValue generateClassLiteral(ClassLiteralExpr node) {
        ClassInfo cls = classInfoOfClass();
        if (cls == null || !classObjectInitEmitted) {
            throw new RuntimeException("Class literal requires the stdlib class cang/lang/Class "
                + "(at line " + node.line + ")");
        }
        String clsPtr = cls.llvmName + "*";
        String resolved = importAliases.getOrDefault(node.className, node.className);
        ClassInfo target = classes.get(resolved);
        if (target != null) {
            int size = classNameTableSize();
            String slot = "%cls.slot." + tmpCount++;
            body.append("  ").append(slot).append(" = getelementptr [").append(size)
                .append(" x ").append(clsPtr).append("], [").append(size).append(" x ")
                .append(clsPtr).append("]* @cang.classobjs, i64 0, i64 ")
                .append(target.typeId).append("\n");
            String val = "%cls.val." + tmpCount++;
            body.append("  ").append(val).append(" = load ").append(clsPtr).append(", ")
                .append(clsPtr).append("* ").append(slot).append("\n");
            return new LLVMValue(val, clsPtr, "Class");
        }
        if (isSpecialClassLiteral(node.className)) {
            String val = "%cls.val." + tmpCount++;
            body.append("  ").append(val).append(" = load ").append(clsPtr).append(", ")
                .append(clsPtr).append("* ").append(specialClassObjectGlobal(node.className))
                .append("\n");
            return new LLVMValue(val, clsPtr, "Class");
        }
        throw unknownClassError(node.className, node.line);
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

    /**
     * Field metadata (lazily emitted): per-typeId {start,count} ranges over a flat table of
     * {name, displayName, fieldIndex}. plus the shared name-lookup helper @cang.field.find.
     * displayName is what Field.getType/getObject work with: the primitive name for primitive
     * fields, "String"/"Array" for those builtins, otherwise the class's full name.
     */
    private void emitFieldMeta() {
        if (header.toString().contains("@cang.fieldmeta.range = ")) return;
        int size = classNameTableSize();
        ClassInfo[] byId = new ClassInfo[size];
        for (ClassInfo ci : classes.values()) {
            if (ci.typeId >= 0 && ci.typeId < size) byId[ci.typeId] = ci; // aliases share the id
        }
        int[] starts = new int[size];
        int[] counts = new int[size];
        int total = 0;
        for (int i = 0; i < size; i++) {
            ClassInfo ci = byId[i];
            int c = 0;
            if (ci != null) {
                for (String fn : ci.fieldNames) if (!fn.equals("_dummy")) c++; // skip compiler placeholder
            }
            counts[i] = c;
            starts[i] = total;
            total += c;
        }
        StringBuilder range = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) range.append(", ");
            // Array element structs need an explicit type prefix for this LLVM.
            range.append("{ i32, i32 } { i32 ").append(starts[i]).append(", i32 ").append(counts[i]).append(" }");
        }
        header.append("@cang.fieldmeta.range = internal global [").append(size)
              .append(" x { i32, i32 }] [").append(range).append("]\n");
        StringBuilder tab = new StringBuilder();
        for (int i = 0; i < size; i++) {
            ClassInfo ci = byId[i];
            if (ci == null) continue;
            for (int f = 0; f < ci.fieldNames.size(); f++) {
                String fname = ci.fieldNames.get(f);
                if (fname.equals("_dummy")) continue; // compiler placeholder, not a user field
                String ftype = fieldDisplayType(ci.fieldTypes.get(f));
                Integer off = ci.fieldIndices.get(fname);
                if (off == null) continue;
                int byteOff = byteOffsetOfField(ci, f);
                String nConst = ensureClassNameStrConst(fname);
                String tConst = ensureClassNameStrConst(ftype);
                if (tab.length() > 0) tab.append(", ");
                tab.append("{ i8*, i8*, i32 } { i8* getelementptr inbounds ([").append(utf8Len(fname) + 1).append(" x i8], [")
                   .append(utf8Len(fname) + 1).append(" x i8]* ").append(nConst).append(", i32 0, i32 0), i8* getelementptr inbounds ([")
                   .append(utf8Len(ftype) + 1).append(" x i8], [").append(utf8Len(ftype) + 1)
                   .append(" x i8]* ").append(tConst).append(", i32 0, i32 0), i32 ").append(byteOff).append(" }");
            }
        }
        header.append("@cang.fieldmeta.tab = internal global [").append(total)
              .append(" x { i8*, i8*, i32 }] [").append(tab).append("]\n");
        if (!header.toString().contains("declare i32 @strncmp(")) {
            header.append("declare i32 @strncmp(i8*, i8*, i64)\n");
        }
        if (!header.toString().contains("declare i32 @memcmp(")) {
            header.append("declare i32 @memcmp(i8*, i8*, i64)\n");
        }
        // Find a field: display type name (or null) + offset through *%offOut.
        header.append("define i8* @cang.field.find(i32 %tid, i8* %name, i32* %offOut) {\n");
        header.append("entry:\n");
        header.append("  %n = zext i32 %tid to i64\n");
        header.append("  %rp = getelementptr [").append(size).append(" x { i32, i32 }], [")
              .append(size).append(" x { i32, i32 }]* @cang.fieldmeta.range, i64 0, i64 %n\n");
        header.append("  %sp = getelementptr { i32, i32 }, { i32, i32 }* %rp, i32 0, i32 0\n");
        header.append("  %start = load i32, i32* %sp\n");
        header.append("  %cp = getelementptr { i32, i32 }, { i32, i32 }* %rp, i32 0, i32 1\n");
        header.append("  %cnt = load i32, i32* %cp\n");
        header.append("  %end = add i32 %start, %cnt\n");
        header.append("  %idx = alloca i32\n  store i32 %start, i32* %idx\n");
        header.append("  br label %loop\n");
        header.append("loop:\n");
        header.append("  %iv = load i32, i32* %idx\n");
        header.append("  %go = icmp slt i32 %iv, %end\n");
        header.append("  br i1 %go, label %body, label %miss\n");
        header.append("body:\n");
        header.append("  %iv64 = zext i32 %iv to i64\n");
        header.append("  %ep = getelementptr [").append(total).append(" x { i8*, i8*, i32 }], [")
              .append(total).append(" x { i8*, i8*, i32 }]* @cang.fieldmeta.tab, i64 0, i64 %iv64\n");
        header.append("  %np = getelementptr { i8*, i8*, i32 }, { i8*, i8*, i32 }* %ep, i32 0, i32 0\n");
        header.append("  %en = load i8*, i8** %np\n");
        // Exact match: same length AND equal bytes (strncmp((size_t)-1) is avoided for portability).
        header.append("  %ln1 = call i64 @strlen(i8* %en)\n");
        header.append("  %ln2 = call i64 @strlen(i8* %name)\n");
        header.append("  %lneq = icmp eq i64 %ln1, %ln2\n");
        header.append("  br i1 %lneq, label %cmpbody, label %next\n");
        header.append("cmpbody:\n");
        header.append("  %cmp = call i32 @memcmp(i8* %en, i8* %name, i64 %ln1)\n");
        header.append("  %eq = icmp eq i32 %cmp, 0\n");
        header.append("  br i1 %eq, label %hit, label %next\n");
        header.append("hit:\n");
        header.append("  %tp2 = getelementptr { i8*, i8*, i32 }, { i8*, i8*, i32 }* %ep, i32 0, i32 1\n");
        header.append("  %tn = load i8*, i8** %tp2\n");
        header.append("  %op = getelementptr { i8*, i8*, i32 }, { i8*, i8*, i32 }* %ep, i32 0, i32 2\n");
        header.append("  %ov = load i32, i32* %op\n");
        header.append("  store i32 %ov, i32* %offOut\n");
        header.append("  ret i8* %tn\n");
        header.append("next:\n");
        header.append("  %iv2 = add i32 %iv, 1\n");
        header.append("  store i32 %iv2, i32* %idx\n");
        header.append("  br label %loop\n");
        header.append("miss:\n");
        header.append("  ret i8* null\n");
        header.append("}\n\n");
        emitFieldListHelper();
    }

    /**
     * Field.list runtime helper @cang.field.list(i32 %tid): walk the metadata range for
     * %tid and return a String[] (standard array ABI: [i64 count][i8* elements...]) of
     * the field-name pointers stored in @cang.fieldmeta.tab. The pointers are compile-time
     * string constants shared with the table — no per-name copy. Zero fields → empty array.
     */
    private void emitFieldListHelper() {
        if (header.toString().contains("define i8* @cang.field.list(")) return;
        String alloc = allocFn();
        int size = classNameTableSize();
        int total = 0;
        ClassInfo[] byId = new ClassInfo[size];
        for (ClassInfo ci : classes.values()) {
            if (ci.typeId >= 0 && ci.typeId < size) byId[ci.typeId] = ci;
        }
        for (int i = 0; i < size; i++) {
            ClassInfo ci = byId[i];
            if (ci != null) {
                for (String fn : ci.fieldNames) if (!fn.equals("_dummy")) total++;
            }
        }
        header.append("define i8* @cang.field.list(i32 %tid) {\n");
        header.append("entry:\n");
        header.append("  %n = zext i32 %tid to i64\n");
        header.append("  %rp = getelementptr [").append(size).append(" x { i32, i32 }], [")
             .append(size).append(" x { i32, i32 }]* @cang.fieldmeta.range, i64 0, i64 %n\n");
        header.append("  %sp = getelementptr { i32, i32 }, { i32, i32 }* %rp, i32 0, i32 0\n");
        header.append("  %start = load i32, i32* %sp\n");
        header.append("  %cp = getelementptr { i32, i32 }, { i32, i32 }* %rp, i32 0, i32 1\n");
        header.append("  %cnt = load i32, i32* %cp\n");
        header.append("  %cnt64 = zext i32 %cnt to i64\n");
        header.append("  %dsz = shl i64 %cnt64, 3\n");
        header.append("  %tsz = add i64 %dsz, 8\n");
        header.append("  %arr = call i8* @").append(alloc).append("(i64 %tsz)\n");
        header.append("  store i64 %cnt64, i64* %arr\n");
        header.append("  %data = getelementptr i8, i8* %arr, i64 8\n");
        header.append("  %typed = bitcast i8* %data to i8**\n");
        header.append("  %s64 = zext i32 %start to i64\n");
        header.append("  %i = alloca i64\n  store i64 0, i64* %i\n");
        header.append("  br label %loop\n");
        header.append("loop:\n");
        header.append("  %iv = load i64, i64* %i\n");
        header.append("  %go = icmp slt i64 %iv, %cnt64\n");
        header.append("  br i1 %go, label %body, label %done\n");
        header.append("body:\n");
        header.append("  %gi = add i64 %s64, %iv\n");
        header.append("  %ep = getelementptr [").append(total).append(" x { i8*, i8*, i32 }], [")
             .append(total).append(" x { i8*, i8*, i32 }]* @cang.fieldmeta.tab, i64 0, i64 %gi\n");
        header.append("  %np = getelementptr { i8*, i8*, i32 }, { i8*, i8*, i32 }* %ep, i32 0, i32 0\n");
        header.append("  %name = load i8*, i8** %np\n");
        header.append("  %slot = getelementptr i8*, i8** %typed, i64 %iv\n");
        header.append("  store i8* %name, i8** %slot\n");
        header.append("  %iv2 = add i64 %iv, 1\n");
        header.append("  store i64 %iv2, i64* %i\n");
        header.append("  br label %loop\n");
        header.append("done:\n");
        header.append("  ret i8* %arr\n");
        header.append("}\n\n");
    }

    // ==================== Field reflection (builtin Field class) ====================

    /** Methods on the builtin Field class (stdlib/cang/lang/Field.cang) generated intrinsically. */
    private static final java.util.Set<String> FIELD_ACCESSOR_METHODS = new java.util.HashSet<>(java.util.Arrays.asList(
        "getObject", "getString", "getInt", "getLong", "getByte", "getBool", "getFloat", "getDouble", "getType", "list",
        "setObject", "setString", "setInt", "setLong", "setByte", "setBool", "setFloat", "setDouble"));

    /** Accessor/setter method -> expected primitive field type (getObject/getType/setObject/setString are absent). */
    private static final Map<String, String> FIELD_PRIM_METHODS = new java.util.LinkedHashMap<>();
    static {
        FIELD_PRIM_METHODS.put("getInt", "int");
        FIELD_PRIM_METHODS.put("getLong", "long");
        FIELD_PRIM_METHODS.put("getByte", "byte");
        FIELD_PRIM_METHODS.put("getBool", "bool");
        FIELD_PRIM_METHODS.put("getFloat", "float");
        FIELD_PRIM_METHODS.put("getDouble", "double");
        FIELD_PRIM_METHODS.put("setInt", "int");
        FIELD_PRIM_METHODS.put("setLong", "long");
        FIELD_PRIM_METHODS.put("setByte", "byte");
        FIELD_PRIM_METHODS.put("setBool", "bool");
        FIELD_PRIM_METHODS.put("setFloat", "float");
        FIELD_PRIM_METHODS.put("setDouble", "double");
    }
    /** Primitive field display names, matching stdlib Field accessor declaration order. */
    private static final String[] FIELD_PRIMS = {"int", "long", "byte", "bool", "float", "double"};

    private boolean isFieldPrimType(String cangType) {
        for (String p : FIELD_PRIMS) if (cangType.equals(p)) return true;
        return false;
    }

    /**
     * Metadata display name for @cang.field.find / Field.getType: primitives keep their Cang
     * name; Array<T> collapses to "Array" (matching Object.getClassName); String stays
     * "String"; other class types resolve to the registered full name — a subclass value
     * stored in the field still reports the declared class of that field.
     */
    private String fieldDisplayType(String cangType) {
        if (isFieldPrimType(cangType)) return cangType;
        if (cangType.startsWith("Array<") && cangType.endsWith(">")) return "Array";
        String base = cangType;
        int lt = cangType.indexOf('<');
        if (lt > 0) base = cangType.substring(0, lt);
        ClassInfo ci = classes.get(cangType);
        if (ci == null) ci = classes.get(base);
        return ci != null ? ci.fullName : cangType;
    }

    /**
     * Field.<accessor>(name) intrinsics: read the bound target object from the Field
     * receiver, resolve the field through @cang.field.find on the target's runtime typeId,
     * then load and return the value:
     *   getObject  — String field loads the pointer as String; other reference fields load
     *                it as %cang_lang_Object* (Object intrinsics like getClassName keep
     *                dispatching through the runtime typeId); primitive fields are boxed
     *                bit-exact into a hidden cang_lang_Boxed {i32, i64} wrapper (raw bits
     *                only — read the value back through getInt/getLong/...).
     *   getInt/... — the display name must equal the accessor's primitive, else a runtime
     *                error; the value loads at the metadata byte offset.
     *   getType    — hands back the metadata display-name pointer (allocation-free).
     * Runtime errors (field name/target null, field missing, wrong primitive kind) are
     * reported with the call site line.
     */
    private LLVMValue generateFieldAccessor(ClassInfo fieldCi, LLVMValue recv, MethodCallExpr node) {
        String m = node.method;
        String expectedPrim = FIELD_PRIM_METHODS.get(m); // null for getObject/getType/setObject/setString
        boolean isSetter = m.startsWith("set");
        // Field.list(): no field-name argument — enumerate every field of the target via
        // the shared metadata table. Returns the standard String[] array ABI.
        if (m.equals("list")) {
            if (!node.args.isEmpty()) {
                throw new RuntimeException("Field.list expects no arguments (at line " + node.line + ")");
            }
            return generateFieldList(fieldCi, recv, node);
        }
        int expectArgs = isSetter ? 2 : 1;
        if (node.args.size() != expectArgs) {
            throw new RuntimeException("Field." + m + " expects " + expectArgs + " argument(s) (at line " + node.line + ")");
        }
        LLVMValue nameV = generateExpr(node.args.get(0));
        if (!"i8*".equals(nameV.type)) {
            throw new RuntimeException("Field." + m + " field name must be a String (at line " + node.line + ")");
        }
        LLVMValue valV = null; // setter: the value to store (arg 1)
        if (isSetter) {
            valV = generateExpr(node.args.get(1));
        }

        int id = labelCount++;
        String nx = "fa." + m + "." + id;

        // Load the bound target object from the receiver's `target` field (declared Object).
        String recvBase = fieldCi.llvmName.substring(1);
        int tgtOff = fieldCi.fieldIndices.get("target");
        String tgtPtr = "%fa.tp." + tmpCount++;
        body.append("  ").append(tgtPtr).append(" = getelementptr %").append(recvBase)
             .append(", %").append(recvBase).append("* ").append(recv.value)
             .append(", i32 0, i32 ").append(tgtOff).append("\n");
        String tgt = "%fa.tgt." + tmpCount++;
        body.append("  ").append(tgt).append(" = load %cang_lang_Object*, %cang_lang_Object** ").append(tgtPtr).append("\n");
        // null target → runtime error
        String tNull = "%fa.tn." + tmpCount++;
        body.append("  ").append(tNull).append(" = icmp eq %cang_lang_Object* ").append(tgt).append(", null\n");
        String errT = nx + ".nulltgt", okT = nx + ".tgt";
        body.append("  br i1 ").append(tNull).append(", label %").append(errT).append(", label %").append(okT).append("\n");
        body.append(errT).append(":\n");
        emitRuntimeError("Field." + m + ": target object is null", node.line, okT);
        body.append(okT).append(":\n");
        // null field name must not reach strlen/memcmp
        String nNull = "%fa.nn." + tmpCount++;
        body.append("  ").append(nNull).append(" = icmp eq i8* ").append(nameV.value).append(", null\n");
        String errN = nx + ".noname", okN = nx + ".name";
        body.append("  br i1 ").append(nNull).append(", label %").append(errN).append(", label %").append(okN).append("\n");
        body.append(errN).append(":\n");
        emitRuntimeError("Field." + m + ": field name is null", node.line, okN);
        body.append(okN).append(":\n");

        // Field lookup by the target's runtime typeId.
        String tidPtr = "%fa.tidp." + tmpCount++;
        body.append("  ").append(tidPtr).append(" = getelementptr %cang_lang_Object, %cang_lang_Object* ")
             .append(tgt).append(", i32 0, i32 0\n");
        String tid = "%fa.tid." + tmpCount++;
        body.append("  ").append(tid).append(" = load i32, i32* ").append(tidPtr).append("\n");
        emitFieldMeta();
        emitBoxPrimHelper();
        String off = "%fa.off." + tmpCount++;
        body.append("  ").append(off).append(" = alloca i32\n");
        String ftype = "%fa.ft." + tmpCount++;
        body.append("  ").append(ftype).append(" = call i8* @cang.field.find(i32 ").append(tid)
             .append(", i8* ").append(nameV.value).append(", i32* ").append(off).append(")\n");
        String missing = "%fa.miss." + tmpCount++;
        body.append("  ").append(missing).append(" = icmp eq i8* ").append(ftype).append(", null\n");
        String errF = nx + ".missing", okF = nx + ".found";
        body.append("  br i1 ").append(missing).append(", label %").append(errF).append(", label %").append(okF).append("\n");
        body.append(errF).append(":\n");
        emitRuntimeErrorDynamic(buildFieldErrorMsg(m, "target object has no field \"%s\"", nameV), node.line, okF);
        body.append(okF).append(":\n");

        // Byte address of the field inside the target struct.
        String t8 = "%fa.t8." + tmpCount++;
        body.append("  ").append(t8).append(" = bitcast %cang_lang_Object* ").append(tgt).append(" to i8*\n");
        String offv = "%fa.offv." + tmpCount++;
        body.append("  ").append(offv).append(" = load i32, i32* ").append(off).append("\n");
        String off64 = "%fa.off64." + tmpCount++;
        body.append("  ").append(off64).append(" = sext i32 ").append(offv).append(" to i64\n");
        String bp = "%fa.bp." + tmpCount++;
        body.append("  ").append(bp).append(" = getelementptr i8, i8* ").append(t8).append(", i64 ").append(off64).append("\n");

        // getType: hand back the metadata display-name pointer directly.
        if (m.equals("getType")) {
            return new LLVMValue(ftype, "i8*", "String");
        }

        // ---- setters: type-check the display name, then store the value at the field slot ----
        if (isSetter) {
            // Type check helpers shared by all setters.
            String emitTypeCheck = null; // placeholder to keep structure flat below
            String expected = null;      // display name to require ("int"/"String"/...)
            String mismatchMsg = null;
            if (expectedPrim != null) {
                expected = expectedPrim;
                mismatchMsg = "field \"%s\" is not " + expectedPrim;
            } else if (m.equals("setString")) {
                expected = "String";
                mismatchMsg = "field \"%s\" is not String";
            } // setObject: any non-primitive reference field (no display-name check beyond "exists")

            if (expected != null) {
                LLVMValue expC = makeStringConstant(expected);
                String sln1 = "%fa.sln1." + tmpCount++;
                body.append("  ").append(sln1).append(" = call i64 @strlen(i8* ").append(ftype).append(")\n");
                String sln2 = "%fa.sln2." + tmpCount++;
                body.append("  ").append(sln2).append(" = call i64 @strlen(i8* ").append(expC.value).append(")\n");
                String seld = "%fa.seld." + tmpCount++;
                body.append("  ").append(seld).append(" = icmp eq i64 ").append(sln1).append(", ").append(sln2).append("\n");
                String smem2 = "%fa.smem2." + tmpCount++;
                body.append("  ").append(smem2).append(" = call i32 @memcmp(i8* ").append(ftype).append(", i8* ")
                     .append(expC.value).append(", i64 ").append(sln1).append(")\n");
                String smok2 = "%fa.smok2." + tmpCount++;
                body.append("  ").append(smok2).append(" = icmp eq i32 ").append(smem2).append(", 0\n");
                String ssame = "%fa.ssame." + tmpCount++;
                body.append("  ").append(ssame).append(" = and i1 ").append(seld).append(", ").append(smok2).append("\n");
                String sBad = "%fa.sbad." + tmpCount++;
                body.append("  ").append(sBad).append(" = icmp eq i1 ").append(ssame).append(", 0\n");
                String serrY = nx + ".typemismatch", sokY = nx + ".typeok";
                body.append("  br i1 ").append(sBad).append(", label %").append(serrY).append(", label %").append(sokY).append("\n");
                body.append(serrY).append(":\n");
                emitRuntimeErrorDynamic(buildFieldErrorMsg(m, mismatchMsg, nameV), node.line, sokY);
                body.append(sokY).append(":\n");
            } else if (m.equals("setObject")) {
                // setObject: reject primitive fields — basic types must use setInt/setLong/...
                // Compare ftype's display name against each of the six primitive names.
                String flen = "%fa.flen." + tmpCount++;
                body.append("  ").append(flen).append(" = call i64 @strlen(i8* ").append(ftype).append(")\n");
                String isPrim = "%fa.isprim." + tmpCount++;
                body.append("  ").append(isPrim).append(" = icmp eq i32 0, 1\n"); // constant false
                for (String prim : FIELD_PRIMS) {
                    LLVMValue pc = makeStringConstant(prim);
                    String plen = "%fa.plen." + tmpCount++;
                    body.append("  ").append(plen).append(" = call i64 @strlen(i8* ").append(pc.value).append(")\n");
                    String peq = "%fa.peq." + tmpCount++;
                    body.append("  ").append(peq).append(" = icmp eq i64 ").append(flen).append(", ").append(plen).append("\n");
                    String pmem = "%fa.pmem." + tmpCount++;
                    body.append("  ").append(pmem).append(" = call i32 @memcmp(i8* ").append(ftype).append(", i8* ")
                         .append(pc.value).append(", i64 ").append(flen).append(")\n");
                    String pok = "%fa.pok." + tmpCount++;
                    body.append("  ").append(pok).append(" = icmp eq i32 ").append(pmem).append(", 0\n");
                    String pm = "%fa.pm." + tmpCount++;
                    body.append("  ").append(pm).append(" = and i1 ").append(peq).append(", ").append(pok).append("\n");
                    String pn = "%fa.pn." + tmpCount++;
                    body.append("  ").append(pn).append(" = or i1 ").append(isPrim).append(", ").append(pm).append("\n");
                    isPrim = pn;
                }
                String primBad = nx + ".primfield", primOk = nx + ".refok";
                body.append("  br i1 ").append(isPrim).append(", label %").append(primBad)
                     .append(", label %").append(primOk).append("\n");
                body.append(primBad).append(":\n");
                emitRuntimeErrorDynamic(buildFieldErrorMsg(m,
                    "field \"%s\" is a primitive; use the matching setInt/setLong/... instead", nameV),
                    node.line, primOk);
                body.append(primOk).append(":\n");
            }

            // Store: bitcast the byte pointer to the value's LLVM type and store.
            if (expectedPrim != null) {
                String llvmT = toLLVMType(expectedPrim);
                String castV = castValue(valV, llvmT);
                String fp = "%fa.fp." + tmpCount++;
                body.append("  ").append(fp).append(" = bitcast i8* ").append(bp).append(" to ").append(llvmT).append("*\n");
                body.append("  store ").append(llvmT).append(" ").append(castV).append(", ").append(llvmT).append("* ").append(fp).append("\n");
            } else {
                // setString / setObject: the value is a pointer (i8* / %X*); store as i8*.
                String ptrV = valV.type.equals("i8*") ? valV.value
                    : castValue(valV, "i8*");
                String fp = "%fa.fp." + tmpCount++;
                body.append("  ").append(fp).append(" = bitcast i8* ").append(bp).append(" to i8**\n");
                body.append("  store i8* ").append(ptrV).append(", i8** ").append(fp).append("\n");
            }
            return new LLVMValue("void", "void");
        }

        // getString: the display type must be "String".
        if (m.equals("getString")) {
            LLVMValue strC = makeStringConstant("String");
            String sln1 = "%fa.sln1." + tmpCount++;
            body.append("  ").append(sln1).append(" = call i64 @strlen(i8* ").append(ftype).append(")\n");
            String sln2 = "%fa.sln2." + tmpCount++;
            body.append("  ").append(sln2).append(" = call i64 @strlen(i8* ").append(strC.value).append(")\n");
            String seld = "%fa.seld." + tmpCount++;
            body.append("  ").append(seld).append(" = icmp eq i64 ").append(sln1).append(", ").append(sln2).append("\n");
            String smem2 = "%fa.smem2." + tmpCount++;
            body.append("  ").append(smem2).append(" = call i32 @memcmp(i8* ").append(ftype).append(", i8* ")
                 .append(strC.value).append(", i64 ").append(sln1).append(")\n");
            String smok2 = "%fa.smok2." + tmpCount++;
            body.append("  ").append(smok2).append(" = icmp eq i32 ").append(smem2).append(", 0\n");
            String ssame = "%fa.ssame." + tmpCount++;
            body.append("  ").append(ssame).append(" = and i1 ").append(seld).append(", ").append(smok2).append("\n");
            String sBad = "%fa.sbad." + tmpCount++;
            body.append("  ").append(sBad).append(" = icmp eq i1 ").append(ssame).append(", 0\n");
            String serrY = nx + ".typemismatch", sokY = nx + ".typeok";
            body.append("  br i1 ").append(sBad).append(", label %").append(serrY).append(", label %").append(sokY).append("\n");
            body.append(serrY).append(":\n");
            emitRuntimeErrorDynamic(buildFieldErrorMsg(m, "field \"%s\" is not String", nameV), node.line, sokY);
            body.append(sokY).append(":\n");
            String sfp = "%fa.sfp." + tmpCount++;
            body.append("  ").append(sfp).append(" = bitcast i8* ").append(bp).append(" to i8**\n");
            String sv = "%fa.sv." + tmpCount++;
            body.append("  ").append(sv).append(" = load i8*, i8** ").append(sfp).append("\n");
            return new LLVMValue(sv, "i8*", "String");
        }

        // Typed primitive getters: display name must equal the accessor's primitive.
        if (expectedPrim != null) {
            LLVMValue expected = makeStringConstant(expectedPrim);
            String eln1 = "%fa.eln1." + tmpCount++;
            body.append("  ").append(eln1).append(" = call i64 @strlen(i8* ").append(ftype).append(")\n");
            String eln2 = "%fa.eln2." + tmpCount++;
            body.append("  ").append(eln2).append(" = call i64 @strlen(i8* ").append(expected.value).append(")\n");
            String eld = "%fa.eld." + tmpCount++;
            body.append("  ").append(eld).append(" = icmp eq i64 ").append(eln1).append(", ").append(eln2).append("\n");
            String emem = "%fa.emem." + tmpCount++;
            body.append("  ").append(emem).append(" = call i32 @memcmp(i8* ").append(ftype).append(", i8* ")
                 .append(expected.value).append(", i64 ").append(eln1).append(")\n");
            String emok = "%fa.emok." + tmpCount++;
            body.append("  ").append(emok).append(" = icmp eq i32 ").append(emem).append(", 0\n");
            String same = "%fa.esame." + tmpCount++;
            body.append("  ").append(same).append(" = and i1 ").append(eld).append(", ").append(emok).append("\n");
            String tBad = "%fa.tb." + tmpCount++;
            body.append("  ").append(tBad).append(" = icmp eq i1 ").append(same).append(", 0\n");
            String errY = nx + ".typemismatch", okY = nx + ".typeok";
            body.append("  br i1 ").append(tBad).append(", label %").append(errY).append(", label %").append(okY).append("\n");
            body.append(errY).append(":\n");
            emitRuntimeErrorDynamic(buildFieldErrorMsg(m, "field \"%s\" is not " + expectedPrim, nameV), node.line, okY);
            body.append(okY).append(":\n");
            String llvmT = toLLVMType(expectedPrim);
            String fp = "%fa.fp." + tmpCount++;
            body.append("  ").append(fp).append(" = bitcast i8* ").append(bp).append(" to ").append(llvmT).append("*\n");
            String v = "%fa.v." + tmpCount++;
            body.append("  ").append(v).append(" = load ").append(llvmT).append(", ").append(llvmT).append("* ").append(fp).append("\n");
            return new LLVMValue(v, llvmT);
        }

        // ---- getObject ----
        // isPrim = the display type matches one of the six primitive names at runtime.
        String nlen = "%fa.nlen." + tmpCount++;
        body.append("  ").append(nlen).append(" = call i64 @strlen(i8* ").append(ftype).append(")\n");
        String isPrim = "%fa.isprim." + tmpCount++;
        body.append("  ").append(isPrim).append(" = icmp eq i32 0, 1\n"); // constant false
        for (String prim : FIELD_PRIMS) {
            LLVMValue pc = makeStringConstant(prim);
            String plen = "%fa.plen." + tmpCount++;
            body.append("  ").append(plen).append(" = call i64 @strlen(i8* ").append(pc.value).append(")\n");
            String peq = "%fa.peq." + tmpCount++;
            body.append("  ").append(peq).append(" = icmp eq i64 ").append(nlen).append(", ").append(plen).append("\n");
            String pmem = "%fa.pmem." + tmpCount++;
            body.append("  ").append(pmem).append(" = call i32 @memcmp(i8* ").append(ftype).append(", i8* ")
                 .append(pc.value).append(", i64 ").append(nlen).append(")\n");
            String pok = "%fa.pok." + tmpCount++;
            body.append("  ").append(pok).append(" = icmp eq i32 ").append(pmem).append(", 0\n");
            String pm = "%fa.pm." + tmpCount++;
            body.append("  ").append(pm).append(" = and i1 ").append(peq).append(", ").append(pok).append("\n");
            String pn = "%fa.pn." + tmpCount++;
            body.append("  ").append(pn).append(" = or i1 ").append(isPrim).append(", ").append(pm).append("\n");
            isPrim = pn;
        }

        String okP = nx + ".prim", nextSR = nx + ".strref";
        body.append("  br i1 ").append(isPrim).append(", label %").append(okP).append(", label %").append(nextSR).append("\n");

        // ---- primitive branch: box the raw bits into a Boxed wrapper ----
        body.append(okP).append(":\n");
        // Raw bits: @cang.boxprim dispatches on the display name (int/long/byte/bool/
        // float/double) and widens each kind into an i64 without destroying the bits.
        String raw = "%fa.raw." + tmpCount++;
        body.append("  ").append(raw).append(" = call i64 @cang.boxprim(i8* ").append(ftype)
             .append(", i8* ").append(bp).append(")\n");
        ClassInfo bx = classes.get("Boxed");
        if (bx == null || bx.typeId < 0) {
            throw new RuntimeException("internal: Boxed wrapper class missing (getObject at line " + node.line + ")");
        }
        String mem = "%fa.box." + tmpCount++;
        body.append("  ").append(mem).append(" = call i8* @").append(allocFn()).append("(i64 16)\n");
        String btidPtr = "%fa.btidp." + tmpCount++;
        body.append("  ").append(btidPtr).append(" = bitcast i8* ").append(mem).append(" to i32*\n");
        body.append("  store i32 ").append(bx.typeId).append(", i32* ").append(btidPtr).append("\n");
        String brawPtr = "%fa.brawp." + tmpCount++;
        body.append("  ").append(brawPtr).append(" = getelementptr i8, i8* ").append(mem).append(", i64 8\n");
        String braw64 = "%fa.braw64." + tmpCount++;
        body.append("  ").append(braw64).append(" = bitcast i8* ").append(brawPtr).append(" to i64*\n");
        body.append("  store i64 ").append(raw).append(", i64* ").append(braw64).append("\n");
        body.append("  br label %").append(nx).append(".done\n");

        // ---- String / reference branch: load the slot's pointer ----
        body.append(nextSR).append(":\n");
        String rfp = "%fa.rfp." + tmpCount++;
        body.append("  ").append(rfp).append(" = bitcast i8* ").append(bp).append(" to i8**\n");
        String rv = "%fa.rv." + tmpCount++;
        body.append("  ").append(rv).append(" = load i8*, i8** ").append(rfp).append("\n");
        body.append("  br label %").append(nx).append(".done\n");

        // Merge both branches: primitive boxing result vs the loaded pointer. The result is
        // exposed as %cang_lang_Object* so Object intrinsics (getClassName/toString/...)
        // keep dispatching through the runtime typeId; a String field's pointer works the
        // same shallow way (see getString for a direct read).
        body.append(nx).append(".done").append(":\n");
        String phi = "%fa.res." + tmpCount++;
        body.append("  ").append(phi).append(" = phi i8* [ ").append(mem).append(", %").append(okP)
             .append(" ], [ ").append(rv).append(", %").append(nextSR).append(" ]\n");
        return new LLVMValue(phi, "%cang_lang_Object*");
    }

    /**
     * Field.list(): load the bound target, null-check it, read the runtime typeId, then
     * call @cang.field.list to build a String[] (standard array ABI) of every field name
     * registered for that type in the metadata table — parent fields first, own fields
     * after (the same order collectClass records them). Field name pointers are shared
     * with the metadata table (compile-time string constants), not copied.
     */
    private LLVMValue generateFieldList(ClassInfo fieldCi, LLVMValue recv, MethodCallExpr node) {
        int id = labelCount++;
        String nx = "fa.list." + id;

        String recvBase = fieldCi.llvmName.substring(1);
        int tgtOff = fieldCi.fieldIndices.get("target");
        String tgtPtr = "%fa.tp." + tmpCount++;
        body.append("  ").append(tgtPtr).append(" = getelementptr %").append(recvBase)
             .append(", %").append(recvBase).append("* ").append(recv.value)
             .append(", i32 0, i32 ").append(tgtOff).append("\n");
        String tgt = "%fa.tgt." + tmpCount++;
        body.append("  ").append(tgt).append(" = load %cang_lang_Object*, %cang_lang_Object** ").append(tgtPtr).append("\n");
        String tNull = "%fa.tn." + tmpCount++;
        body.append("  ").append(tNull).append(" = icmp eq %cang_lang_Object* ").append(tgt).append(", null\n");
        String errT = nx + ".nulltgt", okT = nx + ".tgt";
        body.append("  br i1 ").append(tNull).append(", label %").append(errT).append(", label %").append(okT).append("\n");
        body.append(errT).append(":\n");
        emitRuntimeError("Field.list: target object is null", node.line, okT);
        body.append(okT).append(":\n");

        String tidPtr = "%fa.tidp." + tmpCount++;
        body.append("  ").append(tidPtr).append(" = getelementptr %cang_lang_Object, %cang_lang_Object* ")
             .append(tgt).append(", i32 0, i32 0\n");
        String tid = "%fa.tid." + tmpCount++;
        body.append("  ").append(tid).append(" = load i32, i32* ").append(tidPtr).append("\n");
        emitFieldMeta();
        emitFieldListHelper();
        String arr = "%fa.arr." + tmpCount++;
        body.append("  ").append(arr).append(" = call i8* @cang.field.list(i32 ").append(tid).append(")\n");
        return new LLVMValue(arr, "i8*", "Array<String>");
    }

    /**
     * Class.newInstance(): read the receiver's _typeId and dispatch through
     * @cang.newinstance (no args) or @cang.newinstance.args (one Object / Object[]).
     * A null result means no public constructor matched; it is reported as a catchable
     * runtime Error carrying the class name. The constructed pointer is handed back as
     * %cang_lang_Object* so Object intrinsics keep using the runtime typeId.
     */
    private LLVMValue generateClassNewInstance(ClassInfo classCi, LLVMValue recv, MethodCallExpr node) {
        if (node.args.size() > 1) {
            throw new RuntimeException("Class.newInstance expects zero, one Object, or one Object[] argument (at line "
                + node.line + ")");
        }
        int id = labelCount++;
        String nx = "ni." + id;

        // Load _typeId from the Class object.
        Integer tidIdx = classCi.fieldIndices.get("_typeId");
        if (tidIdx == null) {
            throw new RuntimeException("cang/lang/Class must declare an 'int _typeId' field");
        }
        String tidPtr = "%ni.tidp." + tmpCount++;
        body.append("  ").append(tidPtr).append(" = getelementptr ").append(classCi.llvmName)
             .append(", ").append(classCi.llvmName).append("* ").append(recv.value)
             .append(", i32 0, i32 ").append(tidIdx).append("\n");
        String tid = "%ni.tid." + tmpCount++;
        body.append("  ").append(tid).append(" = load i32, i32* ").append(tidPtr).append("\n");

        boolean hasCtorArgs = !node.args.isEmpty();
        if (!hasCtorArgs) {
            emitNewInstanceHelper();
            String raw = "%ni.raw." + tmpCount++;
            body.append("  ").append(raw).append(" = call i8* @cang.newinstance(i32 ")
                 .append(tid).append(")\n");
            emitPostCallUnwindCheck();
            return finishClassNewInstance(classCi, recv, node, raw, false);
        }

        emitNewInstanceArgsHelper();
        AST argNode = node.args.get(0);
        LLVMValue arrayVal = null;
        LLVMValue oneVal = null;
        if (isNewInstanceArrayArg(argNode)) {
            arrayVal = generateNewInstanceArrayArg(argNode, node.line);
        } else {
            oneVal = generateExpr(argNode);
            if (oneVal.semanticType != null && "Array<Object>".equals(oneVal.semanticType)) {
                arrayVal = oneVal;
                oneVal = null;
            }
        }

        String raw;
        if (arrayVal != null) {
            String isNull = "%ni.anull." + tmpCount++;
            body.append("  ").append(isNull).append(" = icmp eq i8* ").append(arrayVal.value).append(", null\n");
            String nullErr = nx + ".argnull";
            String argsOk = nx + ".argok";
            body.append("  br i1 ").append(isNull).append(", label %").append(nullErr)
                 .append(", label %").append(argsOk).append("\n");
            body.append(nullErr).append(":\n");
            emitRuntimeError("Class.newInstance: arguments array is null", node.line, argsOk);
            body.append(argsOk).append(":\n");
            String len64 = "%ni.len64." + tmpCount++;
            body.append("  ").append(len64).append(" = load i64, i64* ").append(arrayVal.value).append("\n");
            String len32 = "%ni.len." + tmpCount++;
            body.append("  ").append(len32).append(" = trunc i64 ").append(len64).append(" to i32\n");
            String data = "%ni.argdata." + tmpCount++;
            body.append("  ").append(data).append(" = getelementptr i8, i8* ")
                 .append(arrayVal.value).append(", i64 8\n");
            String argv = "%ni.argv." + tmpCount++;
            body.append("  ").append(argv).append(" = bitcast i8* ").append(data).append(" to i8**\n");
            raw = "%ni.raw." + tmpCount++;
            body.append("  ").append(raw).append(" = call i8* @cang.newinstance.args(i32 ")
                 .append(tid).append(", i32 ").append(len32).append(", i8** ").append(argv).append(")\n");
        } else {
            if (!oneVal.type.endsWith("*")) {
                throw new RuntimeException("Class.newInstance(Object) expects an Object/reference argument; "
                    + "primitives must be boxed first (at line " + node.line + ")");
            }
            String argI8 = oneVal.value;
            if (!"i8*".equals(oneVal.type)) {
                argI8 = "%ni.argcast." + tmpCount++;
                body.append("  ").append(argI8).append(" = bitcast ").append(oneVal.type).append(" ")
                     .append(oneVal.value).append(" to i8*\n");
            }
            String slot = "%ni.argslot." + tmpCount++;
            body.append("  ").append(slot).append(" = alloca i8*\n");
            body.append("  store i8* ").append(argI8).append(", i8** ").append(slot).append("\n");
            raw = "%ni.raw." + tmpCount++;
            body.append("  ").append(raw).append(" = call i8* @cang.newinstance.args(i32 ")
                 .append(tid).append(", i32 1, i8** ").append(slot).append(")\n");
        }
        emitPostCallUnwindCheck();
        return finishClassNewInstance(classCi, recv, node, raw, true);
    }

    /** Shared null-check / catchable-error tail for Class.newInstance(). */
    private LLVMValue finishClassNewInstance(ClassInfo classCi, LLVMValue recv, MethodCallExpr node,
                                             String raw, boolean argsForm) {
        int id = labelCount++;
        String nx = "ni." + id;
        String isNull = "%ni.isnull." + tmpCount++;
        body.append("  ").append(isNull).append(" = icmp eq i8* ").append(raw).append(", null\n");
        String errL = nx + ".fail", okL = nx + ".ok";
        body.append("  br i1 ").append(isNull).append(", label %").append(errL)
             .append(", label %").append(okL).append("\n");
        body.append(errL).append(":\n");
        Integer nameIdx = classCi.fieldIndices.get("_name");
        String namePtr = "%ni.namep." + tmpCount++;
        body.append("  ").append(namePtr).append(" = getelementptr ").append(classCi.llvmName)
             .append(", ").append(classCi.llvmName).append("* ").append(recv.value)
             .append(", i32 0, i32 ").append(nameIdx).append("\n");
        String nameV = "%ni.name." + tmpCount++;
        body.append("  ").append(nameV).append(" = load i8*, i8** ").append(namePtr).append("\n");
        String format = argsForm
            ? "Class.newInstance: %s has no matching constructor for the given arguments"
            : "Class.newInstance: %s cannot be instantiated";
        emitRuntimeErrorDynamic(buildClassNewInstanceMsg(new LLVMValue(nameV, "i8*"), format),
            node.line, okL);
        body.append(okL).append(":\n");
        String obj = "%ni.obj." + tmpCount++;
        body.append("  ").append(obj).append(" = bitcast i8* ").append(raw).append(" to %cang_lang_Object*\n");
        return new LLVMValue(obj, "%cang_lang_Object*", "Object");
    }

    /**
     * True for an Object[] expression (the multi-argument form). Other array types are
     * single Object arguments, matching the declared overload: String[] is not Object[].
     * Array literals are always the multi-argument form.
     */
    private boolean isNewInstanceArrayArg(AST arg) {
        if (arg instanceof ArrayLit) return true;
        return "Object".equals(elemCangOf(arg));
    }

    /** Evaluate an Object[] argument; an array literal is generated with Object elements. */
    private LLVMValue generateNewInstanceArrayArg(AST arg, int line) {
        LLVMValue arr;
        if (arg instanceof ArrayLit) {
            arr = generateArrayLit((ArrayLit) arg, toLLVMType("Object"), "Object");
        } else {
            arr = generateExpr(arg);
        }
        if (!"i8*".equals(arr.type)) {
            throw new RuntimeException("Class.newInstance argument array must be Object[] (at line "
                + line + ")");
        }
        return arr;
    }

    /** sprintf-style Class.newInstance failure message with the runtime class name. */
    private LLVMValue buildClassNewInstanceMsg(LLVMValue nameV, String format) {
        if (!header.toString().contains("declare i32 @sprintf(")) {
            header.append("declare i32 @sprintf(i8*, i8*, ...)\n");
        }
        LLVMValue fmtV = makeStringConstant(format);
        String flen = "%nim.flen." + tmpCount++;
        body.append("  ").append(flen).append(" = call i64 @strlen(i8* ").append(fmtV.value).append(")\n");
        String nlen = "%nim.nlen." + tmpCount++;
        body.append("  ").append(nlen).append(" = call i64 @strlen(i8* ").append(nameV.value).append(")\n");
        // fmt is %s-expanded: rendered length = flen - 2 + nlen, plus the NUL.
        String sz = "%nim.sz." + tmpCount++;
        body.append("  ").append(sz).append(" = add i64 ").append(flen).append(", ").append(nlen).append("\n");
        String sz2 = "%nim.sz2." + tmpCount++;
        body.append("  ").append(sz2).append(" = sub i64 ").append(sz).append(", 1\n");
        String buf = "%nim.buf." + tmpCount++;
        body.append("  ").append(buf).append(" = call i8* @").append(allocFn()).append("(i64 ").append(sz2).append(")\n");
        String n2 = "%nim.call." + tmpCount++;
        body.append("  ").append(n2).append(" = call i32 (i8*, i8*, ...) @sprintf(i8* ").append(buf)
             .append(", i8* ").append(fmtV.value).append(", i8* ").append(nameV.value).append(")\n");
        return new LLVMValue(buf, "i8*");
    }

    /**
     * sprintf-style managed message builder for Field accessors: renders
     * "Field.<method>: <verb>" where the runtime field name is embedded via %s.
     */
    private LLVMValue buildFieldErrorMsg(String method, String verb, LLVMValue nameV) {
        if (!header.toString().contains("declare i32 @sprintf(")) {
            header.append("declare i32 @sprintf(i8*, i8*, ...)\n");
        }
        LLVMValue fmtV = makeStringConstant("Field." + method + ": " + verb);
        String flen = "%fe.flen." + tmpCount++;
        body.append("  ").append(flen).append(" = call i64 @strlen(i8* ").append(fmtV.value).append(")\n");
        String nlen = "%fe.nlen." + tmpCount++;
        body.append("  ").append(nlen).append(" = call i64 @strlen(i8* ").append(nameV.value).append(")\n");
        // fmt is %s-expanded: rendered length = flen - 2 + nlen, plus the NUL.
        String sz = "%fe.sz." + tmpCount++;
        body.append("  ").append(sz).append(" = add i64 ").append(flen).append(", ").append(nlen).append("\n");
        String sz2 = "%fe.sz2." + tmpCount++;
        body.append("  ").append(sz2).append(" = sub i64 ").append(sz).append(", 1\n");
        String buf = "%fe.buf." + tmpCount++;
        body.append("  ").append(buf).append(" = call i8* @").append(allocFn()).append("(i64 ").append(sz2).append(")\n");
        String n2 = "%fe.call." + tmpCount++;
        body.append("  ").append(n2).append(" = call i32 (i8*, i8*, ...) @sprintf(i8* ").append(buf)
             .append(", i8* ").append(fmtV.value).append(", i8* ").append(nameV.value).append(")\n");
        return new LLVMValue(buf, "i8*");
    }

    /**
     * Runtime error with a runtime-built message: wraps the message string into an
     * Error(message) object (hand-allocated, matching new Error(msg) — message field set,
     * stack initialized empty on non-null allocation) so catch handlers see the real
     * message; without the Error class the uncaught printer prints the value directly.
     */
    private void emitRuntimeErrorDynamic(LLVMValue msgValue, int line, String continuationLabel) {
        ClassInfo errorInfo = classes.get("Error");
        String funcName = errorInfo != null ? "cang_lang_Error.constructor" : null;
        FuncInfo oneArg = null;
        if (errorInfo != null) {
            List<FuncInfo> grp = overloadGroups.get(funcName);
            if (grp != null) {
                for (FuncInfo fi : grp) {
                    if (fi.paramTypes.size() == 1 && "String".equals(fi.paramTypes.get(0))) {
                        oneArg = fi;
                        break;
                    }
                }
            }
        }
        if (errorInfo == null || oneArg == null) {
            // No Error type: print + exit directly, then keep the dead-block contract.
            emitUncaughtErrorWith(msgValue, line);
            body.append("runtime.error.dead.").append(runtimeErrorCount++).append(":\n");
            body.append("  br label %").append(continuationLabel).append("\n");
            return;
        }
        // Heap-allocate an Error, set its typeId, hand it to the init(String) constructor.
        String sizeReg = "%fe.size." + tmpCount++;
        body.append("  ").append(sizeReg).append(" = getelementptr ").append(errorInfo.llvmName)
             .append(", ").append(errorInfo.llvmName).append("* null, i32 1\n");
        String sizeVal = "%fe.sizeval." + tmpCount++;
        body.append("  ").append(sizeVal).append(" = ptrtoint ").append(errorInfo.llvmName)
             .append("* ").append(sizeReg).append(" to i64\n");
        String raw = "%fe.errraw." + tmpCount++;
        body.append("  ").append(raw).append(" = call i8* @").append(allocFn()).append("(i64 ").append(sizeVal).append(")\n");
        String errPtr = "%fe.errptr." + tmpCount++;
        body.append("  ").append(errPtr).append(" = bitcast i8* ").append(raw).append(" to ")
             .append(errorInfo.llvmName).append("*\n");
        String tidp = "%fe.tidp." + tmpCount++;
        body.append("  ").append(tidp).append(" = getelementptr ").append(errorInfo.llvmName)
             .append(", ").append(errorInfo.llvmName).append("* ").append(errPtr).append(", i32 0, i32 0\n");
        body.append("  store i32 ").append(errorInfo.typeId).append(", i32* ").append(tidp).append("\n");
        body.append("  call void @").append(oneArg.llvmName).append("(").append(errorInfo.llvmName)
             .append("* ").append(errPtr).append(", i8* ").append(msgValue.value).append(")\n");
        String errI8 = "%fe.erri8." + tmpCount++;
        body.append("  ").append(errI8).append(" = bitcast ").append(errorInfo.llvmName).append("* ")
             .append(errPtr).append(" to i8*\n");
        body.append("  store i8* ").append(errI8).append(", i8** @cang.current.error\n");
        emitUnwindOrHandler(line);
        body.append("runtime.error.dead.").append(runtimeErrorCount++).append(":\n");
        body.append("  br label %").append(continuationLabel).append("\n");
    }

    /**
     * Boxed-primitive bit loader, used by Field.getObject: given a metadata display name
     * (int/long/byte/bool/float/double — the caller has already matched one of them) and
     * the field byte address, returns the field's bits widened into an i64. Kind selection
     * happens at runtime by name length + first byte ('long' vs 'byte'/'bool'); float bits
     * ride through an i32 bitcast, double straight to i64.
     */
    private void emitBoxPrimHelper() {
        if (header.toString().contains("define i64 @cang.boxprim(")) return;
        if (!header.toString().contains("declare i64 @strlen(")) {
            header.append("declare i64 @strlen(i8*)\n");
        }
        header.append("define i64 @cang.boxprim(i8* %disp, i8* %addr) {\n");
        header.append("entry:\n");
        header.append("  %len = call i64 @strlen(i8* %disp)\n");
        // len 3 → "int" → load i32
        header.append("  %l3 = icmp eq i64 %len, 3\n");
        header.append("  br i1 %l3, label %ldi32, label %chkl\n");
        header.append("chkl:\n");
        // len 4 with first byte 'l' → "long" → load i64; len 4 otherwise → byte/bool → i8
        header.append("  %b0 = load i8, i8* %disp\n");
        header.append("  %l4 = icmp eq i64 %len, 4\n");
        header.append("  %isl = icmp eq i8 %b0, 108\n");
        header.append("  %gol = and i1 %l4, %isl\n");
        header.append("  br i1 %gol, label %ldi64, label %chkt\n");
        header.append("chkt:\n");
        header.append("  br i1 %l4, label %ldi8, label %chkf\n");
        header.append("chkf:\n");
        // len 5 → "float"; len 6 → "double" (both pre-verified by the caller).
        header.append("  %l5 = icmp eq i64 %len, 5\n");
        header.append("  br i1 %l5, label %ldf, label %ldd\n");
        header.append("ldi32:\n");
        header.append("  %p32 = bitcast i8* %addr to i32*\n");
        header.append("  %v32 = load i32, i32* %p32\n");
        header.append("  %w32 = sext i32 %v32 to i64\n");
        header.append("  br label %out\n");
        header.append("ldi64:\n");
        header.append("  %p64 = bitcast i8* %addr to i64*\n");
        header.append("  %w64 = load i64, i64* %p64\n");
        header.append("  br label %out\n");
        header.append("ldi8:\n");
        header.append("  %p8 = bitcast i8* %addr to i8*\n");
        header.append("  %v8 = load i8, i8* %p8\n");
        header.append("  %w8 = sext i8 %v8 to i64\n");
        header.append("  br label %out\n");
        header.append("ldf:\n");
        header.append("  %pf = bitcast i8* %addr to float*\n");
        header.append("  %vf = load float, float* %pf\n");
        header.append("  %bf = bitcast float %vf to i32\n");
        header.append("  %wf = zext i32 %bf to i64\n");
        header.append("  br label %out\n");
        header.append("ldd:\n");
        header.append("  %pd = bitcast i8* %addr to double*\n");
        header.append("  %vd = load double, double* %pd\n");
        header.append("  %wd = bitcast double %vd to i64\n");
        header.append("  br label %out\n");
        header.append("out:\n");
        header.append("  %w = phi i64 [ %w32, %ldi32 ], [ %w64, %ldi64 ], [ %w8, %ldi8 ]")
             .append(", [ %wf, %ldf ], [ %wd, %ldd ]\n");
        header.append("  ret i64 %w\n");
        header.append("}\n\n");
    }
    /**
     * Allocate + initialize an intrinsic object-style Thread object (daemon flag, optional
     * seeded task, birth parent/id). Shared by new Thread(...) and Thread-subclass start().
     */
    private LLVMValue newThreadObject(String daemonVal, LLVMValue taskFn) {
        String raw = "%thr.obj.raw." + tmpCount++;
        body.append("  ").append(raw).append(" = call i8* @").append(allocFn()).append("(i64 48)\n");
        String obj = "%thr.obj." + tmpCount++;
        body.append("  ").append(obj).append(" = bitcast i8* ").append(raw).append(" to %CangThreadObj*\n");
        for (int f = 0; f < 3; f++) {
            String fp = "%thr.obj.f." + tmpCount++;
            body.append("  ").append(fp).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(obj)
                 .append(", i32 0, i32 ").append(f).append("\n");
            body.append("  store i8* null, i8** ").append(fp).append("\n");
        }
        if (taskFn != null) {
            emitThreadTaskFields(new LLVMValue(obj, "%CangThreadObj*"), taskFn, "thr.newtask");
        }
        String dptr = "%thr.obj.daemon." + tmpCount++;
        body.append("  ").append(dptr).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(obj)
             .append(", i32 0, i32 3\n");
        body.append("  store i1 ").append(daemonVal).append(", i1* ").append(dptr).append("\n");
        // Parent = this (creating) thread's self handle — materialized BEFORE the child id
        // so a parent's id is always lower than its children's (and main's first child
        // gives main id 0). Also makes parent identity match the creator's currentThread().
        LLVMValue parentSelf = ensureThreadSelf();
        String parentCast = "%thr.obj.pcast." + tmpCount++;
        body.append("  ").append(parentCast).append(" = bitcast %CangThreadHandle* ").append(parentSelf.value)
             .append(" to i8*\n");
        String pptr = "%thr.obj.pp." + tmpCount++;
        body.append("  ").append(pptr).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(obj)
             .append(", i32 0, i32 5\n");
        body.append("  store i8* ").append(parentCast).append(", i8** ").append(pptr).append("\n");
        String threadId = emitThreadIdAlloc();
        String iptr = "%thr.obj.idp." + tmpCount++;
        body.append("  ").append(iptr).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(obj)
             .append(", i32 0, i32 4\n");
        body.append("  store i32 ").append(threadId).append(", i32* ").append(iptr).append("\n");
        return new LLVMValue(obj, "%CangThreadObj*", "Thread<void>");
    }

    /**
     * Thread-subclass start(): build an internal adapter Thread object whose task is a thunk
     * calling the receiver's run() (resolved on its static class chain), then run the normal
     * object-style start. Returns the Thread<void> handle.
     */
    private LLVMValue generateSubclassStart(LLVMValue receiver, ClassInfo subCi, MethodCallExpr node) {
        if (!node.args.isEmpty()) {
            throw new RuntimeException("Thread.start accepts no arguments (at line " + node.line + ")");
        }
        FuncInfo runFi = null;
        List<FuncInfo> cands = lookupInstanceMethods(subCi, "run");
        if (cands != null) {
            for (FuncInfo f : cands) {
                if (f.paramTypes.isEmpty()) { runFi = f; break; }
            }
        }
        if (runFi == null || "cang_lang_Thread".equals(runFi.className)) {
            throw new RuntimeException("Thread subclass '" + subCi.simpleName
                + "' must override run() before start() (at line " + node.line + ")");
        }
        if (!"void".equals(runFi.returnType)) {
            throw new RuntimeException("Thread subclass '" + subCi.simpleName
                + "': run() must return void (at line " + node.line + ")");
        }
        String[] bv = classPtrBaseAndValue(receiver);
        LLVMValue adapter = newThreadObject("false", null);
        String thunk = emitThunk(runFi, "%" + bv[0] + "*");
        String codePtr = "%subt.code." + tmpCount++;
        body.append("  ").append(codePtr).append(" = bitcast void (i8*)* ").append(thunk).append(" to i8*\n");
        String cfp = "%subt.cf." + tmpCount++;
        body.append("  ").append(cfp).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(adapter.value)
             .append(", i32 0, i32 0\n");
        body.append("  store i8* ").append(codePtr).append(", i8** ").append(cfp).append("\n");
        String recvCast = "%subt.rc." + tmpCount++;
        body.append("  ").append(recvCast).append(" = bitcast %").append(bv[0]).append("* ").append(bv[1]).append(" to i8*\n");
        String rfp = "%subt.rf." + tmpCount++;
        body.append("  ").append(rfp).append(" = getelementptr %CangThreadObj, %CangThreadObj* ").append(adapter.value)
             .append(", i32 0, i32 1\n");
        body.append("  store i8* ").append(recvCast).append(", i8** ").append(rfp).append("\n");
        return generateThreadObjStart(adapter, node);
    }

    /** Receiver's class if its inheritance chain reaches Thread (the receiver class included). */
    private ClassInfo threadSubclassInfo(LLVMValue r) {
        if (r.type == null || !r.type.startsWith("%") || !r.type.endsWith("*")) return null;
        ClassInfo own = classes.get(extractClassName(r.type));
        if (own == null) return null;
        ClassInfo ci = own;
        int guard = 0;
        while (ci != null && guard++ < 64) {
            if ("cang_lang_Thread".equals(ci.fullName)) return own;
            ci = ci.parentName != null ? classes.get(ci.parentName) : null;
        }
        return null;
    }

    /** True when the chain defines a run() body of its own (Thread.run is a native marker). */
    private boolean chainHasUserRun(ClassInfo ci) {
        List<FuncInfo> grp = lookupInstanceMethods(ci, "run");
        if (grp != null) {
            for (FuncInfo f : grp) {
                if (!"cang_lang_Thread".equals(f.className)) return true;
            }
        }
        return false;
    }

    // ==================== Coroutine (libuv single-thread event loop) ====================

    /**
     * libuv runtime for cang/lang/Coroutine — emitted lazily on first Coroutine use.
     * A coroutine body runs on whichever thread pumps the loop (join()/main-exit drain),
     * on the uv_loop via a 0ms one-shot timer. The timer block is [i8* co | 8 pad |
     * over-sized uv_timer_t] so the shared callback recovers the co from timer-8 —
     * no uv struct field offsets are assumed. The block is plain malloc (uv_close frees).
     */
    private void emitCoroutineRuntime() {
        if (header.toString().contains("@cang.co.loop = ")) return;
        cc.ruok.cang.Cang.needsLibuv = true;
        header.append("@cang.co.loop = internal global i8* null\n");
        header.append("@cang.co.seq = internal global i32 0\n");
        header.append("@cang.co.current = internal global i8* null\n");
        header.append("@cang.co.regs = internal global [256 x i8*] zeroinitializer\n");
        header.append("@cang.co.regc = internal global i64 0\n");
        header.append("declare i8* @uv_default_loop()\n");
        header.append("declare i32 @uv_timer_init(i8*, i8*)\n");
        header.append("declare i32 @uv_timer_start(i8*, void (i8*)*, i64, i64)\n");
        header.append("declare void @uv_close(i8*, void (i8*)*)\n");
        header.append("declare i32 @uv_run(i8*, i32)\n");
        // Shared timer callback: recover co from timer-8, run the task, mark done, close.
        header.append("define void @cang.co.entry(i8* %timer) {\n");
        header.append("entry:\n");
        header.append("  %base = getelementptr i8, i8* %timer, i64 -8\n");
        header.append("  %coRawp = bitcast i8* %base to i8**\n");
        header.append("  %coRaw = load i8*, i8** %coRawp\n");
        header.append("  %prev = load i8*, i8** @cang.co.current\n");
        header.append("  store i8* %coRaw, i8** @cang.co.current\n");
        header.append("  %co = bitcast i8* %coRaw to %CangCo*\n");
        header.append("  %cf = getelementptr %CangCo, %CangCo* %co, i32 0, i32 0\n");
        header.append("  %code = load i8*, i8** %cf\n");
        header.append("  %rf = getelementptr %CangCo, %CangCo* %co, i32 0, i32 1\n");
        header.append("  %recv = load i8*, i8** %rf\n");
        header.append("  %fp = bitcast i8* %code to void (i8*)*\n");
        header.append("  call void %fp(i8* %recv)\n");
        header.append("  %dp = getelementptr %CangCo, %CangCo* %co, i32 0, i32 2\n");
        header.append("  store i32 1, i32* %dp\n");
        header.append("  store i8* %prev, i8** @cang.co.current\n");
        header.append("  call void @uv_close(i8* %timer, void (i8*)* @cang.co.close)\n");
        header.append("  ret void\n");
        header.append("}\n\n");
        header.append("define void @cang.co.close(i8* %h) {\n");
        header.append("entry:\n");
        header.append("  %base = getelementptr i8, i8* %h, i64 -8\n");
        header.append("  call void @free(i8* %base)\n");
        header.append("  ret void\n");
        header.append("}\n\n");
    }

    /** The uv_loop, created on first use (stored in @cang.co.loop). */
    private String ensureCoroutineLoop() {
        emitCoroutineRuntime();
        int id = labelCount++;
        String create = "coloop.create." + id;
        String done = "coloop.done." + id;
        String cur = "%coloop.cur." + tmpCount++;
        body.append("  ").append(cur).append(" = load i8*, i8** @cang.co.loop\n");
        String isN = "%coloop.isn." + tmpCount++;
        body.append("  ").append(isN).append(" = icmp eq i8* ").append(cur).append(", null\n");
        body.append("  br i1 ").append(isN).append(", label %").append(create).append(", label %").append(done).append("\n");
        body.append(create).append(":\n");
        String lp = "%coloop.lp." + tmpCount++;
        body.append("  ").append(lp).append(" = call i8* @uv_default_loop()\n");
        body.append("  store i8* ").append(lp).append(", i8** @cang.co.loop\n");
        body.append("  br label %").append(done).append("\n");
        body.append(done).append(":\n");
        String res = "%coloop.res." + tmpCount++;
        body.append("  ").append(res).append(" = load i8*, i8** @cang.co.loop\n");
        return res;
    }

    /** Pump uv_run(UV_RUN_ONCE) until the co's done flag is 1 (no self-join check). */
    private void emitCoroutinePumpUntilDone(String coValue, int labelBase) {
        String condL = "copump.cond." + labelBase;
        String bodyL = "copump.run." + labelBase;
        String doneL = "copump.done." + labelBase;
        String dp = "%copump.dp." + tmpCount++;
        body.append("  ").append(dp).append(" = getelementptr %CangCo, %CangCo* ").append(coValue)
             .append(", i32 0, i32 2\n");
        body.append("  br label %").append(condL).append("\n");
        body.append(condL).append(":\n");
        String dv = "%copump.dv." + tmpCount++;
        body.append("  ").append(dv).append(" = load i32, i32* ").append(dp).append("\n");
        String isDone = "%copump.isd." + tmpCount++;
        body.append("  ").append(isDone).append(" = icmp eq i32 ").append(dv).append(", 1\n");
        body.append("  br i1 ").append(isDone).append(", label %").append(doneL).append(", label %").append(bodyL).append("\n");
        body.append(bodyL).append(":\n");
        String lp = "%copump.lp." + tmpCount++;
        body.append("  ").append(lp).append(" = load i8*, i8** @cang.co.loop\n");
        // UV_RUN_ONCE = 1
        body.append("  call i32 @uv_run(i8* ").append(lp).append(", i32 1)\n");
        body.append("  br label %").append(condL).append("\n");
        body.append(doneL).append(":\n");
    }

    /** Non-daemon started coroutines: drain at main exit (mirrors the thread registry join). */
    private void emitCoroutineRegistryJoin() {
        if (!header.toString().contains("@cang.co.loop = ")) return;
        String cnt = "%coreg.cnt." + tmpCount++;
        body.append("  ").append(cnt).append(" = load i64, i64* @cang.co.regc\n");
        int id = labelCount++;
        String condL = "coreg.cond." + id;
        String bodyL = "coreg.body." + id;
        String doneL = "coreg.done." + id;
        String iPtr = "%coreg.ip." + tmpCount++;
        body.append("  ").append(iPtr).append(" = alloca i64\n  store i64 0, i64* ").append(iPtr).append("\n");
        body.append("  br label %").append(condL).append("\n");
        body.append(condL).append(":\n");
        String i = "%coreg.i." + tmpCount++;
        body.append("  ").append(i).append(" = load i64, i64* ").append(iPtr).append("\n");
        String has = "%coreg.has." + tmpCount++;
        body.append("  ").append(has).append(" = icmp ult i64 ").append(i).append(", ").append(cnt).append("\n");
        body.append("  br i1 ").append(has).append(", label %").append(bodyL).append(", label %").append(doneL).append("\n");
        body.append(bodyL).append(":\n");
        String slot = "%coreg.slot." + tmpCount++;
        body.append("  ").append(slot).append(" = getelementptr [256 x i8*], [256 x i8*]* @cang.co.regs, i64 0, i64 ").append(i).append("\n");
        String raw = "%coreg.raw." + tmpCount++;
        body.append("  ").append(raw).append(" = load i8*, i8** ").append(slot).append("\n");
        String co = "%coreg.co." + tmpCount++;
        body.append("  ").append(co).append(" = bitcast i8* ").append(raw).append(" to %CangCo*\n");
        int sub = labelCount++;
        emitCoroutinePumpUntilDone(co, sub);
        String next = "%coreg.next." + tmpCount++;
        body.append("  ").append(next).append(" = add i64 ").append(i).append(", 1\n");
        body.append("  store i64 ").append(next).append(", i64* ").append(iPtr).append("\n");
        body.append("  br label %").append(condL).append("\n");
        body.append(doneL).append(":\n");
    }

    /** Task field store for a %CangCo receiver (code/recv at fields 0/1). */
    private void emitCoroutineTaskFields(LLVMValue co, LLVMValue fn) {
        String code = "%cotask.code." + tmpCount++;
        body.append("  ").append(code).append(" = extractvalue %CangFunction ").append(fn.value).append(", 0\n");
        String recv = "%cotask.recv." + tmpCount++;
        body.append("  ").append(recv).append(" = extractvalue %CangFunction ").append(fn.value).append(", 1\n");
        String cfield = "%cotask.cf." + tmpCount++;
        body.append("  ").append(cfield).append(" = getelementptr %CangCo, %CangCo* ").append(co.value)
             .append(", i32 0, i32 0\n");
        body.append("  store i8* ").append(code).append(", i8** ").append(cfield).append("\n");
        String rfield = "%cotask.rf." + tmpCount++;
        body.append("  ").append(rfield).append(" = getelementptr %CangCo, %CangCo* ").append(co.value)
             .append(", i32 0, i32 1\n");
        body.append("  store i8* ").append(recv).append(", i8** ").append(rfield).append("\n");
    }

    /** new Coroutine(...) — mirrors new Thread(): optional daemon bool or seeded Function<void>. */
    private LLVMValue newCoroutineObject(String daemonVal, LLVMValue taskFn) {
        emitCoroutineRuntime();
        String raw = "%co.obj.raw." + tmpCount++;
        body.append("  ").append(raw).append(" = call i8* @").append(allocFn()).append("(i64 48)\n");
        String co = "%co.obj." + tmpCount++;
        body.append("  ").append(co).append(" = bitcast i8* ").append(raw).append(" to %CangCo*\n");
        for (int f = 0; f < 2; f++) {
            String fp = "%co.obj.f." + tmpCount++;
            body.append("  ").append(fp).append(" = getelementptr %CangCo, %CangCo* ").append(co)
                 .append(", i32 0, i32 ").append(f).append("\n");
            body.append("  store i8* null, i8** ").append(fp).append("\n");
        }
        String donePtr = "%co.obj.done." + tmpCount++;
        body.append("  ").append(donePtr).append(" = getelementptr %CangCo, %CangCo* ").append(co)
             .append(", i32 0, i32 2\n");
        body.append("  store i32 0, i32* ").append(donePtr).append("\n");
        String dptr = "%co.obj.daemon." + tmpCount++;
        body.append("  ").append(dptr).append(" = getelementptr %CangCo, %CangCo* ").append(co)
             .append(", i32 0, i32 3\n");
        body.append("  store i1 ").append(daemonVal).append(", i1* ").append(dptr).append("\n");
        String idPtr = "%co.obj.idp." + tmpCount++;
        body.append("  ").append(idPtr).append(" = getelementptr %CangCo, %CangCo* ").append(co)
             .append(", i32 0, i32 4\n");
        String threadId = "%co.seq.id." + tmpCount++;
        body.append("  ").append(threadId).append(" = atomicrmw add i32* @cang.co.seq, i32 1 seq_cst\n");
        body.append("  store i32 ").append(threadId).append(", i32* ").append(idPtr).append("\n");
        String blockPtr = "%co.obj.bp." + tmpCount++;
        body.append("  ").append(blockPtr).append(" = getelementptr %CangCo, %CangCo* ").append(co)
             .append(", i32 0, i32 5\n");
        body.append("  store i8* null, i8** ").append(blockPtr).append("\n");
        if (taskFn != null) {
            emitCoroutineTaskFields(new LLVMValue(co, "%CangCo*"), taskFn);
        }
        return new LLVMValue(co, "%CangCo*", "Coroutine");
    }

    /** Coroutine.task(fn): validate Function<void>, store { code, receiver }; returns this. */
    private LLVMValue generateCoroutineTask(LLVMValue co, MethodCallExpr node) {
        if (node.args.size() != 1) {
            throw new RuntimeException("Coroutine.task accepts exactly one function argument (at line " + node.line + ")");
        }
        String saved = expectedFunctionType;
        expectedFunctionType = "Function<void>";
        LLVMValue fn;
        try {
            fn = generateExpr(node.args.get(0));
        } finally {
            expectedFunctionType = saved;
        }
        validateThreadTaskFn(fn, node.line, "Coroutine.task");
        emitCoroutineTaskFields(co, fn);
        return co;
    }

    /** Coroutine.setDaemon(b): only legal before start(); returns this. */
    private LLVMValue generateCoroutineSetDaemon(LLVMValue co, MethodCallExpr node) {
        if (node.args.size() != 1) {
            throw new RuntimeException("Coroutine.setDaemon expects one bool argument (at line " + node.line + ")");
        }
        LLVMValue flag = generateExpr(node.args.get(0));
        if (!flag.type.equals("i1")) {
            throw new RuntimeException("Coroutine.setDaemon flag must be a bool (at line " + node.line + ")");
        }
        String blockPtr = "%co.setd.bp." + tmpCount++;
        body.append("  ").append(blockPtr).append(" = getelementptr %CangCo, %CangCo* ").append(co.value)
             .append(", i32 0, i32 5\n");
        String block = "%co.setd.b." + tmpCount++;
        body.append("  ").append(block).append(" = load i8*, i8** ").append(blockPtr).append("\n");
        String started = "%co.setd.st." + tmpCount++;
        body.append("  ").append(started).append(" = icmp ne i8* ").append(block).append(", null\n");
        int id = labelCount++;
        String badL = "co.setdaemon.started." + id;
        String okL = "co.setdaemon.ok." + id;
        body.append("  br i1 ").append(started).append(", label %").append(badL).append(", label %").append(okL).append("\n");
        body.append(badL).append(":\n");
        emitRuntimeError("Coroutine already started: setDaemon must be called before start", node.line, okL);
        body.append(okL).append(":\n");
        String dptr = "%co.setd.dp." + tmpCount++;
        body.append("  ").append(dptr).append(" = getelementptr %CangCo, %CangCo* ").append(co.value)
             .append(", i32 0, i32 3\n");
        body.append("  store i1 ").append(flag.value).append(", i1* ").append(dptr).append("\n");
        return co;
    }

    /** Coroutine.start(): schedule the task as a 0ms one-shot uv timer; returns this (for .join()). */
    private LLVMValue generateCoroutineStart(LLVMValue co, MethodCallExpr node) {
        if (!node.args.isEmpty()) {
            throw new RuntimeException("Coroutine.start accepts no arguments (at line " + node.line + ")");
        }
        String blockPtr = "%co.start.bp." + tmpCount++;
        body.append("  ").append(blockPtr).append(" = getelementptr %CangCo, %CangCo* ").append(co.value)
             .append(", i32 0, i32 5\n");
        String oldBlock = "%co.start.ob." + tmpCount++;
        body.append("  ").append(oldBlock).append(" = load i8*, i8** ").append(blockPtr).append("\n");
        int id = labelCount++;
        String already = "co.start.already." + id;
        String okL = "co.start.ok." + id;
        String cmp = "%co.start.cmp." + tmpCount++;
        body.append("  ").append(cmp).append(" = icmp ne i8* ").append(oldBlock).append(", null\n");
        body.append("  br i1 ").append(cmp).append(", label %").append(already).append(", label %").append(okL).append("\n");
        body.append(already).append(":\n");
        emitRuntimeError("Coroutine already started", node.line, okL);
        body.append(okL).append(":\n");
        String loop = ensureCoroutineLoop();
        // Timer block: [i8* co | pad to 8 | uv_timer_t (over-sized)] — malloc, freed by uv_close.
        String raw = "%co.start.raw." + tmpCount++;
        body.append("  ").append(raw).append(" = call i8* @malloc(i64 264)\n");
        String coSlot = "%co.start.slot." + tmpCount++;
        body.append("  ").append(coSlot).append(" = bitcast i8* ").append(raw).append(" to i8**\n");
        String coRaw = "%co.start.coraw." + tmpCount++;
        body.append("  ").append(coRaw).append(" = bitcast %CangCo* ").append(co.value).append(" to i8*\n");
        body.append("  store i8* ").append(coRaw).append(", i8** ").append(coSlot).append("\n");
        String timer = "%co.start.timer." + tmpCount++;
        body.append("  ").append(timer).append(" = getelementptr i8, i8* ").append(raw).append(", i64 8\n");
        String r1 = "%co.start.r1." + tmpCount++;
        body.append("  ").append(r1).append(" = call i32 @uv_timer_init(i8* ").append(loop).append(", i8* ").append(timer).append(")\n");
        String r2 = "%co.start.r2." + tmpCount++;
        body.append("  ").append(r2).append(" = call i32 @uv_timer_start(i8* ")
             .append(timer).append(", void (i8*)* @cang.co.entry, i64 0, i64 0)\n");
        body.append("  store i8* ").append(raw).append(", i8** ").append(blockPtr).append("\n");
        // Registry (non-daemon): drained at main exit like object-style threads.
        String dptr = "%co.start.dp." + tmpCount++;
        body.append("  ").append(dptr).append(" = getelementptr %CangCo, %CangCo* ").append(co.value)
             .append(", i32 0, i32 3\n");
        String daemon = "%co.start.daemon." + tmpCount++;
        body.append("  ").append(daemon).append(" = load i1, i1* ").append(dptr).append("\n");
        int regId = labelCount++;
        String doReg = "coreg.enter." + regId;
        String skipReg = "coreg.skip." + regId;
        body.append("  br i1 ").append(daemon).append(", label %").append(skipReg).append(", label %").append(doReg).append("\n");
        body.append(doReg).append(":\n");
        String regCnt = "%coreg.cnt." + tmpCount++;
        body.append("  ").append(regCnt).append(" = load i64, i64* @cang.co.regc\n");
        String regFull = "%coreg.full." + tmpCount++;
        String regOk = "coreg.ok." + regId;
        String regBad = "coreg.bad." + regId;
        body.append("  ").append(regFull).append(" = icmp uge i64 ").append(regCnt).append(", 256\n");
        body.append("  br i1 ").append(regFull).append(", label %").append(regBad).append(", label %").append(regOk).append("\n");
        body.append(regBad).append(":\n");
        emitFatalError("Too many live coroutines (registry full)", node.line, regOk);
        body.append(regOk).append(":\n");
        String regSlot = "%coreg.slot." + tmpCount++;
        body.append("  ").append(regSlot).append(" = getelementptr [256 x i8*], [256 x i8*]* @cang.co.regs, i64 0, i64 ")
             .append(regCnt).append("\n");
        body.append("  store i8* ").append(coRaw).append(", i8** ").append(regSlot).append("\n");
        String regCnt2 = "%coreg.cnt2." + tmpCount++;
        body.append("  ").append(regCnt2).append(" = add i64 ").append(regCnt).append(", 1\n");
        body.append("  store i64 ").append(regCnt2).append(", i64* @cang.co.regc\n");
        body.append("  br label %").append(skipReg).append("\n");
        body.append(skipReg).append(":\n");
        return co;
    }

    /** Coroutine.join(): pump uv_run until done. Not-started and self-join are errors. */
    private LLVMValue generateCoroutineJoin(LLVMValue co, MethodCallExpr node) {
        if (!node.args.isEmpty()) {
            throw new RuntimeException("Coroutine.join accepts no arguments (at line " + node.line + ")");
        }
        String blockPtr = "%co.join.bp." + tmpCount++;
        body.append("  ").append(blockPtr).append(" = getelementptr %CangCo, %CangCo* ").append(co.value)
             .append(", i32 0, i32 5\n");
        String block = "%co.join.b." + tmpCount++;
        body.append("  ").append(block).append(" = load i8*, i8** ").append(blockPtr).append("\n");
        String notStarted = "%co.join.ns." + tmpCount++;
        body.append("  ").append(notStarted).append(" = icmp eq i8* ").append(block).append(", null\n");
        int id = labelCount++;
        String badL = "co.join.notstarted." + id;
        String okL = "co.join.ok." + id;
        body.append("  br i1 ").append(notStarted).append(", label %").append(badL).append(", label %").append(okL).append("\n");
        body.append(badL).append(":\n");
        emitRuntimeError("Coroutine not started: call start() before join", node.line, okL);
        body.append(okL).append(":\n");
        // Self-join: joining the coroutine whose body is currently executing deadlocks.
        String coRaw = "%co.join.coraw." + tmpCount++;
        body.append("  ").append(coRaw).append(" = bitcast %CangCo* ").append(co.value).append(" to i8*\n");
        String cur = "%co.join.cur." + tmpCount++;
        body.append("  ").append(cur).append(" = load i8*, i8** @cang.co.current\n");
        String isSelf = "%co.join.self." + tmpCount++;
        body.append("  ").append(isSelf).append(" = icmp eq i8* ").append(cur).append(", ").append(coRaw).append("\n");
        String selfL = "co.join.selfjoin." + id;
        String pumpL = "co.join.pump." + id;
        body.append("  br i1 ").append(isSelf).append(", label %").append(selfL).append(", label %").append(pumpL).append("\n");
        body.append(selfL).append(":\n");
        emitFatalError("Cannot join the running coroutine", node.line, pumpL);
        body.append(pumpL).append(":\n");
        ensureCoroutineLoop();
        emitCoroutinePumpUntilDone(co.value, labelCount++);
        return new LLVMValue("void", "void");
    }

    /** Coroutine.getId(): logical id from the shared atomic counter (independent of Thread ids). */
    private LLVMValue generateCoroutineGetId(LLVMValue co, MethodCallExpr node) {
        String idPtr = "%co.getid.ip." + tmpCount++;
        body.append("  ").append(idPtr).append(" = getelementptr %CangCo, %CangCo* ").append(co.value)
             .append(", i32 0, i32 4\n");
        String v = "%co.getid.v." + tmpCount++;
        body.append("  ").append(v).append(" = load i32, i32* ").append(idPtr).append("\n");
        return new LLVMValue(v, "i32");
    }

    /** Coroutine.isAlive(): started (block non-null) and not yet done. */
    private LLVMValue generateCoroutineIsAlive(LLVMValue co, MethodCallExpr node) {
        String blockPtr = "%co.alive.bp." + tmpCount++;
        body.append("  ").append(blockPtr).append(" = getelementptr %CangCo, %CangCo* ").append(co.value)
             .append(", i32 0, i32 5\n");
        String block = "%co.alive.b." + tmpCount++;
        body.append("  ").append(block).append(" = load i8*, i8** ").append(blockPtr).append("\n");
        String started = "%co.alive.st." + tmpCount++;
        body.append("  ").append(started).append(" = icmp ne i8* ").append(block).append(", null\n");
        String dp = "%co.alive.dp." + tmpCount++;
        body.append("  ").append(dp).append(" = getelementptr %CangCo, %CangCo* ").append(co.value)
             .append(", i32 0, i32 2\n");
        String dv = "%co.alive.dv." + tmpCount++;
        body.append("  ").append(dv).append(" = load i32, i32* ").append(dp).append("\n");
        String notDone = "%co.alive.nd." + tmpCount++;
        body.append("  ").append(notDone).append(" = icmp eq i32 ").append(dv).append(", 0\n");
        String a = "%co.alive.a." + tmpCount++;
        body.append("  ").append(a).append(" = and i1 ").append(started).append(", ").append(notDone).append("\n");
        return new LLVMValue(a, "i1");
    }

    private LLVMValue generateNew(NewExpr node) {
        // String(...) / new String(...) — re-tag a string value as the String object type.
        if (node.className.equals("String") && node.args.size() == 1) {
            LLVMValue value = generateExpr(node.args.get(0));
            if (!value.type.equals("i8*")) {
                throw new RuntimeException("new String(...) expects a string value (at line " + node.line + ")");
            }
            return new LLVMValue(value.value, "i8*", "String");
        }
        // new Coroutine() / new Coroutine(true) / new Coroutine(f) — libuv event-loop object;
        // same shape as new Thread() but a single object (no separate handle: start() returns this).
        if (node.className.equals("Coroutine")) {
            if (node.typeArgs != null && !node.typeArgs.isEmpty()) {
                throw new RuntimeException("new Coroutine() does not take type parameters (at line " + node.line + ")");
            }
            if (node.args.size() > 1) {
                throw new RuntimeException("new Coroutine() takes at most one argument (at line " + node.line + ")");
            }
            String daemonVal = "false";
            LLVMValue taskFn = null;
            if (node.args.size() == 1) {
                String saved = expectedFunctionType;
                expectedFunctionType = "Function<void>";
                LLVMValue arg;
                try {
                    arg = generateExpr(node.args.get(0));
                } finally {
                    expectedFunctionType = saved;
                }
                if (arg.type.equals("i1")) {
                    daemonVal = arg.value;
                } else if (arg.type.equals("%CangFunction")) {
                    validateThreadTaskFn(arg, node.line, "new Coroutine(f)");
                    taskFn = arg;
                } else {
                    throw new RuntimeException("new Coroutine() takes a bool daemon flag or a Function<void> task (at line " + node.line + ")");
                }
            }
            return newCoroutineObject(daemonVal, taskFn);
        }

        // new Thread() / new Thread(true) / new Thread(f) — intrinsic object-style handle;
        // no Cang class body. Fields start null; .task(f) stores { code, receiver }; .start()
        // creates the thread and stores the handle. The Function form seeds the task up front.
        if (node.className.equals("Thread")) {
            if (node.typeArgs != null && !node.typeArgs.isEmpty()) {
                throw new RuntimeException("new Thread() does not take type parameters (at line " + node.line + ")");
            }
            if (node.args.size() > 1) {
                throw new RuntimeException("new Thread() takes at most one argument (at line " + node.line + ")");
            }
            // Daemon flag default false; a Function<void> arg seeds the task instead.
            String daemonVal = "false";
            LLVMValue taskFn = null;
            if (node.args.size() == 1) {
                String saved = expectedFunctionType;
                expectedFunctionType = "Function<void>";
                LLVMValue arg;
                try {
                    arg = generateExpr(node.args.get(0));
                } finally {
                    expectedFunctionType = saved;
                }
                if (arg.type.equals("i1")) {
                    daemonVal = arg.value;
                } else if (arg.type.equals("%CangFunction")) {
                    validateThreadTaskFn(arg, node.line, "new Thread(f)");
                    taskFn = arg;
                } else {
                    throw new RuntimeException("new Thread() takes a bool daemon flag or a Function<void> task (at line " + node.line + ")");
                }
            }
            return newThreadObject(daemonVal, taskFn);
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
                    + "' has a private constructor (at line "
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

        // Constructor: resolve via the overload group when init() overloads exist
        // (legacy single '.constructor' entry still works when no group was registered).
        String ctorName = ci.fullName + ".constructor";
        FuncInfo fi = null;
        List<FuncInfo> ctorGrp = overloadGroups.get(ctorName);
        if (ctorGrp != null && !ctorGrp.isEmpty()) {
            fi = resolveOverload(ctorGrp, node.args, node.line);
        } else if (functions.containsKey(ctorName)) {
            fi = functions.get(ctorName);
        }
        if (fi != null) {
            // _init signature: new is only allowed inside this class
            if (ci.privateInitSigs.contains(String.join(",", fi.paramTypes))) {
                if (currentClassName == null || !currentClassName.equals(ci.fullName)) {
                    throw new RuntimeException("Class '" + node.className
                        + "' has a private constructor (_init) (at line "
                        + node.line + ")");
                }
            }
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
            String ctorSym = fi.llvmName != null ? fi.llvmName : fi.name;
            body.append("  call void @").append(ctorSym).append("(").append(args).append(")\n");
            emitPostCallUnwindCheck();
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
    /** UTF-8 byte length — LLVM [N x i8] constants count bytes, not Java chars. */
    private static int utf8Len(String v) {
        return v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** LLVM c"..." escaping over UTF-8 bytes: printable ASCII stays raw, the rest becomes \XX. */
    private static String escapeBytes(String v) {
        StringBuilder sb = new StringBuilder();
        for (byte b : v.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c == '\\') sb.append("\\5C");
            else if (c == '\n') sb.append("\\0A");
            else if (c == '\t') sb.append("\\09");
            else if (c == '"') sb.append("\\22");
            else if (c >= 32 && c < 127) sb.append((char) c);
            else sb.append(String.format("\\%02X", c));
        }
        return sb.toString();
    }

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
        if (classObjectInitEmitted) body.append("  call void @cang.classinit()\n");

        for (AST stmt : topLevelStmts) {
            generateStmt(stmt);
        }

        emitGcFramePop();
        emitCoroutineRegistryJoin();
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
        if (scCi == null) return;
        boolean win = targetPlatform.equals("windows");

        // Pending byte for a 0xE0-prefixed CJK sequence (readKey only; -1 = none)
        header.append("@cang.sc.pending = internal global i32 -1\n");
        // libc declares (strlen/malloc/free already declared in emitHeader)
        if (!header.toString().contains("declare i8* @fgets(")) {
            header.append("declare i8* @fgets(i8*, i32, i8*)\n");
        }
        if (win) {
            header.append("declare i32 @_getch()\n");
            header.append("declare i32 @kbhit()\n");
            header.append("declare void @Sleep(i32)\n");
            header.append("declare i64 @GetTickCount64()\n");
            header.append("declare i32 @GetFileType(i8*)\n");
            header.append("declare i32 @PeekNamedPipe(i8*, i8*, i32, i8*, i32*, i32*)\n");
            // readLine still waits on the console input handle (readKey polls kbhit instead)
            if (!header.toString().contains("declare i8* @GetStdHandle(")) {
                header.append("declare i8* @GetStdHandle(i32)\n");
            }
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
        // Make earlier println output visible before we block on input (CRT buffering).
        rk.append("  call i32 @fflush(i8* null)\n");
        // Pending byte from a multi-byte sequence whose first byte looked like a 0xE0 prefix
        // (CJK encoding data, not a scan code) — serve it before any waiting.
        rk.append("  %pd = load i32, i32* @cang.sc.pending\n");
        rk.append("  %hasp = icmp ne i32 %pd, -1\n");
        rk.append("  br i1 %hasp, label %pdret, label %nopend\n");
        rk.append("pdret:\n");
        rk.append("  store i32 -1, i32* @cang.sc.pending\n");
        rk.append("  ret i32 %pd\n");
        rk.append("nopend:\n");
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
            // Special keys are returned as 256 + scancode (KEY_UP=328 = 256+72 etc.).
            // Scan codes are always <128, so a SECOND byte >= 128 means multi-byte CJK text
            // whose first byte happened to be 0xE0 — return the prefix byte and stash the
            // second byte in @cang.sc.pending instead of swallowing it as a scan code.
            rk.append("  %c0 = call i32 @_getch()\n");
            rk.append("  %p0 = icmp eq i32 %c0, 0\n");
            rk.append("  %pe = icmp eq i32 %c0, 224\n");
            rk.append("  %pre = or i1 %p0, %pe\n");
            rk.append("  br i1 %pre, label %sc, label %ret\n");
            rk.append("sc:\n");
            rk.append("  %c1 = call i32 @_getch()\n");
            rk.append("  %c1hi = icmp sge i32 %c1, 128\n");
            rk.append("  br i1 %c1hi, label %enc, label %scan\n");
            rk.append("enc:\n");
            rk.append("  store i32 %c1, i32* @cang.sc.pending\n");
            rk.append("  ret i32 %c0\n");
            rk.append("scan:\n");
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

        // ---- readLine (overloads: readLine() forever; readLine(ms) -> null on timeout) ----
        StringBuilder rl = new StringBuilder();
        // 0-arg forward (-1 = wait forever; EOF still returns null via fgets)
        rl.append("define i8* @cang_io_Scanner.readLine(i8* %this) {\nentry:\n");
        rl.append("  %r0 = call i8* @cang_io_Scanner.readLine.1(i8* %this, i32 -1)\n");
        rl.append("  ret i8* %r0\n");
        rl.append("}\n\n");
        // 1-arg main: gate readiness before fgets; timeout -> null (same as EOF)
        rl.append("define i8* @cang_io_Scanner.readLine.1(i8* %this, i32 %timeoutMs) {\nentry:\n");
        // Make earlier println output visible before we block on input (CRT buffering).
        rl.append("  call i32 @fflush(i8* null)\n");
        rl.append("  %pfdrl = alloca i64\n");
        if (win) {
            // -1 skips the wait. Console: WaitForSingleObject; pipe: PeekNamedPipe polling
            // (verified: the pipe read-end signals immediately, so a plain wait would hang
            // inside fgets — availability must be peeked, not waited on).
            rl.append("  %neg = icmp slt i32 %timeoutMs, 0\n");
            rl.append("  br i1 %neg, label %ready, label %wait\n");
            rl.append("wait:\n");
            rl.append("  %hin = call i8* @GetStdHandle(i32 -10)\n");
            rl.append("  %ft = call i32 @GetFileType(i8* %hin)\n");
            rl.append("  %isp = icmp eq i32 %ft, 3\n");
            rl.append("  br i1 %isp, label %pipewait, label %conwait\n");
            rl.append("conwait:\n");
            rl.append("  %wr = call i32 @WaitForSingleObject(i8* %hin, i32 %timeoutMs)\n");
            rl.append("  %wok = icmp eq i32 %wr, 0\n");
            rl.append("  br i1 %wok, label %ready, label %timeoutNull\n");
            rl.append("pipewait:\n");
            rl.append("  %t0 = call i64 @GetTickCount64()\n");
            rl.append("  br label %loopk\n");
            rl.append("loopk:\n");
            rl.append("  %avail = alloca i32\n");
            rl.append("  %pk = call i32 @PeekNamedPipe(i8* %hin, i8* null, i32 0, i8* null, i32* %avail, i32* null)\n");
            rl.append("  %pok = icmp ne i32 %pk, 0\n");
            rl.append("  %has = load i32, i32* %avail\n");
            rl.append("  %hasd = icmp sgt i32 %has, 0\n");
            rl.append("  %rdyc = and i1 %pok, %hasd\n");
            rl.append("  br i1 %rdyc, label %ready, label %tick\n");
            rl.append("tick:\n");
            rl.append("  %now = call i64 @GetTickCount64()\n");
            rl.append("  %t0z = zext i32 %timeoutMs to i64\n");
            rl.append("  %dt = sub i64 %now, %t0\n");
            rl.append("  %exp = icmp uge i64 %dt, %t0z\n");
            rl.append("  br i1 %exp, label %timeoutNull, label %nap\n");
            rl.append("nap:\n");
            rl.append("  call void @Sleep(i32 1)\n");
            rl.append("  br label %loopk\n");
            rl.append("timeoutNull:\n");
            rl.append("  ret i8* null\n");
            rl.append("ready:\n");
        } else {
            // canonical stdin: a full line or EOF wakes poll; -1 jumps straight to fgets
            rl.append("  %neg = icmp slt i32 %timeoutMs, 0\n");
            rl.append("  br i1 %neg, label %ready, label %wait\n");
            rl.append("wait:\n");
            rl.append("  store i64 4294967296, i64* %pfdrl\n");
            rl.append("  %pr = call i32 @poll(i8* %pfdrl, i64 1, i32 %timeoutMs)\n");
            rl.append("  %prok = icmp sgt i32 %pr, 0\n");
            rl.append("  br i1 %prok, label %ready, label %timeoutNull\n");
            rl.append("timeoutNull:\n");
            rl.append("  ret i8* null\n");
            rl.append("ready:\n");
        }
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

        // ---- readChar: String-returning single-character read (reuses readKey.1 for all
        // waiting/ESC/special-key/pending logic, then converts the key code to a string) ----
        String escS = ensureStringConstant("@.s.ch.esc", "<ESC>\\00", 6);
        String entS = ensureStringConstant("@.s.ch.enter", "<ENTER>\\00", 8);
        String tabS = ensureStringConstant("@.s.ch.tab", "<TAB>\\00", 6);
        String bkspS = ensureStringConstant("@.s.ch.bksp", "<BACKSPACE>\\00", 12);
        String upS = ensureStringConstant("@.s.ch.up", "<UP>\\00", 5);
        String dnS = ensureStringConstant("@.s.ch.dn", "<DOWN>\\00", 7);
        String lfS = ensureStringConstant("@.s.ch.lf", "<LEFT>\\00", 7);
        String rtS = ensureStringConstant("@.s.ch.rt", "<RIGHT>\\00", 8);
        String keyFmt = ensureStringConstant("@.fmt.ch.key", "<KEY:%d>\\00", 9);
        StringBuilder cc = new StringBuilder();
        // 0-arg forward
        cc.append("define i8* @cang_io_Scanner.readChar(i8* %this) {\nentry:\n");
        cc.append("  %r0 = call i8* @cang_io_Scanner.readChar.1(i8* %this, i32 -1)\n");
        cc.append("  ret i8* %r0\n");
        cc.append("}\n\n");
        cc.append("define i8* @cang_io_Scanner.readChar.1(i8* %this, i32 %ms) {\nentry:\n");
        // Reuse readKey.1: timeout/-1, ESC sequences, 0xE0 protection, pending byte, raw mode.
        cc.append("  %k = call i32 @cang_io_Scanner.readKey.1(i8* %this, i32 %ms)\n");
        cc.append("  %isneg = icmp eq i32 %k, -1\n");
        cc.append("  br i1 %isneg, label %nullb, label %ck256\n");
        cc.append("nullb:\n  ret i8* null\n");
        cc.append("ck256:\n");
        cc.append("  %ge256 = icmp sge i32 %k, 256\n");
        cc.append("  br i1 %ge256, label %keynamed, label %ck128\n");
        // >=256: named special keys, else <KEY:code>
        cc.append("keynamed:\n");
        cc.append("  %ku = icmp eq i32 %k, 328\n  br i1 %ku, label %sUp, label %ckdn\n");
        cc.append("sUp:\n  ret i8* getelementptr ([5 x i8], [5 x i8]* ").append(upS).append(", i32 0, i32 0)\n");
        cc.append("ckdn:\n  %kd = icmp eq i32 %k, 332\n  br i1 %kd, label %sDn, label %cklf\n");
        cc.append("sDn:\n  ret i8* getelementptr ([7 x i8], [7 x i8]* ").append(dnS).append(", i32 0, i32 0)\n");
        cc.append("cklf:\n  %kl = icmp eq i32 %k, 331\n  br i1 %kl, label %sLf, label %ckrt\n");
        cc.append("sLf:\n  ret i8* getelementptr ([7 x i8], [7 x i8]* ").append(lfS).append(", i32 0, i32 0)\n");
        cc.append("ckrt:\n  %kr = icmp eq i32 %k, 333\n  br i1 %kr, label %sRt, label %keycode\n");
        cc.append("sRt:\n  ret i8* getelementptr ([8 x i8], [8 x i8]* ").append(rtS).append(", i32 0, i32 0)\n");
        cc.append("keycode:\n");
        cc.append("  %kbuf = call i8* @").append(allocFn()).append("(i64 16)\n");
        cc.append("  call i32 (i8*, i8*, ...) @sprintf(i8* %kbuf, i8* getelementptr ([9 x i8], [9 x i8]* ")
             .append(keyFmt).append(", i32 0, i32 0), i32 %k)\n");
        cc.append("  ret i8* %kbuf\n");
        // <128: named control keys or a plain one-byte string
        cc.append("ck128:\n");
        cc.append("  %ge128 = icmp sge i32 %k, 128\n");
        cc.append("  br i1 %ge128, label %multibyte, label %ctrl\n");
        cc.append("ctrl:\n");
        cc.append("  %c27 = icmp eq i32 %k, 27\n  br i1 %c27, label %sEsc, label %ck13\n");
        cc.append("sEsc:\n  ret i8* getelementptr ([6 x i8], [6 x i8]* ").append(escS).append(", i32 0, i32 0)\n");
        cc.append("ck13:\n  %c13 = icmp eq i32 %k, 13\n  br i1 %c13, label %sEn, label %ck9\n");
        cc.append("sEn:\n  ret i8* getelementptr ([8 x i8], [8 x i8]* ").append(entS).append(", i32 0, i32 0)\n");
        cc.append("ck9:\n  %c9 = icmp eq i32 %k, 9\n  br i1 %c9, label %sTab, label %ck8\n");
        cc.append("sTab:\n  ret i8* getelementptr ([6 x i8], [6 x i8]* ").append(tabS).append(", i32 0, i32 0)\n");
        cc.append("ck8:\n  %c8k = icmp eq i32 %k, 8\n  br i1 %c8k, label %sBk, label %ascii\n");
        cc.append("sBk:\n  ret i8* getelementptr ([12 x i8], [12 x i8]* ").append(bkspS).append(", i32 0, i32 0)\n");
        cc.append("ascii:\n");
        cc.append("  %abuf = call i8* @").append(allocFn()).append("(i64 2)\n");
        cc.append("  %a8 = trunc i32 %k to i8\n");
        cc.append("  store i8 %a8, i8* %abuf\n");
        cc.append("  %ap1 = getelementptr i8, i8* %abuf, i64 1\n");
        cc.append("  store i8 0, i8* %ap1\n");
        cc.append("  ret i8* %abuf\n");
        // >=128: multi-byte (CJK) — length from the lead byte, continuation via readKey.1
        cc.append("multibyte:\n");
        cc.append("  %geF0 = icmp uge i32 %k, 240\n");
        cc.append("  %geE0 = icmp uge i32 %k, 224\n");
        cc.append("  %geC0 = icmp uge i32 %k, 192\n");
        cc.append("  %n3 = select i1 %geE0, i32 3, i32 2\n");
        cc.append("  %n4 = select i1 %geF0, i32 4, i32 %n3\n");
        cc.append("  %n = select i1 %geC0, i32 %n4, i32 2\n");
        cc.append("  %ns = zext i32 %n to i64\n");
        cc.append("  %asz = add i64 %ns, 1\n");
        cc.append("  %mbuf = call i8* @").append(allocFn()).append("(i64 %asz)\n");
        cc.append("  %m8 = trunc i32 %k to i8\n");
        cc.append("  store i8 %m8, i8* %mbuf\n");
        cc.append("  %w = alloca i8*\n");
        cc.append("  %w1 = getelementptr i8, i8* %mbuf, i64 1\n");
        cc.append("  store i8* %w1, i8** %w\n");
        // continuation byte 1 (only when n >= 2 — always true for lead >= 0xC0, kept generic)
        cc.append("  %g1 = icmp sge i32 %n, 2\n");
        cc.append("  br i1 %g1, label %r1, label %endm\n");
        cc.append("r1:\n");
        cc.append("  %b1 = call i32 @cang_io_Scanner.readKey.1(i8* %this, i32 -1)\n");
        cc.append("  %b1n = icmp eq i32 %b1, -1\n");
        cc.append("  br i1 %b1n, label %endm, label %f1\n");
        cc.append("f1:\n");
        cc.append("  %b1v = trunc i32 %b1 to i8\n");
        cc.append("  %w1p = load i8*, i8** %w\n");
        cc.append("  store i8 %b1v, i8* %w1p\n");
        cc.append("  %w1n = getelementptr i8, i8* %w1p, i64 1\n");
        cc.append("  store i8* %w1n, i8** %w\n");
        cc.append("  br label %chk3\n");
        cc.append("chk3:\n  %g2 = icmp sge i32 %n, 3\n  br i1 %g2, label %r2, label %endm\n");
        cc.append("r2:\n");
        cc.append("  %b2 = call i32 @cang_io_Scanner.readKey.1(i8* %this, i32 -1)\n");
        cc.append("  %b2n = icmp eq i32 %b2, -1\n");
        cc.append("  br i1 %b2n, label %endm, label %f2\n");
        cc.append("f2:\n");
        cc.append("  %b2v = trunc i32 %b2 to i8\n");
        cc.append("  %w2p = load i8*, i8** %w\n");
        cc.append("  store i8 %b2v, i8* %w2p\n");
        cc.append("  %w2n = getelementptr i8, i8* %w2p, i64 1\n");
        cc.append("  store i8* %w2n, i8** %w\n");
        cc.append("  br label %chk4\n");
        cc.append("chk4:\n  %g3 = icmp sge i32 %n, 4\n  br i1 %g3, label %r3, label %endm\n");
        cc.append("r3:\n");
        cc.append("  %b3 = call i32 @cang_io_Scanner.readKey.1(i8* %this, i32 -1)\n");
        cc.append("  %b3n = icmp eq i32 %b3, -1\n");
        cc.append("  br i1 %b3n, label %endm, label %f3\n");
        cc.append("f3:\n");
        cc.append("  %b3v = trunc i32 %b3 to i8\n");
        cc.append("  %w3p = load i8*, i8** %w\n");
        cc.append("  store i8 %b3v, i8* %w3p\n");
        cc.append("  %w3n = getelementptr i8, i8* %w3p, i64 1\n");
        cc.append("  store i8* %w3n, i8** %w\n");
        cc.append("  br label %endm\n");
        cc.append("endm:\n");
        cc.append("  %wend = load i8*, i8** %w\n");
        cc.append("  store i8 0, i8* %wend\n");
        cc.append("  ret i8* %mbuf\n");
        cc.append("}\n\n");
        header.append(cc);
    }

    /**
     * cang/lang/ProcessBuilder — exec(String[] cmd, Function<Void,String> cb, int timeoutMs).
     * argv direct exec (no shell). stdout line-split -> callback per line; stderr inherits
     * the terminal. Returns the child exit code; -1 = timeout (killed), -2 = the
     * executable could not be started (surfaced as a throw at the call site).
     * Labels prl.* / values prv.* — kept apart from the start (collision #6 lesson).
     */
    private void emitProcessRuntime() {
        ClassInfo pCi = classes.get("cang_lang_ProcessBuilder");
        if (pCi == null) return;
        boolean win = targetPlatform.equals("windows");
        StringBuilder pb = new StringBuilder();
        // libc / kernel32 declares (contains-guarded; scanner may already declare Sleep/Peek/GetTickCount64)
        if (!header.toString().contains("declare i8* @memchr(")) {
            header.append("declare i8* @memchr(i8*, i32, i64)\n");
        }
        if (!header.toString().contains("@.pr.exe = ")) {
            header.append("@.pr.exe = private constant [5 x i16] [i16 46, i16 101, i16 120, i16 101, i16 0]\n");
        }
        if (!header.toString().contains("@cang.proc.spawnname = ")) {
            header.append("@cang.proc.spawnname = global i8* null\n");
        }
        if (win) {
            if (!header.toString().contains("declare i32 @CreatePipe(")) {
                header.append("declare i32 @CreatePipe(i8*, i8*, i8*, i32)\n");
                header.append("declare i32 @CreateProcessW(i16*, i16*, i8*, i8*, i32, i32, i8*, i16*, i8*, i8*)\n");
                header.append("declare i32 @ReadFile(i8*, i8*, i32, i32*, i8*)\n");
                header.append("declare i32 @GetExitCodeProcess(i8*, i32*)\n");
                header.append("declare i32 @TerminateProcess(i8*, i32)\n");
                header.append("declare i32 @MultiByteToWideChar(i32, i32, i8*, i32, i16*, i32)\n");
                header.append("declare i32 @WideCharToMultiByte(i32, i32, i16*, i32, i8*, i32, i8*, i8*)\n");
                header.append("declare i32 @SearchPathW(i16*, i16*, i16*, i32, i16*, i16*)\n");
            }
            if (!header.toString().contains("declare i32 @CloseHandle(")) {
                header.append("declare i32 @CloseHandle(i8*)\n");
            }
            if (!header.toString().contains("declare void @Sleep(i32)")) {
                header.append("declare void @Sleep(i32)\n");
            }
            if (!header.toString().contains("declare i64 @GetTickCount64()")) {
                header.append("declare i64 @GetTickCount64()\n");
            }
            if (!header.toString().contains("declare i32 @PeekNamedPipe(")) {
                header.append("declare i32 @PeekNamedPipe(i8*, i8*, i32, i8*, i32*, i32*)\n");
            }
            if (!header.toString().contains("declare i8* @GetStdHandle(")) {
                header.append("declare i8* @GetStdHandle(i32)\n");
            }
        } else {
            if (!header.toString().contains("declare i32 @pipe(")) {
                header.append("declare i32 @pipe(i32*)\n");
                header.append("declare i32 @fork()\n");
                header.append("declare i32 @dup2(i32, i32)\n");
                header.append("declare i32 @execvp(i8*, i8**)\n");
                header.append("declare void @_exit(i32)\n");
                header.append("declare i32 @kill(i32, i32)\n");
                header.append("declare i32 @waitpid(i32, i32*, i32)\n");
                header.append("declare i32 @close(i32)\n");
                header.append("declare i32 @gettimeofday(i8*, i8*)\n");
            }
            if (!header.toString().contains("declare i64 @read(i32, i8*, i64)")) {
                header.append("declare i64 @read(i32, i8*, i64)\n");
            }
            if (!header.toString().contains("declare i32 @poll(")) {
                header.append("declare i32 @poll(i8*, i64, i32)\n");
            }
        }
        int id = labelCount++;
        String lblArgvLoop = "prl.argv.loop." + id, lblArgvBody = "prl.argv.body." + id,
               lblArgvDone = "prl.argv.done." + id, lblSpawn = "prl.spawn." + id,
               lblSpawnFail = "prl.spawn.fail." + id, lblReadLoop = "prl.read.loop." + id,
               lblChunk = "prl.chunk." + id, lblScan = "prl.scan." + id,
               lblScanAppend = "prl.scan.append." + id, lblScanLine = "prl.scan.line." + id,
               lblScanNext = "prl.scan.next." + id, lblDone = "prl.done." + id,
               lblKill = "prl.kill." + id, lblSleep = "prl.sleep." + id,
               lblRet = "prl.ret." + id;
        String argc = "%prv.argc." + tmpCount, argv = "%prv.argv." + tmpCount,
               iAlloca = "%prv.i." + tmpCount, pend = "%prv.pend." + tmpCount,
               pendLen = "%prv.pendlen." + tmpCount, exited = "%prv.exited." + tmpCount,
               timedOut = "%prv.timedout." + tmpCount, codeSlot = "%prv.code." + tmpCount,
               srcPtr = "%prv.src." + tmpCount, rem = "%prv.rem." + tmpCount,
               tmpBuf = "%prv.tmpbuf." + tmpCount, nrd = "%prv.nrd." + tmpCount,
               avail = "%prv.avail." + tmpCount, startT = "%prv.start." + tmpCount;
        tmpCount++;
        pb.append("define i32 @cang_lang_ProcessBuilder.exec(i8* %cmd, %CangFunction %cb, i32 %timeoutMs) {\n");
        // Flush pending parent output so it is visible while we wait on the child.
        pb.append("entry:\n");
        pb.append("  call i32 @fflush(i8* null)\n");
        pb.append("  ").append(argc).append(" = load i64, i64* %cmd\n");
        pb.append("  ").append(pend).append(" = alloca i8*\n  store i8* null, i8** ").append(pend).append("\n");
        pb.append("  ").append(pendLen).append(" = alloca i64\n  store i64 0, i64* ").append(pendLen).append("\n");
        pb.append("  ").append(exited).append(" = alloca i32\n  store i32 0, i32* ").append(exited).append("\n");
        pb.append("  ").append(timedOut).append(" = alloca i32\n  store i32 0, i32* ").append(timedOut).append("\n");
        pb.append("  ").append(codeSlot).append(" = alloca i32\n  store i32 -1, i32* ").append(codeSlot).append("\n");
        pb.append("  ").append(srcPtr).append(" = alloca i8*\n");
        pb.append("  ").append(rem).append(" = alloca i64\n");
        pb.append("  ").append(tmpBuf).append(" = alloca [4096 x i8]\n");
        pb.append("  ").append(nrd).append(" = alloca i32\n");
        pb.append("  ").append(avail).append(" = alloca i32\n");
        pb.append("  ").append(startT).append(" = alloca i64\n  store i64 0, i64* ").append(startT).append("\n");
        // argv = malloc((argc+1)*8), fill from the length-prefixed Cang array, NULL-terminate
        String asz = "%prv.asz." + tmpCount++, asz8 = "%prv.asz8." + tmpCount++;
        pb.append("  ").append(asz).append(" = add i64 ").append(argc).append(", 1\n");
        pb.append("  ").append(asz8).append(" = mul i64 ").append(asz).append(", 8\n");
        pb.append("  ").append(argv).append(" = call i8* @").append(allocFn()).append("(i64 ").append(asz8).append(")\n");
        pb.append("  ").append(iAlloca).append(" = alloca i64\n  store i64 0, i64* ").append(iAlloca).append("\n");
        pb.append("  br label %").append(lblArgvLoop).append("\n");
        pb.append(lblArgvLoop).append(":\n");
        String icur = "%prv.icur." + tmpCount++, icmp = "%prv.icmp." + tmpCount++;
        pb.append("  ").append(icur).append(" = load i64, i64* ").append(iAlloca).append("\n");
        pb.append("  ").append(icmp).append(" = icmp slt i64 ").append(icur).append(", ").append(argc).append("\n");
        pb.append("  br i1 ").append(icmp).append(", label %").append(lblArgvBody).append(", label %").append(lblArgvDone).append("\n");
        pb.append(lblArgvBody).append(":\n");
        String cmdAs = "%prv.cmdas." + tmpCount++;
        pb.append("  ").append(cmdAs).append(" = bitcast i8* %cmd to i8**\n");
        String cmdS0 = "%prv.cmds0." + tmpCount++;
        pb.append("  ").append(cmdS0).append(" = getelementptr i8*, i8** ").append(cmdAs).append(", i64 1\n");
        String slotPtr = "%prv.sp." + tmpCount++;
        pb.append("  ").append(slotPtr).append(" = getelementptr i8*, i8** ").append(cmdS0).append(", i64 ").append(icur).append("\n");
        String elem = "%prv.elem." + tmpCount++;
        pb.append("  ").append(elem).append(" = load i8*, i8** ").append(slotPtr).append("\n");
        String argvAs = "%prv.argvas." + tmpCount++;
        pb.append("  ").append(argvAs).append(" = bitcast i8* ").append(argv).append(" to i8**\n");
        String argvSlot = "%prv.as." + tmpCount++;
        pb.append("  ").append(argvSlot).append(" = getelementptr i8*, i8** ").append(argvAs).append(", i64 ").append(icur).append("\n");
        pb.append("  store i8* ").append(elem).append(", i8** ").append(argvSlot).append("\n");
        String iinc = "%prv.iinc." + tmpCount++;
        pb.append("  ").append(iinc).append(" = add i64 ").append(icur).append(", 1\n");
        pb.append("  store i64 ").append(iinc).append(", i64* ").append(iAlloca).append("\n");
        pb.append("  br label %").append(lblArgvLoop).append("\n");
        pb.append(lblArgvDone).append(":\n");
        String argvAs2 = "%prv.argvas2." + tmpCount++;
        pb.append("  ").append(argvAs2).append(" = bitcast i8* ").append(argv).append(" to i8**\n");
        String nullSlot = "%prv.ns." + tmpCount++;
        pb.append("  ").append(nullSlot).append(" = getelementptr i8*, i8** ").append(argvAs2).append(", i64 ").append(icur).append("\n");
        pb.append("  store i8* null, i8** ").append(nullSlot).append("\n");
        // Record argv[0] so the call site can say which program failed to start.
        String psnCast = "%prv.psn." + tmpCount++;
        pb.append("  ").append(psnCast).append(" = bitcast i8* ").append(argv).append(" to i8**\n");
        String psnP = "%prv.psnp." + tmpCount++;
        pb.append("  ").append(psnP).append(" = getelementptr i8*, i8** ").append(psnCast).append(", i64 0\n");
        String psnV = "%prv.psnv." + tmpCount++;
        pb.append("  ").append(psnV).append(" = load i8*, i8** ").append(psnP).append("\n");
        pb.append("  store i8* ").append(psnV).append(", i8** @cang.proc.spawnname\n");
pb.append("  br label %").append(lblSpawn).append("\n");
        if (win) {
            // ---- Windows spawn: PATH-resolve argv[0] (CreateProcess does NOT search PATH),
            //      quote-join argv into a command line, CreateProcessW with stdout piped. ----
            pb.append(lblSpawn).append(":\n");
            String sa = "%prv.sa." + tmpCount++, rd = "%prv.rd." + tmpCount++, wr = "%prv.wr." + tmpCount++;
            String si = "%prv.si." + tmpCount++, pi = "%prv.pi." + tmpCount++;
            pb.append("  ").append(sa).append(" = alloca [24 x i8]\n");
            pb.append("  ").append(rd).append(" = alloca i8*\n");
            pb.append("  ").append(wr).append(" = alloca i8*\n");
            pb.append("  ").append(si).append(" = alloca [104 x i8]\n");
            pb.append("  ").append(pi).append(" = alloca [24 x i8]\n");
            String a0slot = "%prv.a0s." + tmpCount++;
            pb.append("  ").append(a0slot).append(" = bitcast i8* ").append(argv).append(" to i8**\n");
            String a0 = "%prv.a0." + tmpCount++;
            pb.append("  ").append(a0).append(" = load i8*, i8** ").append(a0slot).append("\n");
            // scan argv[0] for a path separator (/ \ :) — if present, skip PATH search
            String hasSep = "%prv.hassep." + tmpCount++, sepPtr = "%prv.sepp." + tmpCount++;
            String lScan = "prl.sep.scan." + id, lSepYes = "prl.sep.yes." + id, lSearch = "prl.search." + id,
                   lFound = "prl.found." + id, lBuild = "prl.build." + id;
            pb.append("  ").append(hasSep).append(" = alloca i1\n  store i1 false, i1* ").append(hasSep).append("\n");
            pb.append("  ").append(sepPtr).append(" = alloca i8*\n  store i8* ").append(a0).append(", i8** ").append(sepPtr).append("\n");
            pb.append("  br label %").append(lScan).append("\n");
            pb.append(lScan).append(":\n");
            String sepCur = "%prv.sepc." + tmpCount++;
            pb.append("  ").append(sepCur).append(" = load i8*, i8** ").append(sepPtr).append("\n");
            String sepB = "%prv.sepb." + tmpCount++;
            pb.append("  ").append(sepB).append(" = load i8, i8* ").append(sepCur).append("\n");
            String sepEnd = "%prv.sepe." + tmpCount++;
            pb.append("  ").append(sepEnd).append(" = icmp eq i8 ").append(sepB).append(", 0\n");
            String lAfterScan = "prl.sep.after." + id;
            pb.append("  br i1 ").append(sepEnd).append(", label %").append(lAfterScan).append(", label %").append("prl.sep.chk." + id).append("\n");
            pb.append("prl.sep.chk.").append(id).append(":\n");
            String c1 = "%prv.c1." + tmpCount++, c2 = "%prv.c2." + tmpCount++, c3 = "%prv.c3." + tmpCount++;
            pb.append("  ").append(c1).append(" = icmp eq i8 ").append(sepB).append(", 47\n");
            pb.append("  ").append(c2).append(" = icmp eq i8 ").append(sepB).append(", 92\n");
            pb.append("  ").append(c3).append(" = icmp eq i8 ").append(sepB).append(", 58\n");
            String c12 = "%prv.c12." + tmpCount++, c123 = "%prv.c123." + tmpCount++;
            pb.append("  ").append(c12).append(" = or i1 ").append(c1).append(", ").append(c2).append("\n");
            pb.append("  ").append(c123).append(" = or i1 ").append(c12).append(", ").append(c3).append("\n");
            pb.append("  br i1 ").append(c123).append(", label %").append(lSepYes).append(", label %").append("prl.sep.next." + id).append("\n");
            pb.append("prl.sep.next.").append(id).append(":\n");
            String sepInc = "%prv.sepi." + tmpCount++;
            pb.append("  ").append(sepInc).append(" = getelementptr i8, i8* ").append(sepCur).append(", i64 1\n");
            pb.append("  store i8* ").append(sepInc).append(", i8** ").append(sepPtr).append("\n");
            pb.append("  br label %").append(lScan).append("\n");
            pb.append(lSepYes).append(":\n");
            pb.append("  store i1 true, i1* ").append(hasSep).append("\n");
            pb.append("  br label %").append(lAfterScan).append("\n");
            pb.append(lAfterScan).append(":\n");
            String sepNow = "%prv.sepn." + tmpCount++;
            pb.append("  ").append(sepNow).append(" = load i1, i1* ").append(hasSep).append("\n");
            pb.append("  br i1 ").append(sepNow).append(", label %").append(lBuild).append(", label %").append(lSearch).append("\n");
            // PATH search: widen argv[0], SearchPathW(L".exe"), widen the hit back to UTF-8 -> argv[0]
            pb.append(lSearch).append(":\n");
            String w0len = "%prv.w0len." + tmpCount++;
            pb.append("  ").append(w0len).append(" = call i32 @MultiByteToWideChar(i32 65001, i32 0, i8* ").append(a0)
                 .append(", i32 -1, i16* null, i32 0)\n");
            String w0bytes = "%prv.w0b." + tmpCount++;
            pb.append("  ").append(w0bytes).append(" = sext i32 ").append(w0len).append(" to i64\n");
            String w0sz = "%prv.w0sz." + tmpCount++;
            pb.append("  ").append(w0sz).append(" = mul i64 ").append(w0bytes).append(", 2\n");
            String w0 = "%prv.w0." + tmpCount++;
            pb.append("  ").append(w0).append(" = call i8* @").append(allocFn()).append("(i64 ").append(w0sz).append(")\n");
            String w0b = "%prv.w0bb." + tmpCount++;
            pb.append("  ").append(w0b).append(" = bitcast i8* ").append(w0).append(" to i16*\n");
            pb.append("  %prv.w0c.").append(id).append(" = call i32 @MultiByteToWideChar(i32 65001, i32 0, i8* ").append(a0)
                 .append(", i32 -1, i16* ").append(w0b).append(", i32 ").append(w0len).append(")\n");
            String sbuf = "%prv.sbuf." + tmpCount++;
            pb.append("  ").append(sbuf).append(" = call i8* @").append(allocFn()).append("(i64 2048)\n");
            String sbuf16 = "%prv.sb16." + tmpCount;
            pb.append("  ").append(sbuf16).append(" = bitcast i8* ").append(sbuf).append(" to i16*\n");
            tmpCount++;
            String sr = "%prv.sr." + tmpCount++;
            pb.append("  ").append(sr).append(" = call i32 @SearchPathW(i16* null, i16* ").append(w0b)
                 .append(", i16* getelementptr ([5 x i16], [5 x i16]* @.pr.exe, i32 0, i32 0), i32 1024, i16* ")
                 .append(sbuf16).append(", i16* null)\n");
            String sok = "%prv.sok." + tmpCount++;
            pb.append("  ").append(sok).append(" = icmp sgt i32 ").append(sr).append(", 0\n");
            pb.append("  br i1 ").append(sok).append(", label %").append(lFound).append(", label %").append(lBuild).append("\n");
            pb.append(lFound).append(":\n");
            String u8len = "%prv.u8len." + tmpCount++;
            pb.append("  ").append(u8len).append(" = call i32 @WideCharToMultiByte(i32 65001, i32 0, i16* ").append(sbuf16)
                 .append(", i32 -1, i8* null, i32 0, i8* null, i8* null)\n");
            String u8sz = "%prv.u8sz." + tmpCount++;
            pb.append("  ").append(u8sz).append(" = sext i32 ").append(u8len).append(" to i64\n");
            String nu8 = "%prv.nu8." + tmpCount++;
            pb.append("  ").append(nu8).append(" = call i8* @").append(allocFn()).append("(i64 ").append(u8sz).append(")\n");
            pb.append("  %prv.u8c.").append(id).append(" = call i32 @WideCharToMultiByte(i32 65001, i32 0, i16* ").append(sbuf16)
                 .append(", i32 -1, i8* ").append(nu8).append(", i32 ").append(u8len).append(", i8* null, i8* null)\n");
            pb.append("  store i8* ").append(nu8).append(", i8** ").append(a0slot).append("\n");
            pb.append("  br label %").append(lBuild).append("\n");
            // ---- quote-join: sum lengths, alloc, then per arg: [" ... "] with embedded " -> \" ----
            pb.append(lBuild).append(":\n");
            String sum = "%prv.sum." + tmpCount++, j = "%prv.j." + tmpCount++;
            String lT1 = "prl.t1." + id, lT1b = "prl.t1b." + id, lT1d = "prl.t1d." + id;
            pb.append("  ").append(sum).append(" = alloca i64\n  store i64 0, i64* ").append(sum).append("\n");
            pb.append("  ").append(j).append(" = alloca i64\n  store i64 0, i64* ").append(j).append("\n");
            pb.append("  br label %").append(lT1).append("\n");
            pb.append(lT1).append(":\n");
            String jc = "%prv.jc." + tmpCount++;
            pb.append("  ").append(jc).append(" = load i64, i64* ").append(j).append("\n");
            String jcmp = "%prv.jcmp." + tmpCount++;
            pb.append("  ").append(jcmp).append(" = icmp slt i64 ").append(jc).append(", ").append(argc).append("\n");
            pb.append("  br i1 ").append(jcmp).append(", label %").append(lT1b).append(", label %").append(lT1d).append("\n");
            pb.append(lT1b).append(":\n");
            String cmdAsJ = "%prv.argvasj." + tmpCount++;
            pb.append("  ").append(cmdAsJ).append(" = bitcast i8* ").append(argv).append(" to i8**\n");
            String jslot = "%prv.js." + tmpCount++;
            pb.append("  ").append(jslot).append(" = getelementptr i8*, i8** ").append(cmdAsJ).append(", i64 ").append(jc).append("\n");
            String je = "%prv.je." + tmpCount++;
            pb.append("  ").append(je).append(" = load i8*, i8** ").append(jslot).append("\n");
            String jl = "%prv.jl." + tmpCount++;
            pb.append("  ").append(jl).append(" = call i64 @strlen(i8* ").append(je).append(")\n");
            String su = "%prv.su." + tmpCount++;
            pb.append("  ").append(su).append(" = load i64, i64* ").append(sum).append("\n");
            String sn = "%prv.sn." + tmpCount++;
            pb.append("  ").append(sn).append(" = add i64 ").append(su).append(", ").append(jl).append("\n");
            pb.append("  store i64 ").append(sn).append(", i64* ").append(sum).append("\n");
            String ji = "%prv.ji." + tmpCount++;
            pb.append("  ").append(ji).append(" = add i64 ").append(jc).append(", 1\n");
            pb.append("  store i64 ").append(ji).append(", i64* ").append(j).append("\n");
            pb.append("  br label %").append(lT1).append("\n");
            pb.append(lT1d).append(":\n");
            String su2 = "%prv.su2." + tmpCount++;
            pb.append("  ").append(su2).append(" = load i64, i64* ").append(sum).append("\n");
            String c3a = "%prv.c3a." + tmpCount++;
            pb.append("  ").append(c3a).append(" = mul i64 ").append(argc).append(", 3\n");
            String cap1 = "%prv.cap1." + tmpCount++;
            pb.append("  ").append(cap1).append(" = mul i64 ").append(su2).append(", 2\n");
            String cap2 = "%prv.cap2." + tmpCount++;
            pb.append("  ").append(cap2).append(" = add i64 ").append(cap1).append(", ").append(c3a).append("\n");
            String cap = "%prv.cap." + tmpCount++;
            pb.append("  ").append(cap).append(" = add i64 ").append(cap2).append(", 16\n");
            String cmdUtf8 = "%prv.cmd8." + tmpCount;
            pb.append("  ").append(cmdUtf8).append(" = call i8* @").append(allocFn()).append("(i64 ").append(cap).append(")\n");
            tmpCount++;
            String pos = "%prv.pos." + tmpCount, k = "%prv.k." + tmpCount;
            pb.append("  ").append(pos).append(" = alloca i8*\n  store i8* ").append(cmdUtf8).append(", i8** ").append(pos).append("\n");
            pb.append("  ").append(k).append(" = alloca i64\n  store i64 0, i64* ").append(k).append("\n");
            String lT2 = "prl.t2." + id, lT2arg = "prl.t2arg." + id, lT2in = "prl.t2in." + id,
                   lT2in2 = "prl.t2in2." + id, lT2inQ = "prl.t2inq." + id, lT2done = "prl.t2done." + id;
            pb.append("  br label %").append(lT2).append("\n");
            pb.append(lT2).append(":\n");
            String kc = "%prv.kc." + tmpCount++;
            pb.append("  ").append(kc).append(" = load i64, i64* ").append(k).append("\n");
            String kcmp = "%prv.kcmp." + tmpCount++;
            pb.append("  ").append(kcmp).append(" = icmp slt i64 ").append(kc).append(", ").append(argc).append("\n");
            pb.append("  br i1 ").append(kcmp).append(", label %").append(lT2arg).append(", label %").append(lT2done).append("\n");
            pb.append(lT2arg).append(":\n");
            String cmdAsK = "%prv.argvask." + tmpCount++;
            pb.append("  ").append(cmdAsK).append(" = bitcast i8* ").append(argv).append(" to i8**\n");
            String kslot = "%prv.ks." + tmpCount++;
            pb.append("  ").append(kslot).append(" = getelementptr i8*, i8** ").append(cmdAsK).append(", i64 ").append(kc).append("\n");
            String ke = "%prv.ke." + tmpCount++;
            pb.append("  ").append(ke).append(" = load i8*, i8** ").append(kslot).append("\n");
            String kstr = "%prv.kstr." + tmpCount;
            pb.append("  ").append(kstr).append(" = alloca i8*\n  store i8* ").append(ke).append(", i8** ").append(kstr).append("\n");
            String kptr = "%prv.kptr." + tmpCount;
            pb.append("  ").append(kptr).append(" = alloca i8*\n");
            String kopen = "%prv.kopen." + tmpCount;
            pb.append("  ").append(kopen).append(" = load i8*, i8** ").append(pos).append("\n");
            tmpCount++;
            String kcmp0 = "%prv.kcmp0." + tmpCount++;
            pb.append("  ").append(kcmp0).append(" = icmp eq i64 ").append(kc).append(", 0\n");
            pb.append("  br i1 ").append(kcmp0).append(", label %").append(lT2in).append(", label %").append("prl.t2sp." + id).append("\n");
            pb.append("prl.t2sp.").append(id).append(":\n");
            pb.append("  store i8 32, i8* ").append(kopen).append("\n");
            String kopen2 = "%prv.kopen2." + tmpCount++;
            pb.append("  ").append(kopen2).append(" = getelementptr i8, i8* ").append(kopen).append(", i64 1\n");
            pb.append("  store i8* ").append(kopen2).append(", i8** ").append(pos).append("\n");
            pb.append("  br label %").append(lT2in).append("\n");
            pb.append(lT2in).append(":\n");
            String kq = "%prv.kq." + tmpCount++;
            pb.append("  ").append(kq).append(" = load i8*, i8** ").append(pos).append("\n");
            pb.append("  store i8 34, i8* ").append(kq).append("\n");  // opening quote
            String kq2 = "%prv.kq2." + tmpCount++;
            pb.append("  ").append(kq2).append(" = getelementptr i8, i8* ").append(kq).append(", i64 1\n");
            pb.append("  store i8* ").append(kq2).append(", i8** ").append(pos).append("\n");
            pb.append("  store i8* ").append(ke).append(", i8** ").append(kstr).append("\n");
            pb.append("  store i8* ").append(ke).append(", i8** ").append(kptr).append("\n");
            pb.append("  br label %").append(lT2in2).append("\n");
            pb.append(lT2in2).append(":\n");
            String pc = "%prv.pc." + tmpCount++;
            pb.append("  ").append(pc).append(" = load i8*, i8** ").append(kptr).append("\n");
            String pb2 = "%prv.pb." + tmpCount++;
            pb.append("  ").append(pb2).append(" = load i8, i8* ").append(pc).append("\n");
            String pend2 = "%prv.pd." + tmpCount++;
            pb.append("  ").append(pend2).append(" = icmp eq i8 ").append(pb2).append(", 0\n");
            pb.append("  br i1 ").append(pend2).append(", label %").append("prl.t2close." + id).append(", label %").append(lT2inQ).append("\n");
            pb.append("prl.t2inq.").append(id).append(":\n");
            String isQ = "%prv.isq." + tmpCount++;
            pb.append("  ").append(isQ).append(" = icmp eq i8 ").append(pb2).append(", 34\n");
            String pst = "%prv.pst." + tmpCount;
            pb.append("  ").append(pst).append(" = load i8*, i8** ").append(pos).append("\n");
            tmpCount++;
            pb.append("  br i1 ").append(isQ).append(", label %").append("prl.t2esc." + id).append(", label %").append("prl.t2copy." + id).append("\n");
            pb.append("prl.t2esc.").append(id).append(":\n");
            pb.append("  store i8 92, i8* ").append(pst).append("\n");
            String pst1 = "%prv.pst1." + tmpCount++;
            pb.append("  ").append(pst1).append(" = getelementptr i8, i8* ").append(pst).append(", i64 1\n");
            pb.append("  store i8* ").append(pst1).append(", i8** ").append(pos).append("\n");
            pb.append("  br label %").append("prl.t2copy." + id).append("\n");
            pb.append("prl.t2copy.").append(id).append(":\n");
            String pstC = "%prv.pstc." + tmpCount;
            pb.append("  ").append(pstC).append(" = load i8*, i8** ").append(pos).append("\n");
            tmpCount++;
            pb.append("  store i8 ").append(pb2).append(", i8* ").append(pstC).append("\n");
            String pst2 = "%prv.pst2." + tmpCount++;
            pb.append("  ").append(pst2).append(" = getelementptr i8, i8* ").append(pstC).append(", i64 1\n");
            pb.append("  store i8* ").append(pst2).append(", i8** ").append(pos).append("\n");
            String pk2 = "%prv.pk2." + tmpCount++;
            pb.append("  ").append(pk2).append(" = getelementptr i8, i8* ").append(pc).append(", i64 1\n");
            pb.append("  store i8* ").append(pk2).append(", i8** ").append(kptr).append("\n");
            pb.append("  br label %").append(lT2in2).append("\n");
            pb.append("prl.t2close.").append(id).append(":\n");
            String kcl = "%prv.kcl." + tmpCount++;
            pb.append("  ").append(kcl).append(" = load i8*, i8** ").append(pos).append("\n");
            pb.append("  store i8 34, i8* ").append(kcl).append("\n");  // closing quote
            String kcl2 = "%prv.kcl2." + tmpCount++;
            pb.append("  ").append(kcl2).append(" = getelementptr i8, i8* ").append(kcl).append(", i64 1\n");
            pb.append("  store i8* ").append(kcl2).append(", i8** ").append(pos).append("\n");
            String ki = "%prv.ki." + tmpCount++;
            pb.append("  ").append(ki).append(" = add i64 ").append(kc).append(", 1\n");
            pb.append("  store i64 ").append(ki).append(", i64* ").append(k).append("\n");
            pb.append("  br label %").append(lT2).append("\n");
            pb.append(lT2done).append(":\n");
            String pe = "%prv.pe." + tmpCount++;
            pb.append("  ").append(pe).append(" = load i8*, i8** ").append(pos).append("\n");
            pb.append("  store i8 0, i8* ").append(pe).append("\n");
            // widen cmdline -> CreateProcessW(NULL, wcmd, NULL, NULL, inherit, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)
            String wl = "%prv.wl." + tmpCount++;
            pb.append("  ").append(wl).append(" = call i32 @MultiByteToWideChar(i32 65001, i32 0, i8* ").append(cmdUtf8)
                 .append(", i32 -1, i16* null, i32 0)\n");
            String wl64 = "%prv.wl64." + tmpCount++;
            pb.append("  ").append(wl64).append(" = sext i32 ").append(wl).append(" to i64\n");
            String wsz = "%prv.wsz." + tmpCount++;
            pb.append("  ").append(wsz).append(" = mul i64 ").append(wl64).append(", 2\n");
            String wcmd = "%prv.wcmd." + tmpCount;
            pb.append("  ").append(wcmd).append(" = call i8* @").append(allocFn()).append("(i64 ").append(wsz).append(")\n");
            String wcmd16 = "%prv.wcmd16." + tmpCount++;
            pb.append("  ").append(wcmd16).append(" = bitcast i8* ").append(wcmd).append(" to i16*\n");
            String wc2 = "%prv.wc2." + tmpCount++;
            pb.append("  ").append(wc2).append(" = call i32 @MultiByteToWideChar(i32 65001, i32 0, i8* ").append(cmdUtf8)
                 .append(", i32 -1, i16* ").append(wcmd16).append(", i32 ").append(wl).append(")\n");
            // zero structs; field stores via byte-offset GEP (offsets probe-verified: si=104 pi=24 sa=24)
            String sa8 = "%prv.sa8." + tmpCount;
            String si8 = "%prv.si8." + tmpCount;
            String pi8 = "%prv.pi8." + tmpCount;
            tmpCount++;
            pb.append("  ").append(sa8).append(" = bitcast [24 x i8]* ").append(sa).append(" to i8*\n");
            pb.append("  ").append(si8).append(" = bitcast [104 x i8]* ").append(si).append(" to i8*\n");
            pb.append("  ").append(pi8).append(" = bitcast [24 x i8]* ").append(pi).append(" to i8*\n");
            pb.append("  call void @llvm.memset.p0i8.p0i8.i64(i8* ").append(sa8).append(", i8 0, i64 24, i1 false)\n");
            pb.append("  call void @llvm.memset.p0i8.p0i8.i64(i8* ").append(si8).append(", i8 0, i64 104, i1 false)\n");
            pb.append("  call void @llvm.memset.p0i8.p0i8.i64(i8* ").append(pi8).append(", i8 0, i64 24, i1 false)\n");
            // SA: nLength@0=24, bInheritHandle@16=1
            String saP0 = "%prv.sap0." + tmpCount++, saI0 = "%prv.sai0." + tmpCount++;
            pb.append("  ").append(saP0).append(" = getelementptr i8, i8* ").append(sa8).append(", i64 0\n");
            pb.append("  ").append(saI0).append(" = bitcast i8* ").append(saP0).append(" to i32*\n");
            pb.append("  store i32 24, i32* ").append(saI0).append("\n");
            String saP16 = "%prv.sap16." + tmpCount++, saI16 = "%prv.sai16." + tmpCount++;
            pb.append("  ").append(saP16).append(" = getelementptr i8, i8* ").append(sa8).append(", i64 16\n");
            pb.append("  ").append(saI16).append(" = bitcast i8* ").append(saP16).append(" to i32*\n");
            pb.append("  store i32 1, i32* ").append(saI16).append("\n");
            // SI: cb@0=104, dwFlags@60=STARTF_USESTDHANDLES(256), hStdInput@80, hStdOutput@88, hStdError@96
            String siP0 = "%prv.sip0." + tmpCount++, siI0 = "%prv.sii0." + tmpCount++;
            pb.append("  ").append(siP0).append(" = getelementptr i8, i8* ").append(si8).append(", i64 0\n");
            pb.append("  ").append(siI0).append(" = bitcast i8* ").append(siP0).append(" to i32*\n");
            pb.append("  store i32 104, i32* ").append(siI0).append("\n");
            String siP60 = "%prv.sip60." + tmpCount++, siI60 = "%prv.sii60." + tmpCount++;
            pb.append("  ").append(siP60).append(" = getelementptr i8, i8* ").append(si8).append(", i64 60\n");
            pb.append("  ").append(siI60).append(" = bitcast i8* ").append(siP60).append(" to i32*\n");
            pb.append("  store i32 256, i32* ").append(siI60).append("\n");
            String hIn = "%prv.hin." + tmpCount++, hInP = "%prv.hinp." + tmpCount++, hInPP = "%prv.hinpp." + tmpCount++;
            pb.append("  ").append(hIn).append(" = call i8* @GetStdHandle(i32 -10)\n");
            pb.append("  ").append(hInP).append(" = getelementptr i8, i8* ").append(si8).append(", i64 80\n");
            pb.append("  ").append(hInPP).append(" = bitcast i8* ").append(hInP).append(" to i8**\n");
            pb.append("  store i8* ").append(hIn).append(", i8** ").append(hInPP).append("\n");

            // CreatePipe(&rd, &wr, &sa, 0)
            String cpr = "%prv.cpr." + tmpCount++;
            pb.append("  ").append(cpr).append(" = call i32 @CreatePipe(i8** ").append(rd).append(", i8** ").append(wr)
                 .append(", i8* ").append(sa8).append(", i32 0)\n");
            String hOutP = "%prv.houtp." + tmpCount++, hOutPP = "%prv.houtpp." + tmpCount++;
            pb.append("  ").append(hOutP).append(" = getelementptr i8, i8* ").append(si8).append(", i64 88\n");
            pb.append("  ").append(hOutPP).append(" = bitcast i8* ").append(hOutP).append(" to i8**\n");
            String wrV = "%prv.wrv." + tmpCount++;
            pb.append("  ").append(wrV).append(" = load i8*, i8** ").append(wr).append("\n");
            pb.append("  store i8* ").append(wrV).append(", i8** ").append(hOutPP).append("\n");
            // hStdError = the SAME pipe write end: stderr lines flow into the callback too.
            String hErrP = "%prv.herrp." + tmpCount++, hErrPP = "%prv.herrpp." + tmpCount++;
            pb.append("  ").append(hErrP).append(" = getelementptr i8, i8* ").append(si8).append(", i64 96\n");
            pb.append("  ").append(hErrPP).append(" = bitcast i8* ").append(hErrP).append(" to i8**\n");
            pb.append("  store i8* ").append(wrV).append(", i8** ").append(hErrPP).append("\n");
            String cprOk = "%prv.cprok." + tmpCount++;
            pb.append("  ").append(cprOk).append(" = icmp eq i32 ").append(cpr).append(", 0\n");
            pb.append("  br i1 ").append(cprOk).append(", label %prl.pfail." + id + ", label %prl.proc." + id + "\n");
            pb.append("prl.proc.").append(id).append(":\n");
            String cpc = "%prv.cpc." + tmpCount++;
            pb.append("  ").append(cpc).append(" = call i32 @CreateProcessW(i16* null, i16* ").append(wcmd16)
                 .append(", i8* null, i8* null, i32 1, i32 134217728, i8* null, i16* null, i8* ").append(si8)
                 .append(", i8* ").append(pi8).append(")\n");
String cpOk = "%prv.cpok." + tmpCount++;
            pb.append("  ").append(cpOk).append(" = icmp eq i32 ").append(cpc).append(", 0\n");
            pb.append("  br i1 ").append(cpOk).append(", label %prl.cf." + id + ", label %prl.cok." + id + "\n");
            pb.append("prl.cf.").append(id).append(":\n");
            // CreateProcess failed: close both pipe ends (they exist) then bail with -1
            String rdV1 = "%prv.rdv1." + tmpCount++;
            pb.append("  ").append(rdV1).append(" = load i8*, i8** ").append(rd).append("\n");
            pb.append("  %prv.cc1.").append(id).append(" = call i32 @CloseHandle(i8* ").append(rdV1).append(")\n");
            String wrV1 = "%prv.wrv1." + tmpCount++;
            pb.append("  ").append(wrV1).append(" = load i8*, i8** ").append(wr).append("\n");
            pb.append("  %prv.cc2.").append(id).append(" = call i32 @CloseHandle(i8* ").append(wrV1).append(")\n");
            pb.append("  store i32 -2, i32* ").append(codeSlot).append("\n");
            pb.append("  br label %").append(lblRet).append("\n");
            pb.append("prl.pfail.").append(id).append(":\n");
            pb.append("  store i32 -2, i32* ").append(codeSlot).append("\n");
            pb.append("  br label %").append(lblRet).append("\n");
            pb.append("prl.cok.").append(id).append(":\n");
            String wrV2 = "%prv.wrv2." + tmpCount++;
            pb.append("  ").append(wrV2).append(" = load i8*, i8** ").append(wr).append("\n");
            pb.append("  %prv.cc3.").append(id).append(" = call i32 @CloseHandle(i8* ").append(wrV2).append(")\n");
            String now0 = "%prv.now0." + tmpCount++;
            pb.append("  ").append(now0).append(" = call i64 @GetTickCount64()\n");
            pb.append("  store i64 ").append(now0).append(", i64* ").append(startT).append("\n");
            pb.append("  br label %").append(lblReadLoop).append("\n");
            // ---- read loop (windows): PeekNamedPipe -> ReadFile / exit / timeout ----
pb.append(lblReadLoop).append(":\n");
            String rdV = "%prv.rdv." + tmpCount;
            pb.append("  ").append(rdV).append(" = load i8*, i8** ").append(rd).append("\n");
            tmpCount++;
            String pk = "%prv.pk." + tmpCount++;
pb.append("  store i32 0, i32* ").append(avail).append("\n");
            pb.append("  ").append(pk).append(" = call i32 @PeekNamedPipe(i8* ").append(rdV)
                 .append(", i8* null, i32 0, i8* null, i32* ").append(avail).append(", i32* null)\n");
            String av = "%prv.av." + tmpCount++;
            pb.append("  ").append(av).append(" = load i32, i32* ").append(avail).append("\n");
            String avGt = "%prv.avgt." + tmpCount++;
            pb.append("  ").append(avGt).append(" = icmp sgt i32 ").append(av).append(", 0\n");
            pb.append("  br i1 ").append(avGt).append(", label %prl.rd." + id + ", label %prl.chk." + id + "\n");
            pb.append("prl.rd.").append(id).append(":\n");
            String avMin = "%prv.avmin." + tmpCount++;
            pb.append("  ").append(avMin).append(" = icmp sgt i32 ").append(av).append(", 4096\n");
            String rdSz = "%prv.rdsz." + tmpCount++;
            pb.append("  ").append(rdSz).append(" = select i1 ").append(avMin).append(", i32 4096, i32 ").append(av).append("\n");
            String tb = "%prv.tb." + tmpCount;
            pb.append("  ").append(tb).append(" = bitcast [4096 x i8]* ").append(tmpBuf).append(" to i8*\n");
            tmpCount++;
            String rf = "%prv.rf." + tmpCount++;
            pb.append("  ").append(rf).append(" = call i32 @ReadFile(i8* ").append(rdV).append(", i8* ").append(tb)
                 .append(", i32 ").append(rdSz).append(", i32* ").append(nrd).append(", i8* null)\n");
        pb.append("  br label %").append(lblChunk).append("\n");
pb.append("prl.chk.").append(id).append(":\n");

            String exV = "%prv.exv." + tmpCount++;
            pb.append("  ").append(exV).append(" = load i32, i32* ").append(exited).append("\n");
            String exNz = "%prv.exnz." + tmpCount++;
            pb.append("  ").append(exNz).append(" = icmp ne i32 ").append(exV).append(", 0\n");
            pb.append("  br i1 ").append(exNz).append(", label %prl.finish." + id + ", label %prl.wait." + id + "\n");
            pb.append("prl.wait.").append(id).append(":\n");

            String piH = "%prv.pih." + tmpCount++;
            pb.append("  ").append(piH).append(" = bitcast i8* ").append(pi8).append(" to i8**\n");
            String hProc = "%prv.hproc." + tmpCount;
            pb.append("  ").append(hProc).append(" = load i8*, i8** ").append(piH).append("\n");
            tmpCount++;
            String ws = "%prv.ws." + tmpCount++;
            pb.append("  ").append(ws).append(" = call i32 @WaitForSingleObject(i8* ").append(hProc).append(", i32 0)\n");
            String still = "%prv.still." + tmpCount++;
            pb.append("  ").append(still).append(" = icmp eq i32 ").append(ws).append(", 258\n");
            pb.append("  br i1 ").append(still).append(", label %prl.tmo." + id + ", label %prl.exitedset." + id + "\n");
            pb.append("prl.exitedset.").append(id).append(":\n");
            pb.append("  store i32 1, i32* ").append(exited).append("\n");
            pb.append("  br label %").append(lblReadLoop).append("\n");
pb.append("prl.tmo.").append(id).append(":\n");
            String tNeg = "%prv.tneg." + tmpCount++;
            pb.append("  ").append(tNeg).append(" = icmp slt i32 %timeoutMs, 0\n");
            pb.append("  br i1 ").append(tNeg).append(", label %").append(lblSleep).append(", label %prl.tchk." + id + "\n");
            pb.append("prl.tchk.").append(id).append(":\n");
            String tDone = "%prv.tdone." + tmpCount;
            pb.append("  ").append(tDone).append(" = load i32, i32* ").append(timedOut).append("\n");
            tmpCount++;
            String tDoneNz = "%prv.tdnz." + tmpCount++;
            pb.append("  ").append(tDoneNz).append(" = icmp ne i32 ").append(tDone).append(", 0\n");
            pb.append("  br i1 ").append(tDoneNz).append(", label %").append(lblSleep).append(", label %prl.tnow." + id + "\n");
            pb.append("prl.tnow.").append(id).append(":\n");
            String now = "%prv.now." + tmpCount++;
            pb.append("  ").append(now).append(" = call i64 @GetTickCount64()\n");
            String st0 = "%prv.st0." + tmpCount;
            pb.append("  ").append(st0).append(" = load i64, i64* ").append(startT).append("\n");
            tmpCount++;
            String elapsed = "%prv.el." + tmpCount++;
            pb.append("  ").append(elapsed).append(" = sub i64 ").append(now).append(", ").append(st0).append("\n");
            String tmo64 = "%prv.tmo64." + tmpCount++;
            pb.append("  ").append(tmo64).append(" = sext i32 %timeoutMs to i64\n");
            String tHit = "%prv.thit." + tmpCount++;
            pb.append("  ").append(tHit).append(" = icmp sge i64 ").append(elapsed).append(", ").append(tmo64).append("\n");
            pb.append("  br i1 ").append(tHit).append(", label %").append(lblKill).append(", label %").append(lblSleep).append("\n");
            pb.append(lblSleep).append(":\n");
            pb.append("  call void @Sleep(i32 1)\n");
            pb.append("  br label %").append(lblReadLoop).append("\n");
            // kill: TerminateProcess; mark timedOut+exited; loop once more so buffered output still drains
            pb.append(lblKill).append(":\n");
            pb.append("  store i32 1, i32* ").append(timedOut).append("\n");
            String hProc2 = "%prv.hproc2." + tmpCount;
            pb.append("  ").append(hProc2).append(" = load i8*, i8** ").append(piH).append("\n");
            tmpCount++;
            String tp = "%prv.tp." + tmpCount++;
            pb.append("  ").append(tp).append(" = call i32 @TerminateProcess(i8* ").append(hProc2).append(", i32 1)\n");
            pb.append("  store i32 1, i32* ").append(exited).append("\n");
            pb.append("  br label %").append(lblReadLoop).append("\n");
            // finish: GetExitCodeProcess -> codeSlot (timedOut -> -1)
pb.append("prl.finish.").append(id).append(":\n");

            String exitc = "%prv.exitc." + tmpCount;
            pb.append("  ").append(exitc).append(" = alloca i32\n");
            tmpCount++;
            String piH3 = "%prv.pih3." + tmpCount++;
            pb.append("  ").append(piH3).append(" = bitcast i8* ").append(pi8).append(" to i8**\n");
            String hProc3 = "%prv.hproc3." + tmpCount;
            pb.append("  ").append(hProc3).append(" = load i8*, i8** ").append(piH3).append("\n");
            tmpCount++;
            String gec = "%prv.gec." + tmpCount++;
            pb.append("  ").append(gec).append(" = call i32 @GetExitCodeProcess(i8* ").append(hProc3).append(", i32* ").append(exitc).append(")\n");
            String tDone2 = "%prv.td2." + tmpCount;
            pb.append("  ").append(tDone2).append(" = load i32, i32* ").append(timedOut).append("\n");
            tmpCount++;
            String tDone2Nz = "%prv.td2nz." + tmpCount++;
            pb.append("  ").append(tDone2Nz).append(" = icmp ne i32 ").append(tDone2).append(", 0\n");
            String ec = "%prv.ec." + tmpCount++;
            pb.append("  ").append(ec).append(" = load i32, i32* ").append(exitc).append("\n");
            String fin = "%prv.fin." + tmpCount++;
            pb.append("  ").append(fin).append(" = select i1 ").append(tDone2Nz).append(", i32 -1, i32 ").append(ec).append("\n");
            pb.append("  store i32 ").append(fin).append(", i32* ").append(codeSlot).append("\n");
            pb.append("  br label %").append(lblRet).append("\n");
        } else {
            // ---- POSIX spawn: pipe+fork+execvp; read via poll+read; reap via waitpid ----
            pb.append(lblSpawn).append(":\n");
            String fds = "%prv.fds." + tmpCount++;
            pb.append("  ").append(fds).append(" = alloca [2 x i32]\n");
            String fdsp = "%prv.fdsp." + tmpCount++;
            pb.append("  ").append(fdsp).append(" = bitcast [2 x i32]* ").append(fds).append(" to i32*\n");
            String pr = "%prv.pr." + tmpCount++;
            pb.append("  ").append(pr).append(" = call i32 @pipe(i32* ").append(fdsp).append(")\n");
            String prOk = "%prv.prok." + tmpCount++;
            pb.append("  ").append(prOk).append(" = icmp eq i32 ").append(pr).append(", 0\n");
            String pipeFail = "prl.pipefail." + id;
            pb.append("  br i1 ").append(prOk).append(", label %prl.fork." + id + ", label %").append(pipeFail).append("\n");
            pb.append(pipeFail).append(":\n");
            pb.append("  store i32 -2, i32* ").append(codeSlot).append("\n");
            pb.append("  br label %").append(lblRet).append("\n");
            pb.append("prl.fork.").append(id).append(":\n");
            String pidV = "%prv.pid." + tmpCount++;
            pb.append("  ").append(pidV).append(" = alloca i32\n");
            String pfdV = "%prv.pfd." + tmpCount++;
            pb.append("  ").append(pfdV).append(" = alloca i64\n");
            String tvV = "%prv.tv." + tmpCount++;
            pb.append("  ").append(tvV).append(" = alloca [16 x i8]\n");
            String fv = "%prv.fv." + tmpCount;
            String fvg = fv + "g";
            pb.append("  ").append(fvg).append(" = getelementptr [2 x i32], [2 x i32]* ").append(fds).append(", i32 0, i32 0\n");
            pb.append("  ").append(fv).append(" = load i32, i32* ").append(fvg).append("\n");
            tmpCount++;
            String pid = "%prv.p." + tmpCount++;
            pb.append("  ").append(pid).append(" = call i32 @fork()\n");
            String pidNeg = "%prv.pneg." + tmpCount++;
            pb.append("  ").append(pidNeg).append(" = icmp slt i32 ").append(pid).append(", 0\n");
            pb.append("  br i1 ").append(pidNeg).append(", label %prl.fkill." + id + ", label %prl.fpid." + id + "\n");
            pb.append("prl.fpid.").append(id).append(":\n");
            String pidZero = "%prv.pzero." + tmpCount++;
            pb.append("  ").append(pidZero).append(" = icmp eq i32 ").append(pid).append(", 0\n");
            pb.append("  br i1 ").append(pidZero).append(", label %prl.child." + id + ", label %prl.parent." + id + "\n");
            // child: close read end, dup2 write -> stdout+stderr, execvp; _exit(127)
            pb.append("prl.child.").append(id).append(":\n");
            String f0c = "%prv.f0c." + tmpCount;
            String f0cg = f0c + "g";
            pb.append("  ").append(f0cg).append(" = getelementptr [2 x i32], [2 x i32]* ").append(fds).append(", i32 0, i32 0\n");
            pb.append("  ").append(f0c).append(" = load i32, i32* ").append(f0cg).append("\n");
            tmpCount++;
            String cl0 = "%prv.cl0." + tmpCount++;
            pb.append("  ").append(cl0).append(" = call i32 @close(i32 ").append(f0c).append(")\n");
            String f1c = "%prv.f1c." + tmpCount;
            String f1cg = f1c + "g";
            pb.append("  ").append(f1cg).append(" = getelementptr [2 x i32], [2 x i32]* ").append(fds).append(", i32 0, i32 1\n");
            pb.append("  ").append(f1c).append(" = load i32, i32* ").append(f1cg).append("\n");
            tmpCount++;
            String d2 = "%prv.d2." + tmpCount++;
            pb.append("  ").append(d2).append(" = call i32 @dup2(i32 ").append(f1c).append(", i32 1)\n");
            String d2e = "%prv.d2e." + tmpCount++;
            pb.append("  ").append(d2e).append(" = call i32 @dup2(i32 ").append(f1c).append(", i32 2)\n");
            String cl1 = "%prv.cl1." + tmpCount++;
            pb.append("  ").append(cl1).append(" = call i32 @close(i32 ").append(f1c).append(")\n");
            String a0s2 = "%prv.a0s2." + tmpCount++;
            pb.append("  ").append(a0s2).append(" = bitcast i8* ").append(argv).append(" to i8**\n");
            String a0c = "%prv.a0c." + tmpCount++;
            pb.append("  ").append(a0c).append(" = load i8*, i8** ").append(a0s2).append("\n");
            String ev = "%prv.ev." + tmpCount++;
            pb.append("  ").append(ev).append(" = call i32 @execvp(i8* ").append(a0c).append(", i8** ").append(argv).append(")\n");
            pb.append("  call void @_exit(i32 127)\n");
            pb.append("  unreachable\n");
            // parent: keep pid, close write end, stamp start time (gettimeofday ms)
            pb.append("prl.parent.").append(id).append(":\n");
            pb.append("  store i32 ").append(pid).append(", i32* ").append(pidV).append("\n");
            String f1p = "%prv.f1p." + tmpCount;
            String f1pg = f1p + "g";
            pb.append("  ").append(f1pg).append(" = getelementptr [2 x i32], [2 x i32]* ").append(fds).append(", i32 0, i32 1\n");
            pb.append("  ").append(f1p).append(" = load i32, i32* ").append(f1pg).append("\n");
            tmpCount++;
            String cl1p = "%prv.cl1p." + tmpCount++;
            pb.append("  ").append(cl1p).append(" = call i32 @close(i32 ").append(f1p).append(")\n");
            String tva = "%prv.tva." + tmpCount;
            pb.append("  ").append(tva).append(" = bitcast [16 x i8]* ").append(tvV).append(" to i8*\n");
            tmpCount++;
            String gtv = "%prv.gtv." + tmpCount++;
            pb.append("  ").append(gtv).append(" = call i32 @gettimeofday(i8* ").append(tva).append(", i8* null)\n");
            String secP = "%prv.secp." + tmpCount++;
            pb.append("  ").append(secP).append(" = bitcast [16 x i8]* ").append(tvV).append(" to i64*\n");
            String secV = "%prv.secv." + tmpCount++;
            pb.append("  ").append(secV).append(" = load i64, i64* ").append(secP).append("\n");
            String usecP = "%prv.usecp." + tmpCount++;
            pb.append("  ").append(usecP).append(" = getelementptr i8, i8* ").append(tva).append(", i64 8\n");
            String usecI = "%prv.useci." + tmpCount++;
            pb.append("  ").append(usecI).append(" = bitcast i8* ").append(usecP).append(" to i64*\n");
            String usecV = "%prv.usecv." + tmpCount++;
            pb.append("  ").append(usecV).append(" = load i64, i64* ").append(usecI).append("\n");
            String secMs = "%prv.secms." + tmpCount++;
            pb.append("  ").append(secMs).append(" = mul i64 ").append(secV).append(", 1000\n");
            String usecMs = "%prv.usecms." + tmpCount++;
            pb.append("  ").append(usecMs).append(" = udiv i64 ").append(usecV).append(", 1000\n");
            String nowMs = "%prv.nowms." + tmpCount++;
            pb.append("  ").append(nowMs).append(" = add i64 ").append(secMs).append(", ").append(usecMs).append("\n");
            pb.append("  store i64 ").append(nowMs).append(", i64* ").append(startT).append("\n");
            pb.append("  br label %").append(lblReadLoop).append("\n");
            // fork failed: close pipe fds (valid), bail -1
            pb.append("prl.fkill.").append(id).append(":\n");
            String f0k = "%prv.f0k." + tmpCount;
            String f0kg = f0k + "g";
            pb.append("  ").append(f0kg).append(" = getelementptr [2 x i32], [2 x i32]* ").append(fds).append(", i32 0, i32 0\n");
            pb.append("  ").append(f0k).append(" = load i32, i32* ").append(f0kg).append("\n");
            tmpCount++;
            String cl0k = "%prv.cl0k." + tmpCount++;
            pb.append("  ").append(cl0k).append(" = call i32 @close(i32 ").append(f0k).append(")\n");
            String f1k = "%prv.f1k." + tmpCount;
            String f1kg = f1k + "g";
            pb.append("  ").append(f1kg).append(" = getelementptr [2 x i32], [2 x i32]* ").append(fds).append(", i32 0, i32 1\n");
            pb.append("  ").append(f1k).append(" = load i32, i32* ").append(f1kg).append("\n");
            tmpCount++;
            String cl1k = "%prv.cl1k." + tmpCount++;
            pb.append("  ").append(cl1k).append(" = call i32 @close(i32 ").append(f1k).append(")\n");
            pb.append("  store i32 -2, i32* ").append(codeSlot).append("\n");
            pb.append("  br label %").append(lblRet).append("\n");
            // ---- read loop (posix): pack pollfd {fd, POLLIN} as i64, poll(20ms), read ----
            pb.append(lblReadLoop).append(":\n");
            String f0l = "%prv.f0l." + tmpCount;
            String f0lg = f0l + "g";
            pb.append("  ").append(f0lg).append(" = getelementptr [2 x i32], [2 x i32]* ").append(fds).append(", i32 0, i32 0\n");
            pb.append("  ").append(f0l).append(" = load i32, i32* ").append(f0lg).append("\n");
            tmpCount++;
            String f0z = "%prv.f0z." + tmpCount++;
            pb.append("  ").append(f0z).append(" = zext i32 ").append(f0l).append(" to i64\n");
            String pack = "%prv.pack." + tmpCount++;
            pb.append("  ").append(pack).append(" = or i64 ").append(f0z).append(", 4294967296\n");
            pb.append("  store i64 ").append(pack).append(", i64* ").append(pfdV).append("\n");
            String pfa = "%prv.pfa." + tmpCount++;
            pb.append("  ").append(pfa).append(" = bitcast i64* ").append(pfdV).append(" to i8*\n");
            String ppr = "%prv.ppr." + tmpCount++;
            pb.append("  ").append(ppr).append(" = call i32 @poll(i8* ").append(pfa).append(", i64 1, i32 20)\n");
            // timeout check (after the 20ms poll)
            String tN = "%prv.tn." + tmpCount++;
            pb.append("  ").append(tN).append(" = icmp slt i32 %timeoutMs, 0\n");
            pb.append("  br i1 ").append(tN).append(", label %prl.pr." + id + ", label %prl.pt." + id + "\n");
            pb.append("prl.pt.").append(id).append(":\n");
            String tD = "%prv.td." + tmpCount;
            pb.append("  ").append(tD).append(" = load i32, i32* ").append(timedOut).append("\n");
            tmpCount++;
            String tDz = "%prv.tdz." + tmpCount++;
            pb.append("  ").append(tDz).append(" = icmp ne i32 ").append(tD).append(", 0\n");
            pb.append("  br i1 ").append(tDz).append(", label %prl.pr." + id + ", label %prl.pnow." + id + "\n");
            pb.append("prl.pnow.").append(id).append(":\n");
            String tvB = "%prv.tvb." + tmpCount;
            pb.append("  ").append(tvB).append(" = bitcast [16 x i8]* ").append(tvV).append(" to i8*\n");
            tmpCount++;
            String gtv2 = "%prv.gtv2." + tmpCount++;
            pb.append("  ").append(gtv2).append(" = call i32 @gettimeofday(i8* ").append(tvB).append(", i8* null)\n");
            String secP2 = "%prv.secp2." + tmpCount;
            pb.append("  ").append(secP2).append(" = bitcast [16 x i8]* ").append(tvV).append(" to i64*\n");
            tmpCount++;
            String secV2 = "%prv.secv2." + tmpCount;
            pb.append("  ").append(secV2).append(" = load i64, i64* ").append(secP2).append("\n");
            String usecP2 = "%prv.usecp2." + tmpCount;
            pb.append("  ").append(usecP2).append(" = getelementptr i8, i8* ").append(tvB).append(", i64 8\n");
            tmpCount++;
            String usecI2 = "%prv.useci2." + tmpCount;
            pb.append("  ").append(usecI2).append(" = bitcast i8* ").append(usecP2).append(" to i64*\n");
            String usecV2 = "%prv.usecv2." + tmpCount;
            pb.append("  ").append(usecV2).append(" = load i64, i64* ").append(usecI2).append("\n");
            String secMs2 = "%prv.secms2." + tmpCount++;
            pb.append("  ").append(secMs2).append(" = mul i64 ").append(secV2).append(", 1000\n");
            String usecMs2 = "%prv.usecms2." + tmpCount++;
            pb.append("  ").append(usecMs2).append(" = udiv i64 ").append(usecV2).append(", 1000\n");
            String nowMs2 = "%prv.nowms2." + tmpCount++;
            pb.append("  ").append(nowMs2).append(" = add i64 ").append(secMs2).append(", ").append(usecMs2).append("\n");
            String st1 = "%prv.st1." + tmpCount;
            pb.append("  ").append(st1).append(" = load i64, i64* ").append(startT).append("\n");
            tmpCount++;
            String el2 = "%prv.el2." + tmpCount++;
            pb.append("  ").append(el2).append(" = sub i64 ").append(nowMs2).append(", ").append(st1).append("\n");
            String tmo2 = "%prv.tmo2." + tmpCount++;
            pb.append("  ").append(tmo2).append(" = sext i32 %timeoutMs to i64\n");
            String hit2 = "%prv.hit2." + tmpCount++;
            pb.append("  ").append(hit2).append(" = icmp sge i64 ").append(el2).append(", ").append(tmo2).append("\n");
            pb.append("  br i1 ").append(hit2).append(", label %").append(lblKill).append(", label %prl.pr." + id + "\n");
            pb.append("prl.pr.").append(id).append(":\n");
            String tb2 = "%prv.tb2." + tmpCount;
            pb.append("  ").append(tb2).append(" = bitcast [4096 x i8]* ").append(tmpBuf).append(" to i8*\n");
            tmpCount++;
            String f0r = "%prv.f0r." + tmpCount;
            String f0rg = f0r + "g";
            pb.append("  ").append(f0rg).append(" = getelementptr [2 x i32], [2 x i32]* ").append(fds).append(", i32 0, i32 0\n");
            pb.append("  ").append(f0r).append(" = load i32, i32* ").append(f0rg).append("\n");
            tmpCount++;
            String nr = "%prv.nr." + tmpCount++;
            pb.append("  ").append(nr).append(" = call i64 @read(i32 ").append(f0r).append(", i8* ").append(tb2).append(", i64 4096)\n");
            String nrPos = "%prv.nrpos." + tmpCount++;
            pb.append("  ").append(nrPos).append(" = icmp sgt i64 ").append(nr).append(", 0\n");
            pb.append("  br i1 ").append(nrPos).append(", label %prl.prdata." + id + ", label %").append("prl.finish." + id).append("\n");
            pb.append("prl.prdata.").append(id).append(":\n");
            String nrTr = "%prv.nrtr." + tmpCount++;
            pb.append("  ").append(nrTr).append(" = trunc i64 ").append(nr).append(" to i32\n");
            pb.append("  store i32 ").append(nrTr).append(", i32* ").append(nrd).append("\n");
            pb.append("  br label %").append(lblChunk).append("\n");
            // kill: SIGKILL the child; loop back so the pipe still drains to EOF
            pb.append(lblKill).append(":\n");
            pb.append("  store i32 1, i32* ").append(timedOut).append("\n");
            String pidK = "%prv.pidk." + tmpCount;
            pb.append("  ").append(pidK).append(" = load i32, i32* ").append(pidV).append("\n");
            tmpCount++;
            String kl = "%prv.kl." + tmpCount++;
            pb.append("  ").append(kl).append(" = call i32 @kill(i32 ").append(pidK).append(", i32 9)\n");
            pb.append("  br label %").append(lblReadLoop).append("\n");
            // finish: waitpid + WIFEXITED -> code (non-exited/timeout -> -1; 127 -> -1 not-found map)
            pb.append("prl.finish.").append(id).append(":\n");
            String stA = "%prv.sta." + tmpCount;
            pb.append("  ").append(stA).append(" = alloca i32\n");
            tmpCount++;
            String pidR = "%prv.pidr." + tmpCount;
            pb.append("  ").append(pidR).append(" = load i32, i32* ").append(pidV).append("\n");
            tmpCount++;
            String wp = "%prv.wp." + tmpCount++;
            pb.append("  ").append(wp).append(" = call i32 @waitpid(i32 ").append(pidR).append(", i32* ").append(stA).append(", i32 0)\n");
            String stV = "%prv.stv." + tmpCount++;
            pb.append("  ").append(stV).append(" = load i32, i32* ").append(stA).append("\n");
            String wif = "%prv.wif." + tmpCount++;
            pb.append("  ").append(wif).append(" = and i32 ").append(stV).append(", 127\n");
            String wifZ = "%prv.wifz." + tmpCount++;
            pb.append("  ").append(wifZ).append(" = icmp eq i32 ").append(wif).append(", 0\n");
            String shr = "%prv.shr." + tmpCount++;
            pb.append("  ").append(shr).append(" = lshr i32 ").append(stV).append(", 8\n");
            String xc = "%prv.xc." + tmpCount++;
            pb.append("  ").append(xc).append(" = and i32 ").append(shr).append(", 255\n");
            String cOk = "%prv.cok2." + tmpCount++;
            pb.append("  ").append(cOk).append(" = select i1 ").append(wifZ).append(", i32 ").append(xc).append(", i32 -1\n");
            String c127 = "%prv.c127." + tmpCount++;
            pb.append("  ").append(c127).append(" = icmp eq i32 ").append(cOk).append(", 127\n");
            String cNf = "%prv.cnf." + tmpCount++;
            pb.append("  ").append(cNf).append(" = select i1 ").append(c127).append(", i32 -2, i32 ").append(cOk).append("\n");
            String tD2 = "%prv.td2p." + tmpCount;
            pb.append("  ").append(tD2).append(" = load i32, i32* ").append(timedOut).append("\n");
            tmpCount++;
            String tD2z = "%prv.td2zp." + tmpCount++;
            pb.append("  ").append(tD2z).append(" = icmp eq i32 ").append(tD2).append(", 0\n");
            String cFin = "%prv.cfin." + tmpCount++;
            pb.append("  ").append(cFin).append(" = select i1 ").append(tD2z).append(", i32 ").append(cNf).append(", i32 -1\n");
            pb.append("  store i32 ").append(cFin).append(", i32* ").append(codeSlot).append("\n");
            pb.append("  br label %").append(lblRet).append("\n");
        }
        // ---- shared: chunk -> line scan -> callback -> back to the platform read loop ----
        pb.append(lblChunk).append(":\n");

        String tb3 = "%prv.tb3." + tmpCount;
        pb.append("  ").append(tb3).append(" = bitcast [4096 x i8]* ").append(tmpBuf).append(" to i8*\n");
        tmpCount++;
        pb.append("  store i8* ").append(tb3).append(", i8** ").append(srcPtr).append("\n");
        String n32 = "%prv.n32." + tmpCount;
        pb.append("  ").append(n32).append(" = load i32, i32* ").append(nrd).append("\n");
        tmpCount++;
        String n64 = "%prv.n64." + tmpCount++;
        pb.append("  ").append(n64).append(" = zext i32 ").append(n32).append(" to i64\n");
        pb.append("  store i64 ").append(n64).append(", i64* ").append(rem).append("\n");
        pb.append("  br label %").append(lblScan).append("\n");
        pb.append(lblScan).append(":\n");

        String s0 = "%prv.s0." + tmpCount++;
        pb.append("  ").append(s0).append(" = load i8*, i8** ").append(srcPtr).append("\n");
        String r0 = "%prv.r0." + tmpCount++;
        pb.append("  ").append(r0).append(" = load i64, i64* ").append(rem).append("\n");
        String rZ = "%prv.rz." + tmpCount++;
        pb.append("  ").append(rZ).append(" = icmp eq i64 ").append(r0).append(", 0\n");
        pb.append("  br i1 ").append(rZ).append(", label %").append(lblReadLoop).append(", label %").append(lblScanAppend).append("\n");
        pb.append(lblScanAppend).append(":\n");
        String nl = "%prv.nl." + tmpCount++;
        pb.append("  ").append(nl).append(" = call i8* @memchr(i8* ").append(s0).append(", i32 10, i64 ").append(r0).append(")\n");
        String nlZ = "%prv.nlz." + tmpCount++;
        pb.append("  ").append(nlZ).append(" = icmp eq i8* ").append(nl).append(", null\n");
        pb.append("  br i1 ").append(nlZ).append(", label %prl.accum." + id + ", label %").append(lblScanLine).append("\n");
        // no newline: append the whole chunk to pending, consume rem, back to the read loop
        pb.append("prl.accum.").append(id).append(":\n");
        String pOldL = "%prv.poldl." + tmpCount;
        pb.append("  ").append(pOldL).append(" = load i64, i64* ").append(pendLen).append("\n");
        tmpCount++;
        String nLen = "%prv.nlen." + tmpCount++;
        pb.append("  ").append(nLen).append(" = add i64 ").append(pOldL).append(", ").append(r0).append("\n");
        String nSz = "%prv.nsz." + tmpCount++;
        pb.append("  ").append(nSz).append(" = add i64 ").append(nLen).append(", 1\n");
        String nB = "%prv.nb." + tmpCount;
        pb.append("  ").append(nB).append(" = call i8* @").append(allocFn()).append("(i64 ").append(nSz).append(")\n");
        tmpCount++;
        String pOld = "%prv.pold." + tmpCount;
        pb.append("  ").append(pOld).append(" = load i8*, i8** ").append(pend).append("\n");
        tmpCount++;
        String pOldZ = "%prv.poldz." + tmpCount++;
        pb.append("  ").append(pOldZ).append(" = icmp ne i8* ").append(pOld).append(", null\n");
        String lCpOld = "prl.acc.old." + id, lCpNew = "prl.acc.new." + id;
        pb.append("  br i1 ").append(pOldZ).append(", label %").append(lCpOld).append(", label %").append(lCpNew).append("\n");
        pb.append(lCpOld).append(":\n");
        pb.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* ").append(nB).append(", i8* ").append(pOld)
             .append(", i64 ").append(pOldL).append(", i1 false)\n");
        pb.append("  br label %").append(lCpNew).append("\n");
        pb.append(lCpNew).append(":\n");
        String nDst = "%prv.ndst." + tmpCount++;
        pb.append("  ").append(nDst).append(" = getelementptr i8, i8* ").append(nB).append(", i64 ").append(pOldL).append("\n");
        pb.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* ").append(nDst).append(", i8* ").append(s0)
             .append(", i64 ").append(r0).append(", i1 false)\n");
        String nEnd = "%prv.nend." + tmpCount++;
        pb.append("  ").append(nEnd).append(" = getelementptr i8, i8* ").append(nB).append(", i64 ").append(nLen).append("\n");
        pb.append("  store i8 0, i8* ").append(nEnd).append("\n");
        pb.append("  br i1 ").append(pOldZ).append(", label %prl.acc.free." + id + ", label %prl.acc.store." + id + "\n");
        pb.append("prl.acc.free.").append(id).append(":\n");
        pb.append("  call void @").append(freeFn()).append("(i8* ").append(pOld).append(")\n");
        pb.append("  br label %prl.acc.store.").append(id).append("\n");
        pb.append("prl.acc.store.").append(id).append(":\n");
        pb.append("  store i8* ").append(nB).append(", i8** ").append(pend).append("\n");
        pb.append("  store i64 ").append(nLen).append(", i64* ").append(pendLen).append("\n");
        pb.append("  store i64 0, i64* ").append(rem).append("\n");
        pb.append("  br label %").append(lblReadLoop).append("\n");
        // newline: build line = pending + segment (strip a trailing \r), callback, reset
        pb.append(lblScanLine).append(":\n");
        String segLen = "%prv.segl." + tmpCount++;
        pb.append("  ").append(segLen).append(" = ptrtoint i8* ").append(nl).append(" to i64\n");
        String srcI = "%prv.srci." + tmpCount++;
        pb.append("  ").append(srcI).append(" = ptrtoint i8* ").append(s0).append(" to i64\n");
        String segOnly = "%prv.sego." + tmpCount++;
        pb.append("  ").append(segOnly).append(" = sub i64 ").append(segLen).append(", ").append(srcI).append("\n");
        String pL1 = "%prv.pl1." + tmpCount;
        pb.append("  ").append(pL1).append(" = load i64, i64* ").append(pendLen).append("\n");
        tmpCount++;
        String lineLen = "%prv.llen." + tmpCount++;
        pb.append("  ").append(lineLen).append(" = add i64 ").append(pL1).append(", ").append(segOnly).append("\n");
        String lineSz = "%prv.lsz." + tmpCount++;
        pb.append("  ").append(lineSz).append(" = add i64 ").append(lineLen).append(", 1\n");
        String line = "%prv.line." + tmpCount;
        pb.append("  ").append(line).append(" = call i8* @").append(allocFn()).append("(i64 ").append(lineSz).append(")\n");
        tmpCount++;
        String p1 = "%prv.p1." + tmpCount;
        pb.append("  ").append(p1).append(" = load i8*, i8** ").append(pend).append("\n");
        tmpCount++;
        String p1Z = "%prv.p1z." + tmpCount++;
        pb.append("  ").append(p1Z).append(" = icmp ne i8* ").append(p1).append(", null\n");
        pb.append("  br i1 ").append(p1Z).append(", label %prl.li.old." + id + ", label %prl.li.new." + id + "\n");
        pb.append("prl.li.old.").append(id).append(":\n");
        pb.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* ").append(line).append(", i8* ").append(p1)
             .append(", i64 ").append(pL1).append(", i1 false)\n");
        pb.append("  br label %prl.li.new.").append(id).append("\n");
        pb.append("prl.li.new.").append(id).append(":\n");
        String lDst = "%prv.ldst." + tmpCount++;
        pb.append("  ").append(lDst).append(" = getelementptr i8, i8* ").append(line).append(", i64 ").append(pL1).append("\n");
        pb.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* ").append(lDst).append(", i8* ").append(s0)
             .append(", i64 ").append(segOnly).append(", i1 false)\n");
        String lEnd = "%prv.lend." + tmpCount++;
        pb.append("  ").append(lEnd).append(" = getelementptr i8, i8* ").append(line).append(", i64 ").append(lineLen).append("\n");
        pb.append("  store i8 0, i8* ").append(lEnd).append("\n");
        // CRLF strip: last byte '\r' -> shorten
        pb.append("  br i1 ").append(p1Z).append(", label %prl.li.oldfree." + id + ", label %prl.li.cr." + id + "\n");
        pb.append("prl.li.oldfree.").append(id).append(":\n");
        pb.append("  call void @").append(freeFn()).append("(i8* ").append(p1).append(")\n");
        pb.append("  br label %prl.li.cr.").append(id).append("\n");
        pb.append("prl.li.cr.").append(id).append(":\n");
        String crZ = "%prv.crz." + tmpCount++;
        pb.append("  ").append(crZ).append(" = icmp eq i64 ").append(lineLen).append(", 0\n");
        pb.append("  br i1 ").append(crZ).append(", label %prl.li.cb." + id + ", label %prl.li.crt." + id + "\n");
        pb.append("prl.li.crt.").append(id).append(":\n");
        String lastIdx = "%prv.lastidx." + tmpCount++;
        pb.append("  ").append(lastIdx).append(" = add i64 ").append(lineLen).append(", -1\n");
        String lastP = "%prv.lastp." + tmpCount++;
        pb.append("  ").append(lastP).append(" = getelementptr i8, i8* ").append(line).append(", i64 ")
             .append(lastIdx).append("\n");
        String lastV = "%prv.lastv." + tmpCount++;
        pb.append("  ").append(lastV).append(" = load i8, i8* ").append(lastP).append("\n");
        String isCr = "%prv.iscr." + tmpCount++;
        pb.append("  ").append(isCr).append(" = icmp eq i8 ").append(lastV).append(", 13\n");
        pb.append("  br i1 ").append(isCr).append(", label %prl.li.cut." + id + ", label %prl.li.cb." + id + "\n");
        pb.append("prl.li.cut.").append(id).append(":\n");
        String cutEnd = "%prv.cutend." + tmpCount++;
        pb.append("  ").append(cutEnd).append(" = getelementptr i8, i8* ").append(line).append(", i64 ")
             .append(lastIdx).append("\n");
        pb.append("  store i8 0, i8* ").append(cutEnd).append("\n");
        pb.append("  br label %prl.li.cb." + id + "\n");
        // callback: void %code(i8* receiver, i8* line)
        pb.append("prl.li.cb.").append(id).append(":\n");
        pb.append("  %prv.fc.").append(id).append(" = extractvalue %CangFunction %cb, 0\n");
        pb.append("  %prv.fr.").append(id).append(" = extractvalue %CangFunction %cb, 1\n");
        pb.append("  %prv.fp.").append(id).append(" = bitcast i8* %prv.fc.").append(id).append(" to void (i8*, i8*)*\n");
        pb.append("  call void %prv.fp.").append(id).append("(i8* %prv.fr.").append(id).append(", i8* ").append(line).append(")\n");

        // reset pending, advance src past the newline
        pb.append("  store i8* null, i8** ").append(pend).append("\n");
        pb.append("  store i64 0, i64* ").append(pendLen).append("\n");
        String sN = "%prv.sn2." + tmpCount++;
        pb.append("  ").append(sN).append(" = getelementptr i8, i8* ").append(nl).append(", i64 1\n");
        pb.append("  store i8* ").append(sN).append(", i8** ").append(srcPtr).append("\n");
        String segI64 = "%prv.segi." + tmpCount;
        pb.append("  ").append(segI64).append(" = sext i32 1 to i64\n");
        tmpCount++;
        String adv = "%prv.adv." + tmpCount++;
        pb.append("  ").append(adv).append(" = add i64 ").append(segOnly).append(", 1\n");
        String rN = "%prv.rn." + tmpCount++;
        pb.append("  ").append(rN).append(" = sub i64 ").append(r0).append(", ").append(adv).append("\n");
        pb.append("  store i64 ").append(rN).append(", i64* ").append(rem).append("\n");
        pb.append("  br label %").append(lblScan).append("\n");
        // ---- shared return: flush a trailing partial line, free argv, return the code ----
        pb.append(lblRet).append(":\n");
        String fL = "%prv.fl." + tmpCount;
        pb.append("  ").append(fL).append(" = load i64, i64* ").append(pendLen).append("\n");
        tmpCount++;
        String fZ = "%prv.fz." + tmpCount++;
        pb.append("  ").append(fZ).append(" = icmp eq i64 ").append(fL).append(", 0\n");
        pb.append("  br i1 ").append(fZ).append(", label %prl.freearg." + id + ", label %prl.tail." + id + "\n");
        pb.append("prl.tail.").append(id).append(":\n");
        String tP = "%prv.tp2." + tmpCount;
        pb.append("  ").append(tP).append(" = load i8*, i8** ").append(pend).append("\n");
        tmpCount++;
        // strip a trailing \r in place (pend is NUL-terminated)
        String tLastIdx = "%prv.tlastidx." + tmpCount++;
        pb.append("  ").append(tLastIdx).append(" = add i64 ").append(fL).append(", -1\n");
        String tLastP = "%prv.tlastp." + tmpCount++;
        pb.append("  ").append(tLastP).append(" = getelementptr i8, i8* ").append(tP).append(", i64 ").append(tLastIdx).append("\n");
        String tLast = "%prv.tlast." + tmpCount++;
        pb.append("  ").append(tLast).append(" = load i8, i8* ").append(tLastP).append("\n");
        String tIsCr = "%prv.tiscr." + tmpCount++;
        pb.append("  ").append(tIsCr).append(" = icmp eq i8 ").append(tLast).append(", 13\n");
        pb.append("  br i1 ").append(tIsCr).append(", label %prl.tail.cr." + id + ", label %prl.tail.cb." + id + "\n");
        pb.append("prl.tail.cr.").append(id).append(":\n");
        pb.append("  store i8 0, i8* ").append(tLastP).append("\n");
        pb.append("  br label %prl.tail.cb.").append(id).append("\n");
        pb.append("prl.tail.cb.").append(id).append(":\n");
        pb.append("  %prv.tc.").append(id).append(" = extractvalue %CangFunction %cb, 0\n");
        pb.append("  %prv.tr.").append(id).append(" = extractvalue %CangFunction %cb, 1\n");
        pb.append("  %prv.tp3.").append(id).append(" = bitcast i8* %prv.tc.").append(id).append(" to void (i8*, i8*)*\n");
        pb.append("  call void %prv.tp3.").append(id).append("(i8* %prv.tr.").append(id).append(", i8* ").append(tP).append(")\n");
        pb.append("  store i8* null, i8** ").append(pend).append("\n");
        pb.append("  store i64 0, i64* ").append(pendLen).append("\n");
        pb.append("  br label %prl.freearg.").append(id).append("\n");
        pb.append("prl.freearg.").append(id).append(":\n");
        pb.append("  call void @").append(freeFn()).append("(i8* ").append(argv).append(")\n");
        String retC = "%prv.retc." + tmpCount++;
        pb.append("  ").append(retC).append(" = load i32, i32* ").append(codeSlot).append("\n");
        pb.append("  ret i32 ").append(retC).append("\n");
        pb.append("}\n\n");
        header.append(pb);
    }

    private void emitFileRuntime() {
        ClassInfo fileCi = classes.get("cang_io_File");
        if (fileCi == null) return;
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

    /**
     * Wrapper defines for cang/lang/StringBuilder (Design B, same shape as File).
     * append grows a private byte buffer (double on demand); toString snapshot-copies.
     */
    private void emitStringBuilderWrappers() {
        ClassInfo sbCi = classes.get("cang_lang_StringBuilder");
        if (sbCi == null) return;
        Integer capI = sbCi.fieldIndices.get("cap");
        Integer dataI = sbCi.fieldIndices.get("data");
        Integer sizeI = sbCi.fieldIndices.get("size");
        if (capI == null || dataI == null || sizeI == null) return;
        int capIdx = capI, dataIdx = dataI, sizeIdx = sizeI;
        String t = "%cang_lang_StringBuilder";

        // ---------- append(String s) ----------
        int id = labelCount++;
        header.append("define void @cang_lang_StringBuilder.append(").append(t).append("* %this, i8* %s) {\n");
        header.append("entry:\n");
        String dP = "%sb.d." + tmpCount++, sP = "%sb.s." + tmpCount++, cP = "%sb.c." + tmpCount++;
        header.append("  ").append(dP).append(" = getelementptr ").append(t).append(", ").append(t)
              .append("* %this, i32 0, i32 ").append(dataIdx).append("\n");
        header.append("  ").append(sP).append(" = getelementptr ").append(t).append(", ").append(t)
              .append("* %this, i32 0, i32 ").append(sizeIdx).append("\n");
        header.append("  ").append(cP).append(" = getelementptr ").append(t).append(", ").append(t)
              .append("* %this, i32 0, i32 ").append(capIdx).append("\n");
        String size = "%sb.sz." + tmpCount++, cap = "%sb.cp." + tmpCount++, data = "%sb.dv." + tmpCount++;
        header.append("  ").append(size).append(" = load i64, i64* ").append(sP).append("\n");
        header.append("  ").append(cap).append(" = load i64, i64* ").append(cP).append("\n");
        header.append("  ").append(data).append(" = load i8*, i8** ").append(dP).append("\n");
        String sNull = "%sb.sn." + tmpCount++;
        header.append("  ").append(sNull).append(" = icmp eq i8* %s, null\n");
        header.append("  br i1 ").append(sNull).append(", label %sb.app.done.").append(id)
              .append(", label %sb.app.nn.").append(id).append("\n");
        header.append("sb.app.nn.").append(id).append(":\n");
        String sl = "%sb.sl." + tmpCount++;
        header.append("  ").append(sl).append(" = call i64 @strlen(i8* %s)\n");
        String need = "%sb.need." + tmpCount++;
        header.append("  ").append(need).append(" = add i64 ").append(size).append(", ").append(sl).append("\n");
        String need1 = "%sb.need1." + tmpCount++;
        header.append("  ").append(need1).append(" = add i64 ").append(need).append(", 1\n");
        // grow when the buffer has no data yet (fresh/custom cap) or the request no longer fits
        String oob = "%sb.oob." + tmpCount++;
        header.append("  ").append(oob).append(" = icmp ugt i64 ").append(need1).append(", ").append(cap).append("\n");
        String dNull = "%sb.dn." + tmpCount++;
        header.append("  ").append(dNull).append(" = icmp eq i8* ").append(data).append(", null\n");
        String grow = "%sb.gw." + tmpCount++;
        header.append("  ").append(grow).append(" = or i1 ").append(oob).append(", ").append(dNull).append("\n");
        header.append("  br i1 ").append(grow).append(", label %sb.app.grow.").append(id)
              .append(", label %sb.app.ready.").append(id).append("\n");
        // ---- grow: ncap = max(need1, cap*2, 16); fresh buffer, copy old, free old ----
        header.append("sb.app.grow.").append(id).append(":\n");
        String cap2 = "%sb.cap2." + tmpCount++;
        header.append("  ").append(cap2).append(" = mul i64 ").append(cap).append(", 2\n");
        String t1 = "%sb.t1." + tmpCount++;
        header.append("  ").append(t1).append(" = icmp ugt i64 ").append(need1).append(", ").append(cap2).append("\n");
        String n1 = "%sb.n1." + tmpCount++;
        header.append("  ").append(n1).append(" = select i1 ").append(t1).append(", i64 ").append(need1)
              .append(", i64 ").append(cap2).append("\n");
        String t2 = "%sb.t2." + tmpCount++;
        header.append("  ").append(t2).append(" = icmp ult i64 ").append(n1).append(", 16\n");
        String ncap = "%sb.ncap." + tmpCount++;
        header.append("  ").append(ncap).append(" = select i1 ").append(t2).append(", i64 16, i64 ").append(n1).append("\n");
        String ndata = "%sb.nd." + tmpCount;
        header.append("  ").append(ndata).append(" = call i8* @").append(allocFn())
              .append("(i64 ").append(ncap).append(")\n");
        tmpCount++;
        header.append("  br i1 ").append(dNull).append(", label %sb.app.store.").append(id)
              .append(", label %sb.app.copy.").append(id).append("\n");
        header.append("sb.app.copy.").append(id).append(":\n");
        header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* ").append(ndata).append(", i8* ")
              .append(data).append(", i64 ").append(size).append(", i1 false)\n");
        header.append("  call void @").append(freeFn()).append("(i8* ").append(data).append(")\n");
        header.append("  br label %sb.app.store.").append(id).append("\n");
        header.append("sb.app.store.").append(id).append(":\n");
        header.append("  store i8* ").append(ndata).append(", i8** ").append(dP).append("\n");
        header.append("  store i64 ").append(ncap).append(", i64* ").append(cP).append("\n");
        header.append("  br label %sb.app.ready.").append(id).append("\n");
        // ---- ready: write the segment at [size), NUL-terminate, publish size ----
        header.append("sb.app.ready.").append(id).append(":\n");
        String data2 = "%sb.d2." + tmpCount++;
        header.append("  ").append(data2).append(" = load i8*, i8** ").append(dP).append("\n");
        String dst = "%sb.dst." + tmpCount++;
        header.append("  ").append(dst).append(" = getelementptr i8, i8* ").append(data2).append(", i64 ").append(size).append("\n");
        header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* ").append(dst).append(", i8* %s, i64 ")
              .append(sl).append(", i1 false)\n");
        String endp = "%sb.end." + tmpCount++;
        header.append("  ").append(endp).append(" = getelementptr i8, i8* ").append(data2).append(", i64 ")
              .append(need).append("\n");
        header.append("  store i8 0, i8* ").append(endp).append("\n");
        header.append("  store i64 ").append(need).append(", i64* ").append(sP).append("\n");
        header.append("  br label %sb.app.done.").append(id).append("\n");
        header.append("sb.app.done.").append(id).append(":\n");
        header.append("  ret void\n}\n\n");

        // ---------- length() ----------
        header.append("define i32 @cang_lang_StringBuilder.length(").append(t).append("* %this) {\n");
        header.append("entry:\n");
        String lP = "%sb.ls." + tmpCount++;
        header.append("  ").append(lP).append(" = getelementptr ").append(t).append(", ").append(t)
              .append("* %this, i32 0, i32 ").append(sizeIdx).append("\n");
        String lv = "%sb.lv." + tmpCount++;
        header.append("  ").append(lv).append(" = load i64, i64* ").append(lP).append("\n");
        String li = "%sb.li." + tmpCount++;
        header.append("  ").append(li).append(" = trunc i64 ").append(lv).append(" to i32\n");
        header.append("  ret i32 ").append(li).append("\n}\n\n");

        // ---------- toString() : snapshot copy ----------
        int tid = labelCount++;
        header.append("define i8* @cang_lang_StringBuilder.toString(").append(t).append("* %this) {\n");
        header.append("entry:\n");
        String dP2 = "%sb.d." + tmpCount++, sP2 = "%sb.s." + tmpCount++;
        header.append("  ").append(dP2).append(" = getelementptr ").append(t).append(", ").append(t)
              .append("* %this, i32 0, i32 ").append(dataIdx).append("\n");
        header.append("  ").append(sP2).append(" = getelementptr ").append(t).append(", ").append(t)
              .append("* %this, i32 0, i32 ").append(sizeIdx).append("\n");
        String sz = "%sb.tz." + tmpCount++, dsrc = "%sb.td." + tmpCount++;
        header.append("  ").append(sz).append(" = load i64, i64* ").append(sP2).append("\n");
        header.append("  ").append(dsrc).append(" = load i8*, i8** ").append(dP2).append("\n");
        String sz1 = "%sb.tz1." + tmpCount++;
        header.append("  ").append(sz1).append(" = add i64 ").append(sz).append(", 1\n");
        String out = "%sb.out." + tmpCount;
        header.append("  ").append(out).append(" = call i8* @").append(allocFn())
              .append("(i64 ").append(sz1).append(")\n");
        tmpCount++;
        String szZ = "%sb.tzz." + tmpCount++;
        header.append("  ").append(szZ).append(" = icmp eq i64 ").append(sz).append(", 0\n");
        header.append("  br i1 ").append(szZ).append(", label %sb.ts.zero.").append(tid)
              .append(", label %sb.ts.copy.").append(tid).append("\n");
        header.append("sb.ts.zero.").append(tid).append(":\n");
        header.append("  store i8 0, i8* ").append(out).append("\n");
        header.append("  br label %sb.ts.fin.").append(tid).append("\n");
        header.append("sb.ts.copy.").append(tid).append(":\n");
        header.append("  call void @llvm.memcpy.p0i8.p0i8.i64(i8* ").append(out).append(", i8* ")
              .append(dsrc).append(", i64 ").append(sz).append(", i1 false)\n");
        String oend = "%sb.toe." + tmpCount++;
        header.append("  ").append(oend).append(" = getelementptr i8, i8* ").append(out).append(", i64 ").append(sz).append("\n");
        header.append("  store i8 0, i8* ").append(oend).append("\n");
        header.append("  br label %sb.ts.fin.").append(tid).append("\n");
        header.append("sb.ts.fin.").append(tid).append(":\n");
        header.append("  ret i8* ").append(out).append("\n}\n\n");

        // ---------- clear() : size=0, keep the buffer ----------
        header.append("define void @cang_lang_StringBuilder.clear(").append(t).append("* %this) {\n");
        header.append("entry:\n");
        String cP2 = "%sb.cs." + tmpCount++;
        header.append("  ").append(cP2).append(" = getelementptr ").append(t).append(", ").append(t)
              .append("* %this, i32 0, i32 ").append(sizeIdx).append("\n");
        header.append("  store i64 0, i64* ").append(cP2).append("\n");
        header.append("  ret void\n}\n\n");
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
        return cangType != null && (cangType.equals("String") || isFunctionType(cangType) || isListType(cangType) || isThreadType(cangType))
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
            emitPostCallUnwindCheck();
            return new LLVMValue("void", "void");
        }
        String result = "%indirect.call." + tmpCount++;
        body.append("  ").append(result).append(" = call ").append(returnLLVM).append(" ")
             .append(codePtr).append("(").append(String.join(", ", callArgs)).append(")\n");
        emitPostCallUnwindCheck();
        return new LLVMValue(result, returnLLVM);
    }

    private String toLLVMType(String cangType) {
        // Function values are uniform { code, receiver } structs.
        if (isFunctionType(cangType)) {
            return "%CangFunction";
        }
        // Coroutine objects are the compiler's %CangCo intrinsic struct.
        if (cangType != null && cangType.equals("Coroutine")) {
            return "%CangCo*";
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

    /** LLVM byte offset of the field at list position `position` (typeId i32 occupies 0..3). */
    private int byteOffsetOfField(ClassInfo ci, int position) {
        long off = 4; // type-id i32
        for (int k = 0; k < position; k++) {
            String ft = ci.fieldTypes.get(k);
            long al = fieldLlvmAlign(ft);
            off = (off + al - 1) / al * al;
            off += fieldLlvmSize(ft);
        }
        String ft = ci.fieldTypes.get(position);
        long al = fieldLlvmAlign(ft);
        off = (off + al - 1) / al * al;
        return (int) off;
    }

    /** LLVM size of a field type (pointers 8, primitives natural, %CangFunction by value 16). */
    private long fieldLlvmSize(String t) {
        if (t.equals("i1") || t.equals("byte") || t.equals("i8") || t.equals("bool")) return 1;
        if (t.equals("int") || t.equals("i32") || t.equals("float")) return 4;
        if (t.equals("long") || t.equals("i64") || t.equals("double")) return 8;
        if (isFunctionType(t)) return 16; // %CangFunction value struct { i8*, i8* }
        if (t.startsWith("%") && !t.endsWith("*")) return 16;
        return 8; // pointers (i8*, %X*, Array/thread/Coroutine handles)
    }

    private long fieldLlvmAlign(String t) {
        return Math.min(fieldLlvmSize(t), 8);
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

