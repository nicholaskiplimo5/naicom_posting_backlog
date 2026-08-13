package com.turnkey.naicombacklog.service;

import com.turnkey.naicombacklog.model.Parameter;
import com.turnkey.naicombacklog.repository.ParameterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ParameterService {

    private final ParameterRepository parameterRepository;

    public Parameter findByName(String name) {
        List<Parameter> parameters = parameterRepository.findByParamName(name);
        return parameters.isEmpty() ? null : parameters.get(0);
    }
}
