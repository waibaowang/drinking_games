package cn.friendpoker.server.config;

import cn.friendpoker.server.user.AccountPrincipal;
import cn.friendpoker.server.user.UserAccount;
import cn.friendpoker.server.user.UserAccountRepository;
import cn.friendpoker.server.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketConfigTest {
    @Test
    void oldConnectionStaysInvalidAfterDisableAndReenable() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        UserAccount account = new UserAccount("player", "hash", "Player", UserRole.PLAYER);
        when(users.findByUsernameIgnoreCase("player")).thenReturn(Optional.of(account));
        WebSocketConfig config = new WebSocketConfig("http://localhost:5173", users);
        var principal = new AccountPrincipal("player", "hash", UserRole.PLAYER, true, 0);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());

        assertTrue(config.valid(authentication));
        account.setEnabled(false);
        assertFalse(config.valid(authentication));
        account.setEnabled(true);
        assertFalse(config.valid(authentication));
    }
}
