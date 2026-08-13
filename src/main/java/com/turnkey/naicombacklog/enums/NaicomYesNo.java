package com.turnkey.naicombacklog.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum NaicomYesNo {
    Y(true),
    N(false);

    private final boolean status;
}
