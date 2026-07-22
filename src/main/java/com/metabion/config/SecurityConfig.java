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
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.metabion.domain.RoleName;
import com.metabion.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String PUBLIC_STAFF_INVITATION_ACCEPT_POST = "/api/staff-invitations/accept";
    private static final String OAUTH_TOKEN_ENDPOINT = "/oauth/token";
    private static final String OAUTH_REGISTER_ENDPOINT = "/oauth/register";

    private static final String[] MCP_ENDPOINTS = {
            "/api/mcp",
            "/api/mcp/**"
    };

    private static final String[] PUBLIC_AUTH_POSTS = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/forgot-password",
            "/api/auth/reset-password"
    };

    private static final String[] PUBLIC_OAUTH_GETS = {
            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-authorization-server",
            "/oauth/authorize"
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
            "/staff-invitations/accept",
            "/preferences/language"
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
    SecurityContextRepository securityContextRepository() {
        return new McpSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           RateLimitingFilter rateLimitingFilter,
                                           PatientBearerTokenAuthenticationFilter patientBearerTokenAuthenticationFilter,
                                           McpLocalhostFilter mcpLocalhostFilter,
                                           OAuthAuthorizationProperties oauthProperties,
                                           SecurityContextRepository securityContextRepository) throws Exception {
        var loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login");
        var unauthorizedEntryPoint = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        var mcpUnauthorizedEntryPoint = (org.springframework.security.web.AuthenticationEntryPoint) (request, response, authException) -> {
            response.setHeader("WWW-Authenticate", bearerChallenge(oauthProperties));
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        };
        var authenticationEntryPoint = DelegatingAuthenticationEntryPoint.builder()
                .addEntryPointFor(loginEntryPoint,
                        PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/app"))
                .addEntryPointFor(loginEntryPoint,
                        PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/app/**"))
                .addEntryPointFor(mcpUnauthorizedEntryPoint,
                        PathPatternRequestMatcher.pathPattern("/api/mcp"))
                .addEntryPointFor(mcpUnauthorizedEntryPoint,
                        PathPatternRequestMatcher.pathPattern("/api/mcp/**"))
                .defaultEntryPoint(unauthorizedEntryPoint)
                .build();

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(PUBLIC_AUTH_POSTS)
                        .ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(
                                HttpMethod.POST, OAUTH_TOKEN_ENDPOINT))
                        .ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(
                                HttpMethod.POST, OAUTH_REGISTER_ENDPOINT))
                        .ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(
                                HttpMethod.POST, PUBLIC_STAFF_INVITATION_ACCEPT_POST))
                        .ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/mcp"))
                        .ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/mcp/**"))
                        .ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/mcp"))
                        .ignoringRequestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/mcp/**"))
                        .ignoringRequestMatchers(req -> "GET".equalsIgnoreCase(req.getMethod()))
                )
                .sessionManagement(s -> s
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(sf -> sf.changeSessionId())
                        .maximumSessions(3)
                )
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_STATIC).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_MVC_GETS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_OAUTH_GETS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_MVC_POSTS).permitAll()
                        .requestMatchers(PUBLIC_AUTH_POSTS).permitAll()
                        .requestMatchers(HttpMethod.POST, OAUTH_TOKEN_ENDPOINT).permitAll()
                        .requestMatchers(HttpMethod.POST, OAUTH_REGISTER_ENDPOINT).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_STAFF_INVITATION_ACCEPT_POST).permitAll()
                        .requestMatchers(HttpMethod.POST, "/oauth/authorize").authenticated()
                        .requestMatchers("/api/auth/verify").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/staff-invitations").hasRole("ADMIN")
                        .requestMatchers("/app/staff-invitations/**").hasRole("ADMIN")
                        .requestMatchers("/app/assignment-management", "/app/assignment-management/**")
                            .hasAnyRole("COORDINATOR", "ADMIN")
                        .requestMatchers("/app", "/app/**", "/logout").authenticated()
                        .requestMatchers("/api/auth/logout").authenticated()
                        .requestMatchers(MCP_ENDPOINTS).authenticated()
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
                        .authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(mcpLocalhostFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(patientBearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
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

    private static String bearerChallenge(OAuthAuthorizationProperties oauthProperties) {
        return "Bearer resource_metadata=\""
                + oauthProperties.issuer()
                + "/.well-known/oauth-protected-resource\"";
    }
}
