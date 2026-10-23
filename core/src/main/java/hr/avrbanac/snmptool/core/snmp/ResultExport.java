package hr.avrbanac.snmptool.core.snmp;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Writes formatted results (e.g. of a walk) to a file, as CSV or as a text listing.
 */
public final class ResultExport {

    /** Export formats. */
    public enum Format {
        /** RFC 4180 CSV with a header row: Name, Value, Type, OID, Raw. */
        CSV("CSV", "csv"),
        /** One line per value, {@code name (oid) = type: value}, after {@code #} header lines. */
        TEXT("Text", "txt");

        private final String text;
        private final String extension;

        Format(String text, String extension) {
            this.text = text;
            this.extension = extension;
        }

        /** File name extension without the dot. */
        public String extension() {
            return extension;
        }

        /** The format for a file name's extension, if it has a known one. */
        public static Optional<Format> forFile(Path file) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            for (Format f : values()) {
                if (name.endsWith("." + f.extension)) {
                    return Optional.of(f);
                }
            }
            return Optional.empty();
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private static final List<String> CSV_HEADER = List.of("Name", "Value", "Type", "OID", "Raw");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private ResultExport() {
    }

    /**
     * Writes the rows to a file (UTF-8), replacing it.
     *
     * @param header lines describing the export (device, time, ...); written as {@code #} comments in text
     *               format and left out of CSV, which has only the column header row
     */
    public static void write(Path file, Format format, List<ResultFormatter.Formatted> rows, List<String> header)
            throws IOException {
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(out, format, rows, header);
        }
    }

    /** Writes the rows; see {@link #write(Path, Format, List, List)}. */
    public static void write(Writer out, Format format, List<ResultFormatter.Formatted> rows, List<String> header)
            throws IOException {
        switch (format) {
            case CSV -> writeCsv(out, rows);
            case TEXT -> writeText(out, rows, header);
        }
    }

    private static void writeCsv(Writer out, List<ResultFormatter.Formatted> rows) throws IOException {
        csvRow(out, CSV_HEADER);
        for (ResultFormatter.Formatted r : rows) {
            csvRow(out, List.of(r.name(), r.value(), r.type(), r.oid().toString(), r.raw()));
        }
    }

    private static void csvRow(Writer out, List<String> fields) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(csvField(fields.get(i)));
        }
        out.write("\r\n");
    }

    /**
     * Quotes a field if needed. Text starting with {@code = + - @}, tab or CR gets a leading {@code '} so that
     * spreadsheets do not evaluate agent-supplied values as formulas; plain numbers are left alone.
     */
    static String csvField(String field) {
        String s = field;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !NUMBER.matcher(s).matches()) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.startsWith(" ")
                || s.endsWith(" ")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static void writeText(Writer out, List<ResultFormatter.Formatted> rows, List<String> header)
            throws IOException {
        for (String line : header) {
            out.write("# " + oneLine(line) + "\n");
        }
        if (!header.isEmpty()) {
            out.write("\n");
        }
        for (ResultFormatter.Formatted r : rows) {
            String oid = r.oid().toString();
            String name = r.name().equals(oid) ? oid : r.name() + " (" + oid + ")";
            out.write(name + " = " + r.type() + ": " + oneLine(r.value()) + "\n");
        }
    }

    /** Line breaks inside a value are written as {@code \n}, so that each value stays on one line. */
    private static String oneLine(String s) {
        return s.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }
}
