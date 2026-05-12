package com.bkanent.compare.service.impl;

import com.bkanent.compare.config.CompareReportProperties;
import com.bkanent.compare.model.CompareReportCacheEntry;
import com.bkanent.compare.model.CompareReportResponse;
import com.bkanent.compare.service.CompareReportCacheService;
import com.bkanent.compare.service.CompareReportPdfService;
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
    public Optional<CompareReportResponse> findByCacheKey(String cacheKey) {
        return Optional.ofNullable(cacheKeyIndex.get(cacheKey))
                .map(CompareReportCacheEntry::report);
    }

    @Override
    public CompareReportResponse save(String cacheKey, CompareReportResponse report) {
        CompareReportCacheEntry existingEntry = cacheKeyIndex.get(cacheKey);
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
                shareCode,
                pdfFileName,
                persistedReport,
                LocalDateTime.now()
        );

        writePdfFile(entry);
        writeMetadataFile(entry);
        cacheKeyIndex.put(cacheKey, entry);
        shareCodeIndex.put(shareCode, entry);
        log.info("已缓存房源对比报告，cacheKey={}，shareCode={}", cacheKey, shareCode);
        return persistedReport;
    }

    @Override
    public CompareReportResponse getByShareCode(String shareCode) {
        CompareReportCacheEntry entry = shareCodeIndex.get(shareCode);
        if (entry == null) {
            throw new IllegalArgumentException("未找到对应的房源对比报告: " + shareCode);
        }
        return entry.report();
    }

    @Override
    public Resource loadPdfResource(String shareCode) {
        CompareReportCacheEntry entry = shareCodeIndex.get(shareCode);
        if (entry == null) {
            throw new IllegalArgumentException("未找到对应的房源对比报告 PDF: " + shareCode);
        }
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
                cacheKeyIndex.put(entry.cacheKey(), entry);
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
}
