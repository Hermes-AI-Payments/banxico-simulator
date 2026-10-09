package mx.endcom.hermes.banxicosim.spei;

import java.security.PrivateKey;
import java.security.PublicKey;

import mx.endcom.hermes.banxicosim.crypto.AesCipher;
import mx.endcom.hermes.banxicosim.crypto.RsaCipher;
import mx.endcom.hermes.banxicosim.wire.ByteReader;
import mx.endcom.hermes.banxicosim.wire.ByteWriter;

/**
 * Envolturas de cuerpo del protocolo SPEI, verificadas contra el código fuente real de minos:
 * particionado (con prefijo de tamaño), firmado y cifrado AES-CBC de sesión. Se implementan en
 * ambos sentidos (construir para mandar, y deshacer para parsear) porque el simulador hace las
 * dos cosas según el mensaje.
 *
 * <p>Fuente: {@code core/spei/message/in/protocol/SpeiInput{Partitioned,Signed,EncryptedAndSigned}Message.java}
 * y sus contrapartes {@code .../out/protocol/SpeiOutput...Message.java}. Confirmado: NO hay bytes
 * de relleno entre el cuerpo y la firma en el protocolo SPEI principal (a diferencia del protocolo
 * ARA — ver {@code AraSignedMessage}, que sí tiene una peculiaridad de un byte, documentada en
 * {@code AraWireFraming}).</p>
 */
public final class WireFraming {

	private WireFraming() {
	}

	/** Antepone el prefijo de 2 bytes = tamaño total (particionado sin cifrar, ej. EnSesion, AcuseRecibo).
	 *  Como el simulador siempre manda el mensaje completo en una sola parte, esto basta para que
	 *  minos considere el mensaje completo de inmediato (ver SpeiInputPartitionedMessage.isComplete():
	 *  {@code totalBodySize == body.length - 2}). */
	public static byte[] withLengthPrefix(byte[] inner) {
		return new ByteWriter().writeShortBE((short) inner.length).writeBytes(inner).toByteArray();
	}

	/** Firma {@code payload} con la llave privada propia y arma [tamañoFirma BE(4)][payload][firma],
	 *  donde la firma va en Base64 (ASCII) — igual que minos (ver SpeiOutputSignedMessage.signMessage,
	 *  que firma con RSASSA-PSS/SHA-512 vía CipherBase64.sign512RSASSA — NO SHA256withRSA; ver nota
	 *  en RsaCipher.SIGN_ALGO_PSS). */
	public static byte[] signedBlock(byte[] payload, PrivateKey signingKey) throws Exception {
		byte[] rawSignature = RsaCipher.signRSASSAPSS(payload, signingKey);
		byte[] sigB64 = RsaCipher.encodeBase64(rawSignature);
		return new ByteWriter()
				.writeIntBE(sigB64.length)
				.writeBytes(payload)
				.writeBytes(sigB64)
				.toByteArray();
	}

	/** Cifra con AES-CBC de sesión (llave/IV acordados en ClvSim/RespClvSim). */
	public static byte[] encryptSession(byte[] plaintext, byte[] key, byte[] iv) throws Exception {
		return AesCipher.encrypt(plaintext, key, iv);
	}

	/** Como {@link #signedBlock} pero con la firma de la envoltura corrupta (spec 004, sentido
	 *  H&rarr;B -- mismo criterio que {@code AbonosCodec.SignatureMode.CORRUPTA}: mismo tamaño y
	 *  codificación que una firma real, un byte alterado). Solo para el arnés de pruebas (simular
	 *  que "minos" manda una envoltura con firma inválida contra este simulador); el simulador
	 *  nunca necesita producir esto en operación real. */
	public static byte[] signedBlockCorrupto(byte[] payload, PrivateKey signingKey) throws Exception {
		byte[] rawSignature = RsaCipher.signRSASSAPSS(payload, signingKey);
		// Se voltea el último byte de la firma CRUDA, antes de Base64 -- igual que
		// AbonosCodec#corrupt. Voltear un byte del texto Base64 ya codificado arriesga aterrizar en
		// un caracter fuera del alfabeto Base64 (ej. '=' de relleno), que es una corrupción distinta
		// (falla de decodificación, no de verificación de firma).
		rawSignature[rawSignature.length - 1] ^= 0x01;
		byte[] sigB64 = RsaCipher.encodeBase64(rawSignature);
		return new ByteWriter()
				.writeIntBE(sigB64.length)
				.writeBytes(payload)
				.writeBytes(sigB64)
				.toByteArray();
	}

	/** Arma el cuerpo de un mensaje "particionado + cifrado" sin firma, tipo MsjCatalogos:
	 *  cifra( [totalSize(2)] + payload ). Ver SpeiInputEncryptedPartitionedMessage (MsjCatalogosMessage
	 *  extiende esta clase directamente, sin envoltura de firma, a diferencia de Abonos/OrdenTopoV). */
	public static byte[] buildEncryptedPartitioned(byte[] payload, byte[] sessionKey, byte[] sessionIv)
			throws Exception {
		return encryptSession(withLengthPrefix(payload), sessionKey, sessionIv);
	}

	/**
	 * Arma el cuerpo completo de un mensaje "particionado + firmado + cifrado" tipo Abonos:
	 * [totalSize(2)] + [sigSize(4)] + payload + firmaB64, luego cifrado. Spec 001: si el resultado
	 * excede {@code maxMessageLength - 4} bytes, se reparte en varios frames -- verificado byte a
	 * byte contra el algoritmo real de partición de minos
	 * ({@code SpeiOutputSignedPartitionedMessage.setMessageParts()}, repo minos): el prefijo de
	 * {@code totalSize} se antepone UNA SOLA VEZ al inicio de todo el stream (no por frame), ese
	 * stream completo se corta en trozos de {@code maxMessageLength - 4} bytes, y CADA trozo se
	 * cifra por separado (sin encadenar el cifrado entre trozos -- AES-CBC arranca fresco en cada
	 * uno, misma llave/IV de sesión). {@code maxMessageLength} es el mismo valor que el simulador
	 * declara en su propio {@code EnSesion} (ver {@code EnSesionCodec.buildBody}) -- minos usa ESE
	 * valor para decidir si necesita partir sus propios envíos hacia el simulador, así que bajarlo
	 * es cómo se prueba reensamblado real contra minos real, sin necesitar un arnés sintético.
	 *
	 * @return uno o más cuerpos ya cifrados, un elemento por frame a mandar en el mismo orden.
	 */
	public static java.util.List<byte[]> buildEncryptedSignedPartitionedFrames(byte[] payload,
			PrivateKey signingKey, byte[] sessionKey, byte[] sessionIv, int maxMessageLength) throws Exception {
		byte[] signedBlock = signedBlock(payload, signingKey);
		byte[] withPrefix = withLengthPrefix(signedBlock);
		int chunkSize = maxMessageLength - 4; // 4 = tamaño del header de Frame (destino+operación+tamaño)
		java.util.List<byte[]> frames = new java.util.ArrayList<>();
		int offset = 0;
		do {
			int len = Math.min(chunkSize, withPrefix.length - offset);
			byte[] chunk = java.util.Arrays.copyOfRange(withPrefix, offset, offset + len);
			frames.add(encryptSession(chunk, sessionKey, sessionIv));
			offset += len;
		} while (offset < withPrefix.length);
		return frames;
	}

	/** Como {@link #buildEncryptedSignedPartitionedFrames} pero siempre en un solo frame -- el
	 *  caso de siempre antes de la spec 001, se mantiene igual para no tocar nada que ya funciona
	 *  (equivale a llamar con {@code maxMessageLength} suficientemente grande para que nunca parta). */
	public static byte[] buildEncryptedSignedPartitioned(byte[] payload, PrivateKey signingKey,
			byte[] sessionKey, byte[] sessionIv) throws Exception {
		byte[] signedBlock = signedBlock(payload, signingKey);
		byte[] withPrefix = withLengthPrefix(signedBlock);
		return encryptSession(withPrefix, sessionKey, sessionIv);
	}

	/** Como {@link #buildEncryptedSignedPartitioned} pero con {@link #signedBlockCorrupto} --
	 *  solo para el arnés de pruebas, spec 004 H&rarr;B. */
	public static byte[] buildEncryptedSignedPartitionedCorrupto(byte[] payload, PrivateKey signingKey,
			byte[] sessionKey, byte[] sessionIv) throws Exception {
		byte[] signedBlock = signedBlockCorrupto(payload, signingKey);
		byte[] withPrefix = withLengthPrefix(signedBlock);
		return encryptSession(withPrefix, sessionKey, sessionIv);
	}

	/**
	 * Acumulador de reensamblado real (spec 001, lado de recepción H→B) -- alimentar un frame
	 * cifrado a la vez (mismo orden en que llegan por el socket); {@link #isComplete()} indica
	 * cuándo ya se puede llamar a {@link #assembledPlaintext()}. Cada frame se descifra por
	 * separado y se concatena en texto plano -- verificado contra el algoritmo de recepción real
	 * de minos ({@code SpeiInputPartitionedMessage.addPart}/{@code isComplete}, repo minos): NO es
	 * un stream cifrado continuo, cada frame es un cifrado AES-CBC independiente y autocontenido.
	 */
	public static final class PartitionedAccumulator {
		private byte[] buffer = new byte[0];
		private Integer totalSize;

		public void feed(byte[] cipherChunk, byte[] sessionKey, byte[] sessionIv) throws Exception {
			byte[] plaintext = AesCipher.decrypt(cipherChunk, sessionKey, sessionIv);
			byte[] newBuffer = new byte[buffer.length + plaintext.length];
			System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
			System.arraycopy(plaintext, 0, newBuffer, buffer.length, plaintext.length);
			buffer = newBuffer;
			if (totalSize == null) {
				totalSize = new ByteReader(buffer).readUnsignedShortBE();
			}
		}

		public boolean isComplete() {
			return totalSize != null && buffer.length - 2 == totalSize;
		}

		/** El plaintext completo, prefijo de totalSize incluido -- listo para {@link #parseSignedPartitioned}. */
		public byte[] assembledPlaintext() {
			return buffer;
		}
	}

	/**
	 * Parsea un plaintext YA COMPLETO (una sola parte, o ya reensamblado vía
	 * {@link PartitionedAccumulator}) con la forma [totalSize(2)][sigSize(4)][payload][firmaB64].
	 */
	public static Unwrapped parseSignedPartitioned(byte[] plaintext, PublicKey verifyingKey) throws Exception {
		ByteReader r = new ByteReader(plaintext);
		int totalSize = r.readUnsignedShortBE();
		if (totalSize != plaintext.length - 2) {
			throw new IllegalStateException(
					"Mensaje incompleto o corrupto: totalSize=" + totalSize + " pero cuerpo trae "
							+ (plaintext.length - 2) + " bytes.");
		}
		int sigSize = r.readIntBE();
		int payloadLen = plaintext.length - 2 - 4 - sigSize;
		byte[] payload = r.readBytes(payloadLen);
		byte[] signatureB64 = r.readBytes(sigSize);
		boolean verified = false;
		if (verifyingKey != null) {
			try {
				byte[] rawSig = RsaCipher.decodeBase64(signatureB64);
				verified = RsaCipher.verifyRSASSAPSS(payload, rawSig, verifyingKey);
			} catch (Exception e) {
				verified = false;
			}
		}
		return new Unwrapped(payload, verified);
	}

	/**
	 * Deshace un cuerpo "particionado + firmado + cifrado" de UN SOLO frame (ej. OrdenTopoV que
	 * cupo completo). Para mensajes partidos en varios frames, usar {@link PartitionedAccumulator}
	 * + {@link #parseSignedPartitioned} en su lugar (ver {@code SpeiSession.handleOrdenTopoV}).
	 *
	 * @param cipherBody cuerpo tal como llegó por el cable (ya cifrado)
	 * @return el payload (contenido específico del mensaje) ya desenvuelto
	 */
	public static Unwrapped unwrapEncryptedSignedPartitioned(byte[] cipherBody, byte[] sessionKey,
			byte[] sessionIv, PublicKey verifyingKey) throws Exception {
		byte[] plaintext = AesCipher.decrypt(cipherBody, sessionKey, sessionIv);
		return parseSignedPartitioned(plaintext, verifyingKey);
	}

	public record Unwrapped(byte[] payload, boolean signatureVerified) {
	}
}
