package com.dwp.gateway.productsurface;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class ProductSurfaceContextRouterConfiguration {

    public static final String CONTEXTS_HANDLER_PATH =
            ProductSurfaceGatewayRoutes.CONTEXTS_HANDLER_PATH;
    public static final String PRODUCT_EVALUATION_HANDLER_PATH =
            ProductSurfaceGatewayRoutes.PRODUCT_EVALUATION_HANDLER_PATH;
    public static final String GOVERNED_EVALUATION_HANDLER_PATH =
            ProductSurfaceGatewayRoutes.GOVERNED_EVALUATION_HANDLER_PATH;

    @Bean
    RouterFunction<ServerResponse> productSurfaceContextRoutes(
            ProductSurfaceContextHandler handler) {
        return RouterFunctions.route()
                .GET(CONTEXTS_HANDLER_PATH, handler::contexts)
                .POST(PRODUCT_EVALUATION_HANDLER_PATH, handler::evaluateProduct)
                .POST(GOVERNED_EVALUATION_HANDLER_PATH, handler::evaluateGoverned)
                .build();
    }
}
