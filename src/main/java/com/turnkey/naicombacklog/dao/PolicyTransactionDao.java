package com.turnkey.naicombacklog.dao;

import com.turnkey.naicombacklog.dto.naicom.NaicomPolicyDto;
import com.turnkey.naicombacklog.dto.naicom.NaicomPolicyResponseDto;
import com.turnkey.naicombacklog.dto.regulatorPayload.Coinsurance;
import com.turnkey.naicombacklog.dto.regulatorPayload.PolicyCoinsuranceDto;
import com.turnkey.naicombacklog.dto.regulatorPayload.PolicyTransactionDto;
import com.turnkey.naicombacklog.dto.regulatorPayload.StagingRequirement;
import com.turnkey.naicombacklog.model.GinPolicyTransactionEntity;
import com.turnkey.naicombacklog.repository.GinPolicyTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Raw JDBC access to the existing GIN_* Oracle schema, ported from tps-apis'
 * {@code PolicyTransactionDao}. Uses constructor-injected {@link JdbcTemplate} and
 * try-with-resources instead of the original's static service-locator + manual
 * close() calls, and {@code executeUpdate} instead of {@code executeQuery} for the
 * staging INSERT/UPDATE (the original used executeQuery for both, which happened to
 * "work" against Oracle but is non-idiomatic).
 */
@Repository
@Slf4j
@RequiredArgsConstructor
public class PolicyTransactionDao {

    /**
     * Rows the Oracle driver pulls per network round trip when reading the backlog cursor
     * (the driver default is 10).
     * <p>
     * Measured against TQPDB on a 180,952-row NB backlog this is worth about 7% - 207s at the
     * default versus 193s here - and no more, because the cost is not the round trips: a REF
     * CURSOR runs its query lazily as rows are pulled, so nearly all of that time is
     * {@code get_backlog_policies_prc} executing, not the network. What actually makes the
     * select cheap is asking for a chunk: see {@link #findPoliciesNotPostedToNAICOM(String, int, int)}.
     */
    private static final int BACKLOG_FETCH_SIZE = 5000;

    /** Avoids ~15 array copies while a large backlog list grows from ArrayList's default of 10. */
    private static final int INITIAL_BACKLOG_CAPACITY = 10_000;

    /** Rows per round trip when reading one policy's risks - enough for a fleet in a single trip. */
    private static final int RISK_FETCH_SIZE = 200;

    private final JdbcTemplate jdbcTemplate;
    private final GinPolicyTransactionRepository policyTransactionRepository;
    private final ObjectMapper objectMapper;

    /**
     * Fetches the full policy row (all product-line fields) for the given batch via the
     * existing Oracle stored procedure. Same procedure used by the original staging flow.
     */
    public List<PolicyTransactionDto> getPolicyDetails(BigDecimal policyBatchNo) {
        List<PolicyTransactionDto> transactions = new ArrayList<>();
        if (policyBatchNo == null) {
            return transactions;
        }
        String query = "{ call gin_interfaces_cursor.get_policy_transaction_prc(?,?) }";
        try (Connection conn = jdbcTemplate.getDataSource().getConnection();
             CallableStatement cst = conn.prepareCall(query, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            cst.setFetchSize(RISK_FETCH_SIZE);
            cst.setBigDecimal(1, policyBatchNo);
            cst.registerOutParameter(2, Types.REF_CURSOR);
            cst.executeQuery();
            try (ResultSet rs = (ResultSet) cst.getObject(2)) {
                if (rs == null) {
                    log.info("get_policy_transaction_prc returned no cursor for batch {}", policyBatchNo);
                    return transactions;
                }
                rs.setFetchSize(RISK_FETCH_SIZE);
                while (rs.next()) {
                    transactions.add(mapRow(rs));
                }
            }
        } catch (Exception e) {
            // An empty list means "this batch has no risks" and callers treat it as a data
            // problem for that one policy. A failed query is not that, so it must not return
            // empty and be mistaken for it - staging would report "no policy details found"
            // for what is really a dead connection.
            throw new IllegalStateException("Failed to fetch policy details for batch "
                    + policyBatchNo + ": " + e.getMessage(), e);
        }
        return transactions;
    }

    private PolicyTransactionDto mapRow(ResultSet rs) throws java.sql.SQLException {
        PolicyTransactionDto t = new PolicyTransactionDto();
        t.setADDITIONAL_PERIL_CHARGE(rs.getBigDecimal("ADDITIONAL_PERIL_CHARGE"));
        t.setADJUSTED_PREMIUM(rs.getBigDecimal("ADJUSTED_PREMIUM"));
        t.setAGENT_BRANCH_CODE(rs.getString("AGENT_BRANCH_CODE"));
        t.setAGENT_CODE(rs.getString("AGENT_CODE"));
        t.setAGENT_EMAIL(rs.getString("AGENT_EMAIL"));
        t.setAGENT_FIRST_NAME(rs.getString("AGENT_FIRST_NAME"));
        t.setAGENT_LAST_NAME(rs.getString("AGENT_LAST_NAME"));
        t.setAGENT_OTHER_NAMES(rs.getString("AGENT_OTHER_NAMES"));
        t.setAGENT_PHONE(rs.getString("AGENT_PHONE"));
        t.setAGENT_REFERENCE_NO(rs.getLong("AGENT_REFERENCE_NO"));
        t.setBASIC_PREMIUM(rs.getBigDecimal("BASIC_PREMIUM"));
        t.setBODY_TYPE_CODE(rs.getString("BODY_TYPE_CODE"));
        t.setBROWN_CARD_FEE(rs.getBigDecimal("BROWN_CARD_FEE"));
        t.setCHASIS_NUMBER(rs.getString("CHASIS_NUMBER"));
        t.setCLIENT_BRANCH_CODE(rs.getString("CLIENT_BRANCH_CODE"));
        t.setCLIENT_EMAIL(rs.getString("CLIENT_EMAIL"));
        t.setCLIENT_ID_CARD_TYPE_CODE(rs.getString("CLIENT_ID_CARD_TYPE_CODE"));
        t.setCLIENT_ID_NUMBER(rs.getString("CLIENT_ID_NUMBER"));
        t.setCLIENT_LAST_NAME(rs.getString("CLIENT_LAST_NAME"));
        t.setCLIENT_NATIONAL_ID(rs.getString("CLIENT_NATIONAL_ID"));
        t.setCLIENT_NUMBER(rs.getString("CLIENT_NUMBER"));
        t.setCLIENT_OCCUPATION(rs.getString("CLIENT_OCCUPATION"));
        t.setCLIENT_OTHER_NAMES(rs.getString("CLIENT_OTHER_NAMES"));
        t.setCLIENT_PHONE(rs.getString("CLIENT_PHONE"));
        t.setCLIENT_POSTAL_ADDRESS(rs.getString("CLIENT_POSTAL_ADDRESS"));
        t.setCLIENT_REFERENCE_NO(rs.getString("CLIENT_REFERENCE_NO"));
        t.setCLIENT_RESIDENTIAL_ADDRESS(rs.getString("CLIENT_RESIDENTIAL_ADDRESS"));
        t.setCLIENT_TIN(rs.getString("CLIENT_TIN"));
        t.setCOMPUTATION_TYPE_CODE(rs.getString("COMPUTATION_TYPE_CODE"));
        t.setCO_INSURE_AMOUNT(rs.getBigDecimal("CO_INSURE_AMOUNT"));
        t.setCO_INSURE_CODE(rs.getString("CO_INSURE_CODE"));
        t.setCO_INSURE_RATE(rs.getBigDecimal("CO_INSURE_RATE"));
        t.setCUBIC_CAPACITY(rs.getString("CUBIC_CAPACITY"));
        t.setCUSTOMER_TYPE_CODE(rs.getString("CUSTOMER_TYPE_CODE"));
        t.setDIGITAL_ADDRESS(rs.getString("DIGITAL_ADDRESS"));
        t.setECOWAS_PERIL_CHARGE(rs.getBigDecimal("ECOWAS_PERIL_CHARGE"));
        t.setEXCESS_AMOUNT(rs.getBigDecimal("EXCESS_AMOUNT"));
        t.setEXCESS_CHARGE(rs.getBigDecimal("EXCESS_CHARGE"));
        t.setEXCESS_RATE(rs.getBigDecimal("EXCESS_RATE"));
        t.setEXCESS_TYPE_CODE(rs.getString("EXCESS_TYPE_CODE"));
        t.setEXTRA_SEATS_CHARGE(rs.getBigDecimal("EXTRA_SEATS_CHARGE"));
        t.setEXTRA_TPPD_CHARGE(rs.getBigDecimal("EXTRA_TPPD_CHARGE"));
        t.setFIRST_NAME(rs.getString("FIRST_NAME"));
        t.setFLEET(rs.getString("FLEET"));
        t.setINTERMEDIARY_TYPE_CODE(rs.getString("INTERMEDIARY_TYPE_CODE"));
        t.setAGENT_LICENSE_NUMBER(rs.getString("AGENT_LICENSE_NUMBER"));
        t.setLEGACY_POLICY_NUMBER(rs.getString("LEGACY_POLICY_NUMBER"));
        t.setLEVIES(rs.getBigDecimal("LEVIES"));
        t.setLOADINGS(new ArrayList<>());
        t.setMILEAGE(rs.getBigDecimal("MILEAGE"));
        t.setPERILS(rs.getBigDecimal("PERILS"));
        t.setPERSONAL_ACCIDENT_CHARGE(rs.getBigDecimal("PERSONAL_ACCIDENT_CHARGE"));
        t.setPOLICY_BRANCH_CODE(rs.getString("POLICY_BRANCH_CODE"));
        t.setPOLICY_COVER_TYPE_CODE(rs.getString("POLICY_COVER_TYPE_CODE"));
        t.setPOLICY_CURRENCY_CODE(rs.getString("POLICY_CURRENCY_CODE"));
        t.setPOLICY_DAYS(rs.getLong("POLICY_DAYS"));
        t.setPOLICY_EXCHANGE_RATE(rs.getBigDecimal("POLICY_EXCHANGE_RATE"));
        t.setPOLICY_EXPIRY_DATE(rs.getString("POLICY_EXPIRY_DATE"));
        t.setPOLICY_INCEPTION_DATE(rs.getString("POLICY_INCEPTION_DATE"));
        t.setPOLICY_NUMBER(rs.getString("POLICY_NUMBER"));
        t.setPOLICY_REMARKS(rs.getString("POLICY_REMARKS"));
        t.setPOLICY_SCHEDULE_CODE(rs.getString("POLICY_SCHEDULE_CODE"));
        t.setPOL_BATCH_NO(rs.getBigDecimal("POL_BATCH_NO"));
        t.setREGISTRATION_NUMBER(rs.getString("REGISTRATION_NUMBER"));
        t.setSEATS(rs.getLong("SEATS"));
        t.setSERVER_REFERENCE(rs.getString("SERVER_REFERENCE"));
        t.setSTICKER_FEE(rs.getBigDecimal("STICKER_FEE"));
        t.setSTICKER_NUMBER(rs.getString("STICKER_NUMBER"));
        t.setSUB_TOTAL_PREMIUM(rs.getBigDecimal("SUB_TOTAL_PREMIUM"));
        t.setSUM_INSURED(rs.getBigDecimal("SUM_INSURED"));
        t.setSUM_INSURED_RATE(rs.getBigDecimal("SUM_INSURED_RATE"));
        t.setTIEET(rs.getString("TIEET"));
        t.setTOTAL_DISCOUNTS(rs.getBigDecimal("TOTAL_DISCOUNTS"));
        t.setTOTAL_LOADINGS(rs.getBigDecimal("TOTAL_LOADINGS"));
        t.setTOTAL_PREMIUM(rs.getBigDecimal("TOTAL_PREMIUM"));
        t.setTPPD_LIMIT(rs.getBigDecimal("TPPD_LIMIT"));
        t.setTPPD_RATE(rs.getBigDecimal("TPPD_RATE"));
        t.setVEHICLE_MAKE(rs.getString("VEHICLE_MAKE"));
        t.setVEHICLE_MODEL(rs.getString("VEHICLE_MODEL"));
        t.setVEHICLE_REGISTRATION(rs.getString("VEHICLE_REGISTRATION"));
        t.setYEAR_OF_MANUFACTURE(
                (rs.getString("YEAR_OF_MANUFACTURE") == null || rs.getString("YEAR_OF_MANUFACTURE").equals("0"))
                        ? "2000"
                        : rs.getString("YEAR_OF_MANUFACTURE"));
        t.setCLIENT_DATE_OF_BIRTH(rs.getString("CLIENT_DATE_OF_BIRTH"));
        t.setCOLOR(rs.getString("COLOR"));
        t.setCLIENT_TYPE(rs.getString("CLIENT_TYPE"));
        t.setNCD_CONFIRMATION_NUMBER(rs.getString("NCD_CONFIRMATION_NUMBER"));
        t.setNCD_CONFIRMATION_ID(rs.getString("NCD_CONFIRMATION_ID"));
        t.setDEBIT_NOTE_NUMBER(rs.getString("POL_DRCR_NO"));
        t.setENGINE_NUMBER(rs.getString("MPS_ENGINE_NO"));
        t.setCURRENCY_CODE(rs.getString("POL_CUR_SYMBOL"));
        t.setTERRITORY_ZONE(rs.getString("IPU_TERR_DESC"));
        t.setCLIENT_NAME(rs.getString("CLIENT_NAME"));
        t.setCERTIFICTAE_NUMBER(rs.getString("POLC_CER_CERT_NO"));
        t.setCERTIFICATE_FEE(BigDecimal.ZERO);
        t.setPRO_MARINE(rs.getString("PRO_MARINE"));
        t.setGTP_RETRY_COUNTS(rs.getInt("GTP_RETRY_COUNTS"));
        t.setGTP_TARGET_REGULATOR(rs.getString("GTP_TARGET_REGULATOR"));
        t.setBANK_NAME(rs.getString("BANK_NAME"));
        t.setBASIC_RATE(rs.getBigDecimal("BASIC_RATE"));
        t.setWAR_STRIKE_RATE(rs.getBigDecimal("WAR_STRIKE_RATE"));
        t.setCARGO_DESC(rs.getString("CARGO_DESC"));
        t.setPOL_COINSURANCE(rs.getString("POL_COINSURANCE"));
        t.setCARGO_CONDITION(rs.getString("CARGO_CONDITION"));
        t.setCARGO_NATURE(rs.getString("CARGO_NATURE"));
        t.setCARGO_PACKAGING(rs.getString("CARGO_PACKAGING"));
        String policyType = rs.getString("POLICY_TYPE");
        t.setPOLICY_TYPE(policyType != null && policyType.contains("OPEN") ? "OPEN" : "SINGLE");
        t.setPROFORMA_INVOICE(rs.getString("PROFORMA_INVOICE"));
        t.setSAILING_FROM(rs.getString("SAILING_FROM"));
        t.setSAILING_TO(rs.getString("SAILING_TO"));
        t.setTOTAL_RATE(rs.getBigDecimal("TOTAL_RATE"));
        t.setVESSEL_NAME(rs.getString("VESSEL_NAME"));
        t.setPOL_COMM_ENDOS_DIFF_AMT(rs.getBigDecimal("POL_COMM_ENDOS_DIFF_AMT"));
        t.setPOL_POLICY_COVER_FROM(rs.getString("POL_POLICY_COVER_FROM"));
        t.setPOL_POLICY_COVER_TO(rs.getString("POL_POLICY_COVER_TO"));
        t.setSTS_NAME(rs.getString("STS_NAME"));
        t.setPRG_DESCN(rs.getString("PRG_DESCN"));
        t.setBON_BOND_AMOUNT(rs.getBigDecimal("BON_BOND_AMOUNT"));
        t.setBON_CONTRACT_DETAILS(rs.getString("BON_CONTRACT_DETAILS"));
        t.setCOVT_DESC(rs.getString("COVT_DESC"));
        t.setBUILD_ADDRESS_LINE(rs.getString("BUILD_ADDRESS_LINE"));
        t.setBUILD_CITY(rs.getString("BUILD_CITY"));
        t.setBUILD_DOOR_NO(rs.getString("BUILD_DOOR_NO"));
        t.setBUILD_NAME(rs.getString("BUILD_NAME"));
        t.setBUILD_POST_CODE(rs.getString("BUILD_POST_CODE"));
        t.setBUILD_STATE(rs.getString("BUILD_STATE"));
        t.setHOUSE_BUILD_TYPE(rs.getString("HOUSE_BUILD_TYPE"));
        t.setPROP_CONSTRUCT_VALUE(rs.getBigDecimal("PROP_CONSTRUCT_VALUE"));
        t.setPROP_CONTENT_VALUE(rs.getBigDecimal("PROP_CONTENT_VALUE"));
        t.setVESSEL_BUILD_YEAR(rs.getBigDecimal("VESSEL_BUILD_YEAR"));
        t.setVESSEL_CARRIAGE_CAPACITY(rs.getString("VESSEL_CARRIAGE_CAPACITY"));
        t.setVESSEL_MODEL(rs.getString("VESSEL_MODEL"));
        t.setVESSEL_PURCHASE_VALUE(rs.getBigDecimal("VESSEL_PURCHASE_VALUE"));
        t.setVESSEL_PURCHASE_YEAR(rs.getBigDecimal("VESSEL_PURCHASE_YEAR"));
        t.setVESSEL_TYPE(rs.getString("VESSEL_TYPE"));
        t.setCLNT_GENDER(rs.getString("CLNT_GENDER"));
        t.setCLNT_TITLE(rs.getString("CLNT_TITLE"));
        t.setPRO_REGULATOR_SCH_CODE(rs.getBigDecimal("PRO_REGULATOR_SCH_CODE"));
        t.setPLANT_TYPE(rs.getString("PLANT_TYPE"));
        t.setPROJECT_DETAIL(rs.getString("PROJECT_DETAIL"));
        t.setPROJECT_NAME(rs.getString("PROJECT_NAME"));
        t.setCONTRACT_DESCRIPTION(rs.getString("CONTRACT_DESCRIPTION"));
        t.setCONTRACT_LOCATION(rs.getString("CONTRACT_LOCATION"));
        t.setCONTRACTOR_NAME(rs.getString("CONTRACTOR_NAME"));
        t.setCONTRACTOR_ADDRESS_LINE(rs.getString("CONTRACTOR_ADDRESS_LINE"));
        t.setCONTRACTOR_CITY(rs.getString("CONTRACTOR_CITY"));
        t.setCONTRACTOR_STATE(rs.getString("CONTRACTOR_STATE"));
        t.setCONTRACTOR_SITE(rs.getString("CONTRACTOR_SITE"));
        t.setPLANT_EQUIPMENT_COVERED(rs.getString("PLANT_EQUIPMENT_COVERED"));
        t.setMACHINERY_CONDITIONS(rs.getString("MACHINERY_CONDITIONS"));
        t.setMACHINERY_DESCRIPTION(rs.getString("MACHINERY_DESCRIPTION"));
        t.setMACHINERY_MODEL(rs.getString("MACHINERY_MODEL"));
        t.setMACHINERY_YOM(rs.getString("MACHINERY_YOM"));
        t.setMACHINERY_SERIAL(rs.getString("MACHINERY_SERIAL"));
        t.setMACHINERY_LEASED(rs.getString("MACHINERY_LEASED"));
        t.setESTIMATED_MAXIMUM_LOSS(rs.getBigDecimal("ESTIMATED_MAXIMUM_LOSS"));
        t.setDEDUCTIBLE(rs.getBigDecimal("DEDUCTIBLE"));
        t.setIPU_CODE(rs.getBigDecimal("IPU_CODE"));
        t.setRECORDER_TYPE(rs.getString("RECORDER_TYPE"));
        t.setRECORDER_ID(rs.getBigDecimal("RECORDER_ID"));
        t.setVEHICLE_BODY_TYPE(rs.getString("BODY_TYPE"));
        t.setTransType(rs.getString("POL_POLICY_STATUS"));
        t.setPOL_CHECK_DATE(rs.getDate("POL_CHECK_DATE"));
        t.setIPU_VALUE(rs.getBigDecimal("IPU_VALUE"));
        t.setPOL_TOT_ENDOS_DIFF_AMT(rs.getBigDecimal("POL_TOT_ENDOS_DIFF_AMT"));
        t.setPOL_TOTAL_SUM_INSURED(rs.getBigDecimal("POL_TOTAL_SUM_INSURED"));
        return t;
    }

    /**
     * Agent/product code plus the next GTP_CODE, needed only when a batch is being staged for the
     * first time. The sequence is drawn in the same statement as the GIN_POLICIES lookup (one
     * round trip instead of two), and no sequence value is burned when the policy row is missing.
     */
    private StagingRequirement getAdditionalInfo(BigDecimal policyBatchNo) {
        String query = "SELECT POL_AGNT_AGENT_CODE, POL_PRP_CODE, GIN_GTP_CODE_SEQ.NEXTVAL " +
                "FROM GIN_POLICIES WHERE POL_BATCH_NO = ?";
        try (Connection conn = jdbcTemplate.getDataSource().getConnection();
             PreparedStatement pst = conn.prepareStatement(query)) {
            pst.setBigDecimal(1, policyBatchNo);
            try (ResultSet rs = pst.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                StagingRequirement requirement = new StagingRequirement();
                requirement.setAgnCode(rs.getBigDecimal(1));
                requirement.setPrpCode(rs.getBigDecimal(2));
                requirement.setCode(rs.getBigDecimal(3));
                return requirement;
            }
        } catch (Exception e) {
            // null means "no GIN_POLICIES row for this batch", which saveDetails reports as
            // "cannot stage". A failed query is a different thing and must say so - swallowing
            // it here is what made a dropped connection surface as the misleading
            // "Could not resolve agent/prp code for batch ... - cannot stage".
            throw new IllegalStateException("Failed to fetch staging requirement (agent/prp code, "
                    + "next GTP_CODE) for batch " + policyBatchNo + ": " + e.getMessage(), e);
        }
    }

    /**
     * Removes the batch's staged row so staging can put a fresh one back.
     * <p>
     * Ported from tps-apis' {@code deletePolicyFromGinPoliciesTransaction}, which backs the
     * {@code /restage} endpoint - with one deliberate narrowing. That method deletes every row for
     * the batch whatever its regulator, which also takes out the batch's NIID staging. Fine for a
     * one-policy operator call; not fine here, where a backlog run would clear the NIID row of every
     * policy it walks past. So the delete is scoped by regulator, the same way
     * {@link #saveDetails} scopes its update.
     *
     * @return rows removed. Zero is normal, not an error - it just means this batch had never
     *         been staged, which is the common case for a backlog policy.
     */
    public int deleteStagedPolicy(BigDecimal policyBatchNo, String regulator) {
        String delete = "DELETE FROM GIN_POLICY_TRANSACTIONS WHERE GTP_POL_BATCH_NO = ? AND GTP_TARGET_REGULATOR = ?";
        try {
            return jdbcTemplate.update(delete, policyBatchNo, regulator);
        } catch (Exception e) {
            // Must not be swallowed. The caller follows this with an unconditional INSERT, so a
            // delete that silently failed would leave the batch with two staged rows and let
            // posting pick either one.
            throw new IllegalStateException("Failed to delete staged " + regulator + " row for batch "
                    + policyBatchNo + ": " + e.getMessage(), e);
        }
    }

    /**
     * Inserts (or updates, if already staged) the regulator payload for the given batch
     * into GIN_POLICY_TRANSACTIONS.
     */
    public boolean saveDetails(BigDecimal policyBatchNo, String payload, String postingLevel, String regulator, BigDecimal ipuCode) {
        return saveDetails(policyBatchNo, payload, postingLevel, regulator, ipuCode, false);
    }

    /**
     * @param replaceExisting true when the caller has already cleared the batch's staged row with
     *                        {@link #deleteStagedPolicy}. The UPDATE probe below cannot match in
     *                        that case, so it is skipped rather than spent on every policy.
     */
    public boolean saveDetails(BigDecimal policyBatchNo, String payload, String postingLevel, String regulator, BigDecimal ipuCode,
                               boolean replaceExisting) {
        String insert = "INSERT INTO GIN_POLICY_TRANSACTIONS (GTP_CODE, GTP_POL_BATCH_NO, GTP_PRP_CODE, GTP_AGN_CODE, " +
                "GTP_RESPONSE, GTP_RETRY_COUNTS, GTP_POSTED_STATUS, GTP_TARGET_REGULATOR, GTP_PAYLOAD, " +
                "GTP_POSTING_LEVEL, GTP_IPU_CODE, GTP_MODULE, GTP_REQUEST_ID, GTP_TRANS_NO) " +
                "VALUES (?, ?, ?, ?, 'Pending posting.', 0, 'NOT_POSTED', ?, ?, ?, ?, ?, ?, ?)";
        String update = "UPDATE GIN_POLICY_TRANSACTIONS SET GTP_PAYLOAD = ? WHERE GTP_POL_BATCH_NO = ? AND GTP_TARGET_REGULATOR = ?";

        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            if ("R".equalsIgnoreCase(postingLevel)) {
                // Risk-level staging is keyed by IPU code as well, which the UPDATE below does not
                // filter on, so that path keeps the explicit existence check.
                List<GinPolicyTransactionEntity> existing = policyTransactionRepository
                        .findByGtpPolBatchNoAndGtpTargetRegulatorAndGtpIpuCode(policyBatchNo, regulator, ipuCode);
                if (!existing.isEmpty()) {
                    try (PreparedStatement pst = conn.prepareStatement(update)) {
                        pst.setString(1, payload);
                        pst.setBigDecimal(2, policyBatchNo);
                        pst.setString(3, regulator);
                        pst.executeUpdate();
                    }
                    return true;
                }
            } else if (!replaceExisting) {
                // Try the UPDATE first and let its row count answer "is this batch already staged?".
                // The old flow answered that with a separate JPA select, and unconditionally spent two
                // more round trips resolving the agent/prp code and next sequence value even on the
                // update path, where neither is used.
                try (PreparedStatement pst = conn.prepareStatement(update)) {
                    pst.setString(1, payload);
                    pst.setBigDecimal(2, policyBatchNo);
                    pst.setString(3, regulator);
                    if (pst.executeUpdate() > 0) {
                        return true;
                    }
                }
            }

            StagingRequirement requirement = getAdditionalInfo(policyBatchNo);
            if (requirement == null) {
                log.error("Could not resolve agent/prp code for batch {} - cannot stage", policyBatchNo);
                return false;
            }
            try (PreparedStatement pst = conn.prepareStatement(insert)) {
                pst.setBigDecimal(1, requirement.getCode());
                pst.setBigDecimal(2, policyBatchNo);
                pst.setBigDecimal(3, requirement.getPrpCode());
                pst.setBigDecimal(4, requirement.getAgnCode());
                pst.setString(5, regulator);
                pst.setString(6, payload);
                pst.setString(7, postingLevel);
                pst.setBigDecimal(8, ipuCode);
                pst.setString(9, "U");
                pst.setString(10, null);
                pst.setNull(11, Types.BIGINT);
                pst.executeUpdate();
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to stage payload for batch {}: {}", policyBatchNo, e.getMessage(), e);
            return false;
        }
    }

    public String getPrevPolicyUniqueID(BigDecimal policyBatchNo) {
        String query = "begin ? := get_policy_unique_id(?); end;";
        try (Connection conn = jdbcTemplate.getDataSource().getConnection();
             CallableStatement cst = conn.prepareCall(query)) {
            cst.registerOutParameter(1, Types.VARCHAR);
            cst.setBigDecimal(2, policyBatchNo);
            cst.execute();
            return cst.getString(1);
        } catch (Exception e) {
            // Payload-shaping read: returning null on a failed query would stage the policy as
            // though it had no previous policy id, changing how it is dispatched to NAICOM.
            throw new IllegalStateException("Failed to fetch previous policy unique id for batch "
                    + policyBatchNo + ": " + e.getMessage(), e);
        }
    }

    public List<PolicyCoinsuranceDto> getPolicyCoinsuranceDetails(BigDecimal policyBatchNo) {
        List<PolicyCoinsuranceDto> result = new ArrayList<>();
        String query = "SELECT POL_COINSURE_LEADER, POL_COINSURANCE_SHARE FROM GIN_POLICIES WHERE POL_BATCH_NO = ?";
        try (Connection conn = jdbcTemplate.getDataSource().getConnection();
             PreparedStatement pst = conn.prepareStatement(query)) {
            pst.setBigDecimal(1, policyBatchNo);
            try (ResultSet rs = pst.executeQuery()) {
                while (rs.next()) {
                    PolicyCoinsuranceDto dto = new PolicyCoinsuranceDto();
                    dto.setPolicyCoinLeader(rs.getString(1));
                    dto.setPolicyCoinShare(rs.getBigDecimal(2));
                    result.add(dto);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch coinsurance details for batch "
                    + policyBatchNo + ": " + e.getMessage(), e);
        }
        return result;
    }

    public List<Coinsurance> getCoinsuranceDetails(BigDecimal policyBatchNo) {
        List<Coinsurance> result = new ArrayList<>();
        String query = "SELECT AGN_NAICOM_COIN_ID, COIN_PERCT FROM GIN_COINSURERS, TQC_AGENCIES " +
                "WHERE COIN_AGNT_AGENT_CODE = AGN_CODE AND COIN_POL_BATCH_NO = ?";
        try (Connection conn = jdbcTemplate.getDataSource().getConnection();
             PreparedStatement pst = conn.prepareStatement(query)) {
            pst.setBigDecimal(1, policyBatchNo);
            try (ResultSet rs = pst.executeQuery()) {
                while (rs.next()) {
                    Coinsurance c = new Coinsurance();
                    c.setCoinsuranceId(rs.getInt(1));
                    c.setPremiumPercentage(rs.getBigDecimal(2));
                    result.add(c);
                }
            }
        } catch (Exception e) {
            // Same reasoning: an empty list here would post a coinsurance policy with no
            // coinsurers attached rather than failing the batch.
            throw new IllegalStateException("Failed to fetch coinsurer details for batch "
                    + policyBatchNo + ": " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * Generic single-column lookup. {@code pk} and {@code key} are concatenated verbatim into
     * the WHERE clause, matching the original tps-apis DAO, because some call sites (e.g. the
     * sub-class lookup used while staging AUTO risks) pass a partial sub-query as {@code pk}
     * rather than a plain column name. Every caller in this codebase supplies hardcoded
     * table/column literals and internally-resolved numeric codes here, never raw user input.
     */
    public String getColumnValue(String table, String column, String pk, String key) {
        String query = "SELECT " + column + " FROM " + table + " WHERE " + pk + " = " + key;
        try (Connection conn = jdbcTemplate.getDataSource().getConnection();
             PreparedStatement pst = conn.prepareStatement(query);
             ResultSet rs = pst.executeQuery()) {
            String value = null;
            while (rs.next()) {
                value = rs.getString(1);
            }
            return value;
        } catch (Exception e) {
            log.error("Failed to look up {}.{} where {} = {}: {}", table, column, pk, key, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Calls the existing {@code gin_interfaces_pkg.update_policy_transaction} stored procedure
     * to record the outcome of a posting attempt - same procedure the tps-apis NAICOM controller
     * calls, so this stores both the posting result and the exact payload posted, matching the
     * staging-side behaviour of storing the payload.
     */
    public void updateNaicomRecordOnPosting(NaicomPolicyDto naicomRequest, NaicomPolicyResponseDto response, String postedStatus, String regulator) {
        if (naicomRequest.getPolicy_batch_no() == null) {
            throw new IllegalArgumentException("Policy Batch Number must be provided to update a transaction.");
        }
        String call = "{ call gin_interfaces_pkg.update_policy_transaction(?,?,?,?,?,?,?,?,?,?,?,?) }";
        String errors = response.getErrors() == null ? "" : response.getErrors().toString();
        try (Connection conn = jdbcTemplate.getDataSource().getConnection();
             CallableStatement cst = conn.prepareCall(call)) {
            cst.setBigDecimal(1, new BigDecimal(naicomRequest.getPolicy_batch_no()));
            cst.setString(2, response.getMessage() != null ? response.getMessage().concat(" - ").concat(errors) : errors);
            cst.setString(3, postedStatus);
            cst.setString(4, null);
            cst.setString(5, null);
            cst.setString(6, response.getPolicyUniqueID());
            cst.setString(7, response.toString());
            cst.setString(8, postedStatus);
            cst.setString(9, regulator);
            cst.setBigDecimal(10, null);
            cst.setString(11, "P");
            cst.setString(12, objectMapper.writeValueAsString(naicomRequest));
            cst.execute();
        } catch (Exception e) {
            throw new RuntimeException("Failed to update posting result for batch " + naicomRequest.getPolicy_batch_no(), e);
        }
    }

    /**
     * Calls the standalone {@code get_backlog_policies_prc} (deliberately not part of the
     * gin_interfaces_cursor package, so deploying it doesn't force a recompile of that
     * package's other procedures in prod). Returns the batch numbers with the given
     * POL_POLICY_STATUS that have not yet been staged for NAICOM (i.e. no matching row in
     * GIN_POLICY_TRANSACTIONS with GTP_TARGET_REGULATOR = 'NAICOM').
     */
    public List<BigDecimal> findPoliciesNotPostedToNAICOM(String transactionType) {
        return findPoliciesNotPostedToNAICOM(transactionType, 0, 0);
    }

    /**
     * Chunked variant of {@link #findPoliciesNotPostedToNAICOM(String)}: skips {@code offset}
     * batch numbers and returns at most {@code limit} of them ({@code limit <= 0} means all).
     * <p>
     * This is the change that actually makes the select fast. The procedure's cursor produces
     * rows lazily, so abandoning it after {@code limit} rows means the rest of the query never
     * runs. Measured against TQPDB on the NB backlog:
     * <pre>
     *   limit = 1,000    2.2s
     *   limit = 5,000    2.9s
     *   limit = 0 (all)  193s   (180,952 rows)
     * </pre>
     * <p>
     * {@code offset} is not free for the same reason - skipped rows still have to be produced,
     * at roughly a millisecond each. Prefer walking the backlog with {@code offset=0} and
     * repeated calls (posted policies drop out of the selection) over paging with a large
     * offset; reach for offset only to step over a block of policies that keep failing.
     */
    public List<BigDecimal> findPoliciesNotPostedToNAICOM(String transactionType, int offset, int limit) {
        int skip = Math.max(0, offset);
        boolean bounded = limit > 0;
        List<BigDecimal> batchNumbers = new ArrayList<>(bounded ? limit : INITIAL_BACKLOG_CAPACITY);
        String query = "{ call get_backlog_policies_prc(?,?) }";
        long startedAt = System.currentTimeMillis();

        try (Connection conn = jdbcTemplate.getDataSource().getConnection();
             CallableStatement cst = conn.prepareCall(query, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            cst.setFetchSize(BACKLOG_FETCH_SIZE);
            cst.setString(1, transactionType);
            cst.registerOutParameter(2, Types.REF_CURSOR);
            cst.executeQuery();
            try (ResultSet rs = (ResultSet) cst.getObject(2)) {
                if (rs == null) {
                    log.info("get_backlog_policies_prc returned no cursor for transaction type {}", transactionType);
                    return batchNumbers;
                }
                // The ref cursor's result set does not reliably inherit the statement's fetch
                // size, so set it here too. Worth a few percent (see BACKLOG_FETCH_SIZE) - the
                // early break below is what does the real work.
                rs.setFetchSize(BACKLOG_FETCH_SIZE);
                int skipped = 0;
                while (rs.next()) {
                    if (skipped < skip) {
                        skipped++;
                        continue;
                    }
                    batchNumbers.add(rs.getBigDecimal(1));
                    if (bounded && batchNumbers.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // Deliberately NOT swallowed. This used to log and return whatever had accumulated,
            // so a connection dropped mid-cursor looked like a short-but-complete backlog: on
            // 2026-09-08 a broken connection produced "batchCount=34450" and then "batchCount=31370"
            // against a real backlog of ~180,000, and the run processed those as the whole thing.
            // A failed select must fail, not quietly return a partial answer.
            throw new IllegalStateException("Failed to fetch backlog policies for transaction type "
                    + transactionType + " after " + batchNumbers.size() + " rows: " + e.getMessage(), e);
        }

        log.info("Fetched {} backlog batch numbers (offset={}, limit={}) for transaction type {} in {} ms",
                batchNumbers.size(), skip, bounded ? limit : "all", transactionType, System.currentTimeMillis() - startedAt);
        return batchNumbers;
    }
}
