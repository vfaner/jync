package com.qqmu.jync.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    /** Used by the configuration import to refuse a package that would leave no administrator. */
    long countByRole(UserRole role);
}
