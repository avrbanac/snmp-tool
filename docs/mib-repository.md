# MIB repository design

The repository manages which MIB modules are available, where their files live, how they depend on each
other and how they are updated.

## Sources

1. **Bundled:** standard IETF modules shipped on the classpath. Read-only; cannot be removed.
2. **User store:** files imported by the user, copied to `~/.snmp-tool/mibs/`.

A user-store module with the same name as a bundled one overrides it (useful for newer revisions).

## Index

`~/.snmp-tool/mibs/index.json`, one entry per module:

| Field          | Meaning                                                      |
|----------------|--------------------------------------------------------------|
| `module`       | Module name from `DEFINITIONS`                               |
| `file`         | File name inside the store                                   |
| `sha256`       | Hash of the stored file                                      |
| `lastUpdated`  | `LAST-UPDATED` of `MODULE-IDENTITY` (SMIv2), if present      |
| `revisions`    | `REVISION` dates, newest first                               |
| `imports`      | Names of modules this module imports from                    |
| `importedAt`   | When the file was imported                                   |
| `originalPath` | Where the file was imported from                             |

The index is a cache: if it is missing or stale (hash mismatch), it is rebuilt by scanning the store.

## Import flow

1. User selects files or a folder.
2. Each file is lexed and parsed far enough to read module names, `IMPORTS` and revision info.
3. For each module found:
   - **New module:** stage for adding.
   - **Same name, identical hash:** skip ("already imported").
   - **Same name, different content:** compare `LAST-UPDATED`; show old vs new revision and ask whether to
     replace. Older-than-installed is allowed but flagged.
4. Build the dependency closure of staged modules against bundled + store + staged modules.
   List **missing modules** by name, with which module needs them.
5. User confirms; files are copied into the store, the index is updated, and a new `MibModel` is built.

Modules with missing dependencies can still be imported; their unresolved parts are marked in the tree and
listed in the diagnostics view.

## Dependency graph

- Directed graph: module → modules it imports from.
- Used for: missing-dependency reports, load order, finding dependants on update/remove.
- Cycles are allowed (they exist in the wild); the resolver handles them because it resolves all modules
  together rather than strictly in order.

## Update and remove

- **Update:** replacing a module triggers a full model rebuild. Dependants are re-resolved automatically;
  newly appearing diagnostics (e.g. a symbol removed in the new revision) are shown to the user.
- **Remove:** warns if other modules depend on the module, listing them. Bundled modules cannot be removed.

## Concurrency and safety

- All store changes go through `MibRepository`; writes are done to a temp file and atomically moved.
- The model rebuild runs in the background; the UI keeps the old model until the new one is ready.
