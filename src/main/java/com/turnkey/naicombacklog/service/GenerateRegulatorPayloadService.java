package com.turnkey.naicombacklog.service;

import com.google.gson.Gson;
import com.turnkey.naicombacklog.dao.PolicyTransactionDao;
import com.turnkey.naicombacklog.dto.regulatorPayload.Coinsurance;
import com.turnkey.naicombacklog.dto.regulatorPayload.NaicomInsuredInfo;
import com.turnkey.naicombacklog.dto.regulatorPayload.NiidCustomer;
import com.turnkey.naicombacklog.dto.regulatorPayload.NiidData;
import com.turnkey.naicombacklog.dto.regulatorPayload.PolicyCoinsuranceDto;
import com.turnkey.naicombacklog.dto.regulatorPayload.PolicyTransactionDto;
import com.turnkey.naicombacklog.dto.regulatorPayload.PolicyTransactionResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Builds the NAICOM staging payload (the JSON stored in GTP_PAYLOAD), ported from tps-apis'
 * {@code GenerateRegulatorPayload.generateNaicomPayload}. Covers all 10 NAICOM product lines,
 * since this part of the original source was fully verified and is a straightforward port -
 * only the downstream *posting* logic (NaicomPostingService) is scoped to AUTO for this build.
 * The NIID-regulator counterparts ({@code generateMarinePolicy}/{@code generateAutoPolicy} in
 * the original) are intentionally not ported: this app only targets the NAICOM regulator.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GenerateRegulatorPayloadService {

    private final PolicyTransactionDao policyTransactionDao;

    public PolicyTransactionResponseDto generateNaicomPayload(PolicyTransactionDto transaction) {
        return generateNaicomPayload(transaction, null);
    }

    /**
     * @param preFetchedRisks the batch's risk rows if the caller has already loaded them (staging
     *                        always has), so the {@code get_policy_transaction_prc} cursor is not
     *                        re-opened for the very same batch while building the payload. Pass
     *                        {@code null} to have them fetched on demand.
     */
    public PolicyTransactionResponseDto generateNaicomPayload(PolicyTransactionDto transaction,
                                                              List<PolicyTransactionDto> preFetchedRisks) {
        PolicyTransactionResponseDto response = new PolicyTransactionResponseDto();
        response.setRequestCalled(false);

        // One query for both coinsurance header columns rather than two single-column lookups.
        // The coinsurer list itself is now fetched only inside the branch that consumes it, since
        // the large majority of policies are not coinsurance leaders.
        List<PolicyCoinsuranceDto> coinsuranceHeader = policyTransactionDao.getPolicyCoinsuranceDetails(transaction.getPOL_BATCH_NO());
        String policyCoinLeader = coinsuranceHeader.isEmpty() ? null : coinsuranceHeader.get(0).getPolicyCoinLeader();
        BigDecimal policyCoinShare = coinsuranceHeader.isEmpty() ? null : coinsuranceHeader.get(0).getPolicyCoinShare();

        String coverTo = checkPolicyCoverPeriod(transaction) ? adjustCoverFrom(transaction) : transaction.getPOL_POLICY_COVER_TO();

        String prg = transaction.getPRG_DESCN();
        if ("OIL & GAS".equalsIgnoreCase(prg)) {
            prg = "OIL";
        }

        NiidCustomer customer = new NiidCustomer();
        customer.setCity(transaction.getSTS_NAME());
        customer.setEmailAddress(transaction.getCLIENT_EMAIL());
        customer.setFirstName(transaction.getFIRST_NAME());
        customer.setLastName(transaction.getCLIENT_LAST_NAME());
        customer.setLicense(transaction.getCLIENT_ID_NUMBER());
        customer.setOrganizationId("n/a");
        customer.setOrganizationName("n/a");
        customer.setPhoneNumber(transaction.getCLIENT_PHONE());
        String postalCode = transaction.getCLIENT_POSTAL_ADDRESS() == null ? "" : transaction.getCLIENT_POSTAL_ADDRESS().replaceAll("[^0-9]", "");
        customer.setPostalAddress(postalCode);
        customer.setResidentialAddress(transaction.getCLIENT_RESIDENTIAL_ADDRESS());
        customer.setState(transaction.getSTS_NAME());
        customer.setType("INDIVIDUAL");

        NiidData data = new NiidData();
        data.setNaicomProduct(prg);
        data.setPolicyBatchNo(transaction.getPOL_BATCH_NO());
        data.setNaicomProductCode(transaction.getPRO_REGULATOR_SCH_CODE());
        data.setCommissionFee(transaction.getPOL_COMM_ENDOS_DIFF_AMT());
        data.setConditions("n/a");
        data.setCoverStartDate(transaction.getPOL_POLICY_COVER_FROM());
        data.setCoverEndDate(coverTo);
        data.setCoverType(transaction.getCOVT_DESC());
        data.setEndorsements("n/a");
        data.setExclusions("n/a");
        data.setExtraFee(transaction.getEXTRA_TPPD_CHARGE());
        data.setPolicyDescription("n/a");
        data.setPolicyNumber(transaction.getPOLICY_NUMBER());
        data.setPreamble("n/a");
        data.setPremiumNote(transaction.getDEBIT_NOTE_NUMBER());
        data.setSumInsured(transaction.getPOL_TOTAL_SUM_INSURED());
        data.setTerms("n/a");
        data.setTotalPremium(transaction.getPOL_TOT_ENDOS_DIFF_AMT());
        data.setRecorderType(transaction.getRECORDER_TYPE());
        data.setRecorder(transaction.getRECORDER());
        data.setRecorderId(transaction.getRECORDER_ID());
        if (transaction.getPREV_POLICY_ID() != null) {
            data.setPolicyUniqueID(transaction.getPREV_POLICY_ID());
        }
        if (transaction.getPOL_COINSURANCE() != null && "YES".equalsIgnoreCase(transaction.getPOL_COINSURANCE())
                && policyCoinLeader != null && "Y".equalsIgnoreCase(policyCoinLeader)) {
            data.setCoinsuranceLeader(policyCoinLeader);
            data.setCoInsuranceRate(policyCoinShare);
            data.setCoinsuranceDetails(policyTransactionDao.getCoinsuranceDetails(transaction.getPOL_BATCH_NO()));
        }
        data.setCoinsurance(transaction.getPOL_COINSURANCE());
        data.setTransactionType(transaction.getTransType());

        List<NaicomInsuredInfo> polRisks = new ArrayList<>();
        List<NaicomInsuredInfo> marineRisks = new ArrayList<>();
        List<PolicyTransactionDto> transRisks;

        switch (prg.trim()) {
            case "AUTO":
                transRisks = risksFor(transaction, preFetchedRisks);
                for (PolicyTransactionDto risk : transRisks) {
                    NaicomInsuredInfo insured = new NaicomInsuredInfo();
                    insured.setAutoNote("n/a");
                    insured.setEngineCapacity(risk.getCUBIC_CAPACITY());
                    insured.setPlateNo(risk.getVEHICLE_REGISTRATION());
                    insured.setRegistrationDate(risk.getPOLICY_INCEPTION_DATE());
                    insured.setRegistrationExpiryDate(risk.getPOLICY_EXPIRY_DATE());
                    insured.setRegistrationNumber(risk.getVEHICLE_REGISTRATION());
                    insured.setSeats(risk.getSEATS());
                    insured.setVehicleColor(risk.getCOLOR());
                    insured.setVehicleId(risk.getVEHICLE_REGISTRATION());
                    insured.setVehicleMake(risk.getVEHICLE_MAKE());
                    insured.setVehicleMileage(risk.getMILEAGE());
                    insured.setVehicleModel(risk.getVEHICLE_MODEL());
                    insured.setVehicleType(risk.getVEHICLE_BODY_TYPE());
                    insured.setYearOfManufacture(risk.getYEAR_OF_MANUFACTURE());
                    polRisks.add(insured);
                }
                customer.setType("PERSON");
                customer.setLicense("n/a");
                data.setCustomer(customer);
                data.setInsuredInfo(polRisks);
                break;

            case "BOND":
                customer.setLicense(null);
                customer.setType(transaction.getCLIENT_TYPE());
                customer.setIdentificationNumber(transaction.getCLIENT_NATIONAL_ID());
                customer.setIdentificationType("NATIONAL_ID_CARD");
                customer.setBeneficiaryType("INDIVIDUAL");
                data.setEntryPort("n/a");
                data.setMerchandiseDescription(transaction.getBON_CONTRACT_DETAILS());
                data.setMerchandiseValue(transaction.getBON_BOND_AMOUNT());
                data.setSubContractInfo("n/a");
                data.setTaxDuty(transaction.getIPU_VALUE());
                data.setPolicyDescription("n/a");
                data.setContractDescription(transaction.getBON_CONTRACT_DETAILS());
                data.setContractLocation("n/a");
                data.setContractPrice(transaction.getBON_BOND_AMOUNT());
                data.setCourtName("n/a");
                data.setCourtCaseNo("n/a");
                data.setBondPaid(BigDecimal.ZERO);
                data.setBondPercentage(BigDecimal.ZERO);
                data.setBondAmount(transaction.getBON_BOND_AMOUNT());
                data.setBondDescription(transaction.getBON_CONTRACT_DETAILS());
                data.setImporterNumber("n/a");
                data.setOriginCountry("n/a");
                data.setCommissionFee(BigDecimal.ZERO);
                data.setConditions(null);
                data.setCoverStartDate(transaction.getPOLICY_INCEPTION_DATE());
                data.setCoverEndDate(transaction.getPOLICY_EXPIRY_DATE());
                data.setCoverType(null);
                data.setEndorsements(null);
                data.setExclusions(null);
                data.setExtraFee(BigDecimal.ZERO);
                data.setPreamble(null);
                data.setPremiumNote(null);
                data.setTerms(null);
                data.setPolicyUniqueID(transaction.getPREV_POLICY_ID());
                data.setTransactionType(transaction.getTransType());
                data.setCustomer(customer);
                response.setIPU_CODE(transaction.getIPU_CODE());
                break;

            case "FIRE": {
                customer.setCity(null);
                customer.setFirstName(null);
                customer.setLastName(null);
                customer.setLicense(null);
                customer.setOrganizationId(null);
                customer.setOrganizationName(null);
                customer.setPostalAddress(null);
                customer.setResidentialAddress(null);
                customer.setState(null);
                customer.setType(transaction.getCLIENT_TYPE());
                customer.setName(transaction.getCLIENT_NAME());

                data.setCoverStartDate(transaction.getPOLICY_INCEPTION_DATE());
                data.setCoverEndDate(transaction.getPOLICY_EXPIRY_DATE());
                data.setCoverType(null);
                data.setPreamble(null);
                data.setPremiumNote(null);
                data.setTerms(null);

                NaicomInsuredInfo buildingInfo = new NaicomInsuredInfo();
                buildingInfo.setBuildingAddressLine(transaction.getBUILD_ADDRESS_LINE());
                buildingInfo.setBuildingCity(transaction.getBUILD_CITY());
                buildingInfo.setBuildingDoorNumber(transaction.getBUILD_DOOR_NO());
                buildingInfo.setBuildingName(transaction.getBUILD_NAME());
                buildingInfo.setBuildingPostCode(transaction.getBUILD_POST_CODE());
                buildingInfo.setBuildingState(transaction.getBUILD_STATE());

                NaicomInsuredInfo burglaryInfo = new NaicomInsuredInfo();
                burglaryInfo.setBurglaryAntiTheft("n/a");
                burglaryInfo.setBurglaryCoverageDetail(transaction.getBURGLARY_COVERAGE());
                burglaryInfo.setBurglaryHistory("n/a");

                NaicomInsuredInfo fireInfo = new NaicomInsuredInfo();
                fireInfo.setFireCoverageDetail("n/a");
                fireInfo.setFireHistory("n/a");
                fireInfo.setFireProtectionDetail("n/a");

                NaicomInsuredInfo houseInfo = new NaicomInsuredInfo();
                houseInfo.setHouseBuildingType(transaction.getHOUSE_BUILD_TYPE());
                houseInfo.setHouseCoverageDetail("n/a");
                houseInfo.setHouseHistory("n/a");
                houseInfo.setHouseSecurityDetail("n/a");

                NaicomInsuredInfo propertyInfo = new NaicomInsuredInfo();
                propertyInfo.setPropertyBusiness("n/a");
                propertyInfo.setPropertyConstruction("n/a");
                propertyInfo.setPropertyConstructionValue(transaction.getPROP_CONSTRUCT_VALUE());
                propertyInfo.setPropertyContent("n/a");
                propertyInfo.setPropertyContentValue(transaction.getPROP_CONTENT_VALUE());

                data.setCustomer(customer);
                data.setBuildingDetails(buildingInfo);
                data.setBurglaryDetails(burglaryInfo);
                data.setFireDetails(fireInfo);
                data.setHouseDetails(houseInfo);
                data.setPropertyDetails(propertyInfo);
                data.setPolicyUniqueID(transaction.getPREV_POLICY_ID());
                data.setTransactionType(transaction.getTransType());
                response.setIPU_CODE(transaction.getIPU_CODE());
                break;
            }

            case "MARINE":
                customer.setFirstName(null);
                customer.setLastName(null);
                customer.setLicense(null);
                customer.setOrganizationId(null);
                customer.setOrganizationName(null);
                customer.setResidentialAddress(null);
                customer.setType(transaction.getCLIENT_TYPE());
                customer.setAddressCode(transaction.getCLIENT_RESIDENTIAL_ADDRESS());
                customer.setName(transaction.getCLIENT_NAME());
                customer.setPostalAddress(transaction.getCLIENT_POSTAL_ADDRESS());
                data.setPolicyUniqueID(transaction.getPREV_POLICY_ID());
                data.setTransactionType(transaction.getTransType());
                data.setCoverType(null);
                data.setPreamble(null);
                data.setPremiumNote(null);
                data.setTerms(null);
                data.setMarineCoverType(transaction.getPOLICY_COVER_TYPE_CODE().replace("-", "_"));

                transRisks = risksFor(transaction, preFetchedRisks);
                for (PolicyTransactionDto risk : transRisks) {
                    NaicomInsuredInfo insured = new NaicomInsuredInfo();
                    insured.setVesselBuildYear(risk.getVESSEL_BUILD_YEAR());
                    insured.setVesselCarriageCapacity(risk.getVESSEL_CARRIAGE_CAPACITY());
                    insured.setVesselInsuredValue(risk.getSUM_INSURED());
                    insured.setVesselModel(risk.getVESSEL_MODEL());
                    insured.setVesselName(risk.getVESSEL_NAME());
                    insured.setVesselNote("n/a");
                    insured.setVesselPurchaseValue(risk.getVESSEL_PURCHASE_VALUE());
                    insured.setVesselPurchaseYear(risk.getVESSEL_PURCHASE_YEAR());
                    insured.setVesselRegNo(risk.getVEHICLE_REGISTRATION());
                    insured.setVesselType(risk.getVESSEL_TYPE());
                    insured.setVesselUsage("n/a");
                    marineRisks.add(insured);
                }
                data.setCustomer(customer);
                data.setInsuredInfo(marineRisks);
                break;

            case "CASUALTY": {
                data.setCustomer(customer);
                customer.setCity("n/a");
                customer.setFirstName(transaction.getFIRST_NAME() == null ? "n/a" : transaction.getFIRST_NAME());
                customer.setLastName(transaction.getCLIENT_LAST_NAME() == null ? "n/a" : transaction.getCLIENT_LAST_NAME());
                customer.setLicense("n/a");
                customer.setOrganizationId("n/a");
                customer.setOrganizationName("n/a");
                customer.setResidentialAddress("n/a");
                customer.setState("n/a");
                customer.setType(transaction.getCLIENT_TYPE());
                customer.setDateOfBirth(transaction.getCLIENT_DATE_OF_BIRTH());
                customer.setName(transaction.getCLIENT_NAME());
                customer.setGender(transaction.getCLNT_GENDER());
                customer.setTitle(transaction.getCLNT_TITLE());
                customer.setPostalAddress(transaction.getCLIENT_POSTAL_ADDRESS());

                data.setCoverStartDate(transaction.getPOLICY_INCEPTION_DATE());
                data.setCoverEndDate(transaction.getPOLICY_EXPIRY_DATE());
                data.setCoverType(transaction.getCOVT_DESC());
                data.setProfessionalActivityInfo("n/a");
                data.setProfessionalEmployeeInfo("n/a");
                data.setCompanyDirectorInfo("n/a");
                data.setCompanyInfo("n/a");
                data.setPropertyInfo("n/a");
                data.setPublicRiskInfo("n/a");
                data.setRiskManagementDetail("n/a");
                data.setSafeGuardInfo("n/a");
                data.setSafeStrongRoomDetails("n/a");
                data.setTransitRoute("n/a");
                data.setGoodTransitMethod("n/a");
                data.setTransitSchedule("n/a");
                data.setTransitVehicleInfo("n/a");
                data.setWorkCondition("n/a");
                data.setWorkDescription("n/a");
                data.setWorkEmployeeDetails("n/a");
                data.setEmployeeInfo("n/a");
                data.setAccountInfo("n/a");
                data.setWorkLocation("n/a");
                data.setOtherConditions(BigDecimal.ZERO);
                data.setEstimatedEarnings("n/a");
                data.setEstimatedWages("n/a");
                data.setPolicyUniqueID(transaction.getPREV_POLICY_ID());
                data.setTransactionType(transaction.getTransType());

                NaicomInsuredInfo accidentInfo = new NaicomInsuredInfo();
                accidentInfo.setAccidentBeneficiaryInfo("n/a");
                accidentInfo.setAccidentCoverType("n/a");
                accidentInfo.setAccidentInsuredBenefit("n/a");
                accidentInfo.setAccidentInsuredPersons("n/a");

                NaicomInsuredInfo premiseInfo = new NaicomInsuredInfo();
                premiseInfo.setPremisesBusinessHour("8am-9pm");
                premiseInfo.setPremisesBusinessType("Commerical");
                premiseInfo.setPremisesLocation("121/123 Funso Williams Avenue, Iponri");
                premiseInfo.setPremisesName("121/123 Funso Williams Avenue, Iponri");
                premiseInfo.setPremisesOccupation("INSURANCE");

                data.setBurglaryDetails(accidentInfo);
                data.setFireDetails(premiseInfo);
                data.setAccidentInfo(accidentInfo);
                data.setPremiseDetails(premiseInfo);
                response.setIPU_CODE(transaction.getIPU_CODE());
                break;
            }

            case "MISCELLANEOUS":
                customer.setLicense(null);
                customer.setOrganizationId(null);
                customer.setOrganizationName(null);
                customer.setResidentialAddress(null);
                customer.setIdentificationNumber(transaction.getCLIENT_NATIONAL_ID());
                customer.setAddressCode(transaction.getCLIENT_RESIDENTIAL_ADDRESS());
                customer.setIdentificationType("NATIONAL_ID_CARD");
                customer.setBeneficiaryType("INDIVIDUAL");
                customer.setPostalAddress(transaction.getCLIENT_POSTAL_ADDRESS());
                data.setPolicyUniqueID(transaction.getPREV_POLICY_ID());
                data.setTransactionType(transaction.getTransType());
                data.setCoverStartDate(transaction.getPOLICY_INCEPTION_DATE());
                data.setCoverEndDate(transaction.getPOLICY_EXPIRY_DATE());
                data.setCoverType(null);
                data.setInsuranceDescription("n/a");
                data.setCustomer(customer);
                response.setIPU_CODE(transaction.getIPU_CODE());
                break;

            case "OIL":
                customer.setDateOfBirth(transaction.getCLIENT_DATE_OF_BIRTH());
                customer.setName(transaction.getCLIENT_NAME());
                customer.setGender(transaction.getCLNT_GENDER());
                customer.setTitle(transaction.getCLNT_TITLE());
                customer.setEmailAddress(transaction.getCLIENT_EMAIL());
                customer.setFirstName(transaction.getFIRST_NAME());
                customer.setLastName(transaction.getCLIENT_LAST_NAME());
                customer.setLicense(transaction.getCLIENT_ID_NUMBER());
                customer.setPostalAddress(transaction.getCLIENT_POSTAL_ADDRESS());
                customer.setOrganizationId(null);
                customer.setOrganizationName(null);
                customer.setResidentialAddress(transaction.getCLIENT_RESIDENTIAL_ADDRESS());
                customer.setType(transaction.getCLIENT_TYPE());
                customer.setAddressCode(transaction.getCLIENT_RESIDENTIAL_ADDRESS());

                data.setConditions(null);
                data.setCoverStartDate(transaction.getPOLICY_INCEPTION_DATE());
                data.setCoverEndDate(transaction.getPOLICY_EXPIRY_DATE());
                data.setCoverType(null);
                data.setEndorsements(null);
                data.setExclusions(null);
                data.setExtraFee(null);
                data.setPreamble(null);
                data.setPremiumNote(null);
                data.setTerms(null);
                data.setPostCode(transaction.getCLIENT_RESIDENTIAL_ADDRESS());
                data.setPlantType(transaction.getPLANT_TYPE());
                data.setProjectDetail(transaction.getPROJECT_DETAIL());
                data.setProjectName(transaction.getPROJECT_NAME());
                data.setContractDescription(transaction.getCONTRACT_DESCRIPTION());
                data.setContractLocation(transaction.getCONTRACT_LOCATION());
                data.setContractorName(transaction.getCONTRACTOR_NAME());
                data.setContractorAddressLine(transaction.getCONTRACTOR_ADDRESS_LINE());
                data.setContractorCity(transaction.getCONTRACTOR_CITY());
                data.setContractorState(transaction.getCONTRACTOR_STATE());
                data.setPolicyUniqueID(transaction.getPREV_POLICY_ID());
                data.setTransactionType(transaction.getTransType());
                data.setCustomer(customer);
                response.setIPU_CODE(transaction.getIPU_CODE());
                break;

            case "ENGINEERING":
                customer.setDateOfBirth(transaction.getCLIENT_DATE_OF_BIRTH());
                customer.setName(transaction.getCLIENT_NAME());
                customer.setGender(transaction.getCLNT_GENDER());
                customer.setTitle(transaction.getCLNT_TITLE());
                customer.setEmailAddress(transaction.getCLIENT_EMAIL());
                customer.setFirstName(transaction.getFIRST_NAME());
                customer.setLastName(transaction.getCLIENT_LAST_NAME());
                customer.setLicense(transaction.getCLIENT_ID_NUMBER());
                customer.setPostalAddress(transaction.getCLIENT_POSTAL_ADDRESS());
                customer.setCity(transaction.getSTS_NAME());
                customer.setOrganizationId("n/a");
                customer.setOrganizationName("n/a");
                customer.setResidentialAddress(transaction.getCLIENT_RESIDENTIAL_ADDRESS());
                customer.setState(transaction.getSTS_NAME());
                customer.setPhoneNumber(null);
                customer.setType(transaction.getCLIENT_TYPE());
                customer.setAddressCode(transaction.getCLIENT_RESIDENTIAL_ADDRESS());
                customer.setIdentificationNumber(transaction.getCLIENT_NATIONAL_ID());
                customer.setIdentificationType("NATIONAL_ID_CARD");
                customer.setBeneficiaryType("INDIVIDUAL");

                data.setConditions(null);
                data.setCoverStartDate(transaction.getPOLICY_INCEPTION_DATE());
                data.setCoverEndDate(transaction.getPOLICY_EXPIRY_DATE());
                data.setCoverType(null);
                data.setEndorsements(null);
                data.setExclusions(null);
                data.setExtraFee(null);
                data.setPreamble(null);
                data.setPremiumNote(null);
                data.setTerms(null);
                data.setContractDescription(transaction.getCONTRACT_DESCRIPTION());
                data.setContractLocation(transaction.getCONTRACT_LOCATION());
                data.setContractorName(transaction.getCONTRACTOR_NAME());
                data.setContractorAddressLine(transaction.getCONTRACTOR_ADDRESS_LINE());
                data.setPostCode(transaction.getCLIENT_RESIDENTIAL_ADDRESS());
                data.setContractorCity(transaction.getCONTRACTOR_CITY());
                data.setContractorState(transaction.getCONTRACTOR_STATE());
                data.setContractSite(transaction.getCONTRACTOR_SITE());
                data.setAllPlantEquipmentCovered("Y".equalsIgnoreCase(transaction.getPLANT_EQUIPMENT_COVERED()) ? "Y" : "N");
                data.setMachineryConditions(transaction.getMACHINERY_CONDITIONS());
                data.setMachineryDescription(transaction.getMACHINERY_DESCRIPTION());
                data.setMachineryModel(transaction.getMACHINERY_MODEL());
                data.setMachineryYearOfManufacture(transaction.getMACHINERY_YOM());
                data.setMachinerySerialNumber(transaction.getMACHINERY_SERIAL());
                data.setIsMachineryLeased("Y".equalsIgnoreCase(transaction.getMACHINERY_LEASED()) ? "Y" : "N");
                data.setEstimatedMaximumLoss(transaction.getESTIMATED_MAXIMUM_LOSS());
                data.setDeductibleValue(transaction.getDEDUCTIBLE());
                data.setPolicyUniqueID(transaction.getPREV_POLICY_ID());
                data.setTransactionType(transaction.getTransType());
                data.setCustomer(customer);
                response.setIPU_CODE(transaction.getIPU_CODE());
                break;

            case "AVIATION":
                customer.setFirstName(null);
                customer.setLastName(null);
                customer.setLicense(null);
                customer.setOrganizationId(null);
                customer.setOrganizationName(null);
                customer.setResidentialAddress(null);
                customer.setType(transaction.getCLIENT_TYPE());
                customer.setAddressCode(transaction.getCLIENT_RESIDENTIAL_ADDRESS());
                customer.setName(transaction.getCLIENT_NAME());
                customer.setPostalAddress(transaction.getCLIENT_POSTAL_ADDRESS());
                data.setPolicyUniqueID(transaction.getPREV_POLICY_ID());
                data.setTransactionType(transaction.getTransType());
                data.setCoverType(null);
                data.setPreamble(null);
                data.setPremiumNote(null);
                data.setTerms(null);
                data.setMarineCoverType(transaction.getPOLICY_COVER_TYPE_CODE().replace("-", "_"));

                transRisks = risksFor(transaction, preFetchedRisks);
                for (PolicyTransactionDto risk : transRisks) {
                    NaicomInsuredInfo insured = new NaicomInsuredInfo();
                    insured.setVesselBuildYear(risk.getVESSEL_BUILD_YEAR());
                    insured.setVesselCarriageCapacity(risk.getVESSEL_CARRIAGE_CAPACITY());
                    insured.setVesselInsuredValue(risk.getSUM_INSURED());
                    insured.setVesselModel(risk.getVESSEL_MODEL());
                    insured.setVesselName(risk.getVESSEL_NAME());
                    insured.setVesselNote("n/a");
                    insured.setVesselPurchaseValue(risk.getVESSEL_PURCHASE_VALUE());
                    insured.setVesselPurchaseYear(risk.getVESSEL_PURCHASE_YEAR());
                    insured.setVesselRegNo(risk.getVEHICLE_REGISTRATION());
                    insured.setVesselType(risk.getVESSEL_TYPE());
                    insured.setVesselUsage("n/a");
                    marineRisks.add(insured);
                }
                data.setCustomer(customer);
                data.setInsuredInfo(marineRisks);
                break;

            case "ACCIDENT": {
                customer.setCity("n/a");
                customer.setFirstName("n/a");
                customer.setLastName("n/a");
                customer.setLicense("n/a");
                customer.setOrganizationId("n/a");
                customer.setOrganizationName("n/a");
                customer.setResidentialAddress("n/a");
                customer.setState("n/a");
                customer.setType(transaction.getCLIENT_TYPE());
                customer.setDateOfBirth(transaction.getCLIENT_DATE_OF_BIRTH());
                customer.setName(transaction.getCLIENT_NAME());
                customer.setGender(transaction.getCLNT_GENDER());
                customer.setTitle(transaction.getCLNT_TITLE());
                customer.setPostalAddress(transaction.getCLIENT_POSTAL_ADDRESS());

                data.setCoverStartDate(transaction.getPOLICY_INCEPTION_DATE());
                data.setCoverEndDate(transaction.getPOLICY_EXPIRY_DATE());
                data.setCoverType(transaction.getCOVT_DESC());
                data.setProfessionalActivityInfo("n/a");
                data.setProfessionalEmployeeInfo("n/a");
                data.setCompanyDirectorInfo("n/a");
                data.setCompanyInfo("n/a");
                data.setPropertyInfo("n/a");
                data.setPublicRiskInfo("n/a");
                data.setRiskManagementDetail("n/a");
                data.setSafeGuardInfo("n/a");
                data.setSafeStrongRoomDetails("n/a");
                data.setTransitRoute("n/a");
                data.setGoodTransitMethod("n/a");
                data.setTransitSchedule("n/a");
                data.setTransitVehicleInfo("n/a");
                data.setWorkCondition("n/a");
                data.setWorkDescription("n/a");
                data.setWorkEmployeeDetails("n/a");
                data.setEmployeeInfo("n/a");
                data.setAccountInfo("n/a");
                data.setWorkLocation("n/a");
                data.setOtherConditions(BigDecimal.ZERO);
                data.setEstimatedEarnings("n/a");
                data.setEstimatedWages("n/a");
                data.setPolicyUniqueID(transaction.getPREV_POLICY_ID());
                data.setTransactionType(transaction.getTransType());

                NaicomInsuredInfo accInfo = new NaicomInsuredInfo();
                accInfo.setAccidentBeneficiaryInfo("n/a");
                accInfo.setAccidentCoverType("n/a");
                accInfo.setAccidentInsuredBenefit("n/a");
                accInfo.setAccidentInsuredPersons("n/a");

                NaicomInsuredInfo premInfo = new NaicomInsuredInfo();
                premInfo.setPremisesBusinessHour("n/a");
                premInfo.setPremisesBusinessType("n/a");
                premInfo.setPremisesLocation("n/a");
                premInfo.setPremisesName("n/a");
                premInfo.setPremisesOccupation("n/a");

                data.setCustomer(customer);
                data.setBurglaryDetails(accInfo);
                data.setFireDetails(premInfo);
                data.setAccidentInfo(accInfo);
                data.setPremiseDetails(premInfo);
                response.setIPU_CODE(transaction.getIPU_CODE());
                break;
            }

            default:
                log.warn("Unrecognised NAICOM product line '{}' for batch {} - staging payload built with basic fields only", prg, transaction.getPOL_BATCH_NO());
        }

        Gson gson = new Gson();
        response.setRegulatorPayload(gson.toJson(data));
        return response;
    }

    /** The batch's risks, reusing what the caller already loaded rather than re-opening the cursor. */
    private List<PolicyTransactionDto> risksFor(PolicyTransactionDto transaction, List<PolicyTransactionDto> preFetchedRisks) {
        return preFetchedRisks != null ? preFetchedRisks : policyTransactionDao.getPolicyDetails(transaction.getPOL_BATCH_NO());
    }

    private String adjustCoverFrom(PolicyTransactionDto transaction) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        Date fromDate;
        try {
            fromDate = format.parse(transaction.getPOL_POLICY_COVER_FROM());
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        LocalDate localDate = fromDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return localDate.plusYears(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    private boolean checkPolicyCoverPeriod(PolicyTransactionDto transaction) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        Date fromDate;
        Date toDate;
        try {
            fromDate = formatter.parse(transaction.getPOL_POLICY_COVER_FROM());
            toDate = formatter.parse(transaction.getPOL_POLICY_COVER_TO());
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        if (fromDate == null || toDate == null) {
            return false;
        }
        long diffInMillis = toDate.getTime() - fromDate.getTime();
        long diffInYears = TimeUnit.DAYS.convert(diffInMillis, TimeUnit.MILLISECONDS) / 365;
        return diffInYears > 1;
    }
}
