package com.qqmu.jync.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.SystemSetting;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {
}
