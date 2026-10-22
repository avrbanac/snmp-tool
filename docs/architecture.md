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
It exports `core.mib`, `core.mib.model` and `core.mib.repo`; `core.mib.parse` and `core.mib.resolve` are
internal.

## Packages

Base package: `hr.avrbanac.snmptool`.

| Package                  | Responsibility                                                    |
|--------------------------|-------------------------------------------------------------------|
| `core.mib`               | Public entry points: `MibLoader`, `MibSource`, `BundledMibs`, `MibSearch` |
| `core.mib.parse`         | Tokens, lexer, recursive-descent parser, AST, diagnostics         |
| `core.mib.resolve`       | Turns ASTs from many modules into a resolved model                |
| `core.mib.model`         | Immutable resolved model: modules, nodes, types, the OID tree, `Oid`, diagnostics |
| `core.mib.repo`          | MIB store, import planning (`ImportPlan`), import/update/remove   |
| `core.snmp`              | SNMP4J wrapper: sessions, profiles, operations, value decoding    |
| `app`                    | Application entry point, main window, shared UI helpers           |
| `app.tree`               | Lazy tree view, cells, search box and filtered tree               |
| `app.details`            | Details pane and text formatting for display                      |
| `app.mibs`               | `MibController`, import dialog, MIB modules / problems window     |
| `app.ops`                | Operations panel and results table                                |
| `app.dialog`             | Import, device profile and set dialogs                            |

## Data flow

1. **Startup:** `MibController` asks `MibRepository` for the bundled sources (minus those replaced by imported
   modules) plus the imported ones, and loads them into a `MibModel` in a background task.
2. **Model:** the `MibModel` holds all resolved modules and one merged `OidTree`. It is immutable; any change
   (import, update, remove) builds a new model and the UI swaps to it.
3. **Browsing:** the tree view reads `OidTree` nodes lazily; the details pane renders the selected `MibNode`.
4. **Operations:** the operations panel sends requests through `SnmpClient`; results come back as
   `VarBindResult` objects that are decoded against the current `MibModel`.

## Threading

- All MIB loading and SNMP requests run in JavaFX `Task`s on a background executor.
- `core` is thread-agnostic: it never touches UI threads and its model objects are immutable.
- SNMP walks are cancellable; results are streamed to the UI in batches.

## Performance

Measured with the LibreNMS MIB collection (4,865 files from 383 vendors → 4,723 modules, 893k OID nodes),
the worst case the tool is expected to see:

| Operation                                        | Time / size                   |
|--------------------------------------------------|-------------------------------|
| Plan an import of all files                      | ~2.4 s                        |
| Reload (load model, search index, module list)   | ~5 s warm, ~7.5 s cold        |
| Name search / description search                 | 2–16 ms / ~11 ms              |
| Memory for the model                             | ~1 GB                         |

With a few hundred vendor MIBs a reload takes well under a second. The things that keep large sets usable:
the resolver indexes all names once instead of searching modules on each unresolved name; `Oid` caches its
hash; the OID tree avoids copying its large maps; the repository caches module headers per file (size and
modification time); `MibSearch` keeps prepared lower-case names and normalized descriptions; and the tree
is built lazily. Memory is dominated by description text; loading descriptions on demand is possible but
not needed so far (the installer sets an adequate heap).

## Configuration and data locations

| Item              | Location (default)               |
|-------------------|----------------------------------|
| Imported MIBs     | `~/.snmp-tool/mibs/`             |
| Import origins    | `~/.snmp-tool/mibs/index.properties` |
| Settings/profiles | `~/.snmp-tool/settings.json`     |

Bundled base MIBs are read from the classpath and are never copied into the user store.
