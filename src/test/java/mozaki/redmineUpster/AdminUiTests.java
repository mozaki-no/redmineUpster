package mozaki.redmineUpster;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUiTests {
	@Autowired
	private MockMvc mockMvc;

	@Test
	void servesAdminPage() throws Exception {
		mockMvc.perform(get("/admin.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Admin Console")));
	}

	@Test
	void exposesRedmineInfo() throws Exception {
		mockMvc.perform(get("/api/admin/redmine"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.baseUrl").value("http://localhost:3000"))
				.andExpect(jsonPath("$.projectId").value(""))
				.andExpect(jsonPath("$.apiKeyConfigured").value(false));
	}

	@Test
	void createsDiffFromCsv() throws Exception {
		ClassPathResource resource = new ClassPathResource("wbs_sample.csv");
		try (InputStream input = resource.getInputStream()) {
			MockMultipartFile file = new MockMultipartFile("file", "wbs_sample.csv", "text/csv", input);
			mockMvc.perform(multipart("/api/diffs").file(file))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").isNumber())
					.andExpect(jsonPath("$.itemCount").value(2));
		}
	}
}
