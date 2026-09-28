package mozaki.redmineUpster.cli;

import java.util.HashMap;
import java.util.Map;

import mozaki.redmineUpster.service.RedmineClient;
import mozaki.redmineUpster.util.StringUtils;

/**
 * トラッカー名をトラッカーIDに変換するクラス（1回の同期実行ごとに生成）。
 * <p>
 * 解決順:
 * <ol>
 *   <li>数値ならそのままIDとして扱う</li>
 *   <li>設定 {@code sync.trackerMap}（名前 → ID）</li>
 *   <li>Redmine の {@code GET /trackers.json}（初回のみ取得してキャッシュ）</li>
 * </ol>
 * 見つからない場合は null を返します。
 * </p>
 */
public class TrackerResolver {

    private final Map<String, String> trackerMap;
    private final RedmineClient client;
    private final Map<String, Long> cache = new HashMap<>();
    private Map<String, Long> redmineTrackers;

    /**
     * @param trackerMap 設定のトラッカー名 → ID（null可）
     * @param client Redmineクライアント（null の場合はRedmineへ問い合わせない）
     */
    public TrackerResolver(Map<String, String> trackerMap, RedmineClient client) {
        this.trackerMap = trackerMap == null ? Map.of() : trackerMap;
        this.client = client;
    }

    /**
     * トラッカー名（またはID）をIDに変換します。
     *
     * @param nameOrId トラッカー名またはID
     * @return トラッカーID（不明な場合はnull）
     */
    public Long resolve(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }
        String key = nameOrId.trim();
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Long resolved = doResolve(key);
        cache.put(key, resolved);
        return resolved;
    }

    private Long doResolve(String key) {
        if (StringUtils.isNumeric(key)) {
            return Long.parseLong(key);
        }
        String mapped = trackerMap.get(key);
        if (mapped != null && StringUtils.isNumeric(mapped.trim())) {
            return Long.parseLong(mapped.trim());
        }
        if (client == null) {
            return null;
        }
        if (redmineTrackers == null) {
            redmineTrackers = client.listTrackers();
        }
        return redmineTrackers.get(key);
    }
}
