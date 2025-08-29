package jlox;

import java.util.HashMap;
import java.util.Map;

class Enviroment {
  private final Map<String, Object> values = new HashMap<>();
  final Enviroment enclosing;

  Enviroment() {
    enclosing = null;
  }

  Enviroment(Enviroment enclosing) {
    this.enclosing = enclosing;
  }

  // We have made one interesting semantic choice. When we add the key to the map,
  // we don’t check to see if it’s already present. That means that this program
  // works:
  //
  // var a = "before";
  // print a; // "before".
  // var a = "after";
  // print a; // "after".
  //
  // The user may not intend to redefine an existing variable. (If they did mean
  // to, they probably would have used assignment, not var.) Making redefinition
  // an error would help them find that bug.
  void define(String name, Object value) {
    values.put(name, value);
  }

  Object getAt(int distance, String name) {
    return ancestor(distance).values.get(name);
  }

  Object get(Token name) {
    if (values.containsKey(name.lexeme)) {

      Object value = values.get(name.lexeme);
      return value;
    }

    if (enclosing != null)
      return enclosing.get(name);

    throw new RuntimeError(name, "Undefined variable '" + name.lexeme + "'.");
  }

  void assignAt(int distance, Token name, Object value) {
    ancestor(distance).values.put(name.lexeme, value);
  }

  // The key difference between assignment and definition is that assignment is
  // not allowed to create a new variable. In terms of our implementation, that
  // means it’s a runtime error if the key doesn’t already exist in the
  // environment’s variable map.
  //
  // Unlike Python and Ruby, Lox doesn’t do **implicit variable declaration**.
  void assign(Token name, Object value) {
    if (values.containsKey(name.lexeme)) {
      values.put(name.lexeme, value);
      return;
    }

    if (enclosing != null) {
      enclosing.assign(name, value);
      return;
    }

    throw new RuntimeError(name, "Undefined variable '" + name.lexeme + "'.");
  }

  Enviroment ancestor(int distance) {
    Enviroment enviroment = this;
    for (int i = 0; i < distance; ++i) {
      enviroment = enviroment.enclosing;
    }
    return enviroment;
  }
}
