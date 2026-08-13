package com.turnkey.naicombacklog.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.turnkey.naicombacklog.enums.BondBeneficiaryType;
import com.turnkey.naicombacklog.enums.ClientType;
import com.turnkey.naicombacklog.enums.Gender;
import com.turnkey.naicombacklog.enums.IdentificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientRequestDto {

    private String code;
    private String number;
    private String name;
    private String first_name;
    private String last_name;
    private String other_names;
    private String email_address;
    private String phone_number;
    private String postal_address;
    private String residential_address;
    private String tax_indentification_number;
    private String occupation;
    private String identification_number;
    private String reference;
    private String branch_code;
    private String customer_type_code;
    private IdentificationType identification_type;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private Date date_of_birth;

    private String state;
    private ClientType type;
    private String license;
    private String organization_id;
    private String organization_name;
    private String city;
    private BondBeneficiaryType beneficiary_type;
    private String address_code;
    private String title;
    private Gender gender;
}
