package com.bkanent.agent.orchestration;

import com.bkanent.agent.client.A2aAgentClient;
import com.bkanent.agent.client.AcceptedA2aTask;
import com.bkanent.agent.service.AgentPermissionService;
import com.bkanent.agent.service.A2aExecutionService;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Query/cancel the accepted original task; this class never submits a replacement invocation. */
@Component
public class RemoteTaskReconciler {
    private final OrchestrationStore store;
    private final A2aAgentClient client;
    private final AgentPermissionService permissions;
    private final A2aExecutionService execution;
    public RemoteTaskReconciler(OrchestrationStore store, A2aAgentClient client,
                                AgentPermissionService permissions, A2aExecutionService execution) {
        this.store = store; this.client = client; this.permissions = permissions; this.execution = execution;
    }
    private com.bkanent.agent.security.AgentRagCredentialLeaseStore credentials;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCredentials(com.bkanent.agent.security.AgentRagCredentialLeaseStore credentials) { this.credentials = credentials; }
    public OrchestrationStore.Invocation reconcile(String runId, String callId, String userId, boolean cancel) {
        store.assertOwner(runId, userId);
        try (var ignored = credentials == null ? (AutoCloseable) () -> { } : credentials.openLease(runId, userId)) {
            if (credentials != null) credentials.credentials(userId);
            var result = reconcileAuthenticated(runId, callId, userId, cancel);
            if (credentials != null) credentials.credentials(userId);
            return result;
        } catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("credential scope cleanup failed"); }
    }
    private OrchestrationStore.Invocation reconcileAuthenticated(String runId, String callId, String userId, boolean cancel) {
        var invocation = store.find(runId, callId);
        if (invocation == null) throw new IllegalArgumentException("call not found");
        if ("COMPLETED".equals(invocation.status()) || invocation.remoteAssociation() == null) return invocation;
        var accepted = store.read(invocation.remoteAssociation(), AcceptedA2aTask.class);
        if (!userId.equals(String.valueOf(accepted.request().structuredContext().get("userId"))))
            throw new IllegalStateException("remote call owner mismatch");
        permissions.assertCanInvokeChildAgent(accepted.descriptor(), accepted.request());
        client.restoreAsyncTask(accepted.descriptor(), accepted.remoteTaskId(), accepted.request());
        if (cancel) client.cancelAsyncTask(accepted.descriptor(), accepted.remoteTaskId());
        var status = execution.queryChildAsyncTaskStatus(accepted.descriptor(), accepted.remoteTaskId());
        if (AsyncRunStatus.terminal(status.status())) {
            String result = status.result() == null ? store.json(Map.of("status", status.status(),
                    "errorCode", status.errorCode() == null ? "" : status.errorCode(),
                    "errorMessage", status.errorMessage() == null ? "" : status.errorMessage())) : store.json(status.result());
            store.complete(runId, callId, result);
        }
        return store.find(runId, callId);
    }
}
