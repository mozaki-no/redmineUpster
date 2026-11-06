package mozaki.redmineUpster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import mozaki.redmineUpster.config.ProxyConfig;
import mozaki.redmineUpster.config.RedmineProperties;
import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.TrustStoreSetup;
import mozaki.redmineUpster.settings.SettingsLauncher;
import mozaki.redmineUpster.util.ConsoleEncoding;

@SpringBootApplication
@EnableConfigurationProperties({RedmineProperties.class, SyncConfigProperties.class})
public class RedmineUpsterApplication {

	public static void main(String[] args) {
		// Jenkins などでコンソールが文字化けしないよう、最初に出力の文字コードを決める
		ConsoleEncoding.configure(args);
		// プロキシの Basic 認証（HTTPS の CONNECT を含む）を許可する。JDK は JVM で最初に HTTP 接続を開くときに
		// 1回だけ読むため、どの接続よりも前（起動直後）に設定する（-D で指定済みならその指定に従う）
		ProxyConfig.allowBasicProxyAuthentication();
		// 設定画面（--settings）は Spring を起動せずにすぐ開く
		if (SettingsLauncher.isRequested(args)) {
			int exitCode = SettingsLauncher.launch(args);
			if (exitCode != 0) {
				System.exit(exitCode);
			}
			return;
		}
		// 社内CAの Redmine に接続できるよう、Windows では Windows の証明書ストアも使う
		TrustStoreSetup.configure();
		SpringApplication.run(RedmineUpsterApplication.class, args);
	}

}
