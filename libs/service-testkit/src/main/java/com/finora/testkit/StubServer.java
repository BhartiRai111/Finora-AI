package com.finora.testkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server on loopback that plays a downstream service in tests. It is a test double for the network peer
 * only: the code under test makes genuine HTTP calls to it. Never used outside tests.
 */
public final class StubServer implements AutoCloseable {

    public record Received(String method, String path, Map<String, String> headers, String body) {
    }

    public record Reply(int status, Map<String, String> headers, String body, long delayMillis) {
        public static Reply json(int status, String body) {
            return new Reply(status, Map.of("Content-Type", "application/json"), body, 0);
        }

        public Reply delayed(long millis) {
            return new Reply(status, headers, body, millis);
        }
    }

    @FunctionalInterface
    public interface Responder {
        Reply reply(Received request, int callNumber);
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Responder responder = (r, n) -> Reply.json(200, "{}");

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubServer respond(Responder responder) {
        this.responder = responder;
        return this;
    }

    public StubServer reset() {
        received.clear();
        calls.set(0);
        responder = (r, n) -> Reply.json(200, "{}");
        return this;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public int callCount() {
        return calls.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        Received request = new Received(exchange.getRequestMethod(), path, headers, body);
        received.add(request);
        int number = calls.incrementAndGet();
        Reply reply = responder.reply(request, number);
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        try {
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException ignored) {
            // client gave up (timeout test): nothing to do
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
