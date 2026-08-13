package com.turnkey.naicombacklog.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ClientType {

    I("I", "Individual"),
    C("C", "Corporate"),

    // NAICOM
    PERSON("PERSON", "PERSON"),
    ORG("ORG", "ORG"),
    INDIVIDUAL("INDIVIDUAL", "INDIVIDUAL"),
    CORPORATION("CORPORATION", "CORPORATION");

    private final String code;
    private final String name;
}
