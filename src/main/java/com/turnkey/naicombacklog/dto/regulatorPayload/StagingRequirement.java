package com.turnkey.naicombacklog.dto.regulatorPayload;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StagingRequirement {
    private BigDecimal prpCode;
    private BigDecimal agnCode;
    private BigDecimal code;
}
