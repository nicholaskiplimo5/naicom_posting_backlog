package com.turnkey.naicombacklog.repository;

import com.turnkey.naicombacklog.model.IncomingRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IncomingRequestRepository extends JpaRepository<IncomingRequest, Long> {
}
