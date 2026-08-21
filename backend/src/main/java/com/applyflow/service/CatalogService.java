package com.applyflow.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.applyflow.dto.catalog.CatalogItemResponse;
import com.applyflow.dto.catalog.CompanyResponse;
import com.applyflow.dto.catalog.CreateTechnologyRequest;
import com.applyflow.entity.Technology;
import com.applyflow.entity.UserAccount;
import com.applyflow.exception.ConflictException;
import com.applyflow.repository.CompanyRepository;
import com.applyflow.repository.JobSourceRepository;
import com.applyflow.repository.TechnologyRepository;
import com.applyflow.repository.UserAccountRepository;

@Service
public class CatalogService {

    private static final PageRequest FIRST_TWENTY = PageRequest.of(0, 20);

    private final CompanyRepository companyRepository;
    private final JobSourceRepository jobSourceRepository;
    private final TechnologyRepository technologyRepository;
    private final JobApplicationValidator validator;
    private final UserAccountRepository userRepository;

    public CatalogService(
            CompanyRepository companyRepository,
            JobSourceRepository jobSourceRepository,
            TechnologyRepository technologyRepository,
            UserAccountRepository userRepository,
            JobApplicationValidator validator
    ) {
        this.companyRepository = companyRepository;
        this.jobSourceRepository = jobSourceRepository;
        this.technologyRepository = technologyRepository;
        this.userRepository = userRepository;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<CompanyResponse> findCompanies(Long ownerId, String query) {
        String normalized = validator.normalizeOptional(query);
        return (normalized == null
                ? companyRepository.findAllOrderByNameIgnoreCase(ownerId, FIRST_TWENTY)
                : companyRepository.findByNameContainingIgnoreCaseOrderByName(normalized, ownerId, FIRST_TWENTY))
                .stream()
                .map(company -> new CompanyResponse(
                        company.getId(), company.getName(), company.getWebsite(),
                        company.getCompanyType(), company.getIndustry()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogItemResponse> findTechnologies(Long ownerId) {
        return technologyRepository.findAllVisibleByOrderByNameAsc(ownerId).stream()
                .map(technology -> new CatalogItemResponse(technology.getId(), technology.getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogItemResponse> findSources() {
        return jobSourceRepository.findAllByOrderByNameAsc().stream()
                .map(source -> new CatalogItemResponse(source.getId(), source.getName()))
                .toList();
    }

    @Transactional
    public CatalogItemResponse createTechnology(Long ownerId, CreateTechnologyRequest request) {
        String name = validator.normalizeRequired(request.name());
        if (technologyRepository.findVisibleByNameIgnoreCase(ownerId, name).isPresent()) {
            throw new ConflictException("Technology already exists");
        }
        try {
            UserAccount owner = userRepository.findById(ownerId)
                    .orElseThrow(() -> new com.applyflow.exception.ResourceNotFoundException("User not found"));
            Technology saved = technologyRepository.saveAndFlush(new Technology(owner, name));
            return new CatalogItemResponse(saved.getId(), saved.getName());
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("Technology already exists");
        }
    }
}
