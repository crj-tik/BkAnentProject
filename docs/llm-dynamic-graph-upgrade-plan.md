# LLM Dynamic Graph Upgrade Plan

## 1. Current Supervisor Execution Sequence

Current `agent-service` uses **official Spring AI Alibaba Graph**, but it does **not** let an LLM dynamically generate the graph topology.

Current sequence:

1. Entry:
   - `POST /agent/supervisor/tasks`
   - `POST /agent/supervisor/workflows`
2. `SupervisorGraphPlanner.plan(...)`
3. Official planning graph:
   - `LoadSessionNode`
   - `ParseIntentNode`
   - `PlanTaskNode`
   - `SelectAgentNode`
4. Runtime branch:
   - single agent -> `SingleAgentSubgraph`
   - parallel -> `ParallelAgentSubgraph`
5. Optional approval:
   - `ApprovalSubgraphService`
6. Resume after callback:
   - `ResumeSubgraph`
7. Optional downstream handoff:
   - `HandoffSubgraph`
8. Final completion:
   - `CompletionSubgraph`

Current behavior:

- Graph topology is precompiled and fixed in code.
- Intent parsing is rule-based.
- Parallel/approval routing is rule-based.
- Agent selection is registry + governance driven.

## 2. Target Upgrade

Upgrade target is:

- LLM recognizes user intent
- LLM outputs a structured workflow plan
- system validates the plan
- system maps the validated plan into dynamic graph execution

Important boundary:

- LLM should **not** directly emit raw graph DSL or arbitrary executable graph topology
- LLM should emit a **safe structured plan**
- system should convert the plan into graph/subgraph composition

## 3. Phase Plan

### Phase 1

Goal:

- add LLM planning result
- keep existing graph topology unchanged
- use LLM plan to override rule-based `intent/domain/workflowType/parallel/selectedAgent`
- fallback to existing rules on validation failure

Implementation:

1. add `WorkflowPlan`
2. add `WorkflowPlanStep`
3. add `SupervisorIntentPlanningService`
4. add `WorkflowPlanValidator`
5. integrate into:
   - `ParseIntentNode`
   - `PlanTaskNode`
   - `SelectAgentNode`

### Phase 2

Goal:

- introduce explicit `LlmIntentPlanNode`
- introduce `PlanValidationNode`
- move plan generation out of rule nodes

Planning graph becomes:

- `LoadSessionNode`
- `LlmIntentPlanNode`
- `PlanValidationNode`
- `PlanTaskNode`
- `SelectAgentNode`

### Phase 3

Goal:

- dynamic subgraph assembly from validated plan

Introduce:

- `DynamicWorkflowAssembler`
- `WorkflowPlanExecutionTemplate`
- `AllowedTransitionPolicy`

This phase turns:

- validated plan -> graph/subgraph composition

instead of fixed `SingleAgentSubgraph` / `ParallelAgentSubgraph` only.

### Phase 4

Goal:

- approval, handoff, event-only steps, and route steps become plan-addressable building blocks

This phase completes:

- LLM planning
- validated dynamic assembly
- controlled dynamic execution

## 4. Safety Boundary

The system must continue to own:

- allowed domains
- allowed intents
- allowed transitions
- allowed approval insertion points
- allowed handoff targets

LLM can propose:

- intent
- workflow mode
- parallel candidates
- selected preferred agent
- next-step sequence

System must validate before execution.

## 5. Code Targets

Phase 1 adds:

- `WorkflowPlan`
- `WorkflowPlanStep`
- `SupervisorIntentPlanningService`
- `WorkflowPlanValidator`

Phase 1 updates:

- `ParseIntentNode`
- `PlanTaskNode`
- `SelectAgentNode`
- `DistributedAgentProperties`

## 6. Current Status After Phase 1 Start

Phase 1 should achieve:

- LLM-generated structured plan is available
- validated plan can influence:
  - `domain`
  - `intent`
  - `workflowType`
  - `parallelDomains`
  - `requireApproval`
  - `selectedAgent`
- existing graph remains stable
- fallback to rule plan stays available
