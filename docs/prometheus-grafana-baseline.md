# Prometheus / Grafana Baseline

## Scope

Current baseline covers:

- `agent-service`
- `notification-service`

Metrics endpoint:

- `/actuator/prometheus`

## Prometheus Scrape Example

```yaml
scrape_configs:
  - job_name: agent-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - 127.0.0.1:9002

  - job_name: notification-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - 127.0.0.1:9003
```

## Core Metrics

### Graph

- `bk_agent_graph_subgraph_duration_seconds`
- `bk_agent_graph_subgraph_count_total`

Tags:

- `subgraph`
- `status`
- `application`

### Async Task

- `bk_agent_async_task_duration_seconds`
- `bk_agent_async_task_count_total`

Tags:

- `mode`
- `status`
- `application`

### Async Workflow

- `bk_agent_async_workflow_duration_seconds`
- `bk_agent_async_workflow_count_total`

Tags:

- `status`
- `application`

### Security

- `bk_agent_security_permission_denied_total`

Tags:

- `action`
- `application`

### Notification

- `bk_notification_workflow_event_duration_seconds`
- `bk_notification_workflow_event_count_total`
- `bk_notification_workflow_consume_count_total`

Tags:

- `eventType`
- `status`
- `application`

## Grafana Panel Groups

### Graph

- Subgraph throughput by `subgraph,status`
- P95 subgraph latency by `subgraph`
- Failed subgraph count by `subgraph`

### Async

- Async task QPS by `mode,status`
- Async workflow success/failure count
- P95 async workflow latency

### Security

- Permission denied count by `action`
- Top denied actions in last 15m

### Notification

- Workflow event consume rate by `eventType,status`
- Dead-letter count trend
- Notification consume failure count

## Suggested Alerts

- `graph failed rate > 5% for 5m`
- `graph p95 latency > 5s for 5m`
- `async workflow failed count > 10 in 10m`
- `async workflow p95 latency > 30s for 10m`
- `permission denied count > 50 in 5m`
- `notification DEAD_LETTER count > 0 in 10m`
- `notification FAILED count > 10 in 10m`

## Rollout Notes

- Start with 15s scrape interval
- Validate `/actuator/prometheus` output before dashboard import
- Reuse `application` and `service` tags for cross-service filtering
