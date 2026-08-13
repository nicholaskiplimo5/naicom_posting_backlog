package com.turnkey.naicombacklog.dto.naicom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.turnkey.naicombacklog.dto.ErrorDto;
import lombok.*;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"status", "message", "turnquest_request_id", "success", "PolicyUniqueID", "inputs", "errors", "naicom_response"})
public class NaicomPolicyResponseDto {

    private NaicomPolicyDto inputs;
    private String message;
    private HttpStatus status;
    private Long turnquest_request_id;
    private ArrayList<ErrorDto> errors;
    private Boolean success;
    private Object naicom_response;

    @JsonProperty("naicom_policy_unique_id")
    private String PolicyUniqueID;

    @Override
    public String toString() {
        return "NaicomPolicyResponseDto{" +
                "errors=" + errors +
                ", message='" + message + '\'' +
                ", status=" + status +
                ", turnquest_request_id=" + turnquest_request_id +
                ", success=" + success +
                ", PolicyUniqueID='" + PolicyUniqueID + '\'' +
                '}';
    }
}
