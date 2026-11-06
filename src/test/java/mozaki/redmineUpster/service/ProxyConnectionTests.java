package mozaki.redmineUpster.service;

import static org.assertj.core.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import mozaki.redmineUpster.config.ProxyConfig;
import mozaki.redmineUpster.config.RedmineProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.RedmineConfig;

/**
 * 小さなローカルのプロキシ（Basic 認証必須、なければ 407）を通して接続できることのテスト。
 */
class ProxyConnectionTests {

	private static final String USER = "proxy-user";
	private static final String PASSWORD = "p@ss:word";

	private final List<TinyServer> servers = new CopyOnWriteArrayList<>();

	@BeforeAll
	static void allowBasic() {
		// 実際のアプリと同じく、JVM で最初の HTTP 接続より前に Basic 認証を許可する（設定読み込み時に行う処理）
		ProxyConfig.allowBasicProxyAuthentication();
	}

	@AfterEach
	void stop() {
		servers.forEach(TinyServer::close);
	}

	private TinyServer start(boolean requireAuth) throws IOException {
		TinyServer server = new TinyServer(requireAuth);
		servers.add(server);
		return server;
	}

	private static ProxyConfig proxy(TinyServer server, String user, String password, String... noProxy) {
		ProxyConfig config = new ProxyConfig();
		config.setHost("127.0.0.1");
		config.setPort(server.port());
		config.setUser(user);
		config.setPassword(password);
		config.setNoProxyHosts(List.of(noProxy));
		return config;
	}

	private static RedmineClient client(String baseUrl, ProxyConfig proxy) {
		RedmineConfig redmine = new RedmineConfig();
		redmine.setBaseUrl(baseUrl);
		redmine.setApiKey("api-key-123");
		redmine.setProjectId("p");
		redmine.setProxy(proxy);
		ProjectConfig project = new ProjectConfig();
		project.setName("p");
		project.setRedmine(redmine);
		return new RedmineClientFactory(new RestTemplateBuilder(), new RedmineProperties()).createClient(project);
	}

	@Test
	@DisplayName("正しい資格情報なら http の Redmine にプロキシ経由で GET・POST できる")
	void httpThroughProxy_withCredentials() throws Exception {
		TinyServer proxy = start(true);
		RedmineClient client = client("http://redmine.test.invalid", proxy(proxy, USER, PASSWORD));

		// POST（本文あり）を最初に送っても 407 → 再送失敗にならない（Proxy-Authorization を最初から付ける）
		Long id = client.createIssue(Map.of("subject", "s"));
		assertThat(id).isEqualTo(42L);
		Map<String, Long> trackers = client.listTrackers();
		assertThat(trackers).containsEntry("タスク", 2L);

		assertThat(proxy.requests).hasSize(2);
		assertThat(proxy.requests.get(0).line).startsWith("POST http://redmine.test.invalid/issues.json");
		assertThat(proxy.requests.get(1).line).startsWith("GET http://redmine.test.invalid/trackers.json");
		assertThat(proxy.requests).allSatisfy(r -> assertThat(r.authorized).isTrue());
		assertThat(client.getConnectionDescription()).contains("127.0.0.1:" + proxy.port()).contains("認証あり")
				.doesNotContain(PASSWORD);
	}

	@Test
	@DisplayName("資格情報なし・誤りなら 407 で失敗する")
	void httpThroughProxy_withoutOrWrongCredentials() throws Exception {
		TinyServer proxy = start(true);
		assertThatThrownBy(() -> client("http://redmine.test.invalid", proxy(proxy, null, null)).listTrackers())
				.hasMessageContaining("407");
		assertThatThrownBy(() -> client("http://redmine.test.invalid", proxy(proxy, USER, "wrong")).listTrackers())
				.hasMessageContaining("407");
		assertThat(proxy.requests).noneSatisfy(r -> assertThat(r.authorized).isTrue());
	}

	@Test
	@DisplayName("https の Redmine は CONNECT（トンネル）で Basic 認証する")
	void httpsTunnel_withCredentials() throws Exception {
		TinyServer proxy = start(true);
		RedmineClient client = client("https://redmine.test.invalid:8443", proxy(proxy, USER, PASSWORD));

		// ローカルのプロキシは CONNECT に 200 を返して切断するため TLS で失敗するが、407 にはならない
		Throwable error = catchThrowable(client::listTrackers);
		assertThat(error).isNotNull();
		assertThat(String.valueOf(error.getMessage())).doesNotContain("407");

		assertThat(proxy.requests).isNotEmpty();
		TinyServer.Request last = proxy.requests.get(proxy.requests.size() - 1);
		assertThat(last.line).startsWith("CONNECT redmine.test.invalid:8443");
		assertThat(last.authorized).isTrue();
	}

	@Test
	@DisplayName("https の CONNECT で資格情報がなければ 407 のエラー")
	void httpsTunnel_withoutCredentials() throws Exception {
		TinyServer proxy = start(true);
		RedmineClient client = client("https://redmine.test.invalid:8443", proxy(proxy, null, null));
		assertThatThrownBy(client::listTrackers).hasStackTraceContaining("407");
	}

	@Test
	@DisplayName("noProxyHosts に一致する接続先はプロキシを通さない")
	void noProxyHosts_goesDirect() throws Exception {
		TinyServer proxy = start(true);
		TinyServer origin = start(false);
		RedmineClient client = client("http://127.0.0.1:" + origin.port(),
				proxy(proxy, USER, PASSWORD, "localhost|127.0.0.*"));

		assertThat(client.listTrackers()).containsEntry("タスク", 2L);
		assertThat(proxy.requests).isEmpty();
		assertThat(origin.requests).hasSize(1);
		assertThat(origin.requests.get(0).line).startsWith("GET /trackers.json");
		// プロキシの資格情報を直接の接続先に送らない
		assertThat(origin.requests.get(0).proxyAuthorization).isNull();
		assertThat(client.getConnectionDescription()).contains("直接接続");
	}

	@Test
	@DisplayName("プロキシ設定なしは従来どおり（設定の host 空欄も同じ）")
	void blankHost_direct() throws Exception {
		TinyServer origin = start(false);
		ProxyConfig blank = new ProxyConfig();
		RedmineClient client = client("http://127.0.0.1:" + origin.port(), blank);
		assertThat(client.listTrackers()).containsEntry("タスク", 2L);
		assertThat(origin.requests).hasSize(1);
		assertThat(client(null, null).getConnectionDescription()).isNotBlank();
	}

	/**
	 * 1接続1リクエストの小さな HTTP サーバー。requireAuth ならプロキシとして Proxy-Authorization を確認する。
	 */
	static final class TinyServer implements AutoCloseable {
		record Request(String line, boolean authorized, String proxyAuthorization) {
		}

		private final ServerSocket socket;
		private final boolean requireAuth;
		final List<Request> requests = new CopyOnWriteArrayList<>();
		private final String expected = "Basic "
				+ Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.ISO_8859_1));

		TinyServer(boolean requireAuth) throws IOException {
			this.requireAuth = requireAuth;
			this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			Thread thread = new Thread(this::loop, "tiny-proxy");
			thread.setDaemon(true);
			thread.start();
		}

		int port() {
			return socket.getLocalPort();
		}

		private void loop() {
			while (!socket.isClosed()) {
				try (Socket s = socket.accept()) {
					s.setSoTimeout(5000);
					handle(s);
				} catch (IOException e) {
					// 閉じた・切断
				}
			}
		}

		private void handle(Socket s) throws IOException {
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
			String line = in.readLine();
			if (line == null) {
				return;
			}
			String proxyAuth = null;
			int length = 0;
			String header;
			while ((header = in.readLine()) != null && !header.isEmpty()) {
				int colon = header.indexOf(':');
				String name = header.substring(0, colon).trim();
				String value = header.substring(colon + 1).trim();
				if (name.equalsIgnoreCase("Proxy-Authorization")) {
					proxyAuth = value;
				} else if (name.equalsIgnoreCase("Content-Length")) {
					length = Integer.parseInt(value);
				}
			}
			for (int i = 0; i < length; i++) {
				in.read();
			}
			boolean authorized = expected.equals(proxyAuth);
			requests.add(new Request(line, authorized, proxyAuth));
			OutputStream out = s.getOutputStream();
			if (requireAuth && !authorized) {
				out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
						+ "Proxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
						.getBytes(StandardCharsets.ISO_8859_1));
				out.flush();
				return;
			}
			if (line.startsWith("CONNECT ")) {
				// トンネルを張ったことにしてすぐ切断する（TLS は失敗する）
				out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
				out.flush();
				return;
			}
			String body = line.startsWith("POST ") ? "{\"issue\":{\"id\":42}}"
					: "{\"trackers\":[{\"id\":2,\"name\":\"タスク\"}]}";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			out.write(("HTTP/1.1 " + (line.startsWith("POST ") ? "201 Created" : "200 OK") + "\r\n"
					+ "Content-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length
					+ "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			out.write(bytes);
			out.flush();
		}

		@Override
		public void close() {
			try {
				socket.close();
			} catch (IOException e) {
				// 無視
			}
		}
	}
}
