package mx.endcom.hermes.banxicosim.crypto;

import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Base64;
import javax.crypto.Cipher;

/**
 * RSA tal como lo usa minos: ver {@code util/impl/CipherBase64.java} del repo minos.
 * Modo/relleno exacto: {@code RSA/ECB/PKCS1Padding}. Firma: {@code SHA256withRSA} para ARA y
 * ClvSim ({@link #sign}/{@link #verify}) — pero {@code Abonos}/{@code OrdenTopoV} (el sobre
 * {@code SpeiOutputSignedMessage}/{@code SpeiInputSignedMessage}) usan un algoritmo de firma
 * DISTINTO, RSASSA-PSS/SHA-512 ({@link #signRSASSAPSS}/{@link #verifyRSASSAPSS} — ver
 * {@code CipherBase64.sign512RSASSA}/{@code verifySignantureRSASSAPSS} en minos). Encontrado
 * 2026-09-21: el código original de este archivo asumía {@code SHA256withRSA} para todo,
 * causando que minos siempre rechazara la firma de {@code Abonos} ("No firmado con certificado
 * SPEI", nunca fatal pero nunca válida tampoco) — dos esquemas de padding distintos nunca validan
 * entre sí aunque la llave y los datos sean correctos.
 *
 * minos codifica los blobs cifrados/firmados en Base64 y los manda como cadenas
 * terminadas en 0x00 (ver {@code ClvSim.build()}, {@code RespClvSim.bytes()},
 * {@code InicioSesionCifrada.bytes()}) — el simulador replica exactamente ese patrón:
 * cifrar/firmar bytes crudos, luego Base64, luego lo que corresponda como C-string.
 */
public final class RsaCipher {

	private static final String CIPHER_ALGO = "RSA/ECB/PKCS1Padding";
	private static final String SIGN_ALGO = "SHA256withRSA";

	/**
	 * Algoritmo real que usa minos para firmar/verificar el sobre de mensajes tipo
	 * {@code Abonos}/{@code OrdenTopoV} -- NO {@link #SIGN_ALGO}. Confirmado contra
	 * {@code core/spei/message/out/protocol/SpeiOutputSignedMessage.signMessage()} (llama a
	 * {@code CipherBase64.sign512RSASSA}, RSASSA-PSS con SHA-512) y su contraparte de
	 * verificación {@code util/impl/CipherBase64.verifySignantureRSASSAPSS} -- ambas usan
	 * {@code PSSParameterSpec("SHA-512","MGF1",MGF1ParameterSpec("SHA-512"), 64, 1)}.
	 * {@link #SIGN_ALGO} (SHA256withRSA/PKCS#1v1.5) sigue siendo correcto para ARA
	 * ({@code AraWireFraming}/{@code AraSession}) y para {@code ClvSim} -- no se tocan, sin
	 * evidencia de que estén mal.
	 */
	private static final String SIGN_ALGO_PSS = "RSASSA-PSS";
	private static final String BC_PROVIDER = "BC";

	/**
	 * Único mensaje del protocolo real que NO usa PKCS1Padding: {@code ClvSim} (el reto RSA de
	 * sesión). Verificado contra {@code core/spei/dto/in/ClvSim.java:92} del repo minos — minos
	 * desencripta la llave simétrica de sesión con esta transformación exacta, pidiendo
	 * explícitamente el proveedor "BC" (ver {@code util/impl/RSACipher.java:36}).
	 */
	private static final String CIPHER_ALGO_CLVSIM = "RSA/None/OAEPWithSHA-512AndMGF1Padding";

	private RsaCipher() {
	}

	public static byte[] encrypt(byte[] data, Key publicKey) throws Exception {
		Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
		cipher.init(Cipher.ENCRYPT_MODE, publicKey);
		return cipher.doFinal(data);
	}

	/** Cifra con el padding exacto que {@code ClvSim.build()} espera — ver {@link #CIPHER_ALGO_CLVSIM}. */
	public static byte[] encryptOaepSha512(byte[] data, Key publicKey) throws Exception {
		Cipher cipher = Cipher.getInstance(CIPHER_ALGO_CLVSIM, "BC");
		cipher.init(Cipher.ENCRYPT_MODE, publicKey);
		return cipher.doFinal(data);
	}

	/** Como {@link #encryptOaepSha512} pero el resultado ya viene en Base64 (bytes ASCII). */
	public static byte[] encryptOaepSha512ToBase64(byte[] data, Key publicKey) throws Exception {
		return encodeBase64(encryptOaepSha512(data, publicKey));
	}

	/**
	 * Contraparte de descifrado de {@link #encryptOaepSha512} -- el simulador mismo nunca la
	 * necesita (sólo cifra el reto {@code ClvSim}, nunca lo descifra), pero el arnés de pruebas
	 * Java (specs 003/007, 2026-10-06) sí: para jugar el papel de minos de punta a punta tiene que
	 * poder descifrar la llave simétrica de sesión que el simulador le manda cifrada con SU llave
	 * pública. Mismo algoritmo/proveedor exactos que {@link #encryptOaepSha512} (inverso
	 * matemático de la misma transformación ya verificada contra minos real, no una afirmación
	 * nueva sobre minos).
	 */
	public static byte[] decryptOaepSha512(byte[] data, Key privateKey) throws Exception {
		Cipher cipher = Cipher.getInstance(CIPHER_ALGO_CLVSIM, "BC");
		cipher.init(Cipher.DECRYPT_MODE, privateKey);
		return cipher.doFinal(data);
	}

	/** Como {@link #decryptOaepSha512} pero decodificando Base64 primero -- contraparte de
	 *  {@link #encryptOaepSha512ToBase64}. */
	public static byte[] decryptOaepSha512FromBase64(byte[] base64Data, Key privateKey) throws Exception {
		return decryptOaepSha512(Base64.getDecoder().decode(base64Data), privateKey);
	}

	public static byte[] decrypt(byte[] data, Key privateKey) throws Exception {
		Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
		cipher.init(Cipher.DECRYPT_MODE, privateKey);
		return cipher.doFinal(data);
	}

	/** Como {@link #encrypt} pero el resultado ya viene en Base64 (bytes ASCII). */
	public static byte[] encryptToBase64(byte[] data, Key publicKey) throws Exception {
		return encodeBase64(encrypt(data, publicKey));
	}

	/** Decodifica Base64 y luego desencripta — contraparte de {@code CipherBase64.decryptEncodeRSAECB}. */
	public static byte[] decryptFromBase64(byte[] base64Data, Key privateKey) throws Exception {
		return decrypt(Base64.getDecoder().decode(base64Data), privateKey);
	}

	public static byte[] sign(byte[] data, PrivateKey privateKey) throws Exception {
		Signature sig = Signature.getInstance(SIGN_ALGO);
		sig.initSign(privateKey);
		sig.update(data);
		return sig.sign();
	}

	public static boolean verify(byte[] data, byte[] signature, PublicKey publicKey) throws Exception {
		Signature sig = Signature.getInstance(SIGN_ALGO);
		sig.initVerify(publicKey);
		sig.update(data);
		return sig.verify(signature);
	}

	/** {@link PSSParameterSpec} exacto que usa minos (ver nota de {@link #SIGN_ALGO_PSS}). */
	private static PSSParameterSpec minosPssSpec() {
		return new PSSParameterSpec("SHA-512", "MGF1", new MGF1ParameterSpec("SHA-512"), 64, 1);
	}

	/** BC primero siempre, igual que minos tras su propio fix de 2026-09-21 (ver CipherFactory/
	 *  CipherBase64 del repo minos) -- evita que el algoritmo elegido dependa de qué JDK corre
	 *  el jar/el jar del simulador. */
	private static Signature rsassaPssSignature() throws NoSuchAlgorithmException {
		try {
			return Signature.getInstance(SIGN_ALGO_PSS, BC_PROVIDER);
		} catch (NoSuchProviderException | NoSuchAlgorithmException e) {
			return Signature.getInstance(SIGN_ALGO_PSS);
		}
	}

	/** Firma con RSASSA-PSS/SHA-512 -- lo que minos realmente usa para el sobre de Abonos/
	 *  OrdenTopoV (ver {@link #SIGN_ALGO_PSS}), a diferencia de {@link #sign} (SHA256withRSA,
	 *  correcto sólo para ARA/ClvSim). */
	public static byte[] signRSASSAPSS(byte[] data, PrivateKey privateKey) throws Exception {
		Signature sig = rsassaPssSignature();
		sig.setParameter(minosPssSpec());
		sig.initSign(privateKey);
		sig.update(data);
		return sig.sign();
	}

	/** Contraparte de {@link #signRSASSAPSS} -- verifica lo que minos firmó con
	 *  {@code CipherBase64.sign512RSASSA}. */
	public static boolean verifyRSASSAPSS(byte[] data, byte[] signature, PublicKey publicKey) throws Exception {
		Signature sig = rsassaPssSignature();
		sig.setParameter(minosPssSpec());
		sig.initVerify(publicKey);
		sig.update(data);
		return sig.verify(signature);
	}

	/**
	 * Verifica una firma RSASSA-PSS/SHA-512 tal como la produce {@code CipherBase64.sign512RSASSA}
	 * en la rama {@code refactorClaude} del repo minos (util/impl/CipherBase64.java:229-247):
	 * digest SHA-512, MGF1 con SHA-512, longitud de sal = 64 (el tamaño del digest SHA-512), campo
	 * de cola (trailer field) = 1. La rama {@code master} de minos sigue firmando con
	 * {@code SHA256withRSA} (ver {@link #verify}) — qué rama corre cada instancia real de minos no
	 * es algo que este simulador pueda saber de antemano, así que {@link #verifyEitherScheme}
	 * intenta ambos esquemas en vez de asumir uno.
	 */
	public static boolean verifyRsassaPssSha512(byte[] data, byte[] signature, PublicKey publicKey) throws Exception {
		Signature sig = Signature.getInstance("RSASSA-PSS");
		int saltLength = MessageDigest.getInstance("SHA-512").getDigestLength();
		sig.setParameter(new PSSParameterSpec("SHA-512", "MGF1", new MGF1ParameterSpec("SHA-512"), saltLength, 1));
		sig.initVerify(publicKey);
		sig.update(data);
		return sig.verify(signature);
	}

	/**
	 * Prueba {@link #verifyRsassaPssSha512} y, si no verifica (o truena), cae a {@link #verify}
	 * ({@code SHA256withRSA}) — ver el porqué en el javadoc de {@link #verifyRsassaPssSha512}.
	 */
	public static boolean verifyEitherScheme(byte[] data, byte[] signature, PublicKey publicKey) throws Exception {
		try {
			if (verifyRsassaPssSha512(data, signature, publicKey)) {
				return true;
			}
		} catch (Exception ignored) {
			// esquema no aplica con esta firma/llave, se intenta el otro
		}
		return verify(data, signature, publicKey);
	}

	public static byte[] encodeBase64(byte[] data) {
		return Base64.getEncoder().encodeToString(data).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
	}

	public static byte[] decodeBase64(byte[] data) {
		return Base64.getDecoder().decode(data);
	}
}
