package mozaki.redmineUpster.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConsoleEncodingSetupTests {

	@Test
	@DisplayName("Jenkins から実行されたら UTF-8")
	void jenkinsUsesUtf8() {
		assertThat(ConsoleEncodingSetup.resolve(Map.of("JENKINS_URL", "http://jenkins:8080/")))
				.isEqualTo(StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("環境変数の指定が Jenkins より優先")
	void envOverridesJenkins() {
		assertThat(ConsoleEncodingSetup.resolve(Map.of(
				"JENKINS_URL", "http://jenkins:8080/",
				ConsoleEncodingSetup.ENCODING_ENV, "MS932")).name())
				.isEqualTo("windows-31j");
	}

	@Test
	@DisplayName("不明な文字コードは無視して Jenkins 判定へ")
	void unknownEncodingIsIgnored() {
		assertThat(ConsoleEncodingSetup.resolve(Map.of(
				"JENKINS_URL", "http://jenkins:8080/",
				ConsoleEncodingSetup.ENCODING_ENV, "no-such-charset")))
				.isEqualTo(StandardCharsets.UTF_8);
		assertThat(ConsoleEncodingSetup.resolve(Map.of(ConsoleEncodingSetup.ENCODING_ENV, "no-such-charset"))).isNull();
	}

	@Test
	@DisplayName("通常の実行では変更しない")
	void defaultKeepsConsole() {
		assertThat(ConsoleEncodingSetup.resolve(Map.of())).isNull();
	}
}
