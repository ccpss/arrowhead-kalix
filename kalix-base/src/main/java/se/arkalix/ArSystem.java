package se.arkalix;

import se.arkalix.description.ServiceDescription;
import se.arkalix.internal.ArServer;
import se.arkalix.internal.ArServerRegistry;
import se.arkalix.internal.plugin.PluginNotifier;
import se.arkalix.plugin.Plugin;
import se.arkalix.security.identity.ArSystemCertificateChain;
import se.arkalix.security.identity.ArSystemKeyStore;
import se.arkalix.security.identity.ArTrustStore;
import se.arkalix.util.concurrent.Future;
import se.arkalix.util.concurrent.FutureScheduler;
import se.arkalix.util.concurrent.FutureSchedulerShutdownListener;
import se.arkalix.util.concurrent.Futures;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * An Arrowhead Framework (AHF) system.
 */
public class ArSystem {
    private final String name;
    private final AtomicReference<InetSocketAddress> localSocketAddress = new AtomicReference<>();
    private final boolean isSecure;
    private final ArSystemKeyStore keyStore;
    private final ArTrustStore trustStore;
    private final FutureScheduler scheduler;
    private final FutureSchedulerShutdownListener schedulerShutdownListener;
    private final PluginNotifier pluginNotifier;

    private final Set<ArServer> servers = Collections.synchronizedSet(new HashSet<>());
    private final ArServiceCache serviceCache = new ArServiceCache();
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    private ArSystem(final Builder builder) {
        localSocketAddress.setPlain(Objects.requireNonNullElseGet(builder.socketAddress, () ->
            new InetSocketAddress(0)));

        if (builder.isSecure) {
            isSecure = true;
            if (builder.keyStore == null || builder.trustStore == null) {
                throw new IllegalArgumentException("Expected keyStore and " +
                    "trustStore; required in secure mode");
            }
            keyStore = builder.keyStore;
            trustStore = builder.trustStore;

            final var name0 = keyStore.systemName();
            if (builder.name != null && !Objects.equals(builder.name, name0)) {
                throw new IllegalArgumentException("Expected name either " +
                    "not be provided or to match keyStore system " +
                    "certificate name; \"" + builder.name + "\" != \"" +
                    name0 + "\"");
            }
            name = name0;
        }
        else {
            isSecure = false;
            if (builder.keyStore != null || builder.trustStore != null) {
                throw new IllegalArgumentException("Unexpected keyStore or " +
                    "trustStore; not permitted in insecure mode");
            }
            keyStore = null;
            trustStore = null;

            if (builder.name == null || builder.name.length() == 0) {
                throw new IllegalArgumentException("Expected name; required " +
                    "in insecure mode");
            }
            name = builder.name;
        }

        scheduler = builder.scheduler != null
            ? builder.scheduler
            : FutureScheduler.getDefault();

        schedulerShutdownListener = (scheduler, timeout) -> shutdown()
            .onFailure(Throwable::printStackTrace); // TODO: Log properly.

        scheduler.addShutdownListener(schedulerShutdownListener);

        pluginNotifier = new PluginNotifier(this, builder.plugins != null
            ? builder.plugins
            : Collections.emptyList());
    }

    /**
     * @return Human and machine-readable name of this system.
     */
    public String name() {
        return name;
    }

    /**
     * @return Address and port number of locally bound network interface.
     */
    public InetSocketAddress localSocketAddress() {
        return localSocketAddress.get();
    }

    /**
     * @return Local network interface address.
     */
    public InetAddress localAddress() {
        return localSocketAddress().getAddress();
    }

    /**
     * @return Port through which this system exposes its provided services.
     */
    public int localPort() {
        return localSocketAddress().getPort();
    }

    /**
     * @return {@code true} if and only if this system is configured to run
     * in secure mode.
     */
    public final boolean isSecure() {
        return isSecure;
    }

    /**
     * @return Key store, which represents the identity of this system.
     * @throws UnsupportedOperationException If this system is not running in
     *                                       secure mode.
     */
    public final ArSystemKeyStore keyStore() {
        if (!isSecure) {
            throw new UnsupportedOperationException("System \"" + name() + "\" not in secure mode");
        }
        return keyStore;
    }

    /**
     * @return Trust store, which contains certificates trusted by this system.
     * @throws UnsupportedOperationException If this system is not running in
     *                                       secure mode.
     */
    public final ArTrustStore trustStore() {
        if (!isSecure) {
            throw new UnsupportedOperationException("System \"" + name() + "\" not in secure mode");
        }
        return trustStore;
    }

    /**
     * @return Scheduler being used to handle asynchronous task execution.
     */
    public FutureScheduler scheduler() {
        return scheduler;
    }

    /**
     * Cache of, potentially or previously, consumed services.
     * <p>
     * Searches made in this cache will never trigger any lookup in a remote
     * service registry. The cache is strictly local. It might, however, be
     * updated by any {@link se.arkalix.plugin.Plugin plugins} used by
     * this {@link ArSystem system}.
     *
     * @return Service description cache containing services that this system
     * may have considered to consume.
     */
    public ArServiceCache consumedServices() {
        return serviceCache;
    }

    /**
     * @return Stream of descriptions of all services currently provided by
     * this system.
     */
    public Stream<ServiceDescription> providedServices() {
        return servers.stream()
            .flatMap(server -> server.providedServices()
                .map(ArServiceHandle::description));
    }

    /**
     * Registers given {@code service} with this system, eventually making it
     * accessible to remote Arrowhead systems.
     * <p>
     * It is only acceptable to register any given service once, unless it is
     * first dismissed via the returned handle.
     *
     * @param service Service to be provided by this system.
     * @return Future completed with service handle only if the service can be
     * provided.
     * @throws NullPointerException If {@code service} is {@code null}.
     */
    public Future<ArServiceHandle> provide(final ArService service) {
        Objects.requireNonNull(service, "Expected service");

        if (isShuttingDown.get()) {
            return Future.failure(cannotProvideServiceShuttingDownException());
        }

        for (final var server : servers) {
            if (server.canProvide(service)) {
                return server.provide(service);
            }
        }

        final var serverConstructor = ArServerRegistry.get(service.getClass())
            .orElseThrow(() -> new IllegalArgumentException("" +
                "No Arrowhead server exists for services of type \"" +
                service.getClass() + "\""));

        return serverConstructor.construct(this, pluginNotifier)
            .flatMap(server -> {
                if (isShuttingDown.get()) {
                    return server.close().fail(cannotProvideServiceShuttingDownException());
                }
                localSocketAddress.set(server.localSocketAddress());
                servers.add(server);
                return server.provide(service);
            });
    }

    private Throwable cannotProvideServiceShuttingDownException() {
        return new IllegalStateException("Cannot provide service; system is shutting down");
    }

    /**
     * Initiates system shutdown, causing all of its services to be dismissed.
     * <p>
     * System shutdown is irreversible, meaning it the system cannot be used to
     * provide any more services.
     * <p>
     * System shutdown can also be initiated by shutting down the
     * {@link FutureScheduler scheduler} used by this system.
     *
     * @return Future completed when shutdown is finished.
     */
    public Future<?> shutdown() {
        if (isShuttingDown.getAndSet(true)) {
            return Future.done();
        }
        scheduler.removeShutdownListener(schedulerShutdownListener);
        return Futures.serialize(servers.stream().map(ArServer::close))
            .mapResult(result -> {
                pluginNotifier.clear();
                servers.clear();
                return result;
            });
    }

    /**
     * @return {@code true} only if this system is currently in the process of,
     * or already has, shut down.
     */
    public boolean isShuttingDown() {
        return isShuttingDown.get() || scheduler.isShuttingDown();
    }

    /**
     * Builder useful for creating instances of the {@link ArSystem}
     * class.
     */
    public static class Builder {
        private String name;
        private InetSocketAddress socketAddress;
        private ArSystemKeyStore keyStore;
        private ArTrustStore trustStore;
        private boolean isSecure = true;
        private List<Plugin> plugins;
        private FutureScheduler scheduler;

        /**
         * Sets system name.
         * <p>
         * If running this Arrowhead system in secure mode, the name should
         * either be left unspecified or match the least significant part of
         * the Common Name (CN) in the system certificate. For example, if the
         * CN is "system1.cloud1.arrowhead.eu", then the name must be
         * "system1" or not be set at all. Note that the Arrowhead standard
         * demands that the common name is a dot-separated hierarchical name.
         * If not set, then the least significant part of the certificate
         * provided via {@link #keyStore(ArSystemKeyStore)} is used as name.
         * <p>
         * If not running in secure mode, the name must be specified
         * explicitly. Avoid picking names that contain whitespace or anything
         * but alphanumeric ASCII characters and dashes, as mandated for DNS
         * names by RFC 1035, Section 2.3.1.
         *
         * @param name Name of this system.
         * @return This builder.
         * @see ArSystemCertificateChain More about Arrowhead certifiate names
         * @see <a href="https://tools.ietf.org/html/rfc1035#section-2.3.1">RFC 1035, Section 2.3.1</a>
         */
        public Builder name(final String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the network interface and socket port number to be used by this
         * system when providing its services.
         * <p>
         * If a socket address and/or port has been set previously with any of
         * the other builder setters, only the address is updated by this
         * method.
         * <p>
         * If no socket address or port is specified, the wildcard network
         * interface will be used and a random port will be selected by the
         * system.
         *
         * @param address Internet address associated with the preferred
         *                network interface.
         * @return This builder.
         */
        public Builder localAddress(final InetAddress address) {
            if (socketAddress != null) {
                return localAddressPort(address, socketAddress.getPort());
            }
            return localSocketAddress(new InetSocketAddress(address, 0));
        }

        /**
         * Sets the network interface and socket port number to be used by this
         * system when providing its services.
         * <p>
         * If no socket address or port is specified, the wildcard network
         * interface will be used and a random port will be selected by the
         * system.
         *
         * @param socketAddress Internet socket address associated with the
         *                      preferred network interface.
         * @return This builder.
         */
        public Builder localSocketAddress(final InetSocketAddress socketAddress) {
            this.socketAddress = socketAddress;
            return this;
        }

        /**
         * Sets the network interface by socketAddress and socket port number to be
         * used by this system when providing its services.
         * <p>
         * If no socket address or port is specified, the wildcard network
         * interface will be used and a random port will be selected by the
         * system.
         *
         * @param socketAddress Internet socketAddress associated with the
         *                      preferred network interface.
         * @param port          Socket port number. If 0 is provided, the
         *                      system will choose a random port.
         * @return This builder.
         */
        public Builder localAddressPort(final InetAddress socketAddress, final int port) {
            return localSocketAddress(new InetSocketAddress(socketAddress, port));
        }

        /**
         * Sets the network interface by hostname and socket port number to be
         * used by this system when providing its services.
         * <p>
         * Calling this method with a non-null {@code hostname} will cause a
         * blocking hostname resolution attempt to take place.
         * <p>
         * If no socket hostname or port is specified, the wildcard network
         * interface will be used and a random port will be selected by the
         * system.
         *
         * @param hostname DNS hostname associated with preferred network
         *                 interface.
         * @param port     Socket port number. If 0 is provided, the system
         *                 will choose a random port.
         * @return This builder.
         */
        public Builder localHostnamePort(final String hostname, final int port) {
            return localSocketAddress(new InetSocketAddress(hostname, port));
        }

        /**
         * Sets the socket port number to be used by this system when providing
         * its services.
         * <p>
         * If a socket address has been set previously with any of the other
         * builder setters, only the port number is updated by this method.
         * <p>
         * If no socket address or port is specified, the wildcard network
         * interface will be used and a random port will be selected by the
         * system.
         *
         * @param port Socket port number. If 0 is provided, the system will
         *             choose a random port.
         * @return This builder.
         */
        public Builder localPort(final int port) {
            if (socketAddress != null) {
                final var address = socketAddress.getAddress();
                if (address != null) {
                    return localAddressPort(address, port);
                }
                final var hostname = socketAddress.getHostName();
                if (hostname != null) {
                    return localHostnamePort(hostname, port);
                }
            }
            return localSocketAddress(new InetSocketAddress(port));
        }

        /**
         * Sets key store to use for representing the created system and its
         * services.
         * <p>
         * An {@link ArSystem} either must have or must not have a
         * key store, depending on whether it is running in secure mode or not,
         * respectively.
         *
         * @param keyStore Key store to use.
         * @return This builder.
         */
        public final Builder keyStore(final ArSystemKeyStore keyStore) {
            this.keyStore = keyStore;
            return this;
        }

        /**
         * Sets trust store to use for determining what systems are trusted to
         * be communicated by the created system.
         * <p>
         * An {@link ArSystem} either must have or must not have a
         * trust store, depending on whether it is running in secure mode or
         * not, respectively.
         *
         * @param trustStore Trust store to use.
         * @return This builder.
         */
        public final Builder trustStore(final ArTrustStore trustStore) {
            this.trustStore = trustStore;
            return this;
        }

        /**
         * Explicitly enables insecure mode for this Arrowhead system.
         * <p>
         * In insecure mode, no cryptography is used to establish identities or
         * connections between systems. Usage of this mode is not advised for
         * most kinds of production scenarios.
         * <p>
         * It is an error to provide a key store or a trust store via
         * {@link #keyStore(ArSystemKeyStore)} or
         * {@link #trustStore(ArTrustStore)} if insecure mode is enabled.
         *
         * @return This builder.
         */
        public final Builder insecure() {
            this.isSecure = false;
            return this;
        }

        /**
         * Sets {@link Plugin plugins} to be used by this system.
         * <p>
         * Plugins are useful for listening and reacting to system life-cycle
         * event, and can be used to inject security functionality into
         * services, automatically spin up services, among other things.
         *
         * @param plugins Desired system plugins.
         * @return This builder.
         */
        public Builder plugins(final Plugin... plugins) {
            return plugins(Arrays.asList(plugins));
        }

        /**
         * Sets {@link Plugin plugins} to be used by this system.
         * <p>
         * Plugins are useful for listening and reacting to system life-cycle
         * event, and can be used to inject security functionality into
         * services, automatically spin up services, among other things.
         *
         * @param plugins Desired system plugins.
         * @return This builder.
         */
        public Builder plugins(final List<Plugin> plugins) {
            this.plugins = plugins;
            return this;
        }

        /**
         * Sets scheduler to be used by the created system.
         * <p>
         * If a non-null scheduler is explicitly provided via this method, it
         * becomes the responsibility of the caller to ensure that the
         * scheduler is shut down when no longer in use.
         * <p>
         * If no scheduler is explicitly specified, the one returned by
         * {@link FutureScheduler#getDefault()} is used instead. This means
         * that if several systems are created without schedulers being
         * explicitly set, the systems will all share the same scheduler.
         *
         * @param scheduler Asynchronous task scheduler.
         * @return This builder.
         */
        public Builder scheduler(final FutureScheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * @return New {@link ArSystem}.
         */
        public ArSystem build() {
            return new ArSystem(this);
        }
    }
}
