package mozaki.redmineUpster.config;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * 標準出力・標準エラーの文字コードを決めます。
 *
 * <p>Windows で出力がパイプ（Jenkins など）に流れると、Java は既定で Windows-31J（MS932）で書きます。
 * Jenkins（Java 18 以降）は UTF-8 として読むため、日本語が文字化けします。そこで次の順に文字コードを決め、
 * {@link System#out} / {@link System#err} と Spring Boot のコンソールログ（{@code logging.charset.console}）に設定します。
 * <ol>
 *   <li>環境変数 {@code REDMINEUPSTER_CONSOLE_ENCODING}（例: {@code UTF-8}、{@code MS932}）</li>
 *   <li>Jenkins から実行された場合（環境変数 {@code JENKINS_URL} あり）は UTF-8</li>
 *   <li>それ以外は変更しない（コマンドプロンプトでの表示は従来どおり）</li>
 * </ol>
 * ログファイル（logs フォルダ）は従来どおり常に UTF-8 です。
 */
public final class ConsoleEncodingSetup {

    public static final String ENCODING_ENV = "REDMINEUPSTER_CONSOLE_ENCODING";
    static final String SPRING_CONSOLE_CHARSET = "logging.charset.console";

    private ConsoleEncodingSetup() {
    }

    /**
     * 環境変数から文字コードを決め、標準出力・標準エラーに設定します。
     */
    public static void configure() {
        Charset charset = resolve(System.getenv());
        if (charset == null) {
            return;
        }
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, charset));
        if (System.getProperty(SPRING_CONSOLE_CHARSET) == null) {
            System.setProperty(SPRING_CONSOLE_CHARSET, charset.name());
        }
    }

    /**
     * 使う文字コードを返します。変更しない場合は null。
     *
     * @param env 環境変数
     * @return 文字コード、または null
     */
    static Charset resolve(Map<String, String> env) {
        String requested = env.get(ENCODING_ENV);
        if (requested != null && !requested.isBlank()) {
            try {
                return Charset.forName(requested.trim());
            } catch (IllegalArgumentException e) {
                System.err.println("Unknown " + ENCODING_ENV + " \"" + requested + "\" was ignored.");
            }
        }
        String jenkinsUrl = env.get("JENKINS_URL");
        if (jenkinsUrl != null && !jenkinsUrl.isBlank()) {
            return Charset.forName("UTF-8");
        }
        return null;
    }
}
