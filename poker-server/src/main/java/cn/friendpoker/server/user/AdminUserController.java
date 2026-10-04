package cn.friendpoker.server.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(UserAccountRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<UserView> list() {
        return users.findAll().stream().sorted(Comparator.comparing(UserAccount::getUsername))
                .map(UserView::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UserView create(@Valid @RequestBody CreateUserRequest body) {
        String username = body.username().toLowerCase(Locale.ROOT);
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("用户名已被使用");
        }
        String displayName = body.displayName().isBlank() ? username : body.displayName().trim();
        return UserView.from(users.save(new UserAccount(username, passwordEncoder.encode(body.password()),
                displayName, UserRole.PLAYER)));
    }

    @PatchMapping("/{username}")
    @Transactional
    public UserView update(@PathVariable String username, @Valid @RequestBody UpdateUserRequest body,
                           Principal principal) {
        UserAccount account = users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (account.getUsername().equalsIgnoreCase(principal.getName()) && !body.enabled()) {
            throw new IllegalArgumentException("不能禁用当前登录的管理员");
        }
        account.setEnabled(body.enabled());
        return UserView.from(account);
    }

    @PostMapping("/{username}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void resetPassword(@PathVariable String username, @Valid @RequestBody ResetPasswordRequest body) {
        UserAccount account = users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        account.resetPassword(passwordEncoder.encode(body.password()));
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,20}", message = "用户名只能包含字母、数字和下划线，长度3到20位") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 20) String displayName) {
        public CreateUserRequest { displayName = displayName == null ? "" : displayName; }
    }
    public record UpdateUserRequest(boolean enabled) {}
    public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 72) String password) {}
}
