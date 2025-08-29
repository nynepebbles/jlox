package jlox;

import java.util.List;

class LoxFunction implements LoxCallable {
  private final Stmt.Function declaration;
  private final Enviroment closure;
  private final boolean isInitializer;

  LoxFunction(Stmt.Function declaration, Enviroment closure, boolean isInitializer) {

    // We can’t simply see if the name of the LoxFunction is “init” because the user
    // could have defined a function with that name. In that case, there is no this
    // to return. To avoid that weird edge case, we’ll directly store whether the
    // LoxFunction represents an initializer method.
    this.isInitializer = isInitializer;

    this.closure = closure;
    this.declaration = declaration;
  }

  // There isn’t much to it. We create a new environment nestled inside the
  // method’s original closure. Sort of a closure-within-a-closure. When the
  // method is called, that will become the parent of the method body’s
  // environment.
  //
  // We declare “this” as a variable in that environment and bind it to the given
  // instance, the instance that the method is being accessed from. Et voilà, the
  // returned LoxFunction now carries around its own little persistent world where
  // “this” is bound to the object.
  LoxFunction bind(LoxInstance instance) {
    Enviroment enviroment = new Enviroment(closure);
    enviroment.define("this", instance);
    return new LoxFunction(declaration, enviroment, isInitializer);
  }

  @Override
  public int arity() {
    return declaration.params.size();
  }

  // This gives nicer output if a user decides to print a function value.
  @Override
  public String toString() {
    return "<fn " + declaration.name.lexeme + ">";
  }

  @Override
  public Object call(Interpreter interpreter, List<Object> arguments) {
    // Parameters are core to functions, especially the fact that a function
    // encapsulates its parameters—no other code outside of the function can see
    // them. This means each function gets its own environment where it stores those
    // variables.
    Enviroment enviroment = new Enviroment(closure);
    for (int i = 0; i < declaration.params.size(); ++i) {
      enviroment.define(declaration.params.get(i).lexeme, arguments.get(i));
    }

    try {
      interpreter.executeBlock(declaration.body, enviroment);
    } catch (Return returnValue) {
      if (isInitializer)
        return closure.getAt(0, "this");
      return returnValue.value;
    }

    // If the function is an initializer, we override the actual return value and
    // forcibly return this.
    if (isInitializer)
      return closure.getAt(0, "this");

    // If it never catches one of these exceptions, it means the function reached
    // the end of its body without hitting a return statement. In that case, it
    // implicitly returns nil.
    return null;
  }
}
