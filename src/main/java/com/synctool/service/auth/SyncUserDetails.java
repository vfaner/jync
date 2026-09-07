package com.synctool.service.auth;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.synctool.model.AppUser;
import com.synctool.model.UserRole;

import lombok.Getter;

/**
 * The signed-in user, as the rest of the application sees it.
 *
 * <p>Carries two things beyond what Spring Security needs: the {@link UserRole} so templates can
 * hide write controls without pulling in a Thymeleaf security dialect, and
 * {@link #isUsingDefaultPassword()} so the layout can warn about it.
 *
 * <p>The default-password check is resolved <em>once, at sign-in</em>, and carried here. BCrypt is
 * intentionally slow, so testing it on every request to render a banner would tax every page view
 * for a fact that cannot change mid-session — changing the password ends the session.
 */
@Getter
public class SyncUserDetails implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final UserRole role;
    private final boolean usingDefaultPassword;

    public SyncUserDetails(AppUser user, boolean usingDefaultPassword) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.role = user.getRole();
        this.usingDefaultPassword = usingDefaultPassword;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
