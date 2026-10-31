package com.qqmu.jync.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.AlertConfig;

/** The alerting settings table always holds at most one row. */
public interface AlertConfigRepository extends JpaRepository<AlertConfig, Long> {
}
