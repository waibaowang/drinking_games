package cn.friendpoker.server.user;

import cn.friendpoker.server.common.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();

    public void check(String ip) {
        Instant now = Instant.now();
        attempts.compute(ip, (key, current) -> {
            Window next = current == null || current.startedAt().plus(WINDOW).isBefore(now)
                    ? new Window(now, 1) : new Window(current.startedAt(), current.count() + 1);
            if (next.count() > MAX_ATTEMPTS) {
                throw new TooManyRequestsException("登录尝试过于频繁，请稍后再试");
            }
            return next;
        });
    }

    public void reset(String ip) {
        attempts.remove(ip);
    }

    // ponytail: A single-node fixed window is intentional for this <=20-user deployment.
    private record Window(Instant startedAt, int count) {}
}
