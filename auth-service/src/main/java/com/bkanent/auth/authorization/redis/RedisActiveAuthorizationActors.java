package com.bkanent.auth.authorization.redis;

import com.bkanent.auth.config.AuthAuthorizationRedisProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded shared index permits other Auth instances and restarted publishers to recover active heads. */
public final class RedisActiveAuthorizationActors {
    private final StringRedisTemplate redis;
    private final AuthAuthorizationRedisProperties settings;
    private final Clock clock;
    private final DefaultRedisScript<Long> register;
    private final DefaultRedisScript<List> page;

    public RedisActiveAuthorizationActors(StringRedisTemplate redis, AuthAuthorizationRedisProperties settings,
                                         Clock clock) {
        settings.validateEnabled();
        this.redis = Objects.requireNonNull(redis);
        this.settings = settings;
        this.clock = Objects.requireNonNull(clock);
        this.register = new DefaultRedisScript<>();
        register.setLocation(new ClassPathResource("authz/active-actors.lua"));
        register.setResultType(Long.class);
        this.page = new DefaultRedisScript<>();
        page.setLocation(new ClassPathResource("authz/active-actors.lua"));
        page.setResultType(List.class);
    }

    public boolean register(long userId, Instant runHardDeadline) {
        RedisAuthorizationWriter.requireOutsideTransaction();
        Instant now = clock.instant();
        if (userId <= 0 || runHardDeadline == null || !runHardDeadline.isAfter(now)) return false;
        Instant deadline = runHardDeadline.isAfter(now.plusSeconds(300)) ? now.plusSeconds(300) : runHardDeadline;
        Long result = redis.execute(register, List.of(key()), "REGISTER", Long.toString(userId),
                Long.toString(deadline.getEpochSecond()), Integer.toString(settings.getMaxActiveActors()));
        if (result == null) throw new IllegalStateException("Active actor registration was not confirmed");
        return result == 1;
    }

    /** Each command returns at most renewalBatch actors, never an unbounded keyspace traversal. */
    public List<Actor> page(int offset) {
        RedisAuthorizationWriter.requireOutsideTransaction();
        if (offset < 0 || offset >= settings.getMaxActiveActors()) throw new IllegalArgumentException("Invalid page");
        List<?> result = redis.execute(page, List.of(key()), "PAGE", Integer.toString(offset),
                Integer.toString(settings.getRenewalBatch()), Integer.toString(settings.getMaxActiveActors()));
        if (result == null || result.size() % 2 != 0 || result.size() > settings.getRenewalBatch() * 2) {
            throw new IllegalStateException("Invalid bounded active actor response");
        }
        List<Actor> actors = new ArrayList<>();
        for (int index = 0; index < result.size(); index += 2) {
            try {
                long id = Long.parseLong(result.get(index).toString());
                long expiry = new java.math.BigDecimal(result.get(index + 1).toString()).longValueExact();
                Instant deadline = Instant.ofEpochSecond(expiry);
                if (id > 0 && deadline.isAfter(clock.instant()) && !deadline.isAfter(clock.instant().plusSeconds(300))) {
                    actors.add(new Actor(id, deadline));
                }
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                throw new IllegalStateException("Invalid active actor index value");
            }
        }
        return List.copyOf(actors);
    }

    public String key() { return settings.getNamespace() + ":{active}:actors"; }
    public record Actor(long userId, Instant expiresAt) { }
}
