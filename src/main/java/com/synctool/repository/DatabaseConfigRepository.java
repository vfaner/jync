package com.synctool.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.synctool.model.DatabaseConfig;
import com.synctool.model.DatabaseType;

public interface DatabaseConfigRepository extends JpaRepository<DatabaseConfig, Long> {

    Optional<DatabaseConfig> findByName(String name);

    boolean existsByName(String name);

    List<DatabaseConfig> findAllByOrderByNameAsc();

    long countByType(DatabaseType type);
}
