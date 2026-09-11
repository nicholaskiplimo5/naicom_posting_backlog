package com.turnkey.naicombacklog.repository;

import com.turnkey.naicombacklog.model.IncomingRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface IncomingRequestRepository extends JpaRepository<IncomingRequest, Long> {

    /**
     * Writes the outcome of a posting onto the audit row without going through
     * {@code save()}. The entity is detached by then (it has an id), so save() means merge,
     * and merge SELECTs the row back before updating it - a wasted round trip on every single
     * posting. These are the only two fields that change after the initial insert.
     */
    @Modifying
    @Transactional
    @Query("update IncomingRequest r set r.success = :success, r.responseBody = :responseBody where r.id = :id")
    int updateOutcome(@Param("id") Long id,
                      @Param("success") Boolean success,
                      @Param("responseBody") String responseBody);
}
