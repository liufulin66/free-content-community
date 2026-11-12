package com.freecontent.community.user.service;

import java.util.List;
import java.util.UUID;
import com.freecontent.community.user.model.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.auth.email-challenge.delivery-enabled=false", "app.media.upload-cleanup.enabled=false"})
class AccountAccessFlowIT {
    @Autowired private AccountAccessService accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void lockingRequiresTheCallersTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> accounts.findByIdForShare("missing"));
        assertThrows(IllegalTransactionStateException.class, () -> accounts.findByIdForUpdate("missing"));
        assertThrows(IllegalTransactionStateException.class, () -> accounts.findRolesForShare("missing"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accountAndRoleReadsParticipateInTheCallersRollback(boolean exclusive) {
        String id = UUID.randomUUID().toString().replace("-", "");
        String email = "structure-" + id + "@example.test";
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            transaction.setRollbackOnly();
            jdbc.update("""
                    INSERT INTO app_user(id,email,email_normalized,password_hash,email_verified,email_verified_at,display_name,
                        account_status,version,auth_version,created_at,updated_at)
                    VALUES(?,?,?,'test-only',1,UTC_TIMESTAMP(6),'structure','ACTIVE',1,3,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, id, email, email);
            jdbc.update("INSERT INTO app_user_role(user_id,role_code,created_at) VALUES(?,'USER',UTC_TIMESTAMP(6))", id);
            var account = exclusive ? accounts.findByIdForUpdate(id) : accounts.findByIdForShare(id);
            assertNotNull(account);
            assertEquals(id, account.id());
            assertEquals(3L, account.authVersion());
            assertTrue(account.emailVerified());
            assertTrue(account.active());
            assertFalse(account.disabled());
            assertEquals(List.of(UserRole.USER), accounts.findRolesForShare(id));
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user_role WHERE user_id=?", Integer.class, id));
    }
}
