package com.qqmu.jync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.qqmu.jync.model.RedisTarget;

public interface RedisTargetRepository extends JpaRepository<RedisTarget, Long> {

    List<RedisTarget> findAllByOrderByNameAsc();

    Optional<RedisTarget> findByName(String name);
}
