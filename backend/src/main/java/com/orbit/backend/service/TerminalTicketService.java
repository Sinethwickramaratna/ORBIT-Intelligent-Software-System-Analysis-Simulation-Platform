package com.orbit.backend.service;

import com.orbit.backend.dto.response.TerminalTicketResponse;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tickets for the ORBIT terminal helper - the small program on the user's computer that runs the terminals (the
 * backend may run in Docker and could only offer a Linux shell there).
 *
 * <p>A ticket is {@code base64url(json).base64url(hmacSha256(key, base64url(json)))} with
 * {@code {"v":1,"typ":"orbit-terminal","sub":<user>,"cwd":<project folder>,"exp":<epoch s>,"jti":<random>}}.
 * The key is {@code HMAC-SHA256(JWT_SECRET, "orbit-terminal-ticket-v1")}: the helper reads the same secret from
 * {@code .env}, but a ticket can never be used as an access token or the other way round. Tickets live
 * {@link #TTL_SECONDS} seconds and the helper accepts each one only once.
 */
@Service
public class TerminalTicketService {

    static final long TTL_SECONDS = 60;
    static final String KEY_LABEL = "orbit-terminal-ticket-v1";
    static final String TYPE = "orbit-terminal";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeyService secretKeyService;
    private final ObjectMapper json;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TerminalTicketService(SecretKeyService secretKeyService, ObjectMapper json) {
        this(secretKeyService, json, Clock.systemUTC());
    }

    TerminalTicketService(SecretKeyService secretKeyService, ObjectMapper json, Clock clock) {
        this.secretKeyService = secretKeyService;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param cwd the project folder as stored (a path on the user's computer, e.g. {@code E:/Projects/app}, or
     *            relative to the ORBIT folder, e.g. {@code ./projects/app}, when nothing else is mounted)
     */
    public TerminalTicketResponse issue(UUID userId, String cwd) {
        Instant expires = clock.instant().plusSeconds(TTL_SECONDS);
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("v", 1);
        claims.put("typ", TYPE);
        claims.put("sub", userId.toString());
        claims.put("cwd", cwd);
        claims.put("exp", expires.getEpochSecond());
        claims.put("jti", HexFormat.of().formatHex(nonce));
        String payload = B64.encodeToString(json.writeValueAsBytes(claims));
        String signature = B64.encodeToString(hmac(signingKey(secretKeyService.requireSecret()), payload.getBytes(StandardCharsets.US_ASCII)));
        return new TerminalTicketResponse(payload + "." + signature, expires.toEpochMilli(), cwd);
    }

    /** {@code HMAC-SHA256(secret, "orbit-terminal-ticket-v1")}; the helper derives the very same key. */
    static byte[] signingKey(String secret) {
        return hmac(secret.getBytes(StandardCharsets.UTF_8), KEY_LABEL.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not sign the terminal ticket");
        }
    }
}
