import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;

import java.nio.charset.StandardCharsets;

@Configuration
public class WebClientConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(WebClientConfig.class);
    
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .filter(logRequestFilter())
                .build();
    }
    
    private ExchangeFilterFunction logRequestFilter() {
        return (request, next) -> {
            logger.info("Request: {} {}", request.method(), request.url());
            request.headers().forEach((name, values) -> 
                values.forEach(value -> logger.info("Header: {}={}", name, value)));
            
            if (request.body() != null) {
                return next.exchange(ClientRequest.from(request)
                        .body((outputMessage, context) -> {
                            BodyInserter<?, ? super ClientHttpRequest> bodyInserter = request.body();
                            
                            return bodyInserter.insert(new BufferingClientHttpRequestDecorator(outputMessage), context)
                                    .doOnSuccess(v -> {
                                        byte[] content = ((BufferingClientHttpRequestDecorator) outputMessage).getBufferedContent();
                                        if (content.length > 0) {
                                            logger.info("Request body: {}", new String(content, StandardCharsets.UTF_8));
                                        }
                                    });
                        })
                        .build());
            } else {
                logger.info("Request body: <empty>");
                return next.exchange(request);
            }
        };
    }
    
    /**
     * A decorator that buffers the body content for logging
     */
    private static class BufferingClientHttpRequestDecorator implements ClientHttpRequest {
        private final ClientHttpRequest delegate;
        private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        BufferingClientHttpRequestDecorator(ClientHttpRequest delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
        
        @Override
        public DataBufferFactory bufferFactory() {
            return delegate.bufferFactory();
        }
        
        @Override
        public void beforeCommit(Supplier<? extends Mono<Void>> action) {
            delegate.beforeCommit(action);
        }
        
        @Override
        public boolean isCommitted() {
            return delegate.isCommitted();
        }
        
        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return Flux.from(body)
                    .doOnNext(this::buffer)
                    .then(Mono.defer(() -> {
                        return delegate.writeWith(Mono.just(bufferFactory.wrap(getBufferedContent())));
                    }));
        }
        
        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).flatMap(p -> p));
        }
        
        @Override
        public Mono<Void> setComplete() {
            return delegate.setComplete();
        }
        
        private void buffer(DataBuffer buffer) {
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                baos.write(bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                DataBufferUtils.release(buffer);
            }
        }
        
        byte[] getBufferedContent() {
            return baos.toByteArray();
        }
    }
}