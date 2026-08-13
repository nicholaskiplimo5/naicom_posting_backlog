package com.turnkey.naicombacklog.dto.naicom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.turnkey.naicombacklog.dto.CoinSuranceAttribute;
import com.turnkey.naicombacklog.dto.GroupDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * The exact outbound NAICOM wire format.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaicomPostingDto {

    @JsonProperty("PolicyUniqueID")
    private String PolicyUniqueID;

    @JsonProperty("SID")
    private String SID;

    @JsonProperty("Token")
    private String Token;

    @JsonProperty("Type")
    private String Type;

    @JsonProperty("DataGroup")
    private List<GroupDto> DataGroup;

    @JsonProperty("LossAdjusterID")
    private String LossAdjusterID;

    @JsonProperty("ClaimUniqueID")
    private String ClaimUniqueID;

    @JsonProperty("New_Start")
    private String New_Start;

    @JsonProperty("New_Expiration")
    private String New_Expiration;

    @JsonProperty("New_Premium")
    private String New_Premium;

    @JsonProperty("Note")
    private String Note;

    @JsonProperty("Date_Cancel")
    private String Date_Cancel;

    @JsonProperty("Refund")
    private String Refund;

    @JsonProperty("PremiumPercentage")
    private String PremiumPercentage;

    @JsonProperty("ReInsuranceDetails")
    private List<CoinSuranceAttribute> ReInsuranceDetails;
}
