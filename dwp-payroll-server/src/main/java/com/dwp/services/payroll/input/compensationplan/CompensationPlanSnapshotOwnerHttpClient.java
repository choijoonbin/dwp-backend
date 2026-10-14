package com.dwp.services.payroll.input.compensationplan;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** TLS 1.3 workload client for the exact SVC-PEP-PER-001 owner refetch. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll-compensation-plan-input.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class CompensationPlanSnapshotOwnerHttpClient
        implements CompensationPlanSnapshotOwnerPort {

    static final String PATH_PREFIX =
            "/internal/hris-performance/v1/approved-compensation-plan-snapshots/";
    static final String AUDIENCE =
            "dwp-people:approved-compensation-snapshot-refetch";
    static final String SUBJECT = "dwp-payroll-server";
    static final String SCOPE = "hris.internal";
    static final String CONSUMER_SESSION = "HRIS-PAY";
    static final String POPULATION_POLICY =
            "PAYROLL_INPUT_AUTHORIZED_WORKER_POPULATION_ONLY";
    static final String FIELD_POLICY = "MINIMUM_PAYROLL_INPUT_FIELDS_ONLY";
    private static final String PREFIX =
            "dwp.hris.payroll-compensation-plan-input.owner-refetch.";

    private final RestClient client;
    private final CompensationPlanOwnerTokenIssuer tokens;
    private final Clock clock;
    private final Map<Long, OwnerGrant> grants;

    CompensationPlanSnapshotOwnerHttpClient(
            Environment environment,
            ObjectMapper objectMapper) {
        this(
                transport(environment),
                tokenIssuer(environment, objectMapper),
                Clock.systemUTC(),
                grants(required(environment, PREFIX + "tenant-grants")));
    }

    CompensationPlanSnapshotOwnerHttpClient(
            RestClient client,
            CompensationPlanOwnerTokenIssuer tokens,
            Clock clock,
            Map<Long, OwnerGrant> grants) {
        this.client = Objects.requireNonNull(client);
        this.tokens = Objects.requireNonNull(tokens);
        this.clock = Objects.requireNonNull(clock);
        this.grants = Map.copyOf(grants);
        if (this.grants.isEmpty()) {
            throw new IllegalArgumentException("At least one PAY owner grant is required.");
        }
    }

    @Override
    public Xcon020ApprovedCompensationPlanSnapshotV1 refetch(
            ExactSnapshotRequest request) {
        OwnerGrant grant = grants.get(request.tenantId());
        if (grant == null) {
            throw new SnapshotRejectedException(
                    "The tenant has no current People snapshot owner grant.");
        }
        String path = PATH_PREFIX + request.snapshotId();
        String bearer = tokens.issue(request, grant, path, clock.instant());
        try {
            Xcon020ApprovedCompensationPlanSnapshotV1 result = client.get()
                    .uri(builder -> builder.path(path)
                            .queryParam("snapshotRevision", request.snapshotRevision())
                            .queryParam("payloadDigest", request.payloadDigest())
                            .build())
                    .header("Authorization", "Bearer " + bearer)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(Xcon020ApprovedCompensationPlanSnapshotV1.class);
            if (result == null) {
                throw new TemporarilyUnavailableException(
                        "People returned an empty approved compensation snapshot.");
            }
            return result;
        } catch (SnapshotRejectedException | TemporarilyUnavailableException exception) {
            throw exception;
        } catch (HttpStatusCodeException exception) {
            if (definitiveOwnerRejection(exception.getStatusCode())) {
                throw new SnapshotRejectedException(
                        "People rejected the exact approved compensation snapshot.", exception);
            }
            throw new TemporarilyUnavailableException(
                    "People snapshot authority or transport is temporarily unavailable.",
                    exception);
        } catch (RestClientException exception) {
            throw new TemporarilyUnavailableException(
                    "People snapshot transport is temporarily unavailable.", exception);
        }
    }

    private static boolean definitiveOwnerRejection(HttpStatusCode status) {
        int value = status.value();
        return value == 404 || value == 409 || value == 410 || value == 422;
    }

    private static RestClient transport(Environment environment) {
        URI base = URI.create(required(environment, PREFIX + "base-url"));
        if (!"https".equalsIgnoreCase(base.getScheme())
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null
                || (base.getPath() != null && !base.getPath().isBlank()
                && !"/".equals(base.getPath()))) {
            throw new IllegalStateException(
                    PREFIX + "base-url must be an origin-only HTTPS URI.");
        }
        Duration connectTimeout = duration(
                environment, PREFIX + "connect-timeout", Duration.ofSeconds(2));
        Duration readTimeout = duration(
                environment, PREFIX + "read-timeout", Duration.ofSeconds(30));
        SSLContext sslContext = sslContext(environment);
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(new String[]{"TLSv1.3"});
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .sslContext(sslContext)
                .sslParameters(parameters)
                .version(HttpClient.Version.HTTP_2)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(base.toString())
                .requestFactory(factory)
                .build();
    }

    private static SSLContext sslContext(Environment environment) {
        char[] storePassword = required(environment,
                PREFIX + "key-store-password").toCharArray();
        char[] keyPassword = required(environment,
                PREFIX + "key-password").toCharArray();
        char[] trustPassword = required(environment,
                PREFIX + "trust-store-password").toCharArray();
        try {
            KeyStore keys = loadStore(
                    required(environment, PREFIX + "key-store"), storePassword);
            KeyStore trust = loadStore(
                    required(environment, PREFIX + "trust-store"), trustPassword);
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, keyPassword);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(
                    keyManagers.getKeyManagers(), trustManagers.getTrustManagers(),
                    new SecureRandom());
            return context;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "PAY owner-refetch mTLS material is invalid.", exception);
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
            Arrays.fill(trustPassword, '\0');
        }
    }

    private static KeyStore loadStore(String location, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(Path.of(location))) {
            store.load(input, password);
        }
        return store;
    }

    private static CompensationPlanOwnerTokenIssuer tokenIssuer(
            Environment environment,
            ObjectMapper objectMapper) {
        try {
            RSAKey key = RSAKey.parse(required(environment, PREFIX + "private-jwk"));
            if (!key.isPrivate()
                    || key.size() < 2048
                    || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                    || !JWSAlgorithm.RS256.equals(key.getAlgorithm())
                    || key.getKeyID() == null
                    || key.getKeyID().isBlank()) {
                throw new IllegalStateException("PAY owner-refetch private JWK is invalid.");
            }
            return new CompensationPlanOwnerTokenIssuer(
                    objectMapper, key, required(environment, PREFIX + "issuer"));
        } catch (java.text.ParseException exception) {
            throw new IllegalStateException("PAY owner-refetch private JWK is invalid.", exception);
        }
    }

    private static Duration duration(
            Environment environment, String name, Duration fallback) {
        Duration value = environment.getProperty(name, Duration.class, fallback);
        if (value == null || value.isNegative() || value.isZero()
                || value.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException(name + " must be within (0, PT2M].");
        }
        return value;
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalStateException(name + " is required.");
        }
        return value;
    }

    static Map<Long, OwnerGrant> grants(String value) {
        LinkedHashMap<Long, OwnerGrant> result = new LinkedHashMap<>();
        try {
            for (String raw : value.split(",", -1)) {
                String[] fields = raw.strip().split(":", -1);
                if (fields.length != 3) throw new IllegalArgumentException();
                long tenantId = Long.parseLong(fields[0]);
                OwnerGrant grant = new OwnerGrant(
                        Long.parseLong(fields[1]), Long.parseLong(fields[2]));
                if (tenantId <= 0 || result.putIfAbsent(tenantId, grant) != null) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    PREFIX + "tenant-grants must be tenant:grantRevision:policyRevision.",
                    exception);
        }
        return Map.copyOf(result);
    }

    record OwnerGrant(long tenantGrantRevision, long policyRevision) {
        OwnerGrant {
            if (tenantGrantRevision <= 0 || policyRevision <= 0) {
                throw new IllegalArgumentException("Owner grant revisions must be positive.");
            }
        }
    }
}
