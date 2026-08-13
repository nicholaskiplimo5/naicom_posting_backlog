package com.turnkey.naicombacklog.controller;

import com.turnkey.naicombacklog.dao.PolicyTransactionDao;
import com.turnkey.naicombacklog.service.BacklogResult;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone backlog endpoint: given a batch number and transaction type, runs the full
 * select -&gt; stage -&gt; post pipeline in one call, instead of requiring the two separate
 * tps-apis calls ({@code api/stagePayload} then {@code apis/naicom}) to be made by hand.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/postbacklog")
@Tag(name = "Backlog Poster", description = "Selects, stages and posts backlog NAICOM policy transactions")
public class BacklogController {

    private final BacklogService backlogService;

    private final PolicyTransactionDao transactionDao;

    @Operation(
            summary = "Post backlog policy transactions to NAICOM",
            description = "Selects all policies of the given transaction type not yet posted to NAICOM, "
                    + "then runs the full select -> stage -> post pipeline for each one."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Backlog processed; check each result's success/failedStage for per-policy outcome",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = BacklogResult.class)))
    )
    @PostMapping
    public ResponseEntity<List<BacklogResult>> postBacklog(
            @Parameter(description = "Transaction type to select backlog policies for", required = true)
            @RequestParam("transactionType") String transactionType) {

        List<BigDecimal> batchNumbers = transactionDao.findPoliciesNotPostedToNAICOM(transactionType);
        log.info("Received /postbacklog request: transactionType={}, batchCount={}", transactionType, batchNumbers.size());

        List<BacklogResult> results = new ArrayList<>();
        for (BigDecimal policyBatchNumber : batchNumbers) {
            log.info("Processing backlog: policyBatchNumber={}, transactionType={}", policyBatchNumber, transactionType);

            BacklogResult result = backlogService.processBacklog(policyBatchNumber, transactionType);
            results.add(result);

            log.info("Completed backlog: policyBatchNumber={}, transactionType={}, success={}, stage={}",
                    policyBatchNumber, transactionType, result.isSuccess(), result.getFailedStage());
        }

        return ResponseEntity.ok(results);
    }
}
