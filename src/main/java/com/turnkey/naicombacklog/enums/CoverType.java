package com.turnkey.naicombacklog.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@AllArgsConstructor
@Slf4j
public enum CoverType {
    // NIID
    COMP(100, "C", "Comprehensive"),
    TPO(200, "T", "Third Party Only"),
    TPTF(300, "P", "Third-party, Theft and Fire"),
    STD(100, "T", "STD"),
    ALL_RISKS(100, "AR", "ALL_RISKS"),
    CAPITAL(400, "T", "CAPITAL"),
    BCBURG(500, "T", "BCBURG"),
    BCFIRE(600, "T", "BCFIRE"),
    EARN(700, "T", "EARN"),
    ICC_A(1, "ICC'A'", ""),
    ICC_B(2, "ICC'B'", ""),
    ICC_C(3, "ICC'C'", ""),
    IFFC_A(4, "IFFC'A'", ""),
    IFFC_C(5, "IFFC'C'", ""),
    IFMC_A(6, "IFMC 'A'", ""),
    IFMC_C(7, "IFMC 'C'", ""),
    IBOC(8, "IBOC", ""),
    ICC_AIR(9, "ICC(AIR)", ""),

    // NAICOM AUTO
    THIRD_PARTY(0, "ThirdParty", "ThirdParty"),
    COMPREHENSIVE(1, "Comprehensive", "Comprehensive");

    private final int code;
    private final String type;
    private final String name;

    @JsonCreator
    public static CoverType forValue(String value) {
        return fromTypeOrDefault(value);
    }

    @JsonValue
    public String toValue() {
        return this.type;
    }

    public static CoverType fromTypeOrDefault(String type) {
        for (CoverType coverType : CoverType.values()) {
            if (coverType.type.equals(type)) {
                return coverType;
            }
        }
        return CoverType.THIRD_PARTY;
    }
}
