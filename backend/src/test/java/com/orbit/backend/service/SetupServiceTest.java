package com.orbit.backend.service;

import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SetupServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-ok";

    private SecretKeyService secretKeyService;
    private DatabaseProvisioner provisioner;
    private EnvFileService envFileService;
    private SetupService service;

    @BeforeEach
    void setUp() {
        secretKeyService = mock(SecretKeyService.class);
        provisioner = mock(DatabaseProvisioner.class);
        envFileService = mock(EnvFileService.class);
        service = new SetupService(secretKeyService, provisioner, envFileService);
        when(secretKeyService.isConfigured()).thenReturn(false);
        when(provisioner.hasCredentials()).thenReturn(false);
    }

    private void assertRejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(expected);
        verify(envFileService, never()).update(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void freshInstallSavesSecretAndDatabaseCredentialsTogether() {
        service.configureEnvironment(SECRET, "orbit_user", "Passw0rd!x");

        ArgumentCaptor<Map<String, String>> saved = ArgumentCaptor.forClass(Map.class);
        verify(envFileService).update(saved.capture());
        assertThat(saved.getValue()).containsEntry("JWT_SECRET", SECRET)
                .containsEntry("DB_USERNAME", "orbit_user")
                .containsEntry("DB_PASSWORD", "Passw0rd!x")
                .hasSize(3); // DB_NAME / DB_PORT are defaults, never asked
        verify(secretKeyService).activate(SECRET);
        verify(provisioner).configure("orbit_user", "Passw0rd!x");
    }

    @Test
    void rejectsShortSecret() {
        assertRejected(() -> service.configureEnvironment("short", "orbit_user", "Passw0rd!x"), ErrorCode.SECRET_KEY_TOO_WEAK);
    }

    @Test
    void rejectsSecretsThatWouldBreakTheEnvFile() {
        assertRejected(() -> service.configureEnvironment(SECRET + " with space", "orbit_user", "Passw0rd!x"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> service.configureEnvironment(SECRET + "$HOME", "orbit_user", "Passw0rd!x"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> service.configureEnvironment(SECRET + "\\n", "orbit_user", "Passw0rd!x"), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void rejectsBadDatabaseUsernames() {
        for (String bad : new String[]{"1abc", "has space", "semi;colon", "a".repeat(64)}) {
            assertRejected(() -> service.configureEnvironment(SECRET, bad, "Passw0rd!x"), ErrorCode.VALIDATION_FAILED);
        }
    }

    @Test
    void rejectsWeakOrUnsafeDatabasePasswords() {
        assertRejected(() -> service.configureEnvironment(SECRET, "orbit_user", "short"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> service.configureEnvironment(SECRET, "orbit_user", "pass word 123"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> service.configureEnvironment(SECRET, "orbit_user", "pa$$word123"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> service.configureEnvironment(SECRET, "orbit_user", "pass#word123"), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void requiresBothDatabaseFieldsWhenTheyAreMissing() {
        assertRejected(() -> service.configureEnvironment(SECRET, "orbit_user", null), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> service.configureEnvironment(SECRET, null, "Passw0rd!x"), ErrorCode.VALIDATION_FAILED);
        assertRejected(() -> service.configureEnvironment(null, "orbit_user", "Passw0rd!x"), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void neverOverwritesAnythingThatIsAlreadyConfigured() {
        when(secretKeyService.isConfigured()).thenReturn(true);
        when(provisioner.hasCredentials()).thenReturn(true);
        assertRejected(() -> service.configureEnvironment(SECRET, "orbit_user", "Passw0rd!x"), ErrorCode.ENVIRONMENT_ALREADY_CONFIGURED);
        verify(provisioner, never()).configure(any(), any());
    }

    @Test
    void secretAlreadyConfiguredOnlyDatabaseCredentialsAreAccepted() {
        when(secretKeyService.isConfigured()).thenReturn(true);
        assertRejected(() -> service.configureEnvironment(SECRET, "orbit_user", "Passw0rd!x"), ErrorCode.ENVIRONMENT_ALREADY_CONFIGURED);

        service.configureEnvironment(null, "orbit_user", "Passw0rd!x");
        verify(provisioner).configure("orbit_user", "Passw0rd!x");
        verify(secretKeyService, never()).activate(any());
    }
}
