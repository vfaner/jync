package com.qqmu.jync.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.UpdateVersionDismissal;

/** Storage for the single "ignore this version" acknowledgement. */
public interface UpdateVersionDismissalRepository
        extends JpaRepository<UpdateVersionDismissal, Long> {
}
