package com.freecontent.community.content.browse.service;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import com.freecontent.community.content.browse.ratelimit.PublicContentQueryRateLimiter;
import com.freecontent.community.content.browse.service.impl.UserContentListServiceImpl;
import com.freecontent.community.content.browse.service.transaction.PublicContentListTransactionService;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserContentListServiceTest {
    private final PublicContentCursor cursors = mock(PublicContentCursor.class);
    private final PublicContentQueryRateLimiter limiter = mock(PublicContentQueryRateLimiter.class);
    private final PublicContentListTransactionService transactions = mock(PublicContentListTransactionService.class);
    private final UserContentListService service = new UserContentListServiceImpl(cursors, limiter, transactions);

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void invalidParametersFailBeforeDatabaseAccess(String user, String cursor, String limit, ErrorCode code, String location, String field) {
        var error = assertThrows(BusinessException.class, () -> service.listUserContents(user, cursor, limit, null, "source"));
        assertEquals(code, error.errorCode());
        assertEquals(location, error.violations().get(0).location());
        assertEquals(field, error.violations().get(0).path());
        verifyNoInteractions(cursors, transactions, limiter);
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.concat(
                Stream.of(null, "", "bad.id", "用户", "a".repeat(65)).map(user -> Arguments.of(user, null, null, ErrorCode.VALIDATION_ERROR, "path", "userId")),
                Stream.of(
                        Arguments.of("author_1", null, "no", ErrorCode.MALFORMED_REQUEST, "query", "limit"),
                        Arguments.of("author_1", null, "", ErrorCode.MALFORMED_REQUEST, "query", "limit"),
                        Arguments.of("author_1", null, "1.0", ErrorCode.MALFORMED_REQUEST, "query", "limit"),
                        Arguments.of("author_1", null, "0", ErrorCode.VALIDATION_ERROR, "query", "limit"),
                        Arguments.of("author_1", null, "-1", ErrorCode.VALIDATION_ERROR, "query", "limit"),
                        Arguments.of("author_1", null, "51", ErrorCode.VALIDATION_ERROR, "query", "limit"),
                        Arguments.of("author_1", null, "99999999999999999999999999999", ErrorCode.VALIDATION_ERROR, "query", "limit"),
                        Arguments.of("author_1", "x".repeat(4097), null, ErrorCode.VALIDATION_ERROR, "query", "cursor")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void databaseAndTransactionFailuresBecome503WithoutInternalDetails(boolean transactionFailure) {
        RuntimeException failure = transactionFailure
                ? new CannotCreateTransactionException("sensitive transaction detail")
                : new DataAccessResourceFailureException("sensitive SQL detail");
        when(transactions.list(any())).thenThrow(failure);
        var error = assertThrows(BusinessException.class, () -> service.listUserContents("author_1", null, null, null, "source"));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode());
        assertFalse(error.getMessage().contains("sensitive"));
    }
}
