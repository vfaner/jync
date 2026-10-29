package com.qqmu.jync.service.auth;

import java.io.IOException;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.qqmu.jync.model.OaAuthConfig;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.oa.OaAuthConfigService;
import com.qqmu.jync.util.ClientIpResolver;
import com.qqmu.jync.util.CryptoUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Signs a request in from the identity header a trusted reverse proxy injected.
 *
 * <p>The trust boundary is the <em>direct TCP peer</em>: only loopback or a host listed in
 * {@code app.security.trusted-proxies} may assert an identity this way. A LAN client talking to
 * Jync directly is therefore ignored no matter what header it sends. The filter sits after
 * {@code CsrfFilter} (which precedes the username/password filter) and before
 * {@code UsernamePasswordAuthenticationFilter}.
 */
@Slf4j
public class SsoHeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final List<String> SKIPPED_PATHS = List.of(
            "/login", "/login/local", "/login/oa", "/favicon.ico", "/error");
    private static final List<String> SKIPPED_PREFIXES = List.of(
            "/sso/", "/css/", "/js/", "/img/", "/vendor/", "/assets/");

    private final OaAuthConfigService configService;
    private final ExternalSessionSignIn signIn;
    private final CryptoUtil crypto;

    public SsoHeaderAuthenticationFilter(OaAuthConfigService configService,
                                         ExternalSessionSignIn signIn,
                                         CryptoUtil crypto) {
        this.configService = configService;
        this.signIn = signIn;
        this.crypto = crypto;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = RequestPaths.withinApplication(request);
        return SKIPPED_PATHS.contains(path)
                || SKIPPED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Standing down for a request that already carries a principal must come first: this
        // filter runs on every page and API call, and the configuration read below is a query
        // against the metadata store. Checking the principal after the read charged that query
        // to every authenticated request purely to learn the filter had nothing to do.
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated()
                && !(existing instanceof AnonymousAuthenticationToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        OaAuthConfig config = configService.load();
        if (!config.isEnabled() || config.modeOrDefault() != OaAuthConfig.Mode.HEADER) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!ClientIpResolver.isTrustedPeer(request.getRemoteAddr())) {
            filterChain.doFilter(request, response);
            return;
        }

        String secretName = config.getSecretHeaderName();
        String presentedSecret = secretName == null ? null : request.getHeader(secretName);
        if (!ExternalIdentity.secretMatches(config, presentedSecret, crypto)) {
            log.warn("Refused a header-authenticated request: shared secret mismatch from {}",
                    request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        // A row saved before this mode existed cannot name a header; treat it as "no identity"
        // rather than letting getHeader(null) throw.
        String userHeader = config.getUserHeader();
        String username = userHeader == null ? null
                : ExternalIdentity.sanitize(request.getHeader(userHeader));
        if (username == null) {
            if (StringUtils.hasText(config.getLoginEntryUrl())) {
                response.sendRedirect(config.getLoginEntryUrl());
            } else {
                filterChain.doFilter(request, response);
            }
            return;
        }

        String adminHeader = config.getAdminHeader();
        boolean headerAdmin = adminHeader != null
                && ExternalIdentity.matches(request.getHeader(adminHeader), config.getAdminValue());
        UserRole role = ExternalIdentity.role(config, username, headerAdmin);
        signIn.signIn(request, response, username, role, "HEADER");
        filterChain.doFilter(request, response);
    }
}
