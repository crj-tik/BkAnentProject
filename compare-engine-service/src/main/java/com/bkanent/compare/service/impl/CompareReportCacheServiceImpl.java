package com.bkanent.compare.service.impl;

import com.bkanent.compare.config.CompareReportProperties;
import com.bkanent.compare.model.CompareReportCacheEntry;
import com.bkanent.compare.model.CompareReportResponse;
import com.bkanent.compare.service.CompareReportCacheService;
import com.bkanent.compare.service.CompareReportPdfService;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.model.ListingComparisonFacts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 房源对比报告缓存服务实现。
 */
@Service
public class CompareReportCacheServiceImpl implements CompareReportCacheService {

    private static final Logger log = LoggerFactory.getLogger(CompareReportCacheServiceImpl.class);

    private final ObjectMapper objectMapper;
    private final CompareReportProperties compareReportProperties;
    private final CompareReportPdfService compareReportPdfService;
    private final Map<String, CompareReportCacheEntry> cacheKeyIndex = new ConcurrentHashMap<>();
    private final Map<String, CompareReportCacheEntry> shareCodeIndex = new ConcurrentHashMap<>();

    public CompareReportCacheServiceImpl(ObjectMapper objectMapper,
                                         CompareReportProperties compareReportProperties,
                                         CompareReportPdfService compareReportPdfService) {
        this.objectMapper = objectMapper;
        this.compareReportProperties = compareReportProperties;
        this.compareReportPdfService = compareReportPdfService;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(getStorageDir());
            loadPersistedEntries();
        } catch (IOException exception) {
            throw new IllegalStateException("初始化房源对比报告缓存目录失败", exception);
        }
    }

    @Override
    public Optional<CompareReportResponse> findByCacheKey(String ownerActorId, String cacheKey) {
        if (ownerActorId == null || cacheKey == null) return Optional.empty();
        return Optional.ofNullable(cacheKeyIndex.get(indexKey(ownerActorId, cacheKey)))
                .map(CompareReportCacheEntry::report);
    }

    @Override
    public synchronized CompareReportResponse save(String ownerActorId, String cacheKey,
                                                   String sourceVersionHash, CompareReportResponse report) {
        if (ownerActorId == null || !ownerActorId.matches("[1-9][0-9]{0,18}")
                || cacheKey == null || !cacheKey.matches("[0-9a-f]{64}") || report == null
                || !validSourceVersion(sourceVersionHash, report)) {
            throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
        }
        String ownerCacheKey = indexKey(ownerActorId, cacheKey);
        CompareReportCacheEntry existingEntry = cacheKeyIndex.get(ownerCacheKey);
        if (existingEntry != null) {
            return existingEntry.report();
        }

        String shareCode = UUID.randomUUID().toString().replace("-", "");
        String pdfFileName = shareCode + ".pdf";
        CompareReportResponse persistedReport = new CompareReportResponse(
                report.listings(),
                report.columns(),
                report.rows(),
                report.metrics(),
                report.comparisonTableMarkdown(),
                report.aiConclusion(),
                shareCode,
                buildShareLink(shareCode),
                buildPdfDownloadUrl(shareCode)
        );
        CompareReportCacheEntry entry = new CompareReportCacheEntry(
                cacheKey,
                ownerActorId,
                sourceVersionHash,
                false,
                shareCode,
                pdfFileName,
                persistedReport,
                LocalDateTime.now()
        );

        writePdfFile(entry);
        writeMetadataFile(entry);
        cacheKeyIndex.put(ownerCacheKey, entry);
        shareCodeIndex.put(shareCode, entry);
        log.info("已缓存房源对比报告，cacheKey={}，shareCode={}", cacheKey, shareCode);
        return persistedReport;
    }

    @Override
    public CompareReportCacheEntry findEntryByShareCode(String shareCode) {
        CompareReportCacheEntry entry = shareCodeIndex.get(shareCode);
        if (entry == null || !validEntry(entry)) throw new IllegalArgumentException("未找到对应的房源对比报告: " + shareCode);
        return entry;
    }

    @Override
    public synchronized CompareReportCacheEntry publishShare(String shareCode, String ownerActorId) {
        CompareReportCacheEntry entry = findEntryByShareCode(shareCode);
        if (ownerActorId == null || !ownerActorId.equals(entry.ownerActorId())) {
            throw new AuthzException(AuthzErrorCode.PERMISSION_DENIED);
        }
        if (!entry.publicShare()) {
            CompareReportCacheEntry shared = new CompareReportCacheEntry(entry.cacheKey(), entry.ownerActorId(),
                    entry.sourceVersionHash(), true, entry.shareCode(), entry.pdfFileName(), entry.report(),
                    entry.createdAt());
            writeMetadataFile(shared);
            shareCodeIndex.put(shareCode, shared);
            cacheKeyIndex.put(indexKey(ownerActorId, entry.cacheKey()), shared);
            return shared;
        }
        return entry;
    }

    @Override
    public Resource loadPdfResource(String shareCode) {
        CompareReportCacheEntry entry = findEntryByShareCode(shareCode);
        Path pdfPath = getStorageDir().resolve(entry.pdfFileName());
        if (!Files.exists(pdfPath)) {
            writePdfFile(entry);
        }
        return new FileSystemResource(pdfPath);
    }

    private void loadPersistedEntries() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(getStorageDir(), "*.json")) {
            for (Path metadataFile : stream) {
                CompareReportCacheEntry entry = objectMapper.readValue(metadataFile.toFile(), CompareReportCacheEntry.class);
                if (!validEntry(entry)) {
                    log.warn("忽略缺少owner或源版本指纹的旧对比报告元数据，file={}", metadataFile.getFileName());
                    continue;
                }
                cacheKeyIndex.put(indexKey(entry.ownerActorId(), entry.cacheKey()), entry);
                shareCodeIndex.put(entry.shareCode(), entry);
                ensurePdfExists(entry);
            }
        }
        log.info("已加载房源对比报告缓存，数量={}", shareCodeIndex.size());
    }

    private void ensurePdfExists(CompareReportCacheEntry entry) {
        Path pdfPath = getStorageDir().resolve(entry.pdfFileName());
        if (!Files.exists(pdfPath)) {
            writePdfFile(entry);
        }
    }

    private void writePdfFile(CompareReportCacheEntry entry) {
        try {
            byte[] pdfBytes = compareReportPdfService.generatePdf(entry.report());
            Files.write(getStorageDir().resolve(entry.pdfFileName()), pdfBytes);
        } catch (IOException exception) {
            throw new IllegalStateException("写入房源对比 PDF 文件失败", exception);
        }
    }

    private void writeMetadataFile(CompareReportCacheEntry entry) {
        try {
            objectMapper.writeValue(getStorageDir().resolve(entry.shareCode() + ".json").toFile(), entry);
        } catch (IOException exception) {
            throw new IllegalStateException("写入房源对比报告缓存元数据失败", exception);
        }
    }

    private Path getStorageDir() {
        return Path.of(compareReportProperties.getStorageDir());
    }

    private String buildShareLink(String shareCode) {
        return "/compare/reports/share/" + shareCode;
    }

    private String buildPdfDownloadUrl(String shareCode) {
        return "/compare/reports/pdf/" + shareCode;
    }

    private String indexKey(String ownerActorId, String cacheKey) {
        return ownerActorId + ":" + cacheKey;
    }

    private boolean validEntry(CompareReportCacheEntry entry) {
        return entry != null && entry.ownerActorId() != null && !entry.ownerActorId().isBlank()
                && entry.ownerActorId().matches("[1-9][0-9]{0,18}")
                && entry.cacheKey() != null && entry.cacheKey().matches("[0-9a-f]{64}")
                && entry.shareCode() != null && entry.shareCode().matches("[0-9a-f]{32}")
                && (entry.shareCode() + ".pdf").equals(entry.pdfFileName())
                && entry.sourceVersionHash() != null && validSourceVersion(entry.sourceVersionHash(), entry.report());
    }

    private boolean validSourceVersion(String sourceVersionHash, CompareReportResponse report) {
        if (sourceVersionHash == null || !sourceVersionHash.matches("[0-9a-f]{64}")
                || report == null || report.listings() == null) return false;
        return sourceVersionHash.equals(ListingComparisonFacts.fingerprint(report.listings(), false));
    }
}
