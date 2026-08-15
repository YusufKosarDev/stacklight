package dev.stacklight.backend.ingest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Shared-secret guard over {@code /api}, with more than one secret behind it.
 *
 * <p>Not an authentication feature -- it is quota protection. These endpoints are publicly
 * reachable and the Neon Free plan suspends the project once storage is exhausted, so an
 * unguarded write path is an availability risk before it is a security one.
 *
 * <p>Two secrets rather than one, because the two jobs are not the same privilege. The
 * ingest key is deployed to every installation that reports errors -- both example
 * applications, the workflows, anything anyone wires the SDK into -- and one of those
 * leaking should not also hand over the ability to mark faults resolved. The console key
 * goes to one operator and is typed into one browser.
 *
 * <p>Rules are matched in order and the first prefix that matches decides, so the broadest
 * one is last. That last rule is a catch-all on purpose: an endpoint added under
 * {@code /api} later and forgotten here is guarded by the ingest key rather than being
 * public, which is the failure worth defaulting to.
 *
 * <p>Fails closed throughout: a blank configured key rejects every request that its rule
 * covers, and a request matching no rule at all is rejected too.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String INGEST_HEADER = "X-Stacklight-Key";
    static final String CONSOLE_HEADER = "X-Stacklight-Console-Key";

    /**
     * @param pathPrefix matched against the request path with the context path removed
     * @param header the request header this rule reads the secret from
     * @param expectedKey empty means reject everything this rule covers
     */
    record Rule(String pathPrefix, String header, byte[] expectedKey) {

        static Rule of(String pathPrefix, String header, String expectedKey) {
            return new Rule(
                    pathPrefix,
                    header,
                    expectedKey == null ? new byte[0] : expectedKey.getBytes(StandardCharsets.UTF_8));
        }

        boolean covers(String path) {
            return path.equals(pathPrefix) || path.startsWith(pathPrefix + "/");
        }

        boolean accepts(String provided) {
            byte[] providedBytes =
                    provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
            return expectedKey.length > 0 && MessageDigest.isEqual(providedBytes, expectedKey);
        }
    }

    private final List<Rule> rules;

    ApiKeyFilter(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI().substring(request.getContextPath().length());

        Rule rule = rules.stream().filter(candidate -> candidate.covers(path)).findFirst().orElse(null);

        if (rule == null) {
            reject(response, INGEST_HEADER);
            return;
        }

        if (!rule.accepts(request.getHeader(rule.header()))) {
            reject(response, rule.header());
            return;
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String header) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write("{\"error\":\"missing or invalid " + header + "\"}");
    }
}
