package mx.endcom.hermes.banxicosim.ara;

import java.security.PrivateKey;

import mx.endcom.hermes.banxicosim.crypto.RsaCipher;
import mx.endcom.hermes.banxicosim.wire.ByteReader;
import mx.endcom.hermes.banxicosim.wire.ByteWriter;

/**
 * Envoltura de mensajes ARA "firmados" (los que extienden {@code AraSignedMessage} del lado de
 * minos) — usada para {@code RegCrtNvoFmt} (el simulador entrega un certificado a minos).
 *
 * <p><b>Hallazgo importante (documentado con precisión porque no es intuitivo):</b> el código de
 * minos que arma este tipo de mensaje para SU PROPIA salida
 * ({@code core/ara/message/AraSignedMessage.java#signMessage}, líneas 139-147) construye el
 * cuerpo como {@code [tamañoFirma(4)] + [cuerpo] + [firma]} — SIN ningún byte de relleno entre
 * el cuerpo y la firma. Pero el código que usa esa MISMA clase para PARSEAR un mensaje entrante
 * ({@code AraSignedMessage#extractSignature}, líneas 94-100) calcula el inicio de la firma como
 * {@code BODY_INDEX + bodySize + 1} — con un {@code +1} de más. Esas dos rutas no son
 * simétricas: es, aparentemente, un desajuste real en el código de minos entre cómo arma sus
 * propios mensajes salientes y cómo espera los entrantes.</p>
 *
 * <p>Como el simulador le manda mensajes a minos y es minos quien los PARSEA, lo que importa es
 * replicar exactamente la fórmula de análisis (con el {@code +1}), no la de construcción. Este
 * método arma el cuerpo con un byte de relleno (arbitrario, se descarta al leer) entre el
 * contenido y la firma, y declara el tamaño total del cuerpo del encabezado ARA acorde, para que
 * el cálculo de {@code extractBody}/{@code extractSignature} de minos aterrice exactamente en los
 * límites correctos. Efecto práctico observado: minos SÍ recorta el último byte de la firma
 * capturada (por el mismo desajuste), pero eso no bloquea nada porque
 * {@code AraInputManagerServiceImpl} no verifica esta firma en ningún punto del flujo de
 * descarga de certificados (sólo la guarda/usa el número y el certificado, no valida la firma
 * del mensaje ARA en sí) — ver investigación de esta tarea.</p>
 */
public final class AraWireFraming {

	private AraWireFraming() {
	}

	public record SignedAraBody(byte[] wireBody) {
	}

	/**
	 * Arma [tamañoFirma(4)] + contenido + [1 byte de relleno] + firmaB64, tal como minos lo espera
	 * al parsear un {@code AraSignedMessage} entrante (ver nota de clase).
	 */
	public static byte[] buildSignedBody(byte[] content, PrivateKey signingKey) throws Exception {
		byte[] rawSignature = RsaCipher.sign(content, signingKey);
		byte[] sigB64 = RsaCipher.encodeBase64(rawSignature);
		return new ByteWriter()
				.writeIntBE(sigB64.length)
				.writeBytes(content)
				.writeByte(0x00) // byte de relleno requerido por el desajuste de minos, ver nota de clase
				.writeBytes(sigB64)
				.toByteArray();
	}

	/**
	 * Deshace un cuerpo de {@code AraSignedMessage} tal como minos MISMO lo construye para sus
	 * propios mensajes salientes (ej. {@code ConnUsr}) — ver {@code AraSignedMessage#signMessage},
	 * líneas 139-147: {@code [tamañoFirma(4)] + contenido + firma}, SIN byte de relleno. Esta es
	 * la ruta simétrica (construcción == análisis); el desajuste con relleno de {@link #buildSignedBody}
	 * sólo aplica al sentido simulador-&gt;minos.
	 */
	public static ParsedSignedBody parseSymmetricSignedBody(byte[] wireBody) {
		ByteReader r = new ByteReader(wireBody);
		int sigSize = r.readIntBE();
		int contentLen = wireBody.length - 4 - sigSize;
		byte[] content = r.readBytes(contentLen);
		byte[] signatureB64 = r.readBytes(sigSize);
		return new ParsedSignedBody(content, signatureB64);
	}

	public record ParsedSignedBody(byte[] content, byte[] signatureB64) {
	}

	/**
	 * Contraparte de {@link #buildSignedBody} -- deshace el cuerpo de {@code RegCrtNvoFmt}/
	 * {@code CrtNoExiste} (los dos mensajes que el simulador arma con este formato CON relleno,
	 * ver nota de clase). No existía hasta que el arnés de pruebas Java (spec 007, 2026-10-06)
	 * necesitó parsear la respuesta a {@code PideCrtNvo} jugando el papel de minos -- a diferencia
	 * de minos real, que sí tiene el desajuste del {@code +1}, este parser replica la fórmula de
	 * construcción exacta de {@link #buildSignedBody} (simétrica, sin el desajuste) porque es el
	 * lado que SÍ controla cómo se construyó el mensaje.
	 */
	public static ParsedSignedBody parsePaddedSignedBody(byte[] wireBody) {
		ByteReader r = new ByteReader(wireBody);
		int sigSize = r.readIntBE();
		int contentLen = wireBody.length - 4 - 1 - sigSize;
		byte[] content = r.readBytes(contentLen);
		r.readUnsignedByte(); // byte de relleno, ver nota de clase de buildSignedBody
		byte[] signatureB64 = r.readBytes(sigSize);
		return new ParsedSignedBody(content, signatureB64);
	}
}
