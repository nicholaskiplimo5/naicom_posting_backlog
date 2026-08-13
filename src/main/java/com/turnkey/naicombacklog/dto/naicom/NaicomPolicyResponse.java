package com.turnkey.naicombacklog.dto.naicom;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.turnkey.naicombacklog.dto.GroupDto;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Deserialized response body coming back from the NAICOM endpoint.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaicomPolicyResponse {
    private boolean IsSucceed;
    private String PolicyID;
    private String PolicyUniqueID;

    @JsonIgnore
    private List<GroupDto> DataGroup;

    private List<Integer> ErrCodes;
    private List<BigDecimal> WarnCodes;
    private List<String> WarnMsgs;
    private String ErrMsgs;
}
