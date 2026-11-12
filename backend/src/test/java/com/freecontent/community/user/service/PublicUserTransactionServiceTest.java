package com.freecontent.community.user.service;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.service.read.PublicContentCountReader;
import com.freecontent.community.user.service.read.PublicUserReader;
import com.freecontent.community.user.service.transaction.PublicUserTransactionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicUserTransactionServiceTest {
    private final PublicUserReader users = mock(PublicUserReader.class);
    private final PublicContentCountReader contents = mock(PublicContentCountReader.class);
    private final PublicUserTransactionService transactions = new PublicUserTransactionService(users, contents);

    @Test
    void missingUserIs404AndDoesNotQueryContentCounts() {
        var error = assertThrows(BusinessException.class, () -> transactions.read("missing_user"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode());
        verifyNoInteractions(contents);
    }
}
