package com.turnkey.naicombacklog.repository;

import com.turnkey.naicombacklog.model.BacklogFailureLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BacklogFailureLogRepository extends JpaRepository<BacklogFailureLog, Long> {
}
