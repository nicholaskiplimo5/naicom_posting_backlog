package com.turnkey.naicombacklog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CoinSuranceAttribute {

    @JsonProperty("ReinsurerID")
    private String ReinsurerID;

    @JsonProperty("PremiumPercentage")
    private String PremiumPercentage;
}
