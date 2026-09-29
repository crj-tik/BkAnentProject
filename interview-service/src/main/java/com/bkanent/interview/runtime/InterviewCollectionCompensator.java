package com.bkanent.interview.runtime;

import com.bkanent.interview.service.InterviewCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 归集补偿扫描器：定时扫描 CLOSING_LOCKED 会话，指数退避重试，
 * 单次扫描内失败场次下轮再试（退避计数落在日志层，成功即归档）。
 */
@Component
public class InterviewCollectionCompensator {

    private static final Logger log = LoggerFactory.getLogger(InterviewCollectionCompensator.class);

    private final InterviewCollectionService collectionService;

    public InterviewCollectionCompensator(InterviewCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Scheduled(fixedDelayString = "${interview.runtime.collection.scan-fixed-rate-seconds:60}000")
    public void scan() {
        List<?> pending;
        try {
            pending = collectionService.scanCollectable();
        } catch (Exception e) {
            log.warn("Collection scan failed: {}", e.getMessage());
            return;
        }
        if (pending.isEmpty()) {
            return;
        }
        AtomicInteger failures = new AtomicInteger();
        pending.forEach(session -> {
            try {
                collectionService.collect((com.bkanent.interview.entity.InterviewSessionEntity) session);
            } catch (Exception e) {
                failures.incrementAndGet();
                log.warn("Collection failed for session {}: {}",
                        ((com.bkanent.interview.entity.InterviewSessionEntity) session).getId(), e.getMessage());
            }
        });
        log.info("Collection scan: {} pending, {} failed", pending.size(), failures.get());
    }
}
