package jlox;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import static jlox.TokenType.*;

class Parser {
  private static class ParseError extends RuntimeException {
  }

  private final List<Token> tokens;
  private int current = 0;
  private int loopCount = 0;

  Parser(List<Token> tokens) {
    this.tokens = tokens;
  }

  List<Stmt> parse() {
    List<Stmt> statements = new ArrayList<>();
    while (!isAtEnd()) {
      statements.add(declaration());
    }

    return statements;
  }

  // Each method for parsing a grammar rule produces a syntax tree for that rule
  // and returns it to the caller. When the body of the rule contains a
  // nonterminal—a reference to another rule—we call that other rule’s method.
  //
  // This is why left recursion is problematic for recursive descent. The function
  // for a left-recursive rule immediately calls itself, which calls itself again,
  // and so on, until the parser hits a stack overflow and dies.

  // This declaration() method is the method we call repeatedly when parsing a
  // series of statements in a block or a script, so it’s the right place to
  // synchronize when the parser goes into panic mode. The try-catch block gets it
  // back to trying to parse the beginning of the next statement or declaration.
  private Stmt declaration() {
    try {
      if (match(VAR))
        return varDeclaration();

      if (match(CLASS))
        return classDeclaration();

      if (match(FUN))
        return function("function");

      return statement();
    } catch (ParseError error) {
      synchronize();
      return null;
    }
  }

  private Stmt classDeclaration() {
    Token name = consume(IDENTIFIER, "Expect class name.");

    Expr.Variable superclass = null;
    if (match(LESS)) {
      consume(IDENTIFIER, "Expect superclass name.");
      superclass = new Expr.Variable(previous());
    }

    consume(LEFT_BRACE, "Expect '{' before class body.");

    List<Stmt.Function> methods = new ArrayList<>();
    while (!check(RIGHT_BRACE) && !isAtEnd()) {
      methods.add(function("method"));
    }

    consume(RIGHT_BRACE, "Expect '}' after class body.");

    return new Stmt.Class(name, superclass, methods);
  }

  // We reuse the function() method later to parse methods inside classes. When we
  // do that, we’ll pass in “method” for kind so that the error messages are
  // specific to the kind of declaration being parsed.
  private Stmt.Function function(String kind) {
    Token name = consume(IDENTIFIER, "Expect " + kind + " name.");

    // This is like the code for handling arguments in a call, except not split out
    // into a helper method. The outer if statement handles the zero parameter case,
    // and the inner while loop parses parameters as long as we find commas to
    // separate them. The result is the list of tokens for each parameter’s name.
    consume(LEFT_PAREN, "Expect '(' after " + kind + "name.");
    List<Token> parameters = new ArrayList<>();
    if (!check(RIGHT_PAREN)) {
      do {
        if (parameters.size() >= 255) {
          error(peek(), "Can't have more than 255 parameters.");
        }

        parameters.add(consume(IDENTIFIER, "Expect parameter name."));
      } while (match(COMMA));
    }
    consume(RIGHT_PAREN, "Expect ')' after parameters.");

    // Note that we consume the { at the beginning of the body here before calling
    // block(). That’s because block() assumes the brace token has already been
    // matched. Consuming it here lets us report a more precise error message if the
    // { isn’t found since we know it’s in the context of a function declaration.
    consume(LEFT_BRACE, "Expect '{' before " + kind + " body.");
    List<Stmt> body = block();
    return new Stmt.Function(name, parameters, body);
  }

  private Stmt varDeclaration() {
    Token name = consume(IDENTIFIER, "Expect variable name.");

    Expr initializer = null;
    if (match(EQUAL)) {
      initializer = expression();
    }

    consume(SEMICOLON, "Expect ';' after variable declaration.");
    return new Stmt.Var(name, initializer);

  }

  private Stmt statement() {
    if (match(FOR))
      return forStatement();
    if (match(IF))
      return ifStatement();
    if (match(PRINT)) {
      return printStatement();
    }
    if (match(RETURN))
      return returnStatement();
    if (match(WHILE))
      return whileStatement();
    if (match(LEFT_BRACE))
      return new Stmt.Block(block());
    if (match(BREAK))
      return breakStatement();

    return expressionStatement();
  }

  private Stmt breakStatement() {
    Token token = previous();
    consume(SEMICOLON, "Expect ';' after 'break'.");
    if (loopCount == 0) {
      error(token, "break statements can only exist inside a loop scope.");
    }
    return new Stmt.Break();
  }

  private Stmt forStatement() {
    consume(LEFT_PAREN, "Expect '(' after 'for'.");

    // The desugaring is going to happen here
    //
    // If the token following the ( is a semicolon then the initializer has been
    // omitted. Otherwise, we check for a var keyword to see if it’s a variable
    // declaration. If neither of those matched, it must be an expression. We parse
    // that and wrap it in an expression statement so that the initializer is always
    // of type Stmt.
    Stmt initializer;
    if (match(SEMICOLON)) {
      initializer = null;
    } else if (match(VAR)) {
      initializer = varDeclaration();
    } else {
      initializer = expressionStatement();
    }

    Expr condition = null;
    if (!check(SEMICOLON)) {
      condition = expression();
    }
    consume(SEMICOLON, "Expect ';' after loop condition.");

    Expr increment = null;
    if (!check(RIGHT_PAREN)) {
      increment = expression();
    }
    consume(RIGHT_PAREN, "Expect ')' after for clauses.");

    ++loopCount;
    Stmt body = statement();

    // We’ve parsed all of the various pieces of the for loop and the resulting AST
    // nodes are sitting in a handful of Java local variables. This is where the
    // desugaring comes in. We take those and use them to synthesize syntax tree
    // nodes that express the semantics of the for loop.

    if (increment != null) {
      body = new Stmt.Block(Arrays.asList(body, new Stmt.Expression(increment)));
    }

    // Next, we take the condition and the body and build the loop using a primitive
    // while loop. If the condition is omitted, we jam in true to make an infinite
    // loop.
    if (condition == null)
      condition = new Expr.Literal(true);
    body = new Stmt.While(condition, body);

    // Finally, if there is an initializer, it runs once before the entire loop. We
    // do that by, again, replacing the whole statement with a block that runs the
    // initializer and then executes the loop.
    if (initializer != null) {
      body = new Stmt.Block(Arrays.asList(initializer, body));
    }

    --loopCount;
    return body;
  }

  private Stmt whileStatement() {
    consume(LEFT_PAREN, "Expect '(' after 'while'.");
    Expr condition = expression();
    consume(RIGHT_PAREN, "Expect ')' after condition.");
    ++loopCount;
    Stmt body = statement();
    --loopCount;
    return new Stmt.While(condition, body);
  }

  // See Control\ Flow.md for details
  private Stmt ifStatement() {
    consume(LEFT_PAREN, "Expect'(' after 'if'.");
    Expr condition = expression();
    consume(RIGHT_PAREN, "Expect ')' after if condition.");

    Stmt thenBranch = statement();
    Stmt elseBranch = null;
    if (match(ELSE)) {
      elseBranch = statement();
    }

    return new Stmt.If(condition, thenBranch, elseBranch);
  }

  private Stmt printStatement() {
    Expr value = expression();
    consume(SEMICOLON, "Expect ';' after value.");
    return new Stmt.Print(value);
  }

  // Having block() return the raw list of statements and leaving it to
  // statement() to wrap the list in a Stmt.Block looks a little odd. I did it
  // that way because we’ll reuse block() later for parsing function bodies and we
  // don’t want that body wrapped in a Stmt.Block.
  private List<Stmt> block() {
    List<Stmt> statements = new ArrayList<>();

    while (!check(RIGHT_BRACE) && !isAtEnd()) {
      statements.add(declaration());
    }

    consume(RIGHT_BRACE, "Expect '}' after block.");
    return statements;
  }

  // Since many different tokens can potentially start an expression, it’s hard to
  // tell if a return value is present. Instead, we check if it’s absent.
  private Stmt returnStatement() {
    Token keyword = previous();
    Expr value = null;
    if (!check(SEMICOLON)) {
      value = expression();
    }

    consume(SEMICOLON, "Expect ';' after return value.");
    return new Stmt.Return(keyword, value);
  }

  private Stmt expressionStatement() {
    Expr expr = expression();
    consume(SEMICOLON, "Expect ';' after expression.");
    return new Stmt.Expression(expr);
  }

  // Remember, each rule needs to match expressions at that precedence level or
  // higher, so we also need to let this match a primary expression.
  private Expr expression() {
    return assignment();
  }

  // We want the syntax tree to reflect that an l-value isn’t evaluated like a
  // normal expression. That’s why the Expr.Assign node has a Token for the
  // left-hand side, not an Expr. The problem is that the parser doesn’t know it’s
  // parsing an l-value until it hits the =. In a complex l-value, that may occur
  // many tokens later. --> 'makeList().head.next = node;
  //
  // We have only a single token of lookahead, so what do we do? We use a little
  // trick, and it looks like this
  private Expr assignment() {
    Expr expr = or();

    // One slight difference from binary operators is that we don’t loop to build up
    // a sequence of the same operator. Since assignment is right-associative, we
    // instead recursively call assignment() to parse the right-hand side.
    //
    // The trick is that right before we create the assignment expression node, we
    // look at the left-hand side expression and figure out what kind of assignment
    // target it is. We convert the r-value expression node into an l-value
    // representation.
    if (match(EQUAL)) {
      Token equals = previous();
      Expr value = assignment();

      if (expr instanceof Expr.Variable) {
        Token name = ((Expr.Variable) expr).name;
        return new Expr.Assign(name, value);
      } else if (expr instanceof Expr.Get) {
        Expr.Get get = (Expr.Get) expr;
        return new Expr.Set(get.object, get.name, value);
      }

      error(equals, "Invalid assignment target.");
    }

    return expr;
  }

  private Expr or() {
    Expr expr = and();

    while (match(OR)) {
      Token operator = previous();
      Expr right = and();
      expr = new Expr.Logical(expr, operator, right);
    }

    return expr;
  }

  private Expr and() {
    Expr expr = equality();

    while (match(AND)) {
      Token operator = previous();
      Expr right = equality();
      expr = new Expr.Logical(expr, operator, right);
    }

    return expr;
  }

  private Expr equality() {
    Expr expr = comparison();

    // The ( ... )* loop in the rule maps to a while loop. We need to know when to
    // exit that loop. We can see that inside the rule, we must first find either a
    // != or == token. So, if we don’t see one of those, we must be done with the
    // sequence of equality operators
    while (match(BANG_EQUAL, EQUAL_EQUAL)) {
      Token operator = previous();
      Expr right = comparison();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  private Expr comparison() {
    Expr expr = term();

    while (match(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
      Token operator = previous();
      Expr right = term();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  // Addition and subtraction (+ -)
  private Expr term() {
    Expr expr = factor();

    while (match(MINUS, PLUS)) {
      Token operator = previous();
      Expr right = factor();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  // Multiplication and division (* /)
  private Expr factor() {
    Expr expr = unary();

    while (match(SLASH, STAR)) {
      Token operator = previous();
      Expr right = unary();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  private Expr unary() {
    if (match(BANG, MINUS)) {
      Token operator = previous();
      Expr right = unary();
      return new Expr.Unary(operator, right);
    }

    return call();
  }

  private Expr call() {
    Expr expr = primary();

    // This is roughly similar to how we parse infix operators. First, we parse a
    // primary expression, the “left operand” to the call. Then, each time we see a
    // (, we call finishCall() to parse the call expression using the previously
    // parsed expression as the callee. The returned expression becomes the new expr
    // and we loop to see if the result is itself called.
    while (true) {
      if (match(LEFT_PAREN)) {
        expr = finishCall(expr);
      } else if (match(DOT)) {
        Token name = consume(IDENTIFIER, "Expect property name after '.'.");
        expr = new Expr.Get(expr, name);
      } else {
        break;
      }
    }

    return expr;
  }

  // This is more or less the arguments grammar rule translated to code, except
  // that we also handle the zero-argument case. We check for that case first by
  // seeing if the next token is ). If it is, we don’t try to parse any arguments.
  private Expr finishCall(Expr callee) {
    List<Expr> arguments = new ArrayList<>();

    // Note that the code here reports an error if it encounters too many arguments,
    // but it doesn’t throw the error. Throwing is how we kick into panic mode which
    // is what we want if the parser is in a confused state and doesn’t know where
    // it is in the grammar anymore.
    if (!check(RIGHT_PAREN)) {
      if (arguments.size() >= 255) {
        error(peek(), "Can't have more than 255 arguments.");
      }
      do {
        arguments.add(expression());
      } while (match(COMMA));
    }

    Token paren = consume(RIGHT_PAREN, "Expect ')' after arguments.");

    return new Expr.Call(callee, paren, arguments);
  }

  private Expr primary() {
    if (match(FALSE))
      return new Expr.Literal(false);
    if (match(TRUE))
      return new Expr.Literal(true);
    if (match(NIL))
      return new Expr.Literal(null);

    if (match(NUMBER, STRING)) {
      return new Expr.Literal(previous().literal);
    }

    if (match(SUPER)) {
      Token keyword = previous();
      consume(DOT, "Expect '.' after 'super'.");
      Token method = consume(IDENTIFIER, "Expect superclass method name.");
      return new Expr.Super(keyword, method);
    }

    if (match(THIS))
      return new Expr.This(previous());

    if (match(IDENTIFIER)) {
      return new Expr.Variable(previous());
    }

    if (match(LEFT_PAREN)) {
      Expr expr = expression();
      consume(RIGHT_PAREN, "Expect ')' after expression.");
      return new Expr.Grouping(expr);
    }

    throw error(peek(), "Expected expression.");
  }

  // This checks to see if the current token has any of the given types. If so, it
  // consumes the token and returns true. Otherwise, it returns false and leaves
  // the current token alone.
  private boolean match(TokenType... types) {
    for (TokenType type : types) {
      if (check(type)) {
        advance();
        return true;
      }
    }

    return false;
  }

  private Token consume(TokenType type, String message) {
    if (check(type))
      return advance();

    throw error(peek(), message);
  }

  // The check() method returns true if the current token is of the given type.
  // Unlike match(), it never consumes the token, it only looks at it.
  private boolean check(TokenType type) {
    if (isAtEnd())
      return false;
    return peek().type == type;
  }

  // The advance() method consumes the current token and returns it
  private Token advance() {
    if (!isAtEnd())
      ++current;
    return previous();
  }

  // Checks if we’ve run out of tokens to parse
  private boolean isAtEnd() {
    return peek().type == EOF;
  }

  // Returns the current token we have yet to consume
  private Token peek() {
    return tokens.get(current);
  }

  // Returns the most recently consumed token
  private Token previous() {
    return tokens.get(current - 1);
  }

  private ParseError error(Token token, String message) {
    Lox.error(token, message);
    return new ParseError();
  }

  private void synchronize() {
    advance();

    while (!isAtEnd()) {
      if (previous().type == SEMICOLON)
        return;

      switch (peek().type) {
        case CLASS:
        case FUN:
        case VAR:
        case FOR:
        case IF:
        case WHILE:
        case PRINT:
        case RETURN:
          return;
        default:
      }

      advance();
    }
  }
}
