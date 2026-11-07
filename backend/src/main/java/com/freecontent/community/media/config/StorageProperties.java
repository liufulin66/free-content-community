package com.freecontent.community.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;

@ConfigurationProperties("app.storage")
public record StorageProperties(String endpoint, String accessKey, String secretKey, String bucket, String region) {
    public StorageProperties {
        URI address;
        try { address = URI.create(endpoint); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("存储连接配置不合法"); }
        if (!("http".equals(address.getScheme()) || "https".equals(address.getScheme())) || address.getHost() == null
                || address.getUserInfo() != null || address.getRawQuery() != null || address.getFragment() != null
                || !(address.getPath().isEmpty() || "/".equals(address.getPath()))
                || accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()
                || region == null || region.isBlank() || bucket == null
                || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || bucket.contains("..")) {
            throw new IllegalArgumentException("存储连接配置不合法");
        }
    }
    @Override public String toString() { return "StorageProperties[credentials=REDACTED]"; }
}
