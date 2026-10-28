# Listing Business API v1

These producer endpoints implement the frozen `domain-parallel-v1` contract. All three requests carry `contractVersion`; IDs and revisions are opaque strings. They require the current signed Auth context. A missing signature, expired freshness proof, or unavailable authorization check never falls back to `brokerId`, an old credential path, or cached permission.

## Search

`POST /listings/business/v1/search`

```json
{
  "contractVersion": "domain-parallel-v1",
  "keyword": "朝阳 两居",
  "cursor": null,
  "limit": 20
}
```

The limit defaults to 20 and is capped at 100. A successful response uses the normal `ApiResponse` envelope; `data.items` is empty when no listing matches.

```json
{
  "success": true,
  "code": "0",
  "message": "OK",
  "data": {
    "contractVersion": "domain-parallel-v1",
    "items": [
      {
        "listingRef": { "sourceSystem": "legacy-local", "sourceId": "101" },
        "revision": "0000000000000000000000000000000000000000000000000000000000000000",
        "contentHash": "0000000000000000000000000000000000000000000000000000000000000000",
        "observedAt": "2026-10-27T09:00:00Z",
        "dataMode": "UNKNOWN",
        "datasetId": "legacy-local",
        "facts": {
          "title": "小区房源 101",
          "areaSquareMetres": "89.5",
          "totalPrice": { "amount": "3200000", "currency": "CNY", "unit": "YUAN" },
          "attributes": { "layout": "2室1厅" },
          "unknownFields": ["verificationStatus"]
        }
      }
    ],
    "nextCursor": null
  }
}
```

The sample values are illustrative. A fixture provider returns `SIMULATED`; old local rows without source proof return `UNKNOWN`. Neither request fields nor model output can change `dataMode`.

For an upstream HTTP provider, the Listing service forwards the currently verified delegation as `X-Listing-Actor-Id` and `X-Listing-Read-Scope: broker|business`; broker-scoped requests also carry `X-Listing-Broker-Id`. These headers are generated from the verified Auth context, never copied from request fields. An upstream that uses them must accept them only on the configured service credential. `LISTING_SOURCE_HTTP_CONFIRMED_DATA_MODE` is the operator's source-level confirmation and overrides upstream mode claims. If the upstream does not supply a revision, Listing derives one from the normalized source identity and facts; it always recomputes the public content hash itself.

The built-in fixture belongs to the broker configured by `LISTING_SOURCE_FIXTURE_BROKER_ID` (default `1`). Broker-scoped readers outside that scope receive no matching facts; `listing.read.all` can read the whole fixture set.

## Batch read

`POST /listings/business/v1/snapshots/batch-read`

```json
{
  "contractVersion": "domain-parallel-v1",
  "refs": [
    { "listingRef": { "sourceSystem": "legacy-local", "sourceId": "101" }, "expectedRevision": "0000000000000000000000000000000000000000000000000000000000000000" },
    { "listingRef": { "sourceSystem": "provider-a", "sourceId": "A-101" } }
  ]
}
```

The request accepts 1–20 references. A readable item includes its exact snapshot. An inaccessible or absent item is represented with `status: NOT_ACCESSIBLE` and no snapshot, without distinguishing absence from denied access. A supplied revision mismatch rejects the batch with HTTP 409 and `SOURCE_VERSION_CONFLICT`.

## Current-version check

`POST /listings/business/v1/snapshots/check`

```json
{
  "contractVersion": "domain-parallel-v1",
  "refs": [
    { "listingRef": { "sourceSystem": "provider-a", "sourceId": "A-101" }, "expectedRevision": "rev-17" }
  ]
}
```

Each returned item is `CURRENT`, `STALE`, `NOT_ACCESSIBLE`, or `UNAVAILABLE`. Check results never contain listing facts. A downstream caller must stop using stale or inaccessible facts; `UNAVAILABLE` is a dependency failure and is not permission to use a fixture or old cache.

## Errors

Errors retain the `ApiResponse` envelope. Unknown contract versions are HTTP 400 / `UNSUPPORTED_CONTRACT_VERSION`; malformed input is HTTP 400 / `INVALID_ARGUMENT`; Auth rejects invalid identity with its existing 401/403 contract; an expected-revision conflict is HTTP 409 / `SOURCE_VERSION_CONFLICT`; an unavailable selected source is HTTP 503 / `DEPENDENCY_UNAVAILABLE`. Unavailable upstream reads never switch providers.
