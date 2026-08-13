package com.turnkey.naicombacklog.dao;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * The "select" step of the backlog pipeline: given a batch number and transaction type
 * (POL_POLICY_STATUS), confirms the policy exists with that status in GIN_POLICIES, and
 * reports whether it is still missing from the NAICOM staging table (GIN_POLICY_TRANSACTIONS)
 * so the caller knows whether staging needs to run before posting.
 */
@Repository
@Slf4j
@RequiredArgsConstructor
public class BacklogSelectDao {

    private final JdbcTemplate jdbcTemplate;

    private static final String SELECT_BACKLOG_SQL =
            "SELECT p.POL_BATCH_NO, p.POL_POLICY_STATUS, " +
            "       CASE WHEN EXISTS (" +
            "           SELECT 1 FROM GIN_POLICY_TRANSACTIONS t " +
            "           WHERE t.GTP_POL_BATCH_NO = p.POL_BATCH_NO " +
            "             AND t.GTP_TARGET_REGULATOR = 'NAICOM'" +
            "       ) THEN 'Y' ELSE 'N' END AS ALREADY_STAGED " +
            "  FROM GIN_POLICIES p " +
            " WHERE p.POL_BATCH_NO = ? " +
            "   AND p.POL_POLICY_STATUS = ?";

    public BacklogRecord findBacklogRecord(BigDecimal policyBatchNumber, String transactionType) {
        try (Connection conn = jdbcTemplate.getDataSource().getConnection();
             PreparedStatement pst = conn.prepareStatement(SELECT_BACKLOG_SQL)) {
            pst.setBigDecimal(1, policyBatchNumber);
            pst.setString(2, transactionType);
            try (ResultSet rs = pst.executeQuery()) {
                if (rs.next()) {
                    boolean alreadyStaged = "Y".equalsIgnoreCase(rs.getString("ALREADY_STAGED"));
                    return new BacklogRecord(rs.getBigDecimal("POL_BATCH_NO"), rs.getString("POL_POLICY_STATUS"), alreadyStaged);
                }
                return null;
            }
        } catch (Exception e) {
            log.error("Backlog select failed for batch {} / transaction type {}: {}", policyBatchNumber, transactionType, e.getMessage(), e);
            throw new RuntimeException("Backlog select failed for batch " + policyBatchNumber, e);
        }
    }

    public record BacklogRecord(BigDecimal policyBatchNumber, String policyStatus, boolean alreadyStaged) {
    }
}
