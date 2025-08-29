package jlox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

class ErrorCode {

  static final int EX_USAGE = 64;
  static final int EX_DATAERR = 65;
  static final int EX_SOFTWARE = 70;
};

public class Lox {
  private static final Interpreter Interpreter = new Interpreter();
  static boolean hadError = false;
  static boolean hadRuntimeError = false;

  public static void main(String[] args) throws IOException {
    // Expr expression = new Expr.Binary(
    // new Expr.Unary(
    // new Token(TokenType.MINUS, "-", null, 1),
    // new Expr.Literal(123)),
    // new Token(TokenType.STAR, "*", null, 1),
    // new Expr.Grouping(
    // new Expr.Literal(45.67)));
    //
    // System.out.println(new AstPrinter().print(expression));
    //
    // System.exit(0);

    if (args.length > 1) {
      System.out.println("Usage: jlox [script]");
      System.exit(ErrorCode.EX_USAGE);
    } else if (args.length == 1) {
      try {
        runFile(args[0]);
      } catch (Exception e) {
        System.err.println("Failed to read file '" + args[0] + "' due to " + e.toString());
        System.exit(ErrorCode.EX_USAGE);
      }
    } else {
      runPrompt();
    }
  }

  private static void runFile(String path) throws IOException {
    byte[] bytes = Files.readAllBytes(Paths.get(path));

    run(new String(bytes, Charset.defaultCharset()), false);

    if (hadError)
      System.exit(ErrorCode.EX_DATAERR);

    if (hadRuntimeError)
      System.exit(ErrorCode.EX_SOFTWARE);
  }

  private static void runPrompt() throws IOException {
    InputStreamReader input = new InputStreamReader(System.in);
    BufferedReader reader = new BufferedReader(input);

    while (true) {
      System.out.print("> ");
      String line = reader.readLine();
      if (line == null) // null <=> "end-of-file" (CTRL-D)
      {
        System.out.println();
        break;
      }
      run(line, true);
      hadError = false;
    }
  }

  private static void run(String source, boolean interactive) {
    Scanner scanner = new Scanner(source);
    List<Token> tokens = scanner.scanTokens();

    Parser parser = new Parser(tokens);
    List<Stmt> statements = parser.parse();

    // Stop if there was a syntax error.
    if (hadError)
      return;

    // We don’t run the resolver if there are any parse errors. If the code has a
    // syntax error, it’s never going to run, so there’s little value in resolving
    // it.
    Resolver resolver = new Resolver(Interpreter);
    resolver.resolve(statements);

    // Stop if there was a resolution error.
    if (hadError)
      return;

    for (Stmt statement : statements) {
      Interpreter.interpret(statement);
    }

    if (!interactive)
      return;

    try {
      Stmt last = statements.get(statements.size() - 1);
      if (last instanceof Stmt.Expression) {
        Expr expr = ((Stmt.Expression) last).expression;
        Object result = Interpreter.interpret(expr);
        System.out.println(Interpreter.stringify(result));
      }
    } catch (RuntimeError e) {
      runtimeError(e);
    }

    // System.out.println(new AstPrinter().print(expression));
  }

  static void error(int line, String message) {
    report(line, "", message);
  }

  static void error(Token token, String message) {
    if (token.type == TokenType.EOF) {
      report(token.line, " at end", message);
    } else {
      report(token.line, " at '" + token.lexeme + "'", message);
    }
  }

  private static void report(int line, String where, String message) {
    System.err.println("[line " + line + "] Error" + where + ": " + message);
    hadError = true;
  }

  static void runtimeError(RuntimeError error) {
    System.err.println("[line " + error.token.line + "] " + error.getMessage());
    hadRuntimeError = true;
  }
};
