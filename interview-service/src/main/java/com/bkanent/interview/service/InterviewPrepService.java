package com.bkanent.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.interview.config.InterviewRuntimeProperties;
import com.bkanent.interview.entity.InterviewCaseEntity;
import com.bkanent.interview.entity.InterviewPrepCardEntity;
import com.bkanent.interview.entity.InterviewQuestionEntity;
import com.bkanent.interview.entity.InterviewSessionEntity;
import com.bkanent.interview.engine.OutlineRouter;
import com.bkanent.interview.mapper.InterviewCaseMapper;
import com.bkanent.interview.mapper.InterviewPrepCardMapper;
import com.bkanent.interview.mapper.InterviewQuestionMapper;
import com.bkanent.interview.mapper.InterviewSessionMapper;
import com.bkanent.interview.runtime.InterviewSessionStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开台领域服务：双入口（A2A / 表单 REST）共用同一实现，落齐对齐的
 * 元数据字段，保证 Supervisor 按创建人检索不漏直开场次。
 */
@Service
public class InterviewPrepService {

    private static final Logger log = LoggerFactory.getLogger(InterviewPrepService.class);

    private final InterviewCaseMapper caseMapper;
    private final InterviewPrepCardMapper prepCardMapper;
    private final InterviewQuestionMapper questionMapper;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewRuntimeProperties runtimeProperties;
    private final InterviewSessionStateMachine stateMachine;

    public InterviewPrepService(InterviewCaseMapper caseMapper,
                                InterviewPrepCardMapper prepCardMapper,
                                InterviewQuestionMapper questionMapper,
                                InterviewSessionMapper sessionMapper,
                                InterviewRuntimeProperties runtimeProperties,
                                InterviewSessionStateMachine stateMachine) {
        this.caseMapper = caseMapper;
        this.prepCardMapper = prepCardMapper;
        this.questionMapper = questionMapper;
        this.sessionMapper = sessionMapper;
        this.runtimeProperties = runtimeProperties;
        this.stateMachine = stateMachine;
    }

    /**
     * 开台：案例 + 提纲路由 + 访前基础卡 + DRAFT 会话。
     */
    public Map<String, Object> openCase(String scene, String respondentRole, String caseStatus,
                                        String objective, String divisionName, String regionName,
                                        String businessDistrict, Integer referenceMinutes,
                                        String entrySource, String creatorWorkNo) {
        OutlineRouter.RouteResult route = OutlineRouter.route(scene, respondentRole, caseStatus);

        InterviewCaseEntity caseEntity = new InterviewCaseEntity();
        caseEntity.setScene(scene);
        caseEntity.setCaseStatus(caseStatus == null ? "active" : caseStatus);
        caseEntity.setRespondentRole(respondentRole);
        caseEntity.setObjective(objective);
        caseEntity.setDivisionName(divisionName);
        caseEntity.setRegionName(regionName);
        caseEntity.setBusinessDistrict(businessDistrict);
        caseEntity.setNoSuccessPreset(route.noSuccessPreset() ? 1 : 0);
        caseEntity.setCreatorWorkNo(creatorWorkNo);
        caseMapper.insert(caseEntity);

        InterviewPrepCardEntity prepCard = new InterviewPrepCardEntity();
        prepCard.setCaseId(caseEntity.getId());
        prepCard.setCompleteness("LOW");
        prepCard.setQuestionStrategy("EXPLORE");
        prepCardMapper.insert(prepCard);

        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setCaseId(caseEntity.getId());
        session.setMode("AI_LEAD");
        session.setStatus(InterviewSessionStateMachine.DRAFT);
        session.setVersion(0);
        session.setReferenceMinutes(referenceMinutes);
        session.setEngineType("XIAOZHI");
        session.setEntrySource(entrySource == null ? "A2A" : entrySource);
        session.setCreatorWorkNo(creatorWorkNo);
        session.setClosingLocked(0);
        sessionMapper.insert(session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", caseEntity.getId());
        result.put("sessionId", session.getId());
        result.put("outlineRoute", route.outlineRoute());
        result.put("noSuccessPreset", route.noSuccessPreset());
        result.put("mustCollectItems", route.mustCollectItems());
        result.put("questionBudget", runtimeProperties.questionBudgetFor(referenceMinutes == null ? 30 : referenceMinutes));
        result.put("entrySource", session.getEntrySource());
        log.info("Interview case opened: caseId={}, session={}, route={}, preset={}, entry={}",
                caseEntity.getId(), session.getId(), route.outlineRoute(), route.noSuccessPreset(), entrySource);
        return result;
    }

    /**
     * 题目确认制：确认候选题（只有确认题进入访谈，未选题不计未回答）。
     */
    public int confirmQuestions(Long caseId, List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            throw new IllegalArgumentException("questionIds must not be empty");
        }
        List<InterviewQuestionEntity> questions = questionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestionEntity>()
                        .eq(InterviewQuestionEntity::getCaseId, caseId));
        int confirmed = 0;
        for (InterviewQuestionEntity q : questions) {
            boolean shouldConfirm = questionIds.contains(q.getId());
            InterviewQuestionEntity update = new InterviewQuestionEntity();
            update.setId(q.getId());
            update.setConfirmed(shouldConfirm ? 1 : 0);
            questionMapper.updateById(update);
            if (shouldConfirm) {
                confirmed++;
            }
        }
        if (confirmed == 0) {
            throw new IllegalArgumentException("none of the questionIds belong to case " + caseId);
        }
        InterviewSessionEntity session = sessionMapper.selectOne(
                new LambdaQueryWrapper<InterviewSessionEntity>()
                        .eq(InterviewSessionEntity::getCaseId, caseId)
                        .orderByDesc(InterviewSessionEntity::getId)
                        .last("LIMIT 1"));
        if (session != null && InterviewSessionStateMachine.DRAFT.equals(session.getStatus())) {
            // 治理面推进 DRAFT → QUESTIONS_CONFIRMED
            stateMachine.transition(session.getId(), InterviewSessionStateMachine.QUESTIONS_CONFIRMED,
                    InterviewSessionStateMachine.Actor.GOVERNANCE);
        }
        return confirmed;
    }

    /** 当前题（按预算序号的第一个未答确认题）。 */
    public InterviewQuestionEntity currentQuestion(Long caseId) {
        return questionMapper.selectList(new LambdaQueryWrapper<InterviewQuestionEntity>()
                        .eq(InterviewQuestionEntity::getCaseId, caseId)
                        .eq(InterviewQuestionEntity::getConfirmed, 1)
                        .eq(InterviewQuestionEntity::getAnswerStatus, "PENDING")
                        .orderByAsc(InterviewQuestionEntity::getSeqNo)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    /** 已确认题数。 */
    public long confirmedCount(Long caseId) {
        return questionMapper.selectCount(new LambdaQueryWrapper<InterviewQuestionEntity>()
                .eq(InterviewQuestionEntity::getCaseId, caseId)
                .eq(InterviewQuestionEntity::getConfirmed, 1));
    }

    /** 已答题数。 */
    public long answeredCount(Long caseId) {
        return questionMapper.selectCount(new LambdaQueryWrapper<InterviewQuestionEntity>()
                .eq(InterviewQuestionEntity::getCaseId, caseId)
                .eq(InterviewQuestionEntity::getConfirmed, 1)
                .eq(InterviewQuestionEntity::getAnswerStatus, "ANSWERED"));
    }
}
