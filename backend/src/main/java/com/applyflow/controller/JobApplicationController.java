package com.applyflow.controller;

import java.net.URI;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.applyflow.dto.application.ChangeApplicationStatusRequest;
import com.applyflow.dto.application.CreateJobApplicationRequest;
import com.applyflow.dto.application.JobApplicationDetailResponse;
import com.applyflow.dto.application.JobApplicationPageResponse;
import com.applyflow.dto.application.UpdateJobApplicationRequest;
import com.applyflow.entity.ApplicationStatus;
import com.applyflow.service.JobApplicationService;
import com.applyflow.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import static com.applyflow.validation.RequestLimits.STATUSES;

@RestController
@RequestMapping("/api/applications")
@Validated
public class JobApplicationController {

    private final JobApplicationService applicationService;

    public JobApplicationController(JobApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public ResponseEntity<JobApplicationDetailResponse> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateJobApplicationRequest request
    ) {
        JobApplicationDetailResponse created = applicationService.create(principal.userId(), request);
        return ResponseEntity.created(URI.create("/api/applications/" + created.id())).body(created);
    }

    @GetMapping
    public JobApplicationPageResponse findAll(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) @Size(max = STATUSES) List<ApplicationStatus> status,
            @RequestParam(required = false) Long sourceId,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long technologyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") Sort.Direction direction
    ) {
        return applicationService.findAll(
                principal.userId(), status, sourceId, companyId, technologyId, page, size, sortBy, direction);
    }

    @GetMapping("/{id}")
    public JobApplicationDetailResponse findById(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id
    ) {
        return applicationService.findById(principal.userId(), id);
    }

    @PutMapping("/{id}")
    public JobApplicationDetailResponse update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateJobApplicationRequest request
    ) {
        return applicationService.update(principal.userId(), id, request);
    }

    @PatchMapping("/{id}/status")
    public JobApplicationDetailResponse changeStatus(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody ChangeApplicationStatusRequest request
    ) {
        return applicationService.changeStatus(principal.userId(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id
    ) {
        applicationService.delete(principal.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
