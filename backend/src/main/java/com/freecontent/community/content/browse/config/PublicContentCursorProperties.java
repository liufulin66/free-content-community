package com.freecontent.community.content.browse.config;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.content.public-cursor")
public record PublicContentCursorProperties(int ttlSeconds, String keyBase64) {
    public PublicContentCursorProperties {
        if (ttlSeconds < 1 || ttlSeconds > 86400) throw new IllegalArgumentException("公开列表游标期限不合法");
        try {
            if (keyBase64 == null || Base64.getDecoder().decode(keyBase64).length != 32) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("公开列表游标必须配置独立的32字节Base64密钥");
        }
    }
    @Override public String toString() { return "PublicContentCursorProperties[ttlSeconds=" + ttlSeconds + ", key=REDACTED]"; }
}
