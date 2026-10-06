package mx.endcom.hermes.banxicosim.spei.messages;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import mx.endcom.hermes.banxicosim.wire.ByteReader;
import mx.endcom.hermes.banxicosim.wire.ByteWriter;

/**
 * Tras {@code MsjCatalogos}, minos SIEMPRE manda {@code Reenvio} (código 207, AES simple sin
 * firmar ni particionar — ver {@code core/spei/message/out/ReenvioMessage.java} y
 * {@code SpeiOutputEncryptedMessage}) preguntando desde qué byte debe reenviar; el simulador debe
 * responder {@code FinReenvio} (código 32, AES simple — ver
 * {@code core/spei/message/in/FinReenvioMessage.java:29-37}) para que la sesión quede operativa.
 * Esto no está en el resumen de fases de la spec técnica pero SÍ es necesario en el código real
 * (verificado: {@code processMsjCatalogosMessage} llama a {@code sendReenvio} incondicionalmente).
 *
 * <p>Spec 003 (2026-10-06): {@code SpeiSession.handleReenvio} ya NO simplifica — reenvía de
 * verdad los bytes mandados después de {@code processedBytes} (ver {@code SentHistory}).
 * {@code FinReenvio} se sigue mandando con contadores en cero: la spec no pide que reporte cuánto
 * se reenvió, sólo que cierre el intercambio sin error — ver {@code specs/003-reenvio.md}
 * &sect;"Estado de implementación".</p>
 */
public final class ReenvioCodec {

	private ReenvioCodec() {
	}

	public record Reenvio(LocalDateTime timestamp, int processedBytes) {
	}

	public static Reenvio parse(byte[] plaintextBody) {
		ByteReader r = new ByteReader(plaintextBody);
		LocalDateTime ts = r.readDateTime();
		int processedBytes = r.readIntBE();
		return new Reenvio(ts, processedBytes);
	}

	/**
	 * Construye el cuerpo (sin cifrar) de {@code Reenvio} tal como lo manda minos real -- el
	 * simulador nunca lo había necesitado mandar (solo lo recibe), así que no existía un "build"
	 * hasta que el arnés de pruebas Java (specs 003/007, 2026-10-06) necesitó poder jugar el papel
	 * de minos para ejercitar {@code SpeiSession.handleReenvio} de punta a punta. El cuerpo va
	 * cifrado con AES de sesión antes de mandarse (ver {@code AesCipher.encrypt}, simple, sin
	 * particionar ni firmar -- igual que {@link #buildFinReenvioBody}).
	 */
	public static byte[] buildReenvioBody(LocalDateTime timestamp, int processedBytes) {
		return new ByteWriter()
				.writeDateTime(timestamp)
				.writeIntBE(processedBytes)
				.toByteArray();
	}

	public static byte[] buildFinReenvioBody() {
		return new ByteWriter()
				.writeDateTime(LocalDateTime.now())
				.writeIntBE(0) // bytesServerToClient
				.writeMoney(BigDecimal.ZERO) // totalBalance
				.writeIntBE(0) // bytesClientToServer
				.writeMoney(BigDecimal.ZERO) // reservedBalance
				.toByteArray();
	}

	public record FinReenvio(LocalDateTime timestamp, int bytesServerToClient, BigDecimal totalBalance,
			int bytesClientToServer, BigDecimal reservedBalance) {
	}

	/** Contraparte de {@link #buildFinReenvioBody} -- usada por el arnés de pruebas para confirmar
	 *  que {@code FinReenvio} llega bien formado y sin error (criterio de aceptación "b" de
	 *  specs/003-reenvio.md). */
	public static FinReenvio parseFinReenvio(byte[] plaintextBody) {
		ByteReader r = new ByteReader(plaintextBody);
		LocalDateTime ts = r.readDateTime();
		int bytesServerToClient = r.readIntBE();
		BigDecimal totalBalance = r.readMoney();
		int bytesClientToServer = r.readIntBE();
		BigDecimal reservedBalance = r.readMoney();
		return new FinReenvio(ts, bytesServerToClient, totalBalance, bytesClientToServer, reservedBalance);
	}
}
