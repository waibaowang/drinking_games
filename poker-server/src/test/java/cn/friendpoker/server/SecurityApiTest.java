package cn.friendpoker.server;

import cn.friendpoker.server.user.UserAccount;
import cn.friendpoker.server.user.UserAccountRepository;
import cn.friendpoker.server.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
class SecurityApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwords;
    private String admin;
    private String player;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = "admin_" + suffix;
        player = "player_" + suffix;
        users.save(new UserAccount(admin, passwords.encode("password123"), "Admin", UserRole.ADMIN));
        users.save(new UserAccount(player, passwords.encode("password123"), "Player", UserRole.PLAYER));
    }

    @Test
    void registrationIsGoneAndAdminBoundaryIsEnforced() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).with(user(player).roles("PLAYER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/users").with(user(player).roles("PLAYER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(user(admin).roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void disabledAccountLosesExistingHttpAccess() throws Exception {
        UserAccount account = users.findByUsernameIgnoreCase(player).orElseThrow();
        account.setEnabled(false);
        users.saveAndFlush(account);

        mvc.perform(get("/api/auth/me").with(user(player).roles("PLAYER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanCreateAPlayerButPlayerCannot() throws Exception {
        String body = "{\"username\":\"new_%s\",\"password\":\"password123\",\"displayName\":\"新玩家\"}"
                .formatted(UUID.randomUUID().toString().substring(0, 6));
        mvc.perform(post("/api/admin/users").with(csrf()).with(user(player).roles("PLAYER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users").with(csrf()).with(user(admin).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("PLAYER"));
    }
}
