package com.bkanent.common.memory;

import java.util.Set;

/**
 * Fixed v2 memory RPC identity: one interface, one group, one version, and a closed
 * operation set. Every request envelope binds caller/audience/requestId/bodyHash so a
 * request has a stable hash identity for idempotency and replay control; signatures and
 * anti-replay verification are layered in 3.x, not here.
 */
public final class MemoryV2Contract {
    public static final int VERSION = 2;
    public static final String INTERFACE_NAME = "com.bkanent.common.memory.MemoryV2RpcService";
    public static final String DUBBO_GROUP = "bkanent-memory";
    public static final String DUBBO_SERVICE_VERSION = "2.3.0";
    public static final String MEMORY_AUDIENCE = "memory-service";

    public static final String OPERATION_WORKING_SET = "memory.working-set.read";
    public static final String OPERATION_RECEIVE_PERMIT = "memory.receive-permit.request";
    public static final String OPERATION_DELIVER_MESSAGES = "memory.messages.deliver";
    public static final String OPERATION_SUBMIT_SUMMARY = "memory.summary.submit";
    public static final String OPERATION_QUERY_MATTERS = "memory.matters.query";
    public static final String OPERATION_QUERY_ENTITIES = "memory.entities.query";
    public static final String OPERATION_SEARCH_PREFERENCES = "memory.preferences.search";
    public static final String OPERATION_SEARCH_EXPERIENCES = "memory.experiences.search";
    public static final String OPERATION_GET_GRANT = "memory.grant.get";
    public static final String OPERATION_WITHDRAW = "memory.source.withdraw";
    public static final String OPERATION_WRITER = "memory.writer.manage";
    public static final String OPERATION_BOUNDARY = "memory.boundary.check";
    public static final String OPERATION_PREFERENCE_WRITE = "memory.preference.assessment.submit";
    public static final String OPERATION_PREFERENCE_DETAIL = "memory.preference.history.read";
    public static final String OPERATION_MATTER_WRITE = "memory.matter.transition";
    public static final String OPERATION_ASSEMBLE_PREFERENCES = "memory.preferences.assemble";
    public static final String OPERATION_PRIVACY_STATUS = "memory.privacy-cleanup.status";
    public static final String OPERATION_EXPERIENCE_OUTCOME = "memory.experience.outcome.submit";
    public static final String OPERATION_EXPERIENCE_FEEDBACK = "memory.experience.feedback.submit";
    public static final String OPERATION_EXPERIENCE_STATUS = "memory.experience.event.status";
    public static final String OPERATION_EXPERIENCE_LIFECYCLE = "memory.experience.lifecycle";

    private static final Set<String> OPERATIONS = Set.of(
            OPERATION_WORKING_SET, OPERATION_RECEIVE_PERMIT, OPERATION_DELIVER_MESSAGES,
            OPERATION_SUBMIT_SUMMARY, OPERATION_QUERY_MATTERS, OPERATION_QUERY_ENTITIES,
            OPERATION_SEARCH_PREFERENCES, OPERATION_SEARCH_EXPERIENCES, OPERATION_GET_GRANT, OPERATION_WITHDRAW,
            OPERATION_WRITER, OPERATION_BOUNDARY, OPERATION_PREFERENCE_WRITE, OPERATION_PREFERENCE_DETAIL,
            OPERATION_MATTER_WRITE, OPERATION_ASSEMBLE_PREFERENCES, OPERATION_PRIVACY_STATUS,
            OPERATION_EXPERIENCE_OUTCOME, OPERATION_EXPERIENCE_FEEDBACK, OPERATION_EXPERIENCE_STATUS,
            OPERATION_EXPERIENCE_LIFECYCLE);
    private static final String HASH_PATTERN = "[0-9a-f]{64}";

    private MemoryV2Contract() { }

    static boolean validHash(String value) {
        return value != null && value.matches(HASH_PATTERN);
    }

    /** Shape-level envelope check; user authentication and signature stay server-side (3.x). */
    static void requireEnvelopeFields(String operation, String caller, String audience,
                                      String requestId, String bodyHash, int contractVersion) {
        if (contractVersion != VERSION) {
            throw new MemoryException(MemoryErrorCode.UNSUPPORTED_VERSION, MemoryException.Field.VERSION);
        }
        MemoryContract.require(OPERATIONS.contains(operation), MemoryException.Field.IDENTITY);
        MemoryContract.require(MEMORY_AUDIENCE.equals(audience), MemoryException.Field.IDENTITY);
        MemoryContract.id(caller, MemoryException.Field.IDENTITY);
        MemoryContract.id(requestId, MemoryException.Field.IDENTITY);
        MemoryContract.require(bodyHash != null && bodyHash.matches(HASH_PATTERN), MemoryException.Field.HASH);
    }

    /** Binds a fully constructed envelope to the operation its request type declares. */
    static void requireOperation(MemoryRequestEnvelope envelope, String expectedOperation) {
        MemoryContract.required(envelope, MemoryException.Field.IDENTITY);
        MemoryContract.require(expectedOperation.equals(envelope.operation()), MemoryException.Field.IDENTITY);
    }

    /** A page claiming more entries must expose the next cursor. */
    static void requirePage(String nextCursor, boolean hasMore) {
        if (hasMore) MemoryContract.id(nextCursor, MemoryException.Field.CURSOR);
    }

    static int requireLimit(int limit) {
        MemoryContract.require(limit > 0 && limit <= MemoryContract.MAX_LIST, MemoryException.Field.PAGE);
        return limit;
    }
}
