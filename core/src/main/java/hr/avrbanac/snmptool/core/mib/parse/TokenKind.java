package hr.avrbanac.snmptool.core.mib.parse;

/**
 * Kinds of tokens produced by the {@link Lexer}.
 */
public enum TokenKind {
    /** Identifier starting with an upper-case letter: module, type or macro names, keywords. */
    UPPER_ID,
    /** Identifier starting with a lower-case letter: value names. */
    LOWER_ID,
    /** Number, possibly negative. */
    NUMBER,
    /** {@code '0A'H} */
    HEX_STRING,
    /** {@code '0101'B} */
    BINARY_STRING,
    /** {@code "text"}; the token text is the unquoted content. */
    QUOTED_STRING,
    /** {@code ::=} */
    ASSIGN,
    LBRACE,
    RBRACE,
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    COMMA,
    SEMICOLON,
    PIPE,
    /** {@code ..} */
    RANGE,
    DOT,
    /** Any other character; only meaningful inside skipped MACRO bodies. */
    OTHER,
    EOF
}
