package com.turnkey.naicombacklog.dto.regulatorPayload;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class NiidAgent {
    @SerializedName("license_number")
    @Expose
    private String licenseNumber;

    @SerializedName("registration_number")
    @Expose
    private String registrationNumber;
}
