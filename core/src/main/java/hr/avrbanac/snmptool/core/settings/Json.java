package hr.avrbanac.snmptool.core.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer for the settings file.
 *
 * <p>Objects are {@link Map}s (insertion ordered), arrays {@link List}s, numbers {@link Long} or {@link Double},
 * plus {@link String}, {@link Boolean} and {@code null}.
 */
final class Json {

    /** Malformed JSON, with the line and column where it was noticed. */
    static final class SyntaxException extends Exception {

        private static final long serialVersionUID = 1L;

        SyntaxException(String message, int line, int column) {
            super("line " + line + ", column " + column + ": " + message);
        }
    }

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) throws SyntaxException {
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.value();
        json.skipWhitespace();
        if (json.pos < text.length()) {
            throw json.error("unexpected text after the end");
        }
        return value;
    }

    /** Indented JSON for a value built from the types listed in the class description. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0);
        return out.append('\n').toString();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reading

    private Object value() throws SyntaxException {
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> object() throws SyntaxException {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a quoted key");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, value());
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                pos--;
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> array() throws SyntaxException {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value());
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                pos--;
                throw error("expected ',' or ']'");
            }
        }
    }

    private String string() throws SyntaxException {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (true) {
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                pos--;
                throw error("control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw error("unterminated string");
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("incomplete \\u escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("invalid \\u escape");
                    }
                    pos += 4;
                }
                default -> {
                    pos--;
                    throw error("invalid escape '\\" + e + "'");
                }
            }
        }
    }

    private Object number() throws SyntaxException {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        boolean integral = true;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                integral = false;
                pos++;
            } else {
                break;
            }
        }
        String number = text.substring(start, pos);
        try {
            return integral ? (Object) Long.parseLong(number) : (Object) Double.parseDouble(number);
        } catch (NumberFormatException e) {
            pos = start;
            throw error("invalid number '" + number + "'");
        }
    }

    private Object literal(String word, Object value) throws SyntaxException {
        if (!text.startsWith(word, pos)) {
            throw error("unexpected character '" + text.charAt(pos) + "'");
        }
        pos += word.length();
        return value;
    }

    private void expect(char c) throws SyntaxException {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private char peek() throws SyntaxException {
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private SyntaxException error(String message) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < Math.min(pos, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new SyntaxException(message, line, column);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Writing

    private static void write(Object value, StringBuilder out, int indent) {
        switch (value) {
            case null -> out.append("null");
            case String s -> quote(s, out);
            case Boolean b -> out.append(b);
            case Number n -> out.append(n);
            case Map<?, ?> map -> {
                if (map.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append('{');
                String separator = "\n";
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.append(separator);
                    indent(out, indent + 1);
                    quote(String.valueOf(e.getKey()), out);
                    out.append(": ");
                    write(e.getValue(), out, indent + 1);
                    separator = ",\n";
                }
                out.append('\n');
                indent(out, indent);
                out.append('}');
            }
            case List<?> list -> {
                if (list.isEmpty()) {
                    out.append("[]");
                    return;
                }
                out.append('[');
                String separator = "\n";
                for (Object item : list) {
                    out.append(separator);
                    indent(out, indent + 1);
                    write(item, out, indent + 1);
                    separator = ",\n";
                }
                out.append('\n');
                indent(out, indent);
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Not a JSON value: " + value.getClass().getName());
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void indent(StringBuilder out, int level) {
        out.append("  ".repeat(level));
    }
}
