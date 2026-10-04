package com.quickprescription.gateway.config;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PreflightCorsFilter extends OncePerRequestFilter {

  private final CorsProperties corsProperties;

  public PreflightCorsFilter(CorsProperties corsProperties) {
    this.corsProperties = corsProperties;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String origin = request.getHeader(HttpHeaders.ORIGIN);
    boolean allowed = StringUtils.hasText(origin) && isAllowedOrigin(origin);

    // Preflight (OPTIONS): el gateway responde directamente con las cabeceras CORS.
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      String requestedMethod = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
      if (!StringUtils.hasText(origin) || !StringUtils.hasText(requestedMethod)) {
        filterChain.doFilter(request, response);
        return;
      }
      if (!allowed) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return;
      }
      response.setHeader(HttpHeaders.VARY, String.join(", ",
          HttpHeaders.ORIGIN,
          HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
          HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS));
      applyAllowOrigin(response, origin);
      response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, String.join(", ", corsProperties.getAllowedMethods()));
      response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, resolveAllowedHeaders(request));
      response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, String.valueOf(corsProperties.getMaxAgeSeconds()));
      response.setStatus(HttpServletResponse.SC_OK);
      return;
    }

    // Solicitud real: se fijan las cabeceras CORS ANTES de proxyar al microservicio, para que estén
    // presentes aunque la respuesta se envíe en streaming. Los microservicios downstream ya no emiten
    // CORS (está centralizado aquí), de modo que no hay riesgo de cabeceras duplicadas.
    if (allowed) {
      applyAllowOrigin(response, origin);
      response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION);
    }
    filterChain.doFilter(request, response);
  }

  /** Fija Access-Control-Allow-Origin (y credenciales) a un único valor, reemplazando cualquiera previo. */
  private void applyAllowOrigin(HttpServletResponse response, String origin) {
    response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
    response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
    if (corsProperties.isAllowCredentials()) {
      response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }
  }

  private boolean isAllowedOrigin(String origin) {
    List<String> allowedOrigins = corsProperties.getAllowedOrigins();
    return allowedOrigins != null && allowedOrigins.stream().anyMatch(origin::equals);
  }

  private String resolveAllowedHeaders(HttpServletRequest request) {
    List<String> configuredHeaders = corsProperties.getAllowedHeaders();
    if (configuredHeaders == null || configuredHeaders.isEmpty()) {
      return "";
    }

    boolean wildcard = configuredHeaders.stream().anyMatch("*"::equals);
    if (!wildcard) {
      return String.join(", ", configuredHeaders);
    }

    String requestedHeaders = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
    if (StringUtils.hasText(requestedHeaders)) {
      return requestedHeaders;
    }

    return "*";
  }
}
