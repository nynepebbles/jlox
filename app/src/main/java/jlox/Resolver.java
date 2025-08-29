package jlox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

// The resolver implements the visitor abstraction, since it needs to visit every
// node in the syntax tree. Only a few kinds of nodes are interesting when it 
// comes to resolving variables:
//
// . A block statement introduces a new scope for the statements it contains.
// . A function declaration introduces a new scope for its body and binds its
// parameters in that scope.
// . A variable declaration adds a new variable to the current scope.
// . Variable and assignment expressions need to have their variables resolved.
//
// The rest of the nodes don’t do anything special, but we still need to
// implement visit methods for them that traverse into their subtrees. Even
// though a + expression doesn’t itself have any variables to resolve, either of
// its operands might.

class Resolver implements Expr.Visitor<Void>, Stmt.Visitor<Void> {
  private final Interpreter interpreter;
  private FunctionType currentFunction = FunctionType.NONE;

  // The scope stack is only used for local block scopes. Variables declared at
  // the top level in the global scope are not tracked by the resolver since they
  // are more dynamic in Lox. When resolving a variable, if we can’t find it in
  // the stack of local scopes, we assume it must be global.
  private final Stack<Map<String, Boolean>> scopes = new Stack<>();

  private enum FunctionType {
    NONE, FUNCTION, METHOD, INITIALIZER
  }

  private enum ClassType {
    NONE, CLASS, SUBCLASS
  }

  private ClassType currentClass = ClassType.NONE;

  Resolver(Interpreter interpreter) {
    this.interpreter = interpreter;
  }

  void resolve(List<Stmt> statements) {
    for (Stmt statement : statements) {
      resolve(statement);
    }
  }

  private void resolve(Stmt stmt) {
    stmt.accept(this);
  }

  private void resolve(Expr expr) {
    expr.accept(this);
  }

  private void beginScope() {
    scopes.push(new HashMap<String, Boolean>());
  }

  private void endScope() {
    scopes.pop();
  }

  // Declaration adds the variable to the innermost scope so that it shadows any
  // outer one and so that we know the variable exists. We mark it as “not ready
  // yet” by binding its name to false in the scope map. The value associated with
  // a key in the scope map represents whether or not we have finished resolving
  // that variable’s initializer.
  private void declare(Token name) {
    if (scopes.isEmpty())
      return;
    Map<String, Boolean> scope = scopes.peek();
    if (scope.containsKey(name.lexeme)) {
      Lox.error(name, "Already a variable with this name in this scope.");
    }
    scope.put(name.lexeme, false);
  }

  private void define(Token name) {
    if (scopes.isEmpty())
      return;

    // We set the variable’s value in the scope map to true to mark it as fully
    // initialized and available for use.
    scopes.peek().put(name.lexeme, true);
  }

  private void resolveLocal(Expr expr, Token name) {
    // This looks, for good reason, a lot like the code in Environment for
    // evaluating a variable. We start at the innermost scope and work outwards,
    // looking in each map for a matching name. If we find the variable, we resolve
    // it, passing in the number of scopes between the current innermost scope and
    // the scope where the variable was found. So, if the variable was found in the
    // current scope, we pass in 0. If it’s in the immediately enclosing scope, 1.
    // You get the idea.
    for (int i = scopes.size() - 1; i >= 0; --i) {
      if (scopes.get(i).containsKey(name.lexeme)) {
        interpreter.resolve(expr, scopes.size() - 1 - i);
      }
    }

    // If we walk through all of the block scopes and never find the variable, we
    // leave it unresolved and assume it’s global.
  }

  private void resolveFunction(Stmt.Function function, FunctionType type) {
    // This is used to tell tell whether or not we’re inside a function declaration
    FunctionType enclosingFunction = currentFunction;
    currentFunction = type;

    beginScope();
    for (Token param : function.params) {
      declare(param);
      define(param);
    }
    // resolving the function body is different from how the interpreter handles
    // function declarations. At runtime, declaring a function doesn’t do anything
    // with the function’s body. The body doesn’t get touched until later when the
    // function is called. In a static analysis, we immediately traverse into the
    // body right then and there.
    resolve(function.body);
    endScope();
    currentFunction = enclosingFunction;
  }

  // This begins a new scope, traverses into the statements inside the block, and
  // then discards the scope. The fun stuff lives in those helper methods.
  @Override
  public Void visitBlockStmt(Stmt.Block stmt) {
    beginScope();
    resolve(stmt.statements);
    endScope();
    return null;
  }

  @Override
  public Void visitClassStmt(Stmt.Class stmt) {
    ClassType enclosingClass = currentClass;
    currentClass = ClassType.CLASS;

    declare(stmt.name);
    define(stmt.name);

    if (stmt.superclass != null && stmt.name.lexeme.equals(stmt.superclass.name.lexeme)) {
      Lox.error(stmt.superclass.name, "A class can't inherit from itself");
    }

    if (stmt.superclass != null) {
      currentClass = ClassType.SUBCLASS;
      resolve(stmt.superclass);
    }

    if (stmt.superclass != null) {
      beginScope();
      scopes.peek().put("super", true);
    }

    // Before we step in and start resolving the method bodies, we push a new scope
    // and define “this” in it as if it were a variable.
    beginScope();
    scopes.peek().put("this", true);

    for (Stmt.Function method : stmt.methods) {
      FunctionType declaration = FunctionType.METHOD;
      if (method.name.lexeme.equals("init")) {
        declaration = FunctionType.INITIALIZER;
      }
      resolveFunction(method, declaration);
    }

    // Then, when we’re done, we discard that surrounding scope.
    endScope();

    if (stmt.superclass != null)
      endScope();

    // Now, whenever a this expression is encountered (at least inside a method) it
    // will resolve to a “local variable” defined in an implicit scope just outside
    // of the block for the method body.

    // The resolver has a new scope for `this`, so the interpreter needs to create a
    // corresponding environment for it. Remember, we always have to keep the
    // resolver’s scope chains and the interpreter’s linked environments in sync
    // with each other. At runtime, we create the environment after we find the
    // method on the instance.

    currentClass = enclosingClass;
    return null;
  }

  // Resolving a variable declaration adds a new entry to the current innermost
  // scope’s map. That seems simple, but there’s a little dance we need to do. We
  // split binding into two steps, declaring then defining, in order to handle
  // funny edge cases like this:
  //
  // var a = "outer";
  // { var a = a; }
  //
  @Override
  public Void visitVarStmt(Stmt.Var stmt) {
    declare(stmt.name);
    if (stmt.initializer != null) {
      resolve(stmt.initializer);
    }
    define(stmt.name);
    return null;
  }

  @Override
  public Void visitVariableExpr(Expr.Variable expr) {
    // First, we check to see if the variable is being accessed inside its own
    // initializer. This is where the values in the scope map come into play. If the
    // variable exists in the current scope but its value is false, that means we
    // have declared it but not yet defined it. We report that error.
    if (!scopes.isEmpty() && scopes.peek().get(expr.name.lexeme) == Boolean.FALSE) {
      Lox.error(expr.name, "Can't read local variable in its own initializer.");
    }

    resolveLocal(expr, expr.name);
    return null;
  }

  @Override
  public Void visitAssignExpr(Expr.Assign expr) {
    // First, we resolve the expression for the assigned value in case it also
    // contains references to other variables. Then we use our existing
    // resolveLocal() method to resolve the variable that’s being assigned to.
    resolve(expr.value);
    resolveLocal(expr, expr.name);
    return null;
  }

  // Functions both bind names and introduce a scope. The name of the function
  // itself is bound in the surrounding scope where the function is declared. When
  // we step into the function’s body, we also bind its parameters into that inner
  // function scope.
  @Override
  public Void visitFunctionStmt(Stmt.Function stmt) {
    // Similar to visitVariableStmt(), we declare and define the name of the
    // function in the current scope. Unlike variables, though, we define the name
    // eagerly, before resolving the function’s body. This lets a function
    // recursively refer to itself inside its own body.
    declare(stmt.name);
    define(stmt.name);
    resolveFunction(stmt, FunctionType.FUNCTION);
    return null;
  }

  @Override
  public Void visitExpressionStmt(Stmt.Expression stmt) {
    resolve(stmt.expression);
    return null;
  }

  // Here, we see how resolution is different from interpretation. When we resolve
  // an if statement, there is no control flow. We resolve the condition and both
  // branches. Where a dynamic execution steps only into the branch that is run, a
  // static analysis is conservative—it analyzes any branch that could be run.
  // Since either one could be reached at runtime, we resolve both.
  @Override
  public Void visitIfStmt(Stmt.If stmt) {
    resolve(stmt.condition);
    resolve(stmt.thenBranch);
    if (stmt.elseBranch != null)
      resolve(stmt.elseBranch);
    return null;
  }

  @Override
  public Void visitPrintStmt(Stmt.Print stmt) {
    resolve(stmt.expression);
    return null;
  }

  @Override
  public Void visitReturnStmt(Stmt.Return stmt) {
    if (currentFunction == FunctionType.NONE) {
      Lox.error(stmt.keyword, "Can't return from top-level code.");
    }

    if (stmt.value != null) {
      if (currentFunction == FunctionType.INITIALIZER) {
        Lox.error(stmt.keyword, "Can't return a value from an initializer.");
      }

      resolve(stmt.value);
    }
    return null;
  }

  @Override
  public Void visitWhileStmt(Stmt.While stmt) {
    resolve(stmt.condition);
    resolve(stmt.body);
    return null;
  }

  @Override
  public Void visitBinaryExpr(Expr.Binary expr) {
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitCallExpr(Expr.Call expr) {
    resolve(expr.callee);
    for (Expr argument : expr.arguments) {
      resolve(argument);
    }

    return null;
  }

  @Override
  public Void visitGetExpr(Expr.Get expr) {
    resolve(expr.object);
    return null;
  }

  @Override
  public Void visitGroupingExpr(Expr.Grouping expr) {
    resolve(expr.expression);
    return null;
  }

  @Override
  public Void visitLiteralExpr(Expr.Literal expr) {
    return null;
  }

  @Override
  public Void visitLogicalExpr(Expr.Logical expr) {
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitSetExpr(Expr.Set expr) {
    resolve(expr.value);
    resolve(expr.object);
    return null;
  }

  @Override
  public Void visitSuperExpr(Expr.Super expr) {
    if (currentClass == ClassType.NONE) {
      Lox.error(expr.keyword, "Can't use 'super' outside of a class.");
    } else if (currentClass != ClassType.SUBCLASS) {
      Lox.error(expr.keyword, "Can't use 'super' in a class with no superclass.");
    }

    resolveLocal(expr, expr.keyword);
    return null;
  }

  @Override
  public Void visitThisExpr(Expr.This expr) {
    if (currentClass == ClassType.NONE) {
      Lox.error(expr.keyword, "Can't use 'this' outside of a class.");
    }

    resolveLocal(expr, expr.keyword);
    return null;
  }

  @Override
  public Void visitUnaryExpr(Expr.Unary expr) {
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitBreakStmt(Stmt.Break expr) {
    return null;
  }
}
