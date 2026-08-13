package com.turnkey.naicombacklog.dto.regulatorPayload;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class Coinsurance {
    @SerializedName("coinsurer_id")
    @Expose
    private Integer coinsuranceId;
    @SerializedName("premium_percentage")
    @Expose
    private BigDecimal premiumPercentage;
}
