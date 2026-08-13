package com.turnkey.naicombacklog.dto.regulatorPayload;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

@Data
public class NiidCustomer {
    @SerializedName("code")
    @Expose
    private String code;
    @SerializedName("customer_type_code")
    @Expose
    private String customerTypeCode;
    @SerializedName("date_of_birth")
    @Expose
    private String dateOfBirth;
    @SerializedName("email_address")
    @Expose
    private String emailAddress;
    @SerializedName("first_name")
    @Expose
    private String firstName;
    @SerializedName("identification_number")
    @Expose
    private String identificationNumber;
    @SerializedName("identification_type")
    @Expose
    private String identificationType;
    @SerializedName("last_name")
    @Expose
    private String lastName;
    @SerializedName("name")
    @Expose
    private String name;
    @SerializedName("occupation")
    @Expose
    private Object occupation;
    @SerializedName("other_names")
    @Expose
    private Object otherNames;
    @SerializedName("phone_number")
    @Expose
    private String phoneNumber;
    @SerializedName("postal_address")
    @Expose
    private String postalAddress;
    @SerializedName("reference")
    @Expose
    private String reference;
    @SerializedName("residential_address")
    @Expose
    private String residentialAddress;
    @SerializedName("tax_indentification_number")
    @Expose
    private Object taxIndentificationNumber;
    @SerializedName("city")
    @Expose
    private String city;
    @SerializedName("license")
    @Expose
    private String license;
    @SerializedName("organization_id")
    @Expose
    private String organizationId;
    @SerializedName("organization_name")
    @Expose
    private String organizationName;
    @SerializedName("state")
    @Expose
    private String state;
    @SerializedName("type")
    @Expose
    private String type;
    @SerializedName("beneficiary_type")
    @Expose
    private String beneficiaryType;
    @SerializedName("address_code")
    @Expose
    private String addressCode;
    @SerializedName("gender")
    @Expose
    private String gender;
    @SerializedName("title")
    @Expose
    private String title;
}
