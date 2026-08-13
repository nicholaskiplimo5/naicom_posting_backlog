package com.turnkey.naicombacklog.repository;

import com.turnkey.naicombacklog.model.GinPolicyTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface GinPolicyTransactionRepository extends JpaRepository<GinPolicyTransactionEntity, BigDecimal> {

    List<GinPolicyTransactionEntity> findAllByGtpPolBatchNoAndGtpTargetRegulator(BigDecimal gtpPolBatchNo, String gtpTargetRegulator);

    List<GinPolicyTransactionEntity> findByGtpPolBatchNoAndGtpTargetRegulatorAndGtpIpuCode(BigDecimal gtpPolBatchNo, String gtpTargetRegulator, BigDecimal gtpIpuCode);

    int deleteByGtpPolBatchNo(@Param("batchNo") BigDecimal batchNo);
}
