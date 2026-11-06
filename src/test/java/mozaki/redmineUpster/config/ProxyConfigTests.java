package mozaki.redmineUpster.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.service.SyncConfigService;

/**
 * プロキシ設定（redmine.proxy）の読み込み・解釈のテスト。
 */
class ProxyConfigTests {

	@TempDir
	Path dir;

	private ProjectConfig load(String proxyYaml) throws Exception {
		String yaml = """
				projects:
				  - name: "p"
				    default: true
				    redmine:
				      baseUrl: "https://redmine.example.co.jp"
				      apiKey: "k"
				      projectId: "x"
				""" + proxyYaml.indent(6);
		Path path = dir.resolve("sync-config.yml");
		Files.writeString(path, yaml, StandardCharsets.UTF_8);
		SyncConfigService service = new SyncConfigService(new SyncConfigProperties());
		service.loadConfig(path.toString());
		return service.getAllProjects().get(0);
	}

	@Test
	@DisplayName("proxy がなければ null（直接接続）")
	void noProxySection_isNull() throws Exception {
		assertThat(load("").getRedmine().getProxy()).isNull();
	}

	@Test
	@DisplayName("host が空欄なら無効（直接接続）")
	void blankHost_isDisabled() throws Exception {
		ProxyConfig proxy = load("""
				proxy:
				  host: ""
				  noProxyHosts: "localhost"
				""").getRedmine().getProxy();
		assertThat(proxy.isEnabled()).isFalse();
		assertThat(proxy.hasCredentials()).isFalse();
	}

	@Test
	@DisplayName("全項目をリスト形式の noProxyHosts で読み込む")
	void fullConfig_listForm() throws Exception {
		ProxyConfig proxy = load("""
				proxy:
				  host: "proxy.example.co.jp"
				  port: 3128
				  user: "taro"
				  password: " p@ss "
				  noProxyHosts:
				    - "localhost"
				    - "*.internal.example.co.jp"
				""").getRedmine().getProxy();
		assertThat(proxy.isEnabled()).isTrue();
		assertThat(proxy.getHost()).isEqualTo("proxy.example.co.jp");
		assertThat(proxy.effectivePort()).isEqualTo(3128);
		assertThat(proxy.getUser()).isEqualTo("taro");
		assertThat(proxy.getPassword()).isEqualTo(" p@ss ");
		assertThat(proxy.hasCredentials()).isTrue();
		assertThat(proxy.getNoProxyHosts()).containsExactly("localhost", "*.internal.example.co.jp");
	}

	@Test
	@DisplayName("noProxyHosts は | ・カンマ区切りの文字列でもよく、port 省略時は 8080")
	void stringForm_andDefaultPort() throws Exception {
		ProxyConfig proxy = load("""
				proxy:
				  host: "proxy"
				  noProxyHosts: "localhost|127.0.0.1, *.example.co.jp"
				""").getRedmine().getProxy();
		assertThat(proxy.effectivePort()).isEqualTo(8080);
		assertThat(proxy.getNoProxyHosts()).containsExactly("localhost", "127.0.0.1", "*.example.co.jp");
		assertThat(proxy.hasCredentials()).isFalse();
	}

	@Test
	@DisplayName("${ENV:既定値} を展開する（port も文字列の ${...} で書ける）")
	void envPlaceholders() throws Exception {
		ProxyConfig proxy = load("""
				proxy:
				  host: "${UNSET_PROXY_HOST_FOR_TEST:proxy.example.co.jp}"
				  port: "${UNSET_PROXY_PORT_FOR_TEST:8081}"
				  user: "${UNSET_PROXY_USER_FOR_TEST:hanako}"
				  password: "${UNSET_PROXY_PASSWORD_FOR_TEST:secret}"
				  noProxyHosts: "${UNSET_NO_PROXY_FOR_TEST:a.example|b.example}"
				""").getRedmine().getProxy();
		assertThat(proxy.getHost()).isEqualTo("proxy.example.co.jp");
		assertThat(proxy.getPort()).isEqualTo(8081);
		assertThat(proxy.getUser()).isEqualTo("hanako");
		assertThat(proxy.getPassword()).isEqualTo("secret");
		assertThat(proxy.getNoProxyHosts()).containsExactly("a.example", "b.example");
	}

	@Test
	@DisplayName("環境変数が未設定で既定値もなければ空欄 = 直接接続")
	void unsetEnv_isDirect() throws Exception {
		ProxyConfig proxy = load("""
				proxy:
				  host: "${UNSET_PROXY_HOST_FOR_TEST}"
				""").getRedmine().getProxy();
		assertThat(proxy.isEnabled()).isFalse();
	}

	@Test
	@DisplayName("不正なポート・host なしの port/user はエラー")
	void invalidValues() {
		assertThatThrownBy(() -> load("proxy: { host: \"p\", port: \"abc\" }\n"))
				.hasStackTraceContaining("redmine.proxy.port must be numeric");
		assertThatThrownBy(() -> load("proxy: { host: \"p\", port: 70000 }\n"))
				.hasStackTraceContaining("1-65535");
		assertThatThrownBy(() -> load("proxy: { port: 8080, user: \"u\" }\n"))
				.hasStackTraceContaining("redmine.proxy.host is required");
	}

	@Test
	@DisplayName("noProxyHosts のワイルドカード・完全一致・先頭ドット")
	void bypasses() {
		ProxyConfig proxy = new ProxyConfig();
		proxy.setHost("proxy");
		proxy.setNoProxyHosts(List.of("localhost|*.internal.example.co.jp", ".corp.example", "10.*"));
		assertThat(proxy.bypasses("localhost")).isTrue();
		assertThat(proxy.bypasses("LOCALHOST")).isTrue();
		assertThat(proxy.bypasses("redmine.internal.example.co.jp")).isTrue();
		assertThat(proxy.bypasses("internal.example.co.jp")).isFalse();
		assertThat(proxy.bypasses("corp.example")).isTrue();
		assertThat(proxy.bypasses("a.corp.example")).isTrue();
		assertThat(proxy.bypasses("10.1.2.3")).isTrue();
		assertThat(proxy.bypasses("redmine.example.co.jp")).isFalse();
		assertThat(proxy.bypasses("xlocalhost")).isFalse();
	}

	@Test
	@DisplayName("説明・toString にパスワードを出さない")
	void describe_hidesPassword() {
		ProxyConfig proxy = new ProxyConfig();
		proxy.setHost("proxy.example.co.jp");
		proxy.setPort(3128);
		proxy.setUser("taro");
		proxy.setPassword("TopSecret123");
		assertThat(proxy.describe()).contains("proxy.example.co.jp:3128").contains("認証あり")
				.doesNotContain("TopSecret123");
		assertThat(proxy.toString()).doesNotContain("TopSecret123");
		ProxyConfig none = new ProxyConfig();
		assertThat(none.describe()).contains("直接接続");
	}
}
