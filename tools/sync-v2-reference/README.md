# SoundWave sync v2 — offline reference only

This module is a local, executable protocol model. It is not the deployed SoundWave server, an HTTP service, or a production migration. It must not be pointed at real user accounts. No production endpoint was called to develop or test it.

## Run

```sh
python3 -m unittest discover -v
```

Python standard library only (SQLite included). Tests use synthetic data and in-memory / temporary databases.

## What it demonstrates

- Compare-and-swap revisions within one SQLite transaction.
- Stable event identities and replay deduplication instead of adding ambiguous historical totals.
- Tombstones, owner isolation, revision-bound pagination and derived statistics.
- A versioned envelope with bounded batches and nested JSON validation.
- Rejection of known credential-field names and device-only paths, without rejecting ordinary song titles merely containing a word such as “password”.

The validation is deliberately conservative, not a complete production record schema or data-loss-prevention guarantee. It cannot detect a secret hidden under an innocuous field name. A production adapter must use per-kind field allowlists, authenticate before this module, bind `owner` from the verified identity (never the body), enforce whole-request/aggregate storage limits before JSON decoding, and integrate HTTP status/version negotiation. Identity and authorization are not supplied here. Current storage uses a JSON document per owner; it is a correctness model, not a scalable table/index design.

## Compatibility boundary

The current Android client still omits lifetime play counters and trims history. This reference does not change that behavior. Existing counter totals cannot safely be converted into unique playback events without a documented migration/deduplication rule. HTTP/authentication adapters, capability negotiation and a verified deployed-server migration remain required before any client upload or synchronization changes.

Minimum server material needed: the deployed implementation of the account sync read/write routes, their request/response schemas and database migration/schema code (redacted of credentials), plus its version/commit. A path mentioned in client docs is not evidence of deployed behavior.

## Provenance / license boundary

Based on the independently written round3 offline reference in this workspace; round4 adds strict container/version checks, decoded nested credential-key/device-path rejection, JSON type/finite-number limits, work/depth limits and eight boundary tests. No third-party implementation was copied.

Any Listen's WebDAV synchronization source was inspected for architectural comparison (online/cancellation checks, lock lifecycle, consistent status reporting), not used as code. Its repository license includes AGPL terms and additional restrictions, so integration would require a separate license compatibility review. This reference does not claim compatibility with Any Listen, LX Open API or LX Scheme.
