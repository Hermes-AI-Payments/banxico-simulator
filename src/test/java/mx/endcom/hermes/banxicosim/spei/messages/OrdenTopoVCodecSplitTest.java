package mx.endcom.hermes.banxicosim.spei.messages;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * El detalle que arma minos (OrdenTopoV: {@code details.replace("|", "\0")}) termina con un separador final.
 * Hallazgo 2026-10-02: sin ignorarlo, {@code split(-1)} cuenta un campo vacío de más y el simulador rechaza
 * toda orden con "se esperaban 12 campos de detalle, se recibieron 13" (tipoPg 12 por la instancia C).
 */
class OrdenTopoVCodecSplitTest {

	@Test
	void detalleVacioNoTieneCampos() {
		assertEquals(0, OrdenTopoVCodec.splitDetailFields("").length);
	}

	@Test
	void ignoraElSeparadorFinalQueMandaMinos() {
		String detalle = "Ordenante\0" + "40\0" + "100000000000000001\0" + "XAXX010101000\0"
				+ "Beneficiario\0" + "40\0" + "200000000000000001\0" + "XAXX010101000\0"
				+ "Prueba de pago\0" + "0.0\0" + "9000001\0" + "123456\0";
		String[] campos = OrdenTopoVCodec.splitDetailFields(detalle);
		assertEquals(12, campos.length);
		assertEquals("123456", campos[11]);
	}

	@Test
	void conservaLosCamposVaciosIntermedios() {
		String[] campos = OrdenTopoVCodec.splitDetailFields("a\0\0c\0");
		assertArrayEquals(new String[] {"a", "", "c"}, campos);
	}

	@Test
	void conservaUnUltimoCampoVacioReal() {
		// "a\0\0" = campo "a", campo vacío, y el separador final de minos
		assertArrayEquals(new String[] {"a", ""}, OrdenTopoVCodec.splitDetailFields("a\0\0"));
	}

	@Test
	void sinSeparadorFinalTambienFunciona() {
		assertArrayEquals(new String[] {"a", "b"}, OrdenTopoVCodec.splitDetailFields("a\0b"));
	}
}
