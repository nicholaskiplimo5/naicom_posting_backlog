package com.turnkey.naicombacklog.service;

import com.turnkey.naicombacklog.model.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.InterruptedIOException;

/**
 * Ported from tps-apis' {@code ThirdPartyAPICallsService.postTransaction} - the raw OkHttp
 * POST path that the real NAICOM integration actually uses in production (its
 * resilience4j-wrapped RestTemplate variant is unused there).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ThirdPartyApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;

    public ResponseEntity<Object> postTransaction(Parameter policyPostingParameter, String jsonPayload) {
        RequestBody requestBody = RequestBody.create(jsonPayload, JSON);
        Request request = new Request.Builder()
                .url(policyPostingParameter.getParamValue())
                .post(requestBody)
                .addHeader("accept", "application/json")
                .addHeader("content-type", "application/json")
                .build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                // Prefer NAICOM's own body over the bare reason phrase: a 4xx still carries
                // ErrMsgs/ErrCodes, and throwing those away left the caller with nothing but
                // "Bad Request" to report.
                return ResponseEntity.status(response.code())
                        .body(responseBody.isBlank() ? response.message() : responseBody);
            }
            return ResponseEntity.ok(responseBody);
        } catch (Exception e) {
            // A transport failure used to come back as 200 OK carrying the exception message, so
            // every caller went straight on to parse it as JSON and blew up with
            // "A JSONObject text must begin with '{'", hiding the real cause (a 60s read timeout).
            // Report it as the gateway failure it is and let the caller treat it as a failure.
            boolean timedOut = e instanceof InterruptedIOException;
            HttpStatus status = timedOut ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            if (timedOut) {
                // A read timeout says all it has to say in one line, and the caller reports it
                // properly now. A concurrent backlog run against a slow NAICOM produces one of
                // these per policy, so the stack trace is pure noise at that volume.
                log.warn("Timed out posting to {} after {}: {}", policyPostingParameter.getParamName(),
                        status, e.getMessage());
            } else {
                log.error("Error posting to {} ({}): {}", policyPostingParameter.getParamName(), status, e.getMessage(), e);
            }
            return ResponseEntity.status(status)
                    .body(e.getMessage() + " Encountered while posting Naicom Policy");
        }
    }
}
