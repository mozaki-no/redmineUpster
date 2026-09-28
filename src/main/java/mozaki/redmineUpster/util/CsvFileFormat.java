package mozaki.redmineUpster.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * CSVファイルの文字コード・BOM・改行コードの判定結果。
 * <p>
 * 読み込み（{@code SpreadsheetParser}）と書き戻し（{@code TicketIdWriter}）で
 * 同じ判定を使うことで、元ファイルの形式を保ったまま再出力できるようにします。
 * </p>
 *
 * @param charset 文字コード（UTF-8 または Windows-31J）
 * @param bom UTF-8 BOM付きの場合はtrue
 * @param lineSeparator 改行コード（"\r\n" または "\n"）
 * @param trailingNewline ファイル末尾が改行で終わっている場合はtrue
 */
public record CsvFileFormat(Charset charset, boolean bom, String lineSeparator, boolean trailingNewline) {

	/** Shift_JIS（Windows拡張） */
	public static final Charset WINDOWS_31J = Charset.forName("windows-31j");

	private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	/**
	 * バイト列からCSVの形式を判定します。
	 *
	 * @param bytes ファイル内容
	 * @return 判定結果
	 */
	public static CsvFileFormat detect(byte[] bytes) {
		boolean bom = hasBom(bytes);
		Charset charset;
		if (bom || isValidUtf8(bytes)) {
			charset = StandardCharsets.UTF_8;
		} else {
			charset = WINDOWS_31J;
		}
		String text = decode(bytes, charset, bom);
		String lineSeparator = text.contains("\r\n") ? "\r\n" : "\n";
		boolean trailingNewline = text.endsWith("\n");
		return new CsvFileFormat(charset, bom, lineSeparator, trailingNewline);
	}

	/**
	 * 判定結果に従ってバイト列を文字列に変換します（BOMは除去）。
	 *
	 * @param bytes ファイル内容
	 * @return 文字列
	 */
	public String decode(byte[] bytes) {
		return decode(bytes, charset, bom);
	}

	/**
	 * 判定結果に従って文字列をバイト列に変換します（必要ならBOMを付与）。
	 *
	 * @param text 文字列
	 * @return バイト列
	 */
	public byte[] encode(String text) {
		byte[] body = text.getBytes(charset);
		if (!bom) {
			return body;
		}
		byte[] out = new byte[UTF8_BOM.length + body.length];
		System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
		System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
		return out;
	}

	private static String decode(byte[] bytes, Charset charset, boolean bom) {
		int offset = bom ? UTF8_BOM.length : 0;
		return new String(bytes, offset, bytes.length - offset, charset);
	}

	private static boolean hasBom(byte[] bytes) {
		return bytes.length >= 3
				&& bytes[0] == UTF8_BOM[0]
				&& bytes[1] == UTF8_BOM[1]
				&& bytes[2] == UTF8_BOM[2];
	}

	private static boolean isValidUtf8(byte[] bytes) {
		try {
			StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes));
			return true;
		} catch (CharacterCodingException ex) {
			return false;
		}
	}
}
