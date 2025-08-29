package jlox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static jlox.TokenType.*;

class Scanner {
  private final String source;
  private final List<Token> tokens = new ArrayList<>();
  private int start = 0; // points to the first character in the lexeme being scanned
  private int current = 0; // points at the character currently being considered
  private int line = 1;
  private static final Map<String, TokenType> keywords;

  static {
    keywords = new HashMap<>();
    keywords.put("and", AND);
    keywords.put("class", CLASS);
    keywords.put("else", ELSE);
    keywords.put("false", FALSE);
    keywords.put("for", FOR);
    keywords.put("fun", FUN);
    keywords.put("if", IF);
    keywords.put("nil", NIL);
    keywords.put("or", OR);
    keywords.put("print", PRINT);
    keywords.put("return", RETURN);
    keywords.put("super", SUPER);
    keywords.put("this", THIS);
    keywords.put("true", TRUE);
    keywords.put("var", VAR);
    keywords.put("while", WHILE);
    keywords.put("break", BREAK);
  }

  Scanner(String source) {
    this.source = source;
  }

  List<Token> scanTokens() {
    while (!isAtEnd()) {
      // We are at the beginning of the next lexeme.
      start = current;
      scanToken();
    }

    tokens.add(new Token(EOF, "", null, line));
    return tokens;
  }

  private void scanToken() {
    char c = advance();
    switch (c) {
      case '(':
        addToken(LEFT_PAREN);
        break;
      case ')':
        addToken(RIGHT_PAREN);
        break;
      case '{':
        addToken(LEFT_BRACE);
        break;
      case '}':
        addToken(RIGHT_BRACE);
        break;
      case ',':
        addToken(COMMA);
        break;
      case '.':
        addToken(DOT);
        break;
      case '-':
        addToken(MINUS);
        break;
      case '+':
        addToken(PLUS);
        break;
      case ';':
        addToken(SEMICOLON);
        break;
      case '*':
        addToken(STAR);
        break;
      case '?':
        addToken(QUESTION);
        break;
      case ':':
        addToken(COLON);
        break;
      case '!':
        addToken(match('=') ? BANG_EQUAL : BANG);
        break;
      case '=':
        addToken(match('=') ? EQUAL_EQUAL : EQUAL);
        break;
      case '<':
        addToken(match('=') ? LESS_EQUAL : LESS);
        break;
      case '>':
        addToken(match('=') ? GREATER_EQUAL : GREATER);
        break;
      case '/':
        if (match('/')) {
          lineComment();
        } else if (match('*')) {
          comment();
        } else {
          addToken(SLASH);
        }

        break;
      case ' ':
      case '\r':
      case '\t':
        // Ignore whitespace.
        break;
      case '\n':
        ++line;
        break;
      case '"':
        string();
        break;
      default:
        // It’s kind of tedious to add cases for every decimal digit, so we’ll stuff it
        // in the default case instead.
        if (isDigit(c)) {
          number();
        } else if (isAlpha(c)) {
          // We assume that any lexeme starting with a letter or underscore is an
          // identifier
          identifier();
        } else {
          // [NOTE]
          // The code reports each invalid character separately, so this shotguns
          // the user with a blast of errors if they accidentally paste a big blob of
          // weird text. Coalescing a run of invalid characters into a single error would
          // give a nicer user experience.
          Lox.error(line, "Unexpected character.");
        }
        break;
    }
  }

  private void lineComment() {
    // A comment goes until the end of the line.
    while (peek() != '\n' && !isAtEnd()) {
      // Comments are lexemes, but they aren’t meaningful, and the parser doesn’t want
      // to deal with them. So when we reach the end of the comment, we don’t call
      // addToken()
      advance();
    }
  }

  private void comment() {
    int nestLevel = 1;
    while (!isAtEnd()) {
      char ch = advance();
      switch (ch) {
        case '*':
          if (peek() == '/') {
            if (--nestLevel == 0) {
              advance();
              return;
            }
          }
          break;
        case '/':
          if (peek() == '*') {
            ++nestLevel;
            advance();
          }
          break;
      }
    }
    Lox.error(line, "Unterminated comment");
  }

  private void identifier() {
    while (isAlphaNumeric(peek()))
      advance();

    String text = source.substring(start, current);
    TokenType type = keywords.get(text);
    if (type == null)
      type = IDENTIFIER;
    addToken(type);
  }

  private void number() {
    while (isDigit(peek()))
      advance();

    // Look for a fractional part.
    if (peek() == '.' && isDigit(peekNext())) {
      // Consume the "."
      advance();

      while (isDigit(peek()))
        advance();
    }

    addToken(NUMBER, Double.parseDouble(source.substring(start, current)));
  }

  private void string() {
    while (peek() != '"' && !isAtEnd()) {
      if (peek() == '\n')
        line++;
      advance();
    }

    if (isAtEnd()) {
      Lox.error(line, "Unterminated string.");
      return;
    }

    // The closing ".
    advance();

    // Trim the surrounding quotes.
    String value = source.substring(start + 1, current - 1);

    // If Lox supported escape sequences like \n, we’d also unescape those here.

    addToken(STRING, value);
  }

  // It’s like a conditional advance(). We only consume the current character if
  // it’s what we’re looking for.
  private boolean match(char expected) {
    if (isAtEnd())
      return false;
    if (source.charAt(current) != expected)
      return false;

    ++current;
    return true;
  }

  private char peek() {
    if (isAtEnd())
      return '\0';
    return source.charAt(current);
  }

  private char peekNext() {
    if (current + 1 >= source.length())
      return '\0';
    return source.charAt(current + 1);
  }

  private boolean isAlpha(char c) {
    return (c >= 'a' && c <= 'z') ||
        (c >= 'A' && c <= 'Z') ||
        c == '_';
  }

  private boolean isAlphaNumeric(char c) {
    return isAlpha(c) || isDigit(c);
  }

  private boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private boolean isAtEnd() {
    return current >= source.length();
  }

  private char advance() {
    return source.charAt(current++);
  }

  private void addToken(TokenType type) {
    addToken(type, null);
  }

  private void addToken(TokenType type, Object literal) {
    String text = source.substring(start, current);
    tokens.add(new Token(type, text, literal, line));
  }
}
