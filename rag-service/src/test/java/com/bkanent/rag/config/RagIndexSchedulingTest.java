package com.bkanent.rag.config;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.rag.index.RagIndexWorker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Hot-toggle gate: scheduling always assembles and re-reads the flag every tick. A disabled worker
 * stays silent; flipping the flag mid-flight lets the current batch drain and stops future batches.
 */
class RagIndexSchedulingTest {
    @Test void disabledWorkerNeverRunsAndEnabledFlagIsReReadPerTick() {
        var worker = mock(RagIndexWorker.class);
        var properties = new RagIndexSchedulingProperties();
        var scheduling = new RagIndexScheduling(worker, properties);
        properties.setEnabled(false);
        scheduling.apply();
        scheduling.reconcile();
        verifyNoInteractions(worker);
        properties.setEnabled(true);
        when(worker.runBatch(anyInt())).thenReturn(0);
        scheduling.apply();
        verify(worker, times(1)).runBatch(properties.getBatchSize());
        properties.setEnabled(false);
        scheduling.apply();
        scheduling.reconcile();
        verify(worker, times(1)).runBatch(anyInt());
        verify(worker, never()).reconcileBatch(anyInt());
    }
    @Test void inFlightBatchDrainsWhenDisabledMidRun() throws Exception {
        var worker = mock(RagIndexWorker.class);
        var properties = new RagIndexSchedulingProperties();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(worker.runBatch(anyInt())).thenAnswer(invocation -> {
            running.countDown();
            return release.await(5, TimeUnit.SECONDS) ? 1 : 0;
        });
        var scheduling = new RagIndexScheduling(worker, properties);
        properties.setEnabled(true);
        var thread = new Thread(scheduling::apply);
        thread.setDaemon(true);
        thread.start();
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        properties.setEnabled(false);
        release.countDown();
        thread.join(3000);
        assertThat(thread.isAlive()).isFalse();
        verify(worker, times(1)).runBatch(anyInt());
        scheduling.apply();
        scheduling.reconcile();
        verify(worker, times(1)).runBatch(anyInt());
        verify(worker, never()).reconcileBatch(anyInt());
    }
    @Test void invalidBatchSizeFailsStartup() {
        var properties = new RagIndexSchedulingProperties();
        properties.setBatchSize(0);
        assertThatThrownBy(() -> new RagIndexScheduling(mock(RagIndexWorker.class), properties))
                .isInstanceOfSatisfying(RagException.class, e -> assertThat(e.code()).isEqualTo(RagErrorCode.INVALID_ARGUMENT));
    }
}
