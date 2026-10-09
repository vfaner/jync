package com.qqmu.jync.config;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.auth.LoginAttemptService;
import com.qqmu.jync.service.auth.LoginLockoutFilter;
import com.qqmu.jync.util.ClientIpResolver;

/**
 * Login, roles, and CSRF.
 *
 * <p>The authorization rules lean on an audited fact: <strong>no GET in this application mutates
 * state.</strong> Every write — save, delete, enable, start, stop, sync-now, reset, AI draft,
 * syntax check — is a POST. That makes "may write" expressible as one rule over the HTTP verb
 * instead of an enumeration of paths, and an enumeration is exactly what rots when someone adds
 * an endpoint and forgets to list it.
 *
 * <p>Rule order is load-bearing; see the comments inline.
 */
@Configuration
public class SecurityConfig {

    /** Paths that must work before anyone has signed in. */
    private static final String[] PUBLIC = {
            "/login", "/css/**", "/js/**", "/vendor/**", "/assets/**", "/favicon.ico"
    };

    /**
     * Pages that only render a form, plus the driver-discovery lookup they use.
     *
     * <p>None of these writes anything, so the verb rule below would let a viewer open them. They
     * are locked anyway: handing a read-only user a form whose Save button is guaranteed to fail
     * is a worse experience than not offering it, and the driver lookup discloses server-side
     * filesystem paths to someone who has no use for them.
     */
    private static final String[] ADMIN_ONLY_GET = {
            "/databases/new", "/databases/*/edit",
            "/projects/new", "/projects/*/edit",
            "/ai/new", "/ai/*/edit",
            "/api/databases/discover-drivers",
            // The audit trail is about what admins did; a viewer has no use for it, and
            // showing one would advertise that their own actions are never in it.
            "/audits"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Declared here rather than {@code @Service}: in a {@code @WebMvcTest} slice, generic
     * {@code @Service} beans are not scanned, but this configuration is — and the lockout
     * filter in the chain below needs the service present in every servlet context.
     */
    @Bean
    public LoginAttemptService loginAttemptService() {
        return new LoginAttemptService();
    }

    /**
     * Declared here rather than {@code @Component}: a {@code @WebMvcTest} slice scans Filter
     * beans but not this configuration, so a component-scanned filter was instantiated there
     * without its service and failed the whole slice. Security is off in such tests — the
     * default auto-config chain is used — so the filter need not exist there at all.
     */
    @Bean
    public LoginLockoutFilter loginLockoutFilter(LoginAttemptService attemptService) {
        return new LoginLockoutFilter(attemptService);
    }

    /**
     * Keeps the lockout filter out of Boot's automatic servlet-chain registration. It is added to
     * the Spring Security chain manually below; registered twice it would run twice per request.
     */
    @Bean
    public FilterRegistrationBean<LoginLockoutFilter> loginLockoutFilterRegistration(
            LoginLockoutFilter filter) {
        FilterRegistrationBean<LoginLockoutFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           LoginAttemptService attemptService,
                                           LoginLockoutFilter loginLockoutFilter,
                                           AdminAuditService auditService) throws Exception {
        http
                .addFilterBefore(loginLockoutFilter, UsernamePasswordAuthenticationFilter.class)

                .authorizeHttpRequests(reg -> reg
                        .antMatchers(PUBLIC).permitAll()

                        // Both roles must be able to POST here. This has to precede the blanket
                        // POST rule below, or a viewer would be shown a password form they are
                        // forbidden to submit.
                        .antMatchers("/account/password").authenticated()

                        .antMatchers(HttpMethod.GET, ADMIN_ONLY_GET).hasRole("ADMIN")

                        // Every mutation in the app is a POST; see the class comment.
                        .antMatchers(HttpMethod.POST, "/**").hasRole("ADMIN")

                        // Everything left is a read.
                        .anyRequest().authenticated())

                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler(authenticationSuccessHandler(attemptService, auditService))
                        .failureHandler(authenticationFailureHandler(attemptService, auditService))
                        .permitAll())

                .logout(out -> out
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true))

                .exceptionHandling(ex -> ex
                        // Both mappings are required, and the API one must come first. A single
                        // defaultAuthenticationEntryPointFor is used for *every* request whether
                        // or not its matcher hits -- Spring only builds the delegating entry point
                        // once there are two. With only the JSON one registered, browsing to /
                        // while signed out answered 401 JSON instead of redirecting to the form.
                        .defaultAuthenticationEntryPointFor(
                                jsonEntryPoint(), SecurityConfig::isApiRequest)
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                AnyRequestMatcher.INSTANCE)
                        .accessDeniedHandler(accessDeniedHandler()));

        // CSRF stays on, with the default session-backed repository. Thymeleaf injects the hidden
        // field into every form that uses th:action -- which is all 14 of them -- and layout.html
        // publishes the token in a <meta> tag for postJson() in app.js.
        return http.build();
    }

    /**
     * Clears the failed-attempt counter, then performs the default saved-request redirect so that
     * a deep link is not lost on login.
     */
    private org.springframework.security.web.authentication.AuthenticationSuccessHandler
            authenticationSuccessHandler(LoginAttemptService attemptService,
                                         AdminAuditService auditService) {
        SavedRequestAwareAuthenticationSuccessHandler delegate =
                new SavedRequestAwareAuthenticationSuccessHandler();
        delegate.setDefaultTargetUrl("/");
        return (request, response, authentication) -> {
            attemptService.recordSuccess(attemptKey(request));
            // Audit before the redirect: a failure here must not lose the sign-in record,
            // and the delegate commits the response.
            auditService.record(AuditAction.LOGIN,
                    "Signed in from " + ClientIpResolver.resolve(request),
                    authentication.getName());
            delegate.onAuthenticationSuccess(request, response, authentication);
        };
    }

    /**
     * Records the failed password; the fifth consecutive failure lands the user on the lockout
     * message instead of the ordinary error message.
     */
    private org.springframework.security.web.authentication.AuthenticationFailureHandler
            authenticationFailureHandler(LoginAttemptService attemptService,
                                         AdminAuditService auditService) {
        return (request, response, exception) -> {
            String key = attemptKey(request);
            attemptService.recordFailure(key);
            // The typed-in username is attacker-controlled; the audit service sanitizes and
            // caps it before it becomes a row.
            auditService.record(AuditAction.LOGIN_FAILED,
                    exception.getClass().getSimpleName() + " from "
                            + ClientIpResolver.resolve(request),
                    request.getParameter("username"));
            String target = attemptService.isBlocked(key) ? "/login?locked" : "/login?error";
            response.sendRedirect(request.getContextPath() + target);
        };
    }

    private static String attemptKey(HttpServletRequest request) {
        return LoginAttemptService.key(
                request.getParameter("username"), ClientIpResolver.resolve(request));
    }

    /**
     * Answers a rejected write the way the caller can actually read it.
     *
     * <p>The JSON endpoints are consumed by {@code fetch()}, and {@code postJson()} falls back to
     * {@code "HTTP <status>"} when a response will not parse as JSON. Returning Spring's HTML
     * error page there would surface a role failure to the user as a bare status code.
     */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, denied) -> {
            if (isApiRequest(request)) {
                writeJson(response, HttpStatus.FORBIDDEN, "error.forbidden");
            } else {
                response.sendRedirect(request.getContextPath() + "/?denied");
            }
        };
    }

    /** Unauthenticated JSON calls get JSON, not a redirect to the login page's HTML. */
    private AuthenticationEntryPoint jsonEntryPoint() {
        return (request, response, authException) ->
                writeJson(response, HttpStatus.UNAUTHORIZED, "error.sessionExpired");
    }

    private static void writeJson(HttpServletResponse response, HttpStatus status, String messageKey)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + messageKey + "\"}");
    }

    private static boolean isApiRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path.startsWith("/api/");
    }
}
