package com.turnkey.naicombacklog.config;

import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

/**
 * The live tps-apis NAICOM integration disables TLS certificate/hostname verification
 * for the outbound NAICOM call (its {@code NaicomIntegrationService.init()} installs an
 * all-trusting SSLContext). That is preserved here behind {@code naicom.trust-all-ssl}
 * (default true, matching the working behaviour) so posting keeps working against the
 * same endpoint - flip it to false once NAICOM presents a verifiable certificate.
 */
@Configuration
@Slf4j
public class RestClientConfig {

    @Bean
    public OkHttpClient okHttpClient(@Value("${naicom.trust-all-ssl:true}") boolean trustAllSsl,
                                     @Value("${naicom.http.max-idle-connections:64}") int maxIdleConnections,
                                     @Value("${naicom.http.keep-alive-minutes:5}") long keepAliveMinutes) {
        // OkHttp keeps only 5 idle connections by default. Posting a backlog several policies at a
        // time to a single host then evicts most connections between calls, so nearly every post
        // pays for a fresh TCP connect and TLS handshake. Holding one idle connection per in-flight
        // poster instead lets them all be reused.
        ConnectionPool connectionPool = new ConnectionPool(maxIdleConnections, keepAliveMinutes, TimeUnit.MINUTES);

        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(maxIdleConnections);
        dispatcher.setMaxRequestsPerHost(maxIdleConnections);

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .connectionPool(connectionPool)
                .dispatcher(dispatcher)
                .retryOnConnectionFailure(true);

        if (trustAllSsl) {
            log.warn("naicom.trust-all-ssl is enabled: TLS certificate and hostname verification is DISABLED for NAICOM calls.");
            try {
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            }

                            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            }

                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                builder.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);
                builder.hostnameVerifier((hostname, session) -> true);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to configure trust-all SSL context", e);
            }
        }
        return builder.build();
    }
}
