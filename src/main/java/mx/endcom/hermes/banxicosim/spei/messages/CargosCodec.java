package mx.endcom.hermes.banxicosim.spei.messages;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import mx.endcom.hermes.banxicosim.wire.ByteWriter;

/**
 * Construye el payload de {@code CargosMessage} (código 24, simulador→minos, spec 014) —
 * liquidación/saldo tras aceptar una o más órdenes de un {@code OrdenTopoV}. Verificado campo por
 * campo contra {@code core/spei/message/in/CargosMessage.java#loadProperties} (repo {@code mki},
 * vigente al 2026-09-28): {@code signLenght(4) → serverTimestamp(7) → operationDate(4) →
 * folio(4) → cargos(4, cantidad) → entityIndexes[cargos](1c/u) → entityCodes[cargos](4c/u) →
 * instructionFolios[cargos](4c/u) → internalFolios[cargos](2c/u) → amount(8) → balance(8) →
 * reservedBalance(8) → sign(signLenght)}.
 *
 * <p>{@code signLenght} y {@code sign} son el mismo desalineamiento ya documentado y resuelto en
 * {@link AbonosCodec} (son el {@code sigSize}/firma de la envoltura {@code WireFraming.signedBlock},
 * no un campo propio del payload) -- el payload de aquí NO los incluye, igual que {@code Abonos}.
 * La envoltura (cifrado+firma+prefijo) se arma aparte con
 * {@code WireFraming.buildEncryptedSignedPartitioned}, mismo patrón que {@code Abonos}.</p>
 */
public final class CargosCodec {

	private CargosCodec() {
	}

	/** Una entidad cargada dentro de este {@code Cargos} -- normalmente una por orden aceptada del
	 *  {@code OrdenTopoV} que disparó el cargo (ver specs/014 &sect;4, {@code folio} = su `folioPack`). */
	public record CargoEntry(int entityIndex, int entityCode, int instructionFolio, short internalFolio) {
	}

	public static byte[] buildPayload(LocalDate operationDate, int folio, List<CargoEntry> entries,
			BigDecimal amount, BigDecimal balance, BigDecimal reservedBalance) {
		ByteWriter w = new ByteWriter();
		w.writeDateTime(LocalDateTime.now());
		w.writeDate(operationDate);
		w.writeIntBE(folio);
		w.writeIntBE(entries.size());
		for (CargoEntry e : entries) {
			w.writeChar((char) e.entityIndex());
		}
		for (CargoEntry e : entries) {
			w.writeIntBE(e.entityCode());
		}
		for (CargoEntry e : entries) {
			w.writeIntBE(e.instructionFolio());
		}
		for (CargoEntry e : entries) {
			w.writeShortBE(e.internalFolio());
		}
		w.writeMoney(amount);
		w.writeMoney(balance);
		w.writeMoney(reservedBalance);
		return w.toByteArray();
	}
}
