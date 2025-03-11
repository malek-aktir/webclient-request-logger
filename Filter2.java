import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Publisher;

import java.nio.charset.StandardCharsets;

/**
 * A WebClient filter that logs the request body content using SLF4J.
 * This filter works by buffering the entire request body, logging it,
 * and then passing it on to the actual HTTP client.
 */
public class RequestBodyLoggingFilter implements ExchangeFilterFunction {
    private static final Logger logger = LoggerFactory.getLogger(RequestBodyLoggingFilter.class);

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        // Only proceed with logging if there's a body
        if (request.body().isEmpty()) {
            logger.debug("Request to {}: No body", request.url());
            return next.exchange(request);
        }

        // Create a modified request with our logging decorator
        return next.exchange(ClientRequest.from(request)
                .body((outputMessage, context) -> 
                    request.body().insert(new LoggingDecorator(outputMessage, request), context))
                .build());
    }

    /**
     * Decorator that buffers the entire request body, logs it, and then writes it to the delegate
     */
    private static final class LoggingDecorator extends ClientHttpRequestDecorator {
        private final ClientRequest originalRequest;

        private LoggingDecorator(ClientHttpRequest delegate, ClientRequest originalRequest) {
            super(delegate);
            this.originalRequest = originalRequest;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body)
                    .flatMap(buffer -> {
                        // Read the buffer content as a string for logging
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        String bodyContent = new String(bytes, StandardCharsets.UTF_8);
                        
                        // Log the request details with body
                        logger.debug("Request to {} {}: Body = {}",
                                originalRequest.method(),
                                originalRequest.url(),
                                bodyContent);
                        
                        // Create a new buffer with the same content since we've consumed the original
                        DataBuffer newBuffer = getDelegate().bufferFactory().wrap(bytes);
                        
                        // Write the new buffer to the delegate
                        return getDelegate().writeWith(Mono.just(newBuffer));
                    });
        }
    }
}