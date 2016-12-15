package reactor.ipc.netty.http;

import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientException;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;

/**
 *
 */
public class SniTest {

	private static X509TrustManager getTrustManager() throws Exception {
		TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trustManagerFactory.init((KeyStore) null);
		for (TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
			if (trustManager instanceof X509TrustManager) {
				return (X509TrustManager) trustManager;
			}
		}

		throw new IllegalStateException("No X509TrustManager in TrustManagerFactory");
	}


	public static void main(String[] args) throws Exception {
		final StaticTrustManagerFactory trustManagerFactory = new StaticTrustManagerFactory(new CertificateCollectingTrustManager(getTrustManager()));
		HttpClient.create(options ->
				options.sslSupport(ssl -> ssl.trustManager(trustManagerFactory)))
				.get("https://api.cf.teal.springapps.io/v2/info",
						c -> c.followRedirect()
								.sendHeaders())
				.log()
				.then(r -> Mono.just(r.status().code()))
				.otherwise(HttpClientException.class,
						e -> Mono.just(e.getResponseStatus()
								.code()))
				.block();

	}

}
