# SNMP operations design

SNMP communication uses SNMP4J (Apache 2.0) inside `core.snmp`. No SNMP4J type appears in the public API, so
the UI depends only on `core` types.

## Scope

- One device at a time; no parallel polling.
- Versions: **v2c** and **v3** (USM). SNMPv1 is not supported.
- Operations: **get**, **getNext**, **walk**, **set**.
- Transport: UDP (default port 161).

## Core API (`core.snmp`)

| Type             | Purpose                                                                          |
|------------------|----------------------------------------------------------------------------------|
| `DeviceProfile`  | Host, port, version, timeout, retries, max-repetitions, communities, v3 settings |
| `V3Security`     | USM user, security level, auth/privacy protocol and passphrase, context          |
| `SnmpSession`    | Open for a profile; `get`, `getNext`, `walk`, `set`; `close`                     |
| `SnmpValue`      | Sealed value types (Integer32, OctetString, ObjectId, IpAddress, Counter32, Gauge32, TimeTicks, Counter64, Opaque, Null, and the noSuchObject / noSuchInstance / endOfMibView exceptions) |
| `VarBindResult`  | Instance OID + value                                                             |
| `WalkResult`     | Count and how the walk ended                                                     |
| `SnmpException`  | `TIMEOUT`, `ERROR_STATUS` (status + index), `REPORT` (v3), `LOCAL`               |
| `ResultFormatter`| Names and formats results using the MIB model                                    |
| `DisplayHint`    | RFC 2579 DISPLAY-HINT formatting (octet strings and integers)                    |
| `ValueParser`    | Parses and validates user input for SET against the MIB type                     |
| `ResultExport`   | Writes formatted results as CSV or a text listing                                |

## Device profile

| Field           | v2c | v3 | Notes                                               |
|-----------------|-----|----|-----------------------------------------------------|
| name            | ✓   | ✓  | Unique display name                                 |
| host, port      | ✓   | ✓  | Port defaults to 161; host resolved when opening    |
| timeout (ms)    | ✓   | ✓  | Per attempt, default 2000                           |
| retries         | ✓   | ✓  | Default 1                                           |
| max repetitions | ✓   | ✓  | GETBULK size for walks, default 25                  |
| read community  | ✓   |    | get, getNext, walk                                  |
| write community | ✓   |    | set                                                 |
| user            |     | ✓  | USM security name                                   |
| security level  |     | ✓  | noAuthNoPriv / authNoPriv / authPriv                |
| auth protocol   |     | ✓  | MD5, SHA-1, SHA-224, SHA-256, SHA-384, SHA-512      |
| auth passphrase |     | ✓  | At least 8 characters                               |
| priv protocol   |     | ✓  | DES, 3DES, AES-128, AES-192, AES-256                |
| priv passphrase |     | ✓  | At least 8 characters                               |
| context name    |     | ✓  | Optional                                            |
| context engine ID |   | ✓  | Optional hex; otherwise the discovered engine ID    |

`DeviceProfile.problems()` lists what makes a profile unusable; the profile dialog shows it live.
`toString()` of profiles never includes communities or passphrases.

## Sessions

- `SnmpSession.open(profile)` creates its own UDP socket, message dispatcher and, for v3, its own USM holding
  only the profile's user. Nothing is registered in SNMP4J's global singletons, so sessions never share
  security state. Engine ID discovery is automatic.
- The app keeps one session for the selected device and reopens it when the device or its settings change.
- Methods block; the app calls them on background threads, one request at a time.

## Operations

- **get:** one GET; missing objects come back as noSuchObject / noSuchInstance values, not as errors.
- **getNext:** one GETNEXT. In the app the OID field then moves to the result, so pressing Get Next again
  steps through the MIB.
- **walk:** GETBULK with the profile's max-repetitions, starting at the root and continuing from the last OID
  received, until an OID leaves the subtree, endOfMibView is returned, or the walk is cancelled. Each
  response is delivered as a batch, so the UI fills while walking. If the subtree is empty (e.g. walking
  `sysDescr.0`), the root itself is fetched with a GET, as `snmpwalk` does. An agent returning
  non-increasing OIDs stops the walk with an error.
- **set:** one SET with the write community (v2c) or the v3 user. Error-status responses (`notWritable`,
  `wrongType`, ...) become `SnmpException`s naming the failing OID.

## Errors

| Kind           | Cause                                     | Message                                          |
|----------------|-------------------------------------------|--------------------------------------------------|
| `TIMEOUT`      | No response after timeout and retries     | Says what to check: host, port, community / user, protocols and passphrases |
| `ERROR_STATUS` | Agent answered with an error-status       | Status name and the OID at error-index           |
| `REPORT`       | v3 report (usmStats)                      | Unknown user, wrong digest, decryption error, not in time window, ... |
| `LOCAL`        | Invalid profile, unknown host, socket     | The problem                                      |

Wrong v2c communities time out (agents stay silent). A wrong v3 passphrase is reported by RFC 3414 agents
with an unauthenticated report; agents that authenticate the report, or drop the request, surface as a
timeout whose message points at the passphrases.

## Result decoding (`ResultFormatter`)

- **Name:** the deepest MIB definition above the OID plus the remaining arcs. For table columns the index is
  decoded per the row's `INDEX` (or the augmented row's): integers, `IpAddress` (4 arcs), `OCTET STRING`
  (fixed size, length-prefixed, or `IMPLIED`; printable strings in quotes, others as hex) and
  `OBJECT IDENTIFIER`. Undecodable indexes stay numeric. Examples: `ifDescr.3`, `ipAdEntAddr.10.0.0.1`,
  `vacmGroupName.3."public"`, `snmpTargetAddrTDomain."abc"`.
- **Value:** enumeration labels (`up(1)`), BITS names, DISPLAY-HINT formatting (`1x:` MAC addresses,
  `DateAndTime`, `255a` text, `d-2` decimals, ...), TimeTicks as durations, symbolic OIDs, UNITS appended;
  octet strings without a hint as text when they are clean UTF-8, otherwise hex.
- **Raw:** the value without interpretation (number, hex octets, dotted OID).

## Export (`ResultExport`)

"Export…" in the operations panel saves all results, and "Export selected rows…" in the results context
menu saves the selection, as UTF-8 files written in the background. The format follows the extension
(`.csv`, `.txt`) or, without one, the file type chosen in the save dialog, whose extension is then added.

- **CSV:** RFC 4180 with CRLF line endings and a header row `Name,Value,Type,OID,Raw` (the table's columns).
  Fields with commas, quotes, line breaks or edge spaces are quoted. Agent-supplied text starting with
  `=`, `+`, `-`, `@`, tab or CR gets a leading `'` so spreadsheets do not run it as a formula; plain numbers
  such as `-5` are left alone.
- **Text:** `#` header lines (device, export time, value count), then one line per value in the style of
  `snmpwalk`: `ifDescr.2 (1.3.6.1.2.1.2.2.1.2.2) = OCTET STRING: eth0`, or just the OID when no definition
  matches. Line breaks inside values are written as `\n`.

## Set input (`ValueParser`)

- The type comes from the MIB syntax; without MIB information it is guessed from the current value and can
  be changed by the user.
- Enumerations accept the label, `label(n)` or a defined number; BITS accept bit names or numbers.
- Ranges and sizes from the MIB type are enforced; octet strings can be entered as text or hex.
- OIDs accept numeric form, names and `name.instance` (e.g. `sysUpTime.0`); IP addresses dotted.
- The dialog reads the current value first to prefill the input, and warns when the MIB declares the object
  read-only.

## Secrets

Communities and v3 passphrases are entered in masked fields (with a "show" toggle), never logged, and not
included in `toString()`.

Device profiles are saved in `~/.snmp-tool/settings.json` (see Settings below). Saving secrets is opt-in per
device ("Remember communities and passphrases", ADR-007):

- **Remembered:** communities and passphrases are written to the settings file in plain text. The file is
  replaced atomically and created with owner-only permissions (`rw-------`) where the file system supports
  POSIX permissions.
- **Not remembered (default for new devices):** only the non-secret fields are written. After a restart the
  device asks for its communities or passphrases the first time it is used (a small dialog, which can also
  turn on "remember"); they then stay in memory until the app exits. v3 devices with `noAuthNoPriv` need
  no secrets and never ask.

Turning "remember" off removes the secrets from the file at the next save. The default `localhost` device
created on first start (communities `public` / `private`) remembers its secrets.

## Settings (`core.settings`)

| Type            | Purpose                                                                      |
|-----------------|------------------------------------------------------------------------------|
| `Settings`      | Contents of `settings.json`: devices, selection, window layout, tree node    |
| `SavedDevice`   | A `DeviceProfile` plus its `rememberSecrets` flag                            |
| `WindowLayout`  | Window bounds (not maximized), maximized, dividers, results column widths    |
| `SettingsStore` | Loads and saves the file; `Loaded` carries the settings and any problems     |

The file is JSON (`"version": 1`) written and read by a small built-in reader/writer, so `core` needs no JSON
library. Enums are stored by constant name (`V2C`, `AUTH_PRIV`, `SHA256`, ...). Loading is lenient, like the
MIB parser: wrong types and unknown values fall back to defaults, entries without a name or with a
duplicate name are skipped, unknown keys are ignored, and every such case is reported as a problem that the
app shows once at startup. A file that is not valid JSON is moved to `settings.json.broken` so it is not
overwritten. Layout values that are missing or invalid are unknown (`NaN`) and keep the default layout.

The app reads the file at startup and saves it whenever a device is added, edited, removed, selected or
used, and once more when the main window closes (window layout and tree selection). The device chooser
keeps devices most recently used first: sending a request with a device, or creating one, moves it to the
top. At startup the window gets its saved size and position (ignored if no screen shows that position
any more, e.g. after unplugging a monitor), maximized state, dividers and column widths, and the saved tree
node is selected once the MIBs have loaded.

## Testing

`core/src/test/.../snmp/TestAgent` is an in-process agent on 127.0.0.1 built on SNMP4J's command responder:
GET, GETNEXT, GETBULK and SET over v2c (communities `public` / `private`) and v3 users for every security
level (SHA/SHA-256 with AES-128/AES-256, MD5 with DES). `SnmpSessionTest` covers all operations, value types,
walk ends and cancellation, error statuses, timeouts and v3 reports against it.
