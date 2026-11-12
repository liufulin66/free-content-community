package com.freecontent.community.content.browse.cursor;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.browse.model.PublicContentListQuery;
import com.freecontent.community.content.model.ContentKind;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 独立密钥及用途绑定的 AES-GCM 游标；解密和完整性验证完成后才信任其中的分页条件。 */
@Component
public class PublicContentCursor {
    private static final byte[] PURPOSE = "v1:GET:/api/v1/contents:firstPublishedAt,id:DESC".getBytes(StandardCharsets.UTF_8);
    private final PublicContentCursorProperties properties;
    private final JsonMapper json;
    private final Clock clock;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PublicContentCursor(PublicContentCursorProperties properties, JsonMapper json, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.keyBase64()), "AES");
    }

    public record Payload(int limit, String kind, String tagId, String scope,
                          String snapshotAt, String expiresAt, String lastAt, String lastId, String authorId) { }

    public PublicContentListQuery open(String token, int limit, ContentKind kind, String tagId, String scope) {
        return open(token, limit, kind, tagId, scope, null);
    }

    public PublicContentListQuery openForAuthor(String token, int limit, String authorId, String scope) {
        return open(token, limit, null, null, scope, authorId);
    }

    private PublicContentListQuery open(String token, int limit, ContentKind kind, String tagId, String scope, String authorId) {
        if (token == null) {
            Instant snapshot = clock.instant().truncatedTo(ChronoUnit.MICROS);
            return new PublicContentListQuery(limit, kind, tagId, scope, snapshot, snapshot.plusSeconds(properties.ttlSeconds()), null, null, authorId);
        }
        PublicContentListQuery query;
        try {
            if (!token.matches("v1\\.[A-Za-z0-9_-]+")) throw invalid();
            byte[] packed = Base64.getUrlDecoder().decode(token.substring(3));
            if (packed.length < 29 || !Base64.getUrlEncoder().withoutPadding().encodeToString(packed).equals(token.substring(3))) throw invalid();
            byte[] iv = Arrays.copyOfRange(packed, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(purpose(authorId));
            Payload payload = json.readValue(cipher.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), Payload.class);
            if (payload == null || payload.limit() != limit || limit < 1 || limit > 50
                    || !Objects.equals(payload.kind(), kind == null ? null : kind.name())
                    || !Objects.equals(payload.tagId(), tagId) || !Objects.equals(payload.scope(), scope)
                    || !Objects.equals(payload.authorId(), authorId)
                    || payload.lastId() == null || !payload.lastId().matches("^[A-Za-z0-9_-]{1,64}$")) throw invalid();
            Instant snapshot = Instant.parse(payload.snapshotAt()), expires = Instant.parse(payload.expiresAt());
            Instant last = Instant.parse(payload.lastAt());
            if (!expires.isAfter(snapshot) || last.isAfter(snapshot)) throw invalid();
            query = new PublicContentListQuery(limit, kind, tagId, scope, snapshot, expires, last, payload.lastId(), authorId);
        } catch (GeneralSecurityException | RuntimeException ex) {
            throw invalid();
        }
        if (!query.expiresAt().isAfter(clock.instant())) throw new BusinessException(ErrorCode.CURSOR_EXPIRED);
        return query;
    }

    public String next(PublicContentListQuery query, Instant lastAt, String lastId) {
        Payload payload = new Payload(query.limit(), query.kind() == null ? null : query.kind().name(), query.tagId(), query.scope(),
                query.snapshotAt().toString(), query.expiresAt().toString(), lastAt.toString(), lastId, query.authorId());
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(purpose(query.authorId()));
            byte[] encrypted = cipher.doFinal(json.writeValueAsBytes(payload));
            byte[] packed = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        } catch (GeneralSecurityException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "公开列表游标服务暂不可用");
        }
    }

    private byte[] purpose(String authorId) {
        return authorId == null ? PURPOSE
                : ("v1:GET:/api/v1/users/" + authorId + "/contents:firstPublishedAt,id:DESC").getBytes(StandardCharsets.UTF_8);
    }

    private BusinessException invalid() { return new BusinessException(ErrorCode.CURSOR_INVALID); }
}
