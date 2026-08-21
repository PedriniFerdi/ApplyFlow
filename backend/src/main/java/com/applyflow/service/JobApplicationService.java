package com.applyflow.service;

import static com.applyflow.repository.JobApplicationSpecifications.hasCompany;
import static com.applyflow.repository.JobApplicationSpecifications.hasSource;
import static com.applyflow.repository.JobApplicationSpecifications.hasStatus;
import static com.applyflow.repository.JobApplicationSpecifications.hasTechnology;
import static com.applyflow.repository.JobApplicationSpecifications.ownedBy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.applyflow.dto.application.ChangeApplicationStatusRequest;
import com.applyflow.dto.application.CreateJobApplicationRequest;
import com.applyflow.dto.application.JobApplicationDetailResponse;
import com.applyflow.dto.application.JobApplicationPageResponse;
import com.applyflow.dto.application.JobApplicationSummaryResponse;
import com.applyflow.dto.application.StatusHistoryResponse;
import com.applyflow.dto.application.UpdateJobApplicationRequest;
import com.applyflow.dto.catalog.CatalogItemResponse;
import com.applyflow.dto.catalog.CompanyResponse;
import com.applyflow.dto.catalog.NewCompanyRequest;
import com.applyflow.entity.ApplicationStatus;
import com.applyflow.entity.ApplicationStatusHistory;
import com.applyflow.entity.Company;
import com.applyflow.entity.JobApplication;
import com.applyflow.entity.JobSource;
import com.applyflow.entity.Technology;
import com.applyflow.entity.UserAccount;
import com.applyflow.exception.BusinessRuleException;
import com.applyflow.exception.ResourceNotFoundException;
import com.applyflow.repository.ApplicationStatusHistoryRepository;
import com.applyflow.repository.CompanyRepository;
import com.applyflow.repository.JobApplicationRepository;
import com.applyflow.repository.JobSourceRepository;
import com.applyflow.repository.TechnologyRepository;
import com.applyflow.repository.UserAccountRepository;

@Service
public class JobApplicationService {

    private static final List<ApplicationStatus> RESPONSE_STATUSES = List.of(
            ApplicationStatus.RESPONSE_RECEIVED,
            ApplicationStatus.HR_INTERVIEW,
            ApplicationStatus.TECHNICAL_INTERVIEW,
            ApplicationStatus.FINAL_INTERVIEW,
            ApplicationStatus.OFFER
    );

    private static final Map<String, String> SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "updatedAt", "updatedAt",
            "appliedDate", "appliedDate",
            "positionTitle", "positionTitle",
            "status", "status"
    );

    private final JobApplicationRepository applicationRepository;
    private final ApplicationStatusHistoryRepository historyRepository;
    private final CompanyRepository companyRepository;
    private final JobSourceRepository sourceRepository;
    private final TechnologyRepository technologyRepository;
    private final UserAccountRepository userRepository;
    private final JobApplicationValidator validator;
    private final Clock clock;

    public JobApplicationService(
            JobApplicationRepository applicationRepository,
            ApplicationStatusHistoryRepository historyRepository,
            CompanyRepository companyRepository,
            JobSourceRepository sourceRepository,
            TechnologyRepository technologyRepository,
            UserAccountRepository userRepository,
            JobApplicationValidator validator,
            Clock clock
    ) {
        this.applicationRepository = applicationRepository;
        this.historyRepository = historyRepository;
        this.companyRepository = companyRepository;
        this.sourceRepository = sourceRepository;
        this.technologyRepository = technologyRepository;
        this.userRepository = userRepository;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public JobApplicationDetailResponse create(Long ownerId, CreateJobApplicationRequest request) {
        validator.validateCreate(request);
        JobApplicationValidator.SalaryAmounts salary = validator.validateSalary(
                request.salaryMin(), request.salaryMax(), request.currency(), request.salaryPeriod());
        UserAccount owner = requireOwner(ownerId);
        Company company = resolveCompany(owner, request.companyId(), request.newCompany());
        JobSource source = resolveSource(request.sourceId());
        Set<Technology> technologies = resolveTechnologies(ownerId, request.technologyIds());

        JobApplication application = new JobApplication(
                owner,
                company,
                validator.normalizeRequired(request.positionTitle()),
                validator.normalizeOptional(request.jobUrl()),
                request.appliedDate(),
                request.status(),
                source,
                request.workMode(),
                validator.normalizeOptional(request.location()),
                salary.minimum(),
                salary.maximum(),
                validator.normalizeCurrency(request.currency()),
                request.salaryPeriod(),
                validator.normalizeOptional(request.notes()),
                technologies
        );
        JobApplication saved = applicationRepository.saveAndFlush(application);
        historyRepository.save(new ApplicationStatusHistory(saved, saved.getStatus(), clock.instant()));
        return toDetail(saved);
    }

    @Transactional(readOnly = true)
    public JobApplicationPageResponse findAll(
            Long ownerId,
            Collection<ApplicationStatus> statuses,
            Long sourceId,
            Long companyId,
            Long technologyId,
            int page,
            int size,
            String sortBy,
            Sort.Direction direction
    ) {
        validateListParameters(sourceId, companyId, technologyId, page, size);
        String property = SORT_FIELDS.get(sortBy);
        if (property == null) {
            throw new BusinessRuleException("Unsupported sortBy value");
        }
        Specification<JobApplication> specification = Specification.allOf(
                ownedBy(ownerId),
                hasStatus(statuses),
                hasSource(sourceId),
                hasCompany(companyId),
                hasTechnology(technologyId)
        );
        Sort sort = Sort.by(direction, property).and(Sort.by(Sort.Direction.ASC, "id"));
        Page<JobApplication> result = applicationRepository.findAll(
                specification,
                PageRequest.of(page, size, sort)
        );
        return new JobApplicationPageResponse(
                result.getContent().stream().map(this::toSummary).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public JobApplicationDetailResponse findById(Long ownerId, Long id) {
        return toDetail(findDetailed(ownerId, id));
    }

    @Transactional
    public JobApplicationDetailResponse update(Long ownerId, Long id, UpdateJobApplicationRequest request) {
        JobApplication current = findDetailedForUpdate(ownerId, id);
        validator.validateUpdate(request, current);
        JobApplicationValidator.SalaryAmounts salary = validator.validateSalary(
                request.salaryMin(), request.salaryMax(), request.currency(), request.salaryPeriod());
        historyRepository.findFirstByApplicationIdAndStatusInOrderByChangedAtAscIdAsc(
                        current.getId(), RESPONSE_STATUSES)
                .ifPresent(firstResponse -> validator.validateAppliedDateAgainstFirstResponse(
                        request.appliedDate(), firstResponse.getChangedAt()));
        current.updateDetails(
                resolveCompany(current.getOwner(), request.companyId(), request.newCompany()),
                validator.normalizeRequired(request.positionTitle()),
                validator.normalizeOptional(request.jobUrl()),
                request.appliedDate(),
                resolveSource(request.sourceId()),
                request.workMode(),
                validator.normalizeOptional(request.location()),
                salary.minimum(),
                salary.maximum(),
                validator.normalizeCurrency(request.currency()),
                request.salaryPeriod(),
                validator.normalizeOptional(request.notes()),
                resolveTechnologies(ownerId, request.technologyIds())
        );
        applicationRepository.flush();
        return toDetail(current);
    }

    @Transactional
    public JobApplicationDetailResponse changeStatus(Long ownerId, Long id, ChangeApplicationStatusRequest request) {
        JobApplication current = findDetailedForUpdate(ownerId, id);
        if (current.getStatus() == request.status()) {
            return toDetail(current);
        }
        LocalDate appliedDate = validator.resolveStatusAppliedDate(current, request);
        current.changeStatus(request.status(), appliedDate);
        historyRepository.save(new ApplicationStatusHistory(current, request.status(), clock.instant()));
        applicationRepository.flush();
        return toDetail(current);
    }

    @Transactional
    public void delete(Long ownerId, Long id) {
        JobApplication current = findDetailedForUpdate(ownerId, id);
        applicationRepository.delete(current);
        applicationRepository.flush();
    }

    private Company resolveCompany(UserAccount owner, Long companyId, NewCompanyRequest newCompany) {
        if (companyId != null) {
            return companyRepository.findByIdAndOwnerId(companyId, owner.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Company not found"));
        }
        Company company = new Company(
                owner,
                validator.normalizeRequired(newCompany.name()),
                validator.normalizeOptional(newCompany.website()),
                newCompany.companyType(),
                validator.normalizeOptional(newCompany.industry())
        );
        return companyRepository.save(company);
    }

    private JobSource resolveSource(Long sourceId) {
        return sourceRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Source not found"));
    }

    private Set<Technology> resolveTechnologies(Long ownerId, List<Long> technologyIds) {
        if (technologyIds.isEmpty()) {
            return Set.of();
        }
        List<Technology> technologies = technologyRepository.findAllVisibleByIdIn(ownerId, technologyIds);
        if (technologies.size() != technologyIds.size()) {
            throw new ResourceNotFoundException("One or more technologies were not found");
        }
        technologies.sort(Comparator.comparing(Technology::getName, String.CASE_INSENSITIVE_ORDER));
        return new LinkedHashSet<>(technologies);
    }

    private JobApplication findDetailed(Long ownerId, Long id) {
        return applicationRepository.findDetailedByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Job application not found"));
    }

    private JobApplication findDetailedForUpdate(Long ownerId, Long id) {
        return applicationRepository.findDetailedForUpdateByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Job application not found"));
    }

    private UserAccount requireOwner(Long ownerId) {
        return userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void validateListParameters(Long sourceId, Long companyId, Long technologyId, int page, int size) {
        if (page < 0) {
            throw new BusinessRuleException("page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw new BusinessRuleException("size must be between 1 and 100");
        }
        if ((sourceId != null && sourceId < 1)
                || (companyId != null && companyId < 1)
                || (technologyId != null && technologyId < 1)) {
            throw new BusinessRuleException("Filter IDs must be positive");
        }
    }

    private JobApplicationSummaryResponse toSummary(JobApplication application) {
        return new JobApplicationSummaryResponse(
                application.getId(), toCompany(application.getCompany()), application.getPositionTitle(),
                application.getStatus(), application.getAppliedDate(), toCatalogItem(application.getSource()),
                application.getWorkMode(), toTechnologyItems(application),
                application.getCreatedAt(), application.getUpdatedAt()
        );
    }

    private JobApplicationDetailResponse toDetail(JobApplication application) {
        List<StatusHistoryResponse> history = historyRepository
                .findAllByApplicationIdOrderByChangedAtAscIdAsc(application.getId())
                .stream()
                .map(item -> new StatusHistoryResponse(item.getId(), item.getStatus(), item.getChangedAt()))
                .toList();
        return new JobApplicationDetailResponse(
                application.getId(), toCompany(application.getCompany()), application.getPositionTitle(),
                application.getJobUrl(), application.getAppliedDate(), application.getStatus(),
                toCatalogItem(application.getSource()), application.getWorkMode(), application.getLocation(),
                decimalString(application.getSalaryMin()), decimalString(application.getSalaryMax()), application.getCurrency(),
                application.getSalaryPeriod(), application.getNotes(), toTechnologyItems(application),
                application.getCreatedAt(), application.getUpdatedAt(), history
        );
    }

    private CompanyResponse toCompany(Company company) {
        return new CompanyResponse(
                company.getId(), company.getName(), company.getWebsite(), company.getCompanyType(), company.getIndustry());
    }

    private String decimalString(java.math.BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    private CatalogItemResponse toCatalogItem(JobSource source) {
        return new CatalogItemResponse(source.getId(), source.getName());
    }

    private List<CatalogItemResponse> toTechnologyItems(JobApplication application) {
        return application.getTechnologies().stream()
                .sorted(Comparator.comparing(Technology::getName, String.CASE_INSENSITIVE_ORDER))
                .map(technology -> new CatalogItemResponse(technology.getId(), technology.getName()))
                .toList();
    }
}
