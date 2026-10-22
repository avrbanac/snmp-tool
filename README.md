# SNMP Tool

A desktop MIB browser with basic SNMP operations, built in Java and JavaFX.

The focus is on **browsing MIBs**: import MIB files, see every loaded module merged into one OID tree,
and read the full definition of any node (OID, syntax, access, status, description). On top of the browser,
the tool offers **get, getNext, walk and set** against a single device over SNMP v2c or v3.

> Status: early development. See [docs/milestones.md](docs/milestones.md) for the roadmap.

## Features

- **MIB tree:** every loaded module merged into one OID tree, with search by name or OID.
- **Details pane:** name, numeric OID, syntax (resolved through textual conventions), access, status,
  units, index, enumerated values and description for the selected node.
- **MIB import:** import single files or whole folders (recursively) into a local MIB store; review
  every module (new, newer, older, changed, already imported) before anything is written.
- **Dependency handling:** `IMPORTS` are resolved across modules; missing modules are reported by name,
  both before importing and afterwards.
- **Module updates and removal:** revisions are compared on re-import; imported modules can replace
  bundled ones and can be removed again.
- **Problems view:** every parser and resolver diagnostic with file and line.
- **Bundled base MIBs:** standard IETF modules (`SNMPv2-SMI`, `SNMPv2-TC`, `IF-MIB`, ...) are included.
- **SNMP operations:** get, getNext, walk and set on a selected node, using v2c (community) or v3
  (USM authentication and privacy). Results are decoded using MIB names, types and enumerations.

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
