# Operations Governance Baseline

## Scope

Current governance baseline covers:

- runtime rate-limit override
- runtime gray-release override
- event-audit retention and archive

## Supervisor Governance API

### View current state

- `GET /agent/supervisor/governance`

### Override rate limit

- `POST /agent/supervisor/governance/rate-limit`

```json
{
  "entryType": "supervisor.tasks",
  "perWindow": 120
}
```

### Clear rate-limit override

- `POST /agent/supervisor/governance/rate-limit/clear?entryType=supervisor.tasks`

If `entryType` is omitted, all in-memory overrides are cleared.

### Override gray release

- `POST /agent/supervisor/governance/gray-release`

```json
{
  "enabled": true,
  "strategyVersion": "v3",
  "preferAsyncA2a": true
}
```

### Clear gray-release override

- `POST /agent/supervisor/governance/gray-release/clear`

## Event Audit Retention

Config keys:

- `agent.distributed.event-audit.max-active-events`
- `agent.distributed.event-audit.archive-enabled`
- `agent.distributed.event-audit.max-archived-events`
- `agent.distributed.event-audit.retention-seconds`

Behavior:

1. New events enter active deque
2. Overflow or expired active events move into archive if archive is enabled
3. Archived events are also trimmed by retention window and max archive size

## Event Audit Query

`GET /agent/supervisor/event-audit`

New flag:

- `includeArchived=true`

This lets operations inspect both active and archived in-memory audit windows.

## Rollback Notes

- Runtime overrides are in-memory and take effect immediately
- Restarting `agent-service` clears temporary overrides
- Base config still comes from `application.yml` / `nacos`
