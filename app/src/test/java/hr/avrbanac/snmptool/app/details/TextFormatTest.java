package hr.avrbanac.snmptool.app.details;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextFormatTest {

    @Test
    void removesCommonIndentationAfterFirstLine() {
        String text = "An entry containing management information applicable to a\n"
                + "            particular interface.\n"
                + "\n"
                + "              Indented example.\n"
                + "            ";
        assertEquals("An entry containing management information applicable to a\n"
                + "particular interface.\n"
                + "\n"
                + "  Indented example.", TextFormat.unindent(text));
    }

    @Test
    void handlesTextStartingOnNextLine() {
        assertEquals("First.\nSecond.", TextFormat.unindent("\n        First.\n        Second.\n    "));
    }

    @Test
    void keepsSingleLineText() {
        assertEquals("Short.", TextFormat.unindent("  Short.  "));
    }

    @Test
    void formatsSmiDates() {
        assertEquals("2007-02-15 00:00 UTC", TextFormat.smiDate("200702150000Z"));
        assertEquals("1999-06-14 12:30 UTC", TextFormat.smiDate("9906141230Z"));
        assertEquals("not a date", TextFormat.smiDate("not a date"));
    }
}
