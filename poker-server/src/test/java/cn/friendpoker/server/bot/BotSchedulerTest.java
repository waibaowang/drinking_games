package cn.friendpoker.server.bot;

import cn.friendpoker.server.user.AccountPrincipal;
import cn.friendpoker.server.user.UserAccount;
import cn.friendpoker.server.user.UserAccountRepository;
import cn.friendpoker.server.user.UserRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotSchedulerTest {
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    private final BotScheduler scheduler = new BotScheduler(users, executor);

    @Test
    void schedulesOnlyOncePerPendingVersionAndIgnoresADuplicateCallback() {
        UUID room = UUID.randomUUID();
        Runnable first = mock(Runnable.class);
        Runnable duplicate = mock(Runnable.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(executor).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));

        scheduler.schedule(room, 10, 200, first);
        scheduler.schedule(room, 10, 800, duplicate);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(callback.capture(), eq(200L), eq(TimeUnit.MILLISECONDS));
        verify(future, never()).cancel(false);
        callback.getValue().run();
        callback.getValue().run();
        verify(first).run();
        verify(duplicate, never()).run();
    }

    @Test
    void replacesAnOlderVersionAndItsAlreadyQueuedCallbackBecomesHarmless() {
        UUID room = UUID.randomUUID();
        Runnable older = mock(Runnable.class);
        Runnable newer = mock(Runnable.class);
        ScheduledFuture<?> oldFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> newFuture = mock(ScheduledFuture.class);
        doReturn(oldFuture, newFuture).when(executor)
                .schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));

        scheduler.schedule(room, 10, 800, older);
        scheduler.schedule(room, 11, 200, newer);
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, times(2)).schedule(callbacks.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
        verify(oldFuture).cancel(false);
        callbacks.getAllValues().get(0).run();
        verify(older, never()).run();
        callbacks.getAllValues().get(1).run();
        verify(newer).run();
        verify(newFuture, never()).cancel(false);
    }

    @Test
    void cancellingARoomInvalidatesLateCallbacksWithoutAffectingAnotherRoom() {
        UUID cancelled = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Runnable cancelledOperation = mock(Runnable.class);
        Runnable otherOperation = mock(Runnable.class);
        ScheduledFuture<?> cancelledFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> otherFuture = mock(ScheduledFuture.class);
        doReturn(cancelledFuture, otherFuture).when(executor)
                .schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));

        scheduler.schedule(cancelled, 1, 200, cancelledOperation);
        scheduler.schedule(other, 1, 200, otherOperation);
        scheduler.cancel(cancelled);
        scheduler.cancel(cancelled);
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, times(2)).schedule(callbacks.capture(), eq(200L), eq(TimeUnit.MILLISECONDS));
        verify(cancelledFuture).cancel(false);
        callbacks.getAllValues().forEach(Runnable::run);
        verify(cancelledOperation, never()).run();
        verify(otherOperation).run();
        verify(otherFuture, never()).cancel(false);
    }

    @Test
    void multipleSessionsAndSubscriptionsKeepAHumanPresentUntilTheLastOneLeaves() {
        UUID room = UUID.randomUUID();
        UserAccount human = account("human");
        Principal authenticated = authentication(human);
        scheduler.subscribe("tab-1", "state", room, authenticated);
        scheduler.subscribe("tab-1", "second-state", room, authenticated);
        scheduler.subscribe("tab-2", "state", room, authenticated);
        assertTrue(scheduler.hasHuman(room));

        assertEquals(Set.of(room), scheduler.unsubscribe("tab-1", "state"));
        assertTrue(scheduler.hasHuman(room));
        assertEquals(Set.of(room), scheduler.unsubscribe("tab-2", null));
        assertTrue(scheduler.hasHuman(room));
        assertEquals(Set.of(room), scheduler.unsubscribe("tab-1", "second-state"));
        assertFalse(scheduler.hasHuman(room));
        assertTrue(scheduler.unsubscribe("tab-1", null).isEmpty());
    }

    @Test
    void disconnectClearsEverySubscriptionInTheSessionAndReportsDistinctAffectedRooms() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Principal authenticated = authentication(account("human"));
        scheduler.subscribe("tab", "first", first, authenticated);
        scheduler.subscribe("tab", "duplicate-first", first, authenticated);
        scheduler.subscribe("tab", "second", second, authenticated);

        assertEquals(Set.of(first, second), scheduler.unsubscribe("tab", null));
        assertFalse(scheduler.hasHuman(first));
        assertFalse(scheduler.hasHuman(second));
        assertTrue(scheduler.unsubscribe("unknown", null).isEmpty());
        assertTrue(scheduler.unsubscribe("tab", "unknown").isEmpty());
    }

    @Test
    void disabledReenabledAndPasswordResetAccountsDoNotReviveOldAuthenticatedSessions() {
        UUID room = UUID.randomUUID();
        UserAccount human = account("human");
        scheduler.subscribe("tab", "state", room, authentication(human));
        assertTrue(scheduler.hasHuman(room));
        human.setEnabled(false);
        assertFalse(scheduler.hasHuman(room));
        human.setEnabled(true);
        assertFalse(scheduler.hasHuman(room));
        scheduler.subscribe("tab", "state", room, authentication(human));
        assertTrue(scheduler.hasHuman(room));
        human.resetPassword("new-password-hash");
        assertFalse(scheduler.hasHuman(room));
        scheduler.subscribe("fresh-tab", "state", room, authentication(human));
        assertTrue(scheduler.hasHuman(room));
        when(users.findByUsernameIgnoreCase("human")).thenReturn(Optional.empty());
        assertFalse(scheduler.hasHuman(room));
    }

    @Test
    void ignoresIncompleteSubscriptionsAndKeepsPresenceIsolatedToItsRoom() {
        UUID room = UUID.randomUUID();
        Principal authenticated = authentication(account("human"));
        scheduler.subscribe(null, "state", room, authenticated);
        scheduler.subscribe("tab", null, room, authenticated);
        scheduler.subscribe("tab", "state", room, null);
        assertFalse(scheduler.hasHuman(room));
        scheduler.subscribe("tab", "state", room, authenticated);
        assertFalse(scheduler.hasHuman(UUID.randomUUID()));
        assertTrue(scheduler.hasHuman(room));
        assertTrue(scheduler.unsubscribe("tab", "nonexistent").isEmpty());
        assertTrue(scheduler.hasHuman(room));
    }

    @Test
    void shuttingDownClearsPresenceAndMakesAnAlreadyQueuedActionStale() {
        UUID room = UUID.randomUUID();
        scheduler.subscribe("tab", "state", room, authentication(account("human")));
        Runnable operation = mock(Runnable.class);
        doReturn(mock(ScheduledFuture.class)).when(executor)
                .schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
        scheduler.schedule(room, 1, 200, operation);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(callback.capture(), eq(200L), eq(TimeUnit.MILLISECONDS));

        scheduler.shutdown();
        verify(executor).shutdownNow();
        assertFalse(scheduler.hasHuman(room));
        callback.getValue().run();
        verify(operation, never()).run();
        assertTrue(scheduler.unsubscribe("tab", null).isEmpty());
    }

    private UserAccount account(String username) {
        UserAccount account = new UserAccount(username, "hash", username, UserRole.PLAYER);
        when(users.findByUsernameIgnoreCase(username)).thenReturn(Optional.of(account));
        return account;
    }

    private Principal authentication(UserAccount account) {
        AccountPrincipal principal = new AccountPrincipal(account.getUsername(), "hash", account.getRole(),
                account.isEnabled(), account.getAuthVersion());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
