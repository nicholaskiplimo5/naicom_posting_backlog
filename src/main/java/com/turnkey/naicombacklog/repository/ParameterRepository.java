package com.turnkey.naicombacklog.repository;

import com.turnkey.naicombacklog.model.Parameter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ParameterRepository extends JpaRepository<Parameter, Long> {

    List<Parameter> findByParamName(String parameterName);
}
