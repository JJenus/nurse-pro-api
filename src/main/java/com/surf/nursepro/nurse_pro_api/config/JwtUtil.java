package com.surf.nursepro.nurse_pro_api.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {
    private static final Logger logger = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration}")
    private Long jwtExpirationMs;

    @Value("${jwt.refresh.expiration:604800000}") // Default: 7 days in ms
    private Long jwtRefreshExpirationMs;

    private SecretKey getSigningKey() {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            logger.error("JWT secret is invalid or too short. Minimum length is 32 characters.");
            throw new IllegalStateException("JWT secret is not configured properly");
        }
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String userId, String role, String username, String department) {
        if (userId == null || role == null) {
            logger.error("Cannot generate token: userId or role is null");
            throw new IllegalArgumentException("User ID and role are required");
        }
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);
        claims.put("username", username != null ? username : "");
        claims.put("department", department != null ? department : "");
        return createToken(claims, userId, jwtExpirationMs);
    }

    public String generateRefreshToken(String userId) {
        if (userId == null) {
            logger.error("Cannot generate refresh token: userId is null");
            throw new IllegalArgumentException("User ID is required");
        }
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("type", "refresh");
        return createToken(claims, userId, jwtRefreshExpirationMs);
    }

    private String createToken(Map<String, Object> claims, String subject, Long expirationMs) {
        try {
            Date now = new Date();
            Date expiryDate = new Date(now.getTime() + expirationMs);
            String token = Jwts.builder()
                    .setClaims(claims)
                    .setSubject(subject)
                    .setIssuedAt(now)
                    .setExpiration(expiryDate)
                    .signWith(getSigningKey())
                    .compact();
            logger.debug("Generated JWT token for subject: {}, expiration: {}", subject, expiryDate);
            return token;
        } catch (Exception e) {
            logger.error("Error generating JWT token for subject: {}", subject, e);
            throw new RuntimeException("Failed to generate JWT token", e);
        }
    }

    public String extractUserId(String token) {
        try {
            return extractClaim(token, claims -> claims.get("userId", String.class));
        } catch (JwtException e) {
            logger.warn("Failed to extract userId from token: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid JWT token: " + e.getMessage());
        }
    }

    public String extractRole(String token) {
        try {
            return extractClaim(token, claims -> claims.get("role", String.class));
        } catch (JwtException e) {
            logger.warn("Failed to extract role from token: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid JWT token: " + e.getMessage());
        }
    }

    public String extractUsername(String token) {
        try {
            return extractClaim(token, claims -> claims.get("username", String.class));
        } catch (JwtException e) {
            logger.warn("Failed to extract username from token: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid JWT token: " + e.getMessage());
        }
    }

    public String extractDepartment(String token) {
        try {
            return extractClaim(token, claims -> claims.get("department", String.class));
        } catch (JwtException e) {
            logger.warn("Failed to extract department from token: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid JWT token: " + e.getMessage());
        }
    }

    public boolean isRefreshToken(String token) {
        try {
            String type = extractClaim(token, claims -> claims.get("type", String.class));
            return "refresh".equals(type);
        } catch (JwtException e) {
            logger.warn("Failed to check if token is refresh token: {}", e.getMessage());
            return false;
        }
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        if (token == null || token.trim().isEmpty()) {
            logger.warn("Cannot extract claim: token is null or empty");
            throw new IllegalArgumentException("Token is null or empty");
        }
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        try {
            return Jwts.parser()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException e) {
            logger.warn("Failed to parse JWT token: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid JWT token: " + e.getMessage());
        }
    }

    public boolean isTokenValid(String token, String userId) {
        try {
            if (token == null || userId == null) {
                logger.warn("Token validation failed: token or userId is null");
                return false;
            }
            final String extractedUserId = extractUserId(token);
            boolean isValid = extractedUserId.equals(userId) && !isTokenExpired(token);
            logger.debug("Token validation for userId {}: {}", userId, isValid ? "valid" : "invalid");
            return isValid;
        } catch (JwtException e) {
            logger.warn("Token validation failed for userId {}: {}", userId, e.getMessage());
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        try {
            return extractExpiration(token).before(new Date());
        } catch (JwtException e) {
            logger.warn("Failed to check token expiration: {}", e.getMessage());
            return true; // Treat as expired if parsing fails
        }
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }
}