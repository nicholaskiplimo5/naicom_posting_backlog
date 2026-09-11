package com.turnkey.naicombacklog.controller;

import com.turnkey.naicombacklog.service.BacklogPostingResult;
import com.turnkey.naicombacklog.service.BacklogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Standalone backlog endpoint: given a transaction type, runs the full select -&gt; stage -&gt; post
 * pipeline, instead of requiring the two separate tps-apis calls ({@code api/stagePayload} then
 * {@code apis/naicom}) to be made by hand.
 *
 * <p>A run is paced by the NAICOM HTTP call, so a large backlog is meant to be worked through in
 * chunks: {@code limit} bounds how many policies one request handles (and therefore how large the
 * response is), {@code offset} moves the window, and {@code concurrency} decides how many of that
 * chunk are in flight at once. Posted policies drop out of the selection, so repeatedly calling
 * with the default {@code offset=0} walks the backlog down.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/postbacklog")
@Tag(name = "Backlog Poster", description = "Selects, stages and posts backlog NAICOM policy transactions")
public class BacklogController {

    private final BacklogService backlogService;

    @Value("${naicom.backlog.default-limit:1000}")
    private int defaultLimit;

    @Value("${naicom.backlog.default-concurrency:8}")
    private int defaultConcurrency;

    @Value("${naicom.backlog.max-concurrency:32}")
    private int maxConcurrency;

    @Operation(
            summary = "Post backlog policy transactions to NAICOM",
            description = "Selects policies of the given transaction type not yet posted to NAICOM, "
                    + "then runs the full select -> stage -> post pipeline for each one. Processes at most "
                    + "'limit' policies per call so a large backlog can be worked through in chunks."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Backlog chunk processed; one entry per transaction giving its batch number, "
                    + "whether it posted, and the reason if it did not",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = BacklogPostingResult.class)))
    )
    @PostMapping
    public ResponseEntity<List<BacklogPostingResult>> postBacklog(
            @Parameter(description = "Transaction type to select backlog policies for", required = true)
            @RequestParam("transactionType") String transactionType,

            @Parameter(description = "How many selected policies to skip before processing this chunk")
            @RequestParam(value = "offset", defaultValue = "0") int offset,

            @Parameter(description = "Maximum policies to process in this call. 0 means every one still outstanding "
                    + "- avoid that on a large backlog, since the response holds one entry per policy.")
            @RequestParam(value = "limit", required = false) Integer limit,

            @Parameter(description = "How many policies to post at once. 1 posts them one after another.")
            @RequestParam(value = "concurrency", required = false) Integer concurrency) {

        int effectiveLimit = limit != null ? limit : defaultLimit;
        int effectiveConcurrency = Math.min(concurrency != null ? concurrency : defaultConcurrency, maxConcurrency);

        log.info("Received /postbacklog request: transactionType={}, offset={}, limit={}, concurrency={}",
                transactionType, offset, effectiveLimit, effectiveConcurrency);

        List<BacklogPostingResult> results = backlogService.runBacklog(transactionType, offset, effectiveLimit, effectiveConcurrency);
        return ResponseEntity.ok(results);
    }
}
