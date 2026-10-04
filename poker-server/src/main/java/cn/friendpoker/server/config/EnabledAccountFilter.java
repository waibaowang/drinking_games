package cn.friendpoker.server.config;

import cn.friendpoker.server.user.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class EnabledAccountFilter extends OncePerRequestFilter {
    private final UserAccountRepository users;

    public EnabledAccountFilter(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            boolean enabled = users.findByUsernameIgnoreCase(authentication.getName())
                    .map(account -> account.isEnabled()).orElse(false);
            if (!enabled) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "账号已禁用");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
