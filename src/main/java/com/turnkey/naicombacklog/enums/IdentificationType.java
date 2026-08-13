package com.turnkey.naicombacklog.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum IdentificationType {

    DRIVERS_LICENSE("01", "Driver's License"),
    VOTERS_CARD("02", "Voter Id"),
    PASSPORT("03", "Passport"),
    NATIONAL_ID("04", "National ID"),
    NHIF_CARD("05", "NHISCard"),
    OTHER("06", "Other"),

    // NAICOM
    NATIONAL_ID_CARD("N.A", "National ID");

    private final String code;
    private final String name;

    public static IdentificationType fromCode(String code) {
        for (IdentificationType identificationType : IdentificationType.values()) {
            if (identificationType.getCode().equalsIgnoreCase(code)) {
                return identificationType;
            }
        }
        return IdentificationType.NATIONAL_ID;
    }
}
