package com.project.iot_spring.mqtt;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

@Configuration
public class MqttConfig {

    @Value("${mqtt.broker-url}")
    private String brokerUrl;

    @Value("${mqtt.client-id}")
    private String clientId;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Bean
    public MqttClient mqttClient() throws Exception {
        SSLContext sslContext = createSSLContext();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setSocketFactory(sslContext.getSocketFactory());
        options.setCleanSession(true);
        options.setKeepAliveInterval(60);

        MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
        client.connect(options);
        return client;
    }

    private SSLContext createSSLContext() throws Exception {
        // Load CA Certificate
        Certificate caCert = loadCertificate("certs/ca-cert.pem");

        // Load Client Certificate
        Certificate clientCert = loadCertificate("certs/client-cert.pem");

        // Load Client Private Key (PKCS#1)
        PrivateKey privateKey = loadPrivateKey("certs/client-key.pem");

        // Initialize KeyStore
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("ca-cert", caCert);
        keyStore.setCertificateEntry("client-cert", clientCert);
        keyStore.setKeyEntry("client-key", privateKey, "".toCharArray(), new Certificate[]{clientCert});

        // TrustManager
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        // KeyManager
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "".toCharArray());

        // Create SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        return sslContext;
    }

    private Certificate loadCertificate(String path) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new FileNotFoundException("Certificate file not found: " + path);
            }
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return factory.generateCertificate(is);
        }
    }

    private PrivateKey loadPrivateKey(String path) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path);
             InputStreamReader isr = new InputStreamReader(is);
             BufferedReader br = new BufferedReader(isr);
             PEMParser pemParser = new PEMParser(br)) {

            Object object = pemParser.readObject();
            if (!(object instanceof PEMKeyPair)) {
                throw new IllegalArgumentException("Invalid PKCS#1 private key format");
            }

            PEMKeyPair keyPair = (PEMKeyPair) object;
            return new JcaPEMKeyConverter().getPrivateKey(keyPair.getPrivateKeyInfo());
        }
    }
}