package com.freecontent.community.user.controller;

import com.freecontent.community.auth.LoginTestSupport;
import com.freecontent.community.auth.config.SecurityConfiguration;
import com.freecontent.community.auth.security.*;
import com.freecontent.community.auth.service.SessionAuthenticationService;
import com.freecontent.community.common.error.*;
import com.freecontent.community.user.dto.*;
import com.freecontent.community.user.entity.UserAccount;
import com.freecontent.community.user.model.UserRole;
import com.freecontent.community.user.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MeController.class)
@Import(SecurityConfiguration.class)
class MeProfileEndpointTest {
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @MockitoBean private UserProfileService profiles;
    @MockitoBean private CurrentUserService currentUsers;
    @MockitoBean private PasswordChangeService passwords;
    @MockitoBean private SessionAuthenticationService authentication;
    private static final String TOKEN = "a".repeat(43);

    @Test
    void nicknamePatchKeepsOmittedFieldsAbsentAndUsesConnectionAddress() throws Exception {
        SessionPrincipal actor = authenticate(false);
        var returned = LoginTestSupport.account();
        returned.setDisplayName("新昵称");
        returned.setVersion(2);
        when(profiles.update(any(), any(), anyString())).thenReturn(CurrentUser.from(returned, List.of(UserRole.USER)));
        mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + TOKEN)
                        .header("X-Forwarded-For", "203.0.113.2").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("expectedVersion", 1, "displayName", "新昵称"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.displayName").value("新昵称"))
                .andExpect(jsonPath("$.data.version").value(2)).andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"));
        ArgumentCaptor<UpdateProfileRequest> captured = ArgumentCaptor.forClass(UpdateProfileRequest.class);
        verify(profiles).update(captured.capture(), eq(actor), eq("127.0.0.1"));
        assertEquals(1, captured.getValue().getExpectedVersion());
        assertTrue(captured.getValue().hasDisplayName());
        assertFalse(captured.getValue().hasBio());
        assertFalse(captured.getValue().hasAvatarAssetId());
    }

    @Test
    void explicitNullAvatarAndEmptyBioArePreservedAsProvidedChanges() throws Exception {
        authenticate(false);
        mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("expectedVersion", 1, "avatarAssetId", null, "bio", ""))))
                .andExpect(status().isOk());
        ArgumentCaptor<UpdateProfileRequest> captured = ArgumentCaptor.forClass(UpdateProfileRequest.class);
        verify(profiles).update(captured.capture(), any(), anyString());
        assertTrue(captured.getValue().hasAvatarAssetId());
        assertNull(captured.getValue().getAvatarAssetId());
        assertTrue(captured.getValue().hasBio());
        assertEquals("", captured.getValue().getBio());
        assertFalse(captured.getValue().hasDisplayName());
    }

    @Test
    void missingVersionOnlyVersionAndInvalidNullValuesReturn422() throws Exception {
        authenticate(false);
        for (Map<String, Object> body : List.of(body("displayName", "新昵称"), body("expectedVersion", 1),
                body("expectedVersion", 0, "bio", "简介"), body("expectedVersion", 1, "displayName", null),
                body("expectedVersion", 1, "bio", null), body("expectedVersion", 1, "displayName", ""),
                body("expectedVersion", 1, "avatarAssetId", "中文"))) {
            mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(profiles);
    }

    @Test
    void unicodeLengthsAreCountedByCodePoints() throws Exception {
        authenticate(false);
        mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("expectedVersion", 1, "displayName", "😀".repeat(40), "bio", "😀".repeat(500)))))
                .andExpect(status().isOk());
        for (Map<String, Object> body : List.of(body("expectedVersion", 1, "displayName", "😀".repeat(41)),
                body("expectedVersion", 1, "bio", "😀".repeat(501)))) {
            mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body))).andExpect(status().isUnprocessableContent());
        }
        verify(profiles, times(1)).update(any(), any(), anyString());
    }

    @Test
    void wrongJsonTypesAreRejectedInsteadOfBeingCoerced() throws Exception {
        authenticate(false);
        for (Map<String, Object> body : List.of(body("expectedVersion", "1", "bio", "简介"),
                body("expectedVersion", 1, "displayName", 12), body("expectedVersion", 1, "bio", List.of("简介")),
                body("expectedVersion", 1, "avatarAssetId", Map.of("id", "avatar")))) {
            mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        verifyNoInteractions(profiles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "9223372036854775808", "9999999999999999999999999999999999999999"})
    void integerVersionOutsideTheContractRangeReturns422(String version) throws Exception {
        authenticate(false);
        mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + version + ",\"bio\":\"test\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].location").value("body"))
                .andExpect(jsonPath("$.violations[0].path").value("expectedVersion"));
        verifyNoInteractions(profiles);
    }

    @Test
    void privilegedFieldsAndInternalPresenceFlagsCannotBeSubmitted() throws Exception {
        authenticate(false);
        for (String field : List.of("id", "userId", "email", "emailVerified", "roles", "accountStatus", "passwordHash",
                "authVersion", "version", "displayNameProvided", "bioProvided", "avatarAssetIdProvided")) {
            Map<String, Object> body = body("expectedVersion", 1, "displayName", "新昵称", field, true);
            mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(profiles);
    }

    @Test
    void missingIdentityAndWriteBlockedAccountCannotReachProfileUpdate() throws Exception {
        mvc.perform(patch("/api/v1/me").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("expectedVersion", 1, "bio", "简介"))))
                .andExpect(status().isUnauthorized());
        authenticate(true);
        mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("expectedVersion", 1, "bio", "简介"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_RESTRICTED"));
        verifyNoInteractions(profiles);
    }

    @Test
    void conflictRateLimitAndUnavailableAvatarServiceKeepContractErrors() throws Exception {
        authenticate(false);
        when(profiles.update(any(), any(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_VERSION_CONFLICT, "资料已修改", 4))
                .thenThrow(new BusinessException(ErrorCode.RATE_LIMITED, "频繁", null, 30L, List.of()))
                .thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        var request = patch("/api/v1/me").header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body("expectedVersion", 1, "bio", "简介")));
        mvc.perform(request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(4));
        mvc.perform(request).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "30"));
        mvc.perform(request).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    private SessionPrincipal authenticate(boolean blocked) {
        var account = LoginTestSupport.account();
        if (blocked) account.setAccountStatus(UserAccount.Status.WRITE_BLOCKED);
        SessionPrincipal actor = new SessionPrincipal("session1", CurrentUser.from(account, List.of(UserRole.USER)), TokenDigest.sha256(TOKEN), 1);
        when(authentication.authenticate(TOKEN)).thenReturn(actor);
        return actor;
    }

    private Map<String, Object> body(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
