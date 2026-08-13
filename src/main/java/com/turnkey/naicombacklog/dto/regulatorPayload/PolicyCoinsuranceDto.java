package com.turnkey.naicombacklog.dto.regulatorPayload;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PolicyCoinsuranceDto {
    private String policyCoinLeader;
    private BigDecimal policyCoinShare;
}
