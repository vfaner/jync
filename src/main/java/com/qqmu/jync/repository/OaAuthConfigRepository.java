package com.qqmu.jync.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.OaAuthConfig;

/** The OA sign-in settings table always holds at most one row. */
public interface OaAuthConfigRepository extends JpaRepository<OaAuthConfig, Long> {
}
