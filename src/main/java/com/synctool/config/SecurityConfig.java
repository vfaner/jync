package com.synctool.config;

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
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

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
            "/api/databases/discover-drivers"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
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
                        .defaultSuccessUrl("/", false)
                        .failureUrl("/login?error")
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
