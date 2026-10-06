package mx.endcom.hermes.banxicosim.testsupport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import mx.endcom.hermes.banxicosim.ara.AraFrame;
import mx.endcom.hermes.banxicosim.ara.AraProtocol;
import mx.endcom.hermes.banxicosim.ara.AraWireFraming;
import mx.endcom.hermes.banxicosim.crypto.AesCipher;
import mx.endcom.hermes.banxicosim.crypto.RsaCipher;
import mx.endcom.hermes.banxicosim.crypto.SimulatorIdentity;
import mx.endcom.hermes.banxicosim.spei.Frame;
import mx.endcom.hermes.banxicosim.spei.SpeiProtocol;
import mx.endcom.hermes.banxicosim.spei.messages.ClvSimCodec;
import mx.endcom.hermes.banxicosim.spei.messages.ReenvioCodec;
import mx.endcom.hermes.banxicosim.wire.ByteReader;
import mx.endcom.hermes.banxicosim.wire.ByteWriter;

/**
 * Cliente "minos falso": reconstrucción en Java del arnés de pruebas que las specs 003/007 (y la
 * parte H→B de 004) asumían -- ver {@code specs/README.md}, hallazgo 2026-09-21 ("el arnés
 * Python... nunca existió en el repo"). Juega el papel que normalmente juega {@code minos} del
 * otro lado de los dos sockets del simulador: genera su propia identidad RSA (ver
 * {@link SimuladorHarness}, que la crea y se la pasa al simulador como "la llave pública de
 * minos"), hace el login ARA completo (incluyendo {@code PideCrtNvo}), el reto {@code ClvSim}, y
 * {@code EnSesion}/{@code MsjCatalogos} del lado SPEI -- y puede mandar {@code Reenvio} con un
 * {@code processedBytes} arbitrario.
 *
 * <p>No es un codec ni forma parte del protocolo -- es la contraparte de prueba que faltaba para
 * poder ejercitar {@code SpeiSession}/{@code AraSession} de punta a punta sin depender de una
 * instancia real de minos.</p>
 */
public final class FakeMinosClient implements AutoCloseable {

	private final SimulatorIdentity fakeMinos;
	private final String host;

	private Socket speiSocket;
	private DataInputStream speiIn;
	private DataOutputStream speiOut;
	private byte[] sessionKey;
	private byte[] sessionIv;
	/** Bytes exactos (header + cuerpo) de cada frame mandado por el simulador durante el handshake
	 *  SPEI, en el orden en que se recibieron -- reconstruye el mismo historial que
	 *  {@code SentHistory} lleva del lado del simulador, para poder calcular posiciones de corte
	 *  conocidas y comparar el reenvío byte a byte (spec 003). */
	private final List<byte[]> framesDelSimulador = new ArrayList<>();

	private Socket araSocket;
	private DataInputStream araIn;
	private DataOutputStream araOut;
	private byte[] araPendingChallenge;

	public FakeMinosClient(SimulatorIdentity fakeMinos, String host) {
		this.fakeMinos = fakeMinos;
		this.host = host;
	}

	// ---- Canal SPEI ----

	public void connectSpei(int port) throws Exception {
		speiSocket = new Socket();
		speiSocket.connect(new InetSocketAddress(host, port), 5000);
		speiIn = new DataInputStream(speiSocket.getInputStream());
		speiOut = new DataOutputStream(speiSocket.getOutputStream());
	}

	/**
	 * Hace el handshake SPEI completo (Fases 1-3 de 02_especificacion_tecnica.md, en el orden real
	 * verificado contra minos -- ver el javadoc de {@code SpeiSession}): manda {@code Conexion},
	 * recibe {@code Greeting}/{@code SmLoginReq}, manda {@code Login}, recibe {@code EnSesion},
	 * completa el reto {@code ClvSim} (deriva la llave/IV de sesión y responde
	 * {@code RespClvSim}), y recibe {@code MsjCatalogos}. Al terminar, la sesión del simulador
	 * queda "viva" y este cliente puede mandar {@code Reenvio}.
	 */
	public void performSpeiHandshake(String username) throws Exception {
		Frame.of(SpeiProtocol.OP_CONEXION, new byte[0]).writeTo(speiOut);

		Frame greeting = readSpeiFrame();
		require(greeting, SpeiProtocol.OP_GREETING, "Greeting");

		Frame smLoginReq = readSpeiFrame();
		require(smLoginReq, SpeiProtocol.OP_SMLOGINREQ, "SmLoginReq");

		byte[] loginBody = new ByteWriter().writeCString(username).toByteArray();
		Frame.of(SpeiProtocol.OP_LOGIN, loginBody).writeTo(speiOut);

		Frame enSesion = readSpeiFrame();
		require(enSesion, SpeiProtocol.OP_ENSESION, "EnSesion");

		Frame clvSim = readSpeiFrame();
		require(clvSim, SpeiProtocol.OP_CLVSIM, "ClvSim");
		ClvSimCodec.ClvSimRequest parsed = ClvSimCodec.parse(clvSim.body(), fakeMinos.privateKey());
		this.sessionKey = parsed.key();
		this.sessionIv = parsed.iv();

		byte[] respClvSimBody = ClvSimCodec.buildRespClvSimBody(parsed.rawSymmetricKey(), fakeMinos.privateKey());
		Frame.of(SpeiProtocol.OP_RESP_CLVSIM, respClvSimBody).writeTo(speiOut);

		Frame msjCatalogos = readSpeiFrame();
		require(msjCatalogos, SpeiProtocol.OP_MSJCATALOGOS, "MsjCatalogos");
	}

	private void require(Frame frame, int expectedOp, String name) {
		if (frame.operation() != expectedOp) {
			throw new IllegalStateException("Se esperaba " + name + " (" + expectedOp + "), llegó " + frame.operation());
		}
	}

	/** Total de bytes (header + cuerpo) que el simulador ha mandado hasta ahora en esta sesión --
	 *  debe coincidir exactamente con {@code SentHistory.length()} del lado del simulador. */
	public long totalBytesDelSimulador() {
		long total = 0;
		for (byte[] f : framesDelSimulador) {
			total += f.length;
		}
		return total;
	}

	/** Posición absoluta (bytes) justo después de los primeros {@code frameCount} frames recibidos
	 *  -- un punto de corte conocido para probar {@code Reenvio} (spec 003). */
	public long offsetAfterFrames(int frameCount) {
		long offset = 0;
		for (int i = 0; i < frameCount; i++) {
			offset += framesDelSimulador.get(i).length;
		}
		return offset;
	}

	/** Los bytes exactos de los frames recibidos desde el índice {@code fromFrame} (inclusive) en
	 *  adelante, concatenados -- lo que se espera que {@code Reenvio(processedBytes=offsetAfterFrames(fromFrame))}
	 *  reenvíe. */
	public byte[] expectedBytesFromFrame(int fromFrame) {
		int total = 0;
		for (int i = fromFrame; i < framesDelSimulador.size(); i++) {
			total += framesDelSimulador.get(i).length;
		}
		byte[] result = new byte[total];
		int pos = 0;
		for (int i = fromFrame; i < framesDelSimulador.size(); i++) {
			byte[] f = framesDelSimulador.get(i);
			System.arraycopy(f, 0, result, pos, f.length);
			pos += f.length;
		}
		return result;
	}

	/** Manda {@code Reenvio} (spec 003) con el {@code processedBytes} dado -- cifrado AES de
	 *  sesión simple, sin particionar ni firmar (ver {@code ReenvioCodec}). */
	public void sendReenvio(int processedBytes) throws Exception {
		byte[] plaintext = ReenvioCodec.buildReenvioBody(LocalDateTime.now(), processedBytes);
		byte[] cipher = AesCipher.encrypt(plaintext, sessionKey, sessionIv);
		Frame.of(SpeiProtocol.OP_REENVIO, cipher).writeTo(speiOut);
	}

	/** Lee el siguiente frame crudo del socket SPEI -- se usa tanto durante el handshake como
	 *  después de {@link #sendReenvio} para leer los bytes reenviados (si los hay) y el
	 *  {@code FinReenvio} final. Cada frame leído se agrega a {@link #framesDelSimulador}: un
	 *  reenvío real repite bytes ya vistos, y eso es exactamente lo que también le pasa al
	 *  historial del lado del simulador (ver {@code SentHistory} -- un reenvío también cuenta como
	 *  "bytes mandados"), así que ambos lados se mantienen en sincronía. */
	public Frame readSpeiFrame() throws Exception {
		Frame frame = Frame.read(speiIn);
		framesDelSimulador.add(frame.toBytes());
		return frame;
	}

	public ReenvioCodec.FinReenvio decryptFinReenvio(Frame finReenvioFrame) throws Exception {
		require(finReenvioFrame, SpeiProtocol.OP_FINREENVIO, "FinReenvio");
		byte[] plaintext = AesCipher.decrypt(finReenvioFrame.body(), sessionKey, sessionIv);
		return ReenvioCodec.parseFinReenvio(plaintext);
	}

	// ---- Canal ARA ----

	public void connectAra(int port) throws Exception {
		araSocket = new Socket();
		araSocket.connect(new InetSocketAddress(host, port), 5000);
		araIn = new DataInputStream(araSocket.getInputStream());
		araOut = new DataOutputStream(araSocket.getOutputStream());
	}

	/**
	 * Hace el login ARA completo (Fase 2): manda {@code ConnUsr} con el número de certificado
	 * propio, resuelve el reto {@code IdUsuarioAleat} (desencripta el nonce con su propia llave
	 * privada y lo firma de vuelta en {@code IdFmaAleat}), y espera {@code Logged}.
	 */
	public void performAraLogin() throws Exception {
		byte[] content = new ByteWriter().writeCString(fakeMinos.certificateNumber()).toByteArray();
		byte[] sigB64 = RsaCipher.encodeBase64(RsaCipher.sign(content, fakeMinos.privateKey()));
		byte[] connUsrBody = new ByteWriter().writeIntBE(sigB64.length).writeBytes(content).writeBytes(sigB64)
				.toByteArray();
		AraFrame.of(AraProtocol.OP_CONN_USR, connUsrBody).writeTo(araOut);

		AraFrame idUsuarioAleat = AraFrame.read(araIn);
		if (idUsuarioAleat.operation() != AraProtocol.OP_ID_USUARIO_ALEAT) {
			throw new IllegalStateException(
					"Se esperaba IdUsuarioAleat (0xB7), llegó " + idUsuarioAleat.operation());
		}
		ByteReader r = new ByteReader(idUsuarioAleat.body());
		r.readIntBE(); // tamaño de la firma propia del simulador, no se usa aquí
		String encryptedB64 = r.readCString();
		r.readCString(); // firma propia del simulador sobre el nonce -- no se verifica (no es parte de los criterios)
		araPendingChallenge = RsaCipher.decryptFromBase64(
				encryptedB64.getBytes(StandardCharsets.US_ASCII), fakeMinos.privateKey());

		byte[] respSigB64 = RsaCipher.encodeBase64(RsaCipher.sign(araPendingChallenge, fakeMinos.privateKey()));
		byte[] idFmaAleatBody = new ByteWriter()
				.writeCString(new String(respSigB64, StandardCharsets.US_ASCII))
				.toByteArray();
		AraFrame.of(AraProtocol.OP_ID_FMA_ALEAT, idFmaAleatBody).writeTo(araOut);

		AraFrame logged = AraFrame.read(araIn);
		if (logged.operation() != AraProtocol.OP_LOGGED) {
			throw new IllegalStateException("Se esperaba Logged (0xFD), llegó " + logged.operation());
		}
	}

	/** Manda {@code PideCrtNvo} pidiendo el certificado con el número dado y regresa la respuesta
	 *  cruda (ya sea {@code RegCrtNvoFmt} o {@code CrtNoExiste}) -- spec 007. */
	public AraFrame requestCertificate(String requestedNumber) throws Exception {
		byte[] body = new ByteWriter().writeCString(requestedNumber).toByteArray();
		AraFrame.of(AraProtocol.OP_PIDE_CRT_NVO, body).writeTo(araOut);
		return AraFrame.read(araIn);
	}

	/** Certificado PEM (plus metadatos triviales) empacado dentro de {@code RegCrtNvoFmt} --
	 *  contraparte de {@code AraSession.sendRegCrtNvoFmt}: 2+4+4+4 bytes de metadatos seguidos del
	 *  certificado como cstring, firmado con el formato "con relleno" de {@code AraWireFraming}. */
	public record CertificadoRecibido(String certificatePem, boolean signatureVerified) {
	}

	public CertificadoRecibido parseRegCrtNvoFmt(AraFrame frame) throws Exception {
		if (frame.operation() != AraProtocol.OP_REG_CRT_NVO_FMT) {
			throw new IllegalStateException(
					"Se esperaba RegCrtNvoFmt (0xC3), llegó " + frame.operation());
		}
		AraWireFraming.ParsedSignedBody parsed = AraWireFraming.parsePaddedSignedBody(frame.body());
		ByteReader r = new ByteReader(parsed.content());
		r.readShortBE(); // certificateStatus
		r.readIntBE(); // expiryDate
		r.readIntBE(); // registeredAt
		r.readIntBE(); // createdAt
		String certificatePem = r.readCString();

		boolean verified = false;
		try {
			byte[] rawSig = RsaCipher.decodeBase64(parsed.signatureB64());
			verified = RsaCipher.verify(parsed.content(), rawSig, identityPublicKeyFor(certificatePem));
		} catch (Exception ignored) {
			// la verificación es un extra informativo, no un criterio de aceptación
		}
		return new CertificadoRecibido(certificatePem, verified);
	}

	private java.security.PublicKey identityPublicKeyFor(String certificatePem) throws Exception {
		return SimulatorIdentity.parseCertificatePem(certificatePem).getPublicKey();
	}

	@Override
	public void close() {
		closeQuietly(speiSocket);
		closeQuietly(araSocket);
	}

	private static void closeQuietly(Socket socket) {
		if (socket == null) {
			return;
		}
		try {
			socket.close();
		} catch (Exception ignored) {
			// cierre de prueba, mejor esfuerzo
		}
	}
}
