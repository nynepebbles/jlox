package jlox;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

// This class wraps the return value with the accoutrements Java requires for a
// runtime exception class.
class Return extends RuntimeException {
  final Object value;

  // The weird super constructor call with those null and false arguments disables
  // some JVM machinery that we don’t need. Since we’re using our exception class
  // for control flow and not actual error handling, we don’t need overhead like
  // stack traces.
  Return(Object value) {
    super(null, null, false, false);
    this.value = value;
  }
}

class Interpreter implements Expr.Visitor<Object>, Stmt.Visitor<Void> {
  final Enviroment globals = new Enviroment(); // fixed reference to the outermost global enviroment
  private Enviroment enviroment = globals; // tracks the current scope enviroment
  private final Map<Expr, Integer> envDistances = new HashMap<>();

  // For the record, I’m not generally a fan of using exceptions for control flow.
  // But inside a heavily recursive tree-walk interpreter, it’s the way to go.
  // Since our own syntax tree evaluation is so heavily tied to the Java call
  // stack, we’re pressed to do some heavyweight call stack manipulation
  // occasionally, and exceptions are a handy tool for that.
  public static class Break extends RuntimeException {
    Break() {
      super(null, null, false, false);
    }
  }

  Interpreter() {
    globals.define("clock", new LoxCallable() {
      @Override
      public int arity() {
        return 0;
      }

      @Override
      public Object call(Interpreter interpreter, List<Object> arguments) {
        return (double) System.currentTimeMillis() / 1000.0;
      }

      @Override
      public String toString() {
        return "<native fn>";
      }
    });
  }

  void interpret(Stmt statement) {
    try {
      execute(statement);
    } catch (RuntimeError error) {
      Lox.runtimeError(error);
    }
    // Object last = statements.get(statements.size() - 1);
    // if (interactive && last instanceof Stmt.Expression) {
    // Expr expr = ((Stmt.Expression) last).expression;
    // System.out.println(stringify(evaluate(expr)));
  }

  Object interpret(Expr expression) {
    try {
      return evaluate(expression);
    } catch (RuntimeError error) {
      Lox.runtimeError(error);
    }
    return null;
  }

  private void execute(Stmt stmt) {
    stmt.accept(this);
  }

  void resolve(Expr expr, int depth) {
    envDistances.put(expr, depth);
  }

  // Manually changing and restoring a mutable environment field feels inelegant.
  // Another classic approach is to explicitly pass the environment as a parameter
  // to each visit method. To “change” the environment, you pass a different one
  // as you recurse down the tree. You don’t have to restore the old one, since
  // the new one lives on the Java stack and is implicitly discarded when the
  // interpreter returns from the block’s visit method.
  //
  // I considered that for jlox, but it’s kind of tedious and verbose adding an
  // environment parameter to every single visit method. To keep the book a little
  // simpler, I went with the mutable field.
  void executeBlock(List<Stmt> statements, Enviroment enviroment) {
    Enviroment previous = this.enviroment;
    try {
      this.enviroment = enviroment;

      for (Stmt statement : statements) {
        execute(statement);
      }
    } finally {
      this.enviroment = previous;
    }
  }

  // First, we look up the resolved distance in the map. Remember that we resolved
  // only local variables. Globals are treated specially and don’t end up in the
  // map (hence the name locals). So, if we don’t find a distance in the map, it
  // must be global. In that case, we look it up, dynamically, directly in the
  // global environment. That throws a runtime error if the variable isn’t
  // defined.
  private Object lookUpVariable(Token name, Expr expr) {
    Integer distance = envDistances.get(expr);
    if (distance != null) {
      return enviroment.getAt(distance, name.lexeme);
    } else {
      return globals.get(name);
    }
  }

  // This is another one of those subtle semantic choices. Since argument
  // expressions may have side effects, the order they are evaluated could be user
  // visible. Even so, some languages like Scheme and C don’t specify an order.
  // This gives compilers freedom to reorder them for efficiency, but means users
  // may be unpleasantly surprised if arguments aren’t evaluated in the order they
  // expect.
  @Override
  public Object visitCallExpr(Expr.Call expr) {
    Object callee = evaluate(expr.callee);

    List<Object> arguments = new ArrayList<>();
    for (Expr argument : expr.arguments) {
      arguments.add(evaluate(argument));
    }

    if (!(callee instanceof LoxCallable)) {
      throw new RuntimeError(expr.paren, "Cal only call functions and classes.");
    }

    LoxCallable function = (LoxCallable) callee;

    // We could push the arity checking into the concrete implementation of call().
    // But, since we’ll have multiple classes implementing LoxCallable, that would
    // end up with redundant validation spread across a few classes. Hoisting it up
    // into the visit method lets us do it in one place.
    if (arguments.size() != function.arity()) {
      throw new RuntimeError(expr.paren,
          "Expected " + function.arity() + " arguments but got " + arguments.size() + ".");
    }
    return function.call(this, arguments);
  }

  // First, we evaluate the expression whose property is being accessed. In Lox,
  // only instances of classes have properties. If the object is some other type
  // like a number, invoking a getter on it is a runtime error. If the object is a
  // LoxInstance, then we ask it to look up the property.
  @Override
  public Object visitGetExpr(Expr.Get expr) {
    Object object = evaluate(expr.object);
    if (object instanceof LoxInstance) {
      return ((LoxInstance) object).get(expr.name);
    }

    throw new RuntimeError(expr.name, "Only instances have properties.");
  }

  @Override
  public Void visitBreakStmt(Stmt.Break stmt) {
    throw new Break();
  }

  @Override
  public Void visitBlockStmt(Stmt.Block stmt) {
    executeBlock(stmt.statements, new Enviroment(enviroment));
    return null;
  }

  // This looks similar to how we execute function declarations. We declare the
  // class’s name in the current environment. Then we turn the class syntax node
  // into a LoxClass, the runtime representation of a class. We circle back and
  // store the class object in the variable we previously declared. That two-stage
  // variable binding process allows references to the class inside its own
  // methods.
  @Override
  public Void visitClassStmt(Stmt.Class stmt) {
    Object superclass = null;
    if (stmt.superclass != null) {
      superclass = evaluate(stmt.superclass);
      if (!(superclass instanceof LoxClass)) {
        throw new RuntimeError(stmt.superclass.name, "Superclass must be a class.");
      }
    }

    enviroment.define(stmt.name.lexeme, null);

    if (stmt.superclass != null) {
      enviroment = new Enviroment(enviroment);
      enviroment.define("super", superclass);
    }

    Map<String, LoxFunction> methods = new HashMap<>();
    for (Stmt.Function method : stmt.methods) {
      LoxFunction function = new LoxFunction(method, enviroment, method.name.lexeme.equals("init"));
      methods.put(method.name.lexeme, function);
    }

    LoxClass klass = new LoxClass(stmt.name.lexeme, (LoxClass) superclass, methods);

    if (superclass != null) {
      enviroment = enviroment.enclosing;
    }

    enviroment.assign(stmt.name, klass);
    return null;
  }

  @Override
  public Object visitAssignExpr(Expr.Assign expr) {
    Object value = evaluate(expr.value);

    Integer distance = envDistances.get(expr);
    if (distance != null) {
      enviroment.assignAt(distance, expr.name, value);
    } else {
      globals.assign(expr.name, value);
    }

    return value;
  }

  // If the variable has an initializer, we evaluate it. If not, we have another
  // choice to make. We could have made this a syntax error in the parser by
  // requiring an initializer. Most languages don’t, though.
  //
  // We could make it a runtime error. We’d let you define an uninitialized
  // variable, but if you accessed it before assigning to it, a runtime error
  // would occur. It’s not a bad idea, but most dynamically typed languages don’t
  // do that. Instead, we’ll keep it simple and say that Lox sets a variable to
  // nil if it isn’t explicitly initialized.
  @Override
  public Void visitVarStmt(Stmt.Var stmt) {
    Object value = null;
    if (stmt.initializer != null) {
      value = evaluate(stmt.initializer);
    }

    enviroment.define(stmt.name.lexeme, value);
    return null;
  }

  @Override
  public Void visitWhileStmt(Stmt.While stmt) {
    while (isTruthy(evaluate(stmt.condition))) {
      try {
        execute(stmt.body);
      } catch (Break e) {
        break;
      }
    }
    return null;
  }

  @Override
  public Object visitVariableExpr(Expr.Variable expr) {
    return lookUpVariable(expr.name, expr);
  }

  @Override
  public Void visitExpressionStmt(Stmt.Expression stmt) {
    evaluate(stmt.expression);
    return null;
  }

  // This is similar to how we interpret other literal expressions. We take a
  // function syntax node—a compile-time representation of the function—and
  // convert it to its runtime representation. Here, that’s a LoxFunction that
  // wraps the syntax node.
  @Override
  public Void visitFunctionStmt(Stmt.Function stmt) {
    LoxFunction function = new LoxFunction(stmt, enviroment, false);
    enviroment.define(stmt.name.lexeme, function);
    return null;
  }

  @Override
  public Void visitIfStmt(Stmt.If stmt) {
    if (isTruthy(evaluate(stmt.condition))) {
      execute(stmt.thenBranch);
    } else if (stmt.elseBranch != null) {
      execute(stmt.elseBranch);
    }
    return null;
  }

  @Override
  public Void visitPrintStmt(Stmt.Print stmt) {
    Object value = evaluate(stmt.expression);
    System.out.println(stringify(value));
    return null;
  }

  @Override
  public Void visitReturnStmt(Stmt.Return stmt) {
    Object value = null;
    if (stmt.value != null)
      value = evaluate(stmt.value);
    throw new Return(value);
  }

  @Override
  public Object visitLiteralExpr(Expr.Literal expr) {
    return expr.value;
  }

  // If you compare this to the visitBinaryExpr() method, you can see the
  // difference. Here, we evaluate the left operand first. We look at its value to
  // see if we can short-circuit. If not, and only then, do we evaluate the right
  // operand.
  //
  // The other interesting piece here is deciding what actual value to return.
  // Since Lox is dynamically typed, we allow operands of any type and use
  // truthiness to determine what each operand represents. We apply similar
  // reasoning to the result. Instead of promising to literally return true or
  // false, a logic operator merely guarantees it will return a value with
  // appropriate truthiness.
  @Override
  public Object visitLogicalExpr(Expr.Logical expr) {
    Object left = evaluate(expr.left);

    if (expr.operator.type == TokenType.OR) {
      if (isTruthy(left))
        return left;
    } else {
      if (!isTruthy(left))
        return left;
    }

    return evaluate(expr.right);
  }

  // We evaluate the object whose property is being set and check to see if it’s a
  // LoxInstance. If not, that’s a runtime error. Otherwise, we evaluate the value
  // being set and store it on the instance.
  //
  // This is another semantic edge case. There are three distinct operations:
  //
  // . Evaluate the object.
  // . Raise a runtime error if it’s not an instance of a class.
  // . Evaluate the value.
  //
  // The order that those are performed in could be user visible, which means we
  // need to carefully specify it and ensure our implementations do these in the
  // same order.
  @Override
  public Object visitSetExpr(Expr.Set expr) {
    Object object = evaluate(expr.object);
    if (!(object instanceof LoxInstance)) {
      throw new RuntimeError(expr.name, "Only instances have fields.");
    }
    Object value = evaluate(expr.value);
    ((LoxInstance) object).set(expr.name, value);
    return value;
  }

  @Override
  public Object visitSuperExpr(Expr.Super expr) {
    int distance = envDistances.get(expr);
    LoxClass superclass = (LoxClass) enviroment.getAt(distance, "super");

    LoxInstance object = (LoxInstance) enviroment.getAt(distance - 1, "this");
    LoxFunction method = superclass.findMethod(expr.method.lexeme);

    if (method == null) {
      throw new RuntimeError(expr.method, "Undefined property '" + expr.method.lexeme + "'.");
    }

    return method.bind(object);
  }

  @Override
  public Object visitThisExpr(Expr.This expr) {
    return lookUpVariable(expr.keyword, expr);
  }

  @Override
  public Object visitGroupingExpr(Expr.Grouping expr) {
    return evaluate(expr.expression);
  }

  // You can start to see how evaluation recursively traverses the tree. We can’t
  // evaluate the unary operator itself until after we evaluate its operand
  // subexpression. That means our interpreter is doing a **post-order
  // traversal** — each node evaluates its children before doing its own work.
  @Override
  public Object visitUnaryExpr(Expr.Unary expr) {
    Object right = evaluate(expr.right);

    switch (expr.operator.type) {
      case BANG:
        return !isTruthy(right);
      case MINUS:
        checkNumberOperand(expr.operator, right);
        return -(double) right;
      default:
        Lox.error(expr.operator.line, "Unknown unary operator ' " + expr.operator.lexeme + " '.");
    }

    // Unreachable
    return null;
  }

  // We pinned down a subtle corner of the language semantics here.
  // In a binary expression, we evaluate the operands in left-to-right order.
  // If those operands have side effects, that choice is user visible, so this
  // isn’t simply an implementation detail.
  @Override
  public Object visitBinaryExpr(Expr.Binary expr) {
    Object left = evaluate(expr.left);
    Object right = evaluate(expr.right);

    switch (expr.operator.type) {
      case GREATER:
        checkNumberOperands(expr.operator, left, right);
        return (double) left > (double) right;
      case GREATER_EQUAL:
        checkNumberOperands(expr.operator, left, right);
        return (double) left >= (double) right;
      case LESS:
        checkNumberOperands(expr.operator, left, right);
        return (double) left < (double) right;
      case LESS_EQUAL:
        checkNumberOperands(expr.operator, left, right);
        return (double) left <= (double) right;
      case MINUS:
        checkNumberOperands(expr.operator, left, right);
        return (double) left - (double) right;
      case SLASH:
        checkNumberOperands(expr.operator, left, right);
        if ((double) right == 0) {
          throw new RuntimeError(expr.operator, "Can't divide by 0");
        }
        return (double) left / (double) right;
      case STAR:
        checkNumberOperands(expr.operator, left, right);
        return (double) left * (double) right;
      case BANG_EQUAL:
        return !isEqual(left, right);
      case EQUAL_EQUAL:
        return isEqual(left, right);
      case PLUS:
        if (left instanceof Double && right instanceof Double) {
          return (double) left + (double) right;
        }
        if (left instanceof String) {
          if (right instanceof String) {
            return (String) left + (String) right;
          }
          return (String) left + stringify(right);
        }
        if (right instanceof String) {
          return stringify(left) + (String) right;
        }

        throw new RuntimeError(expr.operator, "Operands must be two numbers or either of them a string.");
      default:
        Lox.error(expr.operator.line, "Unknown binary operator ' " + expr.operator.lexeme + " '.");
    }

    // Unreachable
    return null;
  }

  private Object evaluate(Expr expr) {
    return expr.accept(this);
  }

  // Lox follows Ruby’s simple rule: false and nil are falsey, and everything else
  // is truthy. We implement that like so:
  private boolean isTruthy(Object object) {
    if (object == null)
      return false;
    if (object instanceof Boolean)
      return (boolean) object;
    return true;
  }

  // Unlike the comparison operators which require numbers, the equality operators
  // support operands of any type, even mixed ones. You can’t ask Lox if 3 is less
  // than "three", but you can ask if it’s equal to it.
  private boolean isEqual(Object a, Object b) {
    // We do have to handle nil/null specially so that we don’t throw a
    // NullPointerException if we try to call equals() on null. Otherwise, we’re
    // fine.
    if (a == null && b == null)
      return true;
    if (a == null)
      return true;

    return a.equals(b);

    // What do you expect this to evaluate to:
    //
    // (0 / 0) == (0 / 0)
    //
    // According to IEEE 754, which specifies the behavior of double-precision
    // numbers, dividing a zero by zero gives you the special NaN (“not a number”)
    // value. Strangely enough, NaN is not equal to itself.
    //
    // In Java, the == operator on primitive doubles preserves that behavior, but
    // the equals() method on the Double class does not. Lox uses the latter, so
    // doesn’t follow IEEE. These kinds of subtle incompatibilities occupy a
    // dismaying fraction of language implementers’ lives.
  }

  private void checkNumberOperand(Token operator, Object operand) {
    if (operand instanceof Double)
      return;
    throw new RuntimeError(operator, "Operand must be a number.");
  }

  // Another subtle semantic choice: We evaluate both operands before checking the
  // type of either. Imagine we have a function say() that prints its argument
  // then returns it. Using that, we write:
  //
  // say("left") - say("right");
  //
  // Our interpreter prints “left” and “right” before reporting the runtime error.
  // We could have instead specified that the left operand is checked before even
  // evaluating the right.
  private void checkNumberOperands(Token operator, Object left, Object right) {
    if (left instanceof Double && right instanceof Double)
      return;
    throw new RuntimeError(operator, "Operands must be numbers.");
  }

  public String stringify(Object object) {
    if (object == null)
      return "nil";

    // Yet again, we take care of this edge case with numbers to ensure that jlox
    // and clox work the same. Handling weird corners of the language like this will
    // drive you crazy but is an important part of the job.
    if (object instanceof Double) {
      String text = object.toString();
      if (text.endsWith(".0")) {
        text = text.substring(0, text.length() - 2);
      }
      return text;
    }

    return object.toString();
  }
}
