package jlox;

//
// Our scanner uses a single Token class to represent all kinds of lexemes. 
// To distinguish the different kinds—think the number 123 versus the string "123"—we included a simple TokenType enum. 
// Syntax trees are not so homogeneous. Unary expressions have a single operand, binary expressions have two, and literals have none.
//
// Tokens aren’t entirely homogeneous either. 
// Tokens for literals store the value, but other kinds of lexemes don’t need that state.
// I have seen scanners that use different classes for literals and other kinds of lexemes, but I figured I’d keep things simpler.
// 
class Token {
  final TokenType type;
  final String lexeme;
  final Object literal;
  final int line;

  Token(TokenType type, String lexeme, Object literal, int line) {
    this.type = type;
    this.lexeme = lexeme;
    this.literal = literal;
    this.line = line;
  }

  public String toString() {
    return type + " " + lexeme + " " + literal;
  }
}
