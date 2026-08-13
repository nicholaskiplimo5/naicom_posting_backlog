package com.turnkey.naicombacklog.dto.regulatorPayload;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class NaicomInsuredInfo {
    @SerializedName("auto_note")
    @Expose
    private String autoNote;
    @SerializedName("engine_capacity")
    @Expose
    private String engineCapacity;
    @SerializedName("plate_no")
    @Expose
    private String plateNo;
    @SerializedName("registration_date")
    @Expose
    private String registrationDate;
    @SerializedName("registration_expiry_date")
    @Expose
    private String registrationExpiryDate;
    @SerializedName("registration_number")
    @Expose
    private String registrationNumber;
    @SerializedName("seats")
    @Expose
    private Long seats;
    @SerializedName("vehicle_color")
    @Expose
    private String vehicleColor;
    @SerializedName("vehicle_id")
    @Expose
    private String vehicleId;
    @SerializedName("vehicle_make")
    @Expose
    private String vehicleMake;
    @SerializedName("vehicle_mileage")
    @Expose
    private BigDecimal vehicleMileage;
    @SerializedName("vehicle_model")
    @Expose
    private String vehicleModel;
    @SerializedName("vehicle_type")
    @Expose
    private String vehicleType;
    @SerializedName("year_of_manufacture")
    @Expose
    private String yearOfManufacture;

    // Fire details
    @SerializedName("building_address_line")
    @Expose
    private String buildingAddressLine;
    @SerializedName("building_city")
    @Expose
    private String buildingCity;
    @SerializedName("building_door_number")
    @Expose
    private String buildingDoorNumber;
    @SerializedName("building_name")
    @Expose
    private String buildingName;
    @SerializedName("building_post_code")
    @Expose
    private String buildingPostCode;
    @SerializedName("building_state")
    @Expose
    private String buildingState;
    @SerializedName("burglary_anti_theft")
    @Expose
    private String burglaryAntiTheft;
    @SerializedName("burglary_coverage_detail")
    @Expose
    private String burglaryCoverageDetail;
    @SerializedName("burglary_history")
    @Expose
    private String burglaryHistory;
    @SerializedName("fire_coverage_detail")
    @Expose
    private String fireCoverageDetail;
    @SerializedName("fire_history")
    @Expose
    private String fireHistory;
    @SerializedName("fire_protection_detail")
    @Expose
    private String fireProtectionDetail;
    @SerializedName("house_building_type")
    @Expose
    private String houseBuildingType;
    @SerializedName("house_coverage_detail")
    @Expose
    private String houseCoverageDetail;
    @SerializedName("house_history")
    @Expose
    private String houseHistory;
    @SerializedName("house_security_detail")
    @Expose
    private String houseSecurityDetail;
    @SerializedName("property_business")
    @Expose
    private String propertyBusiness;
    @SerializedName("property_construction")
    @Expose
    private String propertyConstruction;
    @SerializedName("property_construction_value")
    @Expose
    private BigDecimal propertyConstructionValue;
    @SerializedName("property_content")
    @Expose
    private String propertyContent;
    @SerializedName("property_content_value")
    @Expose
    private BigDecimal propertyContentValue;

    // Marine details
    @SerializedName("vessel_build_year")
    @Expose
    private BigDecimal vesselBuildYear;
    @SerializedName("vessel_carriage_capacity")
    @Expose
    private String vesselCarriageCapacity;
    @SerializedName("vessel_insured_value")
    @Expose
    private BigDecimal vesselInsuredValue;
    @SerializedName("vessel_model")
    @Expose
    private String vesselModel;
    @SerializedName("vessel_name")
    @Expose
    private String vesselName;
    @SerializedName("vessel_note")
    @Expose
    private String vesselNote;
    @SerializedName("vessel_purchase_value")
    @Expose
    private BigDecimal vesselPurchaseValue;
    @SerializedName("vessel_purchase_year")
    @Expose
    private BigDecimal vesselPurchaseYear;
    @SerializedName("vessel_reg_no")
    @Expose
    private String vesselRegNo;
    @SerializedName("vessel_type")
    @Expose
    private String vesselType;
    @SerializedName("vessel_usage")
    @Expose
    private String vesselUsage;

    // Casualty/accident details
    @SerializedName("accident_beneficiary_info")
    @Expose
    private String accidentBeneficiaryInfo;
    @SerializedName("accident_cover_type")
    @Expose
    private String accidentCoverType;
    @SerializedName("accident_insured_benefit")
    @Expose
    private String accidentInsuredBenefit;
    @SerializedName("accident_insured_persons")
    @Expose
    private String accidentInsuredPersons;
    @SerializedName("premises_business_hour")
    @Expose
    private String premisesBusinessHour;
    @SerializedName("premises_business_type")
    @Expose
    private String premisesBusinessType;
    @SerializedName("premises_location")
    @Expose
    private String premisesLocation;
    @SerializedName("premises_name")
    @Expose
    private String premisesName;
    @SerializedName("premises_occupation")
    @Expose
    private String premisesOccupation;
}
