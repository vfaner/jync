package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;

public interface DatabaseConfigRepository extends JpaRepository<DatabaseConfig, Long> {

    /** The two columns the Prometheus label map needs; the entity carries the credentials. */
    interface IdAndName {
        Long getId();

        String getName();
    }

    Optional<DatabaseConfig> findByName(String name);

    @Query("select c.id as id, c.name as name from DatabaseConfig c")
    List<IdAndName> findAllIdAndName();

    List<DatabaseConfig> findAllByOrderByNameAsc();

    Page<DatabaseConfig> findByRoleOrderByNameAsc(ConnectionRole role, Pageable pageable);

    List<DatabaseConfig> findByRoleOrderByNameAsc(ConnectionRole role);
}
