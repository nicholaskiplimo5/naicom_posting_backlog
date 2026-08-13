package com.turnkey.naicombacklog.dto.regulatorPayload;

import lombok.Data;
import org.json.JSONArray;

@Data
public class PolicyTransactionResponseDto extends PolicyTransactionDto {
    private Long id;
    private String message;
    private JSONArray errors;
    private int status;
    private String requestId;
    private String stickerNo;
    private String serverNo;
    private Boolean requestCalled = false;
    private String completeResponse;
    private String transPosted;
    private String regulatorPayload;
}
