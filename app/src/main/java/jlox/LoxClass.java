package jlox;

import java.util.List;
import java.util.Map;

class LoxClass implements LoxCallable {
  final String name;
  final LoxClass superclass;
  private final Map<String, LoxFunction> methods;

  // Where an instance stores state, the class stores behavior. LoxInstance has
  // its map of fields, and LoxClass gets a map of methods. Even though methods
  // are owned by the class, they are still accessed through instances of that
  // class.
  LoxClass(String name, LoxClass superclass, Map<String, LoxFunction> methods) {
    this.superclass = superclass;
    this.name = name;
    this.methods = methods;
  }

  LoxFunction findMethod(String name) {
    if (methods.containsKey(name)) {
      return methods.get(name);
    }

    if (superclass != null) {
      return superclass.findMethod(name);
    }

    return null;
  }

  @Override
  public String toString() {
    return name;
  }

  // When you “call” a class, it instantiates a new LoxInstance for the called
  // class and returns it.
  @Override
  public Object call(Interpreter interpreter, List<Object> arguments) {
    LoxInstance instance = new LoxInstance(this);

    // When a class is called, after the LoxInstance is created, we look for an
    // “init” method. If we find one, we immediately bind and invoke it just like a
    // normal method call. The argument list is forwarded along.
    LoxFunction initializer = findMethod("init");
    if (initializer != null) {
      initializer.bind(instance).call(interpreter, arguments);
    }

    return instance;
  }

  @Override
  public int arity() {
    LoxFunction initializer = findMethod("init");
    if (initializer == null)
      return 0;
    return initializer.arity();
  }
}
