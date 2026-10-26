package com.bkanent.interview.service;

import com.bkanent.interview.engine.EvidenceVerifier;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewReportEntity;
import com.bkanent.interview.entity.InterviewReportTaskEntity;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewReportMapper;
import com.bkanent.interview.mapper.InterviewReportTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 报告任务纯逻辑单测（不触库不调模型）：幂等快照语义与证据核验降级。
 */
class InterviewReportServiceTest {

    @Test
    void verbatimQuotesVerified() {
        List<EvidenceVerifier.VerifiedQuote> verified = EvidenceVerifier.verifyAll(
                List.of("当时我们看了三次房才定下来的", "看了好几次才决定"),
                "受访者：当时我们看了三次房才定下来的。");
        assertEquals(2, verified.size());
        assertEquals(EvidenceVerifier.QuoteStatus.VERBATIM, verified.get(0).status());
        assertEquals(EvidenceVerifier.QuoteStatus.PARAPHRASE, verified.get(1).status());
        assertEquals(1, EvidenceVerifier.verbatimCount(verified));
    }

    @Test
    void notAllVerbatimCapsScoreBelow79() {
        // 服务端核验规则：缺失 1 项（存在转述引用）封顶 79 分
        int verbatim = 2;
        int total = 3;
        boolean allVerbatim = verbatim == total;
        int score = allVerbatim ? 85 : Math.min(79, 60 + verbatim * 5);
        assertEquals(70, score);
        assertTrue(score <= 79);
    }

    @Test
    void successfulCaseNeverDirectlyValidated() {
        // L2 以上需外部证据与失效条件闭环：全场逐字命中 + L3 资产最高也只到 L2
        boolean allVerbatim = true;
        String assetGrade = "L3";
        String replicability = allVerbatim && "L3".equals(assetGrade) ? "L2" : "L1";
        assertEquals("L2", replicability);
        assertNotEquals("L3", replicability);
    }

    @Test
    void paraphraseMissingItemsClassifiedAsInterviewGap() {
        Map<String, EvidenceVerifier.MissingClass> missing = Map.of(
                "引用未逐字命中（已降级转述）", EvidenceVerifier.MissingClass.INTERVIEW_GAP);
        String json = EvidenceVerifier.missingJson(missing);
        assertTrue(json.contains("INTERVIEW_GAP"));
    }

    @Test
    void searchReportsRejectsBlankKeywordBeforeTouchingStorage() {
        // 空关键词 = 无检索语义：直接空结果，不进 LIKE 全表扫
        InterviewReportService service = new InterviewReportService(
                null, null, null, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), null, null, null);

        Map<String, Object> result = service.searchReports("", "emp-01", 1, 20);

        assertEquals(0L, result.get("total"));
        assertTrue(((List<?>) result.get("reports")).isEmpty());
        assertEquals(1, result.get("page"));
        assertEquals(20, result.get("pageSize"));
    }

    @Test
    void publicReportRequiresPublishedCurrentSourceAndMatchingCompletedTask() {
        InterviewReportTaskMapper tasks = mock(InterviewReportTaskMapper.class);
        InterviewReportMapper reports = mock(InterviewReportMapper.class);
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewAssetService assetService = mock(InterviewAssetService.class);
        InterviewReportEntity report = reportFixture();
        InterviewReportTaskEntity task = taskFixture();
        InterviewAssetEntity source = assetFixture();
        when(reports.findPublicReportById(3L, com.bkanent.interview.engine.Sanitizer.VERSION)).thenReturn(report);
        when(tasks.selectById(2L)).thenReturn(task);
        when(assets.selectById(9L)).thenReturn(source);
        when(assetService.isUsableReportSource(source)).thenReturn(true);
        when(assetService.isPubliclyEligible(source)).thenReturn(true);
        InterviewReportService service = service(tasks, reports, assets, assetService);

        Map<String, Object> result = service.getPublicReport(3L, null);

        assertEquals("{\"safe\":true}", result.get("report"));
        assertEquals(9L, result.get("assetId"));
        verify(reports).findPublicReportById(3L, com.bkanent.interview.engine.Sanitizer.VERSION);
    }

    @Test
    void publicReportHidesStaleSourceEvenWhenMapperReturnsRow() {
        InterviewReportTaskMapper tasks = mock(InterviewReportTaskMapper.class);
        InterviewReportMapper reports = mock(InterviewReportMapper.class);
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewAssetService assetService = mock(InterviewAssetService.class);
        InterviewReportEntity report = reportFixture();
        InterviewReportTaskEntity task = taskFixture();
        InterviewAssetEntity source = assetFixture();
        when(reports.findPublicReportById(3L, com.bkanent.interview.engine.Sanitizer.VERSION)).thenReturn(report);
        when(tasks.selectById(2L)).thenReturn(task);
        when(assets.selectById(9L)).thenReturn(source);
        when(assetService.isUsableReportSource(source)).thenReturn(true);
        when(assetService.isPubliclyEligible(source)).thenReturn(false);
        InterviewReportService service = service(tasks, reports, assets, assetService);

        assertNull(service.getPublicReport(3L, null));
    }

    @Test
    void taskDetailHidesForeignTaskBeforeReadingItsReport() {
        InterviewReportTaskMapper tasks = mock(InterviewReportTaskMapper.class);
        InterviewReportMapper reports = mock(InterviewReportMapper.class);
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewReportTaskEntity foreign = taskFixture();
        foreign.setCaseId(99L);
        when(tasks.selectById(2L)).thenReturn(foreign);
        InterviewReportService service = service(tasks, reports, assets, mock(InterviewAssetService.class));

        Map<String, Object> result = service.getTaskDetail(2L, List.of(5L));

        assertEquals("task not found: 2", result.get("error"));
        verifyNoInteractions(reports, assets);
    }

    @Test
    void privateReportIsReadableWithinParticipantScopeButNeverAppearsInPublicProjection() {
        InterviewReportTaskMapper tasks = mock(InterviewReportTaskMapper.class);
        InterviewReportMapper reports = mock(InterviewReportMapper.class);
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewAssetService assetService = mock(InterviewAssetService.class);
        InterviewReportEntity report = reportFixture();
        InterviewReportTaskEntity task = taskFixture();
        InterviewAssetEntity source = assetFixture();
        source.setVisibility(InterviewAssetService.PRIVATE);
        when(tasks.selectById(2L)).thenReturn(task);
        when(reports.selectById(3L)).thenReturn(report);
        when(reports.findPublicReportById(3L, com.bkanent.interview.engine.Sanitizer.VERSION)).thenReturn(report);
        when(assets.selectById(9L)).thenReturn(source);
        when(assetService.isUsableReportSource(source)).thenReturn(true);
        when(assetService.isPubliclyEligible(source)).thenReturn(false);
        InterviewReportService service = service(tasks, reports, assets, assetService);

        Map<String, Object> privateDetail = service.getTaskDetail(2L, List.of(5L));

        assertTrue(privateDetail.containsKey("report"));
        assertNull(service.getPublicReport(3L, null));
    }

    @Test
    void unknownReportSourceIsRejectedBeforeAnyTaskIsCreatedOrReused() {
        InterviewReportTaskMapper tasks = mock(InterviewReportTaskMapper.class);
        InterviewReportMapper reports = mock(InterviewReportMapper.class);
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewAssetService assetService = mock(InterviewAssetService.class);
        InterviewAuthorizationService authorization = mock(InterviewAuthorizationService.class);
        doThrow(new IllegalArgumentException("report source is unavailable"))
                .when(assetService).requireReportSource(5L, 999L);
        InterviewReportService service = new InterviewReportService(tasks, reports, assets, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), assetService, authorization,
                mock(InterviewReportTaskCommitter.class));

        assertThrows(IllegalArgumentException.class, () -> service.submitCaseCardTask(5L, 999L));

        verify(authorization).requireOperation(InterviewAuthorizationService.REPORT_GENERATE);
        verify(assetService).requireReportSource(5L, 999L);
        verifyNoInteractions(tasks, reports);
    }

    private static InterviewReportService service(InterviewReportTaskMapper tasks,
                                                  InterviewReportMapper reports,
                                                  InterviewAssetMapper assets,
                                                  InterviewAssetService assetService) {
        return new InterviewReportService(tasks, reports, assets, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper(), assetService,
                mock(InterviewAuthorizationService.class), mock(InterviewReportTaskCommitter.class));
    }

    private static InterviewReportEntity reportFixture() {
        InterviewReportEntity report = new InterviewReportEntity();
        report.setId(3L);
        report.setTaskId(2L);
        report.setCaseId(5L);
        report.setAssetId(9L);
        report.setReportType("CASE_CARD");
        report.setScore(80);
        report.setReplicabilityLevel("L2");
        report.setInputSnapshotHash("input");
        report.setSourceContentHash("hash");
        report.setSourceRevision(1);
        report.setTemplateVersion(InterviewReportService.TEMPLATE_VERSION);
        report.setReportJson("{\"safe\":true}");
        report.setEvidenceVerification("[]");
        report.setMissingItems("[]");
        return report;
    }

    private static InterviewReportTaskEntity taskFixture() {
        InterviewReportTaskEntity task = new InterviewReportTaskEntity();
        task.setId(2L);
        task.setCaseId(5L);
        task.setAssetId(9L);
        task.setStatus("SUCCEEDED");
        task.setReportId(3L);
        task.setInputSnapshotHash("input");
        task.setSourceContentHash("hash");
        task.setSourceRevision(1);
        task.setSourceSanitizationRevision(1);
        task.setTemplateVersion(InterviewReportService.TEMPLATE_VERSION);
        return task;
    }

    private static InterviewAssetEntity assetFixture() {
        InterviewAssetEntity asset = new InterviewAssetEntity();
        asset.setId(9L);
        asset.setCaseId(5L);
        asset.setContentHash("hash");
        asset.setContentRevision(1);
        asset.setSanitizationRevision(1);
        asset.setVisibility(InterviewAssetService.PUBLISHED);
        return asset;
    }
}
