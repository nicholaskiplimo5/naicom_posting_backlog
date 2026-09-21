package com.turnkey.naicombacklog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.turnkey.naicombacklog.dao.PolicyTransactionDao;
import com.turnkey.naicombacklog.dto.AttributeDto;
import com.turnkey.naicombacklog.dto.CoinSuranceAttribute;
import com.turnkey.naicombacklog.dto.CoinsuranceDto;
import com.turnkey.naicombacklog.dto.ErrorDto;
import com.turnkey.naicombacklog.dto.GroupDto;
import com.turnkey.naicombacklog.dto.naicom.NaicomInsuredInfoDto;
import com.turnkey.naicombacklog.dto.naicom.NaicomPolicyDto;
import com.turnkey.naicombacklog.dto.naicom.NaicomPolicyResponse;
import com.turnkey.naicombacklog.dto.naicom.NaicomPolicyResponseDto;
import com.turnkey.naicombacklog.dto.naicom.NaicomPostingDto;
import com.turnkey.naicombacklog.enums.BondBeneficiaryType;
import com.turnkey.naicombacklog.enums.ClientType;
import com.turnkey.naicombacklog.enums.IncomingRequestType;
import com.turnkey.naicombacklog.enums.NaicomProduct;
import com.turnkey.naicombacklog.enums.TargetRegulatorEnum;
import com.turnkey.naicombacklog.model.GinPolicyTransactionEntity;
import com.turnkey.naicombacklog.model.IncomingRequest;
import com.turnkey.naicombacklog.model.Parameter;
import com.turnkey.naicombacklog.repository.GinPolicyTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Posting to NAICOM, ported from tps-apis' {@code NaicomIntegrationService}. Only the AUTO
 * (motor) product line is fully implemented in this build (see the "Phase 1: AUTO only"
 * scoping decision) - {@link #postSingleTransaction} returns a clear "not implemented" error
 * for the other 9 product lines rather than guessing at their field mappings. Renewal,
 * termination and deletion are product-agnostic in the original service (they operate purely
 * on the policy_unique_id) and are fully ported here regardless of product.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NaicomPostingService {

    private static final String CONSTANT_NAICOM_ORG_RECORDER_TYPE = "NAICOM_ORG_RECORDER_TYPE";
    private static final String CONSTANT_NAICOM_ORG_RECORDER_ID = "NAICOM_ORG_RECORDER_ID";
    private static final String CONSTANT_NAICOM_POLICY_POSTING_URL = "NAICOM_POLICY_POSTING_URL";
    private static final String CONSTANT_NAICOM_CLIENT_ID = "NAICOM_CLIENT_ID";
    private static final String CONSTANT_NAICOM_CLIENT_PASSWORD = "NAICOM_CLIENT_PASSWORD";
    private static final String CONSTANT_NAICOM_POLICY_ENDORSEMENT_URL = "NAICOM_POLICY_ENDORSEMENT_URL";
    private static final String CONSTANT_NAICOM_POLICY_RENEWAL_URL = "NAICOM_POLICY_RENEWAL_URL";
    private static final String CONSTANT_NAICOM_POLICY_TERMINATION_URL = "NAICOM_POLICY_TERMINATION_URL";
    private static final String CONSTANT_NAICOM_POLICY_DELETION_URL = "NAICOM_POLICY_DELETION_URL";
    private static final String CONSTANT_NAICOM_POLICY_COINSURANCE_URL = "NAICOM_POLICY_COINSURANCE_URL";

    private static final String DEFAULT_STRING = "n/a";
    private static final String DEFAULT_YEAR_OF_MANUFACTURE = "2015";
    /** 1970-01-01. Handed out as a fresh instance per use: java.util.Date is mutable, and a
     *  shared one would now be reachable from several concurrently-posted policies at once. */
    private static final long DEFAULT_DATE_MILLIS = new Date(70, 0, 1).getTime();

    private final ParameterService parameterService;
    private final IncomingRequestService incomingRequestService;
    private final ThirdPartyApiClient thirdPartyApiClient;
    private final PolicyTransactionDao policyTransactionDao;
    private final GinPolicyTransactionRepository policyTransactionRepository;
    private final ObjectMapper objectMapper;

    /**
     * Fetches and deserializes the currently-staged NAICOM payload for a batch, or null if
     * nothing has been staged yet. Mirrors the original controller's {@code getNaicomPolicyTransaction}
     * lookup, used to decide how to dispatch a transaction type (post/endorse/renew/terminate/delete).
     */
    public NaicomPolicyDto getStagedPolicy(BigInteger policyBatchNumber) {
        List<GinPolicyTransactionEntity> staged = policyTransactionRepository
                .findAllByGtpPolBatchNoAndGtpTargetRegulator(new BigDecimal(policyBatchNumber), TargetRegulatorEnum.NAICOM.name());
        for (GinPolicyTransactionEntity entity : staged) {
            String payload = entity.getGtpPayload();
            if (payload != null && !payload.isEmpty()) {
                try {
                    NaicomPolicyDto dto = objectMapper.readValue(payload, NaicomPolicyDto.class);
                    dto.setPolicy_batch_no(policyBatchNumber);
                    return dto;
                } catch (Exception e) {
                    log.error("Failed to deserialize staged payload for batch {}: {}", policyBatchNumber, e.getMessage(), e);
                    throw new RuntimeException("Failed to deserialize staged payload for batch " + policyBatchNumber, e);
                }
            }
        }
        return null;
    }

    /**
     * Re-fetches the staged payload for the batch (same lookup the original controller used)
     * and posts it. {@code request} is only used to shape the "nothing staged" error response.
     */
    public NaicomPolicyResponseDto postPolicyToNaicom(BigInteger policyBatchNumber, NaicomPolicyDto request) {
        List<GinPolicyTransactionEntity> staged = policyTransactionRepository
                .findAllByGtpPolBatchNoAndGtpTargetRegulator(new BigDecimal(policyBatchNumber), TargetRegulatorEnum.NAICOM.name());

        String payload = staged.stream()
                .map(GinPolicyTransactionEntity::getGtpPayload)
                .filter(p -> p != null && !p.isEmpty())
                .findFirst()
                .orElse(null);

        if (payload == null) {
            return NaicomPolicyResponseDto.builder()
                    .message("Failed to post the policy: no staged payload found for batch " + policyBatchNumber)
                    .status(HttpStatus.BAD_REQUEST)
                    .success(false)
                    .inputs(request)
                    .build();
        }
        try {
            NaicomPolicyDto requestToBePosted = objectMapper.readValue(payload, NaicomPolicyDto.class);
            return postSingleTransaction(requestToBePosted);
        } catch (Exception e) {
            log.error("Failed to post batch {}: {}", policyBatchNumber, e.getMessage(), e);
            return NaicomPolicyResponseDto.builder()
                    .message("Failed to post the policy. " + e.getMessage())
                    .status(HttpStatus.BAD_REQUEST)
                    .success(false)
                    .inputs(request)
                    .build();
        }
    }

    public NaicomPolicyResponseDto postSingleTransaction(NaicomPolicyDto request) {
        if (request.getPremium_note() == null || request.getPremium_note().isEmpty()) {
            request.setPremium_note("n/a");
        }
        if (request.getNaicom_product() == NaicomProduct.AUTO
                && (request.getCustomer().getLicense() == null || request.getCustomer().getLicense().isEmpty())) {
            request.getCustomer().setLicense("n/a");
        }
        if (request.getNaicom_product() == NaicomProduct.AUTO) {
            return postAutoPolicyToNaicom(request);
        }
        return switch (request.getNaicom_product()) {
            case BOND -> postProductPolicy(request, "Bond", validateBondPolicyFields(request), this::getBondGroupDtos);
            case FIRE -> postProductPolicy(request, "Fire", validateFirePolicyFields(request), this::getFireGroupDtos);
            case MARINE -> postProductPolicy(request, "Marine", validateMarineDetails(request), this::getMarineGroupDtos);
            case CASUALTY -> postProductPolicy(request, "Casualty", validateCasualtyPolicyDetails(request), this::getCasualtyGroupDtos);
            case ENGINEERING, ENGINEER -> postProductPolicy(request, "Engineer", validateEngineerPolicy(request), this::getEngineerGroupDtos);
            case OIL -> postProductPolicy(request, "Oil", validateOilPolicy(request), this::getOilGroupDtos);
            case MISCELLANEOUS -> postProductPolicy(request, "Misc", validateMiscellaneousPolicy(request), this::getMiscellaneousGroupDtos);
            case ACCIDENT -> postProductPolicy(request, "Casualty", new ArrayList<>(), this::getCasualtyGroupDtos);
            case AVIATION -> postProductPolicy(request, "Marine", new ArrayList<>(), this::getMarineGroupDtos);
            default -> NaicomPolicyResponseDto.builder()
                    .message("Posting for NAICOM product '" + request.getNaicom_product() + "' is not implemented in this standalone app yet.")
                    .status(HttpStatus.NOT_IMPLEMENTED)
                    .success(false)
                    .inputs(request)
                    .build();
        };
    }

    private NaicomPolicyResponseDto postAutoPolicyToNaicom(NaicomPolicyDto motorPolicyRequestDto) {
        NaicomPolicyResponseDto response = NaicomPolicyResponseDto.builder()
                .message("Failed to post the policy.")
                .status(HttpStatus.BAD_REQUEST)
                .success(false)
                .inputs(motorPolicyRequestDto)
                .build();

        ArrayList<ErrorDto> foundErrors = validatePolicyFields(motorPolicyRequestDto);
        if (!foundErrors.isEmpty()) {
            response.setErrors(foundErrors);
            return response;
        }

        Parameter policyPostingParameter = parameterService.findByName(CONSTANT_NAICOM_POLICY_POSTING_URL);
        if (policyPostingParameter == null) {
            return missingParameterResponse(motorPolicyRequestDto, CONSTANT_NAICOM_POLICY_POSTING_URL);
        }
        if (motorPolicyRequestDto.getRecorder_id() == null) {
            Parameter orgRecorderID = parameterService.findByName(CONSTANT_NAICOM_ORG_RECORDER_ID);
            if (orgRecorderID == null) {
                return missingParameterResponse(motorPolicyRequestDto, CONSTANT_NAICOM_ORG_RECORDER_ID);
            }
            motorPolicyRequestDto.setRecorder_id(orgRecorderID.getParamValue());
        }
        Parameter orgRecorderType = parameterService.findByName(CONSTANT_NAICOM_ORG_RECORDER_TYPE);
        if (orgRecorderType == null) {
            return missingParameterResponse(motorPolicyRequestDto, CONSTANT_NAICOM_ORG_RECORDER_TYPE);
        }
        motorPolicyRequestDto.setRecorder_type(orgRecorderType.getParamValue());

        Parameter clientID = parameterService.findByName(CONSTANT_NAICOM_CLIENT_ID);
        Parameter clientPassword = parameterService.findByName(CONSTANT_NAICOM_CLIENT_PASSWORD);
        if (clientID == null || clientID.getParamValue() == null || clientPassword == null || clientPassword.getParamValue() == null) {
            response.setErrors(singleError("Confirm the NAICOM integration parameters are not set.", "TEX-404"));
            return response;
        }

        IncomingRequest incomingRequest = IncomingRequest.builder()
                .incomingRequestType(IncomingRequestType.POLICY_POSTING)
                .requestDate(new Date())
                .success(false)
                .build();
        incomingRequest = saveIncomingRequestSafely(incomingRequest, motorPolicyRequestDto);

        List<GroupDto> groupDtoList = getAutoGroupDtos(motorPolicyRequestDto);
        NaicomPostingDto requestPayload = NaicomPostingDto.builder()
                .SID(clientID.getParamValue())
                .Token(clientPassword.getParamValue())
                .Type("Auto")
                .DataGroup(groupDtoList)
                .build();

        String jsonStr = writeJson(requestPayload);
        log.debug("Posting AUTO payload to NAICOM: {}", jsonStr);

        ResponseEntity<Object> postingResponseObject = thirdPartyApiClient.postTransaction(policyPostingParameter, jsonStr);
        HttpStatus policyStatus = HttpStatus.valueOf(postingResponseObject.getStatusCode().value());
        String jsonFormat = String.valueOf(postingResponseObject.getBody());

        JSONObject jsonObject = parseJsonBody(jsonFormat);
        if (jsonObject == null) {
            return unreadableResponse(response, incomingRequest, policyStatus, jsonFormat);
        }
        String coinFeedBack = null;
        JSONArray errorMessage = jsonObject.optJSONArray("ErrMsgs");
        JSONArray errorCode = jsonObject.optJSONArray("ErrCodes");

        if ("Y".equalsIgnoreCase(motorPolicyRequestDto.getCoinsurance_policy())
                && "Y".equalsIgnoreCase(motorPolicyRequestDto.getCoinsurance_leader())
                && jsonObject.optBoolean("IsSucceed")) {
            String policyUniqueID = jsonObject.getString("PolicyUniqueID");
            coinFeedBack = recordCoinsuranceDetails(policyUniqueID, motorPolicyRequestDto);
        }

        if (policyStatus.value() == 200 && jsonObject.optBoolean("IsSucceed")) {
            incomingRequest.setSuccess(true);
            response.setSuccess(true);
            response.setMessage(coinFeedBack == null ? "Successfully posted" : "Successfully posted and " + coinFeedBack);
            response.setStatus(HttpStatus.CREATED);
        } else if (policyStatus.value() == 503) {
            response.setSuccess(false);
            response.setMessage("Naicom service is experiencing too much load or downtime. Please try again in 5-10 minutes.");
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        } else {
            response.setSuccess(false);
            response.setStatus(HttpStatus.BAD_REQUEST);
            response.setErrors(mapNaicomErrors(errorMessage, errorCode));
        }

        incomingRequest.setResponseBody(incomingRequestService.truncate(jsonFormat));
        incomingRequest = saveIncomingRequestSafely(incomingRequest);
        response.setNaicom_response(jsonFormat);
        response.setTurnquest_request_id(incomingRequest.getId());
        response.setPolicyUniqueID(jsonObject.optString("PolicyUniqueID", null));
        return response;
    }

    private String recordCoinsuranceDetails(String policyUniqueID, NaicomPolicyDto coinsuranceDto) {
        Parameter recordCoinsurance = parameterService.findByName(CONSTANT_NAICOM_POLICY_COINSURANCE_URL);
        Parameter clientID = parameterService.findByName(CONSTANT_NAICOM_CLIENT_ID);
        Parameter clientPassword = parameterService.findByName(CONSTANT_NAICOM_CLIENT_PASSWORD);
        if (recordCoinsurance == null || clientID == null || clientPassword == null) {
            return "error: NAICOM coinsurance parameters are not set";
        }

        List<CoinSuranceAttribute> coinSuranceAttributes = new ArrayList<>();
        for (CoinsuranceDto detail : coinsuranceDto.getCoinsurance_details()) {
            coinSuranceAttributes.add(CoinSuranceAttribute.builder()
                    .ReinsurerID(detail.getCoinsurer_id())
                    .PremiumPercentage(detail.getPremium_percentage().toString())
                    .build());
        }

        NaicomPostingDto requestPayload = NaicomPostingDto.builder()
                .SID(clientID.getParamValue())
                .Token(clientPassword.getParamValue())
                .PolicyUniqueID(policyUniqueID)
                .PremiumPercentage(coinsuranceDto.getCo_insurance_rate().toString())
                .ReInsuranceDetails(coinSuranceAttributes)
                .Note(coinsuranceDto.getNote())
                .build();

        String jsonStr = writeJson(requestPayload);
        ResponseEntity<Object> postingResponseObject = thirdPartyApiClient.postTransaction(recordCoinsurance, jsonStr);
        JSONObject jsonObject = parseJsonBody(String.valueOf(postingResponseObject.getBody()));
        if (jsonObject == null) {
            return "error: NAICOM returned no usable response while recording the coinsurance policy";
        }
        if (postingResponseObject.getStatusCode().value() == 200 && jsonObject.optBoolean("IsSucceed")) {
            return "coinsurance Policy recorded successfully";
        }
        return "error occurred while recording Coinsurance Policy";
    }

    public NaicomPolicyResponseDto endorsePolicy(NaicomPolicyDto naicomPolicyEndorsement) {
        if (naicomPolicyEndorsement.getNaicom_product() == NaicomProduct.AUTO) {
            return endorseAutoPolicy(naicomPolicyEndorsement);
        }
        return switch (naicomPolicyEndorsement.getNaicom_product()) {
            case BOND -> endorseProductPolicy(naicomPolicyEndorsement, validateBondPolicyFields(naicomPolicyEndorsement), this::getBondGroupDtos);
            case FIRE -> endorseProductPolicy(naicomPolicyEndorsement, validateFirePolicyFields(naicomPolicyEndorsement), this::getFireGroupDtos);
            case MARINE -> endorseProductPolicy(naicomPolicyEndorsement, validateMarineDetails(naicomPolicyEndorsement), this::getMarineGroupDtos);
            case CASUALTY -> endorseProductPolicy(naicomPolicyEndorsement, validateCasualtyPolicyDetails(naicomPolicyEndorsement), this::getCasualtyGroupDtos);
            case ENGINEERING, ENGINEER -> endorseProductPolicy(naicomPolicyEndorsement, validateEngineerPolicy(naicomPolicyEndorsement), this::getEngineerGroupDtos);
            case OIL -> endorseProductPolicy(naicomPolicyEndorsement, validateOilPolicy(naicomPolicyEndorsement), this::getOilGroupDtos);
            case MISCELLANEOUS -> endorseProductPolicy(naicomPolicyEndorsement, validateMiscellaneousPolicy(naicomPolicyEndorsement), this::getMiscellaneousGroupDtos);
            case ACCIDENT -> endorseProductPolicy(naicomPolicyEndorsement, new ArrayList<>(), this::getCasualtyGroupDtos);
            case AVIATION -> endorseProductPolicy(naicomPolicyEndorsement, new ArrayList<>(), this::getMarineGroupDtos);
            default -> NaicomPolicyResponseDto.builder()
                    .message("Endorsement for NAICOM product '" + naicomPolicyEndorsement.getNaicom_product() + "' is not implemented in this standalone app yet.")
                    .status(HttpStatus.NOT_IMPLEMENTED)
                    .success(false)
                    .inputs(naicomPolicyEndorsement)
                    .build();
        };
    }

    /**
     * Shared posting shell for every non-AUTO product line: same param/incomingRequest/coinsurance
     * plumbing as {@link #postAutoPolicyToNaicom}, parameterized by the NAICOM {@code Type}, the
     * pre-computed field-validation errors, and the product's group/attribute builder.
     */
    private NaicomPolicyResponseDto postProductPolicy(NaicomPolicyDto request, String type,
                                                       ArrayList<ErrorDto> foundErrors,
                                                       java.util.function.Function<NaicomPolicyDto, List<GroupDto>> groupBuilder) {
        NaicomPolicyResponseDto response = NaicomPolicyResponseDto.builder()
                .message("Failed to post the policy.")
                .status(HttpStatus.BAD_REQUEST)
                .success(false)
                .inputs(request)
                .build();

        if (request.getNaicom_product_code() == null) {
            foundErrors.add(new ErrorDto("NAICOM product code cannot be null - check the product/scheme code mapping for this policy.", "TEX-400"));
        }
        if (!foundErrors.isEmpty()) {
            response.setErrors(foundErrors);
            return response;
        }

        Parameter policyPostingParameter = parameterService.findByName(CONSTANT_NAICOM_POLICY_POSTING_URL);
        if (policyPostingParameter == null) {
            return missingParameterResponse(request, CONSTANT_NAICOM_POLICY_POSTING_URL);
        }
        if (request.getRecorder_id() == null) {
            Parameter orgRecorderID = parameterService.findByName(CONSTANT_NAICOM_ORG_RECORDER_ID);
            if (orgRecorderID == null) {
                return missingParameterResponse(request, CONSTANT_NAICOM_ORG_RECORDER_ID);
            }
            request.setRecorder_id(orgRecorderID.getParamValue());
        }
        Parameter orgRecorderType = parameterService.findByName(CONSTANT_NAICOM_ORG_RECORDER_TYPE);
        if (orgRecorderType == null) {
            return missingParameterResponse(request, CONSTANT_NAICOM_ORG_RECORDER_TYPE);
        }
        request.setRecorder_type(orgRecorderType.getParamValue());

        Parameter clientID = parameterService.findByName(CONSTANT_NAICOM_CLIENT_ID);
        Parameter clientPassword = parameterService.findByName(CONSTANT_NAICOM_CLIENT_PASSWORD);
        if (clientID == null || clientID.getParamValue() == null || clientPassword == null || clientPassword.getParamValue() == null) {
            response.setErrors(singleError("Confirm the NAICOM integration parameters are not set.", "TEX-404"));
            return response;
        }

        IncomingRequest incomingRequest = IncomingRequest.builder()
                .incomingRequestType(IncomingRequestType.POLICY_POSTING)
                .requestDate(new Date())
                .success(false)
                .build();
        incomingRequest = saveIncomingRequestSafely(incomingRequest, request);

        NaicomPostingDto requestPayload = NaicomPostingDto.builder()
                .SID(clientID.getParamValue())
                .Token(clientPassword.getParamValue())
                .Type(type)
                .DataGroup(groupBuilder.apply(request))
                .build();

        String jsonStr = writeJson(requestPayload);
        log.debug("Posting {} payload to NAICOM: {}", type, jsonStr);

        ResponseEntity<Object> postingResponseObject = thirdPartyApiClient.postTransaction(policyPostingParameter, jsonStr);
        HttpStatus policyStatus = HttpStatus.valueOf(postingResponseObject.getStatusCode().value());
        String jsonFormat = String.valueOf(postingResponseObject.getBody());

        JSONObject jsonObject = parseJsonBody(jsonFormat);
        if (jsonObject == null) {
            return unreadableResponse(response, incomingRequest, policyStatus, jsonFormat);
        }
        String coinFeedBack = null;
        JSONArray errorMessage = jsonObject.optJSONArray("ErrMsgs");
        JSONArray errorCode = jsonObject.optJSONArray("ErrCodes");

        if ("Y".equalsIgnoreCase(request.getCoinsurance_policy())
                && "Y".equalsIgnoreCase(request.getCoinsurance_leader())
                && jsonObject.optBoolean("IsSucceed")) {
            String policyUniqueID = jsonObject.getString("PolicyUniqueID");
            coinFeedBack = recordCoinsuranceDetails(policyUniqueID, request);
        }

        if (policyStatus.value() == 200 && jsonObject.optBoolean("IsSucceed")) {
            incomingRequest.setSuccess(true);
            response.setSuccess(true);
            response.setMessage(coinFeedBack == null ? "Successfully posted" : "Successfully posted and " + coinFeedBack);
            response.setStatus(HttpStatus.CREATED);
        } else if (policyStatus.value() == 503) {
            response.setSuccess(false);
            response.setMessage("Naicom service is experiencing too much load or downtime. Please try again in 5-10 minutes.");
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        } else {
            response.setSuccess(false);
            response.setStatus(HttpStatus.BAD_REQUEST);
            response.setErrors(mapNaicomErrors(errorMessage, errorCode));
        }

        incomingRequest.setResponseBody(incomingRequestService.truncate(jsonFormat));
        incomingRequest = saveIncomingRequestSafely(incomingRequest);
        response.setNaicom_response(jsonFormat);
        response.setTurnquest_request_id(incomingRequest.getId());
        response.setPolicyUniqueID(jsonObject.optString("PolicyUniqueID", null));
        return response;
    }

    /**
     * Shared endorsement shell for every non-AUTO product line, mirroring {@link #endorseAutoPolicy}.
     */
    private NaicomPolicyResponseDto endorseProductPolicy(NaicomPolicyDto request,
                                                          ArrayList<ErrorDto> foundErrors,
                                                          java.util.function.Function<NaicomPolicyDto, List<GroupDto>> groupBuilder) {
        NaicomPolicyResponseDto response = NaicomPolicyResponseDto.builder()
                .message("Failed to endorse the policy.")
                .status(HttpStatus.BAD_REQUEST)
                .success(false)
                .inputs(request)
                .build();

        if (request.getPolicy_unique_id() == null || request.getPolicy_unique_id().isEmpty()) {
            foundErrors.add(0, new ErrorDto("Policy Unique Id cannot be null.", "TEX-400"));
        }
        if (!foundErrors.isEmpty()) {
            response.setErrors(foundErrors);
            return response;
        }

        Parameter policyPostingParameter = parameterService.findByName(CONSTANT_NAICOM_POLICY_ENDORSEMENT_URL);
        if (policyPostingParameter == null) {
            return missingParameterResponse(request, CONSTANT_NAICOM_POLICY_ENDORSEMENT_URL);
        }
        Parameter orgRecorderType = parameterService.findByName(CONSTANT_NAICOM_ORG_RECORDER_TYPE);
        if (orgRecorderType == null) {
            return missingParameterResponse(request, CONSTANT_NAICOM_ORG_RECORDER_TYPE);
        }
        request.setRecorder_type(orgRecorderType.getParamValue());

        Parameter clientID = parameterService.findByName(CONSTANT_NAICOM_CLIENT_ID);
        Parameter clientPassword = parameterService.findByName(CONSTANT_NAICOM_CLIENT_PASSWORD);
        if (clientID == null || clientPassword == null) {
            response.setErrors(singleError("Confirm the NAICOM integration parameters are not set.", "TEX-404"));
            return response;
        }

        IncomingRequest incomingRequest = IncomingRequest.builder()
                .incomingRequestType(IncomingRequestType.POLICY_ENDORSEMENT)
                .requestDate(new Date())
                .success(false)
                .build();
        incomingRequest = saveIncomingRequestSafely(incomingRequest, request);

        NaicomPostingDto requestPayload = NaicomPostingDto.builder()
                .SID(clientID.getParamValue())
                .Token(clientPassword.getParamValue())
                .PolicyUniqueID(request.getPolicy_unique_id())
                .DataGroup(groupBuilder.apply(request))
                .build();

        String jsonStr = writeJson(requestPayload);
        ResponseEntity<Object> postingResponseObject = thirdPartyApiClient.postTransaction(policyPostingParameter, jsonStr);
        HttpStatus policyStatus = HttpStatus.valueOf(postingResponseObject.getStatusCode().value());
        String jsonFormat = String.valueOf(postingResponseObject.getBody());

        JSONObject jsonObject = parseJsonBody(jsonFormat);
        if (jsonObject == null) {
            return unreadableResponse(response, incomingRequest, policyStatus, jsonFormat);
        }
        JSONArray errorMessage = jsonObject.optJSONArray("ErrMsgs");
        JSONArray errorCode = jsonObject.optJSONArray("ErrCodes");
        if (policyStatus.value() == 200 && jsonObject.optBoolean("IsSucceed")) {
            incomingRequest.setSuccess(true);
            response.setSuccess(true);
            response.setMessage("Successfully modified the policy");
            response.setStatus(HttpStatus.OK);
        } else if (policyStatus.value() == 503) {
            response.setSuccess(false);
            response.setMessage("Naicom service is experiencing too much load or downtime. Please try again in 5-10 minutes.");
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        } else {
            response.setSuccess(false);
            response.setStatus(HttpStatus.BAD_REQUEST);
            response.setErrors(mapNaicomErrors(errorMessage, errorCode));
        }

        incomingRequest.setResponseBody(incomingRequestService.truncate(jsonFormat));
        incomingRequest = saveIncomingRequestSafely(incomingRequest);
        response.setNaicom_response(jsonFormat);
        response.setTurnquest_request_id(incomingRequest.getId());
        response.setPolicyUniqueID(jsonObject.optString("PolicyUniqueID", null));
        return response;
    }

    private NaicomPolicyResponseDto endorseAutoPolicy(NaicomPolicyDto motorEndorsementRequest) {
        NaicomPolicyResponseDto response = NaicomPolicyResponseDto.builder()
                .message("Failed to endorse the auto policy.")
                .status(HttpStatus.BAD_REQUEST)
                .success(false)
                .inputs(motorEndorsementRequest)
                .build();

        ArrayList<ErrorDto> foundErrors = validatePolicyFields(motorEndorsementRequest);
        if (motorEndorsementRequest.getPolicy_unique_id() == null || motorEndorsementRequest.getPolicy_unique_id().isEmpty()) {
            foundErrors.add(new ErrorDto("Policy Unique Id cannot be null.", "TEX-400"));
        }
        if (!foundErrors.isEmpty()) {
            response.setErrors(foundErrors);
            return response;
        }

        Parameter policyPostingParameter = parameterService.findByName(CONSTANT_NAICOM_POLICY_ENDORSEMENT_URL);
        if (policyPostingParameter == null) {
            return missingParameterResponse(motorEndorsementRequest, CONSTANT_NAICOM_POLICY_ENDORSEMENT_URL);
        }
        Parameter orgRecorderType = parameterService.findByName(CONSTANT_NAICOM_ORG_RECORDER_TYPE);
        if (orgRecorderType == null) {
            return missingParameterResponse(motorEndorsementRequest, CONSTANT_NAICOM_ORG_RECORDER_TYPE);
        }
        motorEndorsementRequest.setRecorder_type(orgRecorderType.getParamValue());

        Parameter clientID = parameterService.findByName(CONSTANT_NAICOM_CLIENT_ID);
        Parameter clientPassword = parameterService.findByName(CONSTANT_NAICOM_CLIENT_PASSWORD);
        if (clientID == null || clientPassword == null) {
            response.setErrors(singleError("Confirm the NAICOM integration parameters are not set.", "TEX-404"));
            return response;
        }

        IncomingRequest incomingRequest = IncomingRequest.builder()
                .incomingRequestType(IncomingRequestType.POLICY_ENDORSEMENT)
                .requestDate(new Date())
                .success(false)
                .build();
        incomingRequest = saveIncomingRequestSafely(incomingRequest, motorEndorsementRequest);

        List<GroupDto> groupDtoList = getAutoGroupDtos(motorEndorsementRequest);
        NaicomPostingDto requestPayload = NaicomPostingDto.builder()
                .SID(clientID.getParamValue())
                .Token(clientPassword.getParamValue())
                .PolicyUniqueID(motorEndorsementRequest.getPolicy_unique_id())
                .Type("Auto")
                .DataGroup(groupDtoList)
                .build();

        String jsonStr = writeJson(requestPayload);
        ResponseEntity<Object> postingResponseObject = thirdPartyApiClient.postTransaction(policyPostingParameter, jsonStr);
        HttpStatus policyStatus = HttpStatus.valueOf(postingResponseObject.getStatusCode().value());
        String jsonFormat = String.valueOf(postingResponseObject.getBody());

        JSONObject jsonObject = parseJsonBody(jsonFormat);
        if (jsonObject == null) {
            return unreadableResponse(response, incomingRequest, policyStatus, jsonFormat);
        }
        JSONArray errorMessage = jsonObject.optJSONArray("ErrMsgs");
        JSONArray errorCode = jsonObject.optJSONArray("ErrCodes");
        if (policyStatus.value() == 200 && jsonObject.optBoolean("IsSucceed")) {
            incomingRequest.setSuccess(true);
            response.setSuccess(true);
            response.setMessage("Successfully modified the policy");
            response.setStatus(HttpStatus.OK);
        } else if (policyStatus.value() == 503) {
            response.setSuccess(false);
            response.setMessage("Naicom service is experiencing too much load or downtime. Please try again in 5-10 minutes.");
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        } else {
            response.setSuccess(false);
            response.setStatus(HttpStatus.BAD_REQUEST);
            response.setErrors(mapNaicomErrors(errorMessage, errorCode));
        }

        incomingRequest.setResponseBody(incomingRequestService.truncate(jsonFormat));
        incomingRequest = saveIncomingRequestSafely(incomingRequest);
        response.setNaicom_response(jsonFormat);
        response.setTurnquest_request_id(incomingRequest.getId());
        response.setPolicyUniqueID(jsonObject.optString("PolicyUniqueID", null));
        return response;
    }

    public NaicomPolicyResponseDto renewNaicomPolicy(NaicomPolicyDto policyDto) {
        NaicomPolicyResponseDto response = NaicomPolicyResponseDto.builder()
                .message("Failed to renew the policy.")
                .status(HttpStatus.BAD_REQUEST)
                .success(false)
                .inputs(policyDto)
                .build();

        if (policyDto.getPolicy_unique_id() == null || policyDto.getPolicy_unique_id().isEmpty()) {
            response.setErrors(singleError("Policy Unique Id cannot be null.", "TEX-400"));
            return response;
        }

        Parameter policyPostingParameter = parameterService.findByName(CONSTANT_NAICOM_POLICY_RENEWAL_URL);
        if (policyPostingParameter == null) {
            return missingParameterResponse(policyDto, CONSTANT_NAICOM_POLICY_RENEWAL_URL);
        }
        Parameter clientID = parameterService.findByName(CONSTANT_NAICOM_CLIENT_ID);
        Parameter clientPassword = parameterService.findByName(CONSTANT_NAICOM_CLIENT_PASSWORD);
        if (clientID == null || clientPassword == null) {
            response.setErrors(singleError("Confirm the NAICOM integration parameters are not set.", "TEX-404"));
            return response;
        }

        IncomingRequest incomingRequest = IncomingRequest.builder()
                .incomingRequestType(IncomingRequestType.POLICY_RENEWAL)
                .requestDate(new Date())
                .success(false)
                .build();
        incomingRequest = saveIncomingRequestSafely(incomingRequest, policyDto);

        SimpleDateFormat formatter = new SimpleDateFormat("M/dd/yyyy hh:mm aa");
        NaicomPostingDto requestPayload = NaicomPostingDto.builder()
                .SID(clientID.getParamValue())
                .Token(clientPassword.getParamValue())
                .PolicyUniqueID(policyDto.getPolicy_unique_id())
                .New_Start(formatter.format(policyDto.getCover_start_date()))
                .New_Expiration(formatter.format(policyDto.getCover_end_date()))
                .New_Premium(policyDto.getTotal_premium().toString())
                .Note(policyDto.getNote())
                .build();

        String jsonStr = writeJson(requestPayload);
        ResponseEntity<Object> postingResponseObject = thirdPartyApiClient.postTransaction(policyPostingParameter, jsonStr);
        HttpStatus policyStatus = HttpStatus.valueOf(postingResponseObject.getStatusCode().value());
        String jsonFormat = String.valueOf(postingResponseObject.getBody());

        JSONObject jsonObject = parseJsonBody(jsonFormat);
        if (jsonObject == null) {
            return unreadableResponse(response, incomingRequest, policyStatus, jsonFormat);
        }

        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(jsonFormat);
        } catch (Exception e) {
            response.setMessage("Error from mapper " + e.getMessage());
            return response;
        }
        boolean isSucceed = rootNode.path("IsSucceed").asBoolean();
        if (!isSucceed) {
            JsonNode errMsgsNode = rootNode.path("ErrMsgs");
            for (JsonNode msgNode : errMsgsNode) {
                if (msgNode.asText().contains("Cannot find the policy with the given unique policy id")
                        || msgNode.asText().contains("Failed to renew the policy")) {
                    return postPolicyToNaicom(policyDto.getPolicy_batch_no(), policyDto);
                }
            }
        }

        JSONArray errorMessage = jsonObject.optJSONArray("ErrMsgs");
        JSONArray errorCode = jsonObject.optJSONArray("ErrCodes");
        if (policyStatus.value() == 200 && isSucceed) {
            incomingRequest.setSuccess(true);
            response.setSuccess(true);
            response.setMessage("Successfully Renewed");
            response.setStatus(HttpStatus.OK);
        } else if (policyStatus.value() == 503) {
            response.setSuccess(false);
            response.setMessage("Naicom service is experiencing too much load or downtime. Please try again in 5-10 minutes.");
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        } else {
            response.setSuccess(false);
            response.setStatus(HttpStatus.BAD_REQUEST);
            response.setErrors(mapNaicomErrors(errorMessage, errorCode));
        }

        incomingRequest.setResponseBody(incomingRequestService.truncate(jsonFormat));
        saveIncomingRequestSafely(incomingRequest);
        response.setNaicom_response(jsonFormat);
        response.setPolicyUniqueID(jsonObject.optString("PolicyUniqueID", null));
        return response;
    }

    public NaicomPolicyResponseDto terminateNaicomPolicy(NaicomPolicyDto naicomPolicyDto) {
        NaicomPolicyResponseDto response = NaicomPolicyResponseDto.builder()
                .message("Failed to terminate the policy.")
                .status(HttpStatus.BAD_REQUEST)
                .success(false)
                .inputs(naicomPolicyDto)
                .build();

        Parameter policyPostingParameter = parameterService.findByName(CONSTANT_NAICOM_POLICY_TERMINATION_URL);
        if (policyPostingParameter == null) {
            return missingParameterResponse(naicomPolicyDto, CONSTANT_NAICOM_POLICY_TERMINATION_URL);
        }
        Parameter clientID = parameterService.findByName(CONSTANT_NAICOM_CLIENT_ID);
        Parameter clientPassword = parameterService.findByName(CONSTANT_NAICOM_CLIENT_PASSWORD);
        if (clientID == null || clientPassword == null) {
            response.setErrors(singleError("Confirm the NAICOM integration parameters are not set.", "TEX-404"));
            return response;
        }

        IncomingRequest incomingRequest = IncomingRequest.builder()
                .incomingRequestType(IncomingRequestType.POLICY_TERMINATION)
                .requestDate(new Date())
                .success(false)
                .build();
        incomingRequest = saveIncomingRequestSafely(incomingRequest, naicomPolicyDto);

        SimpleDateFormat formatter = new SimpleDateFormat("M/dd/yyyy hh:mm aa");
        String dateToday = formatter.format(Calendar.getInstance().getTime());

        NaicomPostingDto requestPayload = NaicomPostingDto.builder()
                .SID(clientID.getParamValue())
                .Token(clientPassword.getParamValue())
                .PolicyUniqueID(naicomPolicyDto.getPolicy_unique_id())
                .Date_Cancel(naicomPolicyDto.getCancel_date() == null ? dateToday : formatter.format(naicomPolicyDto.getCancel_date()))
                .Refund(naicomPolicyDto.getRefund() == null ? naicomPolicyDto.getTotal_premium().abs().toString() : naicomPolicyDto.getRefund().toString())
                .Note(naicomPolicyDto.getNote())
                .build();

        String jsonStr = writeJson(requestPayload);
        ResponseEntity<Object> postingResponseObject = thirdPartyApiClient.postTransaction(policyPostingParameter, jsonStr);
        HttpStatus policyStatus = HttpStatus.valueOf(postingResponseObject.getStatusCode().value());
        String jsonFormat = String.valueOf(postingResponseObject.getBody());

        JSONObject jsonObject = parseJsonBody(jsonFormat);
        if (jsonObject == null) {
            return unreadableResponse(response, incomingRequest, policyStatus, jsonFormat);
        }
        JSONArray errorMessage = jsonObject.optJSONArray("ErrMsgs");
        JSONArray errorCode = jsonObject.optJSONArray("ErrCodes");
        if (policyStatus.value() == 200 && jsonObject.optBoolean("IsSucceed")) {
            incomingRequest.setSuccess(true);
            response.setSuccess(true);
            response.setMessage("Successfully Terminated");
            response.setStatus(HttpStatus.OK);
        } else if (policyStatus.value() == 503) {
            response.setSuccess(false);
            response.setMessage("Naicom service is experiencing too much load or downtime. Please try again in 5-10 minutes.");
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        } else {
            response.setSuccess(false);
            response.setStatus(HttpStatus.BAD_REQUEST);
            response.setErrors(mapNaicomErrors(errorMessage, errorCode));
        }

        incomingRequest.setResponseBody(incomingRequestService.truncate(jsonFormat));
        incomingRequest = saveIncomingRequestSafely(incomingRequest);
        response.setNaicom_response(jsonFormat);
        response.setTurnquest_request_id(incomingRequest.getId());
        return response;
    }

    public NaicomPolicyResponseDto deleteNaicomPolicy(String uniquePolicyId, NaicomPolicyDto naicomPolicyDto) {
        NaicomPolicyResponseDto response = NaicomPolicyResponseDto.builder()
                .message("Failed to delete the policy.")
                .status(HttpStatus.BAD_REQUEST)
                .success(false)
                .inputs(naicomPolicyDto)
                .build();

        Parameter policyPostingParameter = parameterService.findByName(CONSTANT_NAICOM_POLICY_DELETION_URL);
        if (policyPostingParameter == null) {
            return missingParameterResponse(naicomPolicyDto, CONSTANT_NAICOM_POLICY_DELETION_URL);
        }
        Parameter clientID = parameterService.findByName(CONSTANT_NAICOM_CLIENT_ID);
        Parameter clientPassword = parameterService.findByName(CONSTANT_NAICOM_CLIENT_PASSWORD);
        if (clientID == null || clientPassword == null) {
            response.setErrors(singleError("Confirm the NAICOM integration parameters are not set.", "TEX-404"));
            return response;
        }

        IncomingRequest incomingRequest = IncomingRequest.builder()
                .incomingRequestType(IncomingRequestType.POLICY_DELETION)
                .requestDate(new Date())
                .success(false)
                .build();
        incomingRequest = saveIncomingRequestSafely(incomingRequest, naicomPolicyDto);

        SimpleDateFormat formatter = new SimpleDateFormat("M/dd/yyyy hh:mm aa");
        String dateToday = formatter.format(Calendar.getInstance().getTime());

        NaicomPostingDto requestPayload = NaicomPostingDto.builder()
                .SID(clientID.getParamValue())
                .Token(clientPassword.getParamValue())
                .PolicyUniqueID(uniquePolicyId)
                .Date_Cancel(naicomPolicyDto.getCancel_date() == null ? dateToday : formatter.format(naicomPolicyDto.getCancel_date()))
                .Refund("0")
                .Note(naicomPolicyDto.getNote())
                .build();

        String jsonStr = writeJson(requestPayload);
        ResponseEntity<Object> postingResponseObject = thirdPartyApiClient.postTransaction(policyPostingParameter, jsonStr);
        HttpStatus policyStatus = HttpStatus.valueOf(postingResponseObject.getStatusCode().value());
        String jsonFormat = String.valueOf(postingResponseObject.getBody());

        JSONObject jsonObject = parseJsonBody(jsonFormat);
        if (jsonObject == null) {
            return unreadableResponse(response, incomingRequest, policyStatus, jsonFormat);
        }
        JSONArray errorMessage = jsonObject.optJSONArray("ErrMsgs");
        JSONArray errorCode = jsonObject.optJSONArray("ErrCodes");
        if (policyStatus.value() == 200 && jsonObject.optBoolean("IsSucceed")) {
            incomingRequest.setSuccess(true);
            response.setSuccess(true);
            response.setMessage("Successfully Deleted");
            response.setStatus(HttpStatus.OK);
        } else if (policyStatus.value() == 503) {
            response.setSuccess(false);
            response.setMessage("Naicom service is experiencing too much load or downtime. Please try again in 5-10 minutes.");
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        } else {
            response.setSuccess(false);
            response.setStatus(HttpStatus.BAD_REQUEST);
            response.setErrors(mapNaicomErrors(errorMessage, errorCode));
        }

        incomingRequest.setResponseBody(incomingRequestService.truncate(jsonFormat));
        incomingRequest = saveIncomingRequestSafely(incomingRequest);
        response.setNaicom_response(jsonFormat);
        response.setTurnquest_request_id(incomingRequest.getId());
        return response;
    }

    // -------------------------------------------------------------------------------
    // Field validation (AUTO) - ported from NaicomIntegrationService.validatePolicyFields
    // -------------------------------------------------------------------------------

    private ArrayList<ErrorDto> validatePolicyFields(NaicomPolicyDto dto) {
        ArrayList<ErrorDto> foundErrors = new ArrayList<>();

        if (dto.getNaicom_product_code() == null) {
            foundErrors.add(new ErrorDto("NAICOM product code cannot be null - check the product/scheme code mapping for this policy.", "TEX-400"));
        }
        if (dto.getCover_start_date() == null) {
            foundErrors.add(new ErrorDto("Cover start date cannot be null.", "TEX-400"));
        }
        if (dto.getCover_end_date() == null) {
            foundErrors.add(new ErrorDto("Cover end date cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_number())) {
            foundErrors.add(new ErrorDto("Policy number cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_description())) {
            foundErrors.add(new ErrorDto("Policy Description cannot be null.", "TEX-400"));
        }
        if (dto.getCover_type() == null) {
            foundErrors.add(new ErrorDto("Cover Type cannot be null.", "TEX-400"));
        }
        if (dto.getCustomer() != null) {
            var customer = dto.getCustomer();
            if (customer.getType() == null) {
                foundErrors.add(new ErrorDto("Insured Person type cannot be null.", "TEX-400"));
                if (customer.getType() == ClientType.PERSON) {
                    if (isBlank(customer.getLast_name())) {
                        foundErrors.add(new ErrorDto("Insured Person Last Name cannot be null.", "TEX-400"));
                    }
                    if (isBlank(customer.getFirst_name())) {
                        foundErrors.add(new ErrorDto("Insured Person First Name cannot be null.", "TEX-400"));
                    }
                    if (isBlank(customer.getLicense())) {
                        foundErrors.add(new ErrorDto("Insured Person License cannot be null.", "TEX-400"));
                    }
                } else {
                    if (isBlank(customer.getOrganization_id())) {
                        foundErrors.add(new ErrorDto("Organization id cannot be null.", "TEX-400"));
                    }
                    if (isBlank(customer.getOrganization_name())) {
                        foundErrors.add(new ErrorDto("Organization Name cannot be null.", "TEX-400"));
                    }
                }
            }
            if (isBlank(customer.getResidential_address())) {
                foundErrors.add(new ErrorDto("Insured Residential address cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getCity())) {
                foundErrors.add(new ErrorDto("Insured city cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getState())) {
                foundErrors.add(new ErrorDto("Insured state cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getPostal_address())) {
                foundErrors.add(new ErrorDto("Insured postal Code cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getPhone_number())) {
                foundErrors.add(new ErrorDto("Insured phone number cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getEmail_address())) {
                foundErrors.add(new ErrorDto("Insured email address cannot be null.", "TEX-400"));
            }
        }
        if (dto.getSum_insured() == null) {
            foundErrors.add(new ErrorDto("Sum insured cannot be null.", "TEX-400"));
        }
        if (dto.getTotal_premium() == null) {
            foundErrors.add(new ErrorDto("Premium cannot be null.", "TEX-400"));
        }
        if (dto.getCommission_fee() == null) {
            foundErrors.add(new ErrorDto("Commission fee cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getExtra_fee())) {
            foundErrors.add(new ErrorDto("Extra fee cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPremium_note())) {
            foundErrors.add(new ErrorDto("Premium note cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getTerms())) {
            foundErrors.add(new ErrorDto("Terms cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPreamble())) {
            foundErrors.add(new ErrorDto("Preamble cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getEndorsements())) {
            foundErrors.add(new ErrorDto("Endorsements cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getExclusions())) {
            foundErrors.add(new ErrorDto("Exclusions cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getConditions())) {
            foundErrors.add(new ErrorDto("Conditions cannot be null.", "TEX-400"));
        }

        if (dto.getInsured_info() != null) {
            for (NaicomInsuredInfoDto insuredInfo : dto.getInsured_info()) {
                insuredInfo.setVehicle_id(defaultIfBlank(insuredInfo.getVehicle_id(), DEFAULT_STRING));
                insuredInfo.setPlate_no(defaultIfBlank(insuredInfo.getPlate_no(), DEFAULT_STRING));
                insuredInfo.setRegistration_number(defaultIfBlank(insuredInfo.getRegistration_number(), DEFAULT_STRING));
                insuredInfo.setVehicle_type(defaultIfBlank(insuredInfo.getVehicle_type(), DEFAULT_STRING));
                insuredInfo.setVehicle_model(defaultIfBlank(insuredInfo.getVehicle_model(), DEFAULT_STRING));
                insuredInfo.setVehicle_make(defaultIfBlank(insuredInfo.getVehicle_make(), DEFAULT_STRING));
                insuredInfo.setVehicle_color(defaultIfBlank(insuredInfo.getVehicle_color(), DEFAULT_STRING));
                insuredInfo.setYear_of_manufacture(defaultIfBlank(insuredInfo.getYear_of_manufacture(), DEFAULT_YEAR_OF_MANUFACTURE));
                insuredInfo.setEngine_capacity(defaultIfBlank(insuredInfo.getEngine_capacity(), DEFAULT_STRING));
                insuredInfo.setAuto_note(defaultIfBlank(insuredInfo.getAuto_note(), DEFAULT_STRING));
                insuredInfo.setVehicle_mileage(insuredInfo.getVehicle_mileage() == null ? BigDecimal.ZERO : insuredInfo.getVehicle_mileage());
                insuredInfo.setSeats(insuredInfo.getSeats() == null ? 0 : insuredInfo.getSeats());
                insuredInfo.setRegistration_date(insuredInfo.getRegistration_date() == null ? defaultDate() : insuredInfo.getRegistration_date());
                insuredInfo.setRegistration_expiry_date(insuredInfo.getRegistration_expiry_date() == null ? defaultDate() : insuredInfo.getRegistration_expiry_date());
            }
        }
        return foundErrors;
    }

    // -------------------------------------------------------------------------------
    // Field validation (other product lines) - ported from NaicomIntegrationService's
    // per-product validate*Fields methods. The original's customer/sub-detail checks used an
    // inverted "== null" guard (dead/NPE-prone); this port uses the correct "!= null" sense.
    // -------------------------------------------------------------------------------

    private ArrayList<ErrorDto> validateBondPolicyFields(NaicomPolicyDto dto) {
        ArrayList<ErrorDto> foundErrors = new ArrayList<>();
        if (dto.getCover_start_date() == null) {
            foundErrors.add(new ErrorDto("Cover start date cannot be null.", "TEX-400"));
        }
        if (dto.getCover_end_date() == null) {
            foundErrors.add(new ErrorDto("Cover end date cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_number())) {
            foundErrors.add(new ErrorDto("Policy number cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_description())) {
            foundErrors.add(new ErrorDto("Policy Description cannot be null.", "TEX-400"));
        }
        if (dto.getCustomer() != null) {
            var customer = dto.getCustomer();
            if (customer.getBeneficiary_type() == null) {
                foundErrors.add(new ErrorDto("Insured Person type cannot be null.", "TEX-400"));
            } else if (customer.getBeneficiary_type() == BondBeneficiaryType.INDIVIDUAL) {
                if (isBlank(customer.getLast_name())) {
                    foundErrors.add(new ErrorDto("Bond Person Last Name cannot be null.", "TEX-400"));
                }
                if (isBlank(customer.getFirst_name())) {
                    foundErrors.add(new ErrorDto("Bond Person First Name cannot be null.", "TEX-400"));
                }
                if (customer.getIdentification_type() == null) {
                    foundErrors.add(new ErrorDto("Bond Person Identification Type cannot be null.", "TEX-400"));
                }
                if (isBlank(customer.getIdentification_number())) {
                    foundErrors.add(new ErrorDto("Bond Person Identification Number cannot be null.", "TEX-400"));
                }
            } else {
                if (isBlank(customer.getOrganization_id())) {
                    foundErrors.add(new ErrorDto("Organization id cannot be null.", "TEX-400"));
                }
                if (isBlank(customer.getOrganization_name())) {
                    foundErrors.add(new ErrorDto("Organization Name cannot be null.", "TEX-400"));
                }
            }
            if (isBlank(customer.getResidential_address())) {
                foundErrors.add(new ErrorDto("Insured Residential address cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getCity())) {
                foundErrors.add(new ErrorDto("Insured city cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getState())) {
                foundErrors.add(new ErrorDto("Insured state cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getPostal_address())) {
                foundErrors.add(new ErrorDto("Insured postal Code cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getPhone_number())) {
                foundErrors.add(new ErrorDto("Insured phone number cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getEmail_address())) {
                foundErrors.add(new ErrorDto("Insured email address cannot be null.", "TEX-400"));
            }
        }
        if (dto.getTotal_premium() == null) {
            foundErrors.add(new ErrorDto("Premium cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getContract_description())) {
            foundErrors.add(new ErrorDto("Contract description cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getContract_location())) {
            foundErrors.add(new ErrorDto("Contract location cannot be null.", "TEX-400"));
        }
        if (dto.getContract_price() == null) {
            foundErrors.add(new ErrorDto("Contract price cannot be null.", "TEX-400"));
        }
        if (dto.getBond_percentage() == null) {
            foundErrors.add(new ErrorDto("Bond percentage cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getImporter_number())) {
            foundErrors.add(new ErrorDto("Importer Number cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getMerchandise_description())) {
            foundErrors.add(new ErrorDto("Merchandise description cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getMerchandise_value())) {
            foundErrors.add(new ErrorDto("Merchandise value cannot be null.", "TEX-400"));
        }
        if (dto.getTax_duty() == null) {
            foundErrors.add(new ErrorDto("Tax duty cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getOrigin_country())) {
            foundErrors.add(new ErrorDto("Origin country cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getEntry_port())) {
            foundErrors.add(new ErrorDto("Entry port cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getCourt_name())) {
            foundErrors.add(new ErrorDto("Court name cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getCourt_case_no())) {
            foundErrors.add(new ErrorDto("Court case number cannot be null.", "TEX-400"));
        }
        if (dto.getBond_paid() == null) {
            foundErrors.add(new ErrorDto("Bond paid cannot be null.", "TEX-400"));
        }
        if (dto.getBond_amount() == null) {
            foundErrors.add(new ErrorDto("Bond Amount cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getBond_description())) {
            foundErrors.add(new ErrorDto("Bond description cannot be null.", "TEX-400"));
        }
        return foundErrors;
    }

    private ArrayList<ErrorDto> validateFirePolicyFields(NaicomPolicyDto dto) {
        ArrayList<ErrorDto> foundErrors = new ArrayList<>();
        if (dto.getCover_start_date() == null) {
            foundErrors.add(new ErrorDto("Cover start date cannot be null.", "TEX-400"));
        }
        if (dto.getCover_end_date() == null) {
            foundErrors.add(new ErrorDto("Cover end date cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_number())) {
            foundErrors.add(new ErrorDto("Policy number cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_description())) {
            foundErrors.add(new ErrorDto("Policy Description cannot be null.", "TEX-400"));
        }
        if (dto.getCustomer() != null) {
            if (isBlank(dto.getCustomer().getName())) {
                foundErrors.add(new ErrorDto("Customer Name cannot be null.", "TEX-400"));
            }
            if (isBlank(dto.getCustomer().getEmail_address())) {
                foundErrors.add(new ErrorDto("Customer Email address cannot be null.", "TEX-400"));
            }
            if (isBlank(dto.getCustomer().getPhone_number())) {
                foundErrors.add(new ErrorDto("Customer Phone number cannot be null.", "TEX-400"));
            }
        }
        if (dto.getBurglary_details() != null) {
            var burglary = dto.getBurglary_details();
            if (isBlank(burglary.getBurglary_coverage_detail())) {
                foundErrors.add(new ErrorDto("Burglary coverage Details cannot be null.", "TEX-400"));
            }
            if (isBlank(burglary.getBurglary_anti_theft())) {
                foundErrors.add(new ErrorDto("Burglary anti theft cannot be null.", "TEX-400"));
            }
            if (isBlank(burglary.getBurglary_history())) {
                foundErrors.add(new ErrorDto("Burglary history cannot be null.", "TEX-400"));
            }
        }
        if (dto.getHouse_details() != null) {
            var house = dto.getHouse_details();
            if (isBlank(house.getHouse_coverage_detail())) {
                foundErrors.add(new ErrorDto("House coverage Details cannot be null.", "TEX-400"));
            }
            if (isBlank(house.getHouse_history())) {
                foundErrors.add(new ErrorDto("House history Details cannot be null.", "TEX-400"));
            }
            if (isBlank(house.getHouse_building_type())) {
                foundErrors.add(new ErrorDto("House building type cannot be null.", "TEX-400"));
            }
            if (isBlank(house.getHouse_security_detail())) {
                foundErrors.add(new ErrorDto("House security Details cannot be null.", "TEX-400"));
            }
        }
        if (dto.getFire_details() != null) {
            var fire = dto.getFire_details();
            if (isBlank(fire.getFire_history())) {
                foundErrors.add(new ErrorDto("Fire history Details cannot be null.", "TEX-400"));
            }
            if (isBlank(fire.getFire_coverage_detail())) {
                foundErrors.add(new ErrorDto("Fire coverage Details cannot be null.", "TEX-400"));
            }
            if (isBlank(fire.getFire_protection_detail())) {
                foundErrors.add(new ErrorDto("Fire protection Details cannot be null.", "TEX-400"));
            }
        }
        if (dto.getBuilding_details() != null) {
            var building = dto.getBuilding_details();
            if (isBlank(building.getBuilding_name())) {
                foundErrors.add(new ErrorDto("Building Name cannot be null.", "TEX-400"));
            }
            if (isBlank(building.getBuilding_city())) {
                foundErrors.add(new ErrorDto("Building city cannot be null.", "TEX-400"));
            }
            if (isBlank(building.getBuilding_state())) {
                foundErrors.add(new ErrorDto("Building state cannot be null.", "TEX-400"));
            }
            if (isBlank(building.getBuilding_address_line())) {
                foundErrors.add(new ErrorDto("Building Address line cannot be null.", "TEX-400"));
            }
            if (isBlank(building.getBuilding_door_number())) {
                foundErrors.add(new ErrorDto("Building door number cannot be null.", "TEX-400"));
            }
            if (isBlank(building.getBuilding_post_code())) {
                foundErrors.add(new ErrorDto("Building post code cannot be null.", "TEX-400"));
            }
        }
        if (dto.getProperty_details() != null) {
            var property = dto.getProperty_details();
            if (isBlank(property.getProperty_business())) {
                foundErrors.add(new ErrorDto("Property business code cannot be null.", "TEX-400"));
            }
            if (isBlank(property.getProperty_content())) {
                foundErrors.add(new ErrorDto("Property content code cannot be null.", "TEX-400"));
            }
            if (isBlank(property.getProperty_content_value())) {
                foundErrors.add(new ErrorDto("Property Content value cannot be null.", "TEX-400"));
            }
            if (isBlank(property.getProperty_construction())) {
                foundErrors.add(new ErrorDto("Property construction cannot be null.", "TEX-400"));
            }
            if (isBlank(property.getProperty_construction_value())) {
                foundErrors.add(new ErrorDto("Property construction value cannot be null.", "TEX-400"));
            }
        }
        if (dto.getSum_insured() == null) {
            foundErrors.add(new ErrorDto("Sum insured value cannot be null.", "TEX-400"));
        }
        if (dto.getTotal_premium() == null) {
            foundErrors.add(new ErrorDto("Total premium value cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getEndorsements())) {
            foundErrors.add(new ErrorDto("Endorsements cannot be null.", "TEX-400"));
        }
        if (dto.getCommission_fee() == null) {
            foundErrors.add(new ErrorDto("Commission fee cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getConditions())) {
            foundErrors.add(new ErrorDto("Conditions cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getExclusions())) {
            foundErrors.add(new ErrorDto("Exclusions cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getExtra_fee())) {
            foundErrors.add(new ErrorDto("Extra Fee cannot be null.", "TEX-400"));
        }
        return foundErrors;
    }

    private ArrayList<ErrorDto> validateMarineDetails(NaicomPolicyDto dto) {
        ArrayList<ErrorDto> foundErrors = new ArrayList<>();
        if (dto.getCover_start_date() == null) {
            foundErrors.add(new ErrorDto("Cover start date cannot be null.", "TEX-400"));
        }
        if (dto.getCover_end_date() == null) {
            foundErrors.add(new ErrorDto("Cover end date cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_number())) {
            foundErrors.add(new ErrorDto("Policy number cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_description())) {
            foundErrors.add(new ErrorDto("Policy Description cannot be null.", "TEX-400"));
        }
        if (dto.getCustomer() != null) {
            var customer = dto.getCustomer();
            if (isBlank(customer.getName())) {
                foundErrors.add(new ErrorDto("Customer Name cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getEmail_address())) {
                foundErrors.add(new ErrorDto("Customer Email address cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getPhone_number())) {
                foundErrors.add(new ErrorDto("Customer Phone number cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getCity())) {
                foundErrors.add(new ErrorDto("Customer City cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getState())) {
                foundErrors.add(new ErrorDto("Customer State cannot be null.", "TEX-400"));
            }
            if (isBlank(customer.getAddress_code())) {
                foundErrors.add(new ErrorDto("Customer Address code cannot be null.", "TEX-400"));
            }
        }
        if (dto.getSum_insured() == null) {
            foundErrors.add(new ErrorDto("Sum insured value cannot be null.", "TEX-400"));
        }
        if (dto.getTotal_premium() == null) {
            foundErrors.add(new ErrorDto("Total premium value cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getEndorsements())) {
            foundErrors.add(new ErrorDto("Endorsements cannot be null.", "TEX-400"));
        }
        if (dto.getCommission_fee() == null) {
            foundErrors.add(new ErrorDto("Commission fee cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getConditions())) {
            foundErrors.add(new ErrorDto("Conditions cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getExclusions())) {
            foundErrors.add(new ErrorDto("Exclusions cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getExtra_fee())) {
            foundErrors.add(new ErrorDto("Extra Fee cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getMarine_cover_type())) {
            foundErrors.add(new ErrorDto("Marine cover type cannot be null.", "TEX-400"));
        }
        if (dto.getInsured_info() == null) {
            foundErrors.add(new ErrorDto("Marine Insured Information cannot be null", "TEX-400"));
            return foundErrors;
        }
        for (NaicomInsuredInfoDto insuredInfo : dto.getInsured_info()) {
            if (isBlank(insuredInfo.getVessel_name())) {
                foundErrors.add(new ErrorDto("Vessel name cannot be null.", "TEX-400"));
            }
            if (isBlank(insuredInfo.getVessel_reg_no())) {
                foundErrors.add(new ErrorDto("Vessel registration number cannot be null.", "TEX-400"));
            }
            if (isBlank(insuredInfo.getVessel_type())) {
                foundErrors.add(new ErrorDto("Vessel type cannot be null.", "TEX-400"));
            }
            if (insuredInfo.getVessel_insured_value() == null) {
                foundErrors.add(new ErrorDto("Vessel Insured value cannot be null.", "TEX-400"));
            }
            if (isBlank(insuredInfo.getVessel_build_year())) {
                foundErrors.add(new ErrorDto("Vessel build year cannot be null.", "TEX-400"));
            }
            if (isBlank(insuredInfo.getVessel_model())) {
                foundErrors.add(new ErrorDto("Vessel model cannot be null.", "TEX-400"));
            }
            if (isBlank(insuredInfo.getVessel_purchase_year())) {
                foundErrors.add(new ErrorDto("Vessel purchase year cannot be null.", "TEX-400"));
            }
            if (insuredInfo.getVessel_purchase_value() == null) {
                foundErrors.add(new ErrorDto("Vessel purchase value cannot be null.", "TEX-400"));
            }
            if (isBlank(insuredInfo.getVessel_usage())) {
                foundErrors.add(new ErrorDto("Vessel usage cannot be null.", "TEX-400"));
            }
            if (insuredInfo.getVessel_carriage_capacity() == null) {
                foundErrors.add(new ErrorDto("Vessel carriage capacity cannot be null.", "TEX-400"));
            }
            if (isBlank(insuredInfo.getVessel_note())) {
                foundErrors.add(new ErrorDto("Vessel note cannot be null.", "TEX-400"));
            }
        }
        return foundErrors;
    }

    private ArrayList<ErrorDto> validateCasualtyPolicyDetails(NaicomPolicyDto dto) {
        ArrayList<ErrorDto> foundErrors = new ArrayList<>();
        if (dto.getCover_start_date() == null) {
            foundErrors.add(new ErrorDto("Cover start date cannot be null.", "TEX-400"));
        }
        if (dto.getCover_end_date() == null) {
            foundErrors.add(new ErrorDto("Cover end date cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_number())) {
            foundErrors.add(new ErrorDto("Policy number cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_description())) {
            foundErrors.add(new ErrorDto("Policy Description cannot be null.", "TEX-400"));
        }
        if (dto.getAccident_info() != null) {
            var accident = dto.getAccident_info();
            if (accident.getAccident_cover_type() == null) {
                foundErrors.add(new ErrorDto("Accident cover type cannot be null.", "TEX-400"));
            }
            if (accident.getAccident_beneficiary_info() == null) {
                foundErrors.add(new ErrorDto("Accident beneficiary info cannot be null.", "TEX-400"));
            }
            if (accident.getAccident_insured_benefit() == null) {
                foundErrors.add(new ErrorDto("Accident insured benefit cannot be null.", "TEX-400"));
            }
            if (accident.getAccident_insured_persons() == null) {
                foundErrors.add(new ErrorDto("Accident insured persons cannot be null.", "TEX-400"));
            }
        }
        if (dto.getSum_insured() == null) {
            foundErrors.add(new ErrorDto("Sum insured value cannot be null.", "TEX-400"));
        }
        if (dto.getTotal_premium() == null) {
            foundErrors.add(new ErrorDto("Total premium value cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getEndorsements())) {
            foundErrors.add(new ErrorDto("Endorsements cannot be null.", "TEX-400"));
        }
        if (dto.getCommission_fee() == null) {
            foundErrors.add(new ErrorDto("Commission fee cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getConditions())) {
            foundErrors.add(new ErrorDto("Conditions cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getExclusions())) {
            foundErrors.add(new ErrorDto("Exclusions cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getExtra_fee())) {
            foundErrors.add(new ErrorDto("Extra Fee cannot be null.", "TEX-400"));
        }
        return foundErrors;
    }

    private ArrayList<ErrorDto> validateEngineerPolicy(NaicomPolicyDto dto) {
        ArrayList<ErrorDto> foundErrors = new ArrayList<>();
        if (dto.getCover_start_date() == null) {
            foundErrors.add(new ErrorDto("Cover start date cannot be null.", "TEX-400"));
        }
        if (dto.getCover_end_date() == null) {
            foundErrors.add(new ErrorDto("Cover end date cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_number())) {
            foundErrors.add(new ErrorDto("Policy number cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_description())) {
            foundErrors.add(new ErrorDto("Policy Description cannot be null.", "TEX-400"));
        }
        if (dto.getCustomer() == null) {
            foundErrors.add(new ErrorDto("Customer information cannot be null. Kindly update customer details to proceed.", "TEX-400"));
        }
        return foundErrors;
    }

    private ArrayList<ErrorDto> validateOilPolicy(NaicomPolicyDto dto) {
        ArrayList<ErrorDto> foundErrors = new ArrayList<>();
        if (dto.getCover_start_date() == null) {
            foundErrors.add(new ErrorDto("Cover start date cannot be null.", "TEX-400"));
        }
        if (dto.getCover_end_date() == null) {
            foundErrors.add(new ErrorDto("Cover end date cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_number())) {
            foundErrors.add(new ErrorDto("Policy number cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_description())) {
            foundErrors.add(new ErrorDto("Policy Description cannot be null.", "TEX-400"));
        }
        return foundErrors;
    }

    private ArrayList<ErrorDto> validateMiscellaneousPolicy(NaicomPolicyDto dto) {
        ArrayList<ErrorDto> foundErrors = new ArrayList<>();
        if (dto.getCover_start_date() == null) {
            foundErrors.add(new ErrorDto("Cover start date cannot be null.", "TEX-400"));
        }
        if (dto.getCover_end_date() == null) {
            foundErrors.add(new ErrorDto("Cover end date cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_number())) {
            foundErrors.add(new ErrorDto("Policy number cannot be null.", "TEX-400"));
        }
        if (isBlank(dto.getPolicy_description())) {
            foundErrors.add(new ErrorDto("Policy Description cannot be null.", "TEX-400"));
        }
        return foundErrors;
    }

    // -------------------------------------------------------------------------------
    // Wire-format group/attribute builders (AUTO) - ported verbatim
    // -------------------------------------------------------------------------------

    private List<GroupDto> getAutoGroupDtos(NaicomPolicyDto motorPolicyRequestDto) {
        List<AttributeDto> basicInfoAttList = getBasicAttributeDtos(motorPolicyRequestDto);
        List<AttributeDto> detailInfoList = getAutoDetailInfo(motorPolicyRequestDto);
        List<GroupDto> insuredGroupInfoList = getGroupInsuredInfo(motorPolicyRequestDto.getInsured_info());

        GroupDto basicInfo = GroupDto.builder().GroupName("Basic info").GroupTag(0).GroupCount(0).AttArray(basicInfoAttList).build();
        GroupDto detailInfo = GroupDto.builder().GroupName("Detail Info").GroupTag(1).GroupCount(0).AttArray(detailInfoList).build();

        List<GroupDto> groupDtoList = new ArrayList<>();
        groupDtoList.add(basicInfo);
        groupDtoList.add(detailInfo);
        groupDtoList.addAll(insuredGroupInfoList);
        return groupDtoList;
    }

    private List<AttributeDto> getBasicAttributeDtos(NaicomPolicyDto motorPolicyRequestDto) {
        SimpleDateFormat formatter = new SimpleDateFormat("M/dd/yyyy hh:mm aa");
        AttributeDto typeID = AttributeDto.builder().Name("TypeID").Value(motorPolicyRequestDto.getNaicom_product_code().toString()).build();
        AttributeDto recorderType = AttributeDto.builder().Name("RecorderType").Value(motorPolicyRequestDto.getRecorder_type()).build();
        AttributeDto recorderID = AttributeDto.builder().Name("RecorderID").Value(motorPolicyRequestDto.getRecorder_id()).build();
        AttributeDto coverStartDate = AttributeDto.builder().Name("CoverageStartDate").Value(formatter.format(motorPolicyRequestDto.getCover_start_date())).build();
        AttributeDto coverEndDate = AttributeDto.builder().Name("CoverageEndDate").Value(formatter.format(motorPolicyRequestDto.getCover_end_date())).build();
        String policyNumberValue = motorPolicyRequestDto.getPolicy_number() == null ? "n/a" : motorPolicyRequestDto.getPolicy_number();
        String policyDescriptionValue = motorPolicyRequestDto.getPolicy_description() == null ? "n/a" : motorPolicyRequestDto.getPolicy_description();
        AttributeDto policyNumber = AttributeDto.builder().Name("PolicyInternalID").Value(policyNumberValue).build();
        AttributeDto policyDescription = AttributeDto.builder().Name("PolicyDescription").Value(policyDescriptionValue).build();
        return Stream.of(typeID, coverStartDate, coverEndDate, policyNumber, policyDescription, recorderType, recorderID)
                .collect(Collectors.toList());
    }

    private List<AttributeDto> getAutoDetailInfo(NaicomPolicyDto motorPolicyRequestDto) {
        var customer = motorPolicyRequestDto.getCustomer();

        AttributeDto personLastName = AttributeDto.builder().build();
        AttributeDto personFirstName = AttributeDto.builder().build();
        AttributeDto ownerLicense = AttributeDto.builder().build();
        AttributeDto organizationType = AttributeDto.builder().build();
        AttributeDto organizationName = AttributeDto.builder().build();
        AttributeDto organizationID = AttributeDto.builder().build();

        AttributeDto coverageType = AttributeDto.builder().Name("CoverageType").Value(motorPolicyRequestDto.getCover_type().toString()).build();
        AttributeDto ownerType = AttributeDto.builder().Name("OwnerType").Value(customer.getType().getName()).build();

        if (customer.getType() == ClientType.PERSON) {
            personLastName = AttributeDto.builder().Name("PersonNameLast").Value(customer.getLast_name()).build();
            personFirstName = AttributeDto.builder().Name("PersonNameFirst").Value(customer.getFirst_name()).build();
            ownerLicense = AttributeDto.builder().Name("OwnerLicense").Value(customer.getLicense()).build();
            organizationType = AttributeDto.builder().Name("OrgType").Value("COMMERCIAL").build();
            organizationName = AttributeDto.builder().Name("OrgName").Value("n/a").build();
            organizationID = AttributeDto.builder().Name("OrgID").Value("n/a").build();
        }
        if (customer.getType() == ClientType.ORG) {
            organizationType = AttributeDto.builder().Name("OrgType").Value("COMMERCIAL").build();
            organizationName = AttributeDto.builder().Name("OrgName").Value(customer.getOrganization_name()).build();
            organizationID = AttributeDto.builder().Name("OrgID").Value(customer.getOrganization_id()).build();
            personLastName = AttributeDto.builder().Name("PersonNameLast").Value("n/a").build();
            personFirstName = AttributeDto.builder().Name("PersonNameFirst").Value(customer.getFirst_name()).build();
            ownerLicense = AttributeDto.builder().Name("OwnerLicense").Value("n/a").build();
        }

        AttributeDto addressLine = AttributeDto.builder().Name("AddressLine").Value(customer.getResidential_address()).build();
        AttributeDto city = AttributeDto.builder().Name("CityLGA").Value(customer.getCity()).build();
        AttributeDto state = AttributeDto.builder().Name("State").Value(customer.getState()).build();
        AttributeDto postCode = AttributeDto.builder().Name("PostCode").Value(customer.getPostal_address()).build();
        AttributeDto phone = AttributeDto.builder().Name("Phone").Value(customer.getPhone_number()).build();
        AttributeDto email = AttributeDto.builder().Name("Email").Value(customer.getEmail_address()).build();
        AttributeDto insuredValue = AttributeDto.builder().Name("InsuredValue").Value(motorPolicyRequestDto.getSum_insured().toString()).build();
        AttributeDto premium = AttributeDto.builder().Name("Premium").Value(motorPolicyRequestDto.getTotal_premium().toString()).build();
        AttributeDto commissionFee = AttributeDto.builder().Name("CommissionFee").Value(motorPolicyRequestDto.getCommission_fee().toString()).build();
        AttributeDto extraFee = AttributeDto.builder().Name("ExtraFee").Value(motorPolicyRequestDto.getExtra_fee()).build();
        AttributeDto premiumNote = AttributeDto.builder().Name("PremiumNote").Value(motorPolicyRequestDto.getPremium_note()).build();
        AttributeDto terms = AttributeDto.builder().Name("Terms").Value(motorPolicyRequestDto.getTerms()).build();
        AttributeDto preamble = AttributeDto.builder().Name("Preamble").Value(motorPolicyRequestDto.getPreamble()).build();
        AttributeDto endorsements = AttributeDto.builder().Name("Endorsements").Value(motorPolicyRequestDto.getEndorsements()).build();
        AttributeDto exclusions = AttributeDto.builder().Name("Exclusions").Value(motorPolicyRequestDto.getExclusions()).build();
        AttributeDto conditions = AttributeDto.builder().Name("Conditions").Value(motorPolicyRequestDto.getConditions()).build();

        return Stream.of(personLastName, personFirstName, ownerLicense, organizationType, organizationName, organizationID,
                        coverageType, ownerType, addressLine, state, postCode, phone, city, email, insuredValue, premium,
                        commissionFee, extraFee, premiumNote, terms, preamble, endorsements, exclusions, conditions)
                .collect(Collectors.toList());
    }

    private List<GroupDto> getGroupInsuredInfo(List<NaicomInsuredInfoDto> insuredInfoList) {
        SimpleDateFormat formatter = new SimpleDateFormat("MMMM dd, yyyy");
        List<GroupDto> groupList = new ArrayList<>();
        for (int i = 0; i < insuredInfoList.size(); i++) {
            NaicomInsuredInfoDto info = insuredInfoList.get(i);
            List<AttributeDto> insuredInfo = Stream.of(
                    AttributeDto.builder().Name("InsuredNo").Value(String.valueOf(i + 1)).build(),
                    AttributeDto.builder().Name("VehicleID").Value(info.getVehicle_id()).build(),
                    AttributeDto.builder().Name("PlateNo").Value(info.getPlate_no()).build(),
                    AttributeDto.builder().Name("RegNo").Value(info.getRegistration_number()).build(),
                    AttributeDto.builder().Name("RegDate").Value(formatter.format(info.getRegistration_date())).build(),
                    AttributeDto.builder().Name("RegExpDate").Value(formatter.format(info.getRegistration_expiry_date())).build(),
                    AttributeDto.builder().Name("RegMileage").Value(info.getVehicle_mileage().toString()).build(),
                    AttributeDto.builder().Name("AutoType").Value(info.getVehicle_type()).build(),
                    AttributeDto.builder().Name("AutoMake").Value(info.getVehicle_make()).build(),
                    AttributeDto.builder().Name("AutoModel").Value(info.getVehicle_model()).build(),
                    AttributeDto.builder().Name("AutoColor").Value(info.getVehicle_color()).build(),
                    AttributeDto.builder().Name("AutoYear").Value(info.getYear_of_manufacture()).build(),
                    AttributeDto.builder().Name("EngineCap").Value(info.getEngine_capacity()).build(),
                    AttributeDto.builder().Name("SeatCap").Value(String.valueOf(info.getSeats())).build(),
                    AttributeDto.builder().Name("AutoNote").Value(info.getAuto_note()).build()
            ).collect(Collectors.toList());

            groupList.add(GroupDto.builder().GroupName("Insured Info").GroupTag(2).GroupCount(i).AttArray(insuredInfo).build());
        }
        return groupList;
    }

    // -------------------------------------------------------------------------------
    // Wire-format group/attribute builders (other product lines) - ported from
    // NaicomIntegrationService's get*GroupDtos/get*DetailInfo/get*Attributes methods.
    // -------------------------------------------------------------------------------

    private List<GroupDto> basicAndDetailGroups(List<AttributeDto> basicInfo, List<AttributeDto> detailInfo) {
        GroupDto basic = GroupDto.builder().GroupName("Basic info").GroupTag(0).GroupCount(0).AttArray(basicInfo).build();
        GroupDto detail = GroupDto.builder().GroupName("Detail Info").GroupTag(1).GroupCount(0).AttArray(detailInfo).build();
        List<GroupDto> groups = new ArrayList<>();
        groups.add(basic);
        groups.add(detail);
        return groups;
    }

    private List<GroupDto> getBondGroupDtos(NaicomPolicyDto dto) {
        return basicAndDetailGroups(getBasicAttributeDtos(dto), getBondDetailInfo(dto));
    }

    private List<AttributeDto> getBondDetailInfo(NaicomPolicyDto dto) {
        var customer = dto.getCustomer();
        AttributeDto personLastName = AttributeDto.builder().build();
        AttributeDto personFirstName = AttributeDto.builder().build();
        AttributeDto personIdentificationDoc = AttributeDto.builder().build();
        AttributeDto personIDNO = AttributeDto.builder().build();
        AttributeDto organizationName = AttributeDto.builder().build();
        AttributeDto organizationID = AttributeDto.builder().build();
        AttributeDto organizationType = AttributeDto.builder().Name("OrgType").Value("COMMERCIAL").build();

        AttributeDto beneficiaryType = AttributeDto.builder().Name("BeneficiaryType").Value(customer.getBeneficiary_type().toString()).build();
        if (customer.getBeneficiary_type() == BondBeneficiaryType.INDIVIDUAL) {
            personLastName = AttributeDto.builder().Name("PersonNameLast").Value(customer.getLast_name()).build();
            personFirstName = AttributeDto.builder().Name("PersonNameFirst").Value(customer.getFirst_name()).build();
            personIdentificationDoc = AttributeDto.builder().Name("PersonIDDoc").Value(customer.getIdentification_type().toString()).build();
            personIDNO = AttributeDto.builder().Name("PersonIDNo").Value(customer.getIdentification_number()).build();
        }
        organizationName = AttributeDto.builder().Name("OrgName").Value(customer.getOrganization_name()).build();
        organizationID = AttributeDto.builder().Name("OrgID").Value(customer.getOrganization_id()).build();

        AttributeDto addressLine = AttributeDto.builder().Name("AddressLine").Value(customer.getResidential_address()).build();
        AttributeDto city = AttributeDto.builder().Name("CityLGA").Value(customer.getCity()).build();
        AttributeDto state = AttributeDto.builder().Name("State").Value(customer.getState()).build();
        AttributeDto postCode = AttributeDto.builder().Name("PostCode").Value(customer.getPostal_address()).build();
        AttributeDto phone = AttributeDto.builder().Name("Phone").Value(customer.getPhone_number()).build();
        AttributeDto email = AttributeDto.builder().Name("Email").Value(customer.getEmail_address()).build();
        AttributeDto contractLocation = AttributeDto.builder().Name("ContractLocation").Value(dto.getContract_location()).build();
        AttributeDto subContractInfo = AttributeDto.builder().Name("SubContractInfo").Value(dto.getSub_contract_info()).build();
        AttributeDto contractPrice = AttributeDto.builder().Name("ContractPrice").Value(dto.getContract_price().toString()).build();
        AttributeDto contractDescription = AttributeDto.builder().Name("ContractDescription").Value(dto.getContract_description()).build();
        AttributeDto bondPercentage = AttributeDto.builder().Name("BondPercentage").Value(dto.getBond_percentage().toString()).build();
        AttributeDto importerNumber = AttributeDto.builder().Name("ImporterNumber").Value(dto.getImporter_number()).build();
        AttributeDto merchandiseDescription = AttributeDto.builder().Name("MerchandiseDescription").Value(dto.getMerchandise_description()).build();
        AttributeDto merchandiseValue = AttributeDto.builder().Name("MerchandiseValue").Value(dto.getMerchandise_value()).build();
        AttributeDto dutyTax = AttributeDto.builder().Name("DutyTax").Value(dto.getTax_duty().toString()).build();
        AttributeDto countryOrigin = AttributeDto.builder().Name("CountryOrigin").Value(dto.getOrigin_country()).build();
        AttributeDto portEntry = AttributeDto.builder().Name("PortEntry").Value(dto.getEntry_port()).build();
        AttributeDto courtName = AttributeDto.builder().Name("CourtName").Value(dto.getCourt_name()).build();
        AttributeDto courtCaseNo = AttributeDto.builder().Name("CourtCaseNo").Value(dto.getCourt_case_no()).build();
        AttributeDto bondPaid = AttributeDto.builder().Name("BondPaid").Value(dto.getBond_paid().toString()).build();
        AttributeDto bondAmount = AttributeDto.builder().Name("BondAmount").Value(dto.getBond_amount().toString()).build();
        AttributeDto bondDescription = AttributeDto.builder().Name("BondDescription").Value(dto.getBond_description()).build();
        AttributeDto premium = AttributeDto.builder().Name("Premium").Value(dto.getTotal_premium().toString()).build();

        return Stream.of(personLastName, personFirstName, personIdentificationDoc, organizationType, organizationName,
                        organizationID, personIDNO, bondDescription, addressLine, state, postCode, phone, city, email,
                        bondAmount, premium, bondPaid, courtCaseNo, courtName, portEntry, countryOrigin, dutyTax,
                        contractLocation, merchandiseDescription, merchandiseValue, importerNumber, bondPercentage,
                        contractDescription, contractPrice, subContractInfo, beneficiaryType)
                .collect(Collectors.toList());
    }

    private List<GroupDto> getFireGroupDtos(NaicomPolicyDto dto) {
        return basicAndDetailGroups(getBasicAttributeDtos(dto), getFireDetailInfo(dto));
    }

    private List<AttributeDto> getFireDetailInfo(NaicomPolicyDto dto) {
        var customer = dto.getCustomer();
        var building = dto.getBuilding_details();
        var property = dto.getProperty_details();
        var fire = dto.getFire_details();
        var burglary = dto.getBurglary_details();
        var house = dto.getHouse_details();

        AttributeDto customerName = AttributeDto.builder().Name("CustomerName").Value(customer.getName()).build();
        AttributeDto customerEmail = AttributeDto.builder().Name("CustomerEmail").Value(customer.getEmail_address()).build();
        AttributeDto customerPhone = AttributeDto.builder().Name("CustomerPhone").Value(customer.getPhone_number()).build();
        AttributeDto buildingDoorNo = AttributeDto.builder().Name("CustomerBuildingDoorNo").Value(building.getBuilding_door_number()).build();
        AttributeDto buildingName = AttributeDto.builder().Name("CustomerBuildingName").Value(building.getBuilding_name()).build();
        AttributeDto buildingAddressLine = AttributeDto.builder().Name("CustomerBuildingAddressLine").Value(building.getBuilding_address_line()).build();
        AttributeDto buildingCity = AttributeDto.builder().Name("CustomerBuildingAddressCityLGA").Value(building.getBuilding_city()).build();
        AttributeDto buildingState = AttributeDto.builder().Name("CustomerBuildingAddressState").Value(building.getBuilding_state()).build();
        AttributeDto buildingPostCode = AttributeDto.builder().Name("CustomerBuildingAddressPostCode").Value(building.getBuilding_post_code()).build();
        AttributeDto propertyBusiness = AttributeDto.builder().Name("PropertyBusiness").Value(property.getProperty_business()).build();
        AttributeDto propertyConstruction = AttributeDto.builder().Name("PropertyConstruction").Value(property.getProperty_construction()).build();
        AttributeDto propertyConstructionValue = AttributeDto.builder().Name("PropertyConstructionValue").Value(property.getProperty_construction_value()).build();
        AttributeDto propertyContent = AttributeDto.builder().Name("PropertyContent").Value(property.getProperty_content()).build();
        AttributeDto propertyContentValue = AttributeDto.builder().Name("PropertyContentValue").Value(property.getProperty_content_value()).build();
        AttributeDto fireCoverageDetail = AttributeDto.builder().Name("FireCoverageDetail").Value(fire.getFire_coverage_detail()).build();
        AttributeDto fireProtectionDetail = AttributeDto.builder().Name("FireProtectionDetail").Value(fire.getFire_protection_detail()).build();
        AttributeDto fireHistory = AttributeDto.builder().Name("FireHistory").Value(fire.getFire_history()).build();
        AttributeDto burglaryCoverageDetail = AttributeDto.builder().Name("BurglaryCoverageDetail").Value(burglary.getBurglary_coverage_detail()).build();
        AttributeDto burglaryAntiTheftDetail = AttributeDto.builder().Name("BurglaryAntiTheftDetail").Value(burglary.getBurglary_anti_theft()).build();
        AttributeDto burglaryHistory = AttributeDto.builder().Name("BurglaryHistory").Value(burglary.getBurglary_history()).build();
        AttributeDto houseBuildingType = AttributeDto.builder().Name("HouseBuildingType").Value(house.getHouse_building_type()).build();
        AttributeDto houseCoverageDetail = AttributeDto.builder().Name("HouseCoverageDetail").Value(house.getHouse_coverage_detail()).build();
        AttributeDto houseSecurityDetail = AttributeDto.builder().Name("HouseSecurityDetail").Value(house.getHouse_security_detail()).build();
        AttributeDto houseHistory = AttributeDto.builder().Name("HouseHistory").Value(house.getHouse_history()).build();
        AttributeDto insuredValue = AttributeDto.builder().Name("InsuredValue").Value(dto.getSum_insured().toString()).build();
        AttributeDto premium = AttributeDto.builder().Name("Premium").Value(dto.getTotal_premium().toString()).build();
        AttributeDto commissionFee = AttributeDto.builder().Name("CommissionFee").Value(dto.getCommission_fee().toString()).build();
        AttributeDto extraFee = AttributeDto.builder().Name("ExtraFee").Value(dto.getExtra_fee()).build();
        AttributeDto premiumNote = AttributeDto.builder().Name("PremiumNote").Value(dto.getPremium_note()).build();
        AttributeDto terms = AttributeDto.builder().Name("Terms").Value(dto.getTerms()).build();
        AttributeDto preamble = AttributeDto.builder().Name("Preamble").Value(dto.getPreamble()).build();
        AttributeDto endorsements = AttributeDto.builder().Name("Endorsements").Value(dto.getEndorsements()).build();
        AttributeDto exclusions = AttributeDto.builder().Name("Exclusions").Value(dto.getExclusions()).build();
        AttributeDto exceptions = AttributeDto.builder().Name("Exceptions").Value(dto.getExceptions()).build();
        AttributeDto conditions = AttributeDto.builder().Name("Conditions").Value(dto.getConditions()).build();

        return Stream.of(conditions, exceptions, exclusions, endorsements, preamble, terms, premium, premiumNote,
                        extraFee, commissionFee, insuredValue, houseBuildingType, houseHistory, houseCoverageDetail,
                        houseSecurityDetail, buildingAddressLine, burglaryAntiTheftDetail, burglaryCoverageDetail,
                        burglaryHistory, fireCoverageDetail, fireProtectionDetail, fireHistory, propertyBusiness,
                        propertyConstructionValue, propertyContent, propertyContentValue, propertyConstruction,
                        buildingCity, buildingDoorNo, buildingState, buildingPostCode, buildingName, customerName,
                        customerEmail, customerPhone)
                .collect(Collectors.toList());
    }

    private List<GroupDto> getMarineGroupDtos(NaicomPolicyDto dto) {
        List<GroupDto> groups = basicAndDetailGroups(getBasicAttributeDtos(dto), getMarineDetailInfo(dto));
        groups.addAll(getMarineGroupInsuredInfo(dto.getInsured_info()));
        return groups;
    }

    private List<AttributeDto> getMarineDetailInfo(NaicomPolicyDto dto) {
        var customer = dto.getCustomer();
        AttributeDto customerName = AttributeDto.builder().Name("OwnerName").Value(customer.getName()).build();
        AttributeDto addressLine = AttributeDto.builder().Name("AddressLine").Value(customer.getPostal_address()).build();
        AttributeDto coverageType = AttributeDto.builder().Name("CoverageType").Value(dto.getMarine_cover_type()).build();
        AttributeDto city = AttributeDto.builder().Name("CityLGA").Value(customer.getCity()).build();
        AttributeDto state = AttributeDto.builder().Name("State").Value(customer.getState()).build();
        AttributeDto postCode = AttributeDto.builder().Name("PostCode").Value(customer.getAddress_code()).build();
        AttributeDto phoneNo = AttributeDto.builder().Name("Phone").Value(customer.getPhone_number()).build();
        AttributeDto email = AttributeDto.builder().Name("Email").Value(customer.getEmail_address()).build();
        AttributeDto insuredValue = AttributeDto.builder().Name("InsuredValue").Value(dto.getSum_insured().toString()).build();
        AttributeDto premium = AttributeDto.builder().Name("Premium").Value(dto.getTotal_premium().toString()).build();
        AttributeDto commissionFee = AttributeDto.builder().Name("CommissionFee").Value(dto.getCommission_fee().toString()).build();
        AttributeDto extraFee = AttributeDto.builder().Name("ExtraFee").Value(dto.getExtra_fee()).build();
        AttributeDto premiumNote = AttributeDto.builder().Name("PremiumNote").Value(dto.getPremium_note()).build();
        AttributeDto terms = AttributeDto.builder().Name("Terms").Value(dto.getTerms()).build();
        AttributeDto preamble = AttributeDto.builder().Name("Preamble").Value(dto.getPreamble()).build();
        AttributeDto endorsements = AttributeDto.builder().Name("Endorsements").Value(dto.getEndorsements()).build();
        AttributeDto exclusions = AttributeDto.builder().Name("Exclusions").Value(dto.getExclusions()).build();
        AttributeDto exceptions = AttributeDto.builder().Name("Exceptions").Value(dto.getExceptions()).build();
        AttributeDto conditions = AttributeDto.builder().Name("Conditions").Value(dto.getConditions()).build();

        return Stream.of(conditions, exceptions, exclusions, endorsements, preamble, terms, premium, premiumNote,
                        extraFee, commissionFee, insuredValue, addressLine, state, postCode, phoneNo, city,
                        coverageType, email, customerName)
                .collect(Collectors.toList());
    }

    private List<GroupDto> getMarineGroupInsuredInfo(List<NaicomInsuredInfoDto> insuredInfoList) {
        List<GroupDto> groupList = new ArrayList<>();
        for (int i = 0; i < insuredInfoList.size(); i++) {
            NaicomInsuredInfoDto info = insuredInfoList.get(i);
            List<AttributeDto> insuredInfo = Stream.of(
                    AttributeDto.builder().Name("InsuredNo").Value(String.valueOf(i + 1)).build(),
                    AttributeDto.builder().Name("VessleName").Value(info.getVessel_name()).build(),
                    AttributeDto.builder().Name("VessleRegNo").Value(info.getVessel_reg_no()).build(),
                    AttributeDto.builder().Name("VessleType").Value(info.getVessel_type()).build(),
                    AttributeDto.builder().Name("VessleInsuredValue").Value(info.getVessel_insured_value().toString()).build(),
                    AttributeDto.builder().Name("VessleBuildYear").Value(info.getVessel_build_year()).build(),
                    AttributeDto.builder().Name("VessleMakeModel").Value(info.getVessel_model()).build(),
                    AttributeDto.builder().Name("VesslePurchaseYear").Value(info.getVessel_purchase_year()).build(),
                    AttributeDto.builder().Name("VesslePurchaseValue").Value(info.getVessel_purchase_value() == null ? "0" : info.getVessel_purchase_value().toString()).build(),
                    AttributeDto.builder().Name("VessleUsage").Value(info.getVessel_usage()).build(),
                    AttributeDto.builder().Name("VessleCapacityPassenger").Value(info.getVessel_carriage_capacity() == null ? "0" : info.getVessel_carriage_capacity().toString()).build(),
                    AttributeDto.builder().Name("VessleNote").Value(info.getVessel_note()).build()
            ).collect(Collectors.toList());
            groupList.add(GroupDto.builder().GroupName("Insured Info").GroupTag(2).GroupCount(i).AttArray(insuredInfo).build());
        }
        return groupList;
    }

    private List<GroupDto> getCasualtyGroupDtos(NaicomPolicyDto dto) {
        return basicAndDetailGroups(getBasicAttributeDtos(dto), getCasualtyDetailInfo(dto));
    }

    private List<AttributeDto> getCasualtyDetailInfo(NaicomPolicyDto dto) {
        var customer = dto.getCustomer();
        var premises = dto.getPremise_details();
        var accident = dto.getAccident_info();
        SimpleDateFormat formatter = new SimpleDateFormat("MMMM dd, yyyy");

        AttributeDto customerName = AttributeDto.builder().Name("PersonalName").Value(customer.getName()).build();
        AttributeDto addressLine = AttributeDto.builder().Name("PersonalAddress").Value(customer.getPostal_address()).build();
        AttributeDto email = AttributeDto.builder().Name("ContactEmail").Value(customer.getEmail_address()).build();
        AttributeDto phoneNo = AttributeDto.builder().Name("ContactPhone").Value(customer.getPhone_number()).build();
        AttributeDto title = AttributeDto.builder().Name("PersonalSpecialisation").Value(customer.getTitle()).build();
        AttributeDto gender = AttributeDto.builder().Name("PersonalSex").Value(customer.getGender().name()).build();
        AttributeDto personalDateBirth = AttributeDto.builder().Name("PersonalDateBirth").Value(formatter.format(customer.getDate_of_birth())).build();
        AttributeDto premiseName = AttributeDto.builder().Name("PremisesName").Value(premises.getPremises_name()).build();
        AttributeDto location = AttributeDto.builder().Name("PremisesLocation").Value(premises.getPremises_location()).build();
        AttributeDto premisesOccupation = AttributeDto.builder().Name("PremisesOccupation").Value(premises.getPremises_occupation()).build();
        AttributeDto businessType = AttributeDto.builder().Name("PremisesBusinessType").Value(premises.getPremises_business_type()).build();
        AttributeDto businessHour = AttributeDto.builder().Name("PremisesBusinessHour").Value(premises.getPremises_business_hour()).build();
        AttributeDto transitSchedule = AttributeDto.builder().Name("TransitSchedule").Value(dto.getTransit_schedule()).build();
        AttributeDto transitRoute = AttributeDto.builder().Name("TransitRoute").Value(dto.getTransit_route()).build();
        AttributeDto safeDetails = AttributeDto.builder().Name("SafeStrongRoomDetail").Value(dto.getSafe_strong_room_details()).build();
        AttributeDto guardDetails = AttributeDto.builder().Name("SafeGuardInfo").Value(dto.getSafe_guard_info()).build();
        AttributeDto propertyInfo = AttributeDto.builder().Name("GoodPropertyInfo").Value(dto.getProperty_info()).build();
        AttributeDto goodTransitMethod = AttributeDto.builder().Name("GoodTransitMethod").Value(dto.getGood_transit_method()).build();
        AttributeDto transitVehicleInfo = AttributeDto.builder().Name("GoodTransitVehicleInfo").Value(dto.getTransit_vehicle_info()).build();
        AttributeDto workDescription = AttributeDto.builder().Name("WorkDescription").Value(dto.getWork_description()).build();
        AttributeDto workLocation = AttributeDto.builder().Name("WorkLocation").Value(dto.getWork_location()).build();
        AttributeDto workEmployeeInfo = AttributeDto.builder().Name("WorkEmployeeInfo").Value(dto.getWork_employee_details()).build();
        AttributeDto companyInfo = AttributeDto.builder().Name("CompanyInfo").Value(dto.getCompany_info()).build();
        AttributeDto companyDirectorInfo = AttributeDto.builder().Name("CompanyDirectorInfo").Value(dto.getCompany_director_info()).build();
        AttributeDto professionalActivityInfo = AttributeDto.builder().Name("ProfessionalActivityInfo").Value(dto.getProfessional_activity_info()).build();
        AttributeDto professionalEmployeeInfo = AttributeDto.builder().Name("ProfessionalEmployeeInfo").Value(dto.getProfessional_employee_info()).build();
        AttributeDto accidentCoverType = AttributeDto.builder().Name("AccidentCoverType").Value(accident.getAccident_cover_type()).build();
        AttributeDto accidentBeneficiaryInfo = AttributeDto.builder().Name("AccidentBeneficiaryInfo").Value(accident.getAccident_beneficiary_info()).build();
        AttributeDto insuredBenefit = AttributeDto.builder().Name("AccidentInsuredBenefit").Value(accident.getAccident_insured_benefit()).build();
        AttributeDto accidentInsuredPersons = AttributeDto.builder().Name("AccidentInsuredPersons").Value(accident.getAccident_insured_persons()).build();
        AttributeDto accountInfo = AttributeDto.builder().Name("AccountInfo").Value(dto.getAccount_info()).build();
        AttributeDto employeeInfo = AttributeDto.builder().Name("EmployeeInfo").Value(dto.getEmployee_info()).build();
        AttributeDto publicRiskInfo = AttributeDto.builder().Name("PublicRiskInfo").Value(dto.getPublic_risk_info()).build();
        AttributeDto workCondition = AttributeDto.builder().Name("WorkCondition").Value(dto.getWork_condition()).build();
        AttributeDto estimatedWages = AttributeDto.builder().Name("EstimatedWages").Value(dto.getEstimated_wages()).build();
        AttributeDto estimatedEarnings = AttributeDto.builder().Name("EstimatedEarnings").Value(dto.getEstimated_earnings()).build();
        AttributeDto insuredValue = AttributeDto.builder().Name("InsuredValue").Value(dto.getSum_insured().toString()).build();
        AttributeDto otherConditions = AttributeDto.builder().Name("OtherConditions").Value(dto.getOther_conditions()).build();
        AttributeDto riskManagementDetail = AttributeDto.builder().Name("RiskManagementDetail").Value(dto.getRisk_management_detail()).build();
        AttributeDto premium = AttributeDto.builder().Name("Premium").Value(dto.getTotal_premium().toString()).build();
        AttributeDto commissionFee = AttributeDto.builder().Name("CommissionFee").Value(dto.getCommission_fee().toString()).build();
        AttributeDto extraFee = AttributeDto.builder().Name("ExtraFee").Value(dto.getExtra_fee()).build();
        AttributeDto premiumNote = AttributeDto.builder().Name("PremiumNote").Value(dto.getPremium_note()).build();
        AttributeDto terms = AttributeDto.builder().Name("Terms").Value(dto.getTerms()).build();
        AttributeDto preamble = AttributeDto.builder().Name("Preamble").Value(dto.getPreamble()).build();
        AttributeDto endorsements = AttributeDto.builder().Name("Endorsements").Value(dto.getEndorsements()).build();
        AttributeDto exclusions = AttributeDto.builder().Name("Exclusions").Value(dto.getExclusions()).build();
        AttributeDto exceptions = AttributeDto.builder().Name("Exceptions").Value(dto.getExceptions()).build();
        AttributeDto conditions = AttributeDto.builder().Name("Conditions").Value(dto.getConditions()).build();

        return Stream.of(conditions, exceptions, exclusions, endorsements, preamble, terms, premium, premiumNote,
                        extraFee, commissionFee, insuredValue, addressLine, riskManagementDetail, otherConditions,
                        phoneNo, estimatedEarnings, estimatedWages, workCondition, workDescription, workEmployeeInfo,
                        publicRiskInfo, employeeInfo, accidentBeneficiaryInfo, insuredBenefit, accountInfo,
                        accidentInsuredPersons, companyInfo, accidentCoverType, workLocation, professionalActivityInfo,
                        professionalEmployeeInfo, gender, title, location, companyDirectorInfo, transitRoute,
                        transitSchedule, transitVehicleInfo, goodTransitMethod, propertyInfo, guardDetails,
                        safeDetails, businessHour, businessType, premisesOccupation, premiseName, personalDateBirth,
                        email, customerName)
                .collect(Collectors.toList());
    }

    private List<GroupDto> getEngineerGroupDtos(NaicomPolicyDto dto) {
        return basicAndDetailGroups(getBasicAttributeDtos(dto), getEngineerDetailInfo(dto));
    }

    private List<AttributeDto> getEngineerDetailInfo(NaicomPolicyDto dto) {
        var customer = dto.getCustomer();
        AttributeDto customerName = AttributeDto.builder().Name("PrincipalName").Value(customer.getName()).build();
        AttributeDto addressLine = AttributeDto.builder().Name("PrincipalAddressLine").Value(customer.getPostal_address()).build();
        AttributeDto city = AttributeDto.builder().Name("PrincipalCityLGA").Value(customer.getCity()).build();
        AttributeDto state = AttributeDto.builder().Name("PrincipalState").Value(customer.getState()).build();
        AttributeDto postCode = AttributeDto.builder().Name("PrincipalPostCode").Value(customer.getAddress_code()).build();
        AttributeDto contractorName = AttributeDto.builder().Name("ContractorName").Value(dto.getContractor_name()).build();
        AttributeDto contractorAddressLine = AttributeDto.builder().Name("ContractorAddressLine").Value(dto.getContractor_address_line()).build();
        AttributeDto contractorCityLGA = AttributeDto.builder().Name("ContractorCityLGA").Value(dto.getContractor_city()).build();
        AttributeDto contractorState = AttributeDto.builder().Name("ContractorState").Value(dto.getContractor_state()).build();
        AttributeDto contractorPostCode = AttributeDto.builder().Name("ContractorPostCode").Value(dto.getPost_code()).build();
        AttributeDto contractSite = AttributeDto.builder().Name("ContractSite").Value(dto.getContract_site()).build();
        AttributeDto plantAllRiskIsEquipmentCovered = AttributeDto.builder().Name("PlantAllRiskIsEquipmentCovered").Value(String.valueOf(dto.getPlant_equipment_covered().isStatus())).build();
        AttributeDto machineryDescription = AttributeDto.builder().Name("MachineryDescription").Value(dto.getMachinery_description()).build();
        AttributeDto machineryYearMfg = AttributeDto.builder().Name("MachineryYearMfg").Value(dto.getMachinery_year_of_manufacture()).build();
        AttributeDto machineryModel = AttributeDto.builder().Name("MachineryModel").Value(dto.getMachinery_model()).build();
        AttributeDto machinerySerialNo = AttributeDto.builder().Name("MachinerySerialNo").Value(dto.getSerial_number()).build();
        AttributeDto isLeased = AttributeDto.builder().Name("MachineryIsLeased").Value(String.valueOf(dto.getIs_machinery_leased().isStatus())).build();
        AttributeDto machineryConditions = AttributeDto.builder().Name("MachineryConditions").Value(dto.getMachinery_conditions()).build();
        AttributeDto stockInfo = AttributeDto.builder().Name("DeteriorationStockInfo").Value("n/a").build();
        AttributeDto deteriorationStockGoodsDescription = AttributeDto.builder().Name("DeteriorationStockGoodsDescription").Value("n/a").build();
        AttributeDto deteriorationStockGoodsValue = AttributeDto.builder().Name("DeteriorationStockGoodsValue").Value("0").build();
        AttributeDto deteriorationStockColdRoomDescription = AttributeDto.builder().Name("DeteriorationStockColdRoomDescription").Value("n/a").build();
        AttributeDto deteriorationStockMachineryDetail = AttributeDto.builder().Name("DeteriorationStockMachineryDetail").Value("n/a").build();
        AttributeDto deteriorationStockMachineryValue = AttributeDto.builder().Name("DeteriorationStockMachineryValue").Value("n/a").build();
        AttributeDto publicLiabilityPremisesSituation = AttributeDto.builder().Name("PublicLiabilityPremisesSituation").Value("n/a").build();
        AttributeDto publicLiabilityPremisesOccupation = AttributeDto.builder().Name("PublicLiabilityPremisesOccupation").Value("n/a").build();
        AttributeDto insuredValue = AttributeDto.builder().Name("InsuredValue").Value(dto.getSum_insured().toString()).build();
        AttributeDto premium = AttributeDto.builder().Name("Premium").Value(dto.getTotal_premium().toString()).build();
        AttributeDto commissionFee = AttributeDto.builder().Name("CommissionFee").Value(dto.getCommission_fee().toString()).build();
        AttributeDto maximumLoss = AttributeDto.builder().Name("EstimatedMaximumLoss").Value(dto.getEstimated_maximum_loss()).build();
        AttributeDto deductibleValue = AttributeDto.builder().Name("DeductibleValue").Value(dto.getDeductible_value()).build();
        AttributeDto subContractorInfo = AttributeDto.builder().Name("SubContractorInfo").Value("n/a").build();
        AttributeDto consultingEngineerInfo = AttributeDto.builder().Name("ConsultingEngineerInfo").Value("n/a").build();
        AttributeDto riskProjectName = AttributeDto.builder().Name("ContractorAllRiskProjectName").Value("n/a").build();
        AttributeDto contractorAllRiskProjectDetail = AttributeDto.builder().Name("ContractorAllRiskProjectDetail").Value("n/a").build();
        AttributeDto erectionAllRiskPlantType = AttributeDto.builder().Name("ErectionAllRiskPlantType").Value("n/a").build();
        AttributeDto erectionAllRiskPlantDescription = AttributeDto.builder().Name("ErectionAllRiskPlantDescription").Value("n/a").build();
        AttributeDto plantAllRiskMainItemManufacture = AttributeDto.builder().Name("PlantAllRiskMainItemManufacture").Value("n/a").build();
        AttributeDto tankDescription = AttributeDto.builder().Name("TankDescription").Value("n/a").build();
        AttributeDto tankLocation = AttributeDto.builder().Name("TankLocation").Value("n/a").build();
        AttributeDto tankSubstance = AttributeDto.builder().Name("TankSubstance").Value("n/a").build();
        AttributeDto tankRepairUpgradeHistory = AttributeDto.builder().Name("TankRepairUpgradeHistory").Value("n/a").build();
        AttributeDto lossProfitProductionProcess = AttributeDto.builder().Name("LossProfitProductionProcess").Value("n/a").build();
        AttributeDto lossProfitFacilityInfo = AttributeDto.builder().Name("LossProfitFacilityInfo").Value("n/a").build();
        AttributeDto lossProfitSupplyInfo = AttributeDto.builder().Name("LossProfitSupplyInfo").Value("n/a").build();
        AttributeDto lossProfitStockInfo = AttributeDto.builder().Name("LossProfitStockInfo").Value("n/a").build();
        AttributeDto lossProfitMachineryDamageCost = AttributeDto.builder().Name("LossProfitMachineryDamageCost").Value("0").build();
        AttributeDto otherConditions = AttributeDto.builder().Name("OtherConditions").Value("n/a").build();
        AttributeDto specialRisks = AttributeDto.builder().Name("SpecialRisks").Value("n/a").build();
        AttributeDto extraFee = AttributeDto.builder().Name("ExtraFee").Value("0").build();
        AttributeDto premiumNote = AttributeDto.builder().Name("PremiumNote").Value("n/a").build();
        AttributeDto terms = AttributeDto.builder().Name("Terms").Value("n/a").build();
        AttributeDto preamble = AttributeDto.builder().Name("Preamble").Value("n/a").build();
        AttributeDto endorsements = AttributeDto.builder().Name("Endorsements").Value("n/a").build();
        AttributeDto exclusions = AttributeDto.builder().Name("Exclusions").Value("n/a").build();
        AttributeDto exceptions = AttributeDto.builder().Name("Exceptions").Value("n/a").build();
        AttributeDto conditions = AttributeDto.builder().Name("Conditions").Value("n/a").build();

        return Stream.of(conditions, exceptions, exclusions, endorsements, preamble, terms, premium, premiumNote,
                        extraFee, commissionFee, insuredValue, addressLine, otherConditions, contractorCityLGA,
                        consultingEngineerInfo, contractorName, contractorAddressLine, specialRisks, postCode,
                        contractSite, customerName, deductibleValue, deteriorationStockColdRoomDescription,
                        deteriorationStockGoodsValue, lossProfitFacilityInfo, lossProfitProductionProcess,
                        erectionAllRiskPlantDescription, erectionAllRiskPlantType, riskProjectName,
                        lossProfitStockInfo, lossProfitSupplyInfo, maximumLoss, tankDescription, tankLocation,
                        tankSubstance, tankRepairUpgradeHistory, publicLiabilityPremisesOccupation,
                        machineryConditions, isLeased, machineryModel, machineryYearMfg, machinerySerialNo,
                        machineryDescription, state, plantAllRiskIsEquipmentCovered, plantAllRiskMainItemManufacture,
                        contractorAllRiskProjectDetail, stockInfo, subContractorInfo,
                        deteriorationStockGoodsDescription, deteriorationStockMachineryDetail,
                        deteriorationStockMachineryValue, contractorState, contractorPostCode,
                        publicLiabilityPremisesSituation, lossProfitMachineryDamageCost, city)
                .collect(Collectors.toList());
    }

    private List<GroupDto> getOilGroupDtos(NaicomPolicyDto dto) {
        return basicAndDetailGroups(getBasicAttributeDtos(dto), getOilDetailInfo(dto));
    }

    private List<AttributeDto> getOilDetailInfo(NaicomPolicyDto dto) {
        var customer = dto.getCustomer();
        AttributeDto customerName = AttributeDto.builder().Name("PrincipalName").Value(customer.getName()).build();
        AttributeDto addressLine = AttributeDto.builder().Name("PrincipalAddressLine").Value(customer.getPostal_address()).build();
        AttributeDto city = AttributeDto.builder().Name("PrincipalAddressCityLGA").Value(customer.getCity()).build();
        AttributeDto state = AttributeDto.builder().Name("PrincipalAddressState").Value(customer.getState()).build();
        AttributeDto postCode = AttributeDto.builder().Name("PrincipalAddressPostCode").Value(customer.getAddress_code()).build();
        AttributeDto contractorName = AttributeDto.builder().Name("ContractorName").Value(dto.getContractor_name()).build();
        AttributeDto contractorAddressLine = AttributeDto.builder().Name("ContractorAddressLine").Value(dto.getContractor_address_line()).build();
        AttributeDto contractorCityLGA = AttributeDto.builder().Name("ContractorAddressCityLGA").Value(dto.getContractor_city()).build();
        AttributeDto contractorState = AttributeDto.builder().Name("ContractorAddressState").Value(dto.getContractor_state()).build();
        AttributeDto contractorPostCode = AttributeDto.builder().Name("ContractorAddressPostCode").Value(dto.getPost_code()).build();
        AttributeDto subContractorInfo = AttributeDto.builder().Name("SubContractorInfo").Value("n/a").build();
        AttributeDto consultingInfo = AttributeDto.builder().Name("ConsultingInfo").Value("n/a").build();
        AttributeDto contractSite = AttributeDto.builder().Name("ContractSite").Value("n/a").build();
        AttributeDto projectName = AttributeDto.builder().Name("ProjectName").Value(dto.getProject_name()).build();
        AttributeDto projectDetail = AttributeDto.builder().Name("ProjectDetail").Value(dto.getProject_detail()).build();
        AttributeDto plantType = AttributeDto.builder().Name("PlantType").Value(dto.getPlant_type()).build();
        AttributeDto plantDescription = AttributeDto.builder().Name("PlantDescription").Value("n/a").build();
        AttributeDto machineryDescription = AttributeDto.builder().Name("MachineryDescription").Value("n/a").build();
        AttributeDto sumInsured = AttributeDto.builder().Name("InsuredValue").Value(dto.getSum_insured().toString()).build();
        AttributeDto otherConditions = AttributeDto.builder().Name("OtherConditions").Value("n/a").build();
        AttributeDto specialRisks = AttributeDto.builder().Name("SpecialRisks").Value("n/a").build();
        AttributeDto premium = AttributeDto.builder().Name("Premium").Value(dto.getTotal_premium().toString()).build();
        AttributeDto commissionFee = AttributeDto.builder().Name("CommissionFee").Value(dto.getCommission_fee().toString()).build();
        AttributeDto extraFee = AttributeDto.builder().Name("ExtraFee").Value("0").build();
        AttributeDto premiumNote = AttributeDto.builder().Name("PremiumNote").Value("n/a").build();
        AttributeDto terms = AttributeDto.builder().Name("Terms").Value("n/a").build();
        AttributeDto preamble = AttributeDto.builder().Name("Preamble").Value("n/a").build();
        AttributeDto endorsements = AttributeDto.builder().Name("Endorsements").Value("n/a").build();
        AttributeDto exclusions = AttributeDto.builder().Name("Exclusions").Value("n/a").build();
        AttributeDto exceptions = AttributeDto.builder().Name("Exceptions").Value("n/a").build();
        AttributeDto conditions = AttributeDto.builder().Name("Conditions").Value("n/a").build();

        return Stream.of(conditions, exceptions, exclusions, endorsements, preamble, terms, premium, premiumNote,
                        extraFee, commissionFee, addressLine, otherConditions, contractorCityLGA, contractorName,
                        contractorAddressLine, specialRisks, postCode, contractSite, customerName, plantDescription,
                        plantType, projectName, projectDetail, state, subContractorInfo, contractorState,
                        contractorPostCode, city, consultingInfo, machineryDescription, sumInsured)
                .collect(Collectors.toList());
    }

    private List<GroupDto> getMiscellaneousGroupDtos(NaicomPolicyDto dto) {
        return basicAndDetailGroups(getBasicAttributeDtos(dto), getMiscellaneousDetailInfo(dto));
    }

    private List<AttributeDto> getMiscellaneousDetailInfo(NaicomPolicyDto dto) {
        var customer = dto.getCustomer();
        AttributeDto holderType = AttributeDto.builder().Name("HolderType").Value(customer.getType().toString()).build();
        AttributeDto personLastName = AttributeDto.builder().Name("PersonNameLast").Value(customer.getLast_name()).build();
        AttributeDto personFirstName = AttributeDto.builder().Name("PersonNameFirst").Value(customer.getFirst_name()).build();
        AttributeDto personIdentificationDoc = AttributeDto.builder().Name("PersonIDDoc").Value(customer.getIdentification_type().toString()).build();
        AttributeDto personIDNO = AttributeDto.builder().Name("PersonIDNo").Value(customer.getIdentification_number()).build();
        AttributeDto addressLine = AttributeDto.builder().Name("AddressLine").Value(customer.getPostal_address()).build();
        AttributeDto city = AttributeDto.builder().Name("CityLGA").Value(customer.getCity()).build();
        AttributeDto state = AttributeDto.builder().Name("State").Value(customer.getState()).build();
        AttributeDto postCode = AttributeDto.builder().Name("PostCode").Value(customer.getAddress_code()).build();
        AttributeDto phone = AttributeDto.builder().Name("Phone").Value(customer.getPhone_number()).build();
        AttributeDto email = AttributeDto.builder().Name("Email").Value(customer.getEmail_address()).build();
        AttributeDto description = AttributeDto.builder().Name("InsuranceDescription").Value(dto.getInsurance_description()).build();
        AttributeDto insuredValue = AttributeDto.builder().Name("InsuredValue").Value(dto.getSum_insured().toString()).build();
        AttributeDto premium = AttributeDto.builder().Name("Premium").Value(dto.getTotal_premium().toString()).build();
        AttributeDto commissionFee = AttributeDto.builder().Name("CommissionFee").Value(dto.getCommission_fee().toString()).build();
        AttributeDto extraFee = AttributeDto.builder().Name("ExtraFee").Value(dto.getExtra_fee()).build();
        AttributeDto premiumNote = AttributeDto.builder().Name("PremiumNote").Value(dto.getPremium_note() == null ? "n/a" : dto.getPremium_note()).build();
        AttributeDto terms = AttributeDto.builder().Name("Terms").Value(dto.getTerms() == null ? "n/a" : dto.getTerms()).build();
        AttributeDto preamble = AttributeDto.builder().Name("Preamble").Value(dto.getPreamble() == null ? "n/a" : dto.getPreamble()).build();
        AttributeDto endorsements = AttributeDto.builder().Name("Endorsements").Value(dto.getEndorsements() == null ? "n/a" : dto.getEndorsements()).build();
        AttributeDto exclusions = AttributeDto.builder().Name("Exclusions").Value(dto.getExclusions() == null ? "n/a" : dto.getExclusions()).build();
        AttributeDto exceptions = AttributeDto.builder().Name("Exceptions").Value(dto.getExceptions() == null ? "n/a" : dto.getExceptions()).build();
        AttributeDto conditions = AttributeDto.builder().Name("Conditions").Value(dto.getConditions() == null ? "n/a" : dto.getConditions()).build();

        return Stream.of(conditions, exceptions, exclusions, endorsements, preamble, terms, premium, premiumNote,
                        extraFee, commissionFee, description, insuredValue, email, phone, personFirstName, postCode,
                        personIdentificationDoc, personLastName, personIDNO, holderType, state, city, addressLine)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------

    private static Date defaultDate() {
        return new Date(DEFAULT_DATE_MILLIS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private NaicomPolicyResponseDto missingParameterResponse(NaicomPolicyDto request, String parameterName) {
        return NaicomPolicyResponseDto.builder()
                .message("Failed to post the policy.")
                .status(HttpStatus.BAD_REQUEST)
                .success(false)
                .inputs(request)
                .errors(singleError(parameterName + " has not been set.", "TEX-404"))
                .build();
    }

    private ArrayList<ErrorDto> singleError(String message, String code) {
        ArrayList<ErrorDto> errors = new ArrayList<>();
        errors.add(new ErrorDto(message, code));
        return errors;
    }

    /**
     * NAICOM does not always answer with JSON. A read timeout or a connect failure arrives from
     * {@link ThirdPartyApiClient} as a plain sentence ("timeout Encountered while posting Naicom
     * Policy"), and a gateway in front of it can answer with a reason phrase or an HTML page.
     * Handing any of those to {@code new JSONObject(...)} threw
     * "A JSONObject text must begin with a brace" out of the posting service, which surfaced as a raw
     * stage=POST ERROR and lost the actual reason. Parse leniently instead and let the caller
     * report the body it did get.
     *
     * @return the parsed object, or null when the body is not a JSON object at all.
     */
    private static JSONObject parseJsonBody(String body) {
        if (body == null || body.isBlank() || "null".equals(body)) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * The outcome for a call that came back without a usable JSON body: a failure carrying the
     * body NAICOM (or whatever sits in front of it) actually returned, with the audit row written
     * the same way the normal path writes it.
     */
    private NaicomPolicyResponseDto unreadableResponse(NaicomPolicyResponseDto response,
                                                       IncomingRequest incomingRequest,
                                                       HttpStatus status, String body) {
        String detail = "NAICOM returned no usable JSON (HTTP " + status.value() + "): " + summariseBody(body);
        response.setSuccess(false);
        // A transport failure now arrives as 502/504; only a genuine 200 with a non-JSON body
        // still needs a status of our own.
        response.setStatus(status.value() == 200 ? HttpStatus.BAD_GATEWAY : status);
        response.setMessage(detail);
        response.setErrors(singleError(detail, "TEX-502"));
        response.setNaicom_response(body);

        incomingRequest.setResponseBody(incomingRequestService.truncate(body));
        incomingRequest = saveIncomingRequestSafely(incomingRequest);
        response.setTurnquest_request_id(incomingRequest.getId());
        return response;
    }

    /** Enough of an unparseable body to identify it, without dragging an HTML page into a log line. */
    private static String summariseBody(String body) {
        if (body == null || body.isBlank()) {
            return "<empty body>";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }

    private ArrayList<ErrorDto> mapNaicomErrors(JSONArray errorMessages, JSONArray errorCodes) {
        ArrayList<ErrorDto> errors = new ArrayList<>();
        if (errorMessages == null) {
            return errors;
        }
        for (int i = 0; i < errorMessages.length(); i++) {
            String code = errorCodes != null && i < errorCodes.length() ? errorCodes.get(i).toString() : "TEX-000";
            errors.add(new ErrorDto(errorMessages.getString(i), code));
        }
        return errors;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize NAICOM request payload", e);
        }
    }

    private IncomingRequest saveIncomingRequestSafely(IncomingRequest incomingRequest, NaicomPolicyDto body) {
        String json = writeJson(body);
        incomingRequest.setRequestBody(incomingRequestService.truncate(json));
        return saveIncomingRequestSafely(incomingRequest);
    }

    private IncomingRequest saveIncomingRequestSafely(IncomingRequest incomingRequest) {
        try {
            if (incomingRequest.getId() != null) {
                // Already inserted, so this is the post-call outcome write. Update the two fields
                // directly rather than save()/merge, which would SELECT the row back first.
                incomingRequestService.updateOutcome(incomingRequest);
                return incomingRequest;
            }
            return incomingRequestService.save(incomingRequest);
        } catch (Exception e) {
            log.warn("Failed to persist incoming-request audit log (continuing anyway): {}", e.getMessage());
            return incomingRequest;
        }
    }
}
