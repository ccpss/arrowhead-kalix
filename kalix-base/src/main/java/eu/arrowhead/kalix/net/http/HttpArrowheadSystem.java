package eu.arrowhead.kalix.net.http;

import eu.arrowhead.kalix.ArrowheadSystem;
import eu.arrowhead.kalix.descriptor.InterfaceDescriptor;
import eu.arrowhead.kalix.descriptor.ServiceDescriptor;
import eu.arrowhead.kalix.descriptor.TransportDescriptor;
import eu.arrowhead.kalix.internal.net.NettyBootstraps;
import eu.arrowhead.kalix.internal.net.http.NettyHttpServiceConnectionInitializer;
import eu.arrowhead.kalix.internal.util.concurrent.NettyScheduler;
import eu.arrowhead.kalix.internal.util.logging.LogLevels;
import eu.arrowhead.kalix.net.http.service.HttpService;
import eu.arrowhead.kalix.util.concurrent.Future;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static eu.arrowhead.kalix.internal.util.concurrent.NettyFutures.adapt;

/**
 * An {@link ArrowheadSystem} that provides {@link HttpService}s.
 */
public class HttpArrowheadSystem extends ArrowheadSystem<HttpService> {
    private final AtomicReference<InetSocketAddress> localSocketAddress = new AtomicReference<>();
    private final TreeMap<String, HttpService> providedServices = new TreeMap<>();

    // Created when requested.
    private ServiceDescriptor[] providedServiceDescriptors = null;

    private HttpArrowheadSystem(final Builder builder) {
        super(builder);
    }

    @Override
    public InetAddress localAddress() {
        final var socketAddress = localSocketAddress.get();
        return socketAddress != null
            ? socketAddress.getAddress()
            : super.localAddress();
    }

    @Override
    public int localPort() {
        final var socketAddress = localSocketAddress.get();
        return socketAddress != null
            ? socketAddress.getPort()
            : super.localPort();
    }

    @Override
    public synchronized ServiceDescriptor[] providedServices() {
        if (providedServiceDescriptors == null) {
            final var descriptors = new ServiceDescriptor[providedServices.size()];
            var i = 0;
            for (final var service : providedServices.values()) {
                descriptors[i++] = new ServiceDescriptor(service.name(), Stream.of(service.encodings())
                    .map(encoding -> InterfaceDescriptor.getOrCreate(TransportDescriptor.HTTP, isSecured(), encoding))
                    .collect(Collectors.toList()));
            }
            providedServiceDescriptors = descriptors;
        }
        return providedServiceDescriptors.clone();
    }

    @Override
    public synchronized void provideService(final HttpService service) {
        Objects.requireNonNull(service, "Expected service");
        final var existingService = providedServices.putIfAbsent(service.basePath(), service);
        if (existingService != null) {
            if (existingService == service) {
                return;
            }
            throw new IllegalStateException("Base path \"" +
                service.basePath() + "\" already in use by  \"" +
                existingService.name() + "\"; cannot provide \"" +
                service.name() + "\"");
        }
        providedServiceDescriptors = null; // Force recreation.
    }

    @Override
    public synchronized void dismissService(final HttpService service) {
        Objects.requireNonNull(service, "Expected service");
        if (providedServices.remove(service.basePath()) != null) {
            providedServiceDescriptors = null; // Force recreation.
        }
    }

    @Override
    public synchronized void dismissAllServices() {
        providedServices.clear();
        providedServiceDescriptors = null; // Force recreation.
    }

    private synchronized Optional<HttpService> getServiceByPath(final String path) {
        for (final var entry : providedServices.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    @Override
    public Future<?> serve() {
        try {
            SslContext sslContext = null;
            if (isSecured()) {
                final var keyStore = keyStore();
                sslContext = SslContextBuilder
                    .forServer(keyStore.privateKey(), keyStore.certificateChain())
                    .trustManager(trustStore().certificates())
                    .clientAuth(ClientAuth.REQUIRE)
                    .startTls(false)
                    .build();
            }
            return adapt(NettyBootstraps
                .createServerBootstrapUsing((NettyScheduler) scheduler())
                .handler(new LoggingHandler(LogLevels.toNettyLogLevel(logLevel())))
                .childHandler(new NettyHttpServiceConnectionInitializer(this::getServiceByPath, logLevel(), sslContext))
                .bind(super.localAddress(), super.localPort()))
                .flatMap(channel -> {
                    localSocketAddress.set((InetSocketAddress) channel.localAddress());
                    return adapt(channel.closeFuture());
                });
        }
        catch (final Throwable throwable) {
            return Future.failure(throwable);
        }
    }

    public static class Builder extends ArrowheadSystem.Builder<Builder, HttpArrowheadSystem> {
        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public HttpArrowheadSystem build() {
            return new HttpArrowheadSystem(this);
        }
    }
}
