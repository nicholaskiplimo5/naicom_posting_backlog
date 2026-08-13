package com.turnkey.naicombacklog.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum BondBeneficiaryType {
    INDIVIDUAL("INDIVIDUAL", "INDIVIDUAL");

    private final String code;
    private final String name;
}
