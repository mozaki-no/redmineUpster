package mozaki.redmineUpster.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class PayloadHashUtils {

	private PayloadHashUtils() {
	}

	public static String hashPayload(Map<String, Object> payload) {
		String canonical = canonicalize(payload);
		return sha256(canonical);
	}

	private static String canonicalize(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof Map<?, ?> map) {
			List<String> keys = new ArrayList<>();
			for (Object key : map.keySet()) {
				keys.add(String.valueOf(key));
			}
			keys.sort(Comparator.naturalOrder());
			StringBuilder sb = new StringBuilder();
			sb.append("{");
			boolean first = true;
			for (String key : keys) {
				if (!first) {
					sb.append(",");
				}
				first = false;
				sb.append(escape(key)).append(":").append(canonicalize(map.get(key)));
			}
			sb.append("}");
			return sb.toString();
		}
		if (value instanceof List<?> list) {
			StringBuilder sb = new StringBuilder();
			sb.append("[");
			boolean first = true;
			for (Object item : list) {
				if (!first) {
					sb.append(",");
				}
				first = false;
				sb.append(canonicalize(item));
			}
			sb.append("]");
			return sb.toString();
		}
		if (value instanceof Number || value instanceof Boolean) {
			return String.valueOf(value);
		}
		return "\"" + escape(String.valueOf(value)) + "\"";
	}

	private static String escape(String value) {
		String escaped = value.replace("\\", "\\\\");
		escaped = escaped.replace("\"", "\\\"");
		return escaped;
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : hash) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
