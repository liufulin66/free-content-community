package com.freecontent.community.content.browse.service;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.browse.service.impl.PublishedMediaAccessServiceImpl;
import com.freecontent.community.content.browse.ratelimit.PublishedMediaAccessRateLimiter;
import com.freecontent.community.content.browse.service.transaction.PublishedMediaAccessTransactionService;
import com.freecontent.community.media.storage.MediaObjectReader;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedMediaAccessServiceTest {
    private final PublishedMediaAccessTransactionService transactions = mock(PublishedMediaAccessTransactionService.class);
    private final MediaObjectReader objects = mock(MediaObjectReader.class);
    private final PublishedMediaAccessRateLimiter limiter = mock(PublishedMediaAccessRateLimiter.class);
    private final PublishedMediaAccessService service = new PublishedMediaAccessServiceImpl(transactions, objects, limiter);

    @ParameterizedTest
    @MethodSource("invalidIds")
    void invalidIdsAre422BeforeDatabaseOrStorageAccess(String content, String asset, String field) {
        var error = assertThrows(BusinessException.class, () -> service.getAccess(content, asset, null, "source"));
        assertEquals(ErrorCode.VALIDATION_ERROR, error.errorCode());
        assertEquals("path", error.violations().get(0).location());
        assertEquals(field, error.violations().get(0).path());
        verifyNoInteractions(transactions, objects, limiter);
    }

    static Stream<Arguments> invalidIds() {
        return Stream.of(null, "", "bad.id", "素材", "a".repeat(65)).flatMap(value -> Stream.of(
                Arguments.of(value, "asset_1", "contentId"), Arguments.of("content_1", value, "assetId")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseAndTransactionFailureAre503WithoutInternalDetails(boolean transactionFailure) {
        RuntimeException failure = transactionFailure ? new CannotCreateTransactionException("sensitive connection detail")
                : new DataAccessResourceFailureException("sensitive SQL detail");
        when(transactions.prepare("content_1", "asset_1")).thenThrow(failure);
        var error = assertThrows(BusinessException.class, () -> service.getAccess("content_1", "asset_1", null, "source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode());
        assertFalse(error.getMessage().contains("sensitive"));
        verifyNoInteractions(objects);
    }
}
