package com.bkanent.rag.config;

import com.bkanent.common.rag.*;
import com.bkanent.rag.index.RagIndexWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "rag.index.worker.enabled", havingValue = "true")
public class RagIndexScheduling {
    private static final Logger LOG = LoggerFactory.getLogger(RagIndexScheduling.class);
    private final RagIndexWorker worker;
    private final int batch;
    public RagIndexScheduling(RagIndexWorker worker, @Value("${rag.index.worker.batch-size:20}") int batch) {
        if (batch < 1 || batch > 100) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "batchSize");
        this.worker = worker; this.batch = batch;
    }
    @Scheduled(fixedDelayString = "${rag.index.worker.delay-ms:1000}", initialDelayString = "${rag.index.worker.delay-ms:1000}")
    public void apply() { guarded(() -> worker.runBatch(batch)); }
    @Scheduled(fixedDelayString = "${rag.index.worker.reconcile-delay-ms:30000}", initialDelayString = "${rag.index.worker.reconcile-delay-ms:30000}")
    public void reconcile() { guarded(() -> worker.reconcileBatch(batch)); }
    private static void guarded(Runnable action) {
        try { action.run(); }
        catch (RagException failure) { LOG.warn("RAG worker failed code={}", failure.code()); }
        catch (RuntimeException failure) { LOG.warn("RAG worker failed code={}", RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
}
