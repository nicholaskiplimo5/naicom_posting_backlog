package com.turnkey.naicombacklog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Maps the existing {@code tex_parameters} key/value config table that the live
 * tps-apis NAICOM integration reads its posting URL, SID/Token and recorder IDs from.
 */
@Entity
@Table(name = "tex_parameters")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
public class Parameter {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "param_code", unique = true, updatable = false, nullable = false)
    private Long paramCode;

    @Column(name = "param_name", nullable = false)
    private String paramName;

    @Column(name = "param_value", nullable = false)
    private String paramValue;
}
