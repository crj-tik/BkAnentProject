package com.bkanent.knowledge.service;

import com.bkanent.knowledge.api.GenerateKnowledgeRequest;
import com.bkanent.knowledge.api.KnowledgeGenerationStatus;
import com.bkanent.knowledge.domain.KnowledgeCaseRecord;
import com.bkanent.knowledge.domain.KnowledgeContentIntegrity;
import com.bkanent.knowledge.domain.KnowledgeOperationException;
import com.bkanent.knowledge.domain.KnowledgeRevisionRecord;
import com.bkanent.knowledge.domain.KnowledgeState;
import com.bkanent.knowledge.generation.CandidateEvidence;
import com.bkanent.knowledge.generation.KnowledgeCandidate;
import com.bkanent.knowledge.generation.KnowledgeCandidateGenerator;
import com.bkanent.knowledge.persistence.KnowledgeRepository;
import com.bkanent.knowledge.security.KnowledgeAuthorization;
import com.bkanent.knowledge.security.KnowledgeOperations;
import com.bkanent.knowledge.source.InterviewSourceSnapshot;
import com.bkanent.knowledge.source.InterviewTranscriptSource;
import com.bkanent.knowledge.source.KnowledgeSourceException;
import com.bkanent.knowledge.source.SourceState;
import com.bkanent.knowledge.source.TranscriptIntegrity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeGenerationService {
    private static final String OPERATION = KnowledgeOperations.GENERATE_OWN;
    private final KnowledgeRepository repository;
    private final InterviewTranscriptSource interviewSource;
    private final KnowledgeCandidateGenerator generator;
    private final TransactionTemplate transactions;

    public KnowledgeGenerationService(KnowledgeRepository repository, InterviewTranscriptSource interviewSource,
                                      KnowledgeCandidateGenerator generator,
                                      PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.interviewSource = interviewSource;
        this.generator = generator;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public KnowledgeGenerationStatus generate(String knowledgeId, GenerateKnowledgeRequest request) {
        validateRequest(request);
        KnowledgeCaseRecord initial = requiredCase(knowledgeId, false);
        String actor = KnowledgeAuthorization.requireOwnActor(OPERATION);
        KnowledgeAuthorization.requireOwner(actor, initial.ownerActorId());
        if (!"OWN".equals(initial.scopeType())) throw failure("PERMISSION_DENIED", 403);
        String requestHash = TranscriptIntegrity.sha256(repository.write(request));
        Map<String, Object> existing = repository.findGenerationTask(request.requestId());
        if (existing != null) return statusForExisting(existing, requestHash, actor, knowledgeId);

        KnowledgeCaseRecord locked = requiredCase(knowledgeId, false);
        if (locked.version() != request.expectedVersion() || (locked.state() != KnowledgeState.DRAFT
                && locked.state() != KnowledgeState.WITHDRAWN)) throw failure("VERSION_CONFLICT", 409);
        List<InterviewSourceSnapshot> sources = loadSources(request.sourceAssetIds());
        String inputHash = inputHash(sources, request.rulesVersion());
        String taskId = UUID.randomUUID().toString();
        BeginResult begin = transactions.execute(status -> beginTask(taskId, knowledgeId, request, actor,
                requestHash, inputHash));
        if (begin == null) throw new IllegalStateException("Generation reservation returned no result");
        if (begin.status() != null) return begin.status();

        try {
            KnowledgeCandidate candidate = generator.generate(sources);
            validateCandidateEvidence(candidate, sources);
            requireSourcesCurrent(sources);
            return persistCandidate(taskId, knowledgeId, request, actor, requestHash, inputHash, sources, candidate);
        } catch (KnowledgeSourceException failure) {
            return failTask(taskId, knowledgeId, request, actor, failure.state().name());
        } catch (KnowledgeOperationException failure) {
            return failTask(taskId, knowledgeId, request, actor, failure.code());
        } catch (RuntimeException failure) {
            return failTask(taskId, knowledgeId, request, actor, "GENERATION_FAILED");
        }
    }

    public KnowledgeGenerationStatus status(String knowledgeId, String taskId) {
        KnowledgeCaseRecord record = requiredCase(knowledgeId, false);
        String actor = KnowledgeAuthorization.requireOwnActor(KnowledgeOperations.READ_OWN);
        KnowledgeAuthorization.requireOwner(actor, record.ownerActorId());
        Map<String, Object> task = repository.findGenerationTaskById(taskId);
        if (task == null || !knowledgeId.equals(String.valueOf(task.get("knowledge_id")))
                || !actor.equals(String.valueOf(task.get("created_by_actor_id")))) throw failure("NOT_ACCESSIBLE", 404);
        return toStatus(task);
    }

    private BeginResult beginTask(String taskId, String knowledgeId, GenerateKnowledgeRequest request,
                                  String actor, String requestHash, String inputHash) {
        Map<String, Object> existing = repository.findGenerationTask(request.requestId());
        if (existing != null) return new BeginResult(statusForExisting(existing, requestHash, actor, knowledgeId));
        try {
            repository.reserveReceipt(request.requestId(), OPERATION, requestHash, actor, knowledgeId, "{}");
        } catch (DuplicateKeyException duplicate) {
            Map<String, Object> task = repository.findGenerationTask(request.requestId());
            if (task == null) throw failure("OPERATION_IN_PROGRESS", 409);
            return new BeginResult(statusForExisting(task, requestHash, actor, knowledgeId));
        }
        KnowledgeCaseRecord record = requiredCase(knowledgeId, true);
        if (record.version() != request.expectedVersion() || (record.state() != KnowledgeState.DRAFT
                && record.state() != KnowledgeState.WITHDRAWN)) throw failure("VERSION_CONFLICT", 409);
        repository.insertGenerationTask(taskId, request.requestId(), knowledgeId, requestHash,
                inputHash, request.rulesVersion(), actor);
        return new BeginResult(null);
    }

    private KnowledgeGenerationStatus persistCandidate(String taskId, String knowledgeId, GenerateKnowledgeRequest request,
                                                        String actor, String requestHash, String inputHash,
                                                        List<InterviewSourceSnapshot> sources, KnowledgeCandidate candidate) {
        return transactions.execute(status -> {
            KnowledgeCaseRecord record = requiredCase(knowledgeId, true);
            if (record.version() != request.expectedVersion() || (record.state() != KnowledgeState.DRAFT
                    && record.state() != KnowledgeState.WITHDRAWN)) {
                throw failure("VERSION_CONFLICT", 409);
            }
            int nextRevision = record.currentRevision() + 1;
            String mode = deriveMode(sources);
            String contentHash = KnowledgeContentIntegrity.contentHash(candidate.summary(), candidate.body());
            repository.insertRevision(knowledgeId, nextRevision, contentHash, candidate.summary(), candidate.body(),
                    mode, candidate.body().evidenceLevel(), inputHash, request.rulesVersion(), actor);
            Map<String, InterviewSourceSnapshot> byId = new HashMap<>();
            sources.forEach(source -> byId.put(source.binding().assetId(), source));
            for (CandidateEvidence evidence : candidate.evidence()) {
                InterviewSourceSnapshot source = byId.get(evidence.assetId());
                if (source == null) throw failure("INVALID_GENERATION_EVIDENCE", 409);
                var location = TranscriptIntegrity.locateQuote(source.content(), evidence.quote());
                if (location == null) throw failure("INVALID_GENERATION_EVIDENCE", 409);
                repository.insertSource(knowledgeId, nextRevision, source.binding(), location.quoteHash(),
                        location.start(), location.end());
            }
            repository.updateCaseContent(knowledgeId, request.expectedVersion(), record.title(), record.businessProblem(),
                    record.scene(), record.tags(), nextRevision, mode);
            KnowledgeGenerationStatus result = new KnowledgeGenerationStatus(taskId, knowledgeId, "SUCCEEDED",
                    "r" + nextRevision, null);
            String resultJson = repository.write(result);
            repository.completeGenerationTask(taskId, nextRevision, resultJson);
            repository.completeReceipt(request.requestId(), OPERATION, resultJson);
            return result;
        });
    }

    private KnowledgeGenerationStatus failTask(String taskId, String knowledgeId, GenerateKnowledgeRequest request,
                                               String actor, String errorCode) {
        return transactions.execute(status -> {
            KnowledgeGenerationStatus result = new KnowledgeGenerationStatus(taskId, knowledgeId, "FAILED", null, errorCode);
            String json = repository.write(result);
            repository.failGenerationTask(taskId, errorCode, json);
            repository.completeReceipt(request.requestId(), OPERATION, json);
            return result;
        });
    }

    private List<InterviewSourceSnapshot> loadSources(List<String> ids) {
        List<InterviewSourceSnapshot> sources = new ArrayList<>();
        for (String id : ids) sources.add(interviewSource.read(id));
        return List.copyOf(sources);
    }

    private void requireSourcesCurrent(List<InterviewSourceSnapshot> sources) {
        for (InterviewSourceSnapshot source : sources) {
            SourceState state = interviewSource.check(source.binding().assetId(), source.binding());
            if (state != SourceState.CURRENT) throw new KnowledgeSourceException(state);
        }
    }

    private static void validateCandidateEvidence(KnowledgeCandidate candidate,
                                                  List<InterviewSourceSnapshot> sources) {
        if (candidate == null || candidate.evidence().isEmpty()) throw failure("INVALID_GENERATION_EVIDENCE", 409);
        Map<String, InterviewSourceSnapshot> byId = new HashMap<>();
        sources.forEach(source -> byId.put(source.binding().assetId(), source));
        java.util.HashSet<String> evidenceAssetIds = new java.util.HashSet<>();
        for (CandidateEvidence evidence : candidate.evidence()) {
            InterviewSourceSnapshot source = evidence == null ? null : byId.get(evidence.assetId());
            if (source == null || !evidenceAssetIds.add(evidence.assetId())
                    || TranscriptIntegrity.locateQuote(source.content(), evidence.quote()) == null) {
                throw failure("INVALID_GENERATION_EVIDENCE", 409);
            }
        }
    }

    private String inputHash(List<InterviewSourceSnapshot> sources, String rulesVersion) {
        List<?> bindings = sources.stream().map(source -> source.binding()).sorted(Comparator.comparing(value -> value.assetId())).toList();
        return TranscriptIntegrity.sha256(repository.write(Map.of("rulesVersion", rulesVersion, "sources", bindings)));
    }

    private static String deriveMode(List<InterviewSourceSnapshot> sources) {
        boolean simulated = sources.stream().anyMatch(source -> "SIMULATED".equals(source.binding().dataMode()));
        boolean unknown = sources.stream().anyMatch(source -> "UNKNOWN".equals(source.binding().dataMode()));
        boolean real = sources.stream().anyMatch(source -> "REAL".equals(source.binding().dataMode()));
        if (unknown) return "UNKNOWN";
        if (simulated && real) return "MIXED";
        if (simulated) return "SIMULATED";
        if (real) return "REAL";
        return "UNKNOWN";
    }

    private KnowledgeGenerationStatus statusForExisting(Map<String, Object> row, String requestHash,
                                                        String actor, String knowledgeId) {
        if (!requestHash.equals(String.valueOf(row.get("request_hash")))
                || !actor.equals(String.valueOf(row.get("created_by_actor_id")))
                || !knowledgeId.equals(String.valueOf(row.get("knowledge_id")))) throw failure("IDEMPOTENCY_CONFLICT", 409);
        return toStatus(row);
    }

    private KnowledgeGenerationStatus toStatus(Map<String, Object> row) {
        String json = row.get("result_json") == null ? null : String.valueOf(row.get("result_json"));
        if (json != null && !json.isBlank()) return repository.read(json, KnowledgeGenerationStatus.class);
        return new KnowledgeGenerationStatus(String.valueOf(row.get("task_id")),
                String.valueOf(row.get("knowledge_id")), String.valueOf(row.get("status")),
                null, row.get("error_code") == null ? null : String.valueOf(row.get("error_code")));
    }

    private KnowledgeCaseRecord requiredCase(String id, boolean lock) {
        if (id == null || id.isBlank() || id.length() > 64) throw failure("NOT_ACCESSIBLE", 404);
        KnowledgeCaseRecord record = repository.findCase(id, lock);
        if (record == null) throw failure("NOT_ACCESSIBLE", 404);
        return record;
    }

    private static void validateRequest(GenerateKnowledgeRequest request) {
        if (request == null || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9._:-]{1,128}")
                || request.expectedVersion() == null || request.expectedVersion() < 0
                || request.sourceAssetIds().isEmpty() || request.sourceAssetIds().size() > 20
                || request.sourceAssetIds().stream().anyMatch(id -> id == null || !id.matches("[0-9]{1,20}"))
                || request.sourceAssetIds().stream().distinct().count() != request.sourceAssetIds().size()
                || request.rulesVersion() == null || !request.rulesVersion().matches("[A-Za-z0-9._-]{1,64}")) {
            throw failure("INVALID_ARGUMENT", 400);
        }
    }

    private static KnowledgeOperationException failure(String code, int status) {
        return new KnowledgeOperationException(code, status);
    }

    private record BeginResult(KnowledgeGenerationStatus status) { }
}
