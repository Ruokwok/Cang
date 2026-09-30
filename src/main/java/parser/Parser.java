package parser;

import lexer.Token;
import lexer.TokenType;
import parser.AST.*;

import java.util.ArrayList;
import java.util.List;

public class Parser {

    private final List<Token> tokens;
    private int pos;
    private int lastArraySize = -1; // set by parseType: >=0 for T[N], -1 otherwise

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    public Program parse() {
        List<AST> members = new ArrayList<>();

        // namespace must be first (after comments, which are skipped by lexer)
        if (check(TokenType.NAMESPACE)) {
            members.add(parseNamespace());
        }

        // imports come after namespace
        while (check(TokenType.IMPORT)) {
            members.add(parseImport());
        }

        // Parse classes and top-level code
        ClassDecl currentClass = null;
        while (!check(TokenType.EOF)) {
            if (check(TokenType.CLASS)) {
                // Save previous class if exists
                if (currentClass != null) {
                    members.add(currentClass);
                }
                // New class - not entry point initially
                currentClass = parseClassDecl();
                currentClass.isEntryPoint = true;
            } else if (currentClass != null && check(TokenType.FUNC)) {
                // Function after class → attach as method
                currentClass.topLevelBody.add(parseTopLevel());
            } else if (currentClass != null && check(TokenType.STATIC)) {
                // static function after class → attach as method
                currentClass.topLevelBody.add(parseTopLevel());
            } else {
                // Top-level statement (if, var, expression, etc.)
                if (currentClass != null) {
                    currentClass.topLevelBody.add(parseTopLevel());
                } else {
                    members.add(parseTopLevel());
                }
            }
        }
        // Add last class
        if (currentClass != null) {
            members.add(currentClass);
        }

        return new Program(members);
    }

    private AST parseNamespace() {
        int line = current().line;
        expect(TokenType.NAMESPACE);
        // Parse path like cc/ruok/cang
        StringBuilder path = new StringBuilder();
        path.append(parsePathSegment());
        while (check(TokenType.SLASH)) {
            advance();
            path.append("/").append(parsePathSegment());
        }
        optionalSemicolon();
        return new NamespaceDecl(path.toString(), line);
    }

    private AST parseImport() {
        int line = current().line;
        expect(TokenType.IMPORT);
        // Parse path like cc/ruok/cang or cc/ruok/cang/Foo
        // Allow type keywords as path segments (e.g., String, int)
        StringBuilder path = new StringBuilder();
        path.append(parsePathSegment());
        while (check(TokenType.SLASH)) {
            advance();
            path.append("/").append(parsePathSegment());
        }
        optionalSemicolon();
        return new ImportDecl(path.toString(), line);
    }

    private String parsePathSegment() {
        if (check(TokenType.IDENT)) return advance().value;
        // Allow type keywords in import paths
        if (TokenType.isTypeKeyword(current().type) || check(TokenType.VOID)) {
            return advance().value;
        }
        error("Expected identifier in import path");
        return null;
    }

    // ==================== Top Level ====================

    private AST parseTopLevel() {
        if (check(TokenType.CLASS)) return parseClassDecl();

        // native modifier for top-level functions
        if (check(TokenType.NATIVE)) {
            int line = advance().line;
            if (check(TokenType.FUNC)) {
                advance();
                String type = parseType();
                String name = expect(TokenType.IDENT).value;
                List<Parameter> params = parseParams();
                optionalSemicolon();
                FuncDecl fd = new FuncDecl(type, name, params, null, false, line);
                fd.isNative = true;
                return fd;
            }
            error("Expected 'func' after 'native'");
        }

        // final modifier for top-level
        if (check(TokenType.FINAL)) {
            int line = advance().line;
            if (check(TokenType.FUNC)) {
                advance();
                String type = parseType();
                String name = expect(TokenType.IDENT).value;
                List<Parameter> params = parseParams();
                AST body = parseBlock();
                FuncDecl fd = new FuncDecl(type, name, params, body, false, line);
                fd.isFinal = true;
                return fd;
            }
            // final var/type declaration
            AST stmt = parseStatement();
            if (stmt instanceof VarDecl) ((VarDecl) stmt).isFinal = true;
            return stmt;
        }

        // static: func or field declaration
        if (check(TokenType.STATIC)) {
            int line = advance().line;
            boolean isNative = false;
            boolean isFinal = false;
            // Parse modifiers: static [final] [native] or static [native] [final]
            while (check(TokenType.NATIVE) || check(TokenType.FINAL)) {
                if (check(TokenType.NATIVE)) { advance(); isNative = true; }
                else if (check(TokenType.FINAL)) { advance(); isFinal = true; }
            }
            if (check(TokenType.FUNC)) {
                advance();
                String type = parseType();
                String name = expect(TokenType.IDENT).value;
                List<Parameter> params = parseParams();
                AST body = isNative ? null : parseBlock();
                if (isNative) optionalSemicolon();
                FuncDecl fd = new FuncDecl(type, name, params, body, true, line);
                fd.isNative = isNative;
                fd.isFinal = isFinal;
                return fd;
            }
            // Static field: static [final] [native] Type name = value;
            String type = parseType();
            String name = expect(TokenType.IDENT).value;
            if (check(TokenType.LPAREN)) {
                error("Static methods must use 'func': static func " + type + " " + name + "(...)");
            }
            AST init = null;
            if (check(TokenType.ASSIGN)) {
                advance();
                init = parseExpression();
            }
            optionalSemicolon();
            FieldDecl fd = new FieldDecl(type, name, init, true, line);
            fd.isNative = isNative;
            fd.isFinal = isFinal;
            return fd;
        }

        // func returnType name(params) { body }
        if (check(TokenType.FUNC)) {
            int line = advance().line;
            String type = parseType();
            String name = expect(TokenType.IDENT).value;
            List<Parameter> params = parseParams();
            AST body = parseBlock();
            return new FuncDecl(type, name, params, body, false, line);
        }

        // Function or variable declaration: type name (...)
        if (isTypeStart()) {
            // Look ahead: if it's a function (name followed by '('), parse as func/field
            // Otherwise parse as a statement (var decl at top level)
            int saved = pos;
            String type = parseType();
            if (check(TokenType.IDENT) && peek(1).type == TokenType.LPAREN) {
                // Function declaration
                String name = advance().value;
                List<Parameter> params = parseParams();
                AST body = parseBlock();
                return new FuncDecl(type, name, params, body, false, current().line);
            }
            // Not a function — rewind and parse as statement
            pos = saved;
        }
        // Top-level statement (var decl, expression, if, while, etc.)
        return parseStatement();
    }

    private ClassDecl parseClassDecl() {
        int line = current().line;
        expect(TokenType.CLASS);
        // Allow type keywords as class names (e.g., class String)
        String name;
        if (check(TokenType.IDENT)) {
            name = advance().value;
        } else if (TokenType.isTypeKeyword(current().type) || check(TokenType.VOID)) {
            name = advance().value;
        } else {
            error("Expected class name");
            name = "unknown";
        }
        String superClass = null;
        List<AST> superArgs = new ArrayList<>();

        // Constructor params: class Name(params)
        List<Parameter> ctorParams = new ArrayList<>();
        if (check(TokenType.LPAREN)) {
            ctorParams = parseParams();
        }

        // Inheritance: : Parent(args)
        if (check(TokenType.COLON)) {
            advance();
            superClass = expect(TokenType.IDENT).value;
            // Parent constructor args: Parent(args)
            if (check(TokenType.LPAREN)) {
                advance();
                if (!check(TokenType.RPAREN)) {
                    superArgs.add(parseExpression());
                    while (check(TokenType.COMMA)) {
                        advance();
                        superArgs.add(parseExpression());
                    }
                }
                expect(TokenType.RPAREN);
            }
        }

        // No braces - class is just declaration, rest of file is top-level code
        optionalSemicolon();

        ClassDecl cd = new ClassDecl(name, superClass, new ArrayList<>(), ctorParams, line);
        cd.isEntryPoint = true;
        cd.superArgs = superArgs;
        return cd;
    }

    private AST parseClassMember() {
        int line = current().line;

        // modifiers: static, final, native
        boolean isStatic = false;
        boolean isFinal = false;
        boolean isNative = false;
        while (check(TokenType.STATIC) || check(TokenType.FINAL) || check(TokenType.NATIVE)) {
            if (check(TokenType.STATIC)) { advance(); isStatic = true; }
            else if (check(TokenType.FINAL)) { advance(); isFinal = true; }
            else if (check(TokenType.NATIVE)) { advance(); isNative = true; }
        }

        // func returnType name(params) { body }
        if (check(TokenType.FUNC)) {
            advance();
            String type = parseType();
            String name = expect(TokenType.IDENT).value;
            List<Parameter> params = parseParams();
            AST body;
            if (isNative) {
                // native methods have no body
                body = null;
                optionalSemicolon();
            } else {
                body = parseBlock();
            }
            FuncDecl fd = new FuncDecl(type, name, params, body, isStatic, line);
            fd.isFinal = isFinal;
            fd.isNative = isNative;
            return fd;
        }

        // constructor: ClassName(params) { body }
        if (!isStatic && check(TokenType.IDENT) && peek(1).type == TokenType.LPAREN) {
            String ctorName = advance().value;
            List<Parameter> params = parseParams();
            AST body = parseBlock();
            return new ConstructorDecl(ctorName, params, body, line);
        }

        // Field or method (with optional static modifier)
        if (isTypeStart()) {
            String type = parseType();
            String name = expect(TokenType.IDENT).value;

            // Method: type name ( ... ) { ... } (only for non-static)
            if (check(TokenType.LPAREN) && !isStatic) {
                List<Parameter> params = parseParams();
                AST body = parseBlock();
                return new FuncDecl(type, name, params, body, isStatic, line);
            }

            if (isStatic && check(TokenType.LPAREN)) {
                error("Static methods must use 'func' keyword: static func " + type + " " + name + "(...)");
            }

            // Field: type name [= expr] ;
            AST init = null;
            if (check(TokenType.ASSIGN)) {
                advance();
                init = parseExpression();
            }
            optionalSemicolon();
            return new FieldDecl(type, name, init, isStatic, line);
        }

        error("Expected class member declaration");
        return null;
    }

    // ==================== Types ====================

    private boolean isTypeStart() {
        if (check(TokenType.T_BYTE, TokenType.T_INT, TokenType.T_LONG,
                  TokenType.T_FLOAT, TokenType.T_DOUBLE, TokenType.T_BOOL,
                  TokenType.T_STRING, TokenType.T_STR, TokenType.VOID, TokenType.VAR, TokenType.FUNC)) {
            return true;
        }
        // Reject the removed legacy Array<T> spelling with a focused diagnostic.
        if (check(TokenType.IDENT) && current().value.equals("Array") && peek(1).type == TokenType.LT) {
            error("Array<T> syntax has been removed; use T[] or T[N]");
        }
        // Function<R, ...> callable type
        if (check(TokenType.IDENT) && current().value.equals("Function") && peek(1).type == TokenType.LT) {
            return true;
        }
        // ClassName varName → declaration (lookahead for IDENT IDENT)
        if (check(TokenType.IDENT) && peek(1).type == TokenType.IDENT) {
            return true;
        }
        // Array syntax declaration: IDENT [ ... ] IDENT  (e.g. Point[] pts, Point[10] pts, int[][] m)
        if (check(TokenType.IDENT) && peek(1).type == TokenType.LBRACKET) {
            int i = 1;
            // skip balanced bracket groups: [], [N], [expr]
            while (peek(i).type == TokenType.LBRACKET && i < tokens.size()) {
                int depth = 0;
                do {
                    if (peek(i).type == TokenType.LBRACKET) depth++;
                    else if (peek(i).type == TokenType.RBRACKET) depth--;
                    i++;
                } while (depth > 0 && peek(i).type != TokenType.EOF);
            }
            if (peek(i).type == TokenType.IDENT) {
                return true;
            }
        }
        return false;
    }

    private String parseType() {
        String base = parseBaseType();
        lastArraySize = -1;
        if (!check(TokenType.LBRACKET)) {
            return base;
        }
        // Array suffix: T[] (length inferred) or T[N] (fixed size); nesting: T[][], T[2][]
        if (base.equals("var")) {
            error("Cannot use 'var' with array syntax; use 'var arr = [...]' for type inference");
        }
        if (base.equals("void")) {
            error("'void' cannot be an array element type");
        }
        int dim = 0;
        while (check(TokenType.LBRACKET)) {
            advance(); // consume '['
            if (check(TokenType.RBRACKET)) {
                advance(); // T[] — length inferred
            } else {
                if (dim > 0) {
                    error("Only the outermost dimension can have a fixed length; use T[2][] for nested arrays");
                }
                if (!check(TokenType.INT_LIT)) {
                    error("Array size must be a positive integer literal, e.g. int[10]");
                }
                String sizeText = advance().value.replace("_", "");
                int size;
                if (sizeText.matches("\\d+")) {
                    size = Integer.parseInt(sizeText);
                } else {
                    error("Array size must be a positive integer literal, e.g. int[10]");
                    size = -1; // unreachable
                }
                expect(TokenType.RBRACKET);
                if (size <= 0) {
                    error("Array size must be greater than 0");
                }
                lastArraySize = size;
            }
            base = "Array<" + base + ">";
            dim++;
        }
        return base;
    }

    private String parseBaseType() {
        if (check(TokenType.T_BYTE)) { advance(); return "byte"; }
        if (check(TokenType.T_INT)) { advance(); return "int"; }
        if (check(TokenType.T_LONG)) { advance(); return "long"; }
        if (check(TokenType.T_FLOAT)) { advance(); return "float"; }
        if (check(TokenType.T_DOUBLE)) { advance(); return "double"; }
        if (check(TokenType.T_BOOL)) { advance(); return "bool"; }
        if (check(TokenType.T_STRING)) { advance(); return "String"; }
        if (check(TokenType.T_STR)) { advance(); return "str"; }
        if (check(TokenType.VOID)) { advance(); return "void"; }
        if (check(TokenType.VAR)) { advance(); return "var"; }
        if (check(TokenType.IDENT) && current().value.equals("Array") && peek(1).type == TokenType.LT) {
            error("Array<T> syntax has been removed; use T[] or T[N]");
        }
        if (check(TokenType.IDENT) && current().value.equals("Function") && peek(1).type == TokenType.LT) {
            advance();
            advance(); // <
            List<String> parts = new ArrayList<>();
            parts.add(parseType());
            while (check(TokenType.COMMA)) {
                advance();
                parts.add(parseType());
                // Optional generic parameter name: Function<Void, int i>
                if (check(TokenType.IDENT)) advance();
            }
            expect(TokenType.GT);
            return "Function<" + String.join(",", parts) + ">";
        }
        if (check(TokenType.IDENT)) { return advance().value; }
        error("Expected type name");
        return null;
    }

    // ==================== Parameters ====================

    private List<Parameter> parseParams() {
        expect(TokenType.LPAREN);
        List<Parameter> params = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            params.add(parseParameter());
            while (check(TokenType.COMMA)) {
                advance();
                params.add(parseParameter());
            }
        }
        expect(TokenType.RPAREN);
        return params;
    }

    private Parameter parseParameter() {
        int line = current().line;
        String type = parseType();
        String name = expect(TokenType.IDENT).value;
        AST defaultValue = null;
        if (check(TokenType.ASSIGN)) {
            advance();
            defaultValue = parseExpression();
        }
        Parameter p = new Parameter(type, name, line);
        p.defaultValue = defaultValue;
        return p;
    }

    // ==================== Statements ====================

    private AST parseBlock() {
        int line = current().line;
        expect(TokenType.LBRACE);
        List<AST> stmts = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            stmts.add(parseStatement());
        }
        expect(TokenType.RBRACE);
        return new Block(stmts, line);
    }

    private AST parseStatement() {
        if (check(TokenType.IF)) return parseIfStmt();
        if (check(TokenType.TRY)) return parseTryStmt();
        if (check(TokenType.THROW)) return parseThrowStmt();
        if (check(TokenType.SWITCH)) return parseSwitchStmt();
        if (check(TokenType.WHILE)) return parseWhileStmt();
        if (check(TokenType.FOR)) return parseForStmt();
        if (check(TokenType.RETURN)) return parseReturnStmt();
        if (check(TokenType.BREAK)) { int line = advance().line; optionalSemicolon(); return new BreakStmt(line); }
        if (check(TokenType.CONTINUE)) { int line = advance().line; optionalSemicolon(); return new ContinueStmt(line); }
        if (check(TokenType.FREE)) return parseFreeStmt();
        if (check(TokenType.LBRACE)) return parseBlock();
        // final var/type declaration
        if (check(TokenType.FINAL)) {
            advance();
            if (isTypeStart()) {
                AST stmt = parseVarDecl();
                if (stmt instanceof VarDecl) ((VarDecl) stmt).isFinal = true;
                return stmt;
            }
            error("Expected type after 'final'");
        }
        if (isTypeStart()) return parseVarDecl();
        return parseExprStmt();
    }

    private ThrowStmt parseThrowStmt() {
        int line = advance().line;
        AST value = parseExpression();
        optionalSemicolon();
        return new ThrowStmt(value, line);
    }

    private TryStmt parseTryStmt() {
        int line = advance().line;
        AST tryBlock = parseBlock();
        List<CatchClause> catches = new ArrayList<>();
        while (check(TokenType.CATCH)) {
            int catchLine = advance().line;
            expect(TokenType.LPAREN);
            String type = parseType();
            String name = expect(TokenType.IDENT).value;
            expect(TokenType.RPAREN);
            if (TokenType.isTypeKeyword(current().type) || type.startsWith("Array<") || type.startsWith("Function<")) {
                error("catch type must be Error or an Error subclass");
            }
            catches.add(new CatchClause(type, name, parseBlock(), catchLine));
        }
        AST finallyBlock = null;
        if (check(TokenType.FINALLY)) {
            advance();
            finallyBlock = parseBlock();
        }
        if (catches.isEmpty() && finallyBlock == null) error("try requires catch or finally");
        return new TryStmt(tryBlock, catches, finallyBlock, line);
    }

    private FreeStmt parseFreeStmt() {
        int line = advance().line;
        List<String> names = new ArrayList<>();
        names.add(expect(TokenType.IDENT).value);
        while (check(TokenType.COMMA)) {
            advance();
            names.add(expect(TokenType.IDENT).value);
        }
        optionalSemicolon();
        return new FreeStmt(names, line);
    }

    private AST parseVarDecl() {
        int line = current().line;
        String type = parseType();
        int arraySize = lastArraySize;
        String name = expect(TokenType.IDENT).value;
        AST init = null;
        if (check(TokenType.ASSIGN)) {
            advance();
            init = parseExpression();
        }
        optionalSemicolon();
        VarDecl decl = new VarDecl(type, name, init, line);
        decl.arraySize = arraySize;
        return decl;
    }

    private IfStmt parseIfStmt() {
        int line = current().line;
        expect(TokenType.IF);
        expect(TokenType.LPAREN);
        AST condition = parseExpression();
        expect(TokenType.RPAREN);
        AST thenBlock = parseStatement();
        AST elseBlock = null;
        if (check(TokenType.ELSE)) {
            advance();
            elseBlock = parseStatement();
        }
        return new IfStmt(condition, thenBlock, elseBlock, line);
    }

    private AST parseSwitchStmt() {
        int line = current().line;
        expect(TokenType.SWITCH);
        expect(TokenType.LPAREN);
        AST subject = parseExpression();
        expect(TokenType.RPAREN);
        expect(TokenType.LBRACE);

        List<SwitchCase> cases = new ArrayList<>();
        List<AST> defaultBody = null;

        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            if (check(TokenType.CASE)) {
                int caseLine = advance().line;
                AST value = parseExpression();
                expect(TokenType.COLON);
                List<AST> body = new ArrayList<>();
                // Parse statements until next case/default or }
                while (!check(TokenType.CASE) && !check(TokenType.DEFAULT) &&
                       !check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                    body.add(parseStatement());
                }
                cases.add(new SwitchCase(value, body, caseLine));
            } else if (check(TokenType.DEFAULT)) {
                int defLine = advance().line;
                expect(TokenType.COLON);
                defaultBody = new ArrayList<>();
                while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                    defaultBody.add(parseStatement());
                }
            } else {
                error("Expected case or default in switch");
            }
        }
        expect(TokenType.RBRACE);
        return new SwitchStmt(subject, cases, defaultBody, line);
    }

    private WhileStmt parseWhileStmt() {
        int line = current().line;
        expect(TokenType.WHILE);
        expect(TokenType.LPAREN);
        AST condition = parseExpression();
        expect(TokenType.RPAREN);
        AST body = parseStatement();
        return new WhileStmt(condition, body, line);
    }

    private AST parseForStmt() {
        int line = current().line;
        expect(TokenType.FOR);
        expect(TokenType.LPAREN);

        // Detect for-each: type/var IDENT : expr
        if (isTypeStart()) {
            int saved = pos;
            String type = parseType();
            if (check(TokenType.IDENT) && peek(1).type == TokenType.COLON) {
                // for-each: for(var i : arr)
                String varName = expect(TokenType.IDENT).value;
                expect(TokenType.COLON);
                AST iterable = parseExpression();
                expect(TokenType.RPAREN);
                AST body = parseStatement();
                return new ForEachStmt(type, varName, iterable, body, line);
            }
            // Not for-each, rewind
            pos = saved;
        }

        // init
        AST init = null;
        if (check(TokenType.SEMICOLON)) {
            advance(); // empty init
        } else if (isTypeStart()) {
            // var decl without semicolon
            String type = parseType();
            int arraySize = lastArraySize;
            String name = expect(TokenType.IDENT).value;
            AST varInit = null;
            if (check(TokenType.ASSIGN)) {
                advance();
                varInit = parseExpression();
            }
            expect(TokenType.SEMICOLON);
            VarDecl vd = new VarDecl(type, name, varInit, line);
            vd.arraySize = arraySize;
            init = vd;
        } else {
            init = parseExpression();
            expect(TokenType.SEMICOLON);
        }

        // condition
        AST condition = null;
        if (!check(TokenType.SEMICOLON)) {
            condition = parseExpression();
        }
        expect(TokenType.SEMICOLON);

        // update
        AST update = null;
        if (!check(TokenType.RPAREN)) {
            update = parseExpression();
        }
        expect(TokenType.RPAREN);

        AST body = parseStatement();
        return new ForStmt(init, condition, update, body, line);
    }

    private ReturnStmt parseReturnStmt() {
        int line = current().line;
        expect(TokenType.RETURN);
        AST value = null;
        if (!check(TokenType.SEMICOLON) && !check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            value = parseExpression();
        }
        optionalSemicolon();
        return new ReturnStmt(value, line);
    }

    private ExprStmt parseExprStmt() {
        int line = current().line;
        AST expr = parseExpression();
        optionalSemicolon();
        return new ExprStmt(expr, line);
    }

    // ==================== Expressions ====================

    private AST parseExpression() {
        return parseAssignment();
    }

    private AST parseAssignment() {
        AST left = parseTernary();
        if (check(TokenType.ASSIGN)) {
            int line = current().line;
            advance();
            AST right = parseAssignment();
            return new AssignExpr(left, right, line);
        }
        return left;
    }

    private AST parseTernary() {
        AST condition = parseLogicOr();
        if (check(TokenType.QUESTION)) {
            int line = current().line;
            advance();
            AST trueExpr = parseExpression();
            expect(TokenType.COLON);
            AST falseExpr = parseAssignment();
            return new TernaryExpr(condition, trueExpr, falseExpr, line);
        }
        return condition;
    }

    private AST parseLogicOr() {
        AST left = parseLogicAnd();
        while (check(TokenType.OR)) {
            int line = current().line;
            String op = advance().value;
            AST right = parseLogicAnd();
            left = new BinaryExpr(left, op, right, line);
        }
        return left;
    }

    private AST parseLogicAnd() {
        AST left = parseEquality();
        while (check(TokenType.AND)) {
            int line = current().line;
            String op = advance().value;
            AST right = parseEquality();
            left = new BinaryExpr(left, op, right, line);
        }
        return left;
    }

    private AST parseEquality() {
        AST left = parseComparison();
        while (check(TokenType.EQ, TokenType.NEQ)) {
            int line = current().line;
            String op = advance().value;
            AST right = parseComparison();
            left = new BinaryExpr(left, op, right, line);
        }
        return left;
    }

    private AST parseComparison() {
        AST left = parseAddition();
        while (check(TokenType.LT, TokenType.GT, TokenType.LE, TokenType.GE, TokenType.LIKE)) {
            int line = current().line;
            if (check(TokenType.LIKE)) {
                advance();
                String className = expect(TokenType.IDENT).value;
                left = new LikeExpr(left, className, line);
            } else {
                String op = advance().value;
                AST right = parseAddition();
                left = new BinaryExpr(left, op, right, line);
            }
        }
        return left;
    }

    private AST parseAddition() {
        AST left = parseMultiplication();
        while (check(TokenType.PLUS, TokenType.MINUS)) {
            int line = current().line;
            String op = advance().value;
            AST right = parseMultiplication();
            left = new BinaryExpr(left, op, right, line);
        }
        return left;
    }

    private AST parseMultiplication() {
        AST left = parseUnary();
        while (check(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT, TokenType.DOUBLE_SLASH)) {
            int line = current().line;
            String op = advance().value;
            AST right = parseUnary();
            left = new BinaryExpr(left, op, right, line);
        }
        return left;
    }

    private AST parseUnary() {
        if (check(TokenType.MINUS, TokenType.NOT, TokenType.INC, TokenType.DEC)) {
            int line = current().line;
            String op = advance().value;
            AST operand = parseUnary();
            return new UnaryExpr(op, operand, true, line);
        }
        return parsePostfix();
    }

    private AST parsePostfix() {
        AST expr = parsePrimary();
        while (true) {
            if (check(TokenType.DOT)) {
                advance();
                String member = expect(TokenType.IDENT).value;
                if (check(TokenType.LPAREN)) {
                    // method call
                    List<AST> args = parseArgList();
                    expr = new MethodCallExpr(expr, member, args, expr.line);
                } else {
                    // field access
                    expr = new FieldAccessExpr(expr, member, expr.line);
                }
            } else if (check(TokenType.DOUBLE_COLON)) {
                int line = advance().line;
                String method = expect(TokenType.IDENT).value;
                expr = new FunctionRefExpr(expr, method, line);
            } else if (check(TokenType.LPAREN) && expr instanceof Identifier) {
                // standalone function call
                String funcName = ((Identifier) expr).name;
                List<AST> args = parseArgList();
                expr = new MethodCallExpr(null, funcName, args, expr.line);
            } else if (check(TokenType.LBRACKET)) {
                // array access: arr[index]
                int line = advance().line;
                AST index = parseExpression();
                expect(TokenType.RBRACKET);
                expr = new ArrayAccessExpr(expr, index, line);
            } else if (check(TokenType.INC)) {
                int line = current().line;
                advance();
                expr = new UnaryExpr("++", expr, false, line);
            } else if (check(TokenType.DEC)) {
                int line = current().line;
                advance();
                expr = new UnaryExpr("--", expr, false, line);
            } else {
                break;
            }
        }
        return expr;
    }

    private AST parsePrimary() {
        int line = current().line;

        if (check(TokenType.INT_LIT)) return new IntLit(advance().value, line);
        if (check(TokenType.LONG_LIT)) return new LongLit(advance().value, line);
        if (check(TokenType.FLOAT_LIT)) return new FloatLit(advance().value, line);
        if (check(TokenType.DOUBLE_LIT)) return new DoubleLit(advance().value, line);
        if (check(TokenType.STRING_LIT)) return new StringLit(advance().value, line);
        if (check(TokenType.STR_LIT)) return new StrLit(advance().value, line);
        if (check(TokenType.TRUE)) { advance(); return new BoolLit(true, line); }
        if (check(TokenType.FALSE)) { advance(); return new BoolLit(false, line); }
        if (check(TokenType.NULL)) { advance(); return new NullLit(line); }
        if (check(TokenType.THIS)) {
            advance();
            if (check(TokenType.DOUBLE_COLON)) {
                advance();
                String method = expect(TokenType.IDENT).value;
                return new FunctionRefExpr(new ThisExpr(line), method, line);
            }
            return new ThisExpr(line);
        }

        // A lambda is deliberately limited to one explicit parameter and a void body.
        if (check(TokenType.LPAREN) && peek(1).type == TokenType.IDENT &&
            peek(2).type == TokenType.RPAREN && peek(3).type == TokenType.ARROW) {
            advance();
            String parameter = expect(TokenType.IDENT).value;
            expect(TokenType.RPAREN);
            expect(TokenType.ARROW);
            if (!check(TokenType.VOID)) error("Lambda return type must be void");
            advance();
            AST body = parseBlock();
            return new LambdaExpr(parameter, body, line);
        }

        if (check(TokenType.NEW)) {
            advance();
            String className;
            if (check(TokenType.IDENT, TokenType.T_STRING)) {
                className = advance().value;
            } else {
                error("Expected class name after 'new'");
                className = "unknown";
            }
            expect(TokenType.LPAREN);
            List<AST> args = new ArrayList<>();
            if (!check(TokenType.RPAREN)) {
                args.add(parseArg());
                while (check(TokenType.COMMA)) {
                    advance();
                    args.add(parseArg());
                }
            }
            expect(TokenType.RPAREN);
            return new NewExpr(className, args, line);
        }

        // Type cast: byte(expr), int(expr), long(expr), float(expr), double(expr), string(expr)
        if (check(TokenType.T_BYTE, TokenType.T_INT, TokenType.T_LONG,
                  TokenType.T_FLOAT, TokenType.T_DOUBLE, TokenType.T_STR) && peek(1).type == TokenType.LPAREN) {
            String targetType = parseType();
            expect(TokenType.LPAREN);
            AST expr = parseExpression();
            expect(TokenType.RPAREN);
            return new TypeCastExpr(targetType, expr, line);
        }

        if (check(TokenType.IDENT)) {
            return new Identifier(advance().value, line);
        }

        // Array literal: [1, 2, 3]
        if (check(TokenType.LBRACKET)) {
            advance();
            List<AST> elements = new ArrayList<>();
            if (!check(TokenType.RBRACKET)) {
                elements.add(parseExpression());
                while (check(TokenType.COMMA)) {
                    advance();
                    elements.add(parseExpression());
                }
            }
            expect(TokenType.RBRACKET);
            return new ArrayLit(elements, line);
        }

        if (check(TokenType.LPAREN)) {
            advance();
            AST expr = parseExpression();
            expect(TokenType.RPAREN);
            return expr;
        }

        error("Unexpected token: " + current().type + " (" + current().value + ")");
        return null;
    }

    private List<AST> parseArgList() {
        expect(TokenType.LPAREN);
        List<AST> args = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            args.add(parseArg());
            while (check(TokenType.COMMA)) {
                advance();
                args.add(parseArg());
            }
        }
        expect(TokenType.RPAREN);
        return args;
    }

    private AST parseArg() {
        // def as placeholder for default value
        if (check(TokenType.DEF)) {
            int line = advance().line;
            return new VoidPlaceholder(line);
        }
        return parseExpression();
    }

    // ==================== Token helpers ====================

    private Token current() {
        return tokens.get(pos);
    }

    private Token peek(int offset) {
        int idx = pos + offset;
        if (idx >= tokens.size()) return tokens.get(tokens.size() - 1);
        return tokens.get(idx);
    }

    private boolean check(TokenType... types) {
        TokenType cur = current().type;
        for (TokenType t : types) {
            if (cur == t) return true;
        }
        return false;
    }

    private Token advance() {
        Token tok = tokens.get(pos);
        if (pos < tokens.size() - 1) pos++;
        return tok;
    }

    private Token expect(TokenType type) {
        if (!check(type)) {
            error("Expected " + type + " but got " + current().type + " (" + current().value + ")");
        }
        return advance();
    }

    private void optionalSemicolon() {
        if (check(TokenType.SEMICOLON)) {
            advance();
        }
    }

    private void error(String msg) {
        Token tok = current();
        throw new RuntimeException("Parser error at line " + tok.line + ", column " + tok.column + ": " + msg);
    }
}
