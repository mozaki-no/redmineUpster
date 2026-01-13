package mozaki.redmineUpster.dto;

import java.util.List;

public class ConfigUpdateRequest {
	private List<ConfigItem> items;

	public ConfigUpdateRequest() {
	}

	public ConfigUpdateRequest(List<ConfigItem> items) {
		this.items = items;
	}

	public List<ConfigItem> getItems() {
		return items;
	}

	public void setItems(List<ConfigItem> items) {
		this.items = items;
	}
}
