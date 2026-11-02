package com.qqmu.jync.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;

public interface MigrationConnectionRepository extends JpaRepository<MigrationConnection, Long> {

    Optional<MigrationConnection> findByName(String name);

    List<MigrationConnection> findAllByOrderByNameAsc();

    Page<MigrationConnection> findAllByOrderByNameAsc(Pageable pageable);

    List<MigrationConnection> findByKindInOrderByNameAsc(Collection<MigrationConnectionKind> kinds);
}
