package mozaki.redmineUpster.dto;

import java.util.List;

public class ProbeHeadersResponse {
	private List<String> headers;
	private List<String> missingRequired;

	public ProbeHeadersResponse(List<String> headers, List<String> missingRequired) {
		this.headers = headers;
		this.missingRequired = missingRequired;
	}

	public List<String> getHeaders() {
		return headers;
	}

	public List<String> getMissingRequired() {
		return missingRequired;
	}
}
