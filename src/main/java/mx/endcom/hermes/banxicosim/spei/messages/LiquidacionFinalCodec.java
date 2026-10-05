package mx.endcom.hermes.banxicosim.spei.messages;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import mx.endcom.hermes.banxicosim.wire.ByteWriter;

/**
 * Construye el payload de {@code LiquidacionFinalMessage} (código 51, simulador→minos, spec 014)
 * — cierre de día operativo. Verificado campo por campo contra
 * {@code core/spei/message/in/LiquidacionFinalMessage.java} (repo {@code mki}, vigente al
 * 2026-09-28): {@code serverTimestamp(7) → operationDate(4) → folio(4) → entityIndex(1) →
 * entityCode(4) → finalAmount(8)}.
 *
 * <p><b>Framing más simple que {@code Abonos}/{@code Cargos}</b>: {@code LiquidacionFinalMessage}
 * extiende {@code SpeiInputEncryptedMessage}, NO {@code ...PartitionedMessage} ni
 * {@code ...SignedMessage} -- solo cifrado AES-CBC de sesión, sin prefijo de tamaño total ni
 * firma. Se manda con {@code WireFraming.encryptSession(payload, sessionKey, sessionIv)}
 * directo.</p>
 */
public final class LiquidacionFinalCodec {

	private LiquidacionFinalCodec() {
	}

	public static byte[] buildPayload(LocalDate operationDate, int folio, int entityIndex, int entityCode,
			BigDecimal finalAmount) {
		ByteWriter w = new ByteWriter();
		w.writeDateTime(LocalDateTime.now());
		w.writeDate(operationDate);
		w.writeIntBE(folio);
		w.writeChar((char) entityIndex);
		w.writeIntBE(entityCode);
		w.writeMoney(finalAmount);
		return w.toByteArray();
	}
}
