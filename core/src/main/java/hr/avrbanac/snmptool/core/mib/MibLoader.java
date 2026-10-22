package hr.avrbanac.snmptool.core.mib;

import hr.avrbanac.snmptool.core.mib.model.MibModel;
import hr.avrbanac.snmptool.core.mib.parse.Diagnostics;
import hr.avrbanac.snmptool.core.mib.parse.Parser;
import hr.avrbanac.snmptool.core.mib.resolve.Resolver;
import hr.avrbanac.snmptool.core.mib.resolve.Resolver.ParsedModule;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses and resolves MIB sources into a {@link MibModel}.
 */
public final class MibLoader {

    private MibLoader() {
    }

    /**
     * Loads all modules from the given sources. Never fails on bad input: problems are reported in
     * {@link MibModel#diagnostics()}. When two sources define a module with the same name, the first one wins.
     */
    public static MibModel load(List<MibSource> sources) {
        Diagnostics diagnostics = new Diagnostics();
        List<ParsedModule> parsed = new ArrayList<>();
        for (MibSource source : sources) {
            Parser.parse(source.text(), source.name(), diagnostics)
                    .forEach(m -> parsed.add(new ParsedModule(m, source.name())));
        }
        return Resolver.resolve(parsed, diagnostics);
    }
}
