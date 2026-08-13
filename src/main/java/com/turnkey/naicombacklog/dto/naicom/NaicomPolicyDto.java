package com.turnkey.naicombacklog.dto.naicom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.turnkey.naicombacklog.dto.AccidentDetailsDto;
import com.turnkey.naicombacklog.dto.ClientRequestDto;
import com.turnkey.naicombacklog.dto.CoinsuranceDto;
import com.turnkey.naicombacklog.dto.PremisesDetailsDto;
import com.turnkey.naicombacklog.enums.CoverType;
import com.turnkey.naicombacklog.enums.NaicomProduct;
import com.turnkey.naicombacklog.enums.NaicomYesNo;
import lombok.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;

/**
 * The staged-payload shape deserialized at posting time. Spans all 10 NAICOM product
 * lines (only AUTO fields are exercised by {@code NaicomPostingService} in this build).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaicomPolicyDto {

    private NaicomProduct naicom_product;
    private BigDecimal naicom_product_code;
    private String policy_unique_id;
    private ClientRequestDto customer;

    private Date cover_start_date;
    private Date cover_end_date;

    private BigDecimal sum_insured;
    private BigDecimal total_premium;
    private CoverType cover_type;
    private String policy_number;
    private String policy_description;
    private BigDecimal commission_fee;
    private String extra_fee;
    private String premium_note;
    private String terms;
    private String preamble;
    private String endorsements;
    private String exclusions;
    private String conditions;
    private List<NaicomInsuredInfoDto> insured_info;

    private String contract_description;
    private String contract_location;
    private String sub_contract_info;
    private BigDecimal contract_price;
    private BigDecimal bond_percentage;
    private String importer_number;
    private String merchandise_description;
    private String merchandise_value;
    private BigDecimal tax_duty;
    private String origin_country;
    private String entry_port;
    private String court_name;
    private String court_case_no;
    private BigDecimal bond_paid;
    private BigDecimal bond_amount;
    private String bond_description;

    private NaicomBuildingAttributesDto building_details;
    private NaicomPropertyAttributeDto property_details;
    private NaicomFireDetailsDto fire_details;
    private NaicomBurglaryDetails burglary_details;
    private NaicomHouseDetails house_details;

    private String exceptions;
    private String marine_cover_type;
    private PremisesDetailsDto premise_details;
    private String transit_schedule;
    private String transit_route;
    private String safe_strong_room_details;
    private String safe_guard_info;
    private String property_info;
    private String good_transit_method;
    private String transit_vehicle_info;
    private String work_description;
    private String work_location;
    private String work_employee_details;
    private String company_info;
    private String company_director_info;
    private String recorder_type;
    private String recorder_id;
    private String recorder;
    private String professional_activity_info;
    private String professional_employee_info;
    private AccidentDetailsDto accident_info;
    private String account_info;
    private String employee_info;
    private String public_risk_info;
    private String work_condition;
    private String estimated_wages;
    private String estimated_earnings;
    private String other_conditions;
    private String risk_management_detail;
    private String contractor_name;
    private String contractor_address_line;
    private String contractor_city;
    private String contractor_state;
    private String post_code;
    private String consulting_engineer_info;
    private String contract_site;
    private String deterioration_stock_info;
    private String deterioration_stock_goods_description;
    private String deterioration_stock_goods_value;
    private String deterioration_cold_room_description;
    private String deterioration_stock_machinery_detail;
    private String deterioration_stock_machinery_value;
    private String risk_project_name;
    private String risk_project_detail;
    private String risk_plant_type;
    private String risk_plant_description;
    private String item_manufacture;
    private String risk_main_item_manufacture;

    @JsonProperty("all_plant_equipment_covered")
    private NaicomYesNo plant_equipment_covered;

    private String machinery_description;
    private String machinery_year_of_manufacture;
    private String machinery_model;

    @JsonProperty("machine_serial_number")
    private String serial_number;

    private NaicomYesNo is_machinery_leased;
    private String machinery_conditions;
    private String loss_profit_production_process;
    private String loss_profit_facility_info;
    private String loss_profit_supply_info;
    private String loss_profit_stock_info;
    private String loss_profit_machinery_damage_cost;
    private String tank_description;
    private String tank_location;
    private String tank_substance;
    private String tank_upgrade_history;
    private String public_liablity_situation;
    private String tank_repair_upgrade_history;
    private String public_liablity_premises_situation;
    private String public_liablity_premises_occupation;
    private String insured_value;
    private String estimated_maximum_loss;
    private String deductible_value;
    private String special_risks;
    private String project_site;
    private String project_name;
    private String project_detail;
    private String plant_type;
    private String plant_description;
    private String insurance_description;
    private String note;
    private Date cancel_date;
    private BigDecimal refund;
    private BigDecimal co_insurance_rate;
    private List<CoinsuranceDto> coinsurance_details;
    private String trans_type;
    private String coinsurance_leader;
    private String coinsurance_policy;
    private BigInteger policy_batch_no;
}
