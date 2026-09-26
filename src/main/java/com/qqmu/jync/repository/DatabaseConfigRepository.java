package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;

public interface DatabaseConfigRepository extends JpaRepository<DatabaseConfig, Long> {

    Optional<DatabaseConfig> findByName(String name);

    List<DatabaseConfig> findAllByOrderByNameAsc();

    Page<DatabaseConfig> findByRoleOrderByNameAsc(ConnectionRole role, Pageable pageable);

    List<DatabaseConfig> findByRoleOrderByNameAsc(ConnectionRole role);
}
