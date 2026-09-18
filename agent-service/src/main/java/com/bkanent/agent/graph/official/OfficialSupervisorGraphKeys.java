package com.bkanent.agent.graph.official;

public final class OfficialSupervisorGraphKeys {

    public static final String SESSION_ID = "sessionId";
    public static final String TASK_ID = "taskId";
    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String USER_MESSAGE = "userMessage";
    public static final String REQUEST_STREAM = "requestStream";
    public static final String WORKFLOW_STATUS = "workflowStatus";
    public static final String SHARED_CONTEXT = "sharedContext";
    public static final String INTENT = "intent";
    public static final String DOMAIN = "domain";
    public static final String WORKFLOW_TYPE = "workflowType";
    public static final String REQUIRE_PARALLEL = "requireParallel";
    public static final String REQUIRE_APPROVAL = "requireApproval";
    public static final String SELECTED_AGENT_ID = "selectedAgentId";
    public static final String PARALLEL_DOMAINS = "parallelDomains";
    public static final String ARTIFACT_IDS = "artifactIds";
    public static final String HANDOFF_HISTORY = "handoffHistory";
    public static final String FINAL_ANSWER = "finalAnswer";
    public static final String PENDING_APPROVAL = "pendingApproval";
    public static final String LATEST_APPROVAL_DECISION = "latestApprovalDecision";
    public static final String CURRENT_INVOKE_REQUEST = "currentInvokeRequest";
    public static final String LATEST_AGENT_RESPONSE = "latestAgentResponse";
    public static final String NEXT_DOMAIN = "nextDomain";
    public static final String HANDOFF_TYPE = "handoffType";
    public static final String APPROVAL_RESUME_ACTION = "approvalResumeAction";
    public static final String RESUME_FEEDBACK = "resumeFeedback";
    public static final String SUPERVISOR_RESPONSE = "supervisorResponse";
    public static final String CURRENT_NODE = "currentNode";
    public static final String NEXT_NODE = "nextNode";
    public static final String ERROR_CODE = "errorCode";
    public static final String ERROR_MESSAGE = "errorMessage";
    public static final String RETRY_COUNT = "retryCount";
    public static final String MAX_RETRY_COUNT = "maxRetryCount";
    public static final String PLAN_VERSION = "planVersion";
    public static final String APPROVAL_VERSION = "approvalVersion";
    public static final String EVENT_REFERENCES = "eventReferences";
    public static final String RESUME_IDEMPOTENCY_KEY = "resumeIdempotencyKey";
    public static final String PARALLEL_RUN_ID = "parallelRunId";
    public static final String PARALLEL_BRANCH_RESULTS = "parallelBranchResults";
    public static final String PARALLEL_AGGREGATION_STRATEGY = "parallelAggregationStrategy";

    private OfficialSupervisorGraphKeys() {
    }
}
