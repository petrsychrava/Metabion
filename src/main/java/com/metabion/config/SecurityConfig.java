package com.metabion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.metabion.domain.RoleName;
import com.metabion.repository.UserRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String PUBLIC_STAFF_INVITATION_ACCEPT_POST = "/api/staff-invitations/accept";

    private static final String[] PUBLIC_AUTH_POSTS = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/forgot-password",
            "/api/auth/reset-password"
    };

    private static final String[] PUBLIC_MVC_GETS = {
            "/",
            "/login",
            "/register",
            "/verify",
            "/forgot-password",
            "/reset-password",
            "/staff-invitations/accept",
            "/error"
    };

    private static final String[] PUBLIC_MVC_POSTS = {
            "/login",
            "/register",
            "/forgot-password",
            "/reset-password",
            "/staff-invitations/accept"
    };

    private static final String[] PUBLIC_STATIC = {
            "/css/**",
            "/js/**",
            "/images/**",
            "/favicon.ico"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository users) {
        return email -> users.findByEmail(email)
                .map(user -> org.springframework.security.core.userdetails.User
                        .withUsername(user.getEmail())
                        .password(user.getPasswordHash())
                        .disabled(!user.isEnabled())
                        .accountLocked(user.isLocked())
                        .authorities(user.roleNames().stream()
                                .map(role -> new SimpleGrantedAuthority(RoleName.from(role).authority()))
                                .toList())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(UserDetailsService userDetailsService,
                                                               PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, RateLimitingFilter rateLimitingFilter) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(PUBLIC_AUTH_POSTS)
                        .ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(
                                HttpMethod.POST, PUBLIC_STAFF_INVITATION_ACCEPT_POST))
                        .ignoringRequestMatchers(req -> "GET".equalsIgnoreCase(req.getMethod()))
                )
                .sessionManagement(s -> s
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(sf -> sf.changeSessionId())
                        .maximumSessions(3)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_STATIC).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_MVC_GETS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_MVC_POSTS).permitAll()
                        .requestMatchers(PUBLIC_AUTH_POSTS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_STAFF_INVITATION_ACCEPT_POST).permitAll()
                        .requestMatchers("/api/auth/verify").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/staff-invitations").hasRole("ADMIN")
                        .requestMatchers("/admin/staff-invitations/**").hasRole("ADMIN")
                        .requestMatchers("/app", "/logout").authenticated()
                        .requestMatchers("/api/auth/logout").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                .headers(headers -> headers
                        .httpStrictTransportSecurity(h -> h
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000))
                        .frameOptions(frame -> frame.sameOrigin())
                        .contentTypeOptions(opts -> {
                        })
                        .referrerPolicy(r -> r.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}
