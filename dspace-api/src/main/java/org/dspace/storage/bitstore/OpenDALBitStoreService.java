/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.storage.bitstore;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.hash.HashCode;
import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import com.google.common.net.MediaType;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.core.Context;
import org.dspace.core.Utils;
import org.dspace.storage.bitstore.factory.StorageServiceFactory;
import org.dspace.storage.bitstore.service.BitstreamStorageService;

import org.apache.opendal.*;

/**
 * OpenDALBitStoreService asset store service
 *
 * This class provides an implementation of the BitstreamStorageService
 * using Apache OpenDAL for cloud storage.
 * It supports storing, retrieving, and removing bitstreams in a cloud storage container.
 *
 * Author: Jesiel Viana
 */
public class OpenDALBitStoreService extends BaseBitStoreService {

    // https://opendal.apache.org/integrations/object_store/
    // https://github.com/apache/opendal/discussions/5363

    /** Logger for this class */
    private static final Logger log = LogManager.getLogger(OpenDALBitStoreService.class);

    /** Apache OpenDAL operator */
    private Operator operator;

    /** Storage provider (e.g. "s3", "azblob", "gcs") */
    private String provider;

    /** Bucket/container name */
    private String container;

    /** Optional subfolder prefix */
    private String subfolder;

    /** Authentication credentials */
    private String identity;
    private String credential;

    /** Region or endpoint (depending on provider) */
    private String region;
    private String endpoint;

    /** Whether to use relative paths */
    private boolean useRelativePath;

    /** Whether this service is enabled */
    private boolean enabled = false;

    /** Checksum algorithm used (MD5) */
    private static final String CSA = "MD5";

    public OpenDALBitStoreService() {
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public void setContainer(String container) {
        this.container = container;
    }

    public void setSubfolder(String subfolder) {
        this.subfolder = subfolder;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    public void setCredential(String credential) {
        this.credential = credential;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public void setUseRelativePath(boolean useRelativePath) {
        this.useRelativePath = useRelativePath;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void init() throws IOException {
        if (this.isInitialized()) {
            return;
        }
        try {
           final Map<String, String> conf = new HashMap<>();
           conf.put("region", region);
           conf.put("endpoint", endpoint);
           conf.put("bucket", container);
           conf.put("access_key_id", identity);
           conf.put("secret_access_key", credential);

           this.operator = Operator.of(provider, conf);

            this.initialized = true;
            log.info("OpenDALBitStoreService initialized with provider {}", provider);
        } catch (Exception e) {
            log.error("Failed to initialize OpenDALBitStoreService", e);
            this.initialized = false;
        }
    }

    @Override
    public String generateId() {
        return Utils.generateKey();
    }

    @Override
    public InputStream get(Bitstream bitstream) throws IOException {
        String key = getFullKey(bitstream.getInternalId());
        try {
            byte[] data = operator.read(key);
            return new ByteArrayInputStream(data);
        } catch (Exception e) {
            throw new IOException("Failed to read object: " + key, e);
        }
    }

    @Override
    public void remove(Bitstream bitstream) throws IOException {
        String key = getFullKey(bitstream.getInternalId());
        try {
            operator.delete(key);
        } catch (Exception e) {
            throw new IOException("Failed to delete object: " + key, e);
        }
    }

    public void put(ByteSource byteSource, Bitstream bitstream) throws IOException {
        String key = getFullKey(bitstream.getInternalId());

        String type = MediaType.OCTET_STREAM.toString();
        if (byteSource instanceof BitstreamByteSource) {
            type = getMIMEType(((BitstreamByteSource) byteSource).getBitstream());
        }

        try {
            operator.write(key, byteSource.read());
        } catch (Exception e) {
            throw new IOException("Failed to write object: " + key, e);
        }
    }

    @Override
    public void put(Bitstream bitstream, InputStream in) throws IOException {
        String key = getFullKey(bitstream.getInternalId());
        File scratchFile = File.createTempFile(bitstream.getInternalId(), "opendal");
        try {
            FileUtils.copyInputStreamToFile(in, scratchFile);
            long contentLength = scratchFile.length();
            String localChecksum = org.dspace.curate.Utils.checksum(scratchFile, CSA);

            put(Files.asByteSource(scratchFile), bitstream);

            bitstream.setSizeBytes(contentLength);
            bitstream.setChecksum(localChecksum);
            bitstream.setChecksumAlgorithm(CSA);

        } finally {
            if (!scratchFile.delete()) {
                scratchFile.deleteOnExit();
            }
        }
    }

    public static String getMIMEType(final Bitstream bitstream) {
        try {
            BitstreamFormat format = bitstream.getFormat(new Context());
            return format == null ? null : format.getMIMEType();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> about(Bitstream bitstream, List<String> attrs) throws IOException {
        String key = getFullKey(bitstream.getInternalId());
        Map<String, Object> metadata = new HashMap<>();
        try {
            Metadata meta = operator.stat(key);
            metadata.put("size_bytes", String.valueOf(meta.getContentLength()));
            metadata.put("modified", String.valueOf(meta.getLastModified().toEpochMilli()));
            metadata.put("ContentType", meta.getContentType());

            // checksum is not always available directly
            // you may compute it separately if required
            return metadata;
        } catch (Exception e) {
            throw new IOException("Failed to stat object: " + key, e);
        }
    }

    private String getFullKey(String id) {
        StringBuilder bufFilename = new StringBuilder();
        if (StringUtils.isNotEmpty(this.subfolder)) {
            bufFilename.append(this.subfolder).append("/");
        }

        if (this.useRelativePath) {
            bufFilename.append(getRelativePath(id));
        } else {
            bufFilename.append(id);
        }

        if (log.isDebugEnabled()) {
            log.debug("Container filepath for {} is {}", id, bufFilename.toString());
        }

        return bufFilename.toString();
    }

    private String getRelativePath(String sInternalId) {
        BitstreamStorageService bitstreamStorageService = StorageServiceFactory.getInstance()
                .getBitstreamStorageService();

        String sIntermediatePath = StringUtils.EMPTY;
        if (bitstreamStorageService.isRegisteredBitstream(sInternalId)) {
            sInternalId = sInternalId.substring(2);
        } else {
            sInternalId = sanitizeIdentifier(sInternalId);
            sIntermediatePath = getIntermediatePath(sInternalId);
        }

        return sIntermediatePath + sInternalId;
    }
}
