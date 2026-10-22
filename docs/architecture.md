# Architecture

## Overview

```
┌────────────────────────── app (JavaFX) ──────────────────────────┐
│  MainWindow                                                      │
│  ├─ MibTreeView ──── DetailsPane                                 │
│  └─ OperationsPanel ── ResultsTable                              │
│  ImportDialog, DeviceProfileDialog, DiagnosticsView              │
└───────────────┬───────────────────────────────┬──────────────────┘
                │ public API                    │
┌───────────────▼────────── core ───────────────▼──────────────────┐
│  mib.parse      Lexer, Parser, AST, Diagnostics                  │
│  mib.resolve    Resolver: imports, OIDs, types                   │
│  mib.model      MibModule, MibNode, MibType, OidTree (immutable) │
│  mib.repo       MibRepository, ModuleIndex, DependencyGraph      │
│  snmp           SnmpClient, DeviceProfile, VarBindResult         │
└──────────────────────────────────────────────────────────────────┘
```

## Gradle subprojects

| Subproject | Purpose                                     | Depends on          |
|------------|---------------------------------------------|---------------------|
| `core`     | MIB parsing, model, repository, SNMP client | SNMP4J              |
| `app`      | JavaFX user interface                       | `core`, JavaFX      |

`core` has no JavaFX dependency so it can be tested headless and reused (for example by a future CLI).
It exports only `core.mib` and `core.mib.model`; `core.mib.parse` and `core.mib.resolve` are internal.

## Packages

Base package: `hr.avrbanac.snmptool`.

| Package                  | Responsibility                                                    |
|--------------------------|-------------------------------------------------------------------|
| `core.mib`               | Public entry points: `MibLoader`, `MibSource`, `BundledMibs`       |
| `core.mib.parse`         | Tokens, lexer, recursive-descent parser, AST, diagnostics         |
| `core.mib.resolve`       | Turns ASTs from many modules into a resolved model                |
| `core.mib.model`         | Immutable resolved model: modules, nodes, types, the OID tree, `Oid`, diagnostics |
| `core.mib.repo`          | On-disk MIB store, index, dependency graph, import/update/remove  |
| `core.snmp`              | SNMP4J wrapper: sessions, profiles, operations, value decoding    |
| `app`                    | Application entry point, main window                              |
| `app.tree`               | Tree view, cell factories, search                                 |
| `app.details`            | Details pane                                                      |
| `app.ops`                | Operations panel and results table                                |
| `app.dialog`             | Import, device profile and set dialogs                            |

## Data flow

1. **Startup:** `MibRepository` reads its index, collects the bundled base MIBs plus imported ones, and asks
   the resolver to build a `MibModel` (in a background task).
2. **Model:** the `MibModel` holds all resolved modules and one merged `OidTree`. It is immutable; any change
   (import, update, remove) builds a new model and the UI swaps to it.
3. **Browsing:** the tree view reads `OidTree` nodes lazily; the details pane renders the selected `MibNode`.
4. **Operations:** the operations panel sends requests through `SnmpClient`; results come back as
   `VarBindResult` objects that are decoded against the current `MibModel`.

## Threading

- All MIB loading and SNMP requests run in JavaFX `Task`s on a background executor.
- `core` is thread-agnostic: it never touches UI threads and its model objects are immutable.
- SNMP walks are cancellable; results are streamed to the UI in batches.

## Configuration and data locations

| Item              | Location (default)               |
|-------------------|----------------------------------|
| Imported MIBs     | `~/.snmp-tool/mibs/`             |
| Repository index  | `~/.snmp-tool/mibs/index.json`   |
| Settings/profiles | `~/.snmp-tool/settings.json`     |

Bundled base MIBs are read from the classpath and are never copied into the user store.
