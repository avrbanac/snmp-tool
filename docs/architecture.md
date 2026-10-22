# Architecture

## Overview

```
┌──────────────────────────── app (JavaFX) ─────────────────────────────┐
│  MainWindow                                                           │
│  ├─ MibTreeView (search, FilteredTree) ──── DetailsPane               │
│  └─ OperationsPanel (device, OID, results) ── DeviceProfileDialog,    │
│                                                SetDialog              │
│  MibController ── ImportDialog, ModulesWindow (modules / problems)    │
└────────────────┬──────────────────────────────────────┬───────────────┘
                 │ public API                           │
┌────────────────▼──────────────── core ────────────────▼───────────────┐
│  mib          MibLoader, MibSource, BundledMibs, MibSearch            │
│  mib.parse    Lexer, Parser, AST, Diagnostics             (internal)  │
│  mib.resolve  Resolver, ModuleNames                       (internal)  │
│  mib.model    MibModel, MibModule, MibNode, MibType, OidTree, Oid     │
│  mib.repo     MibRepository, ImportPlan, ModuleEntry                  │
│  snmp         SnmpSession, DeviceProfile, SnmpValue, ResultFormatter, │
│               ValueParser, DisplayHint                                │
└───────────────────────────────────────────────────────────────────────┘
```

## Gradle subprojects

| Subproject | Purpose                                     | Depends on          |
|------------|---------------------------------------------|---------------------|
| `core`     | MIB parsing, model, repository, SNMP client | SNMP4J (internal)   |
| `app`      | JavaFX user interface                       | `core`, JavaFX      |

`core` has no JavaFX dependency so it can be tested headless and reused (for example by a future CLI).
It exports `core.mib`, `core.mib.model`, `core.mib.repo` and `core.snmp`; `core.mib.parse` and
`core.mib.resolve` are internal. SNMP4J is required by `core` but no SNMP4J type is part of its API.

## Packages

Base package: `hr.avrbanac.snmptool`.

| Package                  | Responsibility                                                    |
|--------------------------|-------------------------------------------------------------------|
| `core.mib`               | Public entry points: `MibLoader`, `MibSource`, `BundledMibs`, `MibSearch` |
| `core.mib.parse`         | Tokens, lexer, recursive-descent parser, AST, diagnostics         |
| `core.mib.resolve`       | Turns ASTs from many modules into a resolved model                |
| `core.mib.model`         | Immutable resolved model: modules, nodes, types, the OID tree, `Oid`, diagnostics |
| `core.mib.repo`          | MIB store, import planning (`ImportPlan`), import/update/remove   |
| `core.snmp`              | SNMP4J wrapper: sessions, profiles, operations, value decoding and parsing |
| `app`                    | Application entry point, main window, shared UI helpers           |
| `app.tree`               | Lazy tree view, cells, search box and filtered tree               |
| `app.details`            | Details pane and text formatting for display                      |
| `app.mibs`               | `MibController`, import dialog, MIB modules / problems window     |
| `app.ops`                | Operations panel, results table, device profile and set dialogs   |

Dialogs live in the package of the feature they belong to (e.g. the import dialog in `app.mibs`).

## Data flow

1. **Startup:** `MibController` asks `MibRepository` for the bundled sources (minus those replaced by imported
   modules) plus the imported ones, and loads them into a `MibModel` in a background task.
2. **Model:** the `MibModel` holds all resolved modules and one merged `OidTree`. It is immutable; any change
   (import, update, remove) builds a new model and the UI swaps to it.
3. **Browsing:** the tree view reads `OidTree` nodes lazily; the details pane renders the selected `MibNode`.
4. **Changes:** import and removal go through `MibController` → `MibRepository`; afterwards the model and
   its `MibSearch` index are rebuilt in the background and the tree keeps its selection.
5. **Operations:** the operations panel sends requests through the `SnmpSession` of the selected device;
   results come back as `VarBindResult`s and are named and formatted by `ResultFormatter` against the
   current `MibModel`. Selecting a tree node fills the OID field; double-clicking a result selects its
   definition in the tree.

## Threading

- Loading, import planning, importing, removing and search indexing run off the JavaFX thread via
  `Background.run` (a JavaFX `Task` on a daemon thread); results are applied on the JavaFX thread.
- `MibController` disables MIB actions while one is running and keeps the old model until the new one is
  ready.
- `core` is thread-agnostic: it never touches UI threads; model objects are immutable, `MibRepository` is
  synchronized and `MibSearch` is thread-safe.
- SNMP requests run the same way, one at a time; walks stream batches to the table via
  `Platform.runLater` and stop when Stop sets the cancel flag.

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
| Settings/profiles | `~/.snmp-tool/settings.json` (planned, M6) |

Bundled base MIBs are read from the classpath and are never copied into the user store.
