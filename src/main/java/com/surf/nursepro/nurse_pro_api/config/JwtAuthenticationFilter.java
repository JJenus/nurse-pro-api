package com.surf.nursepro.nurse_pro_api.config;

import com.surf.nursepro.nurse_pro_api.dto.ApiError;
import com.surf.nursepro.nurse_pro_api.entity.UserPrincipal;
import com.surf.nursepro.nurse_pro_api.enums.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.UnsupportedJwtException;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final Set<String> PERMITTED_PATHS = new HashSet<>(Arrays.asList(
            "/api/auth",
            "/api-docs",
            "/swagger-ui",
            "/swagger-ui.html",
            "/webjars/swagger-ui",
            "/actuator"
    ));

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestURI = request.getRequestURI();
        logger.debug("Processing request for URI: {}", requestURI);

        // Skip authentication for permitted paths
        if (isPermittedPath(requestURI)) {
            logger.debug("Skipping authentication for permitted path: {}", requestURI);
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String jwt = getJwtFromRequest(request);
            if (jwt == null) {
                logger.warn("No JWT token found in request for URI: {}", requestURI);
                sendErrorResponse(response, "Missing or invalid Authorization header", HttpStatus.UNAUTHORIZED);
                return;
            }

            String userId = jwtUtil.extractUserId(jwt);
            String role = jwtUtil.extractRole(jwt);

            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                if (jwtUtil.isTokenValid(jwt, userId)) {
                    UserDetails userDetails = new UserPrincipal(userId, Role.valueOf(role));
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    logger.info("Authenticated user ID: {} with role: {} for URI: {}", userId, role, requestURI);
                } else {
                    logger.warn("Invalid JWT token for user ID: {} on URI: {}", userId, requestURI);
                    sendErrorResponse(response, "Invalid or unauthorized JWT token", HttpStatus.UNAUTHORIZED);
                    return;
                }
            } else {
                logger.warn("Authentication already exists or user ID not found for URI: {}", requestURI);
            }

            // Add security headers
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("X-XSS-Protection", "1; mode=block");

            filterChain.doFilter(request, response);
        } catch (ExpiredJwtException e) {
            logger.warn("Expired JWT token for URI: {}. Error: {}", requestURI, e.getMessage());
            sendErrorResponse(response, "JWT token has expired", HttpStatus.UNAUTHORIZED);
        } catch (MalformedJwtException | SignatureException | UnsupportedJwtException e) {
            logger.warn("Malformed or invalid JWT token for URI: {}. Error: {}", requestURI, e.getMessage());
            sendErrorResponse(response, "Malformed or invalid JWT token", HttpStatus.UNAUTHORIZED);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid JWT claims for URI: {}. Error: {}", requestURI, e.getMessage());
            sendErrorResponse(response, "Invalid JWT claims", HttpStatus.UNAUTHORIZED);
        } catch (Exception e) {
            logger.error("Unexpected error during authentication for URI: {}", requestURI, e);
            sendErrorResponse(response, "Authentication error: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean isPermittedPath(String requestURI) {
        return true;
//        if ("/".equals(requestURI)) {
//            return true; // allow root only
//        }
//        return PERMITTED_PATHS.stream().anyMatch(requestURI::startsWith);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private void sendErrorResponse(HttpServletResponse response, String message, HttpStatus status)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiError errorResponse = new ApiError(message, status.getReasonPhrase(), null);
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}