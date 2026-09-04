package com.synctool.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.synctool.model.AiProvider;

public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {

    Optional<AiProvider> findByName(String name);

    List<AiProvider> findAllByOrderByNameAsc();

    Optional<AiProvider> findFirstByEnabledTrue();

    /**
     * Clears the enabled flag on every row in one statement.
     *
     * <p>A bulk update rather than a read-modify-write loop: enabling is "switch to this one",
     * and doing it as a single statement inside the same transaction leaves no window in which
     * two rows are enabled at once.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiProvider p set p.enabled = false where p.enabled = true")
    int disableAll();
}
