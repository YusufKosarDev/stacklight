package dev.stacklight.backend.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts one id on every line a request produces.
 *
 * <p>The ingest path does its whole job inside a single request -- grouping, the group and
 * rollup upserts, detection, and the alert row -- so the lines it emits are already related
 * by being close together in the log. That stops being true the moment two events arrive at
 * once, which on a woken instance draining a client's queue is the normal case rather than
 * the rare one. This is what tells those two apart.
 *
 * <p>The id also goes back on the response, so a caller that kept one can be matched to what
 * the server recorded without either side having to guess from timestamps.
 *
 * <h2>Why an inbound header is not simply trusted</h2>
 *
 * <p>Honouring {@code X-Request-Id} lets a client stitch its own trace to this one, which is
 * the point of accepting it. It also means a caller chooses text that this process writes to
 * its log, and a value carrying a newline could forge a whole line -- a log entry that looks
 * like it came from here and did not. So an inbound id is accepted only when it is short and
 * made of characters that cannot break a line or a JSON string; anything else is replaced
 * rather than rejected, because a malformed id is not a reason to refuse an error report.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String id = accept(request.getHeader(HEADER));

        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat reuses threads, so an id left behind would be attributed to whatever
            // request landed on this thread next.
            MDC.remove(MDC_KEY);
        }
    }

    private static String accept(String provided) {
        if (provided == null || provided.isEmpty() || provided.length() > MAX_LENGTH) {
            return generate();
        }
        for (int i = 0; i < provided.length(); i++) {
            char c = provided.charAt(i);
            boolean safe =
                    (c >= 'a' && c <= 'z')
                            || (c >= 'A' && c <= 'Z')
                            || (c >= '0' && c <= '9')
                            || c == '-'
                            || c == '_';
            if (!safe) {
                return generate();
            }
        }
        return provided;
    }

    private static String generate() {
        return UUID.randomUUID().toString();
    }
}
