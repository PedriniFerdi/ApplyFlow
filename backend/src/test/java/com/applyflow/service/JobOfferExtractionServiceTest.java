package com.applyflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.util.zip.GZIPOutputStream;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import com.applyflow.dto.application.JobOfferExtractionResponse;
import com.applyflow.entity.JobSource;
import com.applyflow.entity.SalaryPeriod;
import com.applyflow.entity.WorkMode;
import com.applyflow.exception.BusinessRuleException;
import com.applyflow.exception.JobOfferExtractionException;
import com.applyflow.repository.CompanyRepository;
import com.applyflow.repository.JobSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

class JobOfferExtractionServiceTest {

    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final JobSourceRepository sources = mock(JobSourceRepository.class);
    private final JobOfferExtractionService service = new JobOfferExtractionService(
            null, new ObjectMapper(), companies, sources, 2);

    @Test
    void boundsSuggestionsByUnicodeCodePointWithoutSplittingAstralCharacters() {
        when(sources.findByNameIgnoreCase("Company Website")).thenReturn(Optional.empty());
        String title = "🚀".repeat(181);
        JobOfferExtractionResponse result = service.parse(
                7L, URI.create("https://example.com/job"), Jsoup.parse("<title>" + title + "</title>"));

        assertThat(result.positionTitle()).isEqualTo("🚀".repeat(180));
        assertThat(result.positionTitle().codePointCount(0, result.positionTitle().length())).isEqualTo(180);
    }

    @Test
    void extractsBoundedStructuredJobPostingFieldsWithoutInferringNotesOrTechnologies() {
        when(companies.findAllByOwnerIdAndNameIgnoreCaseOrderByIdAsc(7L, "Acme")).thenReturn(List.of());
        JobSource linkedIn = mock(JobSource.class);
        when(linkedIn.getId()).thenReturn(3L);
        when(sources.findByNameIgnoreCase("LinkedIn")).thenReturn(Optional.of(linkedIn));

        String html = """
                <html><head><script type="application/ld+json">
                {"@context":"https://schema.org","@type":"JobPosting","title":"Backend Engineer",
                 "hiringOrganization":{"@type":"Organization","name":"Acme","url":"https://acme.example"},
                 "jobLocationType":"TELECOMMUTE",
                 "jobLocation":{"address":{"addressLocality":"Buenos Aires","addressCountry":"AR"}},
                 "baseSalary":{"currency":"USD","value":{"minValue":"90000","maxValue":"120000","unitText":"YEAR"}}}
                </script></head><body></body></html>
                """;

        JobOfferExtractionResponse result = service.parse(
                7L, URI.create("https://jobs.linkedin.com/view/42"), Jsoup.parse(html));

        assertThat(result.positionTitle()).isEqualTo("Backend Engineer");
        assertThat(result.company().name()).isEqualTo("Acme");
        assertThat(result.sourceId()).isEqualTo(3L);
        assertThat(result.workMode()).isEqualTo(WorkMode.REMOTE);
        assertThat(result.location()).isEqualTo("Buenos Aires, AR");
        assertThat(result.salary().period()).isEqualTo(SalaryPeriod.YEARLY);
        assertThat(result.salary().min()).isEqualTo("90000");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void fallsBackConservativelyToOpenGraphAndReportsPartialExtraction() {
        when(companies.findAllByOwnerIdAndNameIgnoreCaseOrderByIdAsc(7L, "Acme Careers")).thenReturn(List.of());
        when(sources.findByNameIgnoreCase("Company Website")).thenReturn(Optional.empty());
        String html = "<html><head><meta property='og:title' content='Platform Engineer'>"
                + "<meta property='og:site_name' content='Acme Careers'></head></html>";

        JobOfferExtractionResponse result = service.parse(
                7L, URI.create("https://careers.acme.example/jobs/1"), Jsoup.parse(html));

        assertThat(result.positionTitle()).isEqualTo("Platform Engineer");
        assertThat(result.company().name()).isEqualTo("Acme Careers");
        assertThat(result.workMode()).isNull();
        assertThat(result.salary()).isNull();
        assertThat(result.warnings()).anyMatch(message -> message.contains("structured JobPosting"));
    }

    @Test
    void decomposesRealLinkedInMetadataIntoCompanyPositionAndLocation() {
        when(companies.findAllByOwnerIdAndNameIgnoreCaseOrderByIdAsc(7L, "ROGII Latin America")).thenReturn(List.of());
        when(sources.findByNameIgnoreCase("LinkedIn")).thenReturn(Optional.empty());
        String title = "ROGII Latin America hiring Senior C# Developer in Greater Buenos Aires | LinkedIn";
        String html = "<html><head><title>" + title + "</title>"
                + "<meta property='og:title' content='" + title + "'></head></html>";

        JobOfferExtractionResponse result = service.parse(
                7L, URI.create("https://www.linkedin.com/jobs/view/4453575573/"), Jsoup.parse(html));

        assertThat(result.company().name()).isEqualTo("ROGII Latin America");
        assertThat(result.positionTitle()).isEqualTo("Senior C# Developer");
        assertThat(result.location()).isEqualTo("Greater Buenos Aires");
        assertThat(result.confidence()).containsEntry("companyName", JobOfferExtractionResponse.Confidence.LOW)
                .containsEntry("positionTitle", JobOfferExtractionResponse.Confidence.LOW)
                .containsEntry("location", JobOfferExtractionResponse.Confidence.LOW);
    }

    @Test
    void usesTheFinalLinkedInLocationSeparatorWithoutSplittingLegitimatePositionText() {
        when(companies.findAllByOwnerIdAndNameIgnoreCaseOrderByIdAsc(7L, "Acme")).thenReturn(List.of());
        when(sources.findByNameIgnoreCase("LinkedIn")).thenReturn(Optional.empty());
        String title = "Acme hiring Head of Engineering in Developer Experience in Berlin | LinkedIn";

        JobOfferExtractionResponse result = service.parse(
                7L, URI.create("https://linkedin.com/jobs/view/1"),
                Jsoup.parse("<meta property='og:title' content='" + title + "'>"));

        assertThat(result.positionTitle()).isEqualTo("Head of Engineering in Developer Experience");
        assertThat(result.location()).isEqualTo("Berlin");
    }

    @Test
    void doesNotSplitUnanchoredOrNonLinkedInMetadataTitles() {
        when(companies.findAllByOwnerIdAndNameIgnoreCaseOrderByIdAsc(7L, "LinkedIn")).thenReturn(List.of());
        when(sources.findByNameIgnoreCase("LinkedIn")).thenReturn(Optional.empty());
        when(sources.findByNameIgnoreCase("Company Website")).thenReturn(Optional.empty());
        String legitimateTitle = "Senior Engineer in Platform | LinkedIn";

        JobOfferExtractionResponse unanchored = service.parse(
                7L, URI.create("https://linkedin.com/jobs/view/2"),
                Jsoup.parse("<meta property='og:title' content='" + legitimateTitle + "'>"
                        + "<meta property='og:site_name' content='LinkedIn'>"));
        JobOfferExtractionResponse wrongHost = service.parse(
                7L, URI.create("https://linkedin.com.attacker.example/jobs/view/2"),
                Jsoup.parse("<meta property='og:title' content='Acme hiring Engineer in Berlin | LinkedIn'>"));

        assertThat(unanchored.positionTitle()).isEqualTo(legitimateTitle);
        assertThat(unanchored.location()).isNull();
        assertThat(wrongHost.positionTitle()).isEqualTo("Acme hiring Engineer in Berlin | LinkedIn");
        assertThat(wrongHost.location()).isNull();
    }

    @Test
    void mapsOnlyExactOrSubdomainProviderHosts() {
        assertThat(JobOfferExtractionService.sourceName("jobs.linkedin.com")).isEqualTo("LinkedIn");
        assertThat(JobOfferExtractionService.sourceName("linkedin.com.attacker.example")).isEqualTo("Company Website");
    }

    @Test
    void rejectsCredentialsNonstandardPortsAndNonPublicAddressRanges() throws Exception {
        SafeHtmlFetcher fetcher = new SafeHtmlFetcher(
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(2), 1024, 1);

        assertThatThrownBy(() -> fetcher.parseAndValidateUrl("https://user:secret@example.com/job"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> fetcher.parseAndValidateUrl("https://example.com:8443/job"))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(SafeHtmlFetcher.isPublicAddress(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(SafeHtmlFetcher.isPublicAddress(InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(SafeHtmlFetcher.isPublicAddress(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(SafeHtmlFetcher.isPublicAddress(InetAddress.getByName("::1"))).isFalse();
        assertThat(SafeHtmlFetcher.isPublicAddress(InetAddress.getByName("fc00::1"))).isFalse();
        assertThat(SafeHtmlFetcher.isPublicAddress(InetAddress.getByName("2001:db8::1"))).isFalse();
        assertThat(SafeHtmlFetcher.isPublicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThatThrownBy(() -> fetcher.fetch("http://127.0.0.1/job"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void enforcesDecompressedSizeAndTotalDeadline() throws Exception {
        SafeHtmlFetcher fetcher = new SafeHtmlFetcher(
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(2), 32, 1);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("<html>this response is deliberately larger than thirty-two bytes</html>".getBytes(StandardCharsets.UTF_8));
        }

        assertThatThrownBy(() -> fetcher.decodeBody(
                compressed.toByteArray(), "gzip", System.nanoTime() + java.time.Duration.ofSeconds(1).toNanos()))
                .isInstanceOf(JobOfferExtractionException.class)
                .hasMessageContaining("too large");
        assertThatThrownBy(() -> fetcher.decodeBody(new byte[0], "identity", System.nanoTime() - 1))
                .isInstanceOf(JobOfferExtractionException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void rejectsOverflowingChunkSizesBeforeAllocationOrRead() {
        SafeHtmlFetcher fetcher = new SafeHtmlFetcher(
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(2), 1024, 1);
        BufferedInputStream response = new BufferedInputStream(new ByteArrayInputStream(
                "ffffffffffffffff\r\n".getBytes(StandardCharsets.US_ASCII)));

        assertThatThrownBy(() -> fetcher.readChunked(
                response, System.nanoTime() + java.time.Duration.ofSeconds(1).toNanos()))
                .isInstanceOf(JobOfferExtractionException.class)
                .hasMessageContaining("too large");
    }
}
