package org.fabt.observability.anchor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

import com.oracle.bmc.Region;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase G-3 — OCI Object Storage client configuration for the external
 * audit-chain anchor.
 *
 * <p><b>Active only when {@code fabt.oci.audit-anchor.enabled=true}</b>. In
 * any other profile (dev, CI, local) the {@code @ConditionalOnProperty} keeps
 * the entire OCI integration off — no SDK initialisation, no key file read,
 * no network reachability assumptions. This is the same pattern used by
 * other optional FABT integrations (e.g. webhook delivery in test mode).
 *
 * <p><b>Private key handling</b>: the SDK's {@code SimpleAuthenticationDetailsProvider}
 * accepts a {@code Supplier<InputStream>} that yields the PEM bytes. We
 * supply a lazy file-reader keyed off the configured path. The key contents
 * never enter Java heap until SDK auth handshake; we never log, surface, or
 * persist the bytes ourselves.
 *
 * <p>Region is resolved via {@link Region#fromRegionId} from the configured
 * {@code region} property (e.g. {@code us-ashburn-1}). The endpoint is
 * derived by the SDK; we do not hardcode endpoint URLs.
 */
@Configuration
@ConditionalOnProperty(prefix = "fabt.oci.audit-anchor", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OciAuditAnchorProperties.class)
public class OciAuditAnchorConfig {

    private static final Logger log = LoggerFactory.getLogger(OciAuditAnchorConfig.class);

    @Bean
    public AuthenticationDetailsProvider ociAuthenticationDetailsProvider(OciAuditAnchorProperties props) {
        validateRequired("region", props.region());
        validateRequired("tenancyOcid", props.tenancyOcid());
        validateRequired("userOcid", props.userOcid());
        validateRequired("fingerprint", props.fingerprint());
        validateRequired("privateKeyPath", props.privateKeyPath());

        File keyFile = new File(props.privateKeyPath());
        if (!keyFile.exists() || !keyFile.canRead()) {
            throw new IllegalStateException(
                    "OCI private key not readable at configured path. "
                    + "Verify FABT_OCI_PRIVATE_KEY_PATH points to the deployed "
                    + "service-principal key file with mode 600 owned by the "
                    + "FABT process user. (Path is logged here without contents.)");
        }

        // Supplier reads the file lazily on each SDK auth event. The SDK
        // closes the InputStream after consumption.
        Supplier<InputStream> keySupplier = () -> {
            try {
                return new FileInputStream(keyFile);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to open OCI private key file at the configured path "
                        + "(contents not logged).", e);
            }
        };

        log.info("OCI audit-anchor: building authentication provider for region={} tenancy={} user={} fingerprint=[redacted]",
                props.region(), props.tenancyOcid(), props.userOcid());

        return SimpleAuthenticationDetailsProvider.builder()
                .tenantId(props.tenancyOcid())
                .userId(props.userOcid())
                .fingerprint(props.fingerprint())
                .privateKeySupplier(keySupplier)
                .region(Region.fromRegionId(props.region()))
                .build();
    }

    @Bean(destroyMethod = "close")
    public ObjectStorage ociObjectStorageClient(AuthenticationDetailsProvider auth,
                                                OciAuditAnchorProperties props) {
        validateRequired("namespace", props.namespace());
        validateRequired("bucket", props.bucket());
        validateRequired("compartmentOcid", props.compartmentOcid());
        log.info("OCI audit-anchor: ObjectStorageClient configured for namespace={} bucket={}",
                props.namespace(), props.bucket());
        return ObjectStorageClient.builder().build(auth);
    }

    private static void validateRequired(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "OCI audit-anchor enabled but required property '" + name + "' is missing or blank. "
                    + "Set fabt.oci.audit-anchor." + name + " (or the FABT_OCI_* env var) before enabling.");
        }
    }
}
