package com.quickprescription.gateway.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ExternalAuthenticationFilter extends OncePerRequestFilter {

  private final ExternalAuthProperties authProperties;

  private final RestClient restClient;

  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  public ExternalAuthenticationFilter(ExternalAuthProperties authProperties, RestClient.Builder restClientBuilder) {
    this.authProperties = authProperties;
    this.restClient = restClientBuilder.build();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      return true;
    }

    if (matchesAny(authProperties.getExcludedPaths(), request)) {
      return true;
    }

    // Sin rutas protegidas configuradas se protege todo (cerrado por defecto).
    List<String> protectedPaths = authProperties.getProtectedPaths();
    if (protectedPaths == null || protectedPaths.isEmpty()) {
      return false;
    }

    return !matchesAny(protectedPaths, request);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
      writeError(response, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
      return;
    }
    String token = authorization.substring("Bearer ".length()).trim();
    if (!StringUtils.hasText(token)) {
      writeError(response, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
      return;
    }

    String validationUrl = authProperties.getValidationUrl();
    if (!StringUtils.hasText(validationUrl)) {
      writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, "External authentication service is not configured");
      return;
    }

    TokenValidationResponse validation;
    try {
      validation = restClient.post()
          .uri(validationUrl)
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          //.header(HttpHeaders.AUTHORIZATION, authorization)
          .body(new TokenValidationRequest(token))
          .retrieve()
          .body(TokenValidationResponse.class);
    } catch (RestClientResponseException ex) {
      writeError(response, HttpStatus.UNAUTHORIZED, "Authentication rejected by external service");
      return;
    } catch (ResourceAccessException ex) {
      writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "External authentication service is unavailable");
      return;
    }

    // El Auth Service responde 200 con {"valid": false} para tokens inválidos o expirados.
    if (validation == null || !Boolean.TRUE.equals(validation.valid())) {
      writeError(response, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
      return;
    }

    filterChain.doFilter(request, response);
  }

  private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write("{\"error\":\"" + message + "\"}");
  }

  private boolean matchesAny(List<String> patterns, HttpServletRequest request) {
    if (patterns == null || patterns.isEmpty()) {
      return false;
    }
    return patterns.stream().anyMatch(pattern -> matches(pattern, request));
  }

  // Patrón con formato "[MÉTODO ]ruta", p. ej. "POST /api/v1/auth/login" o "/api/v1/**".
  private boolean matches(String pattern, HttpServletRequest request) {
    String trimmed = pattern.trim();
    int space = trimmed.indexOf(' ');
    if (space < 0) {
      return pathMatcher.match(trimmed, request.getRequestURI());
    }
    String method = trimmed.substring(0, space);
    String path = trimmed.substring(space + 1).trim();
    return method.equalsIgnoreCase(request.getMethod()) && pathMatcher.match(path, request.getRequestURI());
  }

  private record TokenValidationRequest(String token) {
  }

  private record TokenValidationResponse(Boolean valid) {
  }
}
