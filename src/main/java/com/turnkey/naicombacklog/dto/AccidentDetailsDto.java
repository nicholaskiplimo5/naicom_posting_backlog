package com.turnkey.naicombacklog.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccidentDetailsDto {
    private String accident_cover_type;
    private String accident_beneficiary_info;
    private String accident_insured_benefit;
    private String accident_insured_persons;
}
