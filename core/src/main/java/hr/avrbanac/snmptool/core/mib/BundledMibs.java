package hr.avrbanac.snmptool.core.mib;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Standard IETF and IANA MIB modules shipped with the tool.
 */
public final class BundledMibs {

    private static final String DIRECTORY = "ietf/";

    private BundledMibs() {
    }

    /** All bundled modules, one source per file. */
    public static List<MibSource> ietf() {
        return read(DIRECTORY + "index.list").lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(file -> new MibSource(file, read(DIRECTORY + file)))
                .toList();
    }

    private static String read(String resource) {
        try (InputStream in = BundledMibs.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled resource " + resource);
            }
            return MibSource.decode(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
