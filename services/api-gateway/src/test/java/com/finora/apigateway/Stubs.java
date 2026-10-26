package com.finora.apigateway;

import com.finora.testkit.StubServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;

/** One real loopback HTTP stub per downstream service, shared by the gateway tests in this JVM. */
final class Stubs {

    static final List<String> SERVICES = List.of("identity-access", "finance", "document", "ingestion-integration",
            "reporting", "intelligence", "platform", "audit");
    private static final Map<String, StubServer> SERVERS = new LinkedHashMap<>();
    /**
     * A port that refuses connections: models a service that is down. The port is held by a bound-but-never-listening
     * socket for the life of the JVM, so no other server can take it (connecting to it fails with "connection refused").
     */
    private static final Socket DEAD_PORT_HOLDER = new Socket();
    static final int DEAD_PORT = reserveDeadPort();

    static {
        for (String s : SERVICES) {
            SERVERS.put(s, new StubServer());
        }
    }

    private Stubs() {
    }

    static StubServer get(String service) {
        return SERVERS.get(service);
    }

    static void resetAll() {
        SERVERS.values().forEach(StubServer::reset);
    }

    static void register(DynamicPropertyRegistry registry) {
        SERVERS.forEach((name, stub) -> registry.add("finora.gateway.services." + name, stub::baseUrl));
    }

    static void registerWithDown(DynamicPropertyRegistry registry, String downService) {
        register(registry);
        registry.add("finora.gateway.services." + downService, () -> "http://127.0.0.1:" + DEAD_PORT);
    }

    private static int reserveDeadPort() {
        try {
            DEAD_PORT_HOLDER.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return DEAD_PORT_HOLDER.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
