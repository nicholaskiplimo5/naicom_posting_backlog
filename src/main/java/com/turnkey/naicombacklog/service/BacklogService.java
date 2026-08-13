package com.turnkey.naicombacklog.service;

import com.turnkey.naicombacklog.dao.BacklogSelectDao;
import com.turnkey.naicombacklog.dao.PolicyTransactionDao;
import com.turnkey.naicombacklog.dto.naicom.NaicomPolicyDto;
import com.turnkey.naicombacklog.dto.naicom.NaicomPolicyResponseDto;
import com.turnkey.naicombacklog.enums.BacklogStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Orchestrates the backlog pipeline behind {@code POST /postbacklog}: given a policy batch
 * number and transaction type -
 *   1. SELECT  - confirm the policy/status exists and whether it's already staged for NAICOM
 *                (the "dummy query" step - a lightweight existence + backlog check, not the
 *                full stored-procedure fetch, which only runs if staging is actually needed).
 *   2. STAGE   - if not already staged, build and store the NAICOM payload (GTP_PAYLOAD).
 *   3. POST    - dispatch to post/endorse/renew/terminate/delete on NAICOM depending on the
 *                transaction type, exactly mirroring NaicomPolicyPostingController's dispatch,
 *                then record the outcome (and payload) back onto the staging row.
 *
 * A failure at any stage is caught, written to TEX_BACKLOG_FAILURE_LOG with the stage it
 * happened in, and reported back rather than propagating as a raw 500.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BacklogService {

    //private final BacklogSelectDao backlogSelectDao;
    private final StagingService stagingService;
    private final NaicomPostingService naicomPostingService;
    private final PolicyTransactionDao policyTransactionDao;
    private final BacklogFailureLogService failureLogService;

    @Transactional
    public BacklogResult processBacklog(BigDecimal policyBatchNumber, String transactionType) {
        log.info("Staging: policyBatchNumber={}, transactionType={}", policyBatchNumber, transactionType);
        try {
                String stagedPayload = stagingService.stageForNaicom(policyBatchNumber);
                log.info("Staging: Response={}, transactionType={}", stagedPayload, transactionType);

            if (stagedPayload == null) {
                    throw new IllegalStateException("Staging produced no payload for batch " + policyBatchNumber);
                }
            } catch (Exception e) {
                failureLogService.logFailure(policyBatchNumber, transactionType, BacklogStage.STAGE, e, null);
                return failure(BacklogStage.STAGE, HttpStatus.INTERNAL_SERVER_ERROR,
                        "Staging failed for batch " + policyBatchNumber + ": " + e.getMessage());
            }
        log.info("Posting: policyBatchNumber={}, transactionType={}", policyBatchNumber, transactionType);

        try {
            NaicomPolicyResponseDto response = post(policyBatchNumber, transactionType);
            log.info("After Posting: response={}, transactionType={}", response.toString(), transactionType);
            boolean success = Boolean.TRUE.equals(response.getSuccess());
            if (!success) {
                failureLogService.logFailure(policyBatchNumber, transactionType, BacklogStage.POST,
                        describeFailure(response), safeStagedPayloadForLog(policyBatchNumber));
            }
            return BacklogResult.builder()
                    .success(success)
                    .status(response.getStatus() != null ? response.getStatus() : HttpStatus.OK)
                    .failedStage(success ? null : BacklogStage.POST)
                    .message(response.getMessage())
                    .postingResponse(response)
                    .build();
        } catch (Exception e) {
            String payload = safeStagedPayloadForLog(policyBatchNumber);
            failureLogService.logFailure(policyBatchNumber, transactionType, BacklogStage.POST, e, payload);
            return failure(BacklogStage.POST, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Posting failed for batch " + policyBatchNumber + ": " + e.getMessage());
        }
    }

    /**
     * Same dispatch rules as {@code NaicomPolicyPostingController}: NB/SP/DC always post; EN/EX
     * post (new), endorse (premium increase) or terminate (premium decrease) depending on whether
     * a policy_unique_id already exists; RN posts (new) or renews; CN terminates; CO deletes.
     */
    private NaicomPolicyResponseDto post(BigDecimal policyBatchNumber, String transactionType) {
        BigInteger batchNumber = policyBatchNumber.toBigInteger();
        NaicomPolicyDto policy = naicomPostingService.getStagedPolicy(batchNumber);
        if (policy == null) {
            return NaicomPolicyResponseDto.builder()
                    .message("No payload has been staged for batch number: " + policyBatchNumber)
                    .status(HttpStatus.NOT_FOUND)
                    .success(false)
                    .build();
        }

        NaicomPolicyResponseDto response;
        switch (transactionType == null ? "" : transactionType) {
            case "NB", "SP", "DC" -> response = naicomPostingService.postPolicyToNaicom(batchNumber, policy);
            case "EN", "EX" -> {
                if (policy.getPolicy_unique_id() == null) {
                    response = naicomPostingService.postPolicyToNaicom(batchNumber, policy);
                } else if (policy.getTotal_premium() != null && policy.getTotal_premium().compareTo(BigDecimal.ZERO) > 0) {
                    response = naicomPostingService.endorsePolicy(policy);
                } else {
                    response = naicomPostingService.terminateNaicomPolicy(policy);
                }
            }
            case "RN" -> response = policy.getPolicy_unique_id() == null
                    ? naicomPostingService.postPolicyToNaicom(batchNumber, policy)
                    : naicomPostingService.renewNaicomPolicy(policy);
            case "CN" -> response = naicomPostingService.terminateNaicomPolicy(policy);
            case "CO" -> response = naicomPostingService.deleteNaicomPolicy(policy.getPolicy_unique_id(), policy);
            default -> response = naicomPostingService.postPolicyToNaicom(batchNumber, policy);
        }

        if (response.getPolicyUniqueID() != null && !response.getPolicyUniqueID().isEmpty()) {
            try {
                policyTransactionDao.updateNaicomRecordOnPosting(policy, response,
                        Boolean.TRUE.equals(response.getSuccess()) ? "Y" : "N", "NAICOM");
            } catch (Exception e) {
                log.error("Posted successfully but failed to record the outcome for batch {}: {}", policyBatchNumber, e.getMessage(), e);
            }
        }
        return response;
    }

    private String describeFailure(NaicomPolicyResponseDto response) {
        StringBuilder description = new StringBuilder(response.getMessage() != null ? response.getMessage() : "Posting failed.");
        if (response.getErrors() != null && !response.getErrors().isEmpty()) {
            response.getErrors().forEach(error -> description.append(" | ").append(error.getErrorCode()).append(": ").append(error.getErrorText()));
        }
        return description.toString();
    }

    private String safeStagedPayloadForLog(BigDecimal policyBatchNumber) {
        try {
            NaicomPolicyDto policy = naicomPostingService.getStagedPolicy(policyBatchNumber.toBigInteger());
            return policy == null ? null : policy.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private BacklogResult failure(BacklogStage stage, HttpStatus status, String message) {
        return BacklogResult.builder()
                .success(false)
                .status(status)
                .failedStage(stage)
                .message(message)
                .build();
    }
}
