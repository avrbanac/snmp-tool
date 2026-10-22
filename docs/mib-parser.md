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

1. **Module table:** map module name → AST. Duplicate names are resolved by the repository before this step.
2. **Imports:** each imported symbol is looked up in the named module. Missing modules and missing symbols
   produce diagnostics; affected definitions still load with unresolved parts marked.
3. **OIDs:** each OID value `{ parent n }` or `{ iso org(3) dod(6) ... }` is resolved recursively with
   cycle detection. Built-in roots: `ccitt(0)`, `iso(1)`, `joint-iso-ccitt(2)`.
4. **Types:** syntax references are followed through textual conventions down to a base SMI type,
   collecting constraints, enumerations and display hints along the way.
5. **Tree:** all resolved nodes are inserted into a single `OidTree`. When two modules define the same OID,
   both definitions are kept; the tree node shows the first by load order and lists the others.

## Built-in definitions

`SNMPv2-SMI`, `SNMPv2-TC`, `SNMPv2-CONF`, `RFC1155-SMI`, `RFC-1212`, `RFC-1215` and `RFC1213-MIB` are bundled
as resource files and parsed normally. Their `MACRO` definitions are skipped; macro semantics are hard-coded
in the parser.

## Model

Immutable classes in `core.mib.model`:

- `MibModule` — name, file, last-updated, organization, contact, description, revisions, imports, nodes, types.
- `MibNode` — name, module, numeric OID, kind, syntax (`MibType`), access, status, units, index/augments,
  DEFVAL, description, reference, source position.
- `MibType` — base type, constraints, enumerations / bits, display hint, textual convention chain.
- `OidTree` — root plus lookup by numeric OID, by name and by `module::name`; longest-prefix lookup for
  decoding instance OIDs (e.g. `ifDescr.3`).

## Testing

- Unit tests for lexer and parser with small inline snippets.
- Every bundled IETF MIB must load with zero errors.
- Vendor quirks get a regression test with a minimal snippet in `core/src/test/resources/quirks/`.
