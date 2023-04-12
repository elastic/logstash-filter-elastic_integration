/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.util;

import co.elastic.logstash.api.Password;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KeyStoreUtil {
    private KeyStoreUtil() {
        // UTILITY CLASS
    }

    private static String guessKeystoreType(final Path path) {
        final String filename = path.getFileName().toString();
        if (filename.endsWith(".jks")) {
            return "jks";
        }
        if (filename.endsWith(".p12")) {
            return "pkcs12";
        }

        return KeyStore.getDefaultType();
    }

    public static KeyStore load(final Path keyStorePath,
                                final Password keyStorePassword) {
        try {
            final String keystoreType = guessKeystoreType(keyStorePath);
            final KeyStore keyStore = KeyStore.getInstance(keystoreType);

            keyStore.load(Files.newInputStream(keyStorePath), keyStorePassword.getPassword().toCharArray());

            return keyStore;
        } catch (Exception e) {
            throw new RuntimeException(String.format("Failed to load keystore from `%s`", keyStorePath), e);
        }
    }

    public static KeyStore fromCertificateAuthorities(final List<Path> certAuthoritiesPaths) {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");

            final KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(() -> new KeyStore.PasswordProtection("test".toCharArray()));

            for (Path certAuthorityPath : certAuthoritiesPaths) {
                try (InputStream in = Files.newInputStream(certAuthorityPath)) {
                    @SuppressWarnings("unchecked") // X.509-type CertificateFactory#generateCertificates always returns List<X509Certificate>
                    List<X509Certificate> certChains = (List<X509Certificate>) certificateFactory.generateCertificates(in);
                    for (X509Certificate trustedCert : certChains) {
                        keyStore.setCertificateEntry(trustedCert.getSubjectX500Principal().getName(), trustedCert);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(String.format("Failed to extract certificates from `%s`", certAuthorityPath), e);
                }
            }

            return keyStore;
        } catch (Exception e) {
            throw new RuntimeException(String.format("Failed to build keystore from certificate authorities `%s`", certAuthoritiesPaths), e);
        }
    }

    final static Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
            "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + // Header
                    "([a-z0-9+/=\\r\\n]+)" +                       // Base64 text
                    "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",            // Footer
            Pattern.CASE_INSENSITIVE);

    public static KeyStore fromCertKeyPair(final Path cert,
                                           final Path key,
                                           final Password keyPassword) {
        try {
            final CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            final KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            final PKCS8EncodedKeySpec keySpec = readPrivateKey(key, keyPassword);
            final PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            final KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(() -> new KeyStore.PasswordProtection(keyPassword.getPassword().toCharArray()));

            try (InputStream in = Files.newInputStream(cert)) {
                @SuppressWarnings("unchecked") // X.509-type CertificateFactory#generateCertificates always returns List<X509Certificate>
                List<X509Certificate> certChain = (List<X509Certificate>) certificateFactory.generateCertificates(in);
                keyStore.setKeyEntry("key", privateKey, keyPassword.getPassword().toCharArray(), certChain.toArray(new Certificate[0]));
            }

            return keyStore;
        } catch (Exception e) {
            throw new RuntimeException(String.format("Failed to build keystore from certificate/key pair `%s`/`%s`", cert, key), e);
        }
    }

    private static PKCS8EncodedKeySpec readPrivateKey(final Path key, final Password password) throws Exception {
        final String rawKey = Files.readString(key, StandardCharsets.US_ASCII);
        final Matcher matcher = PRIVATE_KEY_PATTERN.matcher(rawKey);
        if (!matcher.find()) {
            throw new KeyStoreException(String.format("found no PEM-encoded private key in file: %s", key));
        }
        byte[] encodedKey = Base64.getMimeDecoder().decode(matcher.group(1));

        if (password == null) {
            return new PKCS8EncodedKeySpec(encodedKey);
        }
        try {
            final EncryptedPrivateKeyInfo encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(encodedKey);
            final SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName());
            final SecretKey secretKey = keyFactory.generateSecret(new PBEKeySpec(password.getPassword().toCharArray()));

            Cipher cipher = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName());
            cipher.init(Cipher.DECRYPT_MODE, secretKey, encryptedPrivateKeyInfo.getAlgParameters());

            return encryptedPrivateKeyInfo.getKeySpec(cipher);
        } catch (Exception e) {
            throw new RuntimeException(String.format("failed to import possibly-encrypted PKCS8-encoded key from file: %s", key), e);
        }
    }
}
