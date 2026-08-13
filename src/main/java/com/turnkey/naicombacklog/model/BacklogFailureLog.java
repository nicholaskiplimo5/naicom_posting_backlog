package com.turnkey.naicombacklog.model;

import com.turnkey.naicombacklog.enums.BacklogStage;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Date;

/**
 * New table this app owns: records every failure encountered while processing a
 * backlog batch, whichever stage (select/stage/post) it happened in, so failed
 * batches can be inspected and retried without digging through log files.
 */
@Entity
@Table(name = "TEX_BACKLOG_FAILURE_LOG")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BacklogFailureLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "backlogFailureLogSeq")
    @SequenceGenerator(name = "backlogFailureLogSeq", sequenceName = "TEX_BACKLOG_FAILURE_LOG_SEQ", allocationSize = 1)
    @Column(name = "BFL_ID")
    private Long id;

    @Column(name = "BFL_POL_BATCH_NO", nullable = false)
    private BigDecimal policyBatchNumber;

    @Column(name = "BFL_TRANS_TYPE", length = 10)
    private String transactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "BFL_STAGE", length = 20, nullable = false)
    private BacklogStage stage;

    @Column(name = "BFL_ERROR_MESSAGE", length = 4000)
    private String errorMessage;

    @Lob
    @Column(name = "BFL_STACK_TRACE")
    private String stackTrace;

    @Lob
    @Column(name = "BFL_PAYLOAD")
    private String payload;

    @Column(name = "BFL_CREATED_DATE")
    private Date createdDate;
}
