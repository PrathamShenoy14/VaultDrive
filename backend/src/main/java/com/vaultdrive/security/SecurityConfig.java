package com.vaultdrive.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.http.HttpMethod;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        AccessDeniedHandlerImpl accessDeniedHandler =
                new AccessDeniedHandlerImpl();

        accessDeniedHandler.setErrorPage(null);

        http
                // Our API authenticates using Authorization headers,
                // not automatically transmitted session cookies.
                .csrf(csrf -> csrf.disable())

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
                                "/api/v1/auth/login"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/health"
                        ).permitAll()

                        .anyRequest().authenticated()
                )

                // Validate bearer JWTs using our JwtDecoder bean.
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> {})
                )

                // Explicitly handle unauthenticated and forbidden requests.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                            new BearerTokenAuthenticationEntryPoint()
                        )
                        .accessDeniedHandler(accessDeniedHandler)
                );

        return http.build();
    }
}