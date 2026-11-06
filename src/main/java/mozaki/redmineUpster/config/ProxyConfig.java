package mozaki.redmineUpster.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import lombok.Data;

/**
 * Redmine に接続するときの HTTP(S) プロキシ設定。
 * <p>
 * {@code host} が空欄なら設定なし（従来どおり直接接続。JVM の {@code -Dhttps.proxyHost} などが
 * 指定されていればそれに従う）。{@code user} を設定すると Basic 認証（など、プロキシが求める方式）で
 * 認証します。
 * </p>
 */
@Data
public class ProxyConfig {
	/** HTTPS の CONNECT（トンネル）で Basic 認証を禁止する JDK の設定（JDK の既定 "Basic"） */
	public static final String TUNNELING_DISABLED_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes";
	/** HTTP のプロキシで Basic 認証を禁止する JDK の設定（JDK の既定は空） */
	public static final String PROXYING_DISABLED_SCHEMES = "jdk.http.auth.proxying.disabledSchemes";

	/** 既定のポート */
	public static final int DEFAULT_PORT = 8080;

	/** プロキシのホスト名（空欄 = 設定なし） */
	private String host;

	/** プロキシのポート（省略時 8080） */
	private Integer port;

	/** プロキシ認証のユーザー名（空欄 = 認証なし） */
	private String user;

	/** プロキシ認証のパスワード */
	private String password;

	/**
	 * プロキシを通さないホスト（{@code *.example.co.jp} のようなワイルドカード可）。
	 * 設定ファイルではリスト、または {@code |}・カンマ区切りの文字列で書けます。
	 */
	private List<String> noProxyHosts = new ArrayList<>();

	/**
	 * @return host が設定されていれば true
	 */
	public boolean isEnabled() {
		return host != null && !host.isBlank();
	}

	/**
	 * @return 認証のユーザー名が設定されていれば true
	 */
	public boolean hasCredentials() {
		return isEnabled() && user != null && !user.isBlank();
	}

	/**
	 * プロキシの Basic 認証を許可します（JDK は既定で HTTPS のトンネル（CONNECT）の Basic 認証を禁止している）。
	 * <p>
	 * HttpURLConnection はこの値をクラスの初期化時（JVM で最初に http/https の接続を開くとき）に1回だけ読むため、
	 * 最初の接続（プロキシを使わない接続を含む）より前に呼ぶ必要があります。プロキシ設定の有無にかかわらず、
	 * 起動直後（main）・RedmineClient のクラス初期化時・RedmineClientFactory の作成時に呼びます
	 * （設定ファイルの読み込み時などの呼び出しは念のため）。
	 * 起動時に {@code -D} で指定済みならその指定に従います。
	 * </p>
	 */
	public static void allowBasicProxyAuthentication() {
		if (System.getProperty(TUNNELING_DISABLED_SCHEMES) == null) {
			System.setProperty(TUNNELING_DISABLED_SCHEMES, "");
		}
		if (System.getProperty(PROXYING_DISABLED_SCHEMES) == null) {
			System.setProperty(PROXYING_DISABLED_SCHEMES, "");
		}
	}

	/**
	 * @return 実際に使うポート（省略時 8080）
	 */
	public int effectivePort() {
		return port == null ? DEFAULT_PORT : port;
	}

	/**
	 * Spring のプロパティ（{@code redmine.proxy.no-proxy-hosts=a|b}）でも {@code |} 区切りを使えるよう、
	 * 各要素をさらに分割して設定します。
	 *
	 * @param noProxyHosts ホストのリスト
	 */
	public void setNoProxyHosts(List<String> noProxyHosts) {
		List<String> result = new ArrayList<>();
		if (noProxyHosts != null) {
			for (String item : noProxyHosts) {
				result.addAll(parseHostList(item));
			}
		}
		this.noProxyHosts = result;
	}

	/**
	 * 設定ファイルの値（リストまたは {@code |}・カンマ・セミコロン・空白区切りの文字列）をホストのリストにします。
	 *
	 * @param value 値（null・空欄は空のリスト）
	 * @return ホストのリスト（空欄の要素は除く）
	 */
	public static List<String> parseHostList(Object value) {
		List<String> result = new ArrayList<>();
		if (value == null) {
			return result;
		}
		if (value instanceof Iterable<?> items) {
			for (Object item : items) {
				result.addAll(parseHostList(item));
			}
			return result;
		}
		for (String part : String.valueOf(value).split("[|,;\\s]+")) {
			if (!part.isBlank()) {
				result.add(part.trim());
			}
		}
		return result;
	}

	/**
	 * @param targetHost 接続先のホスト名
	 * @return プロキシを通さない（noProxyHosts に一致する）なら true
	 */
	public boolean bypasses(String targetHost) {
		if (targetHost == null || noProxyHosts == null) {
			return false;
		}
		String host = targetHost.toLowerCase(Locale.ROOT);
		if (host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length() - 1);
		}
		for (String pattern : noProxyHosts) {
			if (matches(pattern.toLowerCase(Locale.ROOT).trim(), host)) {
				return true;
			}
		}
		return false;
	}

	private static boolean matches(String pattern, String host) {
		if (pattern.isEmpty()) {
			return false;
		}
		if ("*".equals(pattern)) {
			return true;
		}
		// ".example.co.jp" は example.co.jp とそのサブドメイン（no_proxy の書き方）
		if (pattern.startsWith(".")) {
			return host.endsWith(pattern) || host.equals(pattern.substring(1));
		}
		if (!pattern.contains("*")) {
			return host.equals(pattern);
		}
		StringBuilder regex = new StringBuilder();
		StringBuilder literal = new StringBuilder();
		for (char ch : pattern.toCharArray()) {
			if (ch == '*') {
				if (literal.length() > 0) {
					regex.append(java.util.regex.Pattern.quote(literal.toString()));
					literal.setLength(0);
				}
				regex.append(".*");
			} else {
				literal.append(ch);
			}
		}
		if (literal.length() > 0) {
			regex.append(java.util.regex.Pattern.quote(literal.toString()));
		}
		return host.matches(regex.toString());
	}

	/**
	 * ログ用の説明（パスワードは出さない）。
	 *
	 * @return 例: "proxy.example.co.jp:8080（認証あり）"
	 */
	public String describe() {
		if (!isEnabled()) {
			return "なし（直接接続）";
		}
		StringBuilder text = new StringBuilder(host.trim()).append(':').append(effectivePort())
				.append(hasCredentials() ? "（認証あり）" : "（認証なし）");
		if (noProxyHosts != null && !noProxyHosts.isEmpty()) {
			text.append("、除外: ").append(String.join("|", noProxyHosts));
		}
		return text.toString();
	}

	/** パスワードを出さない toString */
	@Override
	public String toString() {
		return "ProxyConfig(" + describe() + ")";
	}
}
