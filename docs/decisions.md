# Architecture decision records

Short records of significant decisions. New decisions are appended; superseded ones are marked, not deleted.

## ADR-001 — Java as the implementation language

- **Date:** 2026-10-22
- **Status:** Accepted
- **Decision:** Implement the tool in Java (25, LTS).
- **Reason:** Preferred language of the maintainer; mature SNMP library (SNMP4J); good desktop GUI option
  (JavaFX); native packaging via jpackage.

## ADR-002 — JavaFX for the user interface

- **Date:** 2026-10-22
- **Status:** Accepted
- **Decision:** Desktop GUI in JavaFX (OpenJFX 25).
- **Alternatives:** Swing + FlatLaf (older toolkit, more effort for a modern look); web UI with a local server
  (two codebases, harder local file handling); Compose Multiplatform (Kotlin-first).
- **Reason:** `TreeView`/`TreeTableView` fit a MIB tree; CSS styling; actively maintained; works with jpackage.

## ADR-003 — In-house MIB parser

- **Date:** 2026-10-22
- **Status:** Accepted
- **Decision:** Write our own SMIv1/SMIv2 lexer, parser and resolver instead of using a third-party library.
- **Alternatives:**
  - *Mibble* — capable, but last release 2017, not on Maven Central, GPL, and its loader model conflicts
    with our repository design (store, revisions, replacement).
  - *SNMP4J-SMI* — commercial license.
  - *libsmi `smidump`* — native C tool as an external dependency.
  - *jsmiparser* — unmaintained.
- **Reason:** The SMI grammar is bounded; the repository features need our own module model anyway; full
  control over lenient parsing and diagnostics; no dependency risk.
- **Consequence:** More upfront work in M2; robustness grows with a regression corpus of real vendor MIBs.

## ADR-004 — SNMP4J for SNMP communication

- **Date:** 2026-10-22
- **Status:** Accepted
- **Decision:** Use SNMP4J (Apache 2.0) for get/getNext/walk/set over v2c and v3.
- **Reason:** Most complete Java SNMP stack, full USM support, permissive license.

## ADR-005 — Gradle with Kotlin DSL

- **Date:** 2026-10-22
- **Status:** Accepted
- **Decision:** Build with Gradle (Kotlin DSL), wrapper committed, versions in a version catalog.
- **Reason:** Straightforward JavaFX and jpackage plugin support; multi-project builds.

## ADR-006 — MIB store without a metadata cache

- **Date:** 2026-10-22
- **Status:** Accepted
- **Decision:** Store imported modules as one `<MODULE>.mib` file each and read module names, imports and
  revisions from the files on every load. Keep only the import origin (path, time) in `index.properties`.
- **Alternatives:** an `index.json` caching module metadata (the original plan in `mib-repository.md`).
- **Reason:** parsing all modules takes tens of milliseconds, so a cache gains nothing and could go stale;
  `java.util.Properties` needs no JSON library. One file per module makes replace and remove trivial.
