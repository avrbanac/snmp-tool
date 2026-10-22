package hr.avrbanac.snmptool.core.mib.parse;

import hr.avrbanac.snmptool.core.mib.model.SourcePosition;

/**
 * A lexical token.
 *
 * @param kind     token kind
 * @param text     token text; for strings the content without delimiters
 * @param position where the token starts
 */
public record Token(TokenKind kind, String text, SourcePosition position) {

    public boolean is(TokenKind k) {
        return kind == k;
    }

    /** Whether this is an identifier (of either case) with exactly the given text. */
    public boolean isWord(String word) {
        return (kind == TokenKind.UPPER_ID || kind == TokenKind.LOWER_ID) && text.equals(word);
    }

    public boolean isIdentifier() {
        return kind == TokenKind.UPPER_ID || kind == TokenKind.LOWER_ID;
    }

    @Override
    public String toString() {
        return switch (kind) {
            case EOF -> "end of file";
            case QUOTED_STRING -> "quoted string";
            default -> "'" + text + "'";
        };
    }
}
