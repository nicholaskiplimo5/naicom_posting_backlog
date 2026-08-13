package com.turnkey.naicombacklog.service;

import com.turnkey.naicombacklog.dto.naicom.NaicomPolicyResponseDto;
import com.turnkey.naicombacklog.enums.BacklogStage;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.HttpStatus;

@Data
@Builder
public class BacklogResult {
    private boolean success;
    private String message;
    private HttpStatus status;
    private BacklogStage failedStage;
    private NaicomPolicyResponseDto postingResponse;
}
