package cn.friendpoker.server.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_accounts")
public class UserAccount {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true, length = 32)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 32)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "auth_version", nullable = false)
    private long authVersion;

    protected UserAccount() {
    }

    public UserAccount(String username, String passwordHash, String displayName, UserRole role) {
        this.id = UUID.randomUUID().toString();
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.createdAt = Instant.now();
        this.role = role.name();
        this.enabled = true;
        this.updatedAt = this.createdAt;
        this.authVersion = 0;
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Instant getCreatedAt() { return createdAt; }
    public UserRole getRole() { return UserRole.valueOf(role); }
    public boolean isEnabled() { return enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getAuthVersion() { return authVersion; }

    public void updateProfile(String displayName) {
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }

    public void resetPassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
        this.authVersion++;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
        this.authVersion++;
    }

    public void setRole(UserRole role) {
        this.role = role.name();
        this.updatedAt = Instant.now();
        this.authVersion++;
    }
}
