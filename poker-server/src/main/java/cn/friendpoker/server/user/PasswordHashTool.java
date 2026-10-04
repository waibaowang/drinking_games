package cn.friendpoker.server.user;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.Console;
import java.util.Arrays;

public final class PasswordHashTool {
    private PasswordHashTool() {}

    public static void main(String[] args) {
        Console console = System.console();
        if (console == null) throw new IllegalStateException("请在交互式终端中运行此工具");
        char[] password = console.readPassword("请输入管理员密码（至少 12 位）：");
        try {
            if (password.length < 12) throw new IllegalArgumentException("管理员密码至少 12 位");
            System.out.println(new BCryptPasswordEncoder(12).encode(new String(password)));
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
