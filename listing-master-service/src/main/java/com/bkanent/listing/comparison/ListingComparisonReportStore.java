package com.bkanent.listing.comparison;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.listing.comparison.config.ListingComparisonProperties;
import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;
import com.bkanent.listing.comparison.model.ListingComparisonStoredReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Persists owner-bound Listing comparison reports and their generated PDFs. */
@Service
public class ListingComparisonReportStore {
    private static final Logger log = LoggerFactory.getLogger(ListingComparisonReportStore.class);

    private final ObjectMapper objectMapper;
    private final ListingComparisonProperties properties;
    private final ListingComparisonPdfService pdfService;
    private final Map<String, ListingComparisonStoredReport> byOwnerAndKey = new ConcurrentHashMap<>();
    private final Map<String, ListingComparisonStoredReport> byReportId = new ConcurrentHashMap<>();
    public ListingComparisonReportStore(ObjectMapper objectMapper, ListingComparisonProperties properties,
                                        ListingComparisonPdfService pdfService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.pdfService = pdfService;
    }

    @PostConstruct
    public void initialize() {
        try {
            Files.createDirectories(directory());
            loadListingReports();
            log.info("房源比较报告存储已初始化，已加载报告={}", byReportId.size());
        } catch (IOException failure) {
            throw new IllegalStateException("初始化房源比较报告存储失败", failure);
        }
    }

    public synchronized ListingComparisonReportResponse createIfAbsent(
            String ownerActorId, String cacheKey, String sourceVersionHash,
            ListingComparisonReportResponse candidate) {
        if (!validActor(ownerActorId) || !hash(cacheKey) || !hash(sourceVersionHash)
                || candidate == null || !validReportId(candidate.reportId())
                || !sourceVersionHash.equals(ListingComparisonFingerprint.sourceVersionHash(candidate.sources()))) {
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        }
        String ownerKey = ownerActorId + ":" + cacheKey;
        ListingComparisonStoredReport existing = byOwnerAndKey.get(ownerKey);
        if (existing != null) return existing.report();

        ListingComparisonStoredReport stored = new ListingComparisonStoredReport(ownerActorId, cacheKey,
                sourceVersionHash, candidate, candidate.createdAt());
        writeMetadata(stored);
        byOwnerAndKey.put(ownerKey, stored);
        byReportId.put(candidate.reportId(), stored);
        return candidate;
    }

    public ListingComparisonReportResponse findOwned(String reportId, String actorId) {
        ListingComparisonStoredReport stored = byReportId.get(reportId);
        if (stored == null || !validStored(stored) || !stored.ownerActorId().equals(actorId)) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        return stored.report();
    }

    public synchronized Resource loadPdf(String reportId, String actorId) {
        ListingComparisonReportResponse report = findOwned(reportId, actorId);
        Path pdf = safeResolve(reportId + ".pdf");
        if (!Files.exists(pdf)) {
            try {
                Files.write(pdf, pdfService.generate(report));
            } catch (IOException failure) {
                throw new IllegalStateException("写入房源比较 PDF 失败", failure);
            }
        }
        return new FileSystemResource(pdf);
    }

    private void loadListingReports() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory(), "*.listing.json")) {
            for (Path file : stream) {
                try {
                    ListingComparisonStoredReport stored = objectMapper.readValue(file.toFile(), ListingComparisonStoredReport.class);
                    if (!validStored(stored)) {
                        log.warn("忽略无效房源比较元数据，file={}", file.getFileName());
                        continue;
                    }
                    byOwnerAndKey.putIfAbsent(stored.ownerActorId() + ":" + stored.cacheKey(), stored);
                    byReportId.putIfAbsent(stored.report().reportId(), stored);
                } catch (RuntimeException | IOException malformed) {
                    log.warn("忽略无法读取的房源比较元数据，file={}", file.getFileName());
                }
            }
        }
    }

    private void writeMetadata(ListingComparisonStoredReport stored) {
        Path target = safeResolve(stored.report().reportId() + ".listing.json");
        Path temporary = safeResolve(stored.report().reportId() + ".listing.json.tmp");
        try {
            objectMapper.writeValue(temporary.toFile(), stored);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
        } catch (IOException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("写入房源比较报告元数据失败", failure);
        }
    }

    private boolean validStored(ListingComparisonStoredReport stored) {
        return stored != null && validActor(stored.ownerActorId()) && hash(stored.cacheKey())
                && hash(stored.sourceVersionHash()) && stored.report() != null
                && validReportId(stored.report().reportId()) && stored.report().createdAt() != null
                && stored.storedAt() != null
                && stored.sourceVersionHash().equals(ListingComparisonFingerprint.sourceVersionHash(stored.report().sources()));
    }

    private Path directory() {
        Path path = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
        return path;
    }

    private Path safeResolve(String fileName) {
        Path root = directory();
        Path target = root.resolve(fileName).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Invalid report file name");
        return target;
    }

    private static boolean validActor(String actor) { return actor != null && actor.matches("[1-9][0-9]{0,18}"); }
    private static boolean validReportId(String reportId) { return reportId != null && reportId.matches("[0-9a-f]{32}"); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
