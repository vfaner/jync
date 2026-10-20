package com.qqmu.jync.controller;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * Model attributes the shared layout needs on every page.
 *
 * <p>The REST endpoints also match this advice, but a model attribute is inert for a
 * {@code @ResponseBody} handler, so there is nothing to exclude.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    private static final DateTimeFormatter BUILD_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private final String githubUrl;
    private final SyncProperties properties;
    private final ObjectProvider<BuildProperties> buildProperties;

    public GlobalModelAdvice(@Value("${app.github-url}") String githubUrl,
                             SyncProperties properties,
                             ObjectProvider<BuildProperties> buildProperties) {
        this.githubUrl = githubUrl;
        this.properties = properties;
        this.buildProperties = buildProperties;
    }

    /**
     * Running artifact version with a leading {@code v} for the header and footer, or
     * {@code null} for IDE runs without {@code build-info.properties} (templates then hide it).
     */
    @ModelAttribute("appVersion")
    public String appVersion() {
        BuildProperties props = buildProperties.getIfAvailable();
        if (props == null || !StringUtils.hasText(props.getVersion())) {
            return null;
        }
        return "v" + props.getVersion().trim();
    }

    /**
     * Build timestamp of the running artifact as a UTC stamp, or {@code null} when the
     * artifact carries no build-info.
     *
     * <p>The version string alone is not enough to identify a running build: a repackaged
     * release keeps the same version. Support reads this stamp next to the version to tell
     * an overwritten same-version jar from the original. UTC (not the server's local zone)
     * keeps the stamp identical for users in any time zone.
     */
    @ModelAttribute("appBuildTime")
    public String buildTime() {
        BuildProperties props = buildProperties.getIfAvailable();
        Instant time = props == null ? null : props.getTime();
        if (time == null) {
            return null;
        }
        return BUILD_TIME_FORMATTER.format(time.atOffset(ZoneOffset.UTC));
    }

    /**
     * The global full-load batch default, shown as the empty field's placeholder.
     *
     * <p>An empty project field means "use the global default"; without the number on screen
     * there is no baseline for the user to tune against. A placeholder, not a prefilled
     * value: prefilling would save an explicit override for every project the user opens.
     */
    @ModelAttribute("fullLoadBatchDefault")
    public int fullLoadBatchDefault() {
        return properties.getFullLoadBatchSize();
    }

    /** The global full-load parallelism default, same placeholder contract as the batch. */
    @ModelAttribute("fullLoadParallelismDefault")
    public int fullLoadParallelismDefault() {
        return properties.getFullLoadParallelism();
    }

    /** Repository link used by the nav icon and the donate dialog. */
    @ModelAttribute("githubUrl")
    public String githubUrl() {
        return githubUrl;
    }

    /**
     * Whether the AI menu is shown at all.
     *
     * <p>Read from configuration on every request rather than cached, so an air-gapped
     * deployment can hide the feature without a code change.
     */
    @ModelAttribute("aiEnabled")
    public boolean aiEnabled() {
        return properties.getAi().isEnabled();
    }

    /**
     * The signed-in user's name, or {@code null} on the login page.
     *
     * <p>Exposing the identity through the existing advice is what lets templates write
     * {@code th:if="${isAdmin}"} without adding a Thymeleaf security dialect to the build — one
     * more dependency to hide a button is a poor trade when the layout already reads global
     * attributes from here.
     *
     * <p>A name rather than the principal object, because a name is all the layout needs and
     * publishing the object would couple the templates to whichever {@code UserDetails}
     * implementation happens to be in the context.
     */
    @ModelAttribute("currentUsername")
    public String currentUsername() {
        Authentication auth = authentication();
        return auth == null ? null : auth.getName();
    }

    /**
     * Drives every write control in the templates.
     *
     * <p>Read from the granted authorities rather than by casting the principal to
     * {@link SyncUserDetails}: the role is what the authorization rules actually check, so
     * checking the same thing here keeps the buttons and the filter chain from disagreeing.
     */
    @ModelAttribute("isAdmin")
    public boolean isAdmin() {
        Authentication auth = authentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> UserRole.ADMIN.authority().equals(a.getAuthority()));
    }

    /** Drives the banner nagging about the seeded password. */
    @ModelAttribute("usingDefaultPassword")
    public boolean usingDefaultPassword() {
        Authentication auth = authentication();
        return auth != null
                && auth.getPrincipal() instanceof SyncUserDetails
                && ((SyncUserDetails) auth.getPrincipal()).isUsingDefaultPassword();
    }

    /**
     * Drives the banner warning that stored connection passwords are protected by the
     * publicly shipped default key.
     *
     * <p>Both password and salt are checked: either one left at the default keeps the key
     * derivable from the downloadable jar. Authenticated only, like the password banner —
     * on the login page there are no secrets shown yet.
     */
    @ModelAttribute("usingDefaultCryptoKey")
    public boolean usingDefaultCryptoKey() {
        Authentication auth = authentication();
        return auth != null
                && (SyncProperties.DEFAULT_CRYPTO_PASSWORD.equals(properties.getCryptoPassword())
                        || SyncProperties.DEFAULT_CRYPTO_SALT.equals(properties.getCryptoSalt()));
    }

    /**
     * The current authentication, or {@code null} when there is effectively nobody signed in.
     *
     * <p>Anonymous authentication is folded into {@code null} deliberately: it is a real
     * {@code Authentication} whose name is the literal {@code "anonymousUser"}, which would
     * otherwise be rendered in the nav as if it were an account.
     */
    private Authentication authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth;
    }
}
