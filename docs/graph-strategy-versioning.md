# Graph Strategy Versioning

## Goal

Use `grayStrategyVersion` to affect real execution behavior, not only diagnostics metadata.

Current versioned controls:

- `preferredAgentIds`
- `routeOverrideDomains`

Both support:

- base config
- versioned override config

## Config Shape

```yaml
agent:
  distributed:
    gray-release:
      enabled: true
      strategy-version: v3
      preferred-agent-ids:
        marketing: marketing-agent
      route-override-domains:
        parallel: contract
      versioned-preferred-agent-ids:
        v2:
          marketing: marketing-agent
        v3:
          marketing: marketing-agent
          settlement: settlement-agent
      versioned-route-override-domains:
        v2:
          parallel: contract
        v3:
          parallel: settlement
```

## Resolution Rule

1. Load base `preferredAgentIds` and `routeOverrideDomains`
2. Read active `strategyVersion`
3. If there is a version-specific map, overlay it onto base config
4. Use merged result in:
   - agent selection
   - parallel route decision
   - downstream handoff context

## Rollback

Rollback is done by switching:

- `agent.distributed.gray-release.strategy-version`

If a version has no override entry, system falls back to base config.

## Current Coverage

- `SelectAgentNode`
- `ParallelInvokeNode`
- `HandoffNode`
- `RouteDecisionNode`
- diagnostics / event-audit metadata
