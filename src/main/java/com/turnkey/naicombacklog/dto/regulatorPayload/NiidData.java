package com.turnkey.naicombacklog.dto.regulatorPayload;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * The staged regulator payload shape (Gson-serialized into GTP_PAYLOAD).
 * Field coverage spans all 10 NAICOM product lines, matching the original service.
 */
@Data
public class NiidData {
    @SerializedName("adjusted_premium")
    @Expose
    private BigDecimal adjustedPremium;
    @SerializedName("agent")
    @Expose
    private NiidAgent agent;
    @SerializedName("basic_premium")
    @Expose
    private BigDecimal basicPremium;
    @SerializedName("body_type")
    @Expose
    private String bodyType;
    @SerializedName("branch_code")
    @Expose
    private String branchCode;
    @SerializedName("certificate_fee")
    @Expose
    private BigDecimal certificateFee;
    @SerializedName("chassis_number")
    @Expose
    private String chassisNumber;
    @SerializedName("certificate_number")
    @Expose
    private String certificateNumber;
    @SerializedName("co_insurance_amount")
    @Expose
    private BigDecimal coInsuranceAmount;
    @SerializedName("co_insurance_rate")
    @Expose
    private BigDecimal coInsuranceRate;
    @SerializedName("cover_end_date")
    @Expose
    private String coverEndDate;
    @SerializedName("cover_start_date")
    @Expose
    private String coverStartDate;
    @SerializedName("cover_type")
    @Expose
    private String coverType;
    @SerializedName("cubic_capacity")
    @Expose
    private String cubicCapacity;
    @SerializedName("currency_code")
    @Expose
    private String currencyCode;
    @SerializedName("customer")
    @Expose
    private NiidCustomer customer;
    @SerializedName("days")
    @Expose
    private Long days;
    @SerializedName("debit_note_number")
    @Expose
    private String debitNoteNumber;
    @SerializedName("engine_number")
    @Expose
    private String engineNumber;
    @SerializedName("exchange_rate")
    @Expose
    private BigDecimal exchangeRate;
    @SerializedName("is_fleet")
    @Expose
    private Boolean isFleet;
    @SerializedName("policy_number")
    @Expose
    private String policyNumber;
    @SerializedName("seats")
    @Expose
    private Long seats;
    @SerializedName("sub_total_premium")
    @Expose
    private BigDecimal subTotalPremium;
    @SerializedName("sum_insured")
    @Expose
    private BigDecimal sumInsured;
    @SerializedName("sum_insured_rate")
    @Expose
    private BigDecimal sumInsuredRate;
    @SerializedName("territy_zone")
    @Expose
    private String territyZone;
    @SerializedName("total_premium")
    @Expose
    private BigDecimal totalPremium;
    @SerializedName("vehicle_color")
    @Expose
    private String vehicleColor;
    @SerializedName("vehicle_make")
    @Expose
    private String vehicleMake;
    @SerializedName("vehicle_mileage")
    @Expose
    private BigDecimal vehicleMileage;
    @SerializedName("vehicle_model")
    @Expose
    private String vehicleModel;
    @SerializedName("vehicle_registration_number")
    @Expose
    private String vehicleRegistrationNumber;
    @SerializedName("old_vehicle_registration_number")
    @Expose
    private String oldVehicleRegistrationNumber;
    @SerializedName("year_of_manufacture")
    @Expose
    private String yearOfManufacture;

    // Marine (NIID) fields
    @SerializedName("customer_category")
    @Expose
    private String customerCategory;
    @SerializedName("inception_date")
    @Expose
    private String inceptionDate;
    @SerializedName("invoiced_value")
    @Expose
    private BigDecimal invoicedValue;
    @SerializedName("nature_of_cargo")
    @Expose
    private String natureOfCargo;
    @SerializedName("packaging_type")
    @Expose
    private String packagingType;
    @SerializedName("policy_type")
    @Expose
    private String policyType;
    @SerializedName("premium")
    @Expose
    private BigDecimal premium;
    @SerializedName("proforma_invoice")
    @Expose
    private String proformaInvoice;
    @SerializedName("sailing_from")
    @Expose
    private String sailingFrom;
    @SerializedName("sailing_to")
    @Expose
    private String sailingTo;
    @SerializedName("total_rate")
    @Expose
    private BigDecimal totalRate;
    @SerializedName("vessel_name")
    @Expose
    private String vesselName;
    @SerializedName("war_strike_rate")
    @Expose
    private BigDecimal warStrikeRate;
    @SerializedName("bank_name")
    @Expose
    private String bankName;
    @SerializedName("basic_rate")
    @Expose
    private BigDecimal basicRate;
    @SerializedName("cargo_currency_type")
    @Expose
    private String cargoCurrencyType;
    @SerializedName("cargo_description")
    @Expose
    private String cargoDescription;
    @SerializedName("certificate_no")
    @Expose
    private String certificateNo;
    @SerializedName("coinsurance")
    @Expose
    private String coinsurance;
    @SerializedName("coinsurance_leader")
    @Expose
    private String coinsuranceLeader;
    @SerializedName("coinsurance_details")
    @Expose
    private List<Coinsurance> coinsuranceDetails;
    @SerializedName("condition")
    @Expose
    private String condition;
    @SerializedName("naicom_product")
    @Expose
    private String naicomProduct;
    @SerializedName("commission_fee")
    @Expose
    private BigDecimal commissionFee;
    @SerializedName("conditions")
    @Expose
    private String conditions;
    @SerializedName("endorsements")
    @Expose
    private String endorsements;
    @SerializedName("exclusions")
    @Expose
    private String exclusions;
    @SerializedName("extra_fee")
    @Expose
    private BigDecimal extraFee;
    @SerializedName("policy_description")
    @Expose
    private String policyDescription;
    @SerializedName("preamble")
    @Expose
    private String preamble;
    @SerializedName("premium_note")
    @Expose
    private String premiumNote;
    @SerializedName("terms")
    @Expose
    private String terms;
    @SerializedName("naicom_product_code")
    @Expose
    private BigDecimal naicomProductCode;
    @SerializedName("insurance_description")
    @Expose
    private String insuranceDescription;

    @SerializedName("insured_info")
    @Expose
    private List<NaicomInsuredInfo> insuredInfo;
    @SerializedName("entry_port")
    @Expose
    private String entryPort;
    @SerializedName("merchandise_description")
    @Expose
    private String merchandiseDescription;
    @SerializedName("merchandise_value")
    @Expose
    private BigDecimal merchandiseValue;
    @SerializedName("sub_contract_info")
    @Expose
    private String subContractInfo;
    @SerializedName("tax_duty")
    @Expose
    private BigDecimal taxDuty;
    @SerializedName("contract_description")
    @Expose
    private String contractDescription;
    @SerializedName("contract_location")
    @Expose
    private String contractLocation;
    @SerializedName("contract_price")
    @Expose
    private BigDecimal contractPrice;
    @SerializedName("court_name")
    @Expose
    private String courtName;
    @SerializedName("court_case_no")
    @Expose
    private String courtCaseNo;
    @SerializedName("bond_paid")
    @Expose
    private BigDecimal bondPaid;
    @SerializedName("bond_percentage")
    @Expose
    private BigDecimal bondPercentage;
    @SerializedName("bond_amount")
    @Expose
    private BigDecimal bondAmount;
    @SerializedName("bond_description")
    @Expose
    private String bondDescription;
    @SerializedName("importer_number")
    @Expose
    private String importerNumber;
    @SerializedName("origin_country")
    @Expose
    private String originCountry;

    // Fire details
    @SerializedName("building_details")
    @Expose
    private NaicomInsuredInfo buildingDetails;
    @SerializedName("burglary_details")
    @Expose
    private NaicomInsuredInfo burglaryDetails;
    @SerializedName("fire_details")
    @Expose
    private NaicomInsuredInfo fireDetails;
    @SerializedName("house_details")
    @Expose
    private NaicomInsuredInfo houseDetails;
    @SerializedName("property_details")
    @Expose
    private NaicomInsuredInfo propertyDetails;

    // Marine details
    @SerializedName("marine_cover_type")
    @Expose
    private String marineCoverType;
    @SerializedName("vessel_info")
    @Expose
    private List<NaicomInsuredInfo> vesselInfo;

    // Casualty details
    @SerializedName("professional_activity_info")
    @Expose
    private String professionalActivityInfo;
    @SerializedName("professional_employee_info")
    @Expose
    private String professionalEmployeeInfo;
    @SerializedName("company_director_info")
    @Expose
    private String companyDirectorInfo;
    @SerializedName("company_info")
    @Expose
    private String companyInfo;
    @SerializedName("property_info")
    @Expose
    private String propertyInfo;
    @SerializedName("public_risk_info")
    @Expose
    private String publicRiskInfo;
    @SerializedName("risk_management_detail")
    @Expose
    private String riskManagementDetail;
    @SerializedName("safe_guard_info")
    @Expose
    private String safeGuardInfo;
    @SerializedName("safe_strong_room_details")
    @Expose
    private String safeStrongRoomDetails;
    @SerializedName("transit_route")
    @Expose
    private String transitRoute;
    @SerializedName("transit_schedule")
    @Expose
    private String transitSchedule;
    @SerializedName("transit_vehicle_info")
    @Expose
    private String transitVehicleInfo;
    @SerializedName("good_transit_method")
    @Expose
    private String goodTransitMethod;
    @SerializedName("work_condition")
    @Expose
    private String workCondition;
    @SerializedName("work_description")
    @Expose
    private String workDescription;
    @SerializedName("work_employee_details")
    @Expose
    private String workEmployeeDetails;
    @SerializedName("employee_info")
    @Expose
    private String employeeInfo;
    @SerializedName("account_info")
    @Expose
    private String accountInfo;
    @SerializedName("work_location")
    @Expose
    private String workLocation;
    @SerializedName("other_conditions")
    @Expose
    private BigDecimal otherConditions;
    @SerializedName("estimated_earnings")
    @Expose
    private String estimatedEarnings;
    @SerializedName("estimated_wages")
    @Expose
    private String estimatedWages;

    @SerializedName("accident_info")
    @Expose
    private NaicomInsuredInfo accidentInfo;

    @SerializedName("premise_details")
    @Expose
    private NaicomInsuredInfo premiseDetails;

    // Oil details
    @SerializedName("post_code")
    @Expose
    private String postCode;
    @SerializedName("plant_type")
    @Expose
    private String plantType;
    @SerializedName("project_detail")
    @Expose
    private String projectDetail;
    @SerializedName("project_name")
    @Expose
    private String projectName;
    @SerializedName("contractor_name")
    @Expose
    private String contractorName;
    @SerializedName("contractor_address_line")
    @Expose
    private String contractorAddressLine;
    @SerializedName("contractor_city")
    @Expose
    private String contractorCity;
    @SerializedName("contractor_state")
    @Expose
    private String contractorState;

    // Engineering details
    @SerializedName("contract_site")
    @Expose
    private String contractSite;
    @SerializedName("all_plant_equipment_covered")
    @Expose
    private String allPlantEquipmentCovered;
    @SerializedName("machinery_conditions")
    @Expose
    private String machineryConditions;
    @SerializedName("machinery_description")
    @Expose
    private String machineryDescription;
    @SerializedName("machinery_model")
    @Expose
    private String machineryModel;
    @SerializedName("machinery_year_of_manufacture")
    @Expose
    private String machineryYearOfManufacture;
    @SerializedName("machine_serial_number")
    @Expose
    private String machinerySerialNumber;
    @SerializedName("is_machinery_leased")
    @Expose
    private String isMachineryLeased;
    @SerializedName("estimated_maximum_loss")
    @Expose
    private BigDecimal estimatedMaximumLoss;
    @SerializedName("deductible_value")
    @Expose
    private BigDecimal deductibleValue;

    @SerializedName("recorder_type")
    @Expose
    private String recorderType;

    @SerializedName("recorder")
    @Expose
    private String recorder;

    @SerializedName("recorder_id")
    @Expose
    private BigDecimal recorderId;

    @SerializedName("policy_unique_id")
    @Expose
    private String policyUniqueID;

    @SerializedName("trans_type")
    @Expose
    private String transactionType;

    @SerializedName("risk_ipu_code")
    @Expose
    private BigDecimal riskIpuCode;

    @SerializedName("policy_batch_no")
    @Expose
    private BigDecimal policyBatchNo;

    @SerializedName("sub_class_desc")
    @Expose
    private String subClassDesc;

    @SerializedName("cancel_policy")
    @Expose
    private String cancel_policy;
}
