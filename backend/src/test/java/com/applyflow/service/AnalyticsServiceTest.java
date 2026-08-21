package com.applyflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.applyflow.dto.analytics.DimensionAnalyticsItemResponse;
import com.applyflow.entity.ApplicationStatus;
import com.applyflow.repository.AnalyticsRepository;
import com.applyflow.repository.AnalyticsRepository.DimensionCounts;
import com.applyflow.repository.AnalyticsRepository.FunnelCount;
import com.applyflow.repository.AnalyticsRepository.SummaryCounts;

class AnalyticsServiceTest {

    private AnalyticsRepository repository;
    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        repository = mock(AnalyticsRepository.class);
        service = new AnalyticsService(repository);
    }

    @Test
    void calculatesSummaryRatesWithTwoDecimalHalfUpRounding() {
        when(repository.findSummary(anyLong(), anyCollection(), anyCollection(), anyCollection(), anyCollection()))
                .thenReturn(new SummaryCounts(9, 6, 4, 3, 1, 2));

        var summary = service.summary(1L);

        assertThat(summary.totalApplications()).isEqualTo(9);
        assertThat(summary.appliedApplications()).isEqualTo(6);
        assertThat(summary.responseRate()).isEqualByComparingTo("66.67");
        assertThat(summary.interviewRate()).isEqualByComparingTo("50.00");
        assertThat(summary.offerRate()).isEqualByComparingTo("16.67");
        assertThat(summary.rejectionRate()).isEqualByComparingTo("33.33");
    }

    @Test
    void returnsZeroRatesWhenThereAreNoAppliedApplications() {
        when(repository.findSummary(anyLong(), anyCollection(), anyCollection(), anyCollection(), anyCollection()))
                .thenReturn(new SummaryCounts(3, 0, 0, 0, 0, 0));

        var summary = service.summary(1L);

        assertThat(summary.responseRate()).isEqualByComparingTo("0.00");
        assertThat(summary.interviewRate()).isEqualByComparingTo("0.00");
        assertThat(summary.offerRate()).isEqualByComparingTo("0.00");
        assertThat(summary.rejectionRate()).isEqualByComparingTo("0.00");
    }

    @Test
    void calculatesAverageAndMedianForOddAndEvenSamples() {
        when(repository.findFirstResponseDays(anyLong(), anyCollection()))
                .thenReturn(List.of(8L, 1L, 4L));

        var odd = service.responseTime(1L);

        assertThat(odd.sampleSize()).isEqualTo(3);
        assertThat(odd.averageDays()).isEqualByComparingTo("4.33");
        assertThat(odd.medianDays()).isEqualByComparingTo("4.00");

        when(repository.findFirstResponseDays(anyLong(), anyCollection()))
                .thenReturn(List.of(8L, 1L, 4L, 3L));

        var even = service.responseTime(1L);

        assertThat(even.sampleSize()).isEqualTo(4);
        assertThat(even.averageDays()).isEqualByComparingTo("4.00");
        assertThat(even.medianDays()).isEqualByComparingTo("3.50");
    }

    @Test
    void returnsNullResponseTimesForAnEmptySample() {
        when(repository.findFirstResponseDays(anyLong(), anyCollection())).thenReturn(List.of());

        var response = service.responseTime(1L);

        assertThat(response.sampleSize()).isZero();
        assertThat(response.averageDays()).isNull();
        assertThat(response.medianDays()).isNull();
    }

    @Test
    void completesAndOrdersTheExactFunnelWithoutInferringStages() {
        when(repository.findFunnel(anyLong(), anyCollection())).thenReturn(List.of(
                new FunnelCount(ApplicationStatus.HR_INTERVIEW.name(), 4),
                new FunnelCount(ApplicationStatus.APPLIED.name(), 2),
                new FunnelCount(ApplicationStatus.OFFER.name(), 1)
        ));

        var funnel = service.funnel(1L);

        assertThat(funnel.stages()).extracting(stage -> stage.status().name())
                .containsExactly("APPLIED", "RESPONSE_RECEIVED", "HR_INTERVIEW",
                        "TECHNICAL_INTERVIEW", "FINAL_INTERVIEW", "OFFER");
        assertThat(funnel.stages()).extracting(stage -> stage.count())
                .containsExactly(2L, 0L, 4L, 0L, 0L, 1L);
    }

    @Test
    void calculatesDimensionRatesAndAppliesStableRanking() {
        when(repository.findSources(anyLong(), anyCollection(), anyCollection(), anyCollection(), anyCollection()))
                .thenReturn(List.of(
                        new DimensionCounts(2, "zeta", 2, 1, 0, 0, 1),
                        new DimensionCounts(3, "alpha", 2, 2, 1, 1, 0),
                        new DimensionCounts(1, "Referral", 3, 1, 1, 0, 0)
                ));

        var response = service.sources(1L);

        assertThat(response.items()).extracting(DimensionAnalyticsItemResponse::name)
                .containsExactly("Referral", "alpha", "zeta");
        assertThat(response.items().get(0).responseRate()).isEqualByComparingTo("33.33");
        assertThat(response.items().get(1).offerRate()).isEqualByComparingTo("50.00");
    }

    @Test
    void sendsTheApprovedStatusGroupsToAllAnalyticsQueries() {
        List<String> responseStatuses = List.of(
                "RESPONSE_RECEIVED", "HR_INTERVIEW", "TECHNICAL_INTERVIEW", "FINAL_INTERVIEW", "OFFER");
        List<String> interviewStatuses = List.of(
                "HR_INTERVIEW", "TECHNICAL_INTERVIEW", "FINAL_INTERVIEW");
        List<String> offerStatuses = List.of("OFFER");
        List<String> rejectionStatuses = List.of("REJECTED");
        when(repository.findSummary(
                eq(1L), eq(responseStatuses), eq(interviewStatuses), eq(offerStatuses), eq(rejectionStatuses)))
                .thenReturn(new SummaryCounts(0, 0, 0, 0, 0, 0));
        when(repository.findSources(
                eq(1L), eq(responseStatuses), eq(interviewStatuses), eq(offerStatuses), eq(rejectionStatuses)))
                .thenReturn(List.of());
        when(repository.findTechnologies(
                eq(1L), eq(responseStatuses), eq(interviewStatuses), eq(offerStatuses), eq(rejectionStatuses)))
                .thenReturn(List.of());
        when(repository.findFirstResponseDays(eq(1L), eq(responseStatuses))).thenReturn(List.of());

        service.summary(1L);
        service.sources(1L);
        service.technologies(1L);
        service.responseTime(1L);

        verify(repository).findSummary(
                eq(1L), eq(responseStatuses), eq(interviewStatuses), eq(offerStatuses), eq(rejectionStatuses));
        verify(repository).findSources(
                eq(1L), eq(responseStatuses), eq(interviewStatuses), eq(offerStatuses), eq(rejectionStatuses));
        verify(repository).findTechnologies(
                eq(1L), eq(responseStatuses), eq(interviewStatuses), eq(offerStatuses), eq(rejectionStatuses));
        verify(repository).findFirstResponseDays(eq(1L), eq(responseStatuses));
    }
}
