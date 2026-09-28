package mozaki.redmineUpster.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import mozaki.redmineUpster.cli.FileLogger;

/**
 * Redmine APIクライアント。
 * <p>
 * RedmineサーバーとのHTTP通信を行い、チケットの取得・作成・更新を実行します。
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
	private Long projectNumericId;

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
	 * チケットを取得します。
	 *
	 * @param issueId チケットID
	 * @return チケット情報（レスポンスの "issue" 部分）。存在しない場合（404）はnull
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public Map<String, Object> getIssue(Long issueId) {
		String url = baseUrl + "/issues/" + issueId + ".json";
		debugLog("Request URL: GET " + url);
		try {
			ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, buildEntity(null), Map.class);
			Map body = response.getBody();
			if (body == null || !(body.get(RESPONSE_ISSUE_KEY) instanceof Map)) {
				return null;
			}
			return (Map<String, Object>) body.get(RESPONSE_ISSUE_KEY);
		} catch (HttpClientErrorException.NotFound ex) {
			debugLog("Response status: 404 NOT_FOUND");
			return null;
		}
	}

	/**
	 * トラッカー一覧を取得します（GET /trackers.json）。
	 *
	 * @return トラッカー名 → トラッカーID
	 */
	@SuppressWarnings("rawtypes")
	public Map<String, Long> listTrackers() {
		String url = baseUrl + "/trackers.json";
		debugLog("Request URL: GET " + url);
		ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, buildEntity(null), Map.class);
		Map<String, Long> trackers = new LinkedHashMap<>();
		Map body = response.getBody();
		if (body == null || !(body.get("trackers") instanceof List<?> list)) {
			return trackers;
		}
		for (Object element : list) {
			if (element instanceof Map tracker && tracker.get("id") instanceof Number id
					&& tracker.get("name") != null) {
				trackers.put(String.valueOf(tracker.get("name")), id.longValue());
			}
		}
		debugLog("Trackers: " + trackers);
		return trackers;
	}

	/**
	 * 同期先プロジェクトの数値IDを取得します。
	 * <p>
	 * 設定のprojectIdが数値ならそのまま、識別子（文字列）なら
	 * GET /projects/{identifier}.json で解決します（結果はキャッシュ）。
	 * </p>
	 *
	 * @return プロジェクトの数値ID（解決できない場合はnull）
	 */
	@SuppressWarnings("rawtypes")
	public Long getProjectNumericId() {
		if (projectNumericId != null) {
			return projectNumericId;
		}
		if (projectId == null || projectId.isBlank()) {
			return null;
		}
		if (projectId.chars().allMatch(Character::isDigit)) {
			projectNumericId = Long.parseLong(projectId);
			return projectNumericId;
		}
		String url = baseUrl + "/projects/" + projectId + ".json";
		debugLog("Request URL: GET " + url);
		ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, buildEntity(null), Map.class);
		Map body = response.getBody();
		if (body != null && body.get("project") instanceof Map project && project.get("id") instanceof Number id) {
			projectNumericId = id.longValue();
		}
		return projectNumericId;
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
