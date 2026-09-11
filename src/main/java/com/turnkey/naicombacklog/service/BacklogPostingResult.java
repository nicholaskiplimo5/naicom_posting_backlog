package com.turnkey.naicombacklog.service;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * One line of a backlog run's response: which transaction it was, whether it posted, and why not
 * if it did not.
 * <p>
 * Deliberately smaller than {@link BacklogResult}, which is what the service passes around
 * internally. That one also carries the whole NAICOM response and HTTP status per policy - more
 * than a caller working the backlog down needs, and a lot to hold onto for a run of a few thousand.
 */
@Data
@Builder
public class BacklogPostingResult {

    private BigDecimal policyBatchNumber;

    private boolean success;

    /** Null when the transaction posted; otherwise the stage it failed at and what went wrong. */
    private String reason;
}
