package com.bkanent.interview.service;

import com.bkanent.interview.entity.InterviewAssetEntity;
import com.bkanent.interview.entity.InterviewReportEntity;
import com.bkanent.interview.entity.InterviewReportTaskEntity;
import com.bkanent.interview.mapper.InterviewAssetMapper;
import com.bkanent.interview.mapper.InterviewReportMapper;
import com.bkanent.interview.mapper.InterviewReportTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class InterviewReportTaskCommitterTest {
    @BeforeAll
    static void initLambdaMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "report-fence-test");
        TableInfoHelper.initTableInfo(assistant, InterviewReportTaskEntity.class);
        TableInfoHelper.initTableInfo(assistant, InterviewAssetEntity.class);
        LambdaUtils.installCache(TableInfoHelper.getTableInfo(InterviewReportTaskEntity.class));
        LambdaUtils.installCache(TableInfoHelper.getTableInfo(InterviewAssetEntity.class));
    }

    @Test
    void staleWorkerCannotCommitAfterLeaseTakeover() {
        InterviewReportTaskMapper tasks = mock(InterviewReportTaskMapper.class);
        InterviewReportMapper reports = mock(InterviewReportMapper.class);
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewAssetService assetService = mock(InterviewAssetService.class);
        when(tasks.selectOne(any())).thenReturn(null);
        InterviewReportTaskCommitter committer = new InterviewReportTaskCommitter(tasks, reports, assets, assetService);

        boolean committed = committer.complete(new InterviewReportTaskCommitter.Claim(11L, "old-owner", 2),
                new InterviewReportEntity());

        assertFalse(committed);
        verifyNoInteractions(reports, assets, assetService);
        verify(tasks, never()).update(isNull(), any());
    }

    @Test
    void changedSourceBlocksClaimWithoutPersistingDerivedReport() {
        InterviewReportTaskMapper tasks = mock(InterviewReportTaskMapper.class);
        InterviewReportMapper reports = mock(InterviewReportMapper.class);
        InterviewAssetMapper assets = mock(InterviewAssetMapper.class);
        InterviewAssetService assetService = mock(InterviewAssetService.class);
        InterviewReportTaskCommitter.Claim claim = new InterviewReportTaskCommitter.Claim(11L, "owner", 3);
        InterviewReportTaskEntity task = new InterviewReportTaskEntity();
        task.setId(11L);
        task.setCaseId(5L);
        task.setSessionId(7L);
        task.setAssetId(9L);
        task.setSourceContentHash("accepted-hash");
        task.setSourceRevision(1);
        task.setSourceSanitizationRevision(1);
        task.setTemplateVersion(InterviewReportService.TEMPLATE_VERSION);
        InterviewAssetEntity changed = new InterviewAssetEntity();
        changed.setId(9L);
        changed.setCaseId(5L);
        changed.setSessionId(7L);
        changed.setContentHash("new-hash");
        changed.setContentRevision(2);
        changed.setSanitizationRevision(1);
        when(tasks.selectOne(any())).thenReturn(task);
        when(assets.selectOne(any())).thenReturn(changed);
        when(tasks.update(isNull(), any())).thenReturn(1);
        InterviewReportTaskCommitter committer = new InterviewReportTaskCommitter(tasks, reports, assets, assetService);

        boolean committed = committer.complete(claim, new InterviewReportEntity());

        assertFalse(committed);
        verify(tasks).update(isNull(), any());
        verifyNoInteractions(reports);
        verifyNoInteractions(assetService);
    }
}
