package cn.friendpoker.server.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserAccountRepository users;
    private final AuthenticationManager authenticationManager;
    private final LoginRateLimiter rateLimiter;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(UserAccountRepository users, AuthenticationManager authenticationManager,
                          LoginRateLimiter rateLimiter) {
        this.users = users;
        this.authenticationManager = authenticationManager;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(org.springframework.security.web.csrf.CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                          HttpServletResponse response) {
        String ip = request.getRemoteAddr();
        rateLimiter.check(ip);
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        if (request.getSession(false) != null) request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        rateLimiter.reset(ip);
        return UserView.from(users.findByUsernameIgnoreCase(authentication.getName()).orElseThrow());
    }

    @GetMapping("/me")
    public UserView me(Principal principal) {
        return UserView.from(users.findByUsernameIgnoreCase(principal.getName()).orElseThrow());
    }

    @PostMapping("/logout")
    @ResponseStatus(NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
}
