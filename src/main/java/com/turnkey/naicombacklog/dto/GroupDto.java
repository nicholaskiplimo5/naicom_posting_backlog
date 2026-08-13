package com.turnkey.naicombacklog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class GroupDto {

    @JsonProperty("GroupName")
    private String GroupName;

    @JsonProperty("GroupTag")
    private Integer GroupTag;

    @JsonProperty("GroupCount")
    private Integer GroupCount;

    @JsonProperty("AttArray")
    private List<AttributeDto> AttArray;
}
