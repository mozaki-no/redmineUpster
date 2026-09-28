package mozaki.redmineUpster.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import mozaki.redmineUpster.domain.IssueLinkEntity;

@DataJpaTest
@ActiveProfiles("test")
class IssueLinkRepositoryTests {

	@Autowired
	private IssueLinkRepository repository;

	@Test
	@DisplayName("issue_linkは(issue_id, project_id)で検索でき、external_keyなしで保存できる")
	void findByIssueIdAndProjectId() {
		IssueLinkEntity link = new IssueLinkEntity(10L, "proj");
		link.setPayloadHash("hash");
		repository.saveAndFlush(link);
		repository.saveAndFlush(new IssueLinkEntity(10L, "other"));
		repository.saveAndFlush(new IssueLinkEntity(11L, "proj"));

		assertThat(repository.findByIssueIdAndProjectId(10L, "proj")).get()
				.extracting(IssueLinkEntity::getPayloadHash).isEqualTo("hash");
		assertThat(repository.findAllByProjectId("proj")).extracting(IssueLinkEntity::getIssueId)
				.containsExactlyInAnyOrder(10L, 11L);
	}

	@Test
	@DisplayName("同じ(issue_id, project_id)は重複登録できない")
	void uniqueIssueIdPerProject() {
		repository.saveAndFlush(new IssueLinkEntity(20L, "proj"));

		assertThatThrownBy(() -> repository.saveAndFlush(new IssueLinkEntity(20L, "proj")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
