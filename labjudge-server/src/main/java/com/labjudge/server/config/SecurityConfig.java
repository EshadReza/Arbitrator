package com.labjudge.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.labjudge.server.security.JwtAuthFilter;

/**
 * Owner: Eshad. Written as PATH PATTERNS, not per-endpoint rules, so Mahir
 * never needs to edit this file when adding submission/announcement endpoints
 * (WORKFLOW_PLAN §3 friction table).
 *
 *   /api/auth/**   permitAll
 *   /api/admin/**  ADMIN role   (+ loopback via LoopbackAdminFilter, D3)
 *   /admin/**      loopback only (static panel; JWT enforced by its JS calls)
 *   /ws/**         permitAll here — the JWT is checked in JwtHandshakeInterceptor
 *   /api/**        authenticated
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())   // stateless JWT, no cookies
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(eh ->
                    eh.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/admin/**").permitAll()   // gated by LoopbackAdminFilter
                    .requestMatchers("/ws/**").permitAll()      // gated by JwtHandshakeInterceptor
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
