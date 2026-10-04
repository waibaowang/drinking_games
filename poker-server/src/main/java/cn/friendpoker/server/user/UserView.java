package cn.friendpoker.server.user;

public record UserView(String username, String displayName, UserRole role, boolean enabled) {
    public static UserView from(UserAccount account) {
        return new UserView(account.getUsername(), account.getDisplayName(), account.getRole(), account.isEnabled());
    }
}
