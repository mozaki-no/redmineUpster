package mozaki.redmineUpster.util;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Optional;

/**
 * コンソール（標準出力・標準エラー）の文字コードを設定します。
 *
 * <p>Jenkins では標準出力がパイプになるため、Java は native.encoding（日本語 Windows では MS932）で
 * 書き出しますが、Jenkins（Java 18 以降）は UTF-8 として読むため文字化けします。
 * 優先順: {@code --console-encoding=<名前>} ＞ 環境変数 {@code REDMINEUPSTER_CONSOLE_ENCODING}
 * ＞ 環境変数 {@code JENKINS_URL} があれば UTF-8 ＞ 何もしない（従来どおり）。
 * Spring のコンソールログ（{@code logging.charset.console}）も同じ文字コードにします。</p>
 */
public final class ConsoleEncoding {

    public static final String ARG = "--console-encoding";
    public static final String ENV = "REDMINEUPSTER_CONSOLE_ENCODING";

    private ConsoleEncoding() {
    }

    /** 引数と環境変数から文字コードを決め、標準出力・標準エラーを差し替えます。 */
    public static void configure(String[] args) {
        Optional<Charset> charset = resolve(args, System.getenv(), System.err);
        if (charset.isEmpty()) {
            return;
        }
        Charset cs = charset.get();
        System.setOut(new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)), true, cs));
        System.setErr(new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.err)), true, cs));
        System.setProperty("logging.charset.console", cs.name());
    }

    /** 文字コードを決めます（不正な名前は警告せず空）。 */
    public static Optional<Charset> resolve(String[] args, Map<String, String> env) {
        return resolve(args, env, null);
    }

    static Optional<Charset> resolve(String[] args, Map<String, String> env, PrintStream warn) {
        String name = null;
        String source = null;
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.startsWith(ARG + "=")) {
                    name = arg.substring(ARG.length() + 1).trim();
                    source = ARG;
                }
            }
        }
        if (isBlank(name) && env != null && !isBlank(env.get(ENV))) {
            name = env.get(ENV).trim();
            source = ENV;
        }
        if (isBlank(name)) {
            if (env != null && !isBlank(env.get("JENKINS_URL"))) {
                return Optional.of(java.nio.charset.StandardCharsets.UTF_8);
            }
            return Optional.empty();
        }
        try {
            return Optional.of(Charset.forName(name));
        } catch (IllegalArgumentException e) {
            if (warn != null) {
                warn.println("Warning: unknown console encoding (" + source + "): " + name
                        + " - keeping the default console encoding");
            }
            return Optional.empty();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
