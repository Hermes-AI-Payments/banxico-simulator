package mx.endcom.hermes.banxicosim.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Corregido 2026-10-12: {@code parseValue} no tenía caso para arreglos, así que el único body
 * documentado de {@code POST /pagos/cargos} ({@code "entradas": [...]}) era imposible de parsear
 * -- confirmado en vivo contra el simulador real antes de esta corrección. Estas pruebas fijan el
 * comportamiento para que no se repita en silencio.
 */
class JsonReaderTest {

	@Test
	void arregloDeNumeros() {
		Map<String, Object> obj = JsonReader.readObject("{\"valores\":[1,2,3]}");
		assertEquals(List.of(1L, 2L, 3L), obj.get("valores"));
	}

	@Test
	void arregloVacio() {
		Map<String, Object> obj = JsonReader.readObject("{\"entradas\":[]}");
		assertTrue(((List<?>) obj.get("entradas")).isEmpty());
	}

	@Test
	void arregloDeObjetos_formaRealDePagosCargos() {
		String body = "{\"folio\":9001,\"entradas\":[" +
				"{\"entityIndex\":1,\"entityCode\":90999,\"instructionFolio\":1,\"internalFolio\":9001,\"monto\":1.00}," +
				"{\"entityIndex\":1,\"entityCode\":90999,\"instructionFolio\":2,\"internalFolio\":9002,\"monto\":2.50}" +
				"]}";
		Map<String, Object> obj = JsonReader.readObject(body);
		assertEquals(9001L, obj.get("folio"));
		List<?> entradas = (List<?>) obj.get("entradas");
		assertEquals(2, entradas.size());
		@SuppressWarnings("unchecked")
		Map<String, Object> primera = (Map<String, Object>) entradas.get(0);
		assertEquals(90999L, primera.get("entityCode"));
		assertEquals(1.00, primera.get("monto"));
	}
}
