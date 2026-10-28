package com.bkanent.knowledge.service;

import com.bkanent.common.knowledge.KnowledgeBody;
import com.bkanent.common.knowledge.KnowledgeSourceRef;
import com.bkanent.knowledge.api.KnowledgeCaseDetail;
import com.bkanent.knowledge.api.CreateKnowledgeRequest;
import com.bkanent.knowledge.api.EditKnowledgeRequest;
import com.bkanent.knowledge.api.KnowledgeMutationReceipt;
import com.bkanent.knowledge.api.KnowledgeOperationRequest;
import com.bkanent.knowledge.api.KnowledgeReviewRequest;
import com.bkanent.knowledge.domain.KnowledgeCaseRecord;
import com.bkanent.knowledge.domain.KnowledgeRevisionRecord;
import com.bkanent.knowledge.domain.KnowledgeSourceRecord;
import com.bkanent.knowledge.domain.KnowledgeState;
import com.bkanent.knowledge.domain.KnowledgeContentIntegrity;
import com.bkanent.knowledge.domain.KnowledgeOperationException;
import com.bkanent.knowledge.persistence.KnowledgeRepository;
import com.bkanent.knowledge.security.KnowledgeAuthorization;
import com.bkanent.knowledge.security.KnowledgeOperations;
import com.bkanent.knowledge.source.InterviewTranscriptSource;
import com.bkanent.knowledge.source.KnowledgeSourceException;
import com.bkanent.knowledge.source.SourceState;
import com.bkanent.knowledge.source.TranscriptIntegrity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeLifecycleService {
    private final KnowledgeRepository repository;
    private final InterviewTranscriptSource interviewSource;

    public KnowledgeLifecycleService(KnowledgeRepository repository, InterviewTranscriptSource interviewSource) {
        this.repository = repository;
        this.interviewSource = interviewSource;
    }

    public KnowledgeCaseDetail read(String knowledgeId) {
        KnowledgeCaseRecord record = requiredCase(knowledgeId, false);
        String ownOp = KnowledgeOperations.READ_OWN;
        String storeOp = KnowledgeOperations.READ_STORE;
        String actor;
        if ("OWN".equals(record.scopeType()) && record.state() == KnowledgeState.REVIEW_PENDING) {
            actor = KnowledgeAuthorization.requirePendingOwnReviewRead(record.ownerActorId());
        } else {
            actor = requireCaseOperation(record, ownOp, storeOp);
        }
        Integer revisionNo = record.state() == KnowledgeState.PUBLISHED
                ? record.publishedRevision() : record.currentRevision();
        KnowledgeRevisionRecord revision = requiredRevision(knowledgeId, revisionNo);
        List<KnowledgeSourceRecord> sources = repository.findSources(knowledgeId, revisionNo);
        for (KnowledgeSourceRecord source : sources) {
            try { interviewSource.read(source.binding().assetId(), source.binding()); }
            catch (KnowledgeSourceException failure) { throw failure; }
        }
        List<KnowledgeSourceRef> refs = sources.stream().map(source -> {
            var binding = source.binding();
            return new KnowledgeSourceRef("interview", binding.assetId(), binding.snapshotHash(), binding.contentHash(),
                    binding.contentRevision(), binding.sanitizationRevision(), binding.publicationRevision(),
                    binding.dataMode(), source.quoteHash(), source.quoteStart(), source.quoteEnd());
        }).toList();
        return new KnowledgeCaseDetail(record.knowledgeId(), record.scopeType(), record.title(),
                record.businessProblem(), record.scene(), record.tags(), record.state().name(),
                revision(record.currentRevision()), revision(record.publishedRevision()), record.dataMode(),
                record.version(), revision.contentHash(), revision.summary(), revision.body(), refs, record.updatedAt());
    }

    @Transactional
    public KnowledgeMutationReceipt create(CreateKnowledgeRequest request) {
        validateRequestId(request == null ? null : request.requestId());
        if (request == null || !"OWN".equals(request.scopeType())) {
            throw failure("PERMISSION_DENIED", 403);
        }
        String actor = KnowledgeAuthorization.requireOwnActor(KnowledgeOperations.CREATE_OWN);
        validateText(request.title(), 200, "INVALID_ARGUMENT");
        validateText(request.problem(), 1000, "INVALID_ARGUMENT");
        validateText(request.scene(), 120, "INVALID_ARGUMENT");
        validateTags(request.tags());
        if (request.storeId() != null) throw failure("INVALID_ARGUMENT", 400);
        KnowledgeBody body = safeManualBody(request.body(), request.problem(), request.scene());
        String knowledgeId = UUID.randomUUID().toString();
        String hash = requestHash(request);
        KnowledgeMutationReceipt replay = replay(request.requestId(), KnowledgeOperations.CREATE_OWN, hash, actor, null);
        if (replay != null) return replay;
        KnowledgeMutationReceipt raced = reserve(request.requestId(), KnowledgeOperations.CREATE_OWN, hash, actor,
                knowledgeId, null);
        if (raced != null) return raced;
        String contentHash = KnowledgeContentIntegrity.contentHash("", body);
        repository.insertCase(knowledgeId, "OWN", actor, null, request.title().trim(), request.problem().trim(),
                request.scene().trim(), request.tags(), KnowledgeState.DRAFT, 1, "UNKNOWN");
        repository.insertRevision(knowledgeId, 1, contentHash, "", body, "UNKNOWN", "UNVERIFIED",
                TranscriptIntegrity.sha256("manual:" + contentHash), "manual-v1", actor);
        return complete(request.requestId(), KnowledgeOperations.CREATE_OWN, actor,
                new KnowledgeMutationReceipt(request.requestId(), knowledgeId, "DRAFT", "r1", 0, "COMPLETED"));
    }

    @Transactional
    public KnowledgeMutationReceipt edit(String knowledgeId, EditKnowledgeRequest request) {
        validateRequestId(request == null ? null : request.requestId());
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw failure("INVALID_ARGUMENT", 400);
        }
        KnowledgeCaseRecord record = requiredCase(knowledgeId, true);
        String actor = requireCaseOperation(record, KnowledgeOperations.EDIT_OWN, null);
        String hash = requestHash(request);
        KnowledgeMutationReceipt replay = replay(request.requestId(), KnowledgeOperations.EDIT_OWN, hash, actor, knowledgeId);
        if (replay != null) return replay;
        verifyVersion(record, request.expectedVersion());
        if (record.state() != KnowledgeState.DRAFT && record.state() != KnowledgeState.WITHDRAWN) {
            throw failure("INVALID_STATE", 409);
        }
        validateText(request.title(), 200, "INVALID_ARGUMENT");
        validateText(request.problem(), 1000, "INVALID_ARGUMENT");
        validateText(request.scene(), 120, "INVALID_ARGUMENT");
        validateTags(request.tags());
        KnowledgeBody body = safeManualBody(request.body(), request.problem(), request.scene());
        KnowledgeMutationReceipt raced = reserve(request.requestId(), KnowledgeOperations.EDIT_OWN, hash, actor,
                knowledgeId, knowledgeId);
        if (raced != null) return raced;
        int nextRevision = record.currentRevision() + 1;
        String contentHash = KnowledgeContentIntegrity.contentHash("", body);
        repository.insertRevision(knowledgeId, nextRevision, contentHash, "", body, record.dataMode(),
                "UNVERIFIED", TranscriptIntegrity.sha256("manual:" + contentHash), "manual-v1", actor);
        repository.copySources(knowledgeId, record.currentRevision(), nextRevision);
        repository.updateCaseContent(knowledgeId, request.expectedVersion(), request.title().trim(),
                request.problem().trim(), request.scene().trim(), request.tags(), nextRevision, record.dataMode());
        return complete(request.requestId(), KnowledgeOperations.EDIT_OWN, actor,
                new KnowledgeMutationReceipt(request.requestId(), knowledgeId, "DRAFT",
                        revision(nextRevision), request.expectedVersion() + 1, "COMPLETED"));
    }

    @Transactional
    public KnowledgeMutationReceipt submit(String knowledgeId, KnowledgeOperationRequest request) {
        return transition(knowledgeId, request, KnowledgeOperations.SUBMIT_OWN, null,
                KnowledgeState.DRAFT, KnowledgeState.REVIEW_PENDING, null, true);
    }

    @Transactional
    public KnowledgeMutationReceipt review(String knowledgeId, KnowledgeReviewRequest request) {
        validateRequestId(request == null ? null : request.requestId());
        if (request == null || request.expectedVersion() == null
                || !("APPROVE".equals(request.decision()) || "REJECT".equals(request.decision()))) {
            throw failure("INVALID_ARGUMENT", 400);
        }
        KnowledgeCaseRecord record = requiredCase(knowledgeId, true);
        String operation = "STORE".equals(record.scopeType()) ? KnowledgeOperations.REVIEW_STORE : KnowledgeOperations.REVIEW_OWN;
        String actor;
        if ("OWN".equals(record.scopeType())) {
            actor = KnowledgeAuthorization.requireOwnActor(operation);
        } else if ("STORE".equals(record.scopeType())) {
            long storeId = KnowledgeAuthorization.requireStoreIdentity(operation);
            if (record.storeId() == null || storeId != record.storeId()) throw failure("NOT_ACCESSIBLE", 404);
            actor = KnowledgeAuthorization.actorId();
        } else {
            throw failure("NOT_ACCESSIBLE", 404);
        }
        String hash = requestHash(request);
        KnowledgeMutationReceipt replay = replay(request.requestId(), operation, hash, actor, knowledgeId);
        if (replay != null) return replay;
        verifyVersion(record, request.expectedVersion());
        if (record.state() != KnowledgeState.REVIEW_PENDING) throw failure("INVALID_STATE", 409);
        if (actor.equals(record.ownerActorId())) throw failure("REVIEWER_MUST_DIFFER", 403);
        if (request.reason() != null && request.reason().length() > 2000) throw failure("INVALID_ARGUMENT", 400);
        KnowledgeMutationReceipt raced = reserve(request.requestId(), operation, hash, actor, knowledgeId, knowledgeId);
        if (raced != null) return raced;
        repository.recordReview(knowledgeId, record.currentRevision(), actor, request.decision(), request.reason());
        KnowledgeState next = "APPROVE".equals(request.decision()) ? KnowledgeState.REVIEW_PENDING : KnowledgeState.DRAFT;
        repository.transition(knowledgeId, request.expectedVersion(), record.state(), next, null);
        return complete(request.requestId(), operation, actor,
                new KnowledgeMutationReceipt(request.requestId(), knowledgeId, next.name(),
                        revision(record.currentRevision()), request.expectedVersion() + 1, "COMPLETED"));
    }

    @Transactional
    public KnowledgeMutationReceipt publish(String knowledgeId, KnowledgeOperationRequest request) {
        validateRequestId(request == null ? null : request.requestId());
        if (request == null || request.expectedVersion() == null) throw failure("INVALID_ARGUMENT", 400);
        KnowledgeCaseRecord record = requiredCase(knowledgeId, true);
        String operation = "STORE".equals(record.scopeType()) ? KnowledgeOperations.PUBLISH_STORE : KnowledgeOperations.PUBLISH_OWN;
        String actor = requireCaseOperation(record, operation, null);
        String hash = requestHash(request);
        KnowledgeMutationReceipt replay = replay(request.requestId(), operation, hash, actor, knowledgeId);
        if (replay != null) return replay;
        verifyVersion(record, request.expectedVersion());
        if (record.state() != KnowledgeState.REVIEW_PENDING || !repository.hasApproval(knowledgeId, record.currentRevision())) {
            throw failure("REVIEW_REQUIRED", 409);
        }
        requireCurrentSources(knowledgeId, record.currentRevision());
        KnowledgeMutationReceipt raced = reserve(request.requestId(), operation, hash, actor, knowledgeId, knowledgeId);
        if (raced != null) return raced;
        repository.transition(knowledgeId, request.expectedVersion(), KnowledgeState.REVIEW_PENDING,
                KnowledgeState.PUBLISHED, record.currentRevision());
        return complete(request.requestId(), operation, actor,
                new KnowledgeMutationReceipt(request.requestId(), knowledgeId, "PUBLISHED",
                        revision(record.currentRevision()), request.expectedVersion() + 1, "COMPLETED"));
    }

    @Transactional
    public KnowledgeMutationReceipt withdraw(String knowledgeId, KnowledgeOperationRequest request) {
        validateRequestId(request == null ? null : request.requestId());
        if (request == null || request.expectedVersion() == null) throw failure("INVALID_ARGUMENT", 400);
        KnowledgeCaseRecord record = requiredCase(knowledgeId, true);
        String operation = "STORE".equals(record.scopeType()) ? KnowledgeOperations.WITHDRAW_STORE : KnowledgeOperations.WITHDRAW_OWN;
        String actor = requireCaseOperation(record, operation, null);
        String hash = requestHash(request);
        KnowledgeMutationReceipt replay = replay(request.requestId(), operation, hash, actor, knowledgeId);
        if (replay != null) return replay;
        verifyVersion(record, request.expectedVersion());
        if (record.state() != KnowledgeState.PUBLISHED) throw failure("INVALID_STATE", 409);
        KnowledgeMutationReceipt raced = reserve(request.requestId(), operation, hash, actor, knowledgeId, knowledgeId);
        if (raced != null) return raced;
        repository.transition(knowledgeId, request.expectedVersion(), KnowledgeState.PUBLISHED,
                KnowledgeState.WITHDRAWN, record.publishedRevision());
        return complete(request.requestId(), operation, actor,
                new KnowledgeMutationReceipt(request.requestId(), knowledgeId, "WITHDRAWN",
                        revision(record.publishedRevision()), request.expectedVersion() + 1, "COMPLETED"));
    }

    private KnowledgeMutationReceipt transition(String id, KnowledgeOperationRequest request, String ownOperation,
                                                String storeOperation, KnowledgeState expected,
                                                KnowledgeState next, Integer publicationRevision,
                                                boolean requireSources) {
        validateRequestId(request == null ? null : request.requestId());
        if (request == null || request.expectedVersion() == null) throw failure("INVALID_ARGUMENT", 400);
        KnowledgeCaseRecord record = requiredCase(id, true);
        String operation = "STORE".equals(record.scopeType()) ? storeOperation : ownOperation;
        if (operation == null) throw failure("PERMISSION_DENIED", 403);
        String actor = requireCaseOperation(record, operation, null);
        String hash = requestHash(request);
        KnowledgeMutationReceipt replay = replay(request.requestId(), operation, hash, actor, id);
        if (replay != null) return replay;
        verifyVersion(record, request.expectedVersion());
        if (record.state() != expected) throw failure("INVALID_STATE", 409);
        if (requireSources) requireCurrentSources(id, record.currentRevision());
        KnowledgeMutationReceipt raced = reserve(request.requestId(), operation, hash, actor, id, id);
        if (raced != null) return raced;
        repository.transition(id, request.expectedVersion(), expected, next, publicationRevision);
        return complete(request.requestId(), operation, actor,
                new KnowledgeMutationReceipt(request.requestId(), id, next.name(),
                        revision(record.currentRevision()), request.expectedVersion() + 1, "COMPLETED"));
    }

    private void requireCurrentSources(String id, int revision) {
        for (KnowledgeSourceRecord source : repository.findSources(id, revision)) {
            try { interviewSource.read(source.binding().assetId(), source.binding()); }
            catch (KnowledgeSourceException failure) { throw failure; }
        }
    }

    private KnowledgeCaseRecord requiredCase(String id, boolean lock) {
        if (id == null || id.isBlank() || id.length() > 64) throw failure("NOT_ACCESSIBLE", 404);
        KnowledgeCaseRecord record = repository.findCase(id, lock);
        if (record == null) throw failure("NOT_ACCESSIBLE", 404);
        return record;
    }

    private KnowledgeRevisionRecord requiredRevision(String id, int revision) {
        KnowledgeRevisionRecord record = repository.findRevision(id, revision);
        if (record == null) throw failure("NOT_ACCESSIBLE", 404);
        return record;
    }

    private String requireCaseOperation(KnowledgeCaseRecord record, String ownOperation, String storeOperation) {
        if ("OWN".equals(record.scopeType())) {
            String actor = KnowledgeAuthorization.requireOwnActor(ownOperation);
            KnowledgeAuthorization.requireOwner(actor, record.ownerActorId());
            return actor;
        }
        if ("STORE".equals(record.scopeType())) {
            if (storeOperation == null) storeOperation = ownOperation;
            long storeId = KnowledgeAuthorization.requireStoreIdentity(storeOperation);
            if (record.storeId() == null || storeId != record.storeId()) throw failure("NOT_ACCESSIBLE", 404);
            return KnowledgeAuthorization.actorId();
        }
        throw failure("NOT_ACCESSIBLE", 404);
    }

    private KnowledgeMutationReceipt replay(String requestId, String operation, String requestHash, String actor,
                                            String expectedTarget) {
        Map<String, Object> receipt = repository.findReceipt(requestId, operation);
        if (receipt == null) return null;
        if (!requestHash.equals(String.valueOf(receipt.get("request_hash")))
                || !actor.equals(String.valueOf(receipt.get("actor_id")))
                || (expectedTarget != null && !expectedTarget.equals(String.valueOf(receipt.get("target_id"))))) {
            throw failure("IDEMPOTENCY_CONFLICT", 409);
        }
        String json = String.valueOf(receipt.get("response_json"));
        if ("{}".equals(json)) throw failure("OPERATION_IN_PROGRESS", 409);
        return repository.read(json, KnowledgeMutationReceipt.class);
    }

    private KnowledgeMutationReceipt reserve(String requestId, String operation, String requestHash,
                                             String actor, String target, String expectedTarget) {
        try { repository.reserveReceipt(requestId, operation, requestHash, actor, target, "{}"); }
        catch (DuplicateKeyException duplicate) {
            return replay(requestId, operation, requestHash, actor, expectedTarget);
        }
        return null;
    }

    private KnowledgeMutationReceipt complete(String requestId, String operation, String actor,
                                              KnowledgeMutationReceipt receipt) {
        repository.completeReceipt(requestId, operation, repository.write(receipt));
        return receipt;
    }

    private String requestHash(Object request) { return TranscriptIntegrity.sha256(repository.write(request)); }
    private static String revision(Integer value) { return value == null ? null : "r" + value; }
    private static String revision(int value) { return "r" + value; }

    private static KnowledgeBody safeManualBody(KnowledgeBody input, String problem, String scene) {
        if (input == null || input.problem() == null || input.scene() == null) throw failure("INVALID_ARGUMENT", 400);
        List<String> fields = new java.util.ArrayList<>();
        fields.add(input.problem()); fields.add(input.scene()); fields.addAll(input.preconditions());
        fields.addAll(input.actions()); fields.addAll(input.observedResults()); fields.addAll(input.applicability());
        fields.addAll(input.unverifiedItems());
        for (String field : fields) {
            if (field == null || field.isBlank() || field.length() > 2000
                    || TranscriptIntegrity.containsCredentialShape(field)) throw failure("INVALID_ARGUMENT", 400);
        }
        return new KnowledgeBody(problem.trim(), scene.trim(), input.preconditions(), input.actions(),
                input.observedResults(), input.applicability(), input.unverifiedItems(), "UNVERIFIED");
    }

    private static void validateRequestId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) throw failure("INVALID_ARGUMENT", 400);
    }
    private static void validateText(String value, int max, String code) {
        if (value == null || value.isBlank() || value.length() > max) throw failure(code, 400);
    }
    private static void validateTags(List<String> tags) {
        if (tags == null || tags.size() > 32 || tags.stream().anyMatch(tag -> tag == null || tag.isBlank()
                || tag.length() > 48 || !tag.matches("[\\p{L}\\p{N}_-]+"))) throw failure("INVALID_ARGUMENT", 400);
    }
    private static void verifyVersion(KnowledgeCaseRecord record, long expected) {
        if (record.version() != expected) throw failure("VERSION_CONFLICT", 409);
    }
    private static KnowledgeOperationException failure(String code, int status) {
        return new KnowledgeOperationException(code, status);
    }
}
