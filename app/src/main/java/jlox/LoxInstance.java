package jlox;

import java.util.HashMap;
import java.util.Map;

// The runtime representation of an instance of a Lox class
class LoxInstance {
  private LoxClass klass;
  private final Map<String, Object> fields = new HashMap<>();

  LoxInstance(LoxClass klass) {
    this.klass = klass;
  }

  // Doing a hash table lookup for every field access is fast enough for many
  // language implementations, but not ideal. High performance VMs for languages
  // like JavaScript use sophisticated optimizations like “hidden classes” to
  // avoid that overhead.
  //
  // Paradoxically, many of the optimizations invented to make dynamic languages
  // fast rest on the observation that—even in those languages—most code is fairly
  // static in terms of the types of objects it works with and their fields.
  Object get(Token name) {
    if (fields.containsKey(name.lexeme)) {
      return fields.get(name.lexeme);
    }

    // When looking up a property on an instance, if we don’t find a matching field,
    // we look for a method with that name on the instance’s class. If found, we
    // return that. This is where the distinction between “field” and “property”
    // becomes meaningful. When accessing a property, you might get a field—a bit of
    // state stored on the instance—or you could hit a method defined on the
    // instance’s class.
    LoxFunction method = klass.findMethod(name.lexeme);
    if (method != null) {

      return method.bind(this);
    }

    // An interesting edge case we need to handle is what happens if the instance
    // doesn’t have a property with the given name. We could silently return some
    // dummy value like nil, but languages with this behavior masks bugs more often
    // than it does anything useful. Instead, we’ll make it a runtime error.
    throw new RuntimeError(name, "Undefined property '" + name.lexeme + "'.");
  }

  void set(Token name, Object value) {
    fields.put(name.lexeme, value);
  }

  @Override
  public String toString() {
    return klass.name + " instance";
  }
}
