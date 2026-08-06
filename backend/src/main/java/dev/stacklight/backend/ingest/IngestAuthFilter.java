package dev.stacklight.backend.ingest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Shared-secret guard on the ingestion endpoint.
 *
 * <p>Not an authentication feature -- it is quota protection. The endpoint is publicly
 * reachable and the Neon Free plan suspends the project once storage is exhausted, so an
 * unguarded write path is an availability risk, not just a security one.
 *
 * <p>Fails closed: a blank configured key rejects every request.
 */
public class IngestAuthFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Stacklight-Key";

    private final byte[] expectedKey;

    IngestAuthFilter(String expectedKey) {
        this.expectedKey = expectedKey == null ? new byte[0] : expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String provided = request.getHeader(HEADER);
        byte[] providedBytes =
                provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);

        if (expectedKey.length == 0 || !MessageDigest.isEqual(providedBytes, expectedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.getWriter().write("{\"error\":\"missing or invalid " + HEADER + "\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
