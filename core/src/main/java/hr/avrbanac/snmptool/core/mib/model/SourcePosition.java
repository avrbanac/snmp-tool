package hr.avrbanac.snmptool.core.mib.model;

/**
 * Location in a MIB source file. Line and column are 1-based.
 *
 * @param source name of the source, usually the file name
 * @param line   line number, starting at 1
 * @param column column number, starting at 1
 */
public record SourcePosition(String source, int line, int column) {

    @Override
    public String toString() {
        return source + ":" + line + ":" + column;
    }
}
