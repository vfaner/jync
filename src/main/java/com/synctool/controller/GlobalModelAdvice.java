package com.synctool.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.synctool.config.SyncProperties;
import com.synctool.model.UserRole;
import com.synctool.service.auth.SyncUserDetails;

/**
 * Model attributes the shared layout needs on every page.
 *
 * <p>The REST endpoints also match this advice, but a model attribute is inert for a
 * {@code @ResponseBody} handler, so there is nothing to exclude.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    private final String githubUrl;
    private final SyncProperties properties;

    public GlobalModelAdvice(@Value("${app.github-url}") String githubUrl,
                             SyncProperties properties) {
        this.githubUrl = githubUrl;
        this.properties = properties;
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
