package hr.avrbanac.snmptool.core.mib.parse;

import hr.avrbanac.snmptool.core.mib.model.SourcePosition;
import hr.avrbanac.snmptool.core.mib.parse.Ast.Bound;
import hr.avrbanac.snmptool.core.mib.parse.Ast.BuiltinKind;
import hr.avrbanac.snmptool.core.mib.parse.Ast.ConformanceKind;
import hr.avrbanac.snmptool.core.mib.parse.Ast.Definition;
import hr.avrbanac.snmptool.core.mib.parse.Ast.Element;
import hr.avrbanac.snmptool.core.mib.parse.Ast.IndexItem;
import hr.avrbanac.snmptool.core.mib.parse.Ast.NamedNumber;
import hr.avrbanac.snmptool.core.mib.parse.Ast.OidComponent;
import hr.avrbanac.snmptool.core.mib.parse.Ast.OidValue;
import hr.avrbanac.snmptool.core.mib.parse.Ast.Range;
import hr.avrbanac.snmptool.core.mib.parse.Ast.Revision;
import hr.avrbanac.snmptool.core.mib.parse.Ast.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hand-written recursive-descent parser for SMIv1 and SMIv2 MIB modules.
 *
 * <p>The parser is lenient: problems are reported to {@link Diagnostics} and parsing continues. When a
 * definition cannot be parsed, the parser skips ahead to the next token that looks like the start of a
 * definition and carries on from there.
 */
public final class Parser {

    private static final Map<String, Set<String>> MACRO_CLAUSES = Map.of(
            "MODULE-IDENTITY", Set.of("LAST-UPDATED", "ORGANIZATION", "CONTACT-INFO", "DESCRIPTION", "REVISION"),
            "OBJECT-IDENTITY", Set.of("STATUS", "DESCRIPTION", "REFERENCE"),
            "OBJECT-TYPE", Set.of("SYNTAX", "UNITS", "MAX-ACCESS", "ACCESS", "STATUS", "DESCRIPTION", "REFERENCE",
                    "INDEX", "AUGMENTS", "DEFVAL"),
            "NOTIFICATION-TYPE", Set.of("OBJECTS", "STATUS", "DESCRIPTION", "REFERENCE"),
            "TRAP-TYPE", Set.of("ENTERPRISE", "VARIABLES", "DESCRIPTION", "REFERENCE"),
            "OBJECT-GROUP", Set.of("OBJECTS", "STATUS", "DESCRIPTION", "REFERENCE"),
            "NOTIFICATION-GROUP", Set.of("NOTIFICATIONS", "STATUS", "DESCRIPTION", "REFERENCE"),
            "MODULE-COMPLIANCE", Set.of("STATUS", "DESCRIPTION", "REFERENCE", "MODULE"),
            "AGENT-CAPABILITIES", Set.of("PRODUCT-RELEASE", "STATUS", "DESCRIPTION", "REFERENCE", "SUPPORTS"),
            "TEXTUAL-CONVENTION", Set.of("DISPLAY-HINT", "STATUS", "DESCRIPTION", "REFERENCE", "SYNTAX"));

    private static final Set<String> CLAUSE_KEYWORDS = MACRO_CLAUSES.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());

    private final List<Token> tokens;
    private final Diagnostics diagnostics;
    private int index;

    private Parser(List<Token> tokens, Diagnostics diagnostics) {
        this.tokens = tokens;
        this.diagnostics = diagnostics;
    }

    /**
     * Parses all modules in the given source text.
     *
     * @param text        MIB source text
     * @param source      source name used in positions, usually the file name
     * @param diagnostics receives problems found while lexing and parsing
     * @return the modules found; empty if none could be parsed
     */
    public static List<Ast.Module> parse(String text, String source, Diagnostics diagnostics) {
        List<Token> tokens = Lexer.tokenize(text, source, diagnostics);
        return new Parser(tokens, diagnostics).parseFile();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Modules

    private List<Ast.Module> parseFile() {
        List<Ast.Module> modules = new ArrayList<>();
        while (!current().is(TokenKind.EOF)) {
            int start = findModuleStart();
            if (start < 0) {
                if (modules.isEmpty()) {
                    diagnostics.error(current().position(), "No module definition found");
                }
                break;
            }
            if (start > index && !modules.isEmpty()) {
                diagnostics.warning(current().position(), "Ignoring text between modules");
            }
            index = start;
            try {
                modules.add(parseModule());
            } catch (ParseException e) {
                // Diagnostic already recorded; continue with the next module, if any.
                advance();
            }
        }
        return modules;
    }

    /** Finds the index of the next module name (the identifier before DEFINITIONS), or -1. */
    private int findModuleStart() {
        for (int i = index; i < tokens.size(); i++) {
            if (!tokens.get(i).isWord("DEFINITIONS") || i == 0) {
                continue;
            }
            int nameIndex = i - 1;
            if (tokens.get(nameIndex).is(TokenKind.RBRACE)) {
                int depth = 0;
                while (nameIndex > index) {
                    TokenKind kind = tokens.get(nameIndex).kind();
                    if (kind == TokenKind.RBRACE) {
                        depth++;
                    } else if (kind == TokenKind.LBRACE && --depth == 0) {
                        break;
                    }
                    nameIndex--;
                }
                nameIndex--;
            }
            if (nameIndex >= index && tokens.get(nameIndex).isIdentifier()) {
                return nameIndex;
            }
        }
        return -1;
    }

    private Ast.Module parseModule() {
        Token name = expectIdentifier("module name");
        if (name.is(TokenKind.LOWER_ID)) {
            diagnostics.warning(name.position(), "Module name '" + name.text() + "' should start with an upper-case letter");
        }
        if (current().is(TokenKind.LBRACE)) {
            skipBalanced();
        }
        expectWord("DEFINITIONS");
        while (current().isIdentifier() && !current().isWord("BEGIN")) {
            advance(); // tag default, e.g. IMPLICIT TAGS
        }
        expect(TokenKind.ASSIGN, "'::='");
        expectWord("BEGIN");

        List<Ast.Import> imports = new ArrayList<>();
        List<Definition> definitions = new ArrayList<>();
        while (!current().is(TokenKind.EOF) && !current().isWord("END")) {
            if (current().isIdentifier() && peek(1).isWord("DEFINITIONS")) {
                break;
            }
            int definitionStart = index;
            try {
                if (current().isWord("EXPORTS")) {
                    skipExports();
                } else if (current().isWord("IMPORTS")) {
                    parseImports(imports);
                } else {
                    definitions.add(parseDefinition());
                }
            } catch (ParseException e) {
                recover(definitionStart);
            }
        }
        int endOffset;
        if (current().isWord("END")) {
            Token end = advance();
            endOffset = end.offset() + end.text().length();
        } else {
            diagnostics.warning(current().position(), "Missing END of module " + name.text());
            endOffset = current().offset();
        }
        return new Ast.Module(name.text(), name.position(), imports, definitions, name.offset(), endOffset);
    }

    private void skipExports() {
        advance();
        while (!current().is(TokenKind.SEMICOLON) && !current().is(TokenKind.EOF) && !current().isWord("END")) {
            advance();
        }
        if (current().is(TokenKind.SEMICOLON)) {
            advance();
        }
    }

    private void parseImports(List<Ast.Import> imports) {
        advance();
        while (true) {
            if (current().is(TokenKind.SEMICOLON)) {
                advance();
                return;
            }
            if (current().is(TokenKind.EOF) || current().isWord("END")) {
                diagnostics.warning(current().position(), "Missing ';' at end of IMPORTS");
                return;
            }
            SourcePosition position = current().position();
            List<String> symbols = new ArrayList<>();
            while (true) {
                Token symbol = expectIdentifier("imported symbol");
                symbols.add(symbol.text());
                if (current().is(TokenKind.COMMA)) {
                    advance();
                    if (current().isWord("FROM")) {
                        diagnostics.warning(current().position(), "Extra ',' before FROM");
                        break;
                    }
                } else if (current().isWord("FROM")) {
                    break;
                } else {
                    throw fail(current(), "Expected ',' or FROM in IMPORTS, found " + current());
                }
            }
            advance(); // FROM
            Token module = expectIdentifier("module name");
            if (current().is(TokenKind.LBRACE)) {
                skipBalanced();
            }
            imports.add(new Ast.Import(module.text(), symbols, position));
            if (current().isIdentifier() && !peek(1).is(TokenKind.COMMA) && !peek(1).isWord("FROM")) {
                diagnostics.warning(current().position(), "Missing ';' at end of IMPORTS");
                return;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Definitions

    private Definition parseDefinition() {
        Token name = current();
        if (!name.isIdentifier()) {
            throw fail(name, "Expected a definition, found " + name);
        }
        Token next = peek(1);
        if (next.isWord("MACRO")) {
            return parseMacro(name);
        }
        if (next.is(TokenKind.ASSIGN)) {
            advance();
            advance();
            if (isWordIgnoreCase(current(), "TEXTUAL-CONVENTION")) {
                checkKeywordCase(current(), "TEXTUAL-CONVENTION");
                checkTypeName(name);
                advance();
                return parseTextualConvention(name);
            }
            checkTypeName(name);
            return new Ast.TypeAssignment(name.text(), name.position(), parseType());
        }
        if (next.isWord("OBJECT") && peek(2).isWord("IDENTIFIER")) {
            checkValueName(name);
            advance();
            advance();
            advance();
            expect(TokenKind.ASSIGN, "'::='");
            return new Ast.ValueAssignment(name.text(), name.position(), parseOidValue());
        }
        Optional<String> macro = macroKeyword(next);
        if (macro.isPresent()) {
            checkKeywordCase(next, macro.get());
            checkValueName(name);
            advance();
            advance();
            return parseMacroInvocation(name, macro.get());
        }
        throw fail(next, "Unexpected " + next + " after '" + name.text() + "'");
    }

    private Definition parseMacro(Token name) {
        advance();
        advance();
        expect(TokenKind.ASSIGN, "'::='");
        expectWord("BEGIN");
        while (!current().isWord("END")) {
            if (current().is(TokenKind.EOF)) {
                throw fail(current(), "Missing END of MACRO " + name.text());
            }
            advance();
        }
        advance();
        return new Ast.Macro(name.text(), name.position());
    }

    private Definition parseMacroInvocation(Token name, String macro) {
        Clauses c = parseClauses(macro, true);
        expect(TokenKind.ASSIGN, "'::='");
        String n = name.text();
        SourcePosition p = name.position();
        return switch (macro) {
            case "MODULE-IDENTITY" -> new Ast.ModuleIdentity(n, p, c.lastUpdated, c.organization, c.contactInfo,
                    c.description, c.revisions, parseOidValue());
            case "OBJECT-IDENTITY" -> new Ast.ObjectIdentity(n, p, c.status, c.description, c.reference,
                    parseOidValue());
            case "OBJECT-TYPE" -> {
                if (c.syntax.isEmpty()) {
                    diagnostics.error(p, "OBJECT-TYPE " + n + " has no SYNTAX clause");
                }
                yield new Ast.ObjectType(n, p, c.syntax, c.units, c.access, c.status, c.description, c.reference,
                        c.index, c.augments, c.defval, parseOidValue());
            }
            case "NOTIFICATION-TYPE" -> new Ast.NotificationType(n, p, c.members, c.status, c.description,
                    c.reference, parseOidValue());
            case "TRAP-TYPE" -> new Ast.TrapType(n, p, c.enterprise, c.members, c.description, c.reference,
                    parseTrapNumber());
            case "OBJECT-GROUP" -> conformance(name, ConformanceKind.OBJECT_GROUP, c);
            case "NOTIFICATION-GROUP" -> conformance(name, ConformanceKind.NOTIFICATION_GROUP, c);
            case "MODULE-COMPLIANCE" -> conformance(name, ConformanceKind.MODULE_COMPLIANCE, c);
            case "AGENT-CAPABILITIES" -> conformance(name, ConformanceKind.AGENT_CAPABILITIES, c);
            default -> throw new IllegalStateException("Unhandled macro " + macro);
        };
    }

    private Definition conformance(Token name, ConformanceKind kind, Clauses c) {
        return new Ast.Conformance(name.text(), name.position(), kind, c.members, c.status, c.description,
                c.reference, parseOidValue());
    }

    private long parseTrapNumber() {
        Token number = expect(TokenKind.NUMBER, "trap number");
        try {
            return Long.parseLong(number.text());
        } catch (NumberFormatException e) {
            throw fail(number, "Invalid trap number " + number.text());
        }
    }

    private Definition parseTextualConvention(Token name) {
        Clauses c = parseClauses("TEXTUAL-CONVENTION", false);
        if (c.syntax.isEmpty()) {
            diagnostics.error(name.position(), "TEXTUAL-CONVENTION " + name.text() + " has no SYNTAX clause");
        }
        return new Ast.TextualConvention(name.text(), name.position(), c.displayHint, c.status, c.description,
                c.reference, c.syntax);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Macro clauses

    /** Clause values collected from a macro invocation. */
    private static final class Clauses {
        Optional<Type> syntax = Optional.empty();
        Optional<String> units = Optional.empty();
        Optional<String> access = Optional.empty();
        Optional<String> status = Optional.empty();
        Optional<String> description = Optional.empty();
        Optional<String> reference = Optional.empty();
        Optional<String> displayHint = Optional.empty();
        Optional<String> lastUpdated = Optional.empty();
        Optional<String> organization = Optional.empty();
        Optional<String> contactInfo = Optional.empty();
        Optional<String> augments = Optional.empty();
        Optional<String> defval = Optional.empty();
        Optional<String> enterprise = Optional.empty();
        List<IndexItem> index = List.of();
        List<String> members = List.of();
        List<Revision> revisions = new ArrayList<>();
    }

    /**
     * Parses the clauses of a macro invocation.
     *
     * @param untilAssign whether the clauses end at {@code ::=} (all macros except TEXTUAL-CONVENTION)
     */
    private Clauses parseClauses(String macro, boolean untilAssign) {
        Set<String> allowed = MACRO_CLAUSES.get(macro);
        Clauses c = new Clauses();
        while (true) {
            Token t = current();
            if (t.is(TokenKind.ASSIGN) || t.is(TokenKind.EOF) || t.isWord("END")) {
                return c;
            }
            if (!t.is(TokenKind.UPPER_ID) || !CLAUSE_KEYWORDS.contains(t.text())) {
                if (!untilAssign) {
                    return c;
                }
                if (atDefinitionStart()) {
                    throw fail(t, "Missing '::=' in " + macro);
                }
                diagnostics.warning(t.position(), "Unexpected " + t + " in " + macro + ", skipped");
                skipToClause();
                continue;
            }
            if (!allowed.contains(t.text())) {
                diagnostics.warning(t.position(), "Clause " + t.text() + " is not allowed in " + macro);
            }
            advance();
            switch (t.text()) {
                case "SYNTAX" -> c.syntax = Optional.of(parseType());
                case "UNITS" -> c.units = Optional.of(parseText(t));
                case "MAX-ACCESS", "ACCESS" -> c.access = Optional.of(expectIdentifier("access").text());
                case "STATUS" -> c.status = Optional.of(expectIdentifier("status").text());
                case "DESCRIPTION" -> c.description = Optional.of(parseText(t));
                case "REFERENCE" -> c.reference = Optional.of(parseText(t));
                case "DISPLAY-HINT" -> c.displayHint = Optional.of(parseText(t));
                case "LAST-UPDATED" -> c.lastUpdated = Optional.of(parseText(t));
                case "ORGANIZATION" -> c.organization = Optional.of(parseText(t));
                case "CONTACT-INFO" -> c.contactInfo = Optional.of(parseText(t));
                case "PRODUCT-RELEASE" -> parseText(t);
                case "INDEX" -> c.index = parseIndex();
                case "AUGMENTS" -> c.augments = parseNameList().stream().findFirst();
                case "DEFVAL" -> c.defval = Optional.of(parseRawBraces());
                case "OBJECTS", "NOTIFICATIONS", "VARIABLES" -> c.members = parseNameList();
                case "ENTERPRISE" -> c.enterprise = Optional.of(parseEnterprise());
                case "REVISION" -> c.revisions.add(parseRevision(t));
                case "MODULE", "SUPPORTS" -> skipToAssign();
                default -> throw new IllegalStateException("Unhandled clause " + t.text());
            }
        }
    }

    private Revision parseRevision(Token keyword) {
        String date = parseText(keyword);
        Optional<String> description = Optional.empty();
        if (current().isWord("DESCRIPTION")) {
            Token d = advance();
            description = Optional.of(parseText(d));
        } else {
            diagnostics.warning(keyword.position(), "REVISION without DESCRIPTION");
        }
        return new Revision(date, description);
    }

    private String parseEnterprise() {
        if (current().is(TokenKind.LBRACE)) {
            List<String> names = parseNameList();
            if (names.isEmpty()) {
                throw fail(current(), "Empty ENTERPRISE");
            }
            return names.getFirst();
        }
        return expectIdentifier("enterprise").text();
    }

    /** Text value of a clause; accepts an unquoted word with a warning. */
    private String parseText(Token clause) {
        Token t = current();
        if (t.is(TokenKind.QUOTED_STRING)) {
            advance();
            return t.text();
        }
        if (t.isIdentifier() || t.is(TokenKind.NUMBER)) {
            diagnostics.warning(t.position(), clause.text() + " value should be a quoted string");
            advance();
            return t.text();
        }
        throw fail(t, "Expected quoted string after " + clause.text() + ", found " + t);
    }

    private List<IndexItem> parseIndex() {
        expect(TokenKind.LBRACE, "'{'");
        List<IndexItem> items = new ArrayList<>();
        while (!current().is(TokenKind.RBRACE)) {
            boolean implied = false;
            if (current().isWord("IMPLIED")) {
                implied = true;
                advance();
            }
            StringBuilder name = new StringBuilder(expectIdentifier("index object").text());
            while (current().isIdentifier()) {
                name.append(' ').append(advance().text()); // SMIv1 type index, e.g. OCTET STRING
            }
            items.add(new IndexItem(name.toString(), implied));
            if (!separator()) {
                break;
            }
        }
        expect(TokenKind.RBRACE, "'}'");
        return items;
    }

    /** {@code { name, name, ... }}. */
    private List<String> parseNameList() {
        expect(TokenKind.LBRACE, "'{'");
        List<String> names = new ArrayList<>();
        while (!current().is(TokenKind.RBRACE)) {
            names.add(expectIdentifier("name").text());
            if (!separator()) {
                break;
            }
        }
        expect(TokenKind.RBRACE, "'}'");
        return names;
    }

    /**
     * Consumes a ',' between list items. A missing comma before another identifier is tolerated with a
     * warning.
     *
     * @return whether another item follows
     */
    private boolean separator() {
        if (current().is(TokenKind.COMMA)) {
            advance();
            if (current().is(TokenKind.RBRACE)) {
                diagnostics.warning(current().position(), "Extra ',' before '}'");
                return false;
            }
            return true;
        }
        if (current().isIdentifier()) {
            diagnostics.warning(current().position(), "Missing ',' before " + current());
            return true;
        }
        return false;
    }

    /** Source text of a braced value such as DEFVAL, without the outer braces. */
    private String parseRawBraces() {
        expect(TokenKind.LBRACE, "'{'");
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (true) {
            Token t = current();
            if (t.is(TokenKind.EOF)) {
                throw fail(t, "Missing '}'");
            }
            if (t.is(TokenKind.LBRACE)) {
                depth++;
            } else if (t.is(TokenKind.RBRACE) && --depth == 0) {
                advance();
                return sb.toString();
            }
            if (!sb.isEmpty() && !t.is(TokenKind.COMMA) && !t.is(TokenKind.RPAREN) && !sb.toString().endsWith("(")) {
                sb.append(' ');
            }
            sb.append(sourceText(t));
            advance();
        }
    }

    private static String sourceText(Token t) {
        return switch (t.kind()) {
            case QUOTED_STRING -> "\"" + t.text().replace("\"", "\"\"") + "\"";
            case HEX_STRING -> "'" + t.text() + "'H";
            case BINARY_STRING -> "'" + t.text() + "'B";
            default -> t.text();
        };
    }

    // ---------------------------------------------------------------------------------------------------------
    // Object identifier values

    private OidValue parseOidValue() {
        Token open = expect(TokenKind.LBRACE, "'{'");
        List<OidComponent> components = new ArrayList<>();
        while (!current().is(TokenKind.RBRACE)) {
            Token t = current();
            if (t.is(TokenKind.NUMBER)) {
                components.add(new OidComponent(Optional.empty(), Optional.of(parseArc(t))));
                advance();
            } else if (t.isIdentifier()) {
                advance();
                if (current().is(TokenKind.LPAREN)) {
                    advance();
                    Token number = expect(TokenKind.NUMBER, "number");
                    expect(TokenKind.RPAREN, "')'");
                    components.add(new OidComponent(Optional.of(t.text()), Optional.of(parseArc(number))));
                } else {
                    components.add(new OidComponent(Optional.of(t.text()), Optional.empty()));
                }
            } else {
                throw fail(t, "Unexpected " + t + " in object identifier value");
            }
            if (current().is(TokenKind.COMMA)) {
                diagnostics.warning(current().position(), "Object identifier components are not separated by ','");
                advance();
            }
        }
        advance();
        if (components.isEmpty()) {
            diagnostics.error(open.position(), "Empty object identifier value");
        }
        return new OidValue(components, open.position());
    }

    private long parseArc(Token number) {
        try {
            long arc = Long.parseLong(number.text());
            if (arc >= 0 && arc <= 0xFFFF_FFFFL) {
                return arc;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw fail(number, "Invalid object identifier component " + number.text());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Types

    private Type parseType() {
        Token t = current();
        SourcePosition p = t.position();
        Type type;
        if (t.is(TokenKind.LBRACKET)) {
            return parseTagged();
        } else if (t.isWord("INTEGER")) {
            advance();
            type = new Ast.Builtin(BuiltinKind.INTEGER, p);
        } else if (t.isWord("OCTET")) {
            advance();
            expectWord("STRING");
            type = new Ast.Builtin(BuiltinKind.OCTET_STRING, p);
        } else if (t.isWord("OBJECT")) {
            advance();
            expectWord("IDENTIFIER");
            type = new Ast.Builtin(BuiltinKind.OBJECT_IDENTIFIER, p);
        } else if (t.isWord("BITS")) {
            advance();
            type = new Ast.Builtin(BuiltinKind.BITS, p);
        } else if (t.isWord("NULL")) {
            advance();
            type = new Ast.Builtin(BuiltinKind.NULL, p);
        } else if (t.isWord("SEQUENCE")) {
            advance();
            if (current().isWord("OF")) {
                advance();
                return new Ast.SequenceOf(parseType(), p);
            }
            return new Ast.Sequence(parseElements(), p);
        } else if (t.isWord("CHOICE")) {
            advance();
            return new Ast.Choice(parseElements(), p);
        } else if (t.isIdentifier()) {
            advance(); // a lower-case type reference is reported by the resolver, which knows what it meant
            if (current().is(TokenKind.DOT) && peek(1).isIdentifier()) {
                advance();
                Token name = advance();
                type = new Ast.TypeRef(Optional.of(t.text()), name.text(), p);
            } else {
                type = new Ast.TypeRef(Optional.empty(), t.text(), p);
            }
        } else {
            throw fail(t, "Expected a type, found " + t);
        }
        if (current().is(TokenKind.LBRACE)) {
            type = new Ast.Enumerated(type, parseNamedNumbers(), p);
        }
        if (current().is(TokenKind.LPAREN)) {
            type = parseConstraint(type);
        }
        return type;
    }

    private Type parseTagged() {
        Token open = advance();
        String tagClass = "CONTEXT";
        if (current().isWord("APPLICATION") || current().isWord("UNIVERSAL") || current().isWord("PRIVATE")) {
            tagClass = advance().text();
        }
        long number = parseArc(expect(TokenKind.NUMBER, "tag number"));
        expect(TokenKind.RBRACKET, "']'");
        boolean implicit = false;
        if (current().isWord("IMPLICIT")) {
            implicit = true;
            advance();
        } else if (current().isWord("EXPLICIT")) {
            advance();
        }
        return new Ast.Tagged(tagClass, number, implicit, parseType(), open.position());
    }

    private List<Element> parseElements() {
        expect(TokenKind.LBRACE, "'{'");
        List<Element> elements = new ArrayList<>();
        while (!current().is(TokenKind.RBRACE)) {
            Token name = expectIdentifier("element name");
            elements.add(new Element(name.text(), parseType()));
            if (!separator()) {
                break;
            }
        }
        expect(TokenKind.RBRACE, "'}'");
        return elements;
    }

    private List<NamedNumber> parseNamedNumbers() {
        expect(TokenKind.LBRACE, "'{'");
        List<NamedNumber> values = new ArrayList<>();
        while (!current().is(TokenKind.RBRACE)) {
            Token name = expectIdentifier("enumeration label");
            expect(TokenKind.LPAREN, "'('");
            Token number = expect(TokenKind.NUMBER, "number");
            expect(TokenKind.RPAREN, "')'");
            values.add(new NamedNumber(name.text(), new BigInteger(number.text())));
            if (!separator()) {
                break;
            }
        }
        expect(TokenKind.RBRACE, "'}'");
        return values;
    }

    private Type parseConstraint(Type base) {
        Token open = advance();
        boolean size = false;
        List<Range> ranges;
        if (current().isWord("SIZE")) {
            advance();
            expect(TokenKind.LPAREN, "'('");
            ranges = parseRanges();
            expect(TokenKind.RPAREN, "')'");
            size = true;
        } else {
            ranges = parseRanges();
        }
        expect(TokenKind.RPAREN, "')'");
        return new Ast.Constrained(base, size, ranges, open.position());
    }

    private List<Range> parseRanges() {
        List<Range> ranges = new ArrayList<>();
        do {
            if (!ranges.isEmpty()) {
                advance(); // '|'
            }
            Bound lower = parseBound();
            Bound upper = lower;
            if (current().is(TokenKind.RANGE)) {
                advance();
                upper = parseBound();
            }
            ranges.add(new Range(lower, upper));
        } while (current().is(TokenKind.PIPE));
        return ranges;
    }

    private Bound parseBound() {
        Token t = advance();
        try {
            return switch (t.kind()) {
                case NUMBER -> new Ast.Value(new BigInteger(t.text()));
                case HEX_STRING -> new Ast.Value(t.text().isEmpty() ? BigInteger.ZERO : new BigInteger(t.text(), 16));
                case BINARY_STRING -> new Ast.Value(t.text().isEmpty() ? BigInteger.ZERO : new BigInteger(t.text(), 2));
                case UPPER_ID -> switch (t.text()) {
                    case "MIN" -> new Ast.Min();
                    case "MAX" -> new Ast.Max();
                    default -> throw fail(t, "Expected a number, MIN or MAX, found " + t);
                };
                default -> throw fail(t, "Expected a number, MIN or MAX, found " + t);
            };
        } catch (NumberFormatException e) {
            throw fail(t, "Invalid number " + t);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Recovery and skipping

    /** Skips ahead to the next plausible definition start, making progress past {@code from}. */
    private void recover(int from) {
        if (index == from) {
            advance();
        }
        while (!current().is(TokenKind.EOF) && !current().isWord("END") && !atDefinitionStart()) {
            advance();
        }
    }

    private boolean atDefinitionStart() {
        Token t = current();
        if (!t.isIdentifier() || CLAUSE_KEYWORDS.contains(t.text())) {
            return false;
        }
        Token next = peek(1);
        return next.is(TokenKind.ASSIGN)
                || next.isWord("MACRO")
                || macroKeyword(next).isPresent()
                || (next.isWord("OBJECT") && peek(2).isWord("IDENTIFIER"));
    }

    private void skipToClause() {
        while (true) {
            Token t = current();
            if (t.is(TokenKind.EOF) || t.is(TokenKind.ASSIGN)
                    || (t.is(TokenKind.UPPER_ID) && CLAUSE_KEYWORDS.contains(t.text())) || atDefinitionStart()) {
                return;
            }
            if (t.is(TokenKind.LBRACE)) {
                skipBalanced();
            } else {
                advance();
            }
        }
    }

    private void skipToAssign() {
        while (!current().is(TokenKind.ASSIGN)) {
            if (current().is(TokenKind.EOF) || atDefinitionStart()) {
                throw fail(current(), "Missing '::='");
            }
            advance();
        }
    }

    private void skipBalanced() {
        Token open = advance();
        int depth = 1;
        while (depth > 0) {
            Token t = advance();
            if (t.is(TokenKind.EOF)) {
                throw fail(open, "Missing '}'");
            }
            if (t.is(TokenKind.LBRACE)) {
                depth++;
            } else if (t.is(TokenKind.RBRACE)) {
                depth--;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Name checks

    private static Optional<String> macroKeyword(Token t) {
        if (!t.isIdentifier()) {
            return Optional.empty();
        }
        return MACRO_CLAUSES.keySet().stream()
                .filter(k -> !k.equals("TEXTUAL-CONVENTION") && k.equalsIgnoreCase(t.text()))
                .findFirst();
    }

    private static boolean isWordIgnoreCase(Token t, String word) {
        return t.isIdentifier() && t.text().equalsIgnoreCase(word);
    }

    private void checkKeywordCase(Token t, String keyword) {
        if (!t.text().equals(keyword)) {
            diagnostics.warning(t.position(), "Keyword '" + t.text() + "' should be written '" + keyword + "'");
        }
    }

    private void checkValueName(Token name) {
        if (name.is(TokenKind.UPPER_ID)) {
            diagnostics.warning(name.position(), "Name '" + name.text() + "' should start with a lower-case letter");
        }
    }

    private void checkTypeName(Token name) {
        if (name.is(TokenKind.LOWER_ID)) {
            diagnostics.warning(name.position(), "Type name '" + name.text() + "' should start with an upper-case letter");
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Token access

    private Token current() {
        return tokens.get(index);
    }

    private Token peek(int offset) {
        return tokens.get(Math.min(index + offset, tokens.size() - 1));
    }

    private Token advance() {
        Token t = current();
        if (!t.is(TokenKind.EOF)) {
            index++;
        }
        return t;
    }

    private Token expect(TokenKind kind, String what) {
        if (!current().is(kind)) {
            throw fail(current(), "Expected " + what + ", found " + current());
        }
        return advance();
    }

    private Token expectIdentifier(String what) {
        if (!current().isIdentifier()) {
            throw fail(current(), "Expected " + what + ", found " + current());
        }
        return advance();
    }

    private void expectWord(String word) {
        if (!current().isWord(word)) {
            throw fail(current(), "Expected " + word + ", found " + current());
        }
        advance();
    }

    private ParseException fail(Token at, String message) {
        diagnostics.error(at.position(), message);
        return new ParseException(message);
    }

    /** Unwinds to the nearest recovery point; the diagnostic is recorded before it is thrown. */
    private static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message, null, false, false);
        }
    }
}
