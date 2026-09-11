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
     * Deletes whatever the batch already had staged in GIN_POLICY_TRANSACTIONS, then fetches the
     * policy row, builds the NAICOM regulator payload and inserts a fresh row holding it
     * (GTP_PAYLOAD).
     *
     * @return the generated payload JSON that was stored, or null if staging failed.
     */
    public String stageForNaicom(BigDecimal policyBatchNumber) {
        // Delete, then stage - the same order tps-apis' /restage uses. A row left from an earlier
        // attempt still carries that attempt's GTP_RESPONSE, GTP_RETRY_COUNTS and
        // GTP_POSTED_STATUS, and the old update path only ever rewrote GTP_PAYLOAD, so a re-run
        // posted a fresh payload against stale posting state. The insert below puts the row back
        // at 'NOT_POSTED' / 'Pending posting.' with the retry count reset.
        //
        // A delete count of zero is not an error, which is the one difference from /restage: that
        // endpoint answers 404 "No matching policies found" and stages nothing, which suits a
        // manual one-policy call, whereas most backlog batches have never been staged at all.
        //
        // Clearing the row up front does mean a batch whose payload cannot be built is left with
        // no staged row. That self-heals - nothing was posted, so it stays in the backlog
        // selection and the next run stages it again from scratch.
        int cleared = policyTransactionDao.deleteStagedPolicy(policyBatchNumber, "NAICOM");
        log.debug("Cleared {} staged NAICOM row(s) for batch {} before staging", cleared, policyBatchNumber);

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

        // Hand the risks we already loaded to the generator - it needs the very same rows for the
        // AUTO/MARINE insured-info lists, and would otherwise re-run get_policy_transaction_prc
        // for this batch a second time.
        PolicyTransactionResponseDto generated = generateRegulatorPayloadService.generateNaicomPayload(risk, transactions);
        String payload = generated.getRegulatorPayload();
        log.debug("Staging: policyBatchNumber={}, payload={}", policyBatchNumber, payload);
        if (payload == null) {
            log.error("Failed to build NAICOM payload for batch {}", policyBatchNumber);
            return null;
        }

        boolean saved = policyTransactionDao.saveDetails(policyBatchNumber, payload, "P", "NAICOM", null, true);
        if (!saved) {
            log.error("Failed to persist staged NAICOM payload for batch {}", policyBatchNumber);
            return null;
        }else{
            log.debug("Staged NAICOM payload for batch {} Success", policyBatchNumber);
        }
        return payload;
    }
}
