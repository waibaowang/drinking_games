package cn.friendpoker.server.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;

    public ProfileController(UserAccountRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @PatchMapping
    @Transactional
    public UserView update(@Valid @RequestBody ProfileRequest body, Principal principal) {
        UserAccount account = users.findByUsernameIgnoreCase(principal.getName()).orElseThrow();
        account.updateProfile(body.displayName().trim());
        return UserView.from(account);
    }

    @PostMapping("/password")
    @Transactional
    public void password(@Valid @RequestBody PasswordRequest body, Principal principal) {
        UserAccount account = users.findByUsernameIgnoreCase(principal.getName()).orElseThrow();
        if (!passwordEncoder.matches(body.currentPassword(), account.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码错误");
        }
        account.resetPassword(passwordEncoder.encode(body.newPassword()));
    }

    public record ProfileRequest(@NotBlank @Size(max = 20) String displayName) {}
    public record PasswordRequest(@NotBlank String currentPassword,
                                  @NotBlank @Size(min = 8, max = 72) String newPassword) {}
}
