package com.turnkey.naicombacklog.service;

import com.turnkey.naicombacklog.model.IncomingRequest;
import com.turnkey.naicombacklog.repository.IncomingRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IncomingRequestService {

    private static final int MAX_BODY_LENGTH = 3000;

    private final IncomingRequestRepository incomingRequestRepository;

    public IncomingRequest save(IncomingRequest incomingRequest) {
        return incomingRequestRepository.save(incomingRequest);
    }

    public String truncate(String body) {
        if (body == null) {
            return null;
        }
        return body.length() > MAX_BODY_LENGTH ? body.substring(0, MAX_BODY_LENGTH) : body;
    }
}
