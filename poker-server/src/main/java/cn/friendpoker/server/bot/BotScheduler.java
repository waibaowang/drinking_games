package cn.friendpoker.server.bot;

import cn.friendpoker.server.user.AccountPrincipal;
import cn.friendpoker.server.user.UserAccountRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class BotScheduler {
    private final UserAccountRepository users;
    private final ScheduledExecutorService executor;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<String, Map<String, Subscription>> subscriptions = new HashMap<>();

    @Autowired
    public BotScheduler(UserAccountRepository users) {
        this(users, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "room-bot-actions");
            thread.setDaemon(true);
            return thread;
        }));
    }

    BotScheduler(UserAccountRepository users, ScheduledExecutorService executor) {
        this.users = users;
        this.executor = executor;
    }

    public synchronized void subscribe(String session, String subscription, UUID room, Principal user) {
        if (session == null || subscription == null || user == null) return;
        subscriptions.computeIfAbsent(session, ignored -> new HashMap<>())
                .put(subscription, new Subscription(room, user));
    }

    public synchronized Set<UUID> unsubscribe(String session, String subscription) {
        Map<String, Subscription> entries = subscriptions.get(session);
        if (entries == null) return Set.of();
        Set<UUID> affected;
        if (subscription == null) {
            affected = entries.values().stream().map(Subscription::room).collect(Collectors.toSet());
            subscriptions.remove(session);
        } else {
            Subscription removed = entries.remove(subscription);
            affected = removed == null ? Set.of() : Set.of(removed.room());
            if (entries.isEmpty()) subscriptions.remove(session);
        }
        return affected;
    }

    public synchronized boolean hasHuman(UUID room) {
        return subscriptions.values().stream().flatMap(entries -> entries.values().stream())
                .filter(entry -> entry.room().equals(room)).anyMatch(entry -> valid(entry.user()));
    }

    private boolean valid(Principal user) {
        return users.findByUsernameIgnoreCase(user.getName()).map(account -> account.isEnabled()
                && (!(user instanceof Authentication auth) || !(auth.getPrincipal() instanceof AccountPrincipal principal)
                || principal.authVersion() == account.getAuthVersion())).orElse(false);
    }

    public synchronized void schedule(UUID room, long version, long delay, Runnable operation) {
        Pending previous = pending.get(room);
        if (previous != null && previous.version == version) return;
        cancel(room);
        Pending next = new Pending(version);
        pending.put(room, next);
        next.future = executor.schedule(() -> {
            synchronized (this) {
                if (pending.get(room) != next) return;
                pending.remove(room);
            }
            operation.run();
        }, delay, TimeUnit.MILLISECONDS);
    }

    public synchronized void cancel(UUID room) {
        Pending previous = pending.remove(room);
        if (previous != null && previous.future != null) previous.future.cancel(false);
    }

    @PreDestroy
    public synchronized void shutdown() {
        pending.clear();
        subscriptions.clear();
        executor.shutdownNow();
    }

    private record Subscription(UUID room, Principal user) {}
    private static final class Pending {
        final long version;
        ScheduledFuture<?> future;
        Pending(long version) { this.version = version; }
    }
}
