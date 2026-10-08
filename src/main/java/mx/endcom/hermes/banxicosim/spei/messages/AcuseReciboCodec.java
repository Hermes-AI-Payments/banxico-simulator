package mx.endcom.hermes.banxicosim.spei.messages;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import mx.endcom.hermes.banxicosim.wire.ByteReader;
import mx.endcom.hermes.banxicosim.wire.ByteWriter;

/**
 * Construye el cuerpo de {@code AcuseReciboMessage} (código 27), verificado campo por campo
 * contra {@code core/spei/message/in/AcuseReciboMessage.java#loadProperties} (líneas 49-62) del
 * repo minos: {@code serverTimestamp(7) → operationDate(4) → folio(4) → entityIndex(1) →
 * entityCode(4) → instructionFolio(4) → status(1) → totalErrors(4) → internalFolios[](2c/u) →
 * codeErrors[](1c/u)}.
 *
 * <p><b>Decisión de diseño (la spec no detalla la semántica exacta de cada campo):</b>
 * {@code folio} debe reflejar el {@code folioPack} recibido (esto sí lo dice la spec técnica
 * &sect;6). {@code instructionFolio} se usa igual al {@code folioPack} porque AcuseRecibo acusa el
 * PAQUETE completo, no cada orden individual (las órdenes rechazadas se listan aparte en
 * {@code internalFolios}/{@code codeErrors}, indexadas por folio interno de orden, no por folio
 * de instrucción). {@code entityIndex}/{@code entityCode} llevan los del emisor del paquete
 * (minos), reflejando a quién se dirige el acuse.</p>
 */
public final class AcuseReciboCodec {

	private AcuseReciboCodec() {
	}

	/** 0 = paquete aceptado en su totalidad; 1 = paquete con una o más órdenes rechazadas. */
	public static final char STATUS_ACCEPTED = 0;
	public static final char STATUS_REJECTED = 1;

	public record OrderError(short internalFolio, char errorCode) {
	}

	public static byte[] buildBody(
			LocalDate operationDate,
			int folioPack,
			int entityIndex,
			int entityCode,
			char status,
			List<OrderError> errors) {

		ByteWriter w = new ByteWriter();
		w.writeDateTime(LocalDateTime.now());
		w.writeDate(operationDate);
		w.writeIntBE(folioPack); // folio
		w.writeChar((char) entityIndex);
		w.writeIntBE(entityCode);
		w.writeIntBE(folioPack); // instructionFolio (ver nota de clase)
		w.writeChar(status);
		w.writeIntBE(errors.size());
		for (OrderError e : errors) {
			w.writeShortBE(e.internalFolio());
		}
		for (OrderError e : errors) {
			w.writeChar(e.errorCode());
		}
		return w.toByteArray();
	}

	public record Parsed(int folioPack, char status, List<OrderError> errors) {
	}

	/**
	 * Inverso de {@link #buildBody}, para el arnés de pruebas ({@code FakeMinosClient}) -- el
	 * simulador real nunca lo necesita (solo construye este mensaje, nunca lo recibe). Se le pasa
	 * el cuerpo YA SIN el prefijo de 2 bytes de {@link mx.endcom.hermes.banxicosim.spei.WireFraming#withLengthPrefix}
	 * (plano, sin cifrar -- ver la nota de esa clase: AcuseRecibo no usa AES).
	 */
	public static Parsed parse(byte[] body) {
		ByteReader r = new ByteReader(body);
		r.readDateTime(); // serverTimestamp, no se usa en la prueba
		r.readDate(); // operationDate
		int folio = r.readIntBE();
		r.readChar(); // entityIndex
		r.readIntBE(); // entityCode
		r.readIntBE(); // instructionFolio
		char status = r.readChar();
		int totalErrors = r.readIntBE();
		short[] folios = new short[totalErrors];
		for (int i = 0; i < totalErrors; i++) {
			folios[i] = r.readShortBE();
		}
		java.util.List<OrderError> errors = new java.util.ArrayList<>(totalErrors);
		for (int i = 0; i < totalErrors; i++) {
			errors.add(new OrderError(folios[i], r.readChar()));
		}
		return new Parsed(folio, status, errors);
	}
}
