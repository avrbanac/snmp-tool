# SNMP Tool

A desktop MIB browser with basic SNMP operations, built in Java and JavaFX.

The focus is on **browsing MIBs**: import MIB files, see every loaded module merged into one OID tree,
and read the full definition of any node (OID, syntax, access, status, description). On top of the browser,
the tool offers **get, getNext, walk and set** against a single device over SNMP v2c or v3.

> Status: initial development (version 0.1.0). The MIB browser, MIB import and SNMP operations are done
> (milestones M1–M5); polish and packaging (M6) are in progress. See [docs/milestones.md](docs/milestones.md).

## Features

- **MIB tree:** every loaded module merged into one OID tree, built lazily, with kind icons.
- **Search:** by name, `MODULE::name`, numeric or instance OID, and optionally description text; results
  are shown as a filtered tree that can be expanded or shown in the full tree.
- **Details pane:** name, numeric and symbolic OID, syntax with its type chain, display hint, enumerations,
  size and range, access, status, units, index, default value, description and source location; module
  identities also show revisions and contact information.
- **MIB import:** import single files or whole folders (recursively) into a local MIB store; review
  every module (new, newer, older, changed, already imported) before anything is written.
- **Dependency handling:** `IMPORTS` are resolved across modules; missing modules are reported by name,
  both before importing and afterwards.
- **Module updates and removal:** revisions are compared on re-import; imported modules can replace
  bundled ones and can be removed again.
- **Problems view:** every parser and resolver diagnostic with file and line.
- **Lenient parsing:** common vendor MIB mistakes are accepted with a warning instead of failing.
- **Bundled MIBs:** 61 standard IETF and IANA modules (`SNMPv2-SMI`, `SNMPv2-TC`, `IF-MIB`, `IP-MIB`,
  `HOST-RESOURCES-MIB`, ...).
- **Large sets:** tested with the LibreNMS collection (4,700+ vendor modules).
- **SNMP operations:** get, getNext, walk (GETBULK, cancellable) and set on the selected node or any OID,
  over v2c (communities) or v3 (USM: MD5/SHA-1/SHA-2 authentication, DES/3DES/AES privacy).
- **Decoded results:** instance names with decoded table indexes (`ifDescr.3`, `vacmGroupName.3."public"`),
  enumeration labels, BITS, display hints (MAC addresses, DateAndTime), TimeTicks, units and symbolic OIDs.
- **Set dialog:** prefilled with the current value, validated against the MIB type (enumerations, ranges,
  sizes), text or hex input.

Imported MIBs are stored in `~/.snmp-tool/mibs/`.

## Technology

| Concern          | Choice                                  |
|------------------|-----------------------------------------|
| Language         | Java 25 (LTS)                           |
| GUI              | JavaFX 25 (OpenJFX)                     |
| MIB parsing      | Own SMIv1/SMIv2 parser (no third party) |
| SNMP             | SNMP4J                                  |
| Build            | Gradle (Kotlin DSL) with wrapper        |
| Packaging        | jpackage (native installers)            |

Reasons behind these choices are recorded in [docs/decisions.md](docs/decisions.md).

## Building and running

Requires JDK 25. The Gradle wrapper downloads Gradle itself.

```sh
./gradlew build        # compile and run tests
./gradlew :app:run     # start the application
```

## Documentation

| Document                                         | Contents                                          |
|--------------------------------------------------|---------------------------------------------------|
| [docs/milestones.md](docs/milestones.md)         | Roadmap and milestone scope                       |
| [docs/architecture.md](docs/architecture.md)     | Modules, packages and how the pieces fit together |
| [docs/mib-parser.md](docs/mib-parser.md)         | Design of the SMI lexer, parser and resolver      |
| [docs/mib-repository.md](docs/mib-repository.md) | Local MIB store, dependencies and updates         |
| [docs/snmp-operations.md](docs/snmp-operations.md) | get / getNext / walk / set, v2c and v3          |
| [docs/development.md](docs/development.md)       | Build, conventions, testing, commits              |
| [docs/decisions.md](docs/decisions.md)           | Architecture decision log                         |
| [CHANGELOG.md](CHANGELOG.md)                     | Release history                                   |

## License

Internal use. No license has been chosen yet.
