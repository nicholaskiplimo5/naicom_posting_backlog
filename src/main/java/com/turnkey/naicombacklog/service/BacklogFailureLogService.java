package com.turnkey.naicombacklog.service;

import com.turnkey.naicombacklog.enums.BacklogStage;
import com.turnkey.naicombacklog.model.BacklogFailureLog;
import com.turnkey.naicombacklog.repository.BacklogFailureLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.Date;

/**
 * Writes to the new TEX_BACKLOG_FAILURE_LOG table whenever a backlog batch fails at any
 * stage of the select -&gt; stage -&gt; post pipeline, so failed batches can be inspected and
 * retried without digging through log files.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BacklogFailureLogService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 4000;

    private final BacklogFailureLogRepository repository;

    public void logFailure(BigDecimal policyBatchNumber, String transactionType, BacklogStage stage, Exception exception, String payload) {
        String message = exception.getMessage();
        BacklogFailureLog entry = BacklogFailureLog.builder()
                .policyBatchNumber(policyBatchNumber)
                .transactionType(transactionType)
                .stage(stage)
                .errorMessage(truncate(message != null ? message : exception.toString()))
                .stackTrace(stackTraceOf(exception))
                .payload(payload)
                .createdDate(new Date())
                .build();
        try {
            repository.save(entry);
        } catch (Exception persistFailure) {
            // The failure log must never itself take down the request - fall back to logging.
            log.error("Could not persist backlog failure log for batch {} (stage {}): {}. Original failure: {}",
                    policyBatchNumber, stage, persistFailure.getMessage(), message, exception);
        }
    }

    /**
     * Same as {@link #logFailure(BigDecimal, String, BacklogStage, Exception, String)} but for
     * failures that come back as a normal (non-exceptional) unsuccessful response - e.g. NAICOM
     * field validation errors or a rejected posting - so every failed transaction ends up in
     * TEX_BACKLOG_FAILURE_LOG, not just the ones that threw.
     */
    public void logFailure(BigDecimal policyBatchNumber, String transactionType, BacklogStage stage, String errorMessage, String payload) {
        BacklogFailureLog entry = BacklogFailureLog.builder()
                .policyBatchNumber(policyBatchNumber)
                .transactionType(transactionType)
                .stage(stage)
                .errorMessage(truncate(errorMessage))
                .payload(payload)
                .createdDate(new Date())
                .build();
        try {
            repository.save(entry);
        } catch (Exception persistFailure) {
            log.error("Could not persist backlog failure log for batch {} (stage {}): {}. Original failure: {}",
                    policyBatchNumber, stage, persistFailure.getMessage(), errorMessage);
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_ERROR_MESSAGE_LENGTH ? value.substring(0, MAX_ERROR_MESSAGE_LENGTH) : value;
    }

    private String stackTraceOf(Exception exception) {
        StringWriter sw = new StringWriter();
        exception.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
