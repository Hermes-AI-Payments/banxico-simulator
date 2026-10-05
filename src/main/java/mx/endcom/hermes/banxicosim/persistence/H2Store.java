package mx.endcom.hermes.banxicosim.persistence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistencia H2 embebida de corridas de prueba (Fase 6 de 02_especificacion_tecnica.md &sect;9).
 *
 * <p>Guarda, por cada mensaje enviado o recibido en una sesión: tipo de mensaje, dirección,
 * código de operación, timestamp y un resumen de resultado (aceptado/rechazado/error), más los
 * bytes crudos en hexadecimal para depuración posterior. No usa un ORM — es JDBC directo sobre
 * H2 en modo archivo, igual de simple que lo que necesita una herramienta de pruebas (mismo
 * patrón de propósito que {@code hermes-conectividad-monitor}, sin replicar su implementación).</p>
 */
public final class H2Store implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(H2Store.class);

	private final Connection connection;
	private final AtomicLong runIdSeq = new AtomicLong();

	private H2Store(Connection connection) {
		this.connection = connection;
	}

	public static H2Store open(String dbPath) throws SQLException {
		Class<?> ignored;
		try {
			ignored = Class.forName("org.h2.Driver");
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("Driver H2 no disponible en el classpath", e);
		}
		// H2 exige una ruta explícitamente relativa (./) o absoluta -- no acepta "data/x" a secas.
		String normalizedPath = dbPath.startsWith("/") || dbPath.startsWith("./") || dbPath.startsWith("~")
				? dbPath
				: "./" + dbPath;
		Connection conn = DriverManager.getConnection("jdbc:h2:file:" + normalizedPath + ";AUTO_SERVER=TRUE");
		H2Store store = new H2Store(conn);
		store.initSchema();
		store.closeUnfinishedRuns();
		return store;
	}

	private void initSchema() throws SQLException {
		try (Statement st = connection.createStatement()) {
			st.execute("""
					CREATE TABLE IF NOT EXISTS test_run (
						id BIGINT PRIMARY KEY,
						started_at TIMESTAMP NOT NULL,
						remote_address VARCHAR(255),
						channel VARCHAR(16)
					)
					""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS test_event (
						id IDENTITY PRIMARY KEY,
						run_id BIGINT NOT NULL,
						occurred_at TIMESTAMP NOT NULL,
						direction VARCHAR(8) NOT NULL,
						message_name VARCHAR(64) NOT NULL,
						op_code INT,
						result VARCHAR(32),
						detail VARCHAR(4000),
						raw_hex CLOB
					)
					""");
			// Spec 014 -- estado del día operativo que debe sobrevivir una reconexión de la sesión
			// SPEI (motivo de rechazo 30 y saldo de Cargos tienen el mismo problema, ver specs/014
			// &sect;3/&sect;4: un solo mecanismo en H2 para los dos, no dos soluciones distintas).
			st.execute("""
					CREATE TABLE IF NOT EXISTS clave_rastreo_vista (
						operation_date DATE NOT NULL,
						tracking_key VARCHAR(30) NOT NULL,
						PRIMARY KEY (operation_date, tracking_key)
					)
					""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS dia_operativo (
						operation_date DATE PRIMARY KEY,
						balance DECIMAL(18,2) NOT NULL,
						reserved_balance DECIMAL(18,2) NOT NULL
					)
					""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS cargo_pendiente (
						id IDENTITY PRIMARY KEY,
						operation_date DATE NOT NULL,
						entity_index INT NOT NULL,
						entity_code INT NOT NULL,
						instruction_folio INT NOT NULL,
						internal_folio INT NOT NULL,
						amount DECIMAL(18,2) NOT NULL
					)
					""");
		}
	}

	public long newRun(String channel, String remoteAddress) {
		long id = runIdSeq.incrementAndGet() * 1000 + System.currentTimeMillis() % 1000;
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO test_run (id, started_at, remote_address, channel) VALUES (?, ?, ?, ?)")) {
			ps.setLong(1, id);
			ps.setTimestamp(2, java.sql.Timestamp.from(Instant.now()));
			ps.setString(3, remoteAddress);
			ps.setString(4, channel);
			ps.executeUpdate();
		} catch (SQLException e) {
			logger.warn("No fue posible registrar la corrida de prueba: {}", e.getMessage());
		}
		return id;
	}

	/**
	 * Spec 013 -- al arrancar no hay ninguna sesión viva, así que toda corrida sin
	 * {@code CierreSesion} terminó sin que quedara registrado cómo: apagado del simulador (el
	 * shutdown hook de H2 cierra la base en paralelo al de Main y puede ganarle; H2 no permite
	 * {@code DB_CLOSE_ON_EXIT=FALSE} junto con {@code AUTO_SERVER}), caída abrupta, o una corrida
	 * de una versión anterior. Se cierran como {@code sin-registro}, con la hora de su último evento.
	 */
	private void closeUnfinishedRuns() {
		try (PreparedStatement ps = connection.prepareStatement(
				"""
				INSERT INTO test_event (run_id, occurred_at, direction, message_name, op_code, result, detail, raw_hex)
				SELECT r.id, COALESCE((SELECT MAX(e.occurred_at) FROM test_event e WHERE e.run_id = r.id), r.started_at),
				       'INTERNO', 'CierreSesion', 0, 'sin-registro', ?, NULL
				FROM test_run r
				WHERE NOT EXISTS (SELECT 1 FROM test_event e WHERE e.run_id = r.id AND e.message_name = 'CierreSesion')
				""")) {
			ps.setString(1, "Cierre no registrado (apagado del simulador, caída o versión anterior a spec 013); "
					+ "hora = último evento de la corrida");
			int closed = ps.executeUpdate();
			if (closed > 0) {
				logger.info("{} corrida(s) sin cierre registrado marcadas como 'sin-registro' (spec 013)", closed);
			}
		} catch (SQLException e) {
			logger.warn("No fue posible cerrar las corridas pendientes: {}", e.getMessage());
		}
	}

	public void logEvent(long runId, String direction, String messageName, int opCode, String result,
			String detail, byte[] raw) {
		try (PreparedStatement ps = connection.prepareStatement(
				"""
				INSERT INTO test_event (run_id, occurred_at, direction, message_name, op_code, result, detail, raw_hex)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""")) {
			ps.setLong(1, runId);
			ps.setTimestamp(2, java.sql.Timestamp.from(Instant.now()));
			ps.setString(3, direction);
			ps.setString(4, messageName);
			ps.setInt(5, opCode);
			ps.setString(6, result);
			ps.setString(7, detail);
			ps.setString(8, raw == null ? null : bytesToHex(raw));
			ps.executeUpdate();
		} catch (SQLException e) {
			logger.warn("No fue posible registrar el evento de prueba {}: {}", messageName, e.getMessage());
		}
	}

	// ---- Spec 014: estado del día operativo (clave de rastreo vista, saldo, cargos pendientes) ----

	/** {@code true} si esa clave de rastreo ya se vio ese día operativo (motivo de rechazo 30,
	 *  specs/014 &sect;3) -- persistida en H2, no en memoria de la sesión, para que sobreviva una
	 *  reconexión. */
	public boolean claveYaVista(LocalDate operationDate, String trackingKey) {
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT 1 FROM clave_rastreo_vista WHERE operation_date = ? AND tracking_key = ?")) {
			ps.setDate(1, Date.valueOf(operationDate));
			ps.setString(2, trackingKey);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			logger.warn("No fue posible consultar clave de rastreo vista: {}", e.getMessage());
			return false; // igual que judeca real: nunca bloquear una orden por un fallo de la propia validación
		}
	}

	/** Marca una clave como vista -- solo se llama para órdenes ACEPTADAS (una orden rechazada por
	 *  otro motivo no "reserva" su clave, specs/014 &sect;3). */
	public void marcarClaveVista(LocalDate operationDate, String trackingKey) {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO clave_rastreo_vista (operation_date, tracking_key) VALUES (?, ?)")) {
			ps.setDate(1, Date.valueOf(operationDate));
			ps.setString(2, trackingKey);
			ps.executeUpdate();
		} catch (SQLException e) {
			logger.warn("No fue posible marcar la clave de rastreo {} como vista: {}", trackingKey, e.getMessage());
		}
	}

	/** Saldo del día operativo (tabla {@code dia_operativo}), specs/014 &sect;4. */
	public record SaldoDia(BigDecimal balance, BigDecimal reservedBalance) {
	}

	/** Lee el saldo vigente de {@code operationDate}; si es la primera vez que se pide ese día,
	 *  crea la fila con los valores iniciales dados y los devuelve. */
	public SaldoDia saldoDelDia(LocalDate operationDate, BigDecimal balanceInicial, BigDecimal reservedBalanceInicial) {
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT balance, reserved_balance FROM dia_operativo WHERE operation_date = ?")) {
			ps.setDate(1, Date.valueOf(operationDate));
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return new SaldoDia(rs.getBigDecimal("balance"), rs.getBigDecimal("reserved_balance"));
				}
			}
		} catch (SQLException e) {
			logger.warn("No fue posible leer el saldo del día {}: {}", operationDate, e.getMessage());
			return new SaldoDia(balanceInicial, reservedBalanceInicial);
		}
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO dia_operativo (operation_date, balance, reserved_balance) VALUES (?, ?, ?)")) {
			ps.setDate(1, Date.valueOf(operationDate));
			ps.setBigDecimal(2, balanceInicial);
			ps.setBigDecimal(3, reservedBalanceInicial);
			ps.executeUpdate();
		} catch (SQLException e) {
			logger.warn("No fue posible crear el saldo del día {}: {}", operationDate, e.getMessage());
		}
		return new SaldoDia(balanceInicial, reservedBalanceInicial);
	}

	/** Actualiza el saldo de un día operativo ya existente (se llama siempre después de
	 *  {@link #saldoDelDia}, que garantiza que la fila ya existe). */
	public void actualizarSaldo(LocalDate operationDate, BigDecimal nuevoBalance, BigDecimal nuevoReservedBalance) {
		try (PreparedStatement ps = connection.prepareStatement(
				"UPDATE dia_operativo SET balance = ?, reserved_balance = ? WHERE operation_date = ?")) {
			ps.setBigDecimal(1, nuevoBalance);
			ps.setBigDecimal(2, nuevoReservedBalance);
			ps.setDate(3, Date.valueOf(operationDate));
			ps.executeUpdate();
		} catch (SQLException e) {
			logger.warn("No fue posible actualizar el saldo del día {}: {}", operationDate, e.getMessage());
		}
	}

	/** Una orden aceptada en espera de liquidarse (modo {@code acumulado}, specs/014 &sect;4). */
	public record CargoPendiente(long id, int entityIndex, int entityCode, int instructionFolio, int internalFolio,
			BigDecimal amount) {
	}

	public void agregarCargoPendiente(LocalDate operationDate, int entityIndex, int entityCode,
			int instructionFolio, short internalFolio, BigDecimal amount) {
		try (PreparedStatement ps = connection.prepareStatement(
				"""
				INSERT INTO cargo_pendiente
					(operation_date, entity_index, entity_code, instruction_folio, internal_folio, amount)
				VALUES (?, ?, ?, ?, ?, ?)
				""")) {
			ps.setDate(1, Date.valueOf(operationDate));
			ps.setInt(2, entityIndex);
			ps.setInt(3, entityCode);
			ps.setInt(4, instructionFolio);
			ps.setInt(5, internalFolio);
			ps.setBigDecimal(6, amount);
			ps.executeUpdate();
		} catch (SQLException e) {
			logger.warn("No fue posible acumular el cargo pendiente: {}", e.getMessage());
		}
	}

	public List<CargoPendiente> listarCargosPendientes(LocalDate operationDate) {
		List<CargoPendiente> pendientes = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement(
				"""
				SELECT id, entity_index, entity_code, instruction_folio, internal_folio, amount
				FROM cargo_pendiente WHERE operation_date = ? ORDER BY id ASC
				""")) {
			ps.setDate(1, Date.valueOf(operationDate));
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					pendientes.add(new CargoPendiente(rs.getLong("id"), rs.getInt("entity_index"),
							rs.getInt("entity_code"), rs.getInt("instruction_folio"), rs.getInt("internal_folio"),
							rs.getBigDecimal("amount")));
				}
			}
		} catch (SQLException e) {
			logger.warn("No fue posible listar los cargos pendientes del día {}: {}", operationDate, e.getMessage());
		}
		return pendientes;
	}

	public void limpiarCargosPendientes(LocalDate operationDate) {
		try (PreparedStatement ps = connection.prepareStatement(
				"DELETE FROM cargo_pendiente WHERE operation_date = ?")) {
			ps.setDate(1, Date.valueOf(operationDate));
			ps.executeUpdate();
		} catch (SQLException e) {
			logger.warn("No fue posible limpiar los cargos pendientes del día {}: {}", operationDate, e.getMessage());
		}
	}

	/** Cabecera de una corrida de prueba (tabla {@code test_run}), para la API de control
	 *  ({@code GET /test-runs}). */
	public record TestRun(long id, Instant startedAt, String remoteAddress, String channel) {
	}

	/** Un evento (mensaje enviado/recibido) de una corrida (tabla {@code test_event}), para la
	 *  API de control ({@code GET /test-runs/{id}/events}). */
	public record TestEvent(long id, long runId, Instant occurredAt, String direction, String messageName,
			Integer opCode, String result, String detail, String rawHex) {
	}

	/** Todas las corridas de prueba registradas, más reciente primero. Lectura directa por
	 *  JDBC, mismo estilo que el resto de esta clase -- sin ORM. */
	public List<TestRun> listRuns() {
		List<TestRun> runs = new ArrayList<>();
		String sql = "SELECT id, started_at, remote_address, channel FROM test_run ORDER BY started_at DESC";
		try (PreparedStatement ps = connection.prepareStatement(sql);
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				runs.add(new TestRun(rs.getLong("id"), rs.getTimestamp("started_at").toInstant(),
						rs.getString("remote_address"), rs.getString("channel")));
			}
		} catch (SQLException e) {
			logger.warn("No fue posible listar las corridas de prueba: {}", e.getMessage());
		}
		return runs;
	}

	/** Eventos de una corrida específica, en orden cronológico. */
	public List<TestEvent> listEventsForRun(long runId) {
		List<TestEvent> events = new ArrayList<>();
		String sql = "SELECT id, run_id, occurred_at, direction, message_name, op_code, result, detail, raw_hex "
				+ "FROM test_event WHERE run_id = ? ORDER BY occurred_at ASC, id ASC";
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setLong(1, runId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					int opCode = rs.getInt("op_code");
					Integer opCodeOrNull = rs.wasNull() ? null : opCode;
					events.add(new TestEvent(rs.getLong("id"), rs.getLong("run_id"),
							rs.getTimestamp("occurred_at").toInstant(), rs.getString("direction"),
							rs.getString("message_name"), opCodeOrNull, rs.getString("result"),
							rs.getString("detail"), rs.getString("raw_hex")));
				}
			}
		} catch (SQLException e) {
			logger.warn("No fue posible listar los eventos de la corrida {}: {}", runId, e.getMessage());
		}
		return events;
	}

	private static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(String.format("%02X", b));
		}
		return sb.toString();
	}

	@Override
	public void close() throws SQLException {
		connection.close();
	}
}
