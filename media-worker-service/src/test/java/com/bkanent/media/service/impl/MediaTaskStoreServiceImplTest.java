package com.bkanent.media.service.impl;

import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.media.model.MediaBackgroundPermit;
import com.bkanent.media.model.MediaTaskStatusEnum;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaTaskStoreServiceImplTest {
    @Test
    void taskIdOnlyLocatesTaskAndOwnerMustMatchSignedPermit() {
        var store = new MediaTaskStoreServiceImpl();
        var request = new MediaGenerateTaskRequest(101L, null, "media-worker-service", "LISTING_IMAGE",
                "prompt", List.of("living-room"), 1);
        var permit = permit("task-73", 73L);
        store.createQueuedTask("task-73", request, permit);

        assertThat(store.getTaskResult("task-73", "73", false).status())
                .isEqualTo(MediaTaskStatusEnum.QUEUED.name());
        assertThatThrownBy(() -> store.getTaskResult("task-73", "74", false))
                .isInstanceOf(AuthzException.class);

        assertThat(store.claimQueuedTask("task-73", permit).getResult().status())
                .isEqualTo(MediaTaskStatusEnum.RUNNING.name());
        assertThat(store.claimQueuedTask("task-73", permit)).isNull();
        assertThat(store.markSuccess("task-73", List.of("asset"), "asset", null, "done").status())
                .isEqualTo(MediaTaskStatusEnum.SUCCESS.name());
    }

    @Test
    void expiredPermitFailsQueuedTaskWithoutGivingWorkerAClaim() {
        var store = new MediaTaskStoreServiceImpl();
        var request = new MediaGenerateTaskRequest(101L, null, "media-worker-service", "LISTING_IMAGE",
                "prompt", List.of("living-room"), 1);
        var expiredPermit = permit("task-expired", 73L, -1);
        store.createQueuedTask("task-expired", request, expiredPermit);

        assertThat(store.claimQueuedTask("task-expired", expiredPermit)).isNull();
        assertThat(store.getTaskResult("task-expired", "73", false).status())
                .isEqualTo(MediaTaskStatusEnum.FAILED.name());
        assertThatThrownBy(() -> store.markSuccess("task-expired", List.of("asset"), "asset", null, "done"))
                .isInstanceOf(AuthzException.class);
    }

    private MediaBackgroundPermit permit(String taskId, long actorId) {
        return permit(taskId, actorId, 20);
    }

    private MediaBackgroundPermit permit(String taskId, long actorId, long permitLifetimeSeconds) {
        long now = Instant.now().getEpochSecond();
        String hash = "c".repeat(64);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var context = new AuthorizationContext(Long.toString(actorId), 1, 0, hash,
                List.of("listing.assets.bind.own", "media.generate.background", "media.task.submit.own"),
                "agent-service", "entry", "run", "session", hash, hash, nonce,
                now, now + 20, now + 20, Map.of("BROKER", 901L), AuthzContract.ALGORITHM, "auth-test", null);
        var freshness = new AuthzFreshness(Long.toString(actorId), 1, 0, AuthorizationStatus.ACTIVE, hash,
                now, now + 20, nonce, AuthzContract.ALGORITHM, "auth-test", null);
        return new MediaBackgroundPermit(taskId, "d".repeat(64), Long.toString(actorId), 901L, 101L,
                now + permitLifetimeSeconds, context, freshness, "media-test", "signature");
    }
}
