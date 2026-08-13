package com.turnkey.naicombacklog.dto.naicom;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaicomInsuredInfoDto {

    private String vehicle_id;
    private String plate_no;
    private String registration_number;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private Date registration_date;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private Date registration_expiry_date;

    private BigDecimal vehicle_mileage;
    private String vehicle_type;
    private String vehicle_make;
    private String vehicle_model;
    private String vehicle_color;
    private String year_of_manufacture;
    private String engine_capacity;
    private Integer seats;
    private String auto_note;

    private String vessel_name;
    private String vessel_reg_no;
    private String vessel_type;
    private BigDecimal vessel_insured_value;
    private String vessel_build_year;
    private String vessel_model;
    private String vessel_purchase_year;
    private BigDecimal vessel_purchase_value;
    private String vessel_usage;
    private Integer vessel_carriage_capacity;
    private String vessel_note;
}
