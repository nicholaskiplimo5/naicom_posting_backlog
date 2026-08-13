package com.turnkey.naicombacklog.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Date;

/**
 * Maps the existing {@code GIN_POLICY_TRANSACTIONS} staging table used by tps-apis.
 */
@Entity
@Data
@Table(name = "GIN_POLICY_TRANSACTIONS")
public class GinPolicyTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "GTP_CODE")
    private BigDecimal gtpCode;

    @Column(name = "GTP_POL_BATCH_NO", nullable = false)
    private BigDecimal gtpPolBatchNo;

    @Column(name = "GTP_PRP_CODE", nullable = false)
    private BigDecimal gtpPrpCode;

    @Column(name = "GTP_REQUEST_ID", length = 200)
    private String gtpRequestId;

    @Column(name = "GTP_UPDATED_TIME")
    private Timestamp gtpUpdatedTime;

    @Column(name = "GTP_POSTED_TIME")
    private Timestamp gtpPostedTime;

    @Column(name = "GTP_AGN_CODE", nullable = false)
    private BigDecimal gtpAgnCode;

    @Column(name = "GTP_RETRY_COUNTS", nullable = false)
    private Integer gtpRetryCounts;

    @Column(name = "GTP_POSTED_STATUS", length = 20)
    private String gtpPostedStatus;

    @Column(name = "GTP_STICKER_NO", length = 30)
    private String gtpStickerNo;

    @Column(name = "GTP_SERVER_REF_NO", length = 30)
    private String gtpServerRefNo;

    @Column(name = "GTP_TARGET_REGULATOR", length = 50)
    private String gtpTargetRegulator;

    @Column(name = "GTP_POSTING_LEVEL", length = 1)
    private String gtpPostingLevel;

    @Column(name = "GTP_IPU_CODE")
    private BigDecimal gtpIpuCode;

    @Lob
    @Column(name = "GTP_PAYLOAD")
    private String gtpPayload;

    @Column(name = "GTP_MODULE", length = 5)
    private String gtpModule;

    @Column(name = "GTP_TRANS_NO")
    private Long gtpTransNo;

    @Column(name = "GTP_RESPONSE", length = 4000, nullable = false)
    private String gtpResponse;

    @Column(name = "GTP_SERVER_RESPONSE", length = 4000)
    private String gtpServerResponse;

    @Column(name = "GTP_POST_AGAIN", length = 10)
    private String gtpPostAgain;

    @Column(name = "GTP_MANUALLY_POSTED", length = 1)
    private String gtpManuallyPosted;

    @Column(name = "GTP_MANUALLY_POSTING_DATE")
    private Date gtpManuallyPostingDate;
}
