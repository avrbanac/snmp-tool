# MIB parser design

The parser reads SMIv1 (RFC 1155, 1212, 1215) and SMIv2 (RFC 2578, 2579, 2580) MIB modules and produces a
resolved, immutable model. It is written in-house (see ADR-003) and is designed to be **lenient**:
real-world vendor MIBs frequently violate the standards, and the tool must still load them.

## Pipeline

```
file text ──► Lexer ──► tokens ──► Parser ──► AST (per module) ──┐
                                                                  ├──► Resolver ──► MibModel
                                   other modules' ASTs ───────────┘
```

Each stage appends to a shared `Diagnostics` collector instead of throwing.

## Lexer

- Tokens: identifiers (upper- and lower-case start are distinguished), numbers, negative numbers,
  `'...'H` hex strings, `'...'B` binary strings, quoted strings (may span lines, `""` escapes a quote),
  and symbols: `::=`, `{`, `}`, `(`, `)`, `[`, `]`, `,`, `;`, `|`, `..`, `.`, `-`.
- Comments: `--` to the next `--` or end of line (ASN.1 rule).
- Keywords are recognised by the parser, not the lexer, so vendor misuse of keyword-like names still lexes.
- Every token carries file, line and column.

## Parser

Hand-written recursive descent. A file may contain several modules.

```
Module      ::= ModuleName [OID] DEFINITIONS ::= BEGIN [Exports] [Imports] Assignment* END
Assignment  ::= name MacroInvocation ::= Value
              | name OBJECT IDENTIFIER ::= OidValue
              | TypeName ::= Type
              | TypeName ::= TEXTUAL-CONVENTION ...
              | MACRO definitions (skipped; built-in macros are known)
```

Supported macro invocations: `MODULE-IDENTITY`, `OBJECT-IDENTITY`, `OBJECT-TYPE`, `NOTIFICATION-TYPE`,
`TRAP-TYPE`, `TEXTUAL-CONVENTION`, `OBJECT-GROUP`, `NOTIFICATION-GROUP`, `MODULE-COMPLIANCE`,
`AGENT-CAPABILITIES`.

Types: `INTEGER` / `Integer32` / `Unsigned32` / `Counter32` / `Counter64` / `Gauge32` / `TimeTicks` /
`IpAddress` / `Opaque` / `OCTET STRING` / `BITS` / `OBJECT IDENTIFIER`, named numbers `{ up(1), down(2) }`,
`SIZE` and range constraints with `|` alternatives, `SEQUENCE { ... }`, `SEQUENCE OF`, references to
textual conventions and other types, and SMIv1 `NetworkAddress`, `Counter`, `Gauge`.

### Error recovery

- On an unexpected token inside an assignment, record a diagnostic and skip to the next token that can
  start an assignment (an identifier at a plausible position followed by a macro keyword or `::=`).
- Missing `END` at end of file is a warning, not an error.
- Unknown clauses inside a macro invocation are skipped up to the next known clause keyword.
- Case-only mismatches in keywords (e.g. `Object-Type`) are accepted with a warning.

## AST

Plain records mirroring the source: `ModuleAst`, `ImportAst`, `ObjectTypeAst`, `TypeAssignmentAst`, etc.
The AST keeps source positions so diagnostics and a future source viewer can point at definitions.

## Resolver

Runs over all loaded module ASTs at once.

1. **Module table:** map module name → AST. If two sources define the same module, the first one wins and the
   other is reported (the repository normally prevents this).
2. **Imports:** each imported symbol is looked up in the named module. Missing modules (error) and missing
   symbols (warning) are reported; definitions that depend on them are left out, everything else loads.
   Lenient fallbacks, each reported as a warning:
   - a symbol that a module re-imports from elsewhere is followed through that module's imports;
   - a name that is used but not imported is taken from any loaded module that defines it, preferring SMIv2
     modules;
   - SMI base types (`Counter32`, `Integer32`, ...) that are used but not imported fall back to their SMI
     definitions.
3. **OIDs:** each OID value `{ parent n }` or `{ iso org(3) dod(6) ... }` is resolved recursively with
   cycle detection. Built-in roots: `ccitt(0)`, `iso(1)`, `joint-iso-ccitt(2)`. A leading number is an
   absolute arc (`zeroDotZero ::= { 0 0 }`). SMIv1 `TRAP-TYPE` definitions are placed at
   `enterprise.0.trapNumber` (RFC 3584 mapping).
4. **Types:** syntax references are followed through textual conventions down to a base SMI type,
   collecting constraints, enumerations and display hints along the way. SMI application types are
   recognised by name when defined in `SNMPv2-SMI` or `RFC1155-SMI`. `MIN`/`MAX` in ranges are replaced by
   the limits of the base type (0..65535 for sizes).
5. **Kinds:** OBJECT-TYPEs become tables (`SEQUENCE OF`), rows (`INDEX`/`AUGMENTS`), columns (child of a row)
   or scalars.
6. **Tree:** all resolved nodes are inserted into a single `OidTree`. When two modules define the same OID,
   both definitions are kept. The primary one is from an SMIv2 module if there is one (so `IF-MIB` wins over
   `RFC1213-MIB`), otherwise the first by load order.

## Bundled MIBs

The SMI modules (`SNMPv2-SMI`, `SNMPv2-TC`, `SNMPv2-CONF`, `RFC1155-SMI`, `RFC-1212`, `RFC-1215`) and about 55
standard IETF and IANA modules are bundled under
`core/src/main/resources/hr/avrbanac/snmptool/core/mib/ietf/`, listed in load order in `index.list`. They are
parsed like any other MIB; `MACRO` definitions are skipped because macro semantics are built into the parser.

- Source: the `mibs/` directory of the net-snmp repository (verbatim IETF/IANA modules). Vendor modules
  (`NET-SNMP-*`, `UCD-*`, `LM-SENSORS-MIB`) are not included.
- `RFC-1212` is not published as a standalone module; it is assembled from the macro text in RFC 1212.
- All bundled modules must load with zero diagnostics (`BundledMibsTest`).

## Public API

- `MibLoader.load(List<MibSource>)` → `MibModel`; never throws on bad MIB content.
- `MibSource.read(Path)` reads a file (UTF-8, falling back to ISO-8859-1).
- `BundledMibs.ietf()` returns the bundled sources.
- `MibModel` gives modules, the `OidTree`, diagnostics and lookups by name, `MODULE::name` or numeric OID.

## Model

Immutable classes in `core.mib.model`:

- `MibModule` — name, source, SMI version, last-updated, organization, contact, description, revisions,
  imports, nodes, types.
- `MibNode` — name, module, numeric OID, kind, syntax (`MibType`), access, status, units, index/augments,
  DEFVAL, description, reference, source position.
- `MibType` — base type, constraints, enumerations / bits, display hint, textual convention chain.
- `OidTree` — top-level nodes, lookup by numeric OID, longest-prefix lookup for decoding instance OIDs
  (e.g. `ifDescr.3`). Unnamed intermediate arcs get nodes too, so the tree is always connected.
- `MibModel` — modules, tree, diagnostics; lookup by name, by `MODULE::name` and by numeric OID.

Text clauses (DESCRIPTION etc.) are kept exactly as written, including indentation; formatting for display
is the UI's job.

## Testing

- Unit tests for lexer and parser with small inline snippets.
- Every bundled IETF MIB must load with zero errors.
- Vendor quirks get a regression test with a minimal snippet in `core/src/test/resources/quirks/`.
