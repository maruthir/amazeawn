package com.amazeawn.api.auth;

import com.amazeawn.api.config.ApiException;
import com.amazeawn.api.config.AppProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AppProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.props = props;
    }

    public record Tokens(String accessToken, String refreshToken, long expiresIn, String tokenType) {}

    @Transactional
    public User register(String email, String password, String fullName) {
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("email_taken", "Email already registered");
        }
        return users.save(new User(email.toLowerCase(), passwordEncoder.encode(password), fullName, Role.CUSTOMER));
    }

    @Transactional
    public Tokens login(String email, String password) {
        User user = users.findByEmailIgnoreCase(email)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid credentials"));
        if (!user.isEnabled()) {
            throw ApiException.unauthorized("Account disabled");
        }
        return issue(user);
    }

    /** Refresh tokens rotate: the presented token is revoked and a new pair is returned. */
    @Transactional
    public Tokens refresh(String refreshToken) {
        RefreshToken stored = refreshTokens.findByTokenHash(hash(refreshToken))
                .filter(t -> !t.isRevoked() && t.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        User user = users.findById(stored.getUserId()).orElseThrow(() -> ApiException.unauthorized("Unknown user"));
        if (!user.isEnabled()) {
            throw ApiException.unauthorized("Account disabled");
        }
        stored.revoke();
        return issue(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokens.findByTokenHash(hash(refreshToken)).ifPresent(RefreshToken::revoke);
    }

    private Tokens issue(User user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user.getId(), hash(raw), Instant.now().plus(props.auth().refreshTtl())));
        return new Tokens(jwtService.issueAccessToken(user), raw, jwtService.accessTtlSeconds(), "Bearer");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Scheduled(fixedDelayString = "PT10M")
    @Transactional
    public void purgeRefreshTokens() {
        refreshTokens.purge(Instant.now());
    }
}
