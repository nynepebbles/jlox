# jlox: A Java Interpreter for the Lox Language

![Java](https://img.shields.io/badge/Java-ED8B00?style=flat&logo=openjdk&logoColor=white)

This repository contains a full-featured Java implementation of the Lox programming language, built by following the excellent guide **["Crafting Interpreters"](https://craftinginterpreters.com/) by Robert Nystrom**. It features a hand-written scanner, a recursive descent parser, a static resolver for lexical scoping, and a tree-walk interpreter.

## About Lox

Lox is a simple, dynamically-typed, object-oriented scripting language with a C-like syntax. It's designed to be small enough to be implemented from scratch, yet powerful enough to be useful.

This implementation, **jlox**, brings the language to life on the Java Virtual Machine (JVM).

---

## Features

The interpreter implements the full feature set of the Lox language described in the book:

* **Data Types**: Booleans, numbers, strings, and `nil`.
* **Operators**: Arithmetic (`+`, `-`, `*`, `/`), logical (`and`, `or`), comparison (`<`, `>`, `==`, etc.), and unary (`!`, `-`).
* **Control Flow**: `if`/`else` statements, `while` and `for` loops.
* **Functions**: First-class functions, closures, and recursion.
* **Object-Oriented Programming**: Classes (`class`), instances, methods, initializers (`init`), and single inheritance.
* **Static Analysis**: A variable resolver that performs a separate pass to handle lexical scope (`this`, `super`) before interpretation.
* **Interactive REPL**: Run code interactively from the command line.

---

## Project Architecture

The interpreter is broken down into several distinct stages, each handled by a core component:

* **`Scanner.java`**: The lexical analyzer. It takes raw Lox source code as a string and scans it into a series of **tokens**.
* **`Parser.java`**: The syntactic analyzer. It uses a recursive descent algorithm to parse the stream of tokens, building an **Abstract Syntax Tree (AST)** that represents the code's structure.
* **`Resolver.java`**: A static analyzer that walks the AST before the interpreter. It resolves all variable bindings, determining the scope and "hop count" for every variable. This allows the interpreter to implement lexical scope efficiently and correctly.
* **`Interpreter.java`**: A tree-walk interpreter. It traverses the AST, evaluating each node and executing the program's logic. It manages environments for variable storage and handles runtime state.
* **`tool/GenerateAST.java`**: A helper script that programmatically generates the `Expr.java` and `Stmt.java` classes. These classes define the nodes of the Abstract Syntax Tree using the Visitor pattern, ensuring a clean separation of tree structure and the operations performed on it (like resolving and interpreting).

---

## Getting Started

### Prerequisites

* A Java Development Kit (JDK), version 8 or higher.

### Building the Project

The project is built using the Gradle wrapper, so no manual installation of Gradle is required.

To compile the source code and build the project, run the following command from the root directory:

```bash
./gradlew build
```

### Running the Interpreter

You can run the interpreter in two modes:

1.  **Interactive REPL (Read-Eval-Print Loop)**
    To start an interactive session where you can type Lox code directly, run:
    ```bash
    ./gradlew :app:run
    ```
    ```
    > print "Hello, world!";
    Hello, world!
    > 1 + 2 * 3;
    7
    ```

2.  **Running a Script File**
    To execute a Lox script from a file (e.g., `script.lox`), use the `--args` flag:
    ```bash
    ./gradlew :app:run --args="path/to/your/script.lox"
    ```

---

## Example Lox Program

Here is a sample program in Lox that demonstrates functions, classes, and inheritance.

```lox
fun fib(n) {
  if (n <= 1) return n;
  return fib(n - 2) + fib(n - 1);
}

class Greeter {
  greet(person) {
    return "Greetings, " + person;
  }
}

class LoudGreeter < Greeter {
  greet(person) {
    var greeting = super.greet(person);
    return greeting + "!!!";
  }
}

print "Fibonacci(10) is " + fib(10);

var greeter = LoudGreeter();
print greeter.greet("Lox User");
```

---

## Acknowledgements

* This project would not be possible without the phenomenal book **["Crafting Interpreters"](https://craftinginterpreters.com/) by Robert Nystrom**. All credit for the language design and implementation strategy goes to him.
