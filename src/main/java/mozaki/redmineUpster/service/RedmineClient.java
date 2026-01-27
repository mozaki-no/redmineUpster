package mozaki.redmineUpster.service;

import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import mozaki.redmineUpster.cli.FileLogger;

/**
 * Redmine APIクライアント。
 * <p>
 * RedmineサーバーとのHTTP通信を行い、チケットの作成・更新を実行します。
 * </p>
 */
public class RedmineClient {

	private static final String ISSUES_ENDPOINT = "/issues.json";
	private static final String API_KEY_HEADER = "X-Redmine-API-Key";
	private static final String CONNECTION_HEADER = "Connection";
	private static final String CONNECTION_CLOSE = "close";
	private static final String RESPONSE_ISSUE_KEY = "issue";
	private static final String RESPONSE_ID_KEY = "id";

	private final String baseUrl;
	private final String apiKey;
	private final String projectId;
	private final RestTemplate restTemplate;
	private FileLogger logger;

	/**
	 * RedmineClientを構築します。
	 *
	 * @param baseUrl RedmineのベースURL
	 * @param apiKey APIキー
	 * @param projectId プロジェクトID
	 * @param restTemplate RestTemplate
	 */
	public RedmineClient(String baseUrl, String apiKey, String projectId, RestTemplate restTemplate) {
		this.baseUrl = normalizeBaseUrl(baseUrl);
		this.apiKey = apiKey;
		this.projectId = projectId;
		this.restTemplate = restTemplate;
	}

	/**
	 * チケットを作成します。
	 *
	 * @param issue チケット情報
	 * @return 作成されたチケットのID（失敗時はnull）
	 */
	@SuppressWarnings("rawtypes")
	public Long createIssue(Map<String, Object> issue) {
		String url = baseUrl + ISSUES_ENDPOINT;
		Map<String, Object> requestBody = Map.of(RESPONSE_ISSUE_KEY, issue);
		HttpEntity<Map<String, Object>> entity = buildEntity(requestBody);

		debugLog("Request URL: " + url);
		debugLog("Request body: " + formatBodyForLog(requestBody));

		ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

		debugLog("Response status: " + response.getStatusCode());
		debugLog("Response body: " + response.getBody());

		Map body = response.getBody();
		if (body == null || !body.containsKey(RESPONSE_ISSUE_KEY)) {
			return null;
		}
		Map issueData = (Map) body.get(RESPONSE_ISSUE_KEY);
		Object id = issueData.get(RESPONSE_ID_KEY);
		if (id instanceof Number) {
			return ((Number) id).longValue();
		}
		return null;
	}

	/**
	 * チケットを更新します。
	 *
	 * @param issueId チケットID
	 * @param issue 更新情報
	 */
	public void updateIssue(Long issueId, Map<String, Object> issue) {
		String url = baseUrl + "/issues/" + issueId + ".json";
		Map<String, Object> requestBody = Map.of(RESPONSE_ISSUE_KEY, issue);
		HttpEntity<Map<String, Object>> entity = buildEntity(requestBody);

		debugLog("Request URL: " + url);
		debugLog("Request body: " + formatBodyForLog(requestBody));

		restTemplate.put(url, entity);

		debugLog("Response status: 200 OK (PUT success)");
	}

	/**
	 * チケットを削除します。
	 *
	 * @param issueId チケットID
	 */
	public void deleteIssue(Long issueId) {
		String url = baseUrl + "/issues/" + issueId + ".json";

		debugLog("Request URL: " + url);

		HttpEntity<Map<String, Object>> entity = buildEntity(Map.of());
		restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);

		debugLog("Response status: 204 NO_CONTENT (DELETE success)");
	}

	/**
	 * プロジェクトIDを取得します。
	 *
	 * @return プロジェクトID
	 */
	public String getProjectId() {
		return projectId;
	}

	/**
	 * ベースURLを取得します。
	 *
	 * @return ベースURL
	 */
	public String getBaseUrl() {
		return baseUrl;
	}

	/**
	 * ロガーを設定します。
	 *
	 * @param logger ファイルロガー
	 */
	public void setLogger(FileLogger logger) {
		this.logger = logger;
	}

	/**
	 * HTTPエンティティを構築します。
	 *
	 * @param body リクエストボディ
	 * @return HTTPエンティティ
	 */
	private HttpEntity<Map<String, Object>> buildEntity(Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set(CONNECTION_HEADER, CONNECTION_CLOSE);
		if (apiKey != null && !apiKey.isBlank()) {
			headers.set(API_KEY_HEADER, apiKey);
		}
		return new HttpEntity<>(body, headers);
	}

	/**
	 * ベースURLを正規化します（末尾のスラッシュを削除）。
	 *
	 * @param base ベースURL
	 * @return 正規化されたURL
	 */
	private static String normalizeBaseUrl(String base) {
		if (base != null && base.endsWith("/")) {
			return base.substring(0, base.length() - 1);
		}
		return base;
	}

	/**
	 * デバッグログを出力します。
	 *
	 * @param message ログメッセージ
	 */
	private void debugLog(String message) {
		if (logger != null) {
			logger.debug(message);
		}
	}

	/**
	 * リクエストボディをログ出力用にフォーマットします。
	 *
	 * @param body リクエストボディ
	 * @return フォーマットされた文字列
	 */
	private String formatBodyForLog(Map<String, Object> body) {
		if (body == null) {
			return "null";
		}
		return body.toString();
	}
}
