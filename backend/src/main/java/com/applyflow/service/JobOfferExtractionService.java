package com.applyflow.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import com.applyflow.dto.application.JobOfferExtractionResponse;
import com.applyflow.dto.application.JobOfferExtractionResponse.CompanySuggestion;
import com.applyflow.dto.application.JobOfferExtractionResponse.Confidence;
import com.applyflow.dto.application.JobOfferExtractionResponse.SalarySuggestion;
import com.applyflow.entity.Company;
import com.applyflow.entity.SalaryPeriod;
import com.applyflow.entity.WorkMode;
import com.applyflow.exception.JobOfferExtractionException;
import com.applyflow.repository.CompanyRepository;
import com.applyflow.repository.JobSourceRepository;
import com.applyflow.exception.RateLimitExceededException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class JobOfferExtractionService {

    private static final int TITLE_LIMIT = 180;
    private static final int COMPANY_LIMIT = 160;
    private static final int WEBSITE_LIMIT = 500;
    private static final int LOCATION_LIMIT = 160;
    private static final int LINKEDIN_METADATA_LIMIT = COMPANY_LIMIT + TITLE_LIMIT + LOCATION_LIMIT + 40;
    private static final Pattern LINKEDIN_JOB_TITLE = Pattern.compile("^(.+?) hiring (.+) in (.+) \\| LinkedIn$");

    private final SafeHtmlFetcher fetcher;
    private final ObjectMapper objectMapper;
    private final CompanyRepository companyRepository;
    private final JobSourceRepository sourceRepository;
    private final Semaphore fetchSlots;

    public JobOfferExtractionService(
            SafeHtmlFetcher fetcher,
            ObjectMapper objectMapper,
            CompanyRepository companyRepository,
            JobSourceRepository sourceRepository,
            @Value("${app.job-offer-extraction.max-concurrent:8}") int maxConcurrent
    ) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("maxConcurrent must be positive");
        this.fetcher = fetcher;
        this.objectMapper = objectMapper;
        this.companyRepository = companyRepository;
        this.sourceRepository = sourceRepository;
        this.fetchSlots = new Semaphore(maxConcurrent);
    }

    public JobOfferExtractionResponse extract(Long ownerId, String url) {
        if (!fetchSlots.tryAcquire()) throw new RateLimitExceededException(1);
        try {
            SafeHtmlFetcher.FetchedHtml fetched = fetcher.fetch(url);
            try {
                Document document = Jsoup.parse(
                        new ByteArrayInputStream(fetched.body()), null, fetched.finalUri().toString());
                return parse(ownerId, fetched.finalUri(), document);
            } catch (IOException exception) {
                throw new JobOfferExtractionException("The job page could not be parsed.", exception);
            }
        } finally {
            fetchSlots.release();
        }
    }

    JobOfferExtractionResponse parse(Long ownerId, URI finalUri, Document document) {
        List<String> warnings = new ArrayList<>();
        Map<String, Confidence> confidence = new LinkedHashMap<>();
        JsonNode posting = findJobPosting(document, warnings);

        String title = null;
        String companyName = null;
        String companyWebsite = null;
        WorkMode workMode = null;
        String location = null;
        SalarySuggestion salary = null;

        if (posting != null) {
            title = bounded(text(posting, "title"), TITLE_LIMIT);
            if (title != null) confidence.put("positionTitle", Confidence.HIGH);

            JsonNode organization = posting.get("hiringOrganization");
            if (organization != null) {
                companyName = bounded(organization.isTextual() ? organization.asText() : text(organization, "name"), COMPANY_LIMIT);
                companyWebsite = safeSuggestedUrl(bounded(firstText(organization, "url", "sameAs"), WEBSITE_LIMIT));
                if (companyName != null) confidence.put("companyName", Confidence.HIGH);
                if (companyWebsite != null) confidence.put("companyWebsite", Confidence.HIGH);
            }

            if (containsToken(posting.get("jobLocationType"), "TELECOMMUTE")) {
                workMode = WorkMode.REMOTE;
                confidence.put("workMode", Confidence.HIGH);
            }
            location = bounded(extractLocation(posting.get("jobLocation")), LOCATION_LIMIT);
            if (location != null) confidence.put("location", Confidence.HIGH);
            salary = extractSalary(posting.get("baseSalary"));
            if (salary != null) {
                if (salary.min() != null) confidence.put("salaryMin", Confidence.HIGH);
                if (salary.max() != null) confidence.put("salaryMax", Confidence.HIGH);
                confidence.put("currency", Confidence.HIGH);
                confidence.put("salaryPeriod", Confidence.HIGH);
            }
        }

        if (posting == null) {
            LinkedInMetadata linkedInMetadata = extractLinkedInMetadata(finalUri, document);
            if (linkedInMetadata != null) {
                title = linkedInMetadata.positionTitle();
                companyName = linkedInMetadata.companyName();
                location = linkedInMetadata.location();
                confidence.put("positionTitle", Confidence.LOW);
                confidence.put("companyName", Confidence.LOW);
                confidence.put("location", Confidence.LOW);
            }
        }
        if (title == null) {
            title = bounded(meta(document, "meta[property=og:title]"), TITLE_LIMIT);
            if (title == null) title = bounded(document.title(), TITLE_LIMIT);
            if (title != null) confidence.put("positionTitle", Confidence.LOW);
        }
        if (companyName == null) {
            companyName = bounded(meta(document, "meta[property=og:site_name]"), COMPANY_LIMIT);
            if (companyName != null) confidence.put("companyName", Confidence.LOW);
        }
        if (posting == null) {
            warnings.add("This page did not expose structured JobPosting data; only conservative page metadata was used.");
        }
        if (title == null && companyName == null) {
            warnings.add("No reliable job details were found. You can still complete the form manually.");
        }

        Long existingCompanyId = null;
        if (companyName != null) {
            List<Company> companies = companyRepository
                    .findAllByOwnerIdAndNameIgnoreCaseOrderByIdAsc(ownerId, companyName);
            existingCompanyId = companies.isEmpty() ? null : companies.getFirst().getId();
        }
        CompanySuggestion company = companyName == null && companyWebsite == null
                ? null : new CompanySuggestion(companyName, companyWebsite, existingCompanyId);
        Long sourceId = resolveSourceId(finalUri.getHost());
        if (sourceId != null) confidence.put("sourceId", Confidence.HIGH);

        return new JobOfferExtractionResponse(
                finalUri.toString(), title, company, sourceId, workMode, location, salary,
                List.copyOf(warnings), Map.copyOf(confidence));
    }

    private LinkedInMetadata extractLinkedInMetadata(URI finalUri, Document document) {
        if (!isHost(finalUri.getHost() == null ? "" : finalUri.getHost().toLowerCase(Locale.ROOT), "linkedin.com")) return null;
        String metadataTitle = meta(document, "meta[property=og:title]");
        if (metadataTitle == null) metadataTitle = document.title();
        if (metadataTitle == null) return null;
        String normalized = metadataTitle.replaceAll("\\s+", " ").trim();
        if (normalized.length() > LINKEDIN_METADATA_LIMIT) return null;
        Matcher matcher = LINKEDIN_JOB_TITLE.matcher(normalized);
        if (!matcher.matches()) return null;
        String companyName = bounded(matcher.group(1), COMPANY_LIMIT);
        String positionTitle = bounded(matcher.group(2), TITLE_LIMIT);
        String location = bounded(matcher.group(3), LOCATION_LIMIT);
        return companyName == null || positionTitle == null || location == null
                ? null : new LinkedInMetadata(companyName, positionTitle, location);
    }

    private record LinkedInMetadata(String companyName, String positionTitle, String location) {}

    private JsonNode findJobPosting(Document document, List<String> warnings) {
        boolean malformedStructuredData = false;
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                JsonNode found = findTypedNode(objectMapper.readTree(script.data()), "JobPosting");
                if (found != null) return found;
            } catch (Exception exception) {
                malformedStructuredData = true;
            }
        }
        if (malformedStructuredData) warnings.add("Malformed structured data was ignored.");
        return null;
    }

    private JsonNode findTypedNode(JsonNode node, String expectedType) {
        if (node == null) return null;
        if (hasType(node.get("@type"), expectedType)) return node;
        if (node.isContainerNode()) {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                JsonNode found = findTypedNode(children.next(), expectedType);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean hasType(JsonNode typeNode, String expectedType) {
        if (typeNode == null) return false;
        if (typeNode.isTextual()) return matchesType(typeNode.asText(), expectedType);
        if (typeNode.isArray()) {
            for (JsonNode item : typeNode) {
                if (item.isTextual() && matchesType(item.asText(), expectedType)) return true;
            }
        }
        return false;
    }

    private boolean matchesType(String actual, String expected) {
        return expected.equalsIgnoreCase(actual)
                || actual.toLowerCase(Locale.ROOT).endsWith("/" + expected.toLowerCase(Locale.ROOT))
                || actual.toLowerCase(Locale.ROOT).endsWith("#" + expected.toLowerCase(Locale.ROOT));
    }

    private String extractLocation(JsonNode jobLocation) {
        if (jobLocation == null) return null;
        JsonNode location = jobLocation.isArray() && !jobLocation.isEmpty() ? jobLocation.get(0) : jobLocation;
        JsonNode address = location == null ? null : location.get("address");
        if (address == null) return null;
        List<String> parts = new ArrayList<>();
        addPart(parts, text(address, "addressLocality"));
        addPart(parts, text(address, "addressRegion"));
        JsonNode country = address.get("addressCountry");
        addPart(parts, country == null ? null : country.isTextual() ? country.asText() : text(country, "name"));
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private SalarySuggestion extractSalary(JsonNode baseSalary) {
        JsonNode salary = baseSalary;
        if (salary == null) return null;
        if (salary.isArray()) salary = salary.isEmpty() ? null : salary.get(0);
        if (salary == null || !salary.isObject()) return null;
        String currency = bounded(text(salary, "currency"), 3);
        JsonNode value = salary.get("value");
        if (value == null) return null;
        String min = decimal(value.get("minValue"));
        String max = decimal(value.get("maxValue"));
        if (min == null && max == null) min = decimal(value.get("value"));
        SalaryPeriod period = salaryPeriod(text(value, "unitText"));
        if ((min == null && max == null) || currency == null || period == null) return null;
        return new SalarySuggestion(min, max, currency.toUpperCase(Locale.ROOT), period);
    }

    private String decimal(JsonNode node) {
        if (node == null || !(node.isNumber() || node.isTextual())) return null;
        try {
            BigDecimal amount = new BigDecimal(node.asText());
            if (amount.signum() < 0 || amount.precision() - amount.scale() > 17 || Math.max(amount.scale(), 0) > 2) return null;
            return amount.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private SalaryPeriod salaryPeriod(String unitText) {
        if (unitText == null) return null;
        return switch (unitText.trim().toUpperCase(Locale.ROOT)) {
            case "YEAR", "YEARLY", "ANNUAL" -> SalaryPeriod.YEARLY;
            case "MONTH", "MONTHLY" -> SalaryPeriod.MONTHLY;
            case "HOUR", "HOURLY" -> SalaryPeriod.HOURLY;
            default -> null;
        };
    }

    private Long resolveSourceId(String host) {
        String source = sourceName(host);
        return sourceRepository.findByNameIgnoreCase(source).map(item -> item.getId()).orElse(null);
    }

    static String sourceName(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (isHost(normalized, "linkedin.com")) return "LinkedIn";
        if (isHost(normalized, "indeed.com")) return "Indeed";
        if (isHost(normalized, "getonbrd.com") || isHost(normalized, "getonboard.com")) return "Get on Board";
        if (isHost(normalized, "ycombinator.com") || isHost(normalized, "workatastartup.com")) return "YC Work at a Startup";
        return "Company Website";
    }

    private static boolean isHost(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private String safeSuggestedUrl(String value) {
        if (value == null) return null;
        try {
            URI uri = URI.create(value.trim());
            return uri.getHost() != null && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    ? bounded(uri.toString(), WEBSITE_LIMIT) : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String meta(Document document, String selector) {
        Element element = document.selectFirst(selector);
        return element == null ? null : element.attr("content");
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isValueNode() ? value.asText() : null;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (bounded(value, WEBSITE_LIMIT) != null) return value;
        }
        return null;
    }

    private boolean containsToken(JsonNode node, String token) {
        if (node == null) return false;
        if (node.isTextual()) return token.equalsIgnoreCase(node.asText());
        if (node.isArray()) {
            for (JsonNode value : node) if (containsToken(value, token)) return true;
        }
        return false;
    }

    private String bounded(String value, int maxLength) {
        if (value == null) return null;
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return null;
        return normalized.substring(0, Math.min(normalized.length(), maxLength));
    }

    private void addPart(List<String> parts, String value) {
        String normalized = bounded(value, LOCATION_LIMIT);
        if (normalized != null && !parts.contains(normalized)) parts.add(normalized);
    }
}
