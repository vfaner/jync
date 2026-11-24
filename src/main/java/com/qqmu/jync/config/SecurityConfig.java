package com.qqmu.jync.config;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.service.auth.ExternalSessionSignIn;
import com.qqmu.jync.service.auth.ForcePasswordChangeFilter;
import com.qqmu.jync.service.auth.LoginAttemptService;
import com.qqmu.jync.service.auth.LoginLockoutFilter;
import com.qqmu.jync.service.auth.SsoHeaderAuthenticationFilter;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.auth.TransportSecurityFilter;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;
import com.qqmu.jync.service.settings.RequireHttpsResolver;
import com.qqmu.jync.service.tls.TlsRuntimeState;
import com.qqmu.jync.util.AuditOrigin;
import com.qqmu.jync.util.ClientIpResolver;
import com.qqmu.jync.util.CryptoUtil;

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
            "/login", "/login/local", "/sso/entry",
            "/css/**", "/js/**", "/vendor/**", "/assets/**", "/favicon.ico",
            // A Prometheus server cannot hold a session. The controller itself demands the
            // bearer token and closes the path when the token is unset, so "public" here
            // means "outside form login", not "unauthenticated".
            "/api/metrics/prometheus"
    };

    /**
     * The administrative surface: settings, credentials of other systems, and the audit trail.
     *
     * <p>An operator edits business configuration, not the installation; these pages and polls
     * belong to the administrator alone. The matching writes are listed in
     * {@link #ADMIN_ONLY_POST} so the blanket writer rule below cannot reach them.
     */
    static final String[] ADMIN_ONLY_GET = {
            "/ai/new", "/ai/*/edit",
            // The external-authentication page holds the endpoints credentials are sent to and
            // the shared secret's configuration; only an administrator configures SSO. The
            // pre-1.8 URL stays listed so its redirect is admin-only too, rather than
            // advertising the new address.
            "/external-auth",
            "/oa/settings",
            // The alerting page holds the webhook URL — a bearer credential — and every POST
            // that changes it is admin-only anyway, so the GET is locked with them.
            "/alert-settings",
            // System settings writes the metadata-database test, the transport-security switch,
            // the AI switch and the configuration package — every one of them an administrative
            // action, and every POST is admin-only by the rules below.
            "/system-config",
            // The audit trail is about what administrators did; an operator has no use for it,
            // and showing one would advertise that their own actions are never in it.
            "/audits",
            // One-click upgrade is an administrator action end to end; an operator is never
            // shown the button, so the status poll must not answer for one either.
            "/api/upgrade/status"
    };

    /**
     * Pages that only render a form, plus the lookups they use.
     *
     * <p>None of these writes anything, so the verb rule below would let a viewer open them. They
     * are locked anyway: handing a read-only user a form whose Save button is guaranteed to fail
     * is a worse experience than not offering it. An operator may open every one of them; which
     * rows that operator may then edit is the handler's ownership check, not this list's.
     */
    static final String[] WRITER_ONLY_GET = {
            "/databases/new", "/databases/*/edit",
            // The connection's performance page reads the target database's own runtime
            // counters (sessions, cache hit, locks) — figures about every user of that server,
            // not just this application. Its JSON poll is the same data and is gated with it.
            "/databases/*/performance", "/databases/*/metrics",
            "/projects/new", "/projects/*/edit",
            // The bulk wizard reads a whole source's table listing and creates tasks in one
            // go; the POSTs follow the blanket writer rule below, so the GET must too
            // — otherwise a viewer is shown a form whose every submit is refused.
            "/projects/bulk",
            "/projects/*/performance", "/projects/*/metrics",
            // ETL forms are the same kind of configuration page; the listing itself stays
            // readable so a viewer can see what is scheduled. The batch form sits one level
            // deeper, so /etl/*/edit does not cover it.
            "/etl/new", "/etl/*/edit",
            "/etl/batches/new", "/etl/batches/*/edit",
            // Dedicated-migration tasks copy whole indices and can delete the target index
            // in OVERWRITE mode; their forms, like every other configuration form, are for
            // writers. The landing pages and task details stay readable for viewers. The
            // connection pages hold search-endpoint credentials, so they are locked too.
            "/migrations/connections", "/migrations/connections/new",
            "/migrations/connections/*/edit",
            // Redis targets hold connection credentials; the list is a configuration page
            // too, like every other connection listing.
            "/redis/targets", "/redis/targets/new", "/redis/targets/*/edit",
            "/migrations/*/new", "/migrations/*/*/edit",
            // Index discovery dials the configured search endpoint to list indices; advisory
            // for the form, but still a call into another system, like the health dots.
            "/api/migrations/indices",
            // Health dots open one connection per listed database. Read-only in the HTTP
            // sense, but it dials production sources, exactly like the probes below; the
            // column is hidden from viewers in the page for the same reason.
            "/api/databases/health",
            // The ETL form's target-table picker opens a connection to the freshly chosen
            // target to list its tables. Advisory for the form, but still a dial into a
            // production database, so it is locked like the probe above.
            "/api/databases/*/tables",
            // Driver discovery reads the class names out of the jar the caller names — a path
            // the same caller may already put on its own connection — so it follows the
            // connection form and admits operators. A viewer is still refused: it is a form
            // helper, and no viewer may open the form.
            "/api/databases/discover-drivers",
            // Preview returns row data straight out of the configured databases, and the two
            // lookups expose an arbitrary table's schema. The CDC probe is read-only in the
            // HTTP sense but opens a connection to the configured source and runs capability
            // queries there. Row-count opens both connections and scans whole tables. The
            // precheck likewise dials both sides and reads their metadata. All six back
            // writer-only controls on the project detail page; a viewer must not call them
            // directly.
            "/api/projects/*/preview",
            "/api/projects/*/columns",
            "/api/projects/*/cursor-candidates",
            "/api/projects/*/cdc-probe",
            "/api/projects/*/row-count",
            "/api/projects/*/row-count-job",
            "/api/projects/*/row-count-job/*",
            "/api/projects/*/precheck"
    };

    /**
     * Writes that belong to the administrator even though every writer may POST elsewhere.
     *
     * <p>These must precede the blanket POST rule: it would otherwise admit an operator to the
     * system surface one verb later. Every entry is asserted against the real mappings by
     * {@code SecurityConfigTest}, so this list cannot quietly go stale.
     */
    static final String[] ADMIN_ONLY_POST = {
            "/system-config/**", "/external-auth/**", "/alert-settings/**",
            "/ai/**", "/api/ai/**",
            "/api/upgrade/**", "/api/diagnostics/**",
            "/change-logs/purge", "/audits/purge", "/system-logs/purge",
            "/api/version/ignore", "/api/version/artifact/dismiss"
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
    public LoginLockoutFilter loginLockoutFilter(LoginAttemptService attemptService,
                                                 OaAuthConfigService configService) {
        return new LoginLockoutFilter(attemptService, configService);
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

    /** The gate that keeps a default-password account on the change form until it is changed. */
    @Bean
    public ForcePasswordChangeFilter forcePasswordChangeFilter() {
        return new ForcePasswordChangeFilter();
    }

    /** Same reason as the lockout filter: it belongs to the security chain, not a second one. */
    @Bean
    public FilterRegistrationBean<ForcePasswordChangeFilter> forcePasswordChangeFilterRegistration(
            ForcePasswordChangeFilter filter) {
        FilterRegistrationBean<ForcePasswordChangeFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** Header-based SSO. Declared here so a MVC slice that scans the chain never needs it. */
    @Bean
    public SsoHeaderAuthenticationFilter ssoHeaderAuthenticationFilter(
            OaAuthConfigService configService,
            ExternalSessionSignIn signIn,
            CryptoUtil crypto) {
        return new SsoHeaderAuthenticationFilter(configService, signIn, crypto);
    }

    /** Same treatment as the lockout filter: in the security chain only, exactly once. */
    @Bean
    public FilterRegistrationBean<SsoHeaderAuthenticationFilter> ssoHeaderAuthenticationFilterRegistration(
            SsoHeaderAuthenticationFilter filter) {
        FilterRegistrationBean<SsoHeaderAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * HTTPS enforcement plus HSTS / CSP / Referrer-Policy / Permissions-Policy.
     *
     * <p>Off by default: a deployment behind a TLS reverse proxy is the normal case, and
     * turning this on without naming the proxy in {@code app.security.trusted-proxies}
     * would reject every forwarded request as plaintext. The resolver is consulted per
     * request so the 系统配置 page can toggle the switch without a restart; an explicit
     * configuration value still wins and pins it.
     */
    @Bean
    public TransportSecurityFilter transportSecurityFilter(
            RequireHttpsResolver requireHttps,
            TlsRuntimeState tlsState,
            @Value("${app.tls.redirect-host:}") String redirectHost) {
        // The snapshot is read per request, not captured: it is fixed for the life of the process,
        // but taking it lazily keeps this bean constructible before the web server exists.
        return new TransportSecurityFilter(requireHttps::isRequired, () -> tlsState, redirectHost);
    }

    /** Same reason as the lockout filter: in the security chain only, exactly once. */
    @Bean
    public FilterRegistrationBean<TransportSecurityFilter> transportSecurityFilterRegistration(
            TransportSecurityFilter filter) {
        FilterRegistrationBean<TransportSecurityFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           LoginAttemptService attemptService,
                                           LoginLockoutFilter loginLockoutFilter,
                                           SsoHeaderAuthenticationFilter ssoHeaderAuthenticationFilter,
                                           ForcePasswordChangeFilter forcePasswordChangeFilter,
                                           TransportSecurityFilter transportSecurityFilter,
                                           AdminAuditService auditService,
                                           OaAuthConfigService configService) throws Exception {
        http
                // First in the chain: an HTTP request is refused before it can authenticate,
                // and the headers land even on the 401/403 Spring Security writes itself.
                .addFilterBefore(transportSecurityFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(loginLockoutFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(ssoHeaderAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                // After authentication, because it reads the principal. The success handler below
                // sends the first request of a seeded account straight here as well; this filter
                // is what covers every request after it.
                .addFilterAfter(forcePasswordChangeFilter,
                        UsernamePasswordAuthenticationFilter.class)

                .authorizeHttpRequests(reg -> reg
                        .requestMatchers(PUBLIC).permitAll()

                        // The OA sign-in form posts before anyone is signed in. It must sit
                        // above the blanket POST rule below, which would otherwise demand an
                        // admin role from the very person trying to log in.
                        .requestMatchers(HttpMethod.POST, "/login/oa").permitAll()

                        // Both roles must be able to POST here. This has to precede the blanket
                        // POST rule below, or a viewer would be shown a password form they are
                        // forbidden to submit.
                        .requestMatchers("/account/password").authenticated()

                        .requestMatchers(HttpMethod.GET, ADMIN_ONLY_GET).hasRole("ADMIN")

                        // Must precede the blanket writer rule below, or an operator would
                        // reach the system surface one verb later.
                        .requestMatchers(HttpMethod.POST, ADMIN_ONLY_POST).hasRole("ADMIN")

                        // Only writers reach the form pages; which rows they may edit is the
                        // handler's ownership check, not this list's.
                        .requestMatchers(HttpMethod.GET, WRITER_ONLY_GET)
                        .hasAnyRole("ADMIN", "OPS")

                        // Every mutation in the app is a POST; see the class comment.
                        .requestMatchers(HttpMethod.POST, "/**").hasAnyRole("ADMIN", "OPS")

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
                        .logoutSuccessHandler(logoutSuccessHandler(configService))
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
     *
     * <p>The saved request is read through {@link DeniedFreeRequestCache}: a destination that
     * only exists to show the "no permission" banner must never become the landing page.
     */
    private org.springframework.security.web.authentication.AuthenticationSuccessHandler
            authenticationSuccessHandler(LoginAttemptService attemptService,
                                         AdminAuditService auditService) {
        SavedRequestAwareAuthenticationSuccessHandler delegate =
                new SavedRequestAwareAuthenticationSuccessHandler();
        delegate.setDefaultTargetUrl("/");
        delegate.setRequestCache(new DeniedFreeRequestCache());
        return (request, response, authentication) -> {
            attemptService.recordSuccess(attemptKey(request));
            // Audit before the redirect: a failure here must not lose the sign-in record,
            // and the delegate commits the response.
            auditService.record(AuditAction.LOGIN,
                    AuditDetail.of("audit.detail.login", AuditOrigin.of(request)),
                    authentication.getName());
            // The seeded password is public; land on the home page, where the layout opens the
            // change-password modal and refuses to close it, instead of the saved request, which
            // the filter below would bounce back here anyway (one hop less).
            if (authentication.getPrincipal() instanceof SyncUserDetails
                    && ((SyncUserDetails) authentication.getPrincipal()).isUsingDefaultPassword()) {
                response.sendRedirect(
                        request.getContextPath() + ForcePasswordChangeFilter.HOME_PATH);
                return;
            }
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
                    AuditDetail.of("audit.detail.loginFailed",
                            failureReason(exception), AuditOrigin.of(request)),
                    request.getParameter("username"));
            // The local form's own page, not /login: under external authentication /login may
            // redirect to the portal, which would swallow the error message entirely.
            String target = attemptService.isBlocked(key)
                    ? "/login/local?locked" : "/login/local?error";
            response.sendRedirect(request.getContextPath() + target);
        };
    }

    /** Stable, localizable slug for the login failure shown in the audit trail. */
    private static String failureReason(RuntimeException exception) {
        String slug;
        if (exception instanceof BadCredentialsException) {
            slug = "bad-credentials";
        } else if (exception instanceof LockedException) {
            slug = "locked";
        } else if (exception instanceof DisabledException) {
            slug = "disabled";
        } else if (exception instanceof AccountExpiredException) {
            slug = "account-expired";
        } else if (exception instanceof CredentialsExpiredException) {
            slug = "credentials-expired";
        } else if (exception instanceof InternalAuthenticationServiceException) {
            slug = "service-unavailable";
        } else {
            slug = "other";
        }
        return "@reason:" + slug;
    }

    /**
     * Sign-out target: the administrator may send the browser on to the portal's own logout page.
     * Anything that is neither a path in this application nor http(s) falls back to /login?logout.
     */
    private LogoutSuccessHandler logoutSuccessHandler(OaAuthConfigService configService) {
        return (request, response, authentication) -> {
            String target = "/login?logout";
            String configured = configService.load().getLogoutRedirectUrl();
            if (configured != null && !configured.isBlank()) {
                String value = configured.trim();
                if (value.startsWith("/") && !value.startsWith("//")) {
                    target = request.getContextPath() + value;
                } else if (value.startsWith("http://") || value.startsWith("https://")) {
                    target = value;
                }
            }
            response.sendRedirect(target);
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
            } else if (isAnonymous()) {
                // An anonymous caller cannot be denied a role; the only refusal that reaches this
                // branch is CSRF, i.e. a sign-in form posted from a page whose session is gone
                // (an old tab, a back/forward restore). Answering /?denied told that visitor the
                // wrong story, and the entry point then saved /?denied as the post-login
                // destination: the NEXT successful sign-in replayed it, so an administrator
                // landed on the home page under a "no permission" banner that was never true.
                // Send them back to the form with a notice instead.
                response.sendRedirect(request.getContextPath() + "/login/local?expired");
            } else {
                response.sendRedirect(request.getContextPath() + "/?denied");
            }
        };
    }

    /**
     * Whether the current request carries no authenticated principal.
     *
     * <p>The CSRF filter runs before the anonymous filter, so at a token rejection the context is
     * empty rather than holding an {@link AnonymousAuthenticationToken}; both mean "not signed
     * in". A restored session (an authenticated form re-post) is the case handled like a role
     * denial.
     */
    private static boolean isAnonymous() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication instanceof AnonymousAuthenticationToken;
    }

    /**
     * A saved-request cache that forgets destinations carrying the denied flag.
     *
     * <p>{@code /?denied} is a banner for someone who is signed in. A signed-out visitor who
     * lands on it — stale history, a bookmark, the old CSRF redirect — would have it saved and
     * replayed after signing in, showing the banner to an account that holds every right. There
     * is no legitimate reason to resume at that URL, so it is dropped and the default target is
     * used.
     */
    private static final class DeniedFreeRequestCache implements RequestCache {

        private final RequestCache delegate = new HttpSessionRequestCache();

        @Override
        public void saveRequest(HttpServletRequest request, HttpServletResponse response) {
            this.delegate.saveRequest(request, response);
        }

        @Override
        public SavedRequest getRequest(HttpServletRequest request, HttpServletResponse response) {
            SavedRequest saved = this.delegate.getRequest(request, response);
            if (saved != null && carriesDeniedFlag(saved.getRedirectUrl())) {
                this.delegate.removeRequest(request, response);
                return null;
            }
            return saved;
        }

        @Override
        public HttpServletRequest getMatchingRequest(HttpServletRequest request,
                                                     HttpServletResponse response) {
            return this.delegate.getMatchingRequest(request, response);
        }

        @Override
        public void removeRequest(HttpServletRequest request, HttpServletResponse response) {
            this.delegate.removeRequest(request, response);
        }
    }

    /**
     * Whether a URL's query names {@code denied}, the home page's banner trigger.
     *
     * <p>Name, not substring: a future {@code ?deniedAt=...} or {@code ?x=denied} is a different
     * thing. The saved URL is absolute and already ends in Spring Security 7's {@code continue}
     * marker, which parses as its own empty parameter and is simply skipped.
     */
    private static boolean carriesDeniedFlag(String url) {
        if (url == null) {
            return false;
        }
        int mark = url.indexOf('?');
        if (mark < 0) {
            return false;
        }
        String query = url.substring(mark + 1);
        int fragment = query.indexOf('#');
        if (fragment >= 0) {
            query = query.substring(0, fragment);
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if ("denied".equals(name)) {
                return true;
            }
        }
        return false;
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
