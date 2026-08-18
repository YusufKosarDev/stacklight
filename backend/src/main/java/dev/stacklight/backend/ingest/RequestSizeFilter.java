package dev.stacklight.backend.ingest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a body too large to be worth reading.
 *
 * <p>The batch endpoint caps its list at a hundred events, but that cap is a bean
 * validation: it is checked after the body has been read and turned into objects, by which
 * point the memory has already been spent. On a 512 MB instance that is the wrong order --
 * a request claiming to carry a million events would be parsed first and rejected second.
 *
 * <p>So the declared length is checked before anything is read. It is not a substitute for
 * the cap, which still decides how many events a batch may contain; it is the cheaper of
 * the two guards, and the one that runs first.
 *
 * <p>A hundred events at their maximum -- 4,000 characters of message and 20,000 of stack
 * trace apiece -- is roughly 2.5 MB. The limit is four, which leaves room for JSON overhead
 * and for fields growing later without this turning into a surprise.
 *
 * <p>A request that declares no length is let through: chunked encoding is legitimate, and
 * the container's own limits apply to it. This closes the honest mistake and the cheap
 * attack, not every possible one.
 */
public class RequestSizeFilter extends OncePerRequestFilter {

    static final long MAX_BYTES = 4L * 1024 * 1024;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long declared = request.getContentLengthLong();

        if (declared > MAX_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.getWriter()
                    .write("{\"error\":\"request body over " + MAX_BYTES + " bytes\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
