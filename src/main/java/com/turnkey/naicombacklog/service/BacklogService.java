package com.turnkey.naicombacklog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import com.turnkey.naicombacklog.dao.PolicyTransactionDao;
import com.turnkey.naicombacklog.dto.naicom.NaicomPolicyDto;
import com.turnkey.naicombacklog.dto.naicom.NaicomPolicyResponseDto;
import com.turnkey.naicombacklog.enums.BacklogStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Orchestrates the backlog pipeline behind {@code POST /postbacklog}: given a policy batch
 * number and transaction type -
 *   1. STAGE   - build and store the NAICOM payload (GTP_PAYLOAD).
 *   2. POST    - dispatch to post/endorse/renew/terminate/delete on NAICOM depending on the
 *                transaction type, exactly mirroring NaicomPolicyPostingController's dispatch,
 *                then record the outcome (and payload) back onto the staging row.
 *
 * A failure at any stage is caught, written to TEX_BACKLOG_FAILURE_LOG with the stage it
 * happened in, and reported back rather than propagating as a raw 500.
 *
 * <h2>Throughput</h2>
 * A backlog run is dominated by the per-policy NAICOM HTTP call, so
 * {@link #runBacklog(String, int, int, int)} posts several policies at once and the caller
 * works through the backlog in bounded chunks (offset/limit) rather than in one giant request.
 * Two things that used to make each policy slower have also been removed:
 *   - the staged payload is no longer read back out of GIN_POLICY_TRANSACTIONS (twice) right
 *     after being written - it is deserialized from the string staging already returned, and
 *   - {@code processBacklog} is no longer {@code @Transactional}. The DAO writes each open
 *     their own connection and commit independently, so the transaction guaranteed nothing;
 *     all it did was pin a pooled connection for the whole NAICOM round trip, which starves
 *     the pool the moment more than one policy is in flight.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BacklogService {

    /**
     * Running tally for one run: numbers each transaction and tracks the ok/fail counts so every
     * per-policy log line can say where it is. Shared across the posting threads - the counters
     * are read and written together, so a synchronized snapshot is used rather than separate
     * atomics, which could report a sequence number and a tally that disagree. The lock is held
     * for a few field increments per policy, against a NAICOM round trip of a few hundred ms.
     */
    private static final class RunProgress {
        private final int total;
        private int done;
        private int ok;
        private int failed;

        RunProgress(int total) {
            this.total = total;
        }

        /** Records one outcome and returns a consistent snapshot of the run so far. */
        synchronized Tick tick(boolean success) {
            done++;
            if (success) {
                ok++;
            } else {
                failed++;
            }
            return new Tick(done, total, ok, failed);
        }

        record Tick(int seq, int total, int ok, int fail) {
            String tally() {
                return "[" + seq + "/" + total + " ok=" + ok + " fail=" + fail + "]";
            }
        }
    }

    /** Set by the container before the datasource and transaction manager are torn down. */
    private volatile boolean shuttingDown;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    private final StagingService stagingService;
    private final NaicomPostingService naicomPostingService;
    private final PolicyTransactionDao policyTransactionDao;
    private final BacklogFailureLogService failureLogService;
    private final ObjectMapper objectMapper;

    /**
     * Selects one chunk of the backlog and posts it.
     *
     * @param offset      how many of the selected batch numbers to skip (for chunked runs).
     * @param limit       how many to process in this run; 0 or less means "all of them".
     * @param concurrency how many policies to post at once; 1 or less runs them one after another.
     */
    public List<BacklogPostingResult> runBacklog(String transactionType, int offset, int limit, int concurrency) {
        long startedAt = System.currentTimeMillis();
        List<BigDecimal> batchNumbers = policyTransactionDao.findPoliciesNotPostedToNAICOM(transactionType, offset, limit);
        if (batchNumbers.isEmpty()) {
            log.info("Backlog run: transactionType={}, offset={} - nothing to process", transactionType, offset);
            return Collections.emptyList();
        }

        log.info("Backlog run starting: transactionType={}, offset={}, count={}, concurrency={}",
                transactionType, offset, batchNumbers.size(), concurrency);

        RunProgress progress = new RunProgress(batchNumbers.size());
        List<BacklogResult> results = concurrency <= 1
                ? processSequentially(batchNumbers, transactionType, progress)
                : processConcurrently(batchNumbers, transactionType, Math.min(concurrency, batchNumbers.size()), progress);

        long succeeded = results.stream().filter(BacklogResult::isSuccess).count();
        long elapsedMs = System.currentTimeMillis() - startedAt;
        log.info("Backlog run finished: transactionType={}, offset={}, count={}, succeeded={}, failed={}, elapsedMs={} ({} ms/policy)",
                transactionType, offset, results.size(), succeeded, results.size() - succeeded,
                elapsedMs, elapsedMs / Math.max(1, results.size()));
        return summarise(batchNumbers, results);
    }

    /**
     * Pairs each batch number with its outcome, dropping the NAICOM response and HTTP status the
     * service used internally.
     * <p>
     * Index-based rather than a map: both {@code processSequentially} and
     * {@code processConcurrently} return results in {@code batchNumbers} order, and a batch number
     * can legitimately appear more than once in a selection.
     */
    private List<BacklogPostingResult> summarise(List<BigDecimal> batchNumbers, List<BacklogResult> results) {
        List<BacklogPostingResult> summary = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            BacklogResult result = results.get(i);
            summary.add(BacklogPostingResult.builder()
                    .policyBatchNumber(batchNumbers.get(i))
                    .success(result.isSuccess())
                    .reason(result.isSuccess() ? null : describeReason(result))
                    .build());
        }
        return summary;
    }

    /** Stage and message together, so one failed line says both where it broke and why. */
    private String describeReason(BacklogResult result) {
        String message = result.getMessage() != null ? result.getMessage() : "no reason reported";
        return result.getFailedStage() != null ? result.getFailedStage() + ": " + message : message;
    }

    public BacklogResult processBacklog(BigDecimal policyBatchNumber, String transactionType) {
        return processBacklog(policyBatchNumber, transactionType, new RunProgress(1));
    }

    private BacklogResult processBacklog(BigDecimal policyBatchNumber, String transactionType, RunProgress progress) {
        long startedAt = System.currentTimeMillis();
        String stagedPayload;
        NaicomPolicyDto policy;
        try {
            stagedPayload = stagingService.stageForNaicom(policyBatchNumber);
            if (stagedPayload == null) {
                throw new IllegalStateException("Staging produced no payload for batch " + policyBatchNumber);
            }
            // Deserialize the payload staging just handed back, instead of re-querying
            // GIN_POLICY_TRANSACTIONS for the row we wrote a moment ago. The old flow read it
            // back twice - once via getStagedPolicy to decide the dispatch, then again inside
            // postPolicyToNaicom - and parsed the same JSON both times.
            policy = objectMapper.readValue(stagedPayload, NaicomPolicyDto.class);
            policy.setPolicy_batch_no(policyBatchNumber.toBigInteger());
        } catch (Exception e) {
            failureLogService.logFailure(policyBatchNumber, transactionType, BacklogStage.STAGE, e, null);
            RunProgress.Tick tick = progress.tick(false);
            log.warn("{}. FAILURE for Transaction with batch={}, type={}, stage=STAGE, elapsedMs={}, reason={} {}",
                    tick.seq(), policyBatchNumber, transactionType,
                    System.currentTimeMillis() - startedAt, e.getMessage(), tick.tally());
            return failure(BacklogStage.STAGE, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Staging failed for batch " + policyBatchNumber + ": " + e.getMessage());
        }

        String action = actionFor(transactionType, policy);
        try {
            NaicomPolicyResponseDto response = post(policyBatchNumber, policy, action);
            boolean success = Boolean.TRUE.equals(response.getSuccess());
            long elapsedMs = System.currentTimeMillis() - startedAt;

            if (success) {
                RunProgress.Tick tick = progress.tick(true);
                log.info("{}. SUCCESS for Transaction with batch={}, type={}, action={}, status={}, uniqueId={}, elapsedMs={} {}",
                        tick.seq(), policyBatchNumber, transactionType, action,
                        response.getStatus(), response.getPolicyUniqueID(), elapsedMs, tick.tally());
            } else {
                String reason = describeFailure(response);
                failureLogService.logFailure(policyBatchNumber, transactionType, BacklogStage.POST,
                        reason, stagedPayload);
                RunProgress.Tick tick = progress.tick(false);
                log.warn("{}. FAILURE for Transaction with batch={}, type={}, action={}, stage=POST, status={}, elapsedMs={}, reason={} {}",
                        tick.seq(), policyBatchNumber, transactionType, action,
                        response.getStatus(), elapsedMs, reason, tick.tally());
            }

            return BacklogResult.builder()
                    .success(success)
                    .status(response.getStatus() != null ? response.getStatus() : HttpStatus.OK)
                    .failedStage(success ? null : BacklogStage.POST)
                    .message(response.getMessage())
                    .postingResponse(response)
                    .build();
        } catch (Exception e) {
            failureLogService.logFailure(policyBatchNumber, transactionType, BacklogStage.POST, e, stagedPayload);
            RunProgress.Tick tick = progress.tick(false);
            log.error("{}. ERROR for Transaction with batch={}, type={}, action={}, stage=POST, elapsedMs={}, reason={} {}",
                    tick.seq(), policyBatchNumber, transactionType, action,
                    System.currentTimeMillis() - startedAt, e.getMessage(), tick.tally(), e);
            return failure(BacklogStage.POST, HttpStatus.INTERNAL_SERVER_ERROR,
                    "Posting failed for batch " + policyBatchNumber + ": " + e.getMessage());
        }
    }

    /**
     * Which NAICOM operation the dispatch rules below will pick. Resolved separately so the
     * per-policy log line can name it - "did this batch post, endorse or terminate?" is the
     * first question anyone asks of a backlog run.
     */
    private String actionFor(String transactionType, NaicomPolicyDto policy) {
        return switch (transactionType == null ? "" : transactionType) {
            case "NB", "SP", "DC" -> "POST";
            case "EN", "EX" -> {
                if (policy.getPolicy_unique_id() == null) {
                    yield "POST";
                }
                yield policy.getTotal_premium() != null && policy.getTotal_premium().compareTo(BigDecimal.ZERO) > 0
                        ? "ENDORSE" : "TERMINATE";
            }
            case "RN" -> policy.getPolicy_unique_id() == null ? "POST" : "RENEW";
            case "CN" -> "TERMINATE";
            case "CO" -> "DELETE";
            default -> "POST";
        };
    }

    /**
     * Same dispatch rules as {@code NaicomPolicyPostingController}: NB/SP/DC always post; EN/EX
     * post (new), endorse (premium increase) or terminate (premium decrease) depending on whether
     * a policy_unique_id already exists; RN posts (new) or renews; CN terminates; CO deletes.
     */
    private NaicomPolicyResponseDto post(BigDecimal policyBatchNumber, NaicomPolicyDto policy, String action) {
        BigInteger batchNumber = policyBatchNumber.toBigInteger();

        // Dispatch on the action resolved by actionFor, not on transactionType again, so the
        // action named in the log line is always the one that actually ran.
        NaicomPolicyResponseDto response = switch (action) {
            case "ENDORSE" -> naicomPostingService.endorsePolicy(policy);
            case "RENEW" -> naicomPostingService.renewNaicomPolicy(policy);
            case "TERMINATE" -> naicomPostingService.terminateNaicomPolicy(policy);
            case "DELETE" -> naicomPostingService.deleteNaicomPolicy(policy.getPolicy_unique_id(), policy);
            default -> naicomPostingService.postSingleTransaction(policy);
        };

        if (response.getPolicyUniqueID() != null && !response.getPolicyUniqueID().isEmpty()) {
            try {
                policyTransactionDao.updateNaicomRecordOnPosting(policy, response,
                        Boolean.TRUE.equals(response.getSuccess()) ? "Y" : "N", "NAICOM");
            } catch (Exception e) {
                log.error("Posted successfully but failed to record the outcome for batch {}: {}", batchNumber, e.getMessage(), e);
            }
        }
        return response;
    }

    private List<BacklogResult> processSequentially(List<BigDecimal> batchNumbers, String transactionType,
                                                    RunProgress progress) {
        List<BacklogResult> results = new ArrayList<>(batchNumbers.size());
        for (BigDecimal policyBatchNumber : batchNumbers) {
            results.add(processBacklog(policyBatchNumber, transactionType, progress));
        }
        return results;
    }

    private List<BacklogResult> processConcurrently(List<BigDecimal> batchNumbers, String transactionType,
                                                    int threads, RunProgress progress) {
        int total = batchNumbers.size();

        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("backlog-poster-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Callable<BacklogResult>> tasks = new ArrayList<>(total);
            for (BigDecimal policyBatchNumber : batchNumbers) {
                // No separate progress log here - every per-policy line already carries the
                // running [n/total ok= fail=] counter.
                tasks.add(() -> processBacklog(policyBatchNumber, transactionType, progress));
            }

            // invokeAll hands back futures in submission order, so results line up with batchNumbers.
            List<Future<BacklogResult>> futures = pool.invokeAll(tasks);
            List<BacklogResult> results = new ArrayList<>(total);
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (Exception e) {
                    BigDecimal policyBatchNumber = batchNumbers.get(i);
                    log.error("Backlog task failed for batch {}: {}", policyBatchNumber, e.getMessage(), e);
                    results.add(failure(BacklogStage.POST, HttpStatus.INTERNAL_SERVER_ERROR,
                            "Posting failed for batch " + policyBatchNumber + ": " + e.getMessage()));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while posting the backlog", e);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * The human-readable reason a policy failed. When NAICOM (or validation) returned specific
     * errors those are the whole answer - the accompanying message is a generic
     * "Failed to post the policy.", which only repeats what FAILURE already says.
     */
    private String describeFailure(NaicomPolicyResponseDto response) {
        if (response.getErrors() != null && !response.getErrors().isEmpty()) {
            return response.getErrors().stream()
                    .map(error -> error.getErrorCode() + ": " + error.getErrorText())
                    .collect(Collectors.joining("; "));
        }
        return response.getMessage() != null ? response.getMessage() : "Posting failed.";
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
