package com.bkanent.knowledge.service;

import com.bkanent.common.knowledge.KnowledgeBody;
import com.bkanent.common.knowledge.KnowledgeContract;
import com.bkanent.common.knowledge.KnowledgeRef;
import com.bkanent.common.knowledge.KnowledgeSourceRef;
import com.bkanent.common.knowledge.PublishedBatchReadData;
import com.bkanent.common.knowledge.PublishedBatchReadItem;
import com.bkanent.common.knowledge.PublishedCheckData;
import com.bkanent.common.knowledge.PublishedCheckItem;
import com.bkanent.common.knowledge.PublishedCheckRequest;
import com.bkanent.common.knowledge.PublishedSearchData;
import com.bkanent.common.knowledge.PublishedSearchItem;
import com.bkanent.common.knowledge.PublishedSearchRequest;
import com.bkanent.knowledge.domain.KnowledgeCaseRecord;
import com.bkanent.knowledge.domain.KnowledgeRevisionRecord;
import com.bkanent.knowledge.domain.KnowledgeSourceRecord;
import com.bkanent.knowledge.domain.KnowledgeState;
import com.bkanent.knowledge.persistence.KnowledgeRepository;
import com.bkanent.knowledge.security.KnowledgeAuthorization;
import com.bkanent.knowledge.security.KnowledgeAuthorization.KnowledgeAccessScope;
import com.bkanent.knowledge.source.InterviewTranscriptSource;
import com.bkanent.knowledge.source.SourceState;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class PublishedKnowledgeService {
    private static final Pattern TAG = Pattern.compile("[\\p{L}\\p{N}_-]{1,48}");
    private static final int MAX_LIMIT = 100;
    private static final int MAX_OFFSET = 1_000_000;
    private static final String DATASET = "knowledge-published-v1";

    private final KnowledgeRepository repository;
    private final InterviewTranscriptSource interviewSource;

    public PublishedKnowledgeService(KnowledgeRepository repository, InterviewTranscriptSource interviewSource) {
        this.repository = repository;
        this.interviewSource = interviewSource;
    }

    public PublishedSearchData search(PublishedSearchRequest request) {
        validateContract(request == null ? null : request.contractVersion());
        validateSearch(request);
        KnowledgeAccessScope scope = KnowledgeAuthorization.requirePublishedReadScope();
        int limit = request.limit() == null ? 20 : request.limit();
        int offset = decodeCursor(request.cursor());
        List<KnowledgeRepository.KnowledgeSearchRow> rows = repository.search(scope.scopeType(), scope.actorId(),
                scope.storeId(), clean(request.problem()), clean(request.scene()), request.tags(), offset, limit + 1);
        boolean more = rows.size() > limit;
        List<PublishedSearchItem> items = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            var row = rows.get(i);
            KnowledgeRef ref = new KnowledgeRef(row.knowledgeId(), revision(row.revisionNo()), row.contentHash());
            SourceState sourceState = status(ref, scope, true);
            if (sourceState == SourceState.UNAVAILABLE) {
                throw new com.bkanent.knowledge.domain.KnowledgeOperationException("DEPENDENCY_UNAVAILABLE", 503);
            }
            if (sourceState != SourceState.CURRENT) continue;
            items.add(new PublishedSearchItem(ref, KnowledgeState.PUBLISHED.name(), row.observedAt(), row.dataMode(),
                    DATASET, sourceRefs(row.knowledgeId(), row.revisionNo()), row.summary()));
        }
        String next = more ? encodeCursor(offset + limit) : null;
        return new PublishedSearchData(KnowledgeContract.VERSION, items, next);
    }

    public PublishedBatchReadData batchRead(List<KnowledgeRef> refs) {
        validateReferences(refs);
        KnowledgeAccessScope scope = KnowledgeAuthorization.requirePublishedReadScope();
        List<PublishedBatchReadItem> items = new ArrayList<>(refs.size());
        for (KnowledgeRef ref : refs) {
            SourceState status = status(ref, scope, ref.contentHash() != null);
            if (status != SourceState.CURRENT) {
                items.add(new PublishedBatchReadItem(ref, status.name(), null, null, null, null, List.of(), null));
                continue;
            }
            int revisionNo = parseRevision(ref.revision());
            KnowledgeRevisionRecord revision = repository.findRevision(ref.knowledgeId(), revisionNo);
            items.add(new PublishedBatchReadItem(new KnowledgeRef(ref.knowledgeId(), ref.revision(), revision.contentHash()),
                    SourceState.CURRENT.name(), KnowledgeState.PUBLISHED.name(), revision.createdAt(),
                    revision.dataMode(), DATASET, sourceRefs(ref.knowledgeId(), revisionNo), revision.body()));
        }
        return new PublishedBatchReadData(KnowledgeContract.VERSION, items);
    }

    public PublishedCheckData check(PublishedCheckRequest request) {
        validateContract(request == null ? null : request.contractVersion());
        validateReferences(request == null ? null : request.refs());
        if (request.refs().stream().anyMatch(ref -> ref.contentHash() == null)) throw invalid();
        KnowledgeAccessScope scope = KnowledgeAuthorization.requirePublishedReadScope();
        List<PublishedCheckItem> items = request.refs().stream()
                .map(ref -> new PublishedCheckItem(ref, status(ref, scope, true).name())).toList();
        return new PublishedCheckData(KnowledgeContract.VERSION, items);
    }

    private SourceState status(KnowledgeRef ref, KnowledgeAccessScope scope, boolean checkHash) {
        KnowledgeCaseRecord record = repository.findCase(ref.knowledgeId(), false);
        if (!visible(record, scope) || record.state() != KnowledgeState.PUBLISHED || record.publishedRevision() == null) {
            return SourceState.NOT_ACCESSIBLE;
        }
        int requested = parseRevisionOrZero(ref.revision());
        if (requested == 0 || requested != record.publishedRevision()) return SourceState.STALE;
        KnowledgeRevisionRecord revision = repository.findRevision(ref.knowledgeId(), requested);
        if (revision == null || (checkHash && !revision.contentHash().equals(ref.contentHash()))) return SourceState.STALE;
        for (KnowledgeSourceRecord source : repository.findSources(ref.knowledgeId(), requested)) {
            SourceState state = interviewSource.check(source.binding().assetId(), source.binding());
            if (state != SourceState.CURRENT) return state;
        }
        return SourceState.CURRENT;
    }

    private List<KnowledgeSourceRef> sourceRefs(String knowledgeId, int revisionNo) {
        return repository.findSources(knowledgeId, revisionNo).stream().map(source -> {
            var binding = source.binding();
            return new KnowledgeSourceRef("interview", binding.assetId(), binding.snapshotHash(), binding.contentHash(),
                    binding.contentRevision(), binding.sanitizationRevision(), binding.publicationRevision(),
                    binding.dataMode(), source.quoteHash(), source.quoteStart(), source.quoteEnd());
        }).toList();
    }

    private static boolean visible(KnowledgeCaseRecord record, KnowledgeAccessScope scope) {
        if (record == null || !scope.scopeType().equals(record.scopeType())) return false;
        if ("OWN".equals(scope.scopeType())) return scope.actorId().equals(record.ownerActorId());
        return scope.storeId() != null && scope.storeId().equals(record.storeId());
    }

    private static void validateSearch(PublishedSearchRequest request) {
        if (request == null || request.tags().size() > 20
                || request.tags().stream().anyMatch(tag -> tag == null || !TAG.matcher(tag).matches())
                || (request.problem() != null && request.problem().length() > 500)
                || (request.scene() != null && request.scene().length() > 120)) throw invalid();
        int limit = request.limit() == null ? 20 : request.limit();
        if (limit < 1 || limit > MAX_LIMIT || (request.cursor() != null && request.cursor().length() > 128)) throw invalid();
    }

    private static void validateReferences(List<KnowledgeRef> refs) {
        if (refs == null || refs.isEmpty() || refs.size() > 20
                || refs.stream().anyMatch(ref -> ref == null || ref.knowledgeId() == null || ref.revision() == null)) {
            throw invalid();
        }
    }

    private static void validateContract(String version) {
        if (!KnowledgeContract.VERSION.equals(version)) {
            throw new com.bkanent.knowledge.domain.KnowledgeOperationException("UNSUPPORTED_CONTRACT_VERSION", 400);
        }
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String revision(int revision) { return "r" + revision; }
    private static int parseRevision(String revision) {
        int parsed = parseRevisionOrZero(revision);
        if (parsed == 0) throw invalid();
        return parsed;
    }
    private static int parseRevisionOrZero(String revision) {
        if (revision == null || !revision.matches("r[1-9][0-9]{0,8}")) return 0;
        try { return Integer.parseInt(revision.substring(1)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
            if (offset < 0 || offset > MAX_OFFSET) throw invalid();
            return offset;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Integer.toString(offset).getBytes(StandardCharsets.US_ASCII));
    }
    private static com.bkanent.knowledge.domain.KnowledgeOperationException invalid() {
        return new com.bkanent.knowledge.domain.KnowledgeOperationException("INVALID_ARGUMENT", 400);
    }
}
