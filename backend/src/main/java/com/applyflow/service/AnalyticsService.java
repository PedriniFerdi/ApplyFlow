package com.applyflow.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.applyflow.dto.analytics.AnalyticsPeriod;
import com.applyflow.dto.analytics.AnalyticsSummaryResponse;
import com.applyflow.dto.analytics.ApplicationsOverTimeResponse;
import com.applyflow.dto.analytics.DimensionAnalyticsItemResponse;
import com.applyflow.dto.analytics.DimensionAnalyticsResponse;
import com.applyflow.dto.analytics.FunnelResponse;
import com.applyflow.dto.analytics.FunnelStageResponse;
import com.applyflow.dto.analytics.ResponseTimeResponse;
import com.applyflow.dto.analytics.TimeBucketResponse;
import com.applyflow.entity.ApplicationStatus;
import com.applyflow.repository.AnalyticsRepository;
import com.applyflow.repository.AnalyticsRepository.DimensionCounts;
import com.applyflow.repository.AnalyticsRepository.SummaryCounts;

@Service
public class AnalyticsService {

    private static final List<ApplicationStatus> RESPONSE_STATUSES = List.of(
            ApplicationStatus.RESPONSE_RECEIVED,
            ApplicationStatus.HR_INTERVIEW,
            ApplicationStatus.TECHNICAL_INTERVIEW,
            ApplicationStatus.FINAL_INTERVIEW,
            ApplicationStatus.OFFER
    );
    private static final List<ApplicationStatus> INTERVIEW_STATUSES = List.of(
            ApplicationStatus.HR_INTERVIEW,
            ApplicationStatus.TECHNICAL_INTERVIEW,
            ApplicationStatus.FINAL_INTERVIEW
    );
    private static final List<ApplicationStatus> OFFER_STATUSES = List.of(ApplicationStatus.OFFER);
    private static final List<ApplicationStatus> REJECTION_STATUSES = List.of(ApplicationStatus.REJECTED);
    private static final List<ApplicationStatus> FUNNEL_STATUSES = List.of(
            ApplicationStatus.APPLIED,
            ApplicationStatus.RESPONSE_RECEIVED,
            ApplicationStatus.HR_INTERVIEW,
            ApplicationStatus.TECHNICAL_INTERVIEW,
            ApplicationStatus.FINAL_INTERVIEW,
            ApplicationStatus.OFFER
    );
    private static final Comparator<DimensionAnalyticsItemResponse> DIMENSION_ORDER =
            Comparator.comparingLong(DimensionAnalyticsItemResponse::applicationCount).reversed()
                    .thenComparing(DimensionAnalyticsItemResponse::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparingLong(DimensionAnalyticsItemResponse::id);

    private final AnalyticsRepository analyticsRepository;

    public AnalyticsService(AnalyticsRepository analyticsRepository) {
        this.analyticsRepository = analyticsRepository;
    }

    @Transactional(readOnly = true)
    public AnalyticsSummaryResponse summary(Long ownerId) {
        SummaryCounts counts = analyticsRepository.findSummary(
                ownerId,
                names(RESPONSE_STATUSES), names(INTERVIEW_STATUSES),
                names(OFFER_STATUSES), names(REJECTION_STATUSES));
        return new AnalyticsSummaryResponse(
                counts.totalApplications(),
                counts.appliedApplications(),
                counts.responseCount(),
                percentage(counts.responseCount(), counts.appliedApplications()),
                counts.interviewCount(),
                percentage(counts.interviewCount(), counts.appliedApplications()),
                counts.offerCount(),
                percentage(counts.offerCount(), counts.appliedApplications()),
                counts.rejectionCount(),
                percentage(counts.rejectionCount(), counts.appliedApplications())
        );
    }

    @Transactional(readOnly = true)
    public FunnelResponse funnel(Long ownerId) {
        Map<ApplicationStatus, Long> counts = analyticsRepository.findFunnel(ownerId, names(FUNNEL_STATUSES)).stream()
                .collect(Collectors.toMap(
                        item -> ApplicationStatus.valueOf(item.status()),
                        AnalyticsRepository.FunnelCount::applicationCount));
        List<FunnelStageResponse> stages = FUNNEL_STATUSES.stream()
                .map(status -> new FunnelStageResponse(status, counts.getOrDefault(status, 0L)))
                .toList();
        return new FunnelResponse(stages);
    }

    @Transactional(readOnly = true)
    public ApplicationsOverTimeResponse applicationsOverTime(Long ownerId, AnalyticsPeriod period) {
        List<TimeBucketResponse> buckets = analyticsRepository.findApplicationsOverTime(ownerId, period).stream()
                .map(bucket -> new TimeBucketResponse(bucket.startDate(), bucket.applicationCount()))
                .toList();
        return new ApplicationsOverTimeResponse(period, buckets);
    }

    @Transactional(readOnly = true)
    public DimensionAnalyticsResponse sources(Long ownerId) {
        return dimensions(analyticsRepository.findSources(
                ownerId,
                names(RESPONSE_STATUSES), names(INTERVIEW_STATUSES),
                names(OFFER_STATUSES), names(REJECTION_STATUSES)));
    }

    @Transactional(readOnly = true)
    public DimensionAnalyticsResponse technologies(Long ownerId) {
        return dimensions(analyticsRepository.findTechnologies(
                ownerId,
                names(RESPONSE_STATUSES), names(INTERVIEW_STATUSES),
                names(OFFER_STATUSES), names(REJECTION_STATUSES)));
    }

    @Transactional(readOnly = true)
    public ResponseTimeResponse responseTime(Long ownerId) {
        List<Long> responseDays = analyticsRepository.findFirstResponseDays(ownerId, names(RESPONSE_STATUSES));
        if (responseDays.isEmpty()) {
            return new ResponseTimeResponse(0, null, null);
        }
        List<Long> sorted = responseDays.stream().sorted().toList();
        BigDecimal total = sorted.stream()
                .map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = total.divide(BigDecimal.valueOf(sorted.size()), 2, RoundingMode.HALF_UP);
        int middle = sorted.size() / 2;
        BigDecimal median = sorted.size() % 2 == 1
                ? BigDecimal.valueOf(sorted.get(middle)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(sorted.get(middle - 1))
                        .add(BigDecimal.valueOf(sorted.get(middle)))
                        .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        return new ResponseTimeResponse(sorted.size(), average, median);
    }

    private DimensionAnalyticsResponse dimensions(List<DimensionCounts> counts) {
        List<DimensionAnalyticsItemResponse> items = counts.stream()
                .map(this::toDimensionResponse)
                .sorted(DIMENSION_ORDER)
                .toList();
        return new DimensionAnalyticsResponse(items);
    }

    private DimensionAnalyticsItemResponse toDimensionResponse(DimensionCounts counts) {
        return new DimensionAnalyticsItemResponse(
                counts.id(),
                counts.name(),
                counts.applicationCount(),
                counts.responseCount(),
                percentage(counts.responseCount(), counts.applicationCount()),
                counts.interviewCount(),
                percentage(counts.interviewCount(), counts.applicationCount()),
                counts.offerCount(),
                percentage(counts.offerCount(), counts.applicationCount()),
                counts.rejectionCount(),
                percentage(counts.rejectionCount(), counts.applicationCount())
        );
    }

    private BigDecimal percentage(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private List<String> names(List<ApplicationStatus> statuses) {
        return statuses.stream().map(Enum::name).toList();
    }
}
