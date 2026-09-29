package mx.endcom.hermes.banxicosim.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuración del simulador. Orden de precedencia, de menor a mayor: valores por defecto →
 * archivo .properties (por defecto {@code config/simulator.properties}, sobreescribible con
 * {@code -Dconfig=<ruta>}) → variables de entorno.
 *
 * <p>Las variables de entorno existen para poder correr el contenedor Docker sin tener que montar
 * un archivo de configuración solo para cambiar un puerto o un código de entidad — se necesita un
 * archivo montado de cualquier forma para {@code minos-public-cert.pem}, pero el resto de los
 * parámetros son casos comunes al portar el simulador a otra máquina/CI. El nombre de cada
 * variable es la clave del .properties en mayúsculas con los puntos convertidos a guión bajo, p.
 * ej. {@code spei.port} &rarr; {@code SPEI_PORT}.</p>
 *
 * Ningún valor aquí es secreto de producción: son parámetros de un ambiente de pruebas propio
 * del simulador (ver AGENTS.md &sect;6 — política de datos sensibles).
 */
public final class SimConfig {

	private final Properties props;

	private SimConfig(Properties props) {
		this.props = props;
	}

	public static SimConfig load() throws IOException {
		Properties defaults = new Properties();
		defaults.setProperty("spei.port", "6001");
		defaults.setProperty("ara.port", "6002");
		defaults.setProperty("control.port", "8089");
		defaults.setProperty("identity.dir", "data/identity");
		// Vacío por defecto = versionado automático (ver SimulatorIdentity.loadOrCreate /
		// generateFreshCertificateNumber). Fijarlo a un valor explícito solo tiene sentido para
		// casos puntuales -- ver nota de clase en SimulatorIdentity.java, 2026-09-21.
		defaults.setProperty("identity.certificateNumber", "");
		defaults.setProperty("minos.publicCert.path", "config/minos-public-cert.pem");
		defaults.setProperty("own.entityCode", "90999");
		defaults.setProperty("own.entityIndex", "1");
		defaults.setProperty("minos.entityCode", "90646");
		defaults.setProperty("minos.entityIndex", "2");
		defaults.setProperty("minos.entityName", "MINOS-DEV");
		defaults.setProperty("minos.certificateNumber", "0000000002");
		defaults.setProperty("db.path", "data/banxicosim");
		defaults.setProperty("spei.user", "BXCOTEST");
		defaults.setProperty("catalogos.poblados", "false"); // spec 008: MsjCatalogos con contenido real
		// Spec 001: valor grande (65535) = comportamiento de siempre, minos nunca necesita partir
		// lo que le manda ni el simulador lo suyo. Bajarlo fuerza partición real de ambos lados --
		// es el mismo valor que se declara en EnSesion, que minos usa para decidir si fragmenta.
		defaults.setProperty("wire.maxMessageLength", "65535");
		// Spec 005 (capa A): timeout de lectura real de minos, base del cálculo de
		// red.variacion.retrasoMaximoMs -- documenta un hecho externo, no cambia comportamiento.
		defaults.setProperty("minos.readTimeoutMs", "6000");
		// Spec 005: reemplaza el 3s hardcodeado en SpeiSession.startHeartbeat().
		defaults.setProperty("spei.heartbeatIntervalMs", "3000");
		// Spec 005 &sect;4: minos.readTimeoutMs - spei.heartbeatIntervalMs - margen(500) = 2500.
		defaults.setProperty("red.variacion.retrasoMaximoMs", "2500");
		defaults.setProperty("red.variacion.duplicacionProbabilidadMaxima", "0.5");
		defaults.setProperty("red.variacion.duracionSegundosDefault", "60");
		defaults.setProperty("red.variacion.duracionSegundosMaxima", "900");

		Properties props = new Properties(defaults);
		String configPath = System.getProperty("config", "config/simulator.properties");
		Path path = Path.of(configPath);
		if (Files.exists(path)) {
			try (InputStream in = Files.newInputStream(path)) {
				props.load(in);
			}
		}
		applyEnvironmentOverrides(props, defaults);
		return new SimConfig(props);
	}

	/** Sobreescribe cada clave conocida si existe la variable de entorno correspondiente. */
	private static void applyEnvironmentOverrides(Properties props, Properties defaults) {
		for (String key : defaults.stringPropertyNames()) {
			String envName = key.toUpperCase(java.util.Locale.ROOT).replace('.', '_');
			String envValue = System.getenv(envName);
			if (envValue != null && !envValue.isBlank()) {
				props.setProperty(key, envValue);
			}
		}
	}

	public int speiPort() {
		return Integer.parseInt(props.getProperty("spei.port"));
	}

	public int araPort() {
		return Integer.parseInt(props.getProperty("ara.port"));
	}

	/** Puerto de la API de control HTTP (fuera del protocolo SPEI/ARA), ver paquete
	 *  {@code control}. */
	public int controlPort() {
		return Integer.parseInt(props.getProperty("control.port"));
	}

	public Path identityDir() {
		return Path.of(props.getProperty("identity.dir"));
	}

	/** Vacío/no configurado = versionado automático al generar una identidad nueva (ver
	 *  {@code SimulatorIdentity.loadOrCreate}). */
	public String ownCertificateNumber() {
		return props.getProperty("identity.certificateNumber");
	}

	public Path minosPublicCertPath() {
		return Path.of(props.getProperty("minos.publicCert.path"));
	}

	public int ownEntityCode() {
		return Integer.parseInt(props.getProperty("own.entityCode"));
	}

	public int ownEntityIndex() {
		return Integer.parseInt(props.getProperty("own.entityIndex"));
	}

	public int minosEntityCode() {
		return Integer.parseInt(props.getProperty("minos.entityCode"));
	}

	public int minosEntityIndex() {
		return Integer.parseInt(props.getProperty("minos.entityIndex"));
	}

	public String minosEntityName() {
		return props.getProperty("minos.entityName");
	}

	public String minosCertificateNumber() {
		return props.getProperty("minos.certificateNumber");
	}

	public String dbPath() {
		return props.getProperty("db.path");
	}

	public String speiUser() {
		return props.getProperty("spei.user");
	}

	/** Spec 008: si {@code true}, {@code MsjCatalogos} se manda con catálogos sintéticos
	 *  poblados en vez del cuerpo vacío de v1 -- ver {@code MsjCatalogosCodec.syntheticCatalogs()}. */
	public boolean catalogosPoblados() {
		return Boolean.parseBoolean(props.getProperty("catalogos.poblados"));
	}

	/** Spec 001: el mismo valor que se declara en {@code EnSesion} y que rige cuándo el simulador
	 *  parte sus propios envíos -- ver {@code WireFraming.buildEncryptedSignedPartitionedFrames}. */
	public int maxMessageLength() {
		return Integer.parseInt(props.getProperty("wire.maxMessageLength"));
	}

	/** Spec 005: timeout de lectura real que minos fija en su socket SPEI
	 *  ({@code SpeiSocketServiceImpl.java}, {@code setSoTimeout(6000)}) -- solo se usa para calcular
	 *  {@link #redVariacionRetrasoMaximoMs()}, no cambia ningún comportamiento por sí solo. */
	public long minosReadTimeoutMs() {
		return Long.parseLong(props.getProperty("minos.readTimeoutMs"));
	}

	/** Spec 005: intervalo real entre {@code AreYouAlive} -- reemplaza el 3s que antes estaba
	 *  hardcodeado en {@code SpeiSession.startHeartbeat()}. */
	public long heartbeatIntervalMs() {
		return Long.parseLong(props.getProperty("spei.heartbeatIntervalMs"));
	}

	/** Spec 005 &sect;4: retraso + jitter máximo combinado que puede pedir una variación de tipo
	 *  "retraso" -- {@code minosReadTimeoutMs() - heartbeatIntervalMs() - margen(500)}, literal en
	 *  config, no calculado en runtime. */
	public long redVariacionRetrasoMaximoMs() {
		return Long.parseLong(props.getProperty("red.variacion.retrasoMaximoMs"));
	}

	/** Spec 005 &sect;4: tope de probabilidad (0-1) para una variación de tipo "duplicacion". */
	public double redVariacionDuplicacionProbabilidadMaxima() {
		return Double.parseDouble(props.getProperty("red.variacion.duplicacionProbabilidadMaxima"));
	}

	/** Spec 005 &sect;4: duración (segundos) que toma una variación de red si no se especifica. */
	public long redVariacionDuracionSegundosDefault() {
		return Long.parseLong(props.getProperty("red.variacion.duracionSegundosDefault"));
	}

	/** Spec 005 &sect;4: duración máxima (segundos) de una variación de red -- ninguna queda
	 *  indefinida. */
	public long redVariacionDuracionSegundosMaxima() {
		return Long.parseLong(props.getProperty("red.variacion.duracionSegundosMaxima"));
	}
}
