package com.bkanent.rag.config;

import com.bkanent.common.rag.*;
import com.bkanent.rag.index.RagIndexWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduling always assembles; the enabled flag is evaluated per tick (parity with the memory
 * dispatcher), so pause is real: a disabled worker finishes its in-flight batch and stops.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(RagIndexSchedulingProperties.class)
public class RagIndexScheduling {
    private static final Logger LOG = LoggerFactory.getLogger(RagIndexScheduling.class);
    private final RagIndexWorker worker;
    private final RagIndexSchedulingProperties properties;
    public RagIndexScheduling(RagIndexWorker worker, RagIndexSchedulingProperties properties) {
        if (properties.getBatchSize() < 1 || properties.getBatchSize() > 100) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "batchSize");
        }
        this.worker = worker;
        this.properties = properties;
    }
    @Scheduled(fixedDelayString = "${rag.index.worker.delay-ms:1000}", initialDelayString = "${rag.index.worker.delay-ms:1000}")
    public void apply() { if (!properties.isEnabled()) return; guarded(() -> worker.runBatch(properties.getBatchSize())); }
    @Scheduled(fixedDelayString = "${rag.index.worker.reconcile-delay-ms:30000}", initialDelayString = "${rag.index.worker.reconcile-delay-ms:30000}")
    public void reconcile() { if (!properties.isEnabled()) return; guarded(() -> worker.reconcileBatch(properties.getBatchSize())); }
    private static void guarded(Runnable action) {
        try { action.run(); }
        catch (RagException failure) { LOG.warn("RAG worker failed code={}", failure.code()); }
        catch (RuntimeException failure) { LOG.warn("RAG worker failed code={}", RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
}
