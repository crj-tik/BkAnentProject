package com.bkanent.agent.service;

public interface SupervisorRateLimiter {

    boolean tryAcquire(String key, int limit, long windowMs);
}
