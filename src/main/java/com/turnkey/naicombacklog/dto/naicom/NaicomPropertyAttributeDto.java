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
public class NaicomPropertyAttributeDto {
    private String property_business;
    private String property_construction;
    private String property_construction_value;
    private String property_content;
    private String property_content_value;
}
