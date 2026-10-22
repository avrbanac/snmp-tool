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
included in `toString()`. In M5 device profiles live only in memory; persisting them (and how secrets are
stored) is part of M6.

## Testing

`core/src/test/.../snmp/TestAgent` is an in-process agent on 127.0.0.1 built on SNMP4J's command responder:
GET, GETNEXT, GETBULK and SET over v2c (communities `public` / `private`) and v3 users for every security
level (SHA/SHA-256 with AES-128/AES-256, MD5 with DES). `SnmpSessionTest` covers all operations, value types,
walk ends and cancellation, error statuses, timeouts and v3 reports against it.
