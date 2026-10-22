package hr.avrbanac.snmptool.app.details;

import java.util.ArrayList;
import java.util.List;

/**
 * Formatting of MIB text for display.
 */
final class TextFormat {

    private TextFormat() {
    }

    /**
     * Removes the indentation that MIB authors put in multi-line quoted strings. The first line usually
     * follows the opening quote directly, so it is only stripped; the common indentation of the other lines is
     * removed. Trailing whitespace and leading/trailing blank lines are dropped.
     */
    static String unindent(String text) {
        String[] lines = text.replace("\t", "    ").split("\n", -1);
        int common = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                common = Math.min(common, indentation(lines[i]));
            }
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = i == 0 ? lines[i].strip() : lines[i].isBlank() ? "" : lines[i].substring(common);
            result.add(line.stripTrailing());
        }
        while (!result.isEmpty() && result.getFirst().isEmpty()) {
            result.removeFirst();
        }
        while (!result.isEmpty() && result.getLast().isEmpty()) {
            result.removeLast();
        }
        return String.join("\n", result);
    }

    private static int indentation(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /**
     * Formats an SMI date ({@code YYYYMMDDHHMMZ}, or {@code YYMMDDHHMMZ} meaning 19YY) as
     * {@code YYYY-MM-DD HH:MM UTC}. Other text is returned unchanged.
     */
    static String smiDate(String date) {
        String d = date.strip();
        if (!d.matches("\\d{10}(\\d{2})?[Zz]")) {
            return date;
        }
        String full = d.length() == 11 ? "19" + d : d;
        return full.substring(0, 4) + "-" + full.substring(4, 6) + "-" + full.substring(6, 8) + " "
                + full.substring(8, 10) + ":" + full.substring(10, 12) + " UTC";
    }
}
