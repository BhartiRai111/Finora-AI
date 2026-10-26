package com.finora.apigateway;

import com.finora.testkit.AbstractServiceTest;
import com.finora.testkit.TestKeys;
import com.finora.testkit.TestTokens;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class ApiGatewayServiceTest extends AbstractServiceTest {

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        Stubs.register(registry);
        registry.add("finora.security.internal-token.signing.private-key-path",
                () -> TestKeys.privatePem(TestTokens.GATEWAY).toString());
    }
}
