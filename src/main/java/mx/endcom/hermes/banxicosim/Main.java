package mx.endcom.hermes.banxicosim;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PublicKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import mx.endcom.hermes.banxicosim.ara.AraServer;
import mx.endcom.hermes.banxicosim.config.SimConfig;
import mx.endcom.hermes.banxicosim.control.ControlServer;
import mx.endcom.hermes.banxicosim.crypto.SimulatorIdentity;
import mx.endcom.hermes.banxicosim.persistence.H2Store;
import mx.endcom.hermes.banxicosim.spei.RechazoForzadoRegistry;
import mx.endcom.hermes.banxicosim.spei.RedVariacionRegistry;
import mx.endcom.hermes.banxicosim.spei.SpeiServer;

/**
 * Punto de entrada del Simulador SPEI ("Banxico falso"). Arranca los dos servidores TCP
 * (SPEI principal y ARA) y una consola simple para disparar pruebas manuales (Fase 5).
 *
 * Ver AGENTS.md y README.md para el estado de avance por fase y cómo apuntar una instancia de
 * minos hacia este simulador.
 */
public final class Main {

	private static final Logger logger = LoggerFactory.getLogger(Main.class);

	private Main() {
	}

	public static void main(String[] args) throws Exception {
		SimConfig config = SimConfig.load();

		SimulatorIdentity identity = SimulatorIdentity.loadOrCreate(
				config.identityDir(), config.ownCertificateNumber());
		logger.info("Identidad propia lista. Número de certificado: {}", identity.certificateNumber());

		PublicKey minosPublicKey = null;
		String minosCertificatePem = null;
		if (Files.exists(config.minosPublicCertPath())) {
			minosCertificatePem = Files.readString(config.minosPublicCertPath(), StandardCharsets.US_ASCII);
			minosPublicKey = SimulatorIdentity.parseCertificatePem(minosCertificatePem).getPublicKey();
			logger.info("Llave pública de minos cargada desde {}", config.minosPublicCertPath());
		} else {
			logger.warn("No se encontró {} — el reto ARA (IdUsuarioAleat) y ClvSim NO funcionarán "
					+ "hasta que exportes el certificado público de tu instancia de minos ahí. "
					+ "Ver README.md.", config.minosPublicCertPath());
		}

		H2Store store = H2Store.open(config.dbPath());
		RedVariacionRegistry redVariacionRegistry = new RedVariacionRegistry(config); // spec 005, capa A
		RechazoForzadoRegistry rechazoForzadoRegistry = new RechazoForzadoRegistry(); // spec 014

		SpeiServer speiServer = new SpeiServer(config.speiPort(), identity, minosPublicKey, config, store,
				redVariacionRegistry, rechazoForzadoRegistry);
		AraServer araServer = new AraServer(config.araPort(), identity, minosPublicKey,
				config.minosCertificateNumber(), minosCertificatePem, store);

		Thread speiThread = new Thread(speiServer, "spei-server");
		Thread araThread = new Thread(araServer, "ara-server");
		speiThread.setDaemon(false);
		araThread.setDaemon(false);
		speiThread.start();
		araThread.start();

		ControlServer controlServer = new ControlServer(config.controlPort(), speiServer, store,
				redVariacionRegistry, rechazoForzadoRegistry, config);
		controlServer.start();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			logger.info("Apagando simulador...");
			controlServer.stop();
			speiServer.stop();
			araServer.stop();
			try {
				store.close();
			} catch (Exception ignored) {
				// apagado en curso
			}
		}));

		printBanner(config);
		runConsole(speiServer);
	}

	private static void printBanner(SimConfig config) {
		logger.info("==================================================================");
		logger.info(" Simulador SPEI (Banxico falso) listo");
		logger.info(" Puerto SPEI: {}   Puerto ARA: {}   API de control HTTP: {}",
				config.speiPort(), config.araPort(), config.controlPort());
		logger.info(" Comandos de consola: 'abono' | 'abono-invalido' | 'salir'");
		logger.info(" API de control: ver httpclient/*.http y README.md \"API de control y flujo de pruebas\"");
		logger.info("==================================================================");
	}

	private static void runConsole(SpeiServer speiServer) throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String cmd = line.trim().toLowerCase();
				switch (cmd) {
					case "abono" -> triggerAbono(speiServer, true);
					case "abono-invalido" -> triggerAbono(speiServer, false);
					case "salir", "exit", "quit" -> {
						logger.info("Cerrando por instrucción de consola...");
						System.exit(0);
					}
					case "" -> {
						// ignorar líneas vacías
					}
					default -> logger.info("Comando no reconocido: '{}'. Usa 'abono', 'abono-invalido' o 'salir'.", cmd);
				}
			}
		}
	}

	private static void triggerAbono(SpeiServer speiServer, boolean valid) {
		var session = speiServer.lastSession();
		if (session == null || !session.isAlive()) {
			logger.warn("No hay una sesión SPEI viva todavía — conecta minos primero.");
			return;
		}
		session.sendTestAbono(valid);
	}
}
