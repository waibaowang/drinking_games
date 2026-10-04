package cn.friendpoker.server.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(UserAccountRepository users, PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/register")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (users.existsByUsernameIgnoreCase(body.username())) {
            throw new IllegalArgumentException("用户名已被使用");
        }
        String displayName = body.displayName().isBlank() ? body.username() : body.displayName().trim();
        users.save(new UserAccount(body.username().toLowerCase(), passwordEncoder.encode(body.password()), displayName));
        authenticate(body.username(), body.password(), request, response);
        return new UserView(body.username().toLowerCase(), displayName);
    }

    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        authenticate(body.username(), body.password(), request, response);
        UserAccount account = users.findByUsernameIgnoreCase(body.username()).orElseThrow();
        return new UserView(account.getUsername(), account.getDisplayName());
    }

    @GetMapping("/me")
    public UserView me(Principal principal) {
        UserAccount account = users.findByUsernameIgnoreCase(principal.getName()).orElseThrow();
        return new UserView(account.getUsername(), account.getDisplayName());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }

    private void authenticate(String username, String password, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,20}", message = "用户名只能包含字母、数字和下划线，长度3到20位") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 20) String displayName) {
        public RegisterRequest {
            displayName = displayName == null ? "" : displayName;
        }
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record UserView(String username, String displayName) {}
}

