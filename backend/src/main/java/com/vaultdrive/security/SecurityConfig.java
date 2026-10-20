package com.vaultdrive.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import org.springframework.http.HttpMethod;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Link secrets never become authentication and JWTs confer no authority on this surface. */
    @Bean
    @Order(1)
    public SecurityFilterChain publicShareLinks(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/v1/public/share-links/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/share-links/{id}",
                                "/api/v1/public/share-links/{id}/download").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                // Cookie endpoints use session-bound synchronizer CSRF checks and custom-header admission.
                // Other APIs remain bearer-only.
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(new AuthTransportFilter(), BearerTokenAuthenticationFilter.class)

                // Do not maintain authentication using HTTP sessions.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                // Disable authentication mechanisms we aren't using.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                // Configure public and protected endpoints.
                .authorizeHttpRequests(auth -> auth

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/health"
                        ).permitAll()

                        .anyRequest().authenticated()
                )

                // Validate bearer JWTs using our JwtDecoder bean.
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> {}).authenticationEntryPoint((request, response, failure) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            com.vaultdrive.common.exception.ApiError.write(response, 401, "Authentication required");
                        })
                )

                // Explicitly handle unauthenticated and forbidden requests.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                            (request, response, failure) -> {
                                response.setHeader("WWW-Authenticate", "Bearer");
                                com.vaultdrive.common.exception.ApiError.write(response, 401, "Authentication required");
                            }
                        )
                        .accessDeniedHandler((request, response, failure) ->
                            com.vaultdrive.common.exception.ApiError.write(response, 403, "Permission denied"))
                );

        return http.build();
    }
}
