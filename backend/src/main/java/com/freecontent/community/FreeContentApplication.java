package com.freecontent.community;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// 身份全部通过业务Redis会话认证，不创建框架默认的内存用户。
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class FreeContentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FreeContentApplication.class, args);
    }
}
