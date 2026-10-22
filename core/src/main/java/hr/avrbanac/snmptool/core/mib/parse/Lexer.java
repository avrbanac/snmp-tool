package hr.avrbanac.snmptool.core.mib.parse;

import hr.avrbanac.snmptool.core.mib.model.SourcePosition;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits MIB source text into tokens.
 *
 * <p>Follows the ASN.1 rules used by SMI: comments run from {@code --} to the next {@code --} or the end of
 * the line, strings may span lines and use {@code ""} for an embedded quote. Identifiers may contain
 * hyphens (but not {@code --}); underscores are accepted for compatibility with vendor MIBs.
 */
public final class Lexer {

    private final String text;
    private final String source;
    private final Diagnostics diagnostics;
    private final List<Token> tokens = new ArrayList<>();
    private int pos;
    private int line = 1;
    private int lineStart;
    private int tokenStart;

    private Lexer(String text, String source, Diagnostics diagnostics) {
        this.text = text;
        this.source = source;
        this.diagnostics = diagnostics;
    }

    /**
     * Tokenizes the text. The returned list always ends with an {@link TokenKind#EOF} token.
     */
    public static List<Token> tokenize(String text, String source, Diagnostics diagnostics) {
        Lexer lexer = new Lexer(text.replace("\r\n", "\n"), source, diagnostics);
        lexer.run();
        return lexer.tokens;
    }

    private void run() {
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= text.length()) {
                tokens.add(new Token(TokenKind.EOF, "", position(), pos));
                return;
            }
            SourcePosition start = position();
            tokenStart = pos;
            char c = text.charAt(pos);
            if (Character.isLetter(c)) {
                identifier(start);
            } else if (isDigit(c) || (c == '-' && isDigit(peek(1)))) {
                number(start);
            } else if (c == '"') {
                quotedString(start);
            } else if (c == '\'') {
                binaryOrHexString(start);
            } else if (text.startsWith("::=", pos)) {
                symbol(TokenKind.ASSIGN, 3, start);
            } else if (text.startsWith("..", pos)) {
                symbol(TokenKind.RANGE, 2, start);
            } else {
                TokenKind kind = switch (c) {
                    case '{' -> TokenKind.LBRACE;
                    case '}' -> TokenKind.RBRACE;
                    case '(' -> TokenKind.LPAREN;
                    case ')' -> TokenKind.RPAREN;
                    case '[' -> TokenKind.LBRACKET;
                    case ']' -> TokenKind.RBRACKET;
                    case ',' -> TokenKind.COMMA;
                    case ';' -> TokenKind.SEMICOLON;
                    case '|' -> TokenKind.PIPE;
                    case '.' -> TokenKind.DOT;
                    default -> TokenKind.OTHER;
                };
                symbol(kind, 1, start);
            }
        }
    }

    private void skipWhitespaceAndComments() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '\n') {
                newLine();
            } else if (Character.isWhitespace(c) || c == '\u001a') {
                pos++;
            } else if (c == '-' && peek(1) == '-') {
                skipComment();
            } else {
                return;
            }
        }
    }

    private void skipComment() {
        pos += 2;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '\n' || c == '\r') {
                return;
            }
            if (c == '-' && peek(1) == '-') {
                pos += 2;
                return;
            }
            pos++;
        }
    }

    private void identifier(SourcePosition start) {
        int begin = pos;
        pos++;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || (c == '-' && peek(1) != '-')) {
                pos++;
            } else {
                break;
            }
        }
        String word = text.substring(begin, pos);
        if (word.endsWith("-")) {
            diagnostics.warning(start, "Identifier '" + word + "' ends with a hyphen");
        }
        TokenKind kind = Character.isUpperCase(word.charAt(0)) ? TokenKind.UPPER_ID : TokenKind.LOWER_ID;
        tokens.add(new Token(kind, word, start, tokenStart));
    }

    private void number(SourcePosition start) {
        int begin = pos;
        pos++;
        while (pos < text.length() && isDigit(text.charAt(pos))) {
            pos++;
        }
        tokens.add(new Token(TokenKind.NUMBER, text.substring(begin, pos), start, tokenStart));
    }

    private void quotedString(SourcePosition start) {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '"') {
                if (peek(1) == '"') {
                    sb.append('"');
                    pos += 2;
                    continue;
                }
                pos++;
                tokens.add(new Token(TokenKind.QUOTED_STRING, sb.toString(), start, tokenStart));
                return;
            }
            if (c == '\n') {
                sb.append(c);
                newLine();
                continue;
            }
            sb.append(c);
            pos++;
        }
        diagnostics.error(start, "Unterminated quoted string");
        tokens.add(new Token(TokenKind.QUOTED_STRING, sb.toString(), start, tokenStart));
    }

    private void binaryOrHexString(SourcePosition start) {
        int end = text.indexOf('\'', pos + 1);
        if (end < 0 || end + 1 >= text.length()) {
            diagnostics.error(start, "Unterminated binary or hex string");
            symbol(TokenKind.OTHER, 1, start);
            return;
        }
        String content = text.substring(pos + 1, end).strip();
        char suffix = Character.toUpperCase(text.charAt(end + 1));
        if (suffix != 'H' && suffix != 'B') {
            diagnostics.error(start, "Expected 'H' or 'B' after quoted value");
            symbol(TokenKind.OTHER, 1, start);
            return;
        }
        pos = end + 2;
        tokens.add(new Token(suffix == 'H' ? TokenKind.HEX_STRING : TokenKind.BINARY_STRING, content, start, tokenStart));
    }

    private void symbol(TokenKind kind, int length, SourcePosition start) {
        tokens.add(new Token(kind, text.substring(pos, pos + length), start, tokenStart));
        pos += length;
    }

    private void newLine() {
        pos++;
        line++;
        lineStart = pos;
    }

    private char peek(int offset) {
        int i = pos + offset;
        return i < text.length() ? text.charAt(i) : '\0';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private SourcePosition position() {
        return new SourcePosition(source, line, pos - lineStart + 1);
    }
}
