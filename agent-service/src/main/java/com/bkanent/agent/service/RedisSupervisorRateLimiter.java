package com.bkanent.agent.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnProperty(prefix = "agent.distributed.rate-limit", name = "provider", havingValue = "redis")
public class RedisSupervisorRateLimiter implements SupervisorRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;

    public RedisSupervisorRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryAcquire(String key, int limit, long windowMs) {
        Long current = stringRedisTemplate.opsForValue().increment(key);
        if (current == null) {
            return false;
        }
        if (current == 1L) {
            stringRedisTemplate.expire(key, Duration.ofMillis(windowMs));
        }
        return current <= limit;
    }
}
