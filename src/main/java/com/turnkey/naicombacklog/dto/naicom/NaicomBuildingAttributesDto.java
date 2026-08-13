package com.turnkey.naicombacklog.dto.naicom;

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
public class NaicomBuildingAttributesDto {
    private String building_door_number;
    private String building_name;
    private String building_address_line;
    private String building_city;
    private String building_state;
    private String building_post_code;
}
