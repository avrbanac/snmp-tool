package hr.avrbanac.snmptool.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesAllValueTypes() throws Exception {
        Object value = Json.parse("""
                { "s": "a\\"b\\\\c\\n\\u00e9/", "i": -42, "d": 1.5e2, "t": true, "f": false, "n": null,
                  "a": [1, "x", []], "o": {} }
                """);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("s", "a\"b\\c\né/");
        expected.put("i", -42L);
        expected.put("d", 150.0);
        expected.put("t", true);
        expected.put("f", false);
        expected.put("n", null);
        expected.put("a", List.of(1L, "x", List.of()));
        expected.put("o", Map.of());
        assertEquals(expected, value);
    }

    @Test
    void writtenJsonReadsBack() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "quote \" backslash \\ tab \t bell \u0007 čćž");
        value.put("number", 7L);
        value.put("flag", true);
        value.put("nothing", null);
        value.put("list", Arrays.asList(Map.of("k", "v"), List.of(), 2L));
        assertEquals(value, Json.parse(Json.write(value)));
    }

    @Test
    void reportsLineAndColumnOfSyntaxErrors() {
        Json.SyntaxException e = assertThrows(Json.SyntaxException.class, () -> Json.parse("{\n  \"a\": 1,\n  \"b\" 2\n}"));
        assertTrue(e.getMessage().startsWith("line 3, column 7"), e.getMessage());
        for (String bad : List.of("", "{", "[1,]", "{\"a\":}", "\"open", "tru", "1 2", "{\"a\" 1}", "\"\\x\"", "-")) {
            assertThrows(Json.SyntaxException.class, () -> Json.parse(bad), bad);
        }
    }
}
