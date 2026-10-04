package parser;

import java.util.ArrayList;
import java.util.List;

public abstract class AST {
    public int line;
    public String sourceFile = ""; // source file this node came from

    public AST() {}

    public AST(int line) {
        this.line = line;
    }

    // ==================== Expressions ====================

    public static class IntLit extends AST {
        public String text;
        public IntLit(String text, int line) { super(line); this.text = text; }
    }

    public static class LongLit extends AST {
        public String text;
        public LongLit(String text, int line) { super(line); this.text = text; }
    }

    public static class FloatLit extends AST {
        public String text;
        public FloatLit(String text, int line) { super(line); this.text = text; }
    }

    public static class DoubleLit extends AST {
        public String text;
        public DoubleLit(String text, int line) { super(line); this.text = text; }
    }

    public static class BoolLit extends AST {
        public boolean value;
        public BoolLit(boolean value, int line) { super(line); this.value = value; }
    }

    public static class StringLit extends AST {
        public String value;
        public StringLit(String value, int line) { super(line); this.value = value; }
    }

    public static class StrLit extends AST {
        public String value;
        public StrLit(String value, int line) { super(line); this.value = value; }
    }

    public static class NullLit extends AST {
        public NullLit(int line) { super(line); }
    }

    public static class Identifier extends AST {
        public String name;
        public Identifier(String name, int line) { super(line); this.name = name; }
    }

    public static class LambdaExpr extends AST {
        public String parameter;
        public AST body;
        public LambdaExpr(String parameter, AST body, int line) {
            super(line); this.parameter = parameter; this.body = body;
        }
    }

    public static class FunctionRefExpr extends AST {
        public AST receiver;
        public String method;
        public FunctionRefExpr(AST receiver, String method, int line) {
            super(line); this.receiver = receiver; this.method = method;
        }
    }

    public static class ThisExpr extends AST {
        public ThisExpr(int line) { super(line); }
    }

    public static class BinaryExpr extends AST {
        public AST left;
        public String op;
        public AST right;
        public BinaryExpr(AST left, String op, AST right, int line) {
            super(line); this.left = left; this.op = op; this.right = right;
        }
    }

    public static class LikeExpr extends AST {
        public AST expr;
        public String className;
        public LikeExpr(AST expr, String className, int line) {
            super(line); this.expr = expr; this.className = className;
        }
    }

    public static class UnaryExpr extends AST {
        public String op;
        public AST operand;
        public boolean prefix;
        public UnaryExpr(String op, AST operand, boolean prefix, int line) {
            super(line); this.op = op; this.operand = operand; this.prefix = prefix;
        }
    }

    public static class AssignExpr extends AST {
        public AST target;
        public AST value;
        public AssignExpr(AST target, AST value, int line) {
            super(line); this.target = target; this.value = value;
        }
    }

    public static class TernaryExpr extends AST {
        public AST condition;
        public AST trueExpr;
        public AST falseExpr;
        public TernaryExpr(AST condition, AST trueExpr, AST falseExpr, int line) {
            super(line); this.condition = condition; this.trueExpr = trueExpr; this.falseExpr = falseExpr;
        }
    }

    public static class TypeCastExpr extends AST {
        public String targetType;
        public AST expr;
        public TypeCastExpr(String targetType, AST expr, int line) {
            super(line); this.targetType = targetType; this.expr = expr;
        }
    }

    public static class VoidPlaceholder extends AST {
        public VoidPlaceholder(int line) { super(line); }
    }

    public static class ArrayLit extends AST {
        public List<AST> elements;
        public ArrayLit(List<AST> elements, int line) {
            super(line); this.elements = elements;
        }
    }

    public static class ArrayAccessExpr extends AST {
        public AST array;
        public AST index;
        public ArrayAccessExpr(AST array, AST index, int line) {
            super(line); this.array = array; this.index = index;
        }
    }

    public static class MethodCallExpr extends AST {
        public AST object;
        public String method;
        public List<AST> args;
        public MethodCallExpr(AST object, String method, List<AST> args, int line) {
            super(line); this.object = object; this.method = method; this.args = args;
        }
    }

    public static class FieldAccessExpr extends AST {
        public AST object;
        public String field;
        public FieldAccessExpr(AST object, String field, int line) {
            super(line); this.object = object; this.field = field;
        }
    }

    public static class NewExpr extends AST {
        public String className;
        public List<String> typeArgs;
        public List<AST> args;
        public NewExpr(String className, List<AST> args, int line) {
            this(className, new ArrayList<>(), args, line);
        }
        public NewExpr(String className, List<String> typeArgs, List<AST> args, int line) {
            super(line); this.className = className; this.typeArgs = typeArgs; this.args = args;
        }
    }

    /** Runtime-sized array creation: new T[sizeExpr] (debug.md #37). Field is named
     *  `type` so generic monomorphization (rewriteAstTypes) substitutes T inside it. */
    public static class NewArrayExpr extends AST {
        public String type;   // element Cang type (int, String, T, ...)
        public AST size;      // runtime size expression
        public NewArrayExpr(String type, AST size, int line) {
            super(line); this.type = type; this.size = size;
        }
    }

    // ==================== Statements ====================

    public static class Block extends AST {
        public List<AST> statements;
        public Block(List<AST> statements, int line) {
            super(line); this.statements = statements;
        }
    }

    public static class VarDecl extends AST {
        public String type;
        public String name;
        public AST init;
        public boolean isFinal;
        public int arraySize = -1; // fixed array size from T[N] syntax; -1 = not fixed
        public VarDecl(String type, String name, AST init, int line) {
            super(line); this.type = type; this.name = name; this.init = init; this.isFinal = false;
        }
    }

    public static class FreeStmt extends AST {
        public List<String> names;
        public FreeStmt(List<String> names, int line) { super(line); this.names = names; }
    }

    public static class ThreadBlockStmt extends AST {
        public AST body;
        public ThreadBlockStmt(AST body, int line) { super(line); this.body = body; }
    }

    public static class IfStmt extends AST {
        public AST condition;
        public AST thenBlock;
        public AST elseBlock;
        public IfStmt(AST condition, AST thenBlock, AST elseBlock, int line) {
            super(line); this.condition = condition; this.thenBlock = thenBlock; this.elseBlock = elseBlock;
        }
    }

    public static class WhileStmt extends AST {
        public AST condition;
        public AST body;
        public WhileStmt(AST condition, AST body, int line) {
            super(line); this.condition = condition; this.body = body;
        }
    }

    public static class ForStmt extends AST {
        public AST init;
        public AST condition;
        public AST update;
        public AST body;
        public ForStmt(AST init, AST condition, AST update, AST body, int line) {
            super(line); this.init = init; this.condition = condition; this.update = update; this.body = body;
        }
    }

    public static class ForEachStmt extends AST {
        public String varType;
        public String varName;
        public AST iterable;
        public AST body;
        public ForEachStmt(String varType, String varName, AST iterable, AST body, int line) {
            super(line); this.varType = varType; this.varName = varName;
            this.iterable = iterable; this.body = body;
        }
    }

    public static class SwitchStmt extends AST {
        public AST subject;
        public List<SwitchCase> cases;
        public List<AST> defaultBody;
        public SwitchStmt(AST subject, List<SwitchCase> cases, List<AST> defaultBody, int line) {
            super(line); this.subject = subject; this.cases = cases; this.defaultBody = defaultBody;
        }
    }

    public static class SwitchCase extends AST {
        public AST value;
        public List<AST> body;
        public SwitchCase(AST value, List<AST> body, int line) {
            super(line); this.value = value; this.body = body;
        }
    }

    public static class ReturnStmt extends AST {
        public AST value;
        public ReturnStmt(AST value, int line) { super(line); this.value = value; }
    }

    public static class ThrowStmt extends AST {
        public AST value;
        public ThrowStmt(AST value, int line) { super(line); this.value = value; }
    }

    public static class CatchClause extends AST {
        public String type;
        public String name;
        public AST body;
        public CatchClause(String type, String name, AST body, int line) {
            super(line); this.type = type; this.name = name; this.body = body;
        }
    }

    public static class TryStmt extends AST {
        public AST tryBlock;
        public List<CatchClause> catches;
        public AST finallyBlock;
        public TryStmt(AST tryBlock, List<CatchClause> catches, AST finallyBlock, int line) {
            super(line); this.tryBlock = tryBlock; this.catches = catches;
            this.finallyBlock = finallyBlock;
        }
    }

    public static class ExprStmt extends AST {
        public AST expr;
        public ExprStmt(AST expr, int line) { super(line); this.expr = expr; }
    }

    public static class BreakStmt extends AST {
        public BreakStmt(int line) { super(line); }
    }

    public static class ContinueStmt extends AST {
        public ContinueStmt(int line) { super(line); }
    }

    // ==================== Declarations ====================

    public static class NamespaceDecl extends AST {
        public String path; // e.g. "cc/ruok/cang"
        public NamespaceDecl(String path, int line) {
            super(line); this.path = path;
        }
    }

    public static class ImportDecl extends AST {
        public String path; // e.g. "cc/ruok/cang/Foo" or "cc/ruok/cang"
        public String alias; // "import path as Alias" — optional; null when absent
        public ImportDecl(String path, int line) {
            super(line); this.path = path; this.alias = null;
        }
    }

    public static class Program extends AST {
        public List<AST> members;
        public Program(List<AST> members) { this.members = members; }

        /** Recursively set sourceFile on all nodes. */
        public void setSourceFile(String file) {
            this.sourceFile = file;
            for (AST m : members) setSourceFileRecursive(m, file);
        }

        private static void setSourceFileRecursive(AST node, String file) {
            if (node == null) return;
            node.sourceFile = file;
            if (node instanceof ClassDecl) {
                ClassDecl cd = (ClassDecl) node;
                for (AST m : cd.members) setSourceFileRecursive(m, file);
                for (AST m : cd.topLevelBody) setSourceFileRecursive(m, file);
            } else if (node instanceof Block) {
                for (AST m : ((Block) node).statements) setSourceFileRecursive(m, file);
            } else if (node instanceof FuncDecl) {
                FuncDecl fd = (FuncDecl) node;
                if (fd.body instanceof Block) {
                    for (AST m : ((Block) fd.body).statements) setSourceFileRecursive(m, file);
                }
            } else if (node instanceof LambdaExpr) {
                LambdaExpr lambda = (LambdaExpr) node;
                setSourceFileRecursive(lambda.body, file);
            } else if (node instanceof ThreadBlockStmt) {
                setSourceFileRecursive(((ThreadBlockStmt) node).body, file);
            } else if (node instanceof ConstructorDecl) {
                ConstructorDecl cd = (ConstructorDecl) node;
                if (cd.body instanceof Block) {
                    for (AST m : ((Block) cd.body).statements) setSourceFileRecursive(m, file);
                }
            }
        }
    }

    public static class ClassDecl extends AST {
        public String name;
        public String superClass;
        public List<String> genericParams;
        public List<AST> members;
        public List<Parameter> ctorParams; // constructor params (implicit ctor)
        public List<AST> topLevelBody; // for entry-point class (no braces): rest of file
        public boolean isEntryPoint; // true when class has no {} and rest of file is body
        public boolean isAbstract; // abstract class: not instantiable, may declare abstract methods
        public List<AST> superArgs; // parent constructor arguments
        public String namespace = ""; // namespace from its source file (e.g. "cang/lang")
        public ClassDecl(String name, String superClass, List<AST> members, int line) {
            super(line); this.name = name; this.superClass = superClass; this.genericParams = new ArrayList<>(); this.members = members;
            this.ctorParams = new ArrayList<>();
            this.topLevelBody = new ArrayList<>();
            this.isEntryPoint = false;
            this.superArgs = new ArrayList<>();
        }
        public ClassDecl(String name, String superClass, List<AST> members, List<Parameter> ctorParams, int line) {
            super(line); this.name = name; this.superClass = superClass; this.genericParams = new ArrayList<>(); this.members = members;
            this.ctorParams = ctorParams;
            this.topLevelBody = new ArrayList<>();
            this.isEntryPoint = false;
            this.superArgs = new ArrayList<>();
        }
    }

    public static class FuncDecl extends AST {
        public String returnType;
        public String name;
        public List<Parameter> params;
        public AST body;
        public boolean isStatic;
        public boolean isFinal;
        public boolean isNative;
        public boolean isAbstract;
        public FuncDecl(String returnType, String name, List<Parameter> params, AST body, boolean isStatic, int line) {
            super(line); this.returnType = returnType; this.name = name; this.params = params;
            this.body = body; this.isStatic = isStatic; this.isFinal = false; this.isNative = false;
            this.isAbstract = false;
        }
    }

    public static class FieldDecl extends AST {
        public String type;
        public String name;
        public AST init;
        public boolean isStatic;
        public boolean isNative;
        public boolean isFinal;
        public FieldDecl(String type, String name, AST init, boolean isStatic, int line) {
            super(line); this.type = type; this.name = name; this.init = init; this.isStatic = isStatic;
            this.isNative = false; this.isFinal = false;
        }
    }

    public static class ConstructorDecl extends AST {
        public String className;
        public List<Parameter> params;
        public AST body;
        public ConstructorDecl(String className, List<Parameter> params, AST body, int line) {
            super(line); this.className = className; this.params = params; this.body = body;
        }
    }

    public static class Parameter extends AST {
        public String type;
        public String name;
        public AST defaultValue; // null if no default
        public Parameter(String type, String name, int line) {
            super(line); this.type = type; this.name = name;
        }
    }
}
