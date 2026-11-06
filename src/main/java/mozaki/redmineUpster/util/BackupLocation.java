package mozaki.redmineUpster.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * バックアップ（{@code .bak}）の保存先。
 * <p>
 * 保存先フォルダが未設定なら元のファイルと同じフォルダに {@code <ファイル名>.bak}（従来どおり）。
 * 設定されていればそのフォルダ（なければ作成）に {@code <ファイル名>.bak} を保存し、同名のバックアップは上書きします。
 * 1回の実行で同じファイル名の別フォルダのファイルを扱う場合（{@link #withFiles}）だけ、
 * 元のフォルダから作った短い識別子を付けて {@code <ファイル名>.<識別子>.bak} にし、互いに上書きしないようにします。
 * </p>
 */
public final class BackupLocation {

	/** 元のファイルと同じフォルダ（従来の動作） */
	public static final BackupLocation BESIDE_ORIGINAL = new BackupLocation(null, Set.of());

	private final Path dir;
	private final Set<String> collidingNames;

	private BackupLocation(Path dir, Set<String> collidingNames) {
		this.dir = dir;
		this.collidingNames = collidingNames;
	}

	/**
	 * @param dir 保存先フォルダ（null・空欄なら元のファイルと同じフォルダ）
	 * @return 保存先
	 */
	public static BackupLocation of(String dir) {
		if (dir == null || dir.isBlank()) {
			return BESIDE_ORIGINAL;
		}
		return new BackupLocation(Path.of(dir.trim()).toAbsolutePath().normalize(), Set.of());
	}

	/**
	 * 同じ実行で扱うファイルを登録します。保存先フォルダが設定されていて、別フォルダの同じファイル名があれば
	 * そのファイル名のバックアップに識別子を付けます。
	 *
	 * @param files 同じ実行で書き込むファイル
	 * @return 保存先
	 */
	public BackupLocation withFiles(Collection<Path> files) {
		if (dir == null || files == null) {
			return this;
		}
		Map<String, Set<Path>> byName = new HashMap<>();
		for (Path file : files) {
			Path absolute = file.toAbsolutePath().normalize();
			byName.computeIfAbsent(key(absolute.getFileName().toString()), k -> new HashSet<>()).add(absolute);
		}
		Set<String> colliding = new HashSet<>(collidingNames);
		byName.forEach((name, paths) -> {
			if (paths.size() > 1) {
				colliding.add(name);
			}
		});
		return new BackupLocation(dir, Set.copyOf(colliding));
	}

	/**
	 * @return 保存先フォルダ（元のファイルと同じフォルダの場合 null）
	 */
	public Path dir() {
		return dir;
	}

	/**
	 * バックアップのパスを返します（保存先フォルダがなければ作成します）。
	 *
	 * @param original 元のファイル
	 * @return バックアップのパス
	 * @throws IOException フォルダを作成できない場合
	 */
	public Path backupFor(Path original) throws IOException {
		String name = original.getFileName().toString();
		if (dir == null) {
			return original.resolveSibling(name + ".bak");
		}
		Files.createDirectories(dir);
		if (collidingNames.contains(key(name))) {
			Path parent = original.toAbsolutePath().normalize().getParent();
			return dir.resolve(name + "." + shortId(parent) + ".bak");
		}
		return dir.resolve(name + ".bak");
	}

	private static String key(String name) {
		// Windows ではファイル名の大文字小文字を区別しない
		return name.toLowerCase(Locale.ROOT);
	}

	private static String shortId(Path parent) {
		String folder = parent == null || parent.getFileName() == null ? "root" : parent.getFileName().toString();
		CRC32 crc = new CRC32();
		crc.update(String.valueOf(parent).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
		String safe = folder.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
		return safe + "-" + String.format("%08x", crc.getValue());
	}

	@Override
	public String toString() {
		return dir == null ? "元のファイルと同じフォルダ" : dir.toString();
	}
}
