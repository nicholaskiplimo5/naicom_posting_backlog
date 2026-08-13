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
            if (!response.isSuccessful()) {
                return ResponseEntity.status(response.code()).body(response.message());
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            return ResponseEntity.ok(responseBody);
        } catch (Exception e) {
            log.error("Error posting to {}: {}", policyPostingParameter.getParamName(), e.getMessage(), e);
            return ResponseEntity.ok(e.getMessage() + " Encountered while posting Naicom Policy");
        }
    }
}
