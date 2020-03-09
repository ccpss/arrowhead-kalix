package eu.arrowhead.kalix;

import eu.arrowhead.kalix.descriptor.ServiceDescriptor;
import eu.arrowhead.kalix.security.X509Certificates;
import eu.arrowhead.kalix.security.X509KeyStore;
import eu.arrowhead.kalix.security.X509TrustStore;
import eu.arrowhead.kalix.util.concurrent.Future;
import eu.arrowhead.kalix.util.concurrent.FutureScheduler;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Base class for all Arrowhead systems.
 *
 * @param <S> Type of service provided by this system.
 */
public abstract class ArrowheadSystem<S> {
    private final String name;
    private final InetSocketAddress socketAddress;
    private final boolean isSecured;
    private final X509KeyStore keyStore;
    private final X509TrustStore trustStore;
    private final FutureScheduler scheduler;

    protected ArrowheadSystem(final Builder<?, ? extends ArrowheadSystem<?>> builder) {
        var name = builder.name;
        socketAddress = Objects.requireNonNullElseGet(builder.socketAddress, () -> new InetSocketAddress(0));

        if (builder.scheduler == null) {
            scheduler = FutureScheduler.getDefault();
        }
        else {
            scheduler = builder.scheduler;
        }

        if (builder.isInsecure) {
            isSecured = false;
            if (builder.keyStore != null || builder.trustStore != null) {
                throw new IllegalArgumentException("Unexpected keyStore or " +
                    "trustStore; not permitted in insecure mode");
            }
            keyStore = null;
            trustStore = null;

            if (name == null || name.length() == 0) {
                throw new IllegalArgumentException("Expected name; required " +
                    "in insecure mode");
            }
            this.name = name;
        }
        else {
            isSecured = true;
            if (builder.keyStore == null || builder.trustStore == null) {
                throw new IllegalArgumentException("Expected keyStore and " +
                    "trustStore; required in secure mode");
            }
            keyStore = builder.keyStore;
            trustStore = builder.trustStore;

            final var descriptor = X509Certificates.subjectDescriptorOf(keyStore.certificate());
            if (descriptor.isEmpty()) {
                throw new IllegalArgumentException("No subject common name " +
                    "in keyStore certificate; required to determine system " +
                    "name");
            }
            final var descriptor0 = descriptor.get();
            final var system = descriptor0.system();
            if (system.isEmpty()) {
                throw new IllegalArgumentException("No Arrowhead system " +
                    "name in keyStore certificate common name \"" +
                    descriptor0 + "\"; that name must match " +
                    "`<system>.<cloud>.<company>.arrowhead.eu`");
            }
            this.name = system.get();
        }
    }

    /**
     * @return Human and machine-readable name of this system.
     */
    public String name() {
        return name;
    }

    /**
     * @return Network interface address.
     */
    public InetAddress localAddress() {
        return socketAddress.getAddress();
    }

    /**
     * @return Port through which this system exposes its provided services.
     */
    public int localPort() {
        return socketAddress.getPort();
    }

    /**
     * @return {@code true} if and only if this system is configured to run
     * in secure mode.
     */
    public boolean isSecured() {
        return isSecured;
    }

    /**
     * @return Key store, which represents the identity of this system.
     * @throws UnsupportedOperationException If this system is not running in
     *                                       secure mode.
     */
    public X509KeyStore keyStore() {
        if (!isSecured) {
            throw new UnsupportedOperationException("System \"" + name() + "\" not in secure mode");
        }
        return keyStore;
    }

    /**
     * @return Trust store, which contains certificates trusted by this system.
     * @throws UnsupportedOperationException If this system is not running in
     *                                       secure mode.
     */
    public X509TrustStore trustStore() {
        if (!isSecured) {
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
     * @return Descriptors representing all services currently provided by this
     * system. The returned array should be a copy that can be modified without
     * having any impact on the set kept internally by this class.
     */
    public abstract ServiceDescriptor[] providedServices();

    /**
     * Registers given {@code service} with system and makes its immediately
     * accessible to other system.
     * <p>
     * Calling this method with a service that is already being provided has no
     * effect.
     *
     * @param service Service to be provided by this system.
     * @throws NullPointerException  If {@code service} is {@code null}.
     * @throws IllegalStateException If {@code service} configuration conflicts
     *                               with an already provided service.
     */
    public abstract void provideService(final S service);

    /**
     * Deregisters given {@code service} from this system, immediately making
     * it inaccessible to other systems.
     * <p>
     * Calling this method with a service that is not currently provided has no
     * effect.
     *
     * @param service Service to no longer be provided by this system.
     * @throws NullPointerException If {@code service} is {@code null}.
     */
    public abstract void dismissService(final S service);

    /**
     * Deregisters all currently registered services from this system.
     */
    public abstract void dismissAllServices();

    /**
     * Starts Arrowhead system, making it serve any services registered via
     * {@link #provideService(Object)} either before or after this method is
     * called.
     *
     * @return Future completed only if (1) starting the system fails, (2) this
     * system crashes or (3) the {@link FutureScheduler} used by this system is
     * shut down.
     */
    public abstract Future<?> serve();

    /**
     * Builder useful for creating instances of classes extending the
     * {@link ArrowheadSystem} class.
     *
     * @param <B>  Type of extending builder class.
     * @param <AS> Type of created {@link ArrowheadSystem} class.
     */
    public static abstract class Builder<B extends Builder<?, AS>, AS extends ArrowheadSystem<?>> {
        private String name;
        private InetSocketAddress socketAddress;
        private X509KeyStore keyStore;
        private X509TrustStore trustStore;
        private boolean isInsecure = false;
        private FutureScheduler scheduler;

        /**
         * @return This builder.
         */
        protected abstract B self();

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
         * provided via {@link #keyStore(X509KeyStore)} is used as name.
         * <p>
         * If not running in secure mode, the name must be specified
         * explicitly. Avoid picking names that contain whitespace or anything
         * but alphanumeric ASCII characters and dashes.
         *
         * @param name Name of this system.
         * @return This builder.
         */
        public final B name(final String name) {
            this.name = name;
            return self();
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
        public final B localAddress(final InetAddress address) {
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
        public final B localSocketAddress(final InetSocketAddress socketAddress) {
            this.socketAddress = socketAddress;
            return self();
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
        public final B localAddressPort(final InetAddress socketAddress, final int port) {
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
        public final B localHostnamePort(final String hostname, final int port) {
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
        public final B localPort(final int port) {
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
         * An {@link ArrowheadSystem} either must have or must not have a
         * key store, depending on whether it is running in secure mode or not,
         * respectively.
         *
         * @param keyStore Key store to use.
         * @return This builder.
         */
        public final B keyStore(final X509KeyStore keyStore) {
            this.keyStore = keyStore;
            return self();
        }

        /**
         * Sets trust store to use for determining what systems are trusted to
         * be communicated by the created system.
         * <p>
         * An {@link ArrowheadSystem} either must have or must not have a
         * trust store, depending on whether it is running in secure mode or
         * not, respectively.
         *
         * @param trustStore Trust store to use.
         * @return This builder.
         */
        public final B trustStore(final X509TrustStore trustStore) {
            this.trustStore = trustStore;
            return self();
        }

        /**
         * Explicitly enables insecure mode for this Arrowhead system.
         * <p>
         * In insecure mode, no cryptography is used to establish identities or
         * connections between systems. Usage of this mode is not advised for
         * most kinds of production scenarios.
         * <p>
         * It is an error to provide a key store or a trust store via
         * {@link #keyStore(X509KeyStore)} or
         * {@link #trustStore(X509TrustStore)} if insecure mode is enabled.
         *
         * @return This builder.
         */
        public final B insecure() {
            this.isInsecure = true;
            return self();
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
        public final B scheduler(final FutureScheduler scheduler) {
            this.scheduler = scheduler;
            return self();
        }

        /**
         * @return New {@link ArrowheadSystem}.
         */
        public abstract AS build();
    }
}
