package hr.avrbanac.snmptool.core.mib.parse;

import hr.avrbanac.snmptool.core.mib.model.Diagnostic;
import hr.avrbanac.snmptool.core.mib.model.Diagnostic.Severity;
import hr.avrbanac.snmptool.core.mib.model.SourcePosition;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects diagnostics while parsing and resolving. Not thread-safe.
 */
public final class Diagnostics {

    private final List<Diagnostic> items = new ArrayList<>();

    public void error(SourcePosition position, String message) {
        items.add(new Diagnostic(Severity.ERROR, position, message));
    }

    public void warning(SourcePosition position, String message) {
        items.add(new Diagnostic(Severity.WARNING, position, message));
    }

    public List<Diagnostic> all() {
        return List.copyOf(items);
    }

    public boolean hasErrors() {
        return items.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }
}
