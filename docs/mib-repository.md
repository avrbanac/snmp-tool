# MIB repository design

The repository (`core.mib.repo.MibRepository`) manages which MIB modules are available, where their files
live, how they depend on each other and how they are updated.

## Sources

1. **Bundled:** the IETF/IANA modules shipped on the classpath (see `docs/mib-parser.md`). Read-only;
   cannot be removed.
2. **Imported:** modules the user imported, stored in `~/.snmp-tool/mibs/`.

An imported module with the same name as a bundled one replaces it (useful for newer revisions). Removing
the imported module brings the bundled one back.

## Store layout

```
~/.snmp-tool/mibs/
├── ACME-SMI.mib          one file per module, named <MODULE>.mib
├── ACME-PORT-MIB.mib
└── index.properties      where and when each module was imported
```

- Module names, imports and revisions are **read from the files** each time (parsing all modules takes a
  few tens of milliseconds), so there is no cache that can go stale.
- `index.properties` holds only what cannot be derived from the files: `<MODULE>.from` (original path) and
  `<MODULE>.at` (import time, ISO-8601).
- Files are written to a temporary file and atomically moved into place; the index likewise.
- A source file containing several modules is split; each module is stored in its own file.
- Characters that are unsafe in file names are replaced with `_`.

## Import flow

1. The user selects files, or a folder that is searched recursively (hidden files are skipped).
2. `planImport` parses every file and builds an `ImportPlan` without writing anything:
   - files without a module, unreadable files and files over 16 MB become **problems** ("Skipped");
   - a module found in several selected files is taken from the one with the newest revision; the other
     copies are listed as problems;
   - each module becomes a **candidate** with a change status compared to the installed module of the
     same name (imported, or else bundled):

     | Status      | Meaning                                                        | Selected by default |
     |-------------|----------------------------------------------------------------|---------------------|
     | `NEW`       | no module with this name yet                                   | yes                 |
     | `NEWER`     | newer revision than installed                                  | yes                 |
     | `OLDER`     | older revision than installed                                  | no                  |
     | `CHANGED`   | different text, but same or no revision date                   | yes                 |
     | `IDENTICAL` | same text as installed; nothing to do                          | not selectable      |

     plus whether it would replace a bundled module, its revision, the installed revision, its
     dependencies and its parse error/warning counts.
   - The revision is the latest of `LAST-UPDATED` and the `REVISION` dates, normalized to
     `YYYYMMDDHHMM` (two-digit years mean 19YY).
3. The import dialog shows the plan. As the selection changes, it lists **missing dependencies**: modules
   that selected candidates import from but that are neither installed nor selected.
4. `applyImport` writes the selected candidates and updates the index; the model is then reloaded.

Modules with missing dependencies can still be imported; their unresolved parts are reported as
diagnostics and left out of the tree.

## Dependencies

- Each module's dependencies are the modules named in its `IMPORTS`.
- Used for missing-dependency reports and for listing dependants before removal.
- Cycles are allowed; the resolver handles them because it resolves all modules together.

## Remove

- Only imported modules can be removed. The confirmation lists the modules that import from it, and says
  when the bundled version will be used again.
- Removing deletes the file and its index entries; the model is reloaded.

## UI

- **File → Import MIB files…** (Ctrl+O) and **Import MIB folder…** open the import dialog.
- **File → MIB modules…** opens the *MIB Modules* window:
  - *Modules* tab: imported modules first, then bundled; origin, revision, problem count, import time and
    original path; import, remove and "show problems" actions; filter by name.
  - *Problems* tab: all diagnostics (severity, file, line, message), filterable by file or message.
- The status bar shows the problem count as a link to the *Problems* tab.

## Concurrency

- `MibRepository` methods are synchronized; the store is meant for one application instance at a time.
- In the app, planning, importing, removing and loading run in the background (`MibController`); actions
  are disabled while one is running, and the UI keeps the old model until the new one is ready.
