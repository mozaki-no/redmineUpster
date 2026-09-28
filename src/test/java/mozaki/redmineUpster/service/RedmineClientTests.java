package mozaki.redmineUpster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class RedmineClientTests {

	private static String page(int from, int count, int total) {
		StringBuilder sb = new StringBuilder("{\"issues\":[");
		for (int i = 0; i < count; i++) {
			if (i > 0) {
				sb.append(',');
			}
			int id = from + i;
			sb.append("{\"id\":").append(id).append(",\"subject\":\"S").append(id)
					.append("\",\"status\":{\"id\":1}}");
		}
		sb.append("],\"total_count\":").append(total).append(",\"offset\":0,\"limit\":100}");
		return sb.toString();
	}

	@Test
	@DisplayName("プロジェクトのチケットをtotal_countまでページングして全件取得する（クローズ済みを含み、サブプロジェクトは除く）")
	void listProjectIssues_paginates() {
		RestTemplate restTemplate = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
		String base = "http://redmine.local/issues.json?project_id=proj&subproject_id=!*&status_id=*&limit=100";
		server.expect(requestTo(base + "&offset=0")).andExpect(method(HttpMethod.GET))
				.andExpect(header("X-Redmine-API-Key", "key"))
				.andRespond(withSuccess(page(1, 100, 205), MediaType.APPLICATION_JSON));
		server.expect(requestTo(base + "&offset=100"))
				.andRespond(withSuccess(page(101, 100, 205), MediaType.APPLICATION_JSON));
		server.expect(requestTo(base + "&offset=200"))
				.andRespond(withSuccess(page(201, 5, 205), MediaType.APPLICATION_JSON));

		RedmineClient client = new RedmineClient("http://redmine.local/", "key", "proj", restTemplate);
		Map<Long, Map<String, Object>> issues = client.listProjectIssues();

		server.verify();
		assertThat(issues).hasSize(205);
		assertThat(issues.keySet()).startsWith(1L, 2L).endsWith(205L);
		assertThat(issues.get(150L)).containsEntry("subject", "S150");
	}

	@Test
	@DisplayName("チケットが無いプロジェクトは空で返す")
	void listProjectIssues_empty() {
		RestTemplate restTemplate = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
		server.expect(requestTo(
				"http://redmine.local/issues.json?project_id=proj&subproject_id=!*&status_id=*&limit=100&offset=0"))
				.andRespond(withSuccess("{\"issues\":[],\"total_count\":0}", MediaType.APPLICATION_JSON));

		RedmineClient client = new RedmineClient("http://redmine.local", null, "proj", restTemplate);
		assertThat(client.listProjectIssues()).isEmpty();
		server.verify();
	}
}
