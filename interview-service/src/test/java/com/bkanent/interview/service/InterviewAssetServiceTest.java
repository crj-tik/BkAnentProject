package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bkanent.interview.engine.Sanitizer;
import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewCaseEntity;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewCaseMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InterviewAssetServiceTest {
    @Test
    void transcriptSubmissionSanitizesBeforeCreatingPrivateAssetAndValidatesCaseOwner() {
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewCaseMapper cases = mock(InterviewCaseMapper.class);
        InterviewSessionMapper sessions = mock(InterviewSessionMapper.class);
        InterviewAuthorizationService authorization = mock(InterviewAuthorizationService.class);
        InterviewCaseEntity caseEntity = new InterviewCaseEntity();
        caseEntity.setId(5L);
        caseEntity.setScene("AGENT_DEAL");
        caseEntity.setRespondentRole("CUSTOMER");
        caseEntity.setObjective("复盘客户决策");
        caseEntity.setDivisionName("交易中心");
        caseEntity.setRegionName("天河大区");
        caseEntity.setBusinessDistrict("珠江新城");
        caseEntity.setRespondentName("张三");
        caseEntity.setCreatorWorkNo("A001");
        when(cases.selectById(5L)).thenReturn(caseEntity);
        InterviewAssetService service = new InterviewAssetService(assets, cases, sessions, authorization);
        String ticket = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signaturevalue123456789";
        String raw = "张三说，手机号13800138000，ticket=" + ticket;

        InterviewAssetEntity saved = service.submitTranscript(5L, "访谈逐字稿", raw);

        verify(authorization).requireInitiatorForCase(5L, InterviewAssetService.SUBMIT);
        verify(assets).insert(any(InterviewAssetEntity.class));
        assertTrue(saved.getRawContent().startsWith("张三说，手机号13800138000，"));
        assertFalse(saved.getRawContent().contains(ticket));
        assertTrue(saved.getRawContent().contains("[凭据已移除]"));
        assertEquals(InterviewAssetService.PRIVATE, saved.getVisibility());
        assertEquals(Sanitizer.VERSION, saved.getSanitizationRevision());
        assertEquals(1, saved.getContentRevision());
        assertEquals(InterviewAssetService.sha256(saved.getContent()), saved.getContentHash());
        assertFalse(saved.getContent().contains("张三"));
        assertFalse(saved.getContent().contains("13800138000"));
        assertFalse(saved.getContent().contains(ticket));
    }

    @Test
    void regradeUsesRealMetadataAndDemotesPublishedAssetToPrivate() {
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewCaseMapper cases = mock(InterviewCaseMapper.class);
        InterviewSessionMapper sessions = mock(InterviewSessionMapper.class);
        InterviewAuthorizationService authorization = mock(InterviewAuthorizationService.class);
        InterviewCaseEntity caseEntity = new InterviewCaseEntity();
        caseEntity.setId(5L);
        InterviewAssetEntity asset = new InterviewAssetEntity();
        asset.setId(9L);
        asset.setCaseId(5L);
        asset.setSource("UPLOAD");
        asset.setContent("受访者：" + "内容".repeat(500));
        asset.setContentHash(InterviewAssetService.sha256(asset.getContent()));
        asset.setContentRevision(1);
        asset.setSanitizationRevision(Sanitizer.VERSION);
        asset.setVisibility(InterviewAssetService.PUBLISHED);
        asset.setPublicationRevision(3);
        asset.setTurnCount(10);
        asset.setWordCount(1000);
        when(assets.selectById(9L)).thenReturn(asset);
        when(cases.selectById(5L)).thenReturn(caseEntity);
        when(assets.update(any(InterviewAssetEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        InterviewAssetService service = new InterviewAssetService(assets, cases, sessions, authorization);

        InterviewAssetEntity result = service.regrade(9L);

        verify(authorization).requireInitiatorForCase(5L, InterviewAssetService.REGRADE);
        assertEquals("L1", result.getArchiveGrade());
        assertEquals(InterviewAssetService.PRIVATE, result.getVisibility());
        assertEquals(4, result.getPublicationRevision());
        verify(assets).update(any(InterviewAssetEntity.class), any(LambdaUpdateWrapper.class));
    }
}
