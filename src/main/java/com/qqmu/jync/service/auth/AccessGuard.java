package com.qqmu.jync.service.auth;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.qqmu.jync.model.UserRole;

/**
 * The one place "may this caller touch this resource" is answered.
 *
 * <p>Instance methods per request; {@link #current()} reads the same
 * {@link SecurityContextHolder} the filter chain populated, and resolves the role from the
 * granted authorities (not from the principal type) so tests can use either mock users or
 * {@link SyncUserDetails}.
 *
 * <p>Not a Spring bean on purpose: a {@code @Component} is invisible to {@code @WebMvcTest}
 * slices, and every controller would need a mock of it. The template side gets the same object
 * as the {@code access} model attribute from {@code GlobalModelAdvice}.
 */
public final class AccessGuard {

    /** Shared with the API exception handler and the layout's denial banner. */
    public static final String DENIED_KEY = "error.accessDenied";

    private final String username;
    private final UserRole role;

    private AccessGuard(String username, UserRole role) {
        this.username = username;
        this.role = role;
    }

    /** The caller of the current request, or a viewer when nobody is signed in. */
    public static AccessGuard current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            return new AccessGuard(null, UserRole.VIEWER);
        }
        return new AccessGuard(auth.getName(), roleOf(auth));
    }

    public static AccessGuard of(String username, UserRole role) {
        return new AccessGuard(username, role);
    }

    /** Background/internal work with full rights and no user identity. */
    public static AccessGuard system() {
        return new AccessGuard(null, UserRole.ADMIN);
    }

    private static UserRole roleOf(Authentication auth) {
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (UserRole.ADMIN.authority().equals(authority.getAuthority())) {
                return UserRole.ADMIN;
            }
            if (UserRole.OPS.authority().equals(authority.getAuthority())) {
                return UserRole.OPS;
            }
        }
        return UserRole.VIEWER;
    }

    public String username() {
        return username;
    }

    public UserRole role() {
        return role;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public boolean isOps() {
        return role == UserRole.OPS;
    }

    public boolean canWrite() {
        return role != UserRole.VIEWER;
    }

    /**
     * The single ownership rule: an administrator may edit anything, an operator only its own
     * resources, and a null owner (pre-v2.4.0 data) is the administrator's alone.
     */
    public boolean canEdit(String owner) {
        return isAdmin() || (isOps() && owner != null && owner.equals(username));
    }

    public void requireWritable() {
        if (!canWrite()) {
            throw denied();
        }
    }

    public void requireEditable(String owner) {
        if (!canEdit(owner)) {
            throw denied();
        }
    }

    /** Same rule as {@link #requireEditable}, named for the form-reference caller. */
    public void requireReferenceable(String owner) {
        requireEditable(owner);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException(DENIED_KEY);
    }
}
