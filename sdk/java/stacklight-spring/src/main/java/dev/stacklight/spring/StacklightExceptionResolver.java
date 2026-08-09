package dev.stacklight.spring;

import dev.stacklight.client.StacklightClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Reports exceptions that reach the dispatcher, and changes nothing else.
 *
 * <p>Written as a {@link HandlerExceptionResolver} that always returns null rather than as
 * a {@code @ControllerAdvice}. A controller advice would have to either handle the
 * exception, which takes over the application's error responses, or rethrow it, which
 * changes where it surfaces. Returning null means "not handled": this sees every exception
 * first and then lets the application's own handling proceed exactly as it would have.
 *
 * <p>Registered at the highest precedence for the same reason — to observe before another
 * resolver converts the exception into a response.
 */
public class StacklightExceptionResolver implements HandlerExceptionResolver, Ordered {

    private final StacklightClient client;

    public StacklightExceptionResolver(StacklightClient client) {
        this.client = client;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {

        client.capture(ex);

        // Null means this resolver did not handle it, so the chain continues untouched.
        return null;
    }
}
