package mx.endcom.hermes.banxicosim.ara;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mx.endcom.hermes.banxicosim.crypto.RsaCipher;
import mx.endcom.hermes.banxicosim.crypto.SimulatorIdentity;
import mx.endcom.hermes.banxicosim.persistence.H2Store;
import mx.endcom.hermes.banxicosim.spei.SessionClosure;
import mx.endcom.hermes.banxicosim.wire.ByteReader;
import mx.endcom.hermes.banxicosim.wire.ByteWriter;

/**
 * Maneja una conexión del socket ARA (Fase 2 de 02_especificacion_tecnica.md &sect;9).
 *
 * Secuencia implementada (verificada contra el código real de minos, ver AraWireFraming,
 * AraOutputManagerServiceImpl y AraInputManagerServiceImpl del repo minos):
 * <ol>
 *   <li>minos conecta y manda {@code ConnUsr} (op 0x10, AraSignedMessage simétrico) con su
 *       número de certificado.</li>
 *   <li>El simulador manda {@code IdUsuarioAleat} (op 0xB7): un número aleatorio cifrado con la
 *       llave PÚBLICA de minos (pre-configurada, ver README — minos la necesita para poder
 *       desencriptar con su propia llave privada) y una firma propia del número plano.</li>
 *   <li>minos responde {@code IdFmaAleat} (op 0x4C): firma del número aleatorio (desencriptado
 *       con su llave privada) usando de nuevo su llave privada. El simulador la verifica con la
 *       llave pública de minos si la tiene configurada (no bloqueante si falla).</li>
 *   <li>El simulador manda {@code Logged} (op 0xFD, sólo encabezado) — login ARA completo.</li>
 *   <li>A partir de aquí, el simulador atiende {@code PideCrtNvo} (op 0x50) respondiendo con el
 *       propio certificado autofirmado ({@code RegCrtNvoFmt}, op 0xC3) cuando el número de
 *       certificado solicitado es el propio, o {@code CrtNoExiste} (op 0xC2) en cualquier otro
 *       caso.</li>
 * </ol>
 *
 * <p><b>Requisito operativo:</b> minos únicamente puede completar el paso 2-3 si el simulador
 * conoce la llave pública REAL de la instancia de minos bajo prueba (para cifrar el reto de forma
 * que minos pueda desencriptarlo con su propia llave privada) — ver README &sect;"Preparar minos".
 * Esto es equivalente a cómo funciona en producción: Banxico ya tiene registrado el certificado
 * del participante de antemano, no lo negocia en este socket.</p>
 */
public final class AraSession implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(AraSession.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Socket socket;
	private final SimulatorIdentity identity;
	private final PublicKey minosPublicKey; // puede ser null si no se configuró
	private final String minosCertificateNumber; // numero de certificado propio de minos (config)
	private final String minosCertificatePem; // mismo contenido que minosPublicKey, en PEM
	private final H2Store store;
	private final long runId;

	public AraSession(Socket socket, SimulatorIdentity identity, PublicKey minosPublicKey,
			String minosCertificateNumber, String minosCertificatePem, H2Store store) {
		this.socket = socket;
		this.identity = identity;
		this.minosPublicKey = minosPublicKey;
		this.minosCertificateNumber = minosCertificateNumber;
		this.minosCertificatePem = minosCertificatePem;
		this.store = store;
		this.runId = store.newRun("ARA", socket.getRemoteSocketAddress().toString());
	}

	@Override
	public void run() {
		try (socket;
				DataInputStream in = new DataInputStream(socket.getInputStream());
				DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
			logger.info("[ARA] Conexión entrante de {}", socket.getRemoteSocketAddress());
			while (!socket.isClosed()) {
				AraFrame frame = AraFrame.read(in);
				handle(frame, out);
			}
		} catch (Exception e) {
			if (e instanceof java.io.EOFException) {
				logger.info("[ARA] Conexión cerrada por minos ({})", socket.getRemoteSocketAddress());
			} else {
				logger.warn("[ARA] Sesión terminada con error: {}", e.getMessage());
			}
			// Spec 013: el cierre también queda en la bitácora, no solo en consola.
			SessionClosure c = SessionClosure.fromException(e);
			store.logEvent(runId, "INTERNO", "CierreSesion", 0, c.cause().code(), c.detail(), null);
		}
	}

	private void handle(AraFrame frame, DataOutputStream out) throws Exception {
		switch (frame.operation()) {
			case AraProtocol.OP_CONN_USR -> handleConnUsr(frame, out);
			case AraProtocol.OP_PIDE_CRT_NVO -> handlePideCrtNvo(frame, out);
			case AraProtocol.OP_ID_FMA_ALEAT -> handleIdFmaAleat(frame);
			case AraProtocol.OP_LOGOUT -> logger.info("[ARA] minos mandó Logout, cerrando");
			default -> logger.warn("[ARA] Código de operación no manejado: {}", frame.operation());
		}
	}

	private void handleConnUsr(AraFrame frame, DataOutputStream out) throws Exception {
		AraWireFraming.ParsedSignedBody parsed = AraWireFraming.parseSymmetricSignedBody(frame.body());
		String minosCertNumber = new ByteReader(parsed.content()).readCString();
		logger.info("[ARA] << ConnUsr, certificado de minos: {}", minosCertNumber);
		store.logEvent(runId, "IN", "ConnUsr", frame.operation(), "recibido",
				"certificadoMinos=" + minosCertNumber, frame.body());

		sendIdUsuarioAleat(out);
	}

	private byte[] pendingChallenge; // número aleatorio en claro, para verificar la respuesta

	private void sendIdUsuarioAleat(DataOutputStream out) throws Exception {
		if (minosPublicKey == null) {
			logger.warn("[ARA] No hay llave pública de minos configurada (minos.publicCert.path); "
					+ "el reto IdUsuarioAleat se manda igual pero minos NO podrá desencriptarlo. "
					+ "Ver README para configurarla.");
		}
		pendingChallenge = new byte[16];
		RANDOM.nextBytes(pendingChallenge);

		byte[] encryptedB64 = minosPublicKey != null
				? RsaCipher.encryptToBase64(pendingChallenge, minosPublicKey)
				: RsaCipher.encodeBase64(pendingChallenge); // relleno inutilizable si falta la llave

		byte[] ownSignatureB64 = RsaCipher.encodeBase64(RsaCipher.sign(pendingChallenge, identity.privateKey()));

		byte[] body = new ByteWriter()
				.writeIntBE(ownSignatureB64.length)
				.writeCString(new String(encryptedB64, java.nio.charset.StandardCharsets.US_ASCII))
				.writeCString(new String(ownSignatureB64, java.nio.charset.StandardCharsets.US_ASCII))
				.toByteArray();

		AraFrame.of(AraProtocol.OP_ID_USUARIO_ALEAT, body).writeTo(out);
		store.logEvent(runId, "OUT", "IdUsuarioAleat", AraProtocol.OP_ID_USUARIO_ALEAT, "enviado", null, body);
		logger.info("[ARA] >> IdUsuarioAleat (reto RSA)");
	}

	private void handleIdFmaAleat(AraFrame frame) {
		String sigB64 = new ByteReader(frame.body()).readCString();
		store.logEvent(runId, "IN", "IdFmaAleat", frame.operation(), "recibido", null, frame.body());
		boolean verified = false;
		if (minosPublicKey != null && pendingChallenge != null) {
			try {
				byte[] rawSig = RsaCipher.decodeBase64(sigB64.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
				verified = RsaCipher.verify(pendingChallenge, rawSig, minosPublicKey);
			} catch (Exception e) {
				logger.warn("[ARA] No fue posible verificar la firma de IdFmaAleat: {}", e.getMessage());
			}
		}
		logger.info("[ARA] << IdFmaAleat, firma verificada={}", verified);
		sendLogged();
	}

	private void sendLogged() {
		try {
			DataOutputStream out = new DataOutputStream(socket.getOutputStream());
			AraFrame.of(AraProtocol.OP_LOGGED, new byte[0]).writeTo(out);
			store.logEvent(runId, "OUT", "Logged", AraProtocol.OP_LOGGED, "enviado", "login ARA completo", null);
			logger.info("[ARA] >> Logged: login ARA completo");
		} catch (Exception e) {
			logger.error("[ARA] No fue posible enviar Logged: {}", e.getMessage());
		}
	}

	private void handlePideCrtNvo(AraFrame frame, DataOutputStream out) throws Exception {
		String requestedNumber = new ByteReader(frame.body()).readCString();
		logger.info("[ARA] << PideCrtNvo: {}", requestedNumber);
		store.logEvent(runId, "IN", "PideCrtNvo", frame.operation(), "recibido",
				"numero=" + requestedNumber, frame.body());

		if (requestedNumber.equals(identity.certificateNumber())) {
			sendRegCrtNvoFmt(out, identity.certificatePem());
		} else if (minosCertificatePem != null && requestedNumber.equals(minosCertificateNumber)) {
			// minos se pide a sí mismo (su propio certificado, declarado como entidad en EnSesion);
			// el simulador simplemente reenvía el mismo PEM que ya tiene configurado para el reto ARA.
			sendRegCrtNvoFmt(out, minosCertificatePem);
		} else {
			logger.warn("[ARA] Certificado {} desconocido para el simulador, respondo CrtNoExiste", requestedNumber);
			sendCrtNoExiste(out);
		}
	}

	private void sendRegCrtNvoFmt(DataOutputStream out, String certificatePem) throws Exception {
		LocalDateTime epoch = LocalDateTime.of(1970, 1, 1, 0, 0, 0);
		long expirySeconds = epoch.until(LocalDateTime.now().plusYears(5), java.time.temporal.ChronoUnit.SECONDS);
		long registeredSeconds = epoch.until(LocalDateTime.now(), java.time.temporal.ChronoUnit.SECONDS);
		long createdSeconds = registeredSeconds;

		// Contenido tal como lo parsea RegCrtNvoFmtMessage.java:32-36 en minos:
		// certificateStatus(2) + expiryDate(4) + registredAt(4) + createdAt(4) + certificate(cstring)
		byte[] content = new ByteWriter()
				.writeShortBE((short) 0) // 0 = vigente; minos no valida este valor en el flujo revisado
				.writeIntBE((int) expirySeconds)
				.writeIntBE((int) registeredSeconds)
				.writeIntBE((int) createdSeconds)
				.writeCString(certificatePem)
				.toByteArray();

		byte[] signedBody = AraWireFraming.buildSignedBody(content, identity.privateKey());
		AraFrame.of(AraProtocol.OP_REG_CRT_NVO_FMT, signedBody).writeTo(out);
		store.logEvent(runId, "OUT", "RegCrtNvoFmt", AraProtocol.OP_REG_CRT_NVO_FMT, "enviado", null, signedBody);
		logger.info("[ARA] >> RegCrtNvoFmt entregado");
	}

	private void sendCrtNoExiste(DataOutputStream out) throws Exception {
		byte[] signedBody = AraWireFraming.buildSignedBody(new byte[0], identity.privateKey());
		AraFrame.of(AraProtocol.OP_CRT_NO_EXISTE, signedBody).writeTo(out);
		store.logEvent(runId, "OUT", "CrtNoExiste", AraProtocol.OP_CRT_NO_EXISTE, "enviado", null, signedBody);
		logger.info("[ARA] >> CrtNoExiste");
	}
}
