package com.bkanent.interview.runtime;

/** 会话入口页所需的最小监播信息。 */
public record InterviewMonitorResponse(
        Long sessionId,
        Long caseId,
        String status,
        String mode,
        String currentQuestion,
        Long currentQuestionId,
        long confirmedQuestions,
        long answeredQuestions,
        boolean closingLocked
) {
}
