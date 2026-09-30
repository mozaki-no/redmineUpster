package mozaki.redmineUpster.service;

import java.net.URI;
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
import org.springframework.web.util.UriComponentsBuilder;

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
	/** チケット一覧取得の1ページ件数（Redmineの上限は既定で100） */
	static final int PAGE_SIZE = 100;
	/** ユーザーの状態（1=有効, 2=登録, 3=ロック） */
	private static final int[] USER_STATUSES = { 1, 2, 3 };

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
	 * 同期先プロジェクトのチケットを全件取得します（クローズ済みを含む、サブプロジェクトは含まない）。
	 * <p>
	 * {@code GET /issues.json?project_id=...&subproject_id=!*&status_id=*&limit=100&offset=...}
	 * を total_count に達するまでページングして取得します。
	 * </p>
	 *
	 * @return チケットID → チケット情報（取得順）
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public Map<Long, Map<String, Object>> listProjectIssues() {
		if (projectId == null || projectId.isBlank()) {
			throw new IllegalStateException("redmine.project-id is not configured");
		}
		Map<Long, Map<String, Object>> issues = new LinkedHashMap<>();
		int offset = 0;
		while (true) {
			URI uri = UriComponentsBuilder.fromUriString(baseUrl + ISSUES_ENDPOINT)
					.queryParam("project_id", projectId)
					.queryParam("subproject_id", "!*")
					.queryParam("status_id", "*")
					.queryParam("limit", PAGE_SIZE)
					.queryParam("offset", offset)
					.encode()
					.build()
					.toUri();
			debugLog("Request URL: GET " + uri);
			ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildEntity(null), Map.class);
			Map body = response.getBody();
			List<?> page = body != null && body.get("issues") instanceof List<?> list ? list : List.of();
			for (Object element : page) {
				if (element instanceof Map issue && issue.get(RESPONSE_ID_KEY) instanceof Number id) {
					issues.put(id.longValue(), (Map<String, Object>) issue);
				}
			}
			int total = body != null && body.get("total_count") instanceof Number n ? n.intValue() : -1;
			offset += page.size();
			if (page.isEmpty() || (total >= 0 && offset >= total) || (total < 0 && page.size() < PAGE_SIZE)) {
				break;
			}
		}
		debugLog("Project issues fetched: " + issues.size());
		return issues;
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
	 * ユーザーを全件取得します（有効・登録・ロックのすべて。管理者の API キーが必要）。
	 * <p>
	 * {@code GET /users.json?status=N&limit=100&offset=...} を状態（1=有効, 2=登録, 3=ロック）ごとにページングして取得します。
	 * </p>
	 *
	 * @return ユーザーID → ユーザー情報（取得順）
	 */
	public Map<Long, Map<String, Object>> listUsers() {
		// 一覧の応答に status が含まれない Redmine があるため、状態ごとに取得して status を補う
		Map<Long, Map<String, Object>> users = new LinkedHashMap<>();
		for (int status : USER_STATUSES) {
			for (Map.Entry<Long, Map<String, Object>> entry : listPaged("/users.json", "users",
					Map.of("status", String.valueOf(status))).entrySet()) {
				entry.getValue().putIfAbsent("status", status);
				users.put(entry.getKey(), entry.getValue());
			}
		}
		debugLog("Users fetched: " + users.size());
		return users;
	}

	/**
	 * グループを全件取得します（メンバーを含む。管理者の API キーが必要）。
	 * <p>
	 * {@code GET /groups.json} で一覧を取得し、各グループの {@code GET /groups/{id}.json?include=users}
	 * で "users"（メンバー）を補います。
	 * </p>
	 *
	 * @return グループID → グループ情報（"users" にメンバーの一覧）
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public Map<Long, Map<String, Object>> listGroups() {
		Map<Long, Map<String, Object>> groups = listPaged("/groups.json", "groups", Map.of());
		for (Map.Entry<Long, Map<String, Object>> entry : groups.entrySet()) {
			String url = baseUrl + "/groups/" + entry.getKey() + ".json?include=users";
			debugLog("Request URL: GET " + url);
			ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, buildEntity(null), Map.class);
			Map body = response.getBody();
			if (body != null && body.get("group") instanceof Map group) {
				Map<String, Object> merged = new LinkedHashMap<>(entry.getValue());
				merged.putAll((Map<String, Object>) group);
				entry.setValue(merged);
			}
		}
		debugLog("Groups fetched: " + groups.size());
		return groups;
	}

	/**
	 * チケットのステータス一覧を取得します（GET /issue_statuses.json）。
	 *
	 * @return ステータス名 → ステータスID
	 */
	@SuppressWarnings("rawtypes")
	public Map<String, Long> listIssueStatuses() {
		String url = baseUrl + "/issue_statuses.json";
		debugLog("Request URL: GET " + url);
		ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, buildEntity(null), Map.class);
		Map<String, Long> statuses = new LinkedHashMap<>();
		Map body = response.getBody();
		if (body == null || !(body.get("issue_statuses") instanceof List<?> list)) {
			return statuses;
		}
		for (Object element : list) {
			if (element instanceof Map status && status.get("id") instanceof Number id && status.get("name") != null) {
				statuses.put(String.valueOf(status.get("name")), id.longValue());
			}
		}
		debugLog("Issue statuses: " + statuses);
		return statuses;
	}

	/**
	 * ユーザーを作成します（POST /users.json）。
	 *
	 * @param user ユーザー情報
	 * @return 作成されたユーザーのID（取得できない場合はnull）
	 */
	public Long createUser(Map<String, Object> user) {
		return post("/users.json", "user", user);
	}

	/**
	 * ユーザーを更新します（PUT /users/{id}.json）。
	 *
	 * @param userId ユーザーID
	 * @param user 更新する項目
	 */
	public void updateUser(Long userId, Map<String, Object> user) {
		put("/users/" + userId + ".json", "user", user);
	}

	/**
	 * グループを作成します（POST /groups.json）。
	 *
	 * @param group グループ情報（name, user_ids）
	 * @return 作成されたグループのID（取得できない場合はnull）
	 */
	public Long createGroup(Map<String, Object> group) {
		return post("/groups.json", "group", group);
	}

	/**
	 * グループを更新します（PUT /groups/{id}.json）。
	 *
	 * @param groupId グループID
	 * @param group 更新する項目（name, user_ids）
	 */
	public void updateGroup(Long groupId, Map<String, Object> group) {
		put("/groups/" + groupId + ".json", "group", group);
	}

	@SuppressWarnings("rawtypes")
	private Long post(String path, String rootKey, Map<String, Object> content) {
		String url = baseUrl + path;
		Map<String, Object> requestBody = Map.of(rootKey, content);
		debugLog("Request URL: POST " + url);
		debugLog("Request body: " + formatBodyForLog(maskPassword(requestBody)));
		ResponseEntity<Map> response = restTemplate.postForEntity(url, buildEntity(requestBody), Map.class);
		debugLog("Response status: " + response.getStatusCode());
		Map body = response.getBody();
		if (body != null && body.get(rootKey) instanceof Map created && created.get(RESPONSE_ID_KEY) instanceof Number id) {
			return id.longValue();
		}
		return null;
	}

	private void put(String path, String rootKey, Map<String, Object> content) {
		String url = baseUrl + path;
		Map<String, Object> requestBody = Map.of(rootKey, content);
		debugLog("Request URL: PUT " + url);
		debugLog("Request body: " + formatBodyForLog(maskPassword(requestBody)));
		restTemplate.put(url, buildEntity(requestBody));
	}

	/**
	 * total_count に達するまでページングして一覧を取得します。
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Map<Long, Map<String, Object>> listPaged(String path, String listKey, Map<String, String> params) {
		Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
		int offset = 0;
		while (true) {
			UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + path);
			params.forEach(builder::queryParam);
			URI uri = builder.queryParam("limit", PAGE_SIZE).queryParam("offset", offset).encode().build().toUri();
			debugLog("Request URL: GET " + uri);
			ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, buildEntity(null), Map.class);
			Map body = response.getBody();
			List<?> page = body != null && body.get(listKey) instanceof List<?> list ? list : List.of();
			int before = result.size();
			for (Object element : page) {
				if (element instanceof Map item && item.get(RESPONSE_ID_KEY) instanceof Number id) {
					result.put(id.longValue(), new LinkedHashMap<>((Map<String, Object>) item));
				}
			}
			int total = body != null && body.get("total_count") instanceof Number n ? n.intValue() : -1;
			offset += page.size();
			// ページングに対応しない一覧（/groups.json）は同じ内容が返るので、新しい要素がなければ終了
			if (page.isEmpty() || result.size() == before || (total >= 0 && offset >= total)
					|| (total < 0 && page.size() < PAGE_SIZE)) {
				break;
			}
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> maskPassword(Map<String, Object> body) {
		Map<String, Object> masked = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : body.entrySet()) {
			if (entry.getValue() instanceof Map<?, ?> inner && inner.containsKey("password")) {
				Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) inner);
				copy.put("password", "********");
				masked.put(entry.getKey(), copy);
			} else {
				masked.put(entry.getKey(), entry.getValue());
			}
		}
		return masked;
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
