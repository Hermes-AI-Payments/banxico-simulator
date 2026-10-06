package mx.endcom.hermes.banxicosim.spei.messages;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;

import mx.endcom.hermes.banxicosim.crypto.RsaCipher;
import mx.endcom.hermes.banxicosim.wire.ByteReader;
import mx.endcom.hermes.banxicosim.wire.ByteWriter;

/**
 * Desafío RSA de sesión: el simulador manda {@code ClvSim} (código 80) y minos responde
 * {@code RespClvSim} (código 221).
 *
 * <p>Verificado contra {@code core/spei/dto/in/ClvSim.java} (lo que minos parsea) y
 * {@code SpeiInputManagerServiceImpl#processClvSimMessage} (líneas 860-869) del repo minos:</p>
 * <ul>
 *   <li>minos desencripta {@code encriptedSymetricKey} con SU PROPIA llave privada
 *       ({@code Spei.privateKey}) — por eso el simulador debe cifrar con la llave PÚBLICA real
 *       de minos (pre-configurada, la misma que se usa para el reto ARA IdUsuarioAleat si minos
 *       usa un solo certificado para ambos sockets — ver README).</li>
 *   <li><b>Único mensaje que no usa {@code RSA/ECB/PKCS1Padding}</b>: {@code ClvSim.build()}
 *       (línea 92 del repo minos) desencripta con {@code RSA/None/OAEPWithSHA-512AndMGF1Padding}
 *       explícitamente vía proveedor "BC" — encontrado en despliegue real 2026-09-14 contra
 *       minos real (el simulador traía {@code RsaCipher.encryptToBase64} de uso general, que usa
 *       PKCS1Padding, y minos tronaba con {@code BadBlockException: unable to decrypt block} al
 *       recibir este mensaje específico; la fase ARA con PKCS1Padding sí funciona igual, no se
 *       tocó). Ver {@link RsaCipher#encryptOaepSha512ToBase64}.</li>
 *   <li>Los primeros 16 bytes desencriptados son la llave AES de sesión; los siguientes 16, el IV
 *       (ver {@code ClvSim.build()}: {@code key = copyOfRange(0,16)}, {@code vector = copyOfRange(16,32)}).</li>
 *   <li>minos firma exactamente esos 32 bytes (la llave simétrica completa, ANTES de partirla)
 *       con su propia llave privada, y regresa esa firma en Base64 como único campo de
 *       {@code RespClvSim}. <b>El algoritmo de firma depende de la rama de minos</b>: en
 *       {@code master}, {@code processClvSimMessage} línea 865 usa
 *       {@code CipherBase64.sign(symmetricalKey, Spei.privateKey)} ({@code SHA256withRSA}); en
 *       {@code refactorClaude}, {@code ClvSimMessageHandler.java:38} usa
 *       {@code CipherBase64.sign512RSASSA(...)} (RSASSA-PSS/SHA-512) — confirmado en despliegue
 *       real 2026-09-17 contra la instancia "minosa" (que corre {@code refactorClaude}): la firma
 *       no verificaba con {@code SHA256withRSA} hasta agregar soporte para PSS. Como no hay forma
 *       de saber de antemano qué rama corre cada instancia real, {@link RsaCipher#verifyEitherScheme}
 *       intenta ambos esquemas.</li>
 *   <li>El campo {@code signature} que el simulador manda en {@code ClvSim} NO es leído por
 *       {@code ClvSim.build()} ni usado en ningún punto de {@code processClvSimMessage} — es
 *       decorativo del lado de minos. El simulador igual lo llena con una firma real propia
 *       (firma los mismos 32 bytes con su propia llave privada) porque ADR-005 pide protocolo
 *       real sin atajos, aunque minos no la verifique.</li>
 * </ul>
 */
public final class ClvSimCodec {

	private static final SecureRandom RANDOM = new SecureRandom();

	private ClvSimCodec() {
	}

	public record SessionKeys(byte[] key, byte[] iv, byte[] rawSymmetricKey) {
	}

	public record ClvSimBody(byte[] bytes, SessionKeys sessionKeys) {
	}

	public static ClvSimBody build(PublicKey minosPublicKey, PrivateKey ownPrivateKey) throws Exception {
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		byte[] key = java.util.Arrays.copyOfRange(raw, 0, 16);
		byte[] iv = java.util.Arrays.copyOfRange(raw, 16, 32);

		byte[] encryptedB64 = RsaCipher.encryptOaepSha512ToBase64(raw, minosPublicKey);
		byte[] ownSignatureB64 = RsaCipher.encodeBase64(RsaCipher.sign(raw, ownPrivateKey));

		byte[] body = new ByteWriter()
				.writeShortBE((short) 1) // idEncryptionAlgorithm: valor arbitrario, minos no lo valida
				.writeCString(new String(encryptedB64, StandardCharsets.US_ASCII))
				.writeCString(new String(ownSignatureB64, StandardCharsets.US_ASCII))
				.toByteArray();

		return new ClvSimBody(body, new SessionKeys(key, iv, raw));
	}

	public record ClvSimRequest(byte[] rawSymmetricKey, byte[] key, byte[] iv) {
	}

	/**
	 * Contraparte de {@link #build} -- parsea el cuerpo de {@code ClvSim} tal como lo manda el
	 * simulador, para que el arnés de pruebas Java (specs 003/007, 2026-10-06) pueda jugar el
	 * papel de minos y completar el desafío (necesita la llave/IV de sesión para todo lo que viene
	 * después: {@code MsjCatalogos}, {@code Reenvio}). No existía hasta ahora porque el simulador
	 * nunca necesita parsear su propio {@code ClvSim}, sólo construirlo.
	 */
	public static ClvSimRequest parse(byte[] clvSimBody, PrivateKey minosPrivateKey) throws Exception {
		ByteReader r = new ByteReader(clvSimBody);
		r.readShortBE(); // idEncryptionAlgorithm, no validado (ver build)
		String encryptedB64 = r.readCString();
		r.readCString(); // firma propia del simulador -- decorativa, ver nota de clase
		byte[] raw = RsaCipher.decryptOaepSha512FromBase64(
				encryptedB64.getBytes(StandardCharsets.US_ASCII), minosPrivateKey);
		byte[] key = java.util.Arrays.copyOfRange(raw, 0, 16);
		byte[] iv = java.util.Arrays.copyOfRange(raw, 16, 32);
		return new ClvSimRequest(raw, key, iv);
	}

	/**
	 * Construye el cuerpo de {@code RespClvSim} tal como lo manda minos real -- usado por el arnés
	 * de pruebas Java para completar el desafío ClvSim del lado de "minos falso" (specs 003/007).
	 * Firma con {@code SHA256withRSA} (rama {@code master} de minos real); {@link #verifyResponse}
	 * ya intenta ambos esquemas de firma (ver {@code RsaCipher.verifyEitherScheme}), así que esto
	 * basta para que el arnés verifique en verde contra el simulador sin importar qué rama imite.
	 */
	public static byte[] buildRespClvSimBody(byte[] rawSymmetricKey, PrivateKey minosPrivateKey) throws Exception {
		byte[] sigB64 = RsaCipher.encodeBase64(RsaCipher.sign(rawSymmetricKey, minosPrivateKey));
		return new ByteWriter().writeCString(new String(sigB64, StandardCharsets.US_ASCII)).toByteArray();
	}

	public record RespClvSimResult(boolean signatureVerified) {
	}

	/** Verifica (opcional, no bloqueante) la firma que minos regresa en RespClvSim. */
	public static RespClvSimResult verifyResponse(byte[] respClvSimBody, byte[] rawSymmetricKey,
			PublicKey minosPublicKey) {
		try {
			String sigB64 = new ByteReader(respClvSimBody).readCString();
			if (minosPublicKey == null) {
				return new RespClvSimResult(false);
			}
			byte[] rawSig = RsaCipher.decodeBase64(sigB64.getBytes(StandardCharsets.US_ASCII));
			boolean ok = RsaCipher.verifyEitherScheme(rawSymmetricKey, rawSig, minosPublicKey);
			return new RespClvSimResult(ok);
		} catch (Exception e) {
			return new RespClvSimResult(false);
		}
	}
}
