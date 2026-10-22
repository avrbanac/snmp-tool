# SNMP operations design

SNMP communication uses SNMP4J, wrapped by `core.snmp.SnmpClient` so the UI never depends on SNMP4J types.

## Scope

- One device at a time; no parallel polling.
- Versions: **v2c** and **v3**. (v1 is not a goal; agents that only speak v1 are out of scope for now.)
- Operations: **get**, **getNext**, **walk**, **set**.
- Transport: UDP (default port 161). TCP is not planned.

## Device profile

| Field          | v2c | v3 | Notes                                              |
|----------------|-----|----|----------------------------------------------------|
| name           | ✓   | ✓  | Display name                                       |
| host, port     | ✓   | ✓  | Port defaults to 161                               |
| timeout (ms)   | ✓   | ✓  | Default 2000                                       |
| retries        | ✓   | ✓  | Default 1                                          |
| read community | ✓   |    | Default `public`                                   |
| write community| ✓   |    | Used for set                                       |
| user name      |     | ✓  | USM security name                                  |
| security level |     | ✓  | noAuthNoPriv / authNoPriv / authPriv               |
| auth protocol  |     | ✓  | MD5, SHA-1, SHA-224, SHA-256, SHA-384, SHA-512     |
| auth password  |     | ✓  |                                                    |
| priv protocol  |     | ✓  | DES, 3DES, AES-128, AES-192, AES-256               |
| priv password  |     | ✓  |                                                    |
| context name   |     | ✓  | Optional                                           |
| context engine ID |  | ✓  | Optional; discovered otherwise                     |

## Operations

- **get:** for a scalar node, request `<oid>.0`; for a column, ask for the instance suffix. Any OID can also be
  typed directly.
- **getNext:** single step from the given OID.
- **walk:** GETBULK (max-repetitions configurable, default 25) until the response leaves the subtree,
  `endOfMibView` is returned, or the user cancels. Results stream to the UI in batches.
- **set:** type comes from the MIB node's resolved syntax; the dialog validates input against base type,
  ranges, sizes and enumerations before sending. Raw type override is possible for nodes without MIB info.

## Result decoding

Each variable binding is decoded against the current `MibModel`:

- Longest-prefix match on the OID gives the node; the remainder is the instance (`ifDescr.3`).
  Index values are decoded per the node's `INDEX` clause where possible.
- Value formatting: enumeration labels (`up(1)`), `BITS` names, `DISPLAY-HINT` formatting, `TimeTicks` as
  duration, `IpAddress` dotted, OIDs shown symbolically.
- Exceptions (`noSuchObject`, `noSuchInstance`, `endOfMibView`) and error status are shown explicitly.

## Secrets

Community strings and v3 passwords are not written to disk in plain text by default. Until a secure
storage solution is chosen (ADR pending, milestone M6), profiles persist without secrets and the user is
prompted when needed. Secrets are never logged.
