package com.bkanent.businessreview.persistence;

import com.bkanent.businessreview.domain.ReviewDomain.*;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository {
    void create(ReviewMatter matter, ReviewObservation observation);
    Optional<ReviewMatter> findMatter(String reviewId);
    List<ReviewMatter> listMatters(long storeId, int limit, int offset);
    void markPendingReview(String reviewId);
    Optional<ReviewPlan> findPlan(String reviewId, int version);
    Optional<ReviewPlan> findLatestPlan(String reviewId);
    List<ReviewPlan> listPlans(String reviewId);
    int latestDiagnosisRevision(String reviewId);
    void appendPlan(ReviewPlan plan, long expectedMatterVersion);
    ConfirmationReceipt confirm(ReviewPlan plan, String requestId, String parameterHash, long actorId);
    Optional<ReviewAction> findAction(String actionId);
    ReviewAction recordUserFeedback(String reviewId, String actionId, ActionStatus nextStatus, String note,
                                    long actorId, long expectedActionVersion, String requestId, String payloadHash);
    ReviewObservation saveObservation(ReviewObservation observation);
    Optional<ReviewObservation> findLatestObservation(String reviewId);
    List<ReviewAction> listActions(String reviewId);
}
