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
public class PremisesDetailsDto {
    private String premises_name;
    private String premises_location;
    private String premises_occupation;
    private String premises_business_type;
    private String premises_business_hour;
}
