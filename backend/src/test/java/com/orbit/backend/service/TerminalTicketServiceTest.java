package com.orbit.backend.service;

import com.orbit.backend.dto.response.TerminalTicketResponse;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TerminalTicketServiceTest {

    private static final String SECRET = "orbit-test-secret-0123456789abcdef-XYZ";
    private final ObjectMapper json = JsonMapper.builder().build();
    private final Instant now = Instant.parse("2026-11-18T10:00:00Z");

    private TerminalTicketService service(String secret) {
        SecretKeyService keys = mock(SecretKeyService.class);
        if (secret == null) {
            when(keys.requireSecret()).thenThrow(new ApiException(ErrorCode.SECRET_KEY_NOT_CONFIGURED));
        } else {
            when(keys.requireSecret()).thenReturn(secret);
        }
        return new TerminalTicketService(keys, json, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void signingKeyMatchesTheIndependentlyComputedVector() {
        // the same vector is checked by the Go helper's tests (computed with Node's crypto, a third implementation)
        assertThat(HexFormat.of().formatHex(TerminalTicketService.signingKey(SECRET)))
                .isEqualTo("c277c799c1bb3f689636169da73e114a30fb5de812201ec90beb787949e2ecab");
        String payload = "eyJ2IjoxLCJ0eXAiOiJvcmJpdC10ZXJtaW5hbCIsInN1YiI6InUxIiwiY3dkIjoiRTovUHJvamVjdHMvYXBwIiwiZXhwIjo0MTAyNDQ0ODAwLCJqdGkiOiIwMGZmIn0";
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(
                TerminalTicketService.hmac(TerminalTicketService.signingKey(SECRET), payload.getBytes(StandardCharsets.US_ASCII))))
                .isEqualTo("bBcThKJPDGl5x-u3sG8g8sui2Tso7uXg2uJFi4eKMrQ");
    }

    @Test
    void ticketCarriesUserFolderExpiryAndAValidSignature() {
        UUID user = UUID.randomUUID();
        TerminalTicketResponse t = service(SECRET).issue(user, "E:/Projects/my app/ünïcode");

        String[] parts = t.ticket().split("\\.");
        assertThat(parts).hasSize(2);
        String expectedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(
                TerminalTicketService.hmac(TerminalTicketService.signingKey(SECRET), parts[0].getBytes(StandardCharsets.US_ASCII)));
        assertThat(parts[1]).isEqualTo(expectedSig);

        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(claims.get("v").asInt()).isEqualTo(1);
        assertThat(claims.get("typ").asString()).isEqualTo("orbit-terminal");
        assertThat(claims.get("sub").asString()).isEqualTo(user.toString());
        assertThat(claims.get("cwd").asString()).isEqualTo("E:/Projects/my app/ünïcode");
        assertThat(claims.get("exp").asLong()).isEqualTo(now.getEpochSecond() + TerminalTicketService.TTL_SECONDS);
        assertThat(claims.get("jti").asString()).matches("[0-9a-f]{32}");
        assertThat(t.expiresAt()).isEqualTo((now.getEpochSecond() + TerminalTicketService.TTL_SECONDS) * 1000);
        assertThat(t.cwd()).isEqualTo("E:/Projects/my app/ünïcode");
    }

    @Test
    void everyTicketIsUniqueAndAnotherSecretGivesAnotherSignature() {
        UUID user = UUID.randomUUID();
        String a = service(SECRET).issue(user, "/p").ticket();
        String b = service(SECRET).issue(user, "/p").ticket();
        String c = service(SECRET + "-other").issue(user, "/p").ticket();
        assertThat(a).isNotEqualTo(b);
        String payloadOfA = a.split("\\.")[0];
        String sigWithOtherKey = Base64.getUrlEncoder().withoutPadding().encodeToString(
                TerminalTicketService.hmac(TerminalTicketService.signingKey(SECRET + "-other"), payloadOfA.getBytes(StandardCharsets.US_ASCII)));
        assertThat(a.split("\\.")[1]).isNotEqualTo(sigWithOtherKey);
        assertThat(c.split("\\.")[1]).isNotEqualTo(a.split("\\.")[1]);
    }

    @Test
    void noTicketWithoutASecret() {
        assertThatThrownBy(() -> service(null).issue(UUID.randomUUID(), "/p"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SECRET_KEY_NOT_CONFIGURED));
    }
}
