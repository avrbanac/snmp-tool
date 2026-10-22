package hr.avrbanac.snmptool.core.mib.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LexerTest {

    private final Diagnostics diagnostics = new Diagnostics();

    private List<Token> lex(String text) {
        return Lexer.tokenize(text, "test", diagnostics);
    }

    private List<String> texts(String text) {
        return lex(text).stream().filter(t -> !t.is(TokenKind.EOF)).map(Token::text).toList();
    }

    @Test
    void recognisesSymbolsAndIdentifiers() {
        List<Token> tokens = lex("ifEntry OBJECT-TYPE ::= { ifTable 1 } (0..255) | [ ] , ; .");
        assertEquals(List.of(TokenKind.LOWER_ID, TokenKind.UPPER_ID, TokenKind.ASSIGN, TokenKind.LBRACE,
                TokenKind.LOWER_ID, TokenKind.NUMBER, TokenKind.RBRACE, TokenKind.LPAREN, TokenKind.NUMBER,
                TokenKind.RANGE, TokenKind.NUMBER, TokenKind.RPAREN, TokenKind.PIPE, TokenKind.LBRACKET,
                TokenKind.RBRACKET, TokenKind.COMMA, TokenKind.SEMICOLON, TokenKind.DOT, TokenKind.EOF),
                tokens.stream().map(Token::kind).toList());
    }

    @Test
    void commentsEndAtDoubleHyphenOrEndOfLine() {
        assertEquals(List.of("a", "b", "c"), texts("a -- comment -- b -- to end of line\nc"));
        assertEquals(List.of("a", "b"), texts("a ------------\nb"));
    }

    @Test
    void identifierStopsBeforeComment() {
        assertEquals(List.of("mib-2", "x"), texts("mib-2-- comment\nx"));
    }

    @Test
    void negativeNumbers() {
        List<Token> tokens = lex("(-2147483648..2147483647)");
        assertEquals("-2147483648", tokens.get(1).text());
        assertEquals(TokenKind.NUMBER, tokens.get(1).kind());
    }

    @Test
    void quotedStringsSpanLinesAndEscapeQuotes() {
        List<Token> tokens = lex("\"first\nsecond \"\"quoted\"\" -- not a comment\" next");
        assertEquals(TokenKind.QUOTED_STRING, tokens.get(0).kind());
        assertEquals("first\nsecond \"quoted\" -- not a comment", tokens.get(0).text());
        assertEquals(2, tokens.get(1).position().line());
    }

    @Test
    void hexAndBinaryStrings() {
        List<Token> tokens = lex("'0aFF'H '0101'b");
        assertEquals(TokenKind.HEX_STRING, tokens.get(0).kind());
        assertEquals("0aFF", tokens.get(0).text());
        assertEquals(TokenKind.BINARY_STRING, tokens.get(1).kind());
        assertEquals("0101", tokens.get(1).text());
    }

    @Test
    void tracksPositions() {
        List<Token> tokens = lex("a\r\n  b");
        assertEquals(1, tokens.get(0).position().line());
        assertEquals(2, tokens.get(1).position().line());
        assertEquals(3, tokens.get(1).position().column());
    }

    @Test
    void reportsUnterminatedString() {
        lex("\"never closed");
        assertTrue(diagnostics.hasErrors());
    }
}
