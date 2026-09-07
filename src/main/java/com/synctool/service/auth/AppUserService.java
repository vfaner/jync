package com.synctool.service.auth;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.synctool.model.AppUser;
import com.synctool.model.UserRole;
import com.synctool.repository.AppUserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Loads users for authentication, seeds the initial pair, and changes passwords.
 *
 * @see SyncUserDetails
 */
@Service
@Slf4j
public class AppUserService implements UserDetailsService {

    /**
     * Handed to both seeded accounts, and the value the layout warns about while it is still in
     * use. Public because the warning check and the tests both need the same constant — a second
     * copy of this string somewhere else is a bug waiting to happen.
     */
    public static final String DEFAULT_PASSWORD = "123456";

    public static final String ADMIN_USERNAME = "admin";
    public static final String VIEWER_USERNAME = "view";

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AppUserService(AppUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates the two starting accounts, but only when there are no users at all.
     *
     * <p>The emptiness check is the whole safety property. Seeding "if this username is missing"
     * would recreate an account the operator deliberately deleted, and seeding unconditionally
     * would reset a changed password on every restart — a silent security regression that nobody
     * would notice until someone tried the default and got in.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedIfEmpty() {
        if (repository.count() > 0) {
            return;
        }
        create(ADMIN_USERNAME, DEFAULT_PASSWORD, UserRole.ADMIN);
        create(VIEWER_USERNAME, DEFAULT_PASSWORD, UserRole.VIEWER);
        log.warn("Seeded users '{}' (ADMIN) and '{}' (VIEWER) with the default password. "
                + "Change both before exposing this instance.", ADMIN_USERNAME, VIEWER_USERNAME);
    }

    private void create(String username, String rawPassword, UserRole role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        repository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public SyncUserDetails loadUserByUsername(String username) {
        AppUser user = repository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + username));
        // Resolved here, at sign-in, and carried on the principal for the life of the session.
        boolean isDefault = passwordEncoder.matches(DEFAULT_PASSWORD, user.getPasswordHash());
        return new SyncUserDetails(user, isDefault);
    }

    @Transactional(readOnly = true)
    public List<AppUser> findAll() {
        return repository.findAllByOrderByUsernameAsc();
    }

    /**
     * Replaces a user's own password after verifying the current one.
     *
     * <p>Verifying the current password matters even though the caller is already authenticated:
     * it is what stops someone who walked up to an unlocked browser from locking the real owner
     * out of their own account.
     *
     * @throws IllegalArgumentException with a message-bundle key, so the page can render it in
     *     the user's language rather than showing an English sentence from the server
     */
    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword,
                                  String confirmPassword) {
        AppUser user = repository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("account.error.notFound"));

        if (currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("account.error.wrongCurrent");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("account.error.tooShort");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("account.error.mismatch");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("account.error.sameAsCurrent");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        repository.save(user);
        log.info("Password changed for user '{}'", username);
    }
}
