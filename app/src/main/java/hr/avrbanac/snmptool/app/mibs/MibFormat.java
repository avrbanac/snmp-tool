package hr.avrbanac.snmptool.app.mibs;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Formatting of repository values for display.
 */
final class MibFormat {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private MibFormat() {
    }

    /** {@code YYYYMMDDHHMM} as {@code YYYY-MM-DD}; empty for no revision. */
    static String revision(Optional<String> revision) {
        return revision.filter(r -> r.length() >= 8)
                .map(r -> r.substring(0, 4) + "-" + r.substring(4, 6) + "-" + r.substring(6, 8))
                .orElse("");
    }

    static String instant(Optional<Instant> instant) {
        return instant.map(LOCAL::format).orElse("");
    }

    static String count(long n, String singular) {
        return n + " " + singular + (n == 1 ? "" : "s");
    }
}
