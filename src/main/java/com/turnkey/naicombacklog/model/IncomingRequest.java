package com.turnkey.naicombacklog.model;

import com.turnkey.naicombacklog.enums.IncomingRequestType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Maps the existing {@code tex_incoming_requests} audit-log table (request/response body
 * of every outbound NAICOM call) so both apps can share the same posting history.
 */
@Entity
@Table(name = "tex_incoming_requests")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class IncomingRequest {

    /**
     * tex_incoming_requests is shared, live prod data - tps-apis inserts into it using the
     * same globally-shared HIBERNATE_SEQUENCE (Hibernate 5's implicit AUTO-strategy default),
     * so this pins to that same sequence rather than a private one, to stay in the same ID
     * space as prod. allocationSize=1 matches its real INCREMENT BY 1 so we never assume a
     * private block of values another consumer of the sequence could also claim.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "icIdSeqGen")
    @SequenceGenerator(name = "icIdSeqGen", sequenceName = "HIBERNATE_SEQUENCE", allocationSize = 1)
    @Column(name = "IC_ID")
    private Long id;

    @Column(name = "IC_REQUEST_NUMBER")
    private String apiRequestNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "IC_REQUEST_TYPE")
    private IncomingRequestType incomingRequestType;

    @Column(name = "IC_REQUEST_DATE")
    @Temporal(TemporalType.TIMESTAMP)
    private Date requestDate;

    @Column(name = "IC_SUCCEEDED")
    private Boolean success;

    /*
     * NOT @Lob. Both columns are VARCHAR2(4000) in tex_incoming_requests, not CLOB - @Lob made
     * Hibernate call ResultSet.getClob() on them, which Oracle answers with
     * "getCLOB not implemented for class oracle.jdbc.driver.T4CVarcharAccessor". That threw on
     * every read of the entity, so the response-body update after each posting always failed
     * (silently, into saveIncomingRequestSafely's catch): IC_RESPONSE_BODY was null and
     * IC_SUCCEEDED stuck at 0 on every row, even for successful posts.
     * IncomingRequestService.truncate keeps values inside 4000.
     */
    @Column(name = "IC_REQUEST_BODY", length = 4000)
    private String requestBody;

    @Column(name = "IC_RESPONSE_BODY", length = 4000)
    private String responseBody;
}
