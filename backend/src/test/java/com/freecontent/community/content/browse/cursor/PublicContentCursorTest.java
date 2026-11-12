package com.freecontent.community.content.browse.cursor;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.config.PublicContentCursorProperties;
import com.freecontent.community.content.model.ContentKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class PublicContentCursorTest {
    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00.123456Z");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private PublicContentCursor cursor(Instant now) {
        return new PublicContentCursor(new PublicContentCursorProperties(900, KEY), JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC));
    }
    @Test void roundTripsTheOriginalSnapshotFiltersScopeAndPosition() {
        var first = cursor(NOW).open(null, 2, ContentKind.ARTICLE, "tag_1", "user:one");
        String token = cursor(NOW).next(first, NOW.minusSeconds(1), "content_2");
        var next = cursor(NOW.plusSeconds(30)).open(token, 2, ContentKind.ARTICLE, "tag_1", "user:one");
        assertEquals(NOW, next.snapshotAt());
        assertEquals(NOW.plusSeconds(900), next.expiresAt());
        assertEquals(NOW.minusSeconds(1), next.lastFirstPublishedAt());
        assertEquals("content_2", next.lastId());
    }
    @Test void rejectsTamperingAndChangesToAnyBoundCondition() {
        var first = cursor(NOW).open(null, 2, ContentKind.ARTICLE, "tag_1", "user:one");
        String token = cursor(NOW).next(first, NOW.minusSeconds(1), "content_2");
        byte[] packed = Base64.getUrlDecoder().decode(token.substring(3));
        packed[packed.length - 1] ^= 1;
        String changed = "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        invalid(() -> cursor(NOW).open(changed, 2, ContentKind.ARTICLE, "tag_1", "user:one"));
        invalid(() -> cursor(NOW).open(token, 3, ContentKind.ARTICLE, "tag_1", "user:one"));
        invalid(() -> cursor(NOW).open(token, 2, ContentKind.VIDEO, "tag_1", "user:one"));
        invalid(() -> cursor(NOW).open(token, 2, ContentKind.ARTICLE, "tag_2", "user:one"));
        invalid(() -> cursor(NOW).open(token, 2, ContentKind.ARTICLE, "tag_1", "anonymous"));
    }
    @Test void subsequentPagesDoNotRenewTheOriginalExpiry() {
        var first = cursor(NOW).open(null, 2, null, null, "anonymous");
        String token = cursor(NOW).next(first, NOW.minusSeconds(1), "content_2");
        var next = cursor(NOW.plusSeconds(800)).open(token, 2, null, null, "anonymous");
        String following = cursor(NOW.plusSeconds(800)).next(next, NOW.minusSeconds(2), "content_3");
        assertEquals(ErrorCode.CURSOR_EXPIRED, assertThrows(BusinessException.class,
                () -> cursor(NOW.plusSeconds(900)).open(following, 2, null, null, "anonymous")).errorCode());
    }
    @Test void usesFreshNoncesAndDoesNotExposeTheKeyInConfigurationText() {
        var first = cursor(NOW).open(null, 2, null, null, "anonymous");
        assertNotEquals(cursor(NOW).next(first, NOW.minusSeconds(1), "content_2"),
                cursor(NOW).next(first, NOW.minusSeconds(1), "content_2"));
        assertFalse(new PublicContentCursorProperties(900, KEY).toString().contains(KEY));
    }

    @Test void authorCursorsKeepTheOriginalScopeExpiryAndScannedPosition() {
        var first = cursor(NOW).openForAuthor(null, 2, "author_1", "user:one");
        String token = cursor(NOW).next(first, NOW.minusSeconds(1), "content_2");
        var page = cursor(NOW.plusSeconds(800)).openForAuthor(token, 2, "author_1", "user:one");
        assertEquals("author_1", page.authorId());
        assertEquals(first.snapshotAt(), page.snapshotAt());
        assertEquals(first.expiresAt(), page.expiresAt());
        assertEquals("content_2", page.lastId());
        invalid(() -> cursor(NOW).openForAuthor(token, 2, "author_2", "user:one"));
        invalid(() -> cursor(NOW).openForAuthor(token, 3, "author_1", "user:one"));
        invalid(() -> cursor(NOW).openForAuthor(token, 2, "author_1", "anonymous"));
        invalid(() -> cursor(NOW).open(token, 2, null, null, "user:one"));
        String next = cursor(NOW.plusSeconds(800)).next(page, NOW.minusSeconds(2), "content_3");
        assertEquals(ErrorCode.CURSOR_EXPIRED, assertThrows(BusinessException.class,
                () -> cursor(NOW.plusSeconds(900)).openForAuthor(next, 2, "author_1", "user:one")).errorCode());
    }

    @Test void existingGlobalV1CursorsWithoutAnAuthorFieldRemainValid() throws Exception {
        String legacyPayload = """
            {"limit":2,"kind":null,"tagId":null,"scope":"anonymous",
             "snapshotAt":"2026-11-11T08:00:00.123456Z","expiresAt":"2026-11-11T08:15:00.123456Z",
             "lastAt":"2026-11-11T07:59:59.123456Z","lastId":"content_2"}
            """;
        byte[] iv = new byte[12];
        var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, iv));
        cipher.updateAAD("v1:GET:/api/v1/contents:firstPublishedAt,id:DESC".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(legacyPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] packed = java.nio.ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        var query = cursor(NOW.plusSeconds(30)).open("v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(packed),
                2, null, null, "anonymous");
        assertNull(query.authorId());
        assertEquals(NOW, query.snapshotAt());
        assertEquals("content_2", query.lastId());
    }
    private void invalid(org.junit.jupiter.api.function.Executable action) {
        assertEquals(ErrorCode.CURSOR_INVALID, assertThrows(BusinessException.class, action).errorCode());
    }
}
