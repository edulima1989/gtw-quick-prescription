package com.quickprescription.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ExternalAuthenticationFilterTest {

  private static final String VALIDATION_URL = "http://auth/users/api/auth/validate-session";

  private MockRestServiceServer authServer;

  private ExternalAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    ExternalAuthProperties properties = new ExternalAuthProperties();
    properties.setValidationUrl(VALIDATION_URL);
    properties.setProtectedPaths(List.of("/**"));
    properties.setExcludedPaths(List.of(
        "POST /api/v1/auth/login",
        "POST /api/v1/auth/register",
        "/users/swagger-ui/**",
        "/users/v3/api-docs/**"));

    RestClient.Builder builder = RestClient.builder();
    authServer = MockRestServiceServer.bindTo(builder).build();
    filter = new ExternalAuthenticationFilter(properties, builder);
  }

  @Test
  void rejectsTokenWhenAuthServiceReportsInvalid() throws Exception {
    expectValidation("{\"valid\":false}");

    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = filter(withAuthorization("Bearer texto-arbitrario"), chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(chain.getRequest()).isNull();
    authServer.verify();
  }

  @Test
  void forwardsRequestWhenTokenIsValid() throws Exception {
    expectValidation("{\"valid\":true,\"userId\":1,\"userMail\":\"a@b.com\",\"userRole\":\"USUARIO_FINAL\"}");

    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = filter(withAuthorization("Bearer token-valido"), chain);

    assertThat(response.getStatus()).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(chain.getRequest()).isNotNull();
    authServer.verify();
  }

  @Test
  void rejectsTokenWhenAuthServiceReturnsEmptyBody() throws Exception {
    authServer.expect(requestTo(VALIDATION_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess());

    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = filter(withAuthorization("Bearer token"), chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(chain.getRequest()).isNull();
    authServer.verify();
  }

  @Test
  void rejectsMissingAuthorizationWithoutCallingAuthService() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = filter(new MockHttpServletRequest("GET", "/api/v1/cie10"), chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(chain.getRequest()).isNull();
    authServer.verify();
  }

  @Test
  void rejectsNonBearerAuthorizationWithoutCallingAuthService() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = filter(withAuthorization("Basic dXNlcjpwYXNz"), chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(chain.getRequest()).isNull();
    authServer.verify();
  }

  @Test
  void allowsLoginAndRegisterWithoutToken() throws Exception {
    for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/register")) {
      MockFilterChain chain = new MockFilterChain();
      MockHttpServletResponse response = filter(new MockHttpServletRequest("POST", path), chain);

      assertThat(response.getStatus()).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
      assertThat(chain.getRequest()).isNotNull();
    }
    authServer.verify();
  }

  @Test
  void requiresTokenForLoginWithOtherMethod() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = filter(new MockHttpServletRequest("GET", "/api/v1/auth/login"), chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void requiresTokenForAnyOtherPath() throws Exception {
    for (String path : List.of("/api/v1/auth/validate-session", "/users/api/auth/validate-session",
        "/prescriptions/api/v1/cie10", "/api/v1/recetas")) {
      MockFilterChain chain = new MockFilterChain();
      MockHttpServletResponse response = filter(new MockHttpServletRequest("POST", path), chain);

      assertThat(response.getStatus()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED.value());
      assertThat(chain.getRequest()).isNull();
    }
    authServer.verify();
  }

  @Test
  void allowsSwaggerWithoutToken() throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = filter(new MockHttpServletRequest("GET", "/users/v3/api-docs"), chain);

    assertThat(response.getStatus()).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    assertThat(chain.getRequest()).isNotNull();
  }

  private void expectValidation(String body) {
    authServer.expect(requestTo(VALIDATION_URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  private MockHttpServletRequest withAuthorization(String authorization) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/cie10");
    request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
    return request;
  }

  private MockHttpServletResponse filter(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }
}
