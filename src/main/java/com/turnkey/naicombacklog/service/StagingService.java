package com.turnkey.naicombacklog.service;

import com.turnkey.naicombacklog.dao.PolicyTransactionDao;
import com.turnkey.naicombacklog.dto.regulatorPayload.PolicyTransactionDto;
import com.turnkey.naicombacklog.dto.regulatorPayload.PolicyTransactionResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ported (and simplified) from tps-apis' {@code StageNaicomNiidPayloadImpl.stageRegulatorPayload}.
 * That original method looped over both NIID and NAICOM regulators; this app only targets NAICOM,
 * so the NIID branch (and its risk-level "R" staging path) is dropped.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StagingService {

    private final PolicyTransactionDao policyTransactionDao;
    private final GenerateRegulatorPayloadService generateRegulatorPayloadService;

    /**
     * Fetches the policy row for the batch, builds the NAICOM regulator payload, and stores it
     * (GTP_PAYLOAD) in GIN_POLICY_TRANSACTIONS - same storage step the original performs.
     *
     * @return the generated payload JSON that was stored, or null if staging failed.
     */
    public String stageForNaicom(BigDecimal policyBatchNumber) {
        List<PolicyTransactionDto> transactions = policyTransactionDao.getPolicyDetails(policyBatchNumber);
        if (transactions.isEmpty()) {
            log.warn("No policy details found for batch {} - nothing to stage", policyBatchNumber);
            return null;
        }

        PolicyTransactionDto risk = transactions.get(0);
        String prevPolicyUniqueId = policyTransactionDao.getPrevPolicyUniqueID(policyBatchNumber);
        if (prevPolicyUniqueId != null) {
            risk.setPREV_POLICY_ID(prevPolicyUniqueId);
        }

        PolicyTransactionResponseDto generated = generateRegulatorPayloadService.generateNaicomPayload(risk);
        String payload = generated.getRegulatorPayload();
        log.info("Staging: policyBatchNumber={}, payload={}", policyBatchNumber, payload);
        if (payload == null) {
            log.error("Failed to build NAICOM payload for batch {}", policyBatchNumber);
            return null;
        }

        boolean saved = policyTransactionDao.saveDetails(policyBatchNumber, payload, "P", "NAICOM", null);
        if (!saved) {
            log.error("Failed to persist staged NAICOM payload for batch {}", policyBatchNumber);
            return null;
        }else{
            log.info("Staged NAICOM payload for batch {} Success", policyBatchNumber);
        }
        return payload;
    }
}
